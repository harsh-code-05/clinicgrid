package com.harsh.hospitalmanagement.storage;

import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Kernel32Util;
import com.sun.jna.platform.win32.Win32Exception;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.FileAttribute;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

import static com.sun.jna.platform.win32.AccCtrl.SE_OBJECT_TYPE.SE_FILE_OBJECT;
import static com.sun.jna.platform.win32.WinError.ERROR_FILE_NOT_FOUND;
import static com.sun.jna.platform.win32.WinError.ERROR_PATH_NOT_FOUND;

/** Operations on an existing, provisioned local NTFS directory. */
final class WindowsPrivateFiles implements PrivateFiles {

    // Win32 FILE_INFO_BY_HANDLE_CLASS values.
    private static final int STANDARD_INFO = 1;
    private static final int DISPOSITION_INFO = 4;
    private static final int ATTRIBUTE_TAG_INFO = 9;

    private final Path root;
    private final String volumeId;
    private final String nativeVolumeId;
    private final Set<String> trustedSids;
    private final FileAttribute<List<AclEntry>> privateAcl;

    public WindowsPrivateFiles(Path root) throws IOException {
        this(root, null);
    }

    public WindowsPrivateFiles(Path root, String customVolumeId) throws IOException {
        if (!Platform.isWindows() || root == null || !root.isAbsolute() || root.getNameCount() == 0
                || !root.getRoot().toString().matches("[A-Za-z]:\\\\") || !root.equals(root.normalize())) {
            throw new IOException("Private storage requires an absolute local Windows directory");
        }
        for (Path component : root) {
            if (component.toString().endsWith(".") || component.toString().endsWith(" ")) {
                throw new IOException("Private storage directory must use canonical path components");
            }
        }
        this.root = root;
        var account = processAccount();
        trustedSids = Set.of(account.sidString);
        var principal = root.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(account.fqn);
        var entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(principal)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
        privateAcl = new FileAttribute<>() {
            @Override public String name() { return "acl:acl"; }
            @Override public List<AclEntry> value() { return List.of(entry); }
        };
        nativeVolumeId = volumeId(root);
        volumeId = customVolumeId != null ? customVolumeId : nativeVolumeId;
        try (var ignored = pinDirectories(root, false)) {
            if (!root.equals(root.toRealPath(LinkOption.NOFOLLOW_LINKS))
                    || !"NTFS".equalsIgnoreCase(Files.getFileStore(root).type())) {
                throw new IOException("Private storage requires a canonical NTFS directory");
            }
            requireOutsideProject(root);
        }
    }

    @Override
    public String volumeId() {
        return volumeId;
    }

    @Override
    public void writeNew(Path relative, byte[] bytes) throws IOException {
        Path target = resolve(relative);
        try (var ignored = pinDirectories(target.getParent(), true)) {
            boolean created = false;
            try (var file = FileChannel.open(target,
                    Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS), privateAcl)) {
                created = true;
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    if (file.write(buffer) == 0) {
                        throw new IOException("Private storage write did not make progress");
                    }
                }
                file.force(true);
            } catch (IOException | RuntimeException failure) {
                if (created) {
                    try {
                        deletePinned(target);
                    } catch (IOException | RuntimeException cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
                throw failure;
            }
        }
    }

    @Override
    public byte[] read(Path relative, int maxBytes) throws IOException {
        Path target = resolve(relative);
        try (var ignored = pinDirectories(target.getParent(), false);
             var file = pin(target, false, true, WinNT.GENERIC_READ)) {
            var standard = new WinBase.FILE_STANDARD_INFO();
            query(file.handle(), STANDARD_INFO, standard);
            long length = standard.EndOfFile.getValue();
            if (length == 0 || length > maxBytes) {
                throw new IOException("Private file has an invalid size");
            }
            byte[] bytes = new byte[(int) length];
            var bytesRead = new IntByReference();
            if (!Kernel32.INSTANCE.ReadFile(file.handle(), bytes, (int) length, bytesRead, null)
                    || bytesRead.getValue() != length) {
                throw winFailure("Private file could not be read from pinned handle", Kernel32.INSTANCE.GetLastError());
            }
            return bytes;
        }
    }

    @Override
    public boolean exists(Path relative) throws IOException {
        Path target = resolve(relative);
        try (var ignored = pinDirectories(target.getParent(), false);
             var file = pin(target, false, true, 0)) {
            return true;
        } catch (NoSuchFileException missing) {
            return false;
        }
    }

    @Override
    public boolean delete(Path relative) throws IOException {
        Path target = resolve(relative);
        try (var ignored = pinDirectories(target.getParent(), false)) {
            deletePinned(target);
            return true;
        } catch (NoSuchFileException missing) {
            return false;
        }
    }

    @FunctionalInterface
    interface DirectoryLister {
        DirectoryStream<Path> open(Path dir) throws IOException;
    }

    @Override
    public FileScanResult scanFiles(Path relativePrefix, String cursor, int maxEntries) throws IOException {
        return scanFiles(relativePrefix, cursor, maxEntries, Math.max(maxEntries * 8, 2000),
                Files::newDirectoryStream);
    }

    FileScanResult scanFiles(Path relativePrefix, String cursor, int maxEntries, int maxVisited) throws IOException {
        return scanFiles(relativePrefix, cursor, maxEntries, maxVisited, Files::newDirectoryStream);
    }

    FileScanResult scanFiles(
            Path relativePrefix,
            String cursor,
            int maxEntries,
            int maxVisited,
            DirectoryLister directoryLister
    ) throws IOException {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        if (maxVisited < 4) {
            throw new IllegalArgumentException("maxVisited must be at least 4");
        }
        DirectoryLister lister = directoryLister != null ? directoryLister : Files::newDirectoryStream;
        Path base = resolve(relativePrefix);
        String prefixRel = relativePrefix.toString().replace('\\', '/');
        String normalizedCursor = (cursor != null && !cursor.isBlank()) ? cursor.replace('\\', '/') : null;
        try (var ignored = pinDirectories(base, false)) {
            List<FileEntry> entries = new ArrayList<>(Math.min(maxEntries, 128));
            var state = new ScanState(parseCursorSegments(prefixRel, normalizedCursor), maxEntries, maxVisited);
            scanDirectory(base, prefixRel, 0, List.of(), entries, state, lister);
            String nextCursor = state.hasMore ? state.formatCursor(prefixRel) : null;
            if (entries.isEmpty() && !state.hasMore) {
                return FileScanResult.EMPTY;
            }
            return new FileScanResult(List.copyOf(entries), nextCursor, state.hasMore);
        } catch (NoSuchFileException missingPrefixDir) {
            return FileScanResult.EMPTY;
        }
    }

    private void scanDirectory(
            Path dir,
            String dirRel,
            int depth,
            List<String> dirSegs,
            List<FileEntry> entries,
            ScanState state,
            DirectoryLister directoryLister
    ) throws IOException {
        if (depth > 2) {
            return;
        }
        // Maintain a bounded max-heap of only (remainingVisited + 1) smallest unskipped names so enumeration
        // memory stays O(maxVisited) regardless of directory size AND completeness never depends on OS order.
        int cap = Math.max(1, state.maxVisited - state.visited) + 1;
        PriorityQueue<String> smallestCandidates = new PriorityQueue<>(cap, (a, b) -> compareNtfsName(b, a));
        try (DirectoryStream<Path> stream = directoryLister.open(dir)) {
            for (Path child : stream) {
                String name = child.getFileName().toString();
                List<String> childSegs = appendSegment(dirSegs, name);
                if (state.shouldSkip(childSegs)) {
                    continue;
                }
                if (smallestCandidates.size() < cap) {
                    smallestCandidates.offer(name);
                } else if (compareNtfsName(name, smallestCandidates.peek()) < 0) {
                    smallestCandidates.poll();
                    smallestCandidates.offer(name);
                }
            }
        } catch (NoSuchFileException missingDir) {
            if (depth == 0) {
                throw missingDir;
            }
            return;
        }

        List<String> orderedCandidates = new ArrayList<>(smallestCandidates);
        orderedCandidates.sort(WindowsPrivateFiles::compareNtfsName);

        for (String name : orderedCandidates) {
            if (state.stop) {
                return;
            }
            List<String> childSegs = appendSegment(dirSegs, name);
            if (entries.size() >= state.maxEntries || state.visited >= state.maxVisited) {
                state.hasMore = true;
                state.stop = true;
                return;
            }
            state.visited++;

            String childRel = dirRel + "/" + name;
            Path childPath = dir.resolve(name);
            try (var handleCheck = openScanHandle(childPath)) {
                if (handleCheck.isDirectory()) {
                    if (depth >= 2) {
                        state.markCompleted(childSegs, true);
                        continue;
                    }
                    requirePrivateAcl(handleCheck.handle(), false);
                    scanDirectory(childPath, childRel, depth + 1, childSegs, entries, state, directoryLister);
                    if (!state.stop) {
                        state.markCompleted(childSegs, true);
                    }
                } else {
                    if (depth != 2) {
                        state.markCompleted(childSegs, false);
                        continue;
                    }
                    var standard = new WinBase.FILE_STANDARD_INFO();
                    query(handleCheck.handle(), STANDARD_INFO, standard);
                    if (standard.NumberOfLinks != 1) {
                        throw new IOException("Private storage refuses hard-linked files");
                    }
                    var lastWrite = new WinBase.FILETIME();
                    if (!Kernel32.INSTANCE.GetFileTime(handleCheck.handle(), null, null, lastWrite)) {
                        throw winFailure("Unable to inspect private file timestamp",
                                Kernel32.INSTANCE.GetLastError());
                    }
                    entries.add(new FileEntry(Path.of(childRel), lastWrite.toDate().toInstant()));
                    state.markCompleted(childSegs, false);
                }
            } catch (NoSuchFileException disappeared) {
                // Entry deleted concurrently between listing and pinning.
                state.markCompleted(childSegs, false);
            }
        }
    }

    private static List<String> appendSegment(List<String> prefix, String segment) {
        List<String> next = new ArrayList<>(prefix.size() + 1);
        next.addAll(prefix);
        next.add(segment);
        return List.copyOf(next);
    }

    private static List<String> parseCursorSegments(String prefixRel, String normalizedCursor) {
        if (normalizedCursor == null) {
            return List.of();
        }
        String prefixWithSlash = prefixRel + "/";
        if (!normalizedCursor.startsWith(prefixWithSlash)) {
            return List.of();
        }
        String tail = normalizedCursor.substring(prefixWithSlash.length());
        while (tail.endsWith("/")) {
            tail = tail.substring(0, tail.length() - 1);
        }
        if (tail.isEmpty()) {
            return List.of();
        }
        String[] parts = tail.split("/");
        for (String part : parts) {
            if (part.isEmpty()) {
                return List.of();
            }
        }
        return List.of(parts);
    }

    static int compareNtfsName(String a, String b) {
        int len1 = a.length();
        int len2 = b.length();
        int lim = Math.min(len1, len2);
        for (int i = 0; i < lim; i++) {
            char c1 = Character.toUpperCase(a.charAt(i));
            char c2 = Character.toUpperCase(b.charAt(i));
            if (c1 != c2) {
                return c1 - c2;
            }
        }
        return len1 - len2;
    }

    private ScanHandle openScanHandle(Path path) throws IOException {
        var handle = Kernel32.INSTANCE.CreateFile(path.toString(),
                WinNT.FILE_LIST_DIRECTORY | WinNT.FILE_READ_ATTRIBUTES | WinNT.READ_CONTROL,
                WinNT.FILE_SHARE_READ, null, WinNT.OPEN_EXISTING,
                WinNT.FILE_FLAG_BACKUP_SEMANTICS | WinNT.FILE_FLAG_OPEN_REPARSE_POINT, null);
        if (handle == null || WinBase.INVALID_HANDLE_VALUE.equals(handle)) {
            int error = Kernel32.INSTANCE.GetLastError();
            if (error == ERROR_FILE_NOT_FOUND || error == ERROR_PATH_NOT_FOUND) {
                throw new NoSuchFileException("Private scan entry is missing");
            }
            throw winFailure("Private scan entry is unavailable", error);
        }
        var pin = new Pin(handle);
        try {
            var tag = new WinBase.FILE_ATTRIBUTE_TAG_INFO();
            query(handle, ATTRIBUTE_TAG_INFO, tag);
            int attributes = tag.FileAttributes;
            if ((attributes & WinNT.FILE_ATTRIBUTE_REPARSE_POINT) != 0
                    || Kernel32.INSTANCE.GetFileType(handle) != WinNT.FILE_TYPE_DISK) {
                throw new IOException("Private storage refuses reparse points or unexpected file types");
            }
            boolean isDirectory = (attributes & WinNT.FILE_ATTRIBUTE_DIRECTORY) != 0;
            return new ScanHandle(pin, isDirectory);
        } catch (IOException | RuntimeException failure) {
            try {
                pin.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    private record ScanHandle(Pin pin, boolean isDirectory) implements AutoCloseable {
        WinNT.HANDLE handle() {
            return pin.handle();
        }

        @Override
        public void close() throws IOException {
            pin.close();
        }
    }

    private static final class ScanState {
        private final List<String> cursorSegments;
        private final int maxEntries;
        private final int maxVisited;
        private int visited;
        private boolean hasMore;
        private boolean stop;
        private List<String> lastCompletedSegments;
        private boolean lastCompletedDirectory;

        private ScanState(List<String> cursorSegments, int maxEntries, int maxVisited) {
            this.cursorSegments = cursorSegments;
            this.maxEntries = maxEntries;
            this.maxVisited = maxVisited;
        }

        private boolean shouldSkip(List<String> nodeSegs) {
            if (cursorSegments.isEmpty()) {
                return false;
            }
            int common = Math.min(nodeSegs.size(), cursorSegments.size());
            for (int i = 0; i < common; i++) {
                int cmp = compareNtfsName(nodeSegs.get(i), cursorSegments.get(i));
                if (cmp < 0) {
                    return true;
                }
                if (cmp > 0) {
                    return false;
                }
            }
            return nodeSegs.size() >= cursorSegments.size();
        }

        private void markCompleted(List<String> segments, boolean directory) {
            this.lastCompletedSegments = segments;
            this.lastCompletedDirectory = directory;
        }

        private String formatCursor(String prefixRel) {
            if (lastCompletedSegments == null || lastCompletedSegments.isEmpty()) {
                return null;
            }
            return prefixRel + "/" + String.join("/", lastCompletedSegments)
                    + (lastCompletedDirectory ? "/" : "");
        }
    }

    private void deletePinned(Path target) throws IOException {
        try (var file = pin(target, false, true, WinNT.DELETE)) {
            var disposition = new WinBase.FILE_DISPOSITION_INFO(true);
            disposition.write();
            if (!Kernel32.INSTANCE.SetFileInformationByHandle(file.handle, DISPOSITION_INFO,
                    disposition.getPointer(), new DWORD(disposition.size()))) {
                throw winFailure("Private file could not be deleted", Kernel32.INSTANCE.GetLastError());
            }
        }
    }

    private Path resolve(Path relative) throws IOException {
        Path resolved = root.resolve(relative).normalize();
        if (relative.isAbsolute() || !relative.equals(relative.normalize()) || resolved.equals(root)
                || !resolved.startsWith(root)) {
            throw new IOException("Invalid private storage address");
        }
        return resolved;
    }

    Pins pinDirectories(Path directory, boolean create) throws IOException {
        if (!nativeVolumeId.equalsIgnoreCase(volumeId(root))) {
            throw new IOException("Private storage volume is unavailable");
        }
        var pins = new Pins();
        Path current = directory.getRoot();
        try {
            pins.add(pin(current, true, false, 0));
            for (Path component : directory) {
                current = current.resolve(component);
                boolean inside = current.startsWith(root);
                if (create && inside && !current.equals(root)) {
                    try {
                        Files.createDirectory(current, privateAcl);
                    } catch (FileAlreadyExistsException existing) {
                        // The handle and ACL checks below also apply to existing entries.
                    }
                }
                pins.add(pin(current, true, inside, 0));
            }
            return pins;
        } catch (IOException | RuntimeException failure) {
            try {
                pins.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            if (failure instanceof NoSuchFileException && (!current.startsWith(root) || current.equals(root))) {
                throw new IOException("Private storage directory is unavailable", failure);
            }
            throw failure;
        }
    }

    private static final Set<String> TRUSTED_ANCESTOR_OWNERS = Set.of(
            "S-1-5-18", // SYSTEM
            "S-1-5-32-544", // BUILTIN\Administrators
            "S-1-5-80-956008885-3418522649-1831038044-1853292631-2271478464" // TrustedInstaller
    );

    private Pin pin(Path path, boolean directory, boolean checkAcl, int access) throws IOException {
        // Pin each directory with FILE_LIST_DIRECTORY without write/delete sharing so it cannot be renamed or substituted.
        int desiredAccess = (directory ? WinNT.FILE_LIST_DIRECTORY : 0)
                | WinNT.FILE_READ_ATTRIBUTES
                | WinNT.READ_CONTROL
                | access;
        var handle = Kernel32.INSTANCE.CreateFile(path.toString(),
                desiredAccess,
                WinNT.FILE_SHARE_READ, null, WinNT.OPEN_EXISTING,
                WinNT.FILE_FLAG_BACKUP_SEMANTICS | WinNT.FILE_FLAG_OPEN_REPARSE_POINT, null);
        if (handle == null || WinBase.INVALID_HANDLE_VALUE.equals(handle)) {
            int error = Kernel32.INSTANCE.GetLastError();
            if (error == ERROR_FILE_NOT_FOUND || error == ERROR_PATH_NOT_FOUND) {
                throw new NoSuchFileException("Private file or directory is missing");
            }
            throw winFailure("Private file or directory is unavailable", error);
        }
        var pin = new Pin(handle);
        try {
            var tag = new WinBase.FILE_ATTRIBUTE_TAG_INFO();
            query(handle, ATTRIBUTE_TAG_INFO, tag);
            int attributes = tag.FileAttributes;
            if ((attributes & WinNT.FILE_ATTRIBUTE_REPARSE_POINT) != 0
                    || ((attributes & WinNT.FILE_ATTRIBUTE_DIRECTORY) != 0) != directory
                    || Kernel32.INSTANCE.GetFileType(handle) != WinNT.FILE_TYPE_DISK) {
                throw new IOException("Private storage refuses reparse points or unexpected file types");
            }
            if (!directory) {
                var standard = new WinBase.FILE_STANDARD_INFO();
                query(handle, STANDARD_INFO, standard);
                if (standard.NumberOfLinks != 1) {
                    throw new IOException("Private storage refuses hard-linked files");
                }
            }
            if (checkAcl) {
                requirePrivateAcl(handle, path.equals(root));
            } else if (directory && !path.startsWith(root)) {
                requireAncestorSecurity(handle);
            }
            return pin;
        } catch (IOException | RuntimeException failure) {
            try {
                pin.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    private void requireAncestorSecurity(WinNT.HANDLE handle) throws IOException {
        var descriptor = new PointerByReference();
        int error = Advapi32.INSTANCE.GetSecurityInfo(handle, SE_FILE_OBJECT,
                WinNT.OWNER_SECURITY_INFORMATION,
                null, null, null, null, descriptor);
        if (error != 0) {
            throw winFailure("Unable to read ancestor owner", error);
        }
        try {
            var security = new WinNT.SECURITY_DESCRIPTOR_RELATIVE(descriptor.getValue());
            var owner = security.getOwner();
            String ownerSid = owner != null ? owner.getSidString() : null;
            if (ownerSid == null || (!TRUSTED_ANCESTOR_OWNERS.contains(ownerSid) && !trustedSids.contains(ownerSid))) {
                throw new IOException("Private storage ancestor has an untrusted owner: " + ownerSid);
            }
        } finally {
            Kernel32Util.freeLocalMemory(descriptor.getValue());
        }
    }

    private void requirePrivateAcl(WinNT.HANDLE handle, boolean requireProtected) throws IOException {
        var descriptor = new PointerByReference();
        int error = Advapi32.INSTANCE.GetSecurityInfo(handle, SE_FILE_OBJECT,
                WinNT.OWNER_SECURITY_INFORMATION | WinNT.DACL_SECURITY_INFORMATION,
                null, null, null, null, descriptor);
        if (error != 0) {
            throw winFailure("Unable to read private storage permissions", error);
        }
        try {
            var security = new WinNT.SECURITY_DESCRIPTOR_RELATIVE(descriptor.getValue());
            var acl = security.getDiscretionaryACL();
            if (!trustedSids.contains(security.getOwner().getSidString()) || acl == null || acl.AceCount == 0
                    || (requireProtected && (security.Control & WinNT.SE_DACL_PROTECTED) == 0)) {
                throw new IOException("Private storage requires a protected ACL and a trusted owner");
            }
            for (var ace : acl.getACEs()) {
                if (ace instanceof WinNT.ACCESS_DENIED_ACE) {
                    continue;
                }
                if (!(ace instanceof WinNT.ACCESS_ALLOWED_ACE allowed) || !trustedSids.contains(allowed.getSidString())) {
                    throw new IOException("Private storage ACL grants untrusted access");
                }
            }
        } finally {
            Kernel32Util.freeLocalMemory(descriptor.getValue());
        }
    }

    private static void query(WinNT.HANDLE handle, int informationClass, com.sun.jna.Structure info) throws IOException {
        if (!Kernel32.INSTANCE.GetFileInformationByHandleEx(handle, informationClass, info.getPointer(), new DWORD(info.size()))) {
            throw winFailure("Unable to inspect a private storage handle", Kernel32.INSTANCE.GetLastError());
        }
        info.read();
    }

    private static String volumeId(Path path) throws IOException {
        String drive = path.getRoot().toString();
        char[] volume = new char[64];
        if (Kernel32.INSTANCE.GetDriveType(drive) != WinBase.DRIVE_FIXED
                || !Kernel32.INSTANCE.GetVolumeNameForVolumeMountPoint(drive, volume, volume.length)) {
            throw new IOException("Private storage requires a mounted local fixed volume");
        }
        return Native.toString(volume);
    }

    private static Advapi32Util.Account processAccount() throws IOException {
        var token = new WinNT.HANDLEByReference();
        if (!Advapi32.INSTANCE.OpenProcessToken(Kernel32.INSTANCE.GetCurrentProcess(), WinNT.TOKEN_QUERY, token)) {
            throw winFailure("Unable to identify the Windows service account", Kernel32.INSTANCE.GetLastError());
        }
        try (var ignored = new Pin(token.getValue())) {
            return Advapi32Util.getTokenAccount(token.getValue());
        } catch (Win32Exception failure) {
            throw winFailure("Unable to identify the Windows service account", failure.getErrorCode());
        }
    }

    private static void requireOutsideProject(Path directory) throws IOException {
        for (Path current = directory; current != null; current = current.getParent()) {
            try {
                Files.readAttributes(current.resolve(".git"), java.nio.file.attribute.BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                throw new IOException("Private storage cannot be inside a Git checkout");
            } catch (NoSuchFileException missing) {
                // Check every ancestor, including Git worktrees whose .git is a file.
            }
            if (current.endsWith(Path.of("frontend", "public"))) {
                throw new IOException("Private storage cannot be inside frontend/public");
            }
        }
    }

    private static IOException winFailure(String message, int error) {
        return new IOException(message + " (Windows error " + error + ")");
    }

    private record Pin(WinNT.HANDLE handle) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            if (!Kernel32.INSTANCE.CloseHandle(handle)) {
                throw winFailure("Unable to release a private storage handle", Kernel32.INSTANCE.GetLastError());
            }
        }
    }

    static final class Pins implements AutoCloseable {
        private final ArrayDeque<Pin> handles = new ArrayDeque<>();

        void add(Pin pin) {
            handles.push(pin);
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            while (!handles.isEmpty()) {
                try {
                    handles.pop().close();
                } catch (IOException closeFailure) {
                    if (failure == null) {
                        failure = closeFailure;
                    } else {
                        failure.addSuppressed(closeFailure);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}

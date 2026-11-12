package com.harsh.hospitalmanagement.storage;

import com.harsh.hospitalmanagement.exception.DocumentStorageUnavailableException;
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
import com.sun.jna.ptr.PointerByReference;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.sun.jna.platform.win32.AccCtrl.SE_OBJECT_TYPE.SE_FILE_OBJECT;
import static com.sun.jna.platform.win32.WinError.ERROR_FILE_NOT_FOUND;
import static com.sun.jna.platform.win32.WinError.ERROR_PATH_NOT_FOUND;

@Component
public class MultipartStagingManager {

    private static final Logger log = LoggerFactory.getLogger(MultipartStagingManager.class);
    public static final Duration DEFAULT_ABANDONED_AGE = Duration.ofHours(1);

    private static final String SYSTEM_SID = "S-1-5-18";
    private static final String ADMINISTRATORS_SID = "S-1-5-32-544";
    private static final String TRUSTED_INSTALLER_SID = "S-1-5-80-956008885-3418522649-1831038044-1853292631-2271478464";

    private static final Set<String> BROAD_UNTRUSTED_SIDS = Set.of(
            "S-1-1-0",       // Everyone
            "S-1-5-11",      // Authenticated Users
            "S-1-5-32-545",  // BUILTIN\Users
            "S-1-5-32-546",  // Guests
            "S-1-5-32-547",  // Power Users
            "S-1-5-4",       // Interactive
            "S-1-5-2",       // Network
            "S-1-5-7"        // Anonymous Logon
    );

    // Win32 FILE_INFO_BY_HANDLE_CLASS values.
    private static final int STANDARD_INFO = 1;
    private static final int DISPOSITION_INFO = 4;
    private static final int ATTRIBUTE_TAG_INFO = 9;

    private static final int INHERIT_ONLY_ACE = 0x08;
    private static final int FILE_DELETE_CHILD_MASK = 0x00000040;
    private static final int FILE_WRITE_DATA_MASK = 0x00000002;
    private static final int FILE_ADD_SUBDIRECTORY_MASK = 0x00000004;
    private static final int FILE_WRITE_EA_MASK = 0x00000010;
    private static final int FILE_WRITE_ATTRIBUTES_MASK = 0x00000100;
    private static final int HIGH_RISK_ANCESTOR_MASK =
            FILE_DELETE_CHILD_MASK | WinNT.WRITE_DAC | WinNT.WRITE_OWNER | WinNT.GENERIC_WRITE | WinNT.GENERIC_ALL;
    private static final int BROAD_ANCESTOR_WRITE_MASK =
            HIGH_RISK_ANCESTOR_MASK | WinNT.DELETE | FILE_WRITE_DATA_MASK | FILE_ADD_SUBDIRECTORY_MASK
                    | FILE_WRITE_EA_MASK | FILE_WRITE_ATTRIBUTES_MASK;
    private static final int PARENT_SUBDIR_CREATE_MASK =
            FILE_ADD_SUBDIRECTORY_MASK | FILE_DELETE_CHILD_MASK | WinNT.GENERIC_WRITE | WinNT.GENERIC_ALL;

    private static final int POSIX_GROUP_WRITE = 0020;
    private static final int POSIX_OTHERS_WRITE = 0002;
    private static final int POSIX_GROUP_OR_OTHERS_ANY = 0077;
    private static final int POSIX_STICKY_BIT = 01000;

    private final Path stagingDirectory;
    private final boolean windowsPlatform;
    private final WindowsSecurityReader windowsSecurityReader;
    private final PosixSecurityReader posixSecurityReader;

    @Autowired
    public MultipartStagingManager(
            @Value("${hms.document-storage.multipart-staging-dir:${java.io.tmpdir}/hms-multipart-staging}") String stagingPath
    ) {
        this(stagingPath != null && !stagingPath.isBlank() ? Path.of(stagingPath) : null);
    }

    public MultipartStagingManager(Path stagingDirectory) {
        this(stagingDirectory, Platform.isWindows(),
                SystemWindowsSecurityReader.INSTANCE, SystemPosixSecurityReader.INSTANCE);
    }

    MultipartStagingManager(
            Path stagingDirectory,
            WindowsSecurityReader windowsSecurityReader,
            PosixSecurityReader posixSecurityReader
    ) {
        this(stagingDirectory, Platform.isWindows(), windowsSecurityReader, posixSecurityReader);
    }

    MultipartStagingManager(
            Path stagingDirectory,
            boolean windowsPlatform,
            WindowsSecurityReader windowsSecurityReader,
            PosixSecurityReader posixSecurityReader
    ) {
        this.stagingDirectory = stagingDirectory != null ? stagingDirectory.toAbsolutePath().normalize() : null;
        this.windowsPlatform = windowsPlatform;
        this.windowsSecurityReader = windowsSecurityReader != null
                ? windowsSecurityReader : SystemWindowsSecurityReader.INSTANCE;
        this.posixSecurityReader = posixSecurityReader != null
                ? posixSecurityReader : SystemPosixSecurityReader.INSTANCE;
    }

    @PostConstruct
    public void init() {
        if (stagingDirectory != null) {
            try (var ignored = validateAndPinStagingDirectory()) {
                cleanAbandonedFiles(DEFAULT_ABANDONED_AGE);
            }
        }
    }

    public Path getStagingDirectory() {
        return stagingDirectory;
    }

    public void validateStagingDirectory() {
        try (var ignored = validateAndPinStagingDirectory()) {
            // Validation complete; pin released cleanly.
        }
    }

    public StagingDirectoryPin validateAndPinStagingDirectory() {
        if (stagingDirectory == null) {
            throw new DocumentStorageUnavailableException("Multipart staging directory is not configured");
        }
        try {
            if (windowsPlatform) {
                return validateAndPinWindows(true);
            }
            validatePosixDirectory(stagingDirectory, true);
            return new StagingDirectoryPin(null, stagingDirectory);
        } catch (DocumentStorageUnavailableException e) {
            throw e;
        } catch (UnsupportedOperationException e) {
            throw new DocumentStorageUnavailableException("POSIX file security attributes are not supported", e);
        } catch (IOException e) {
            log.warn("Failed to validate or pin multipart staging directory: exceptionClass={}", e.getClass().getName());
            throw new DocumentStorageUnavailableException("Insecure or unavailable multipart staging directory", e);
        }
    }

    public static void createRestrictedDirectory(Path path) throws IOException {
        Files.createDirectories(path);
        if (Platform.isWindows()) {
            setRestrictedWindowsDacl(path);
        } else {
            try {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
            } catch (UnsupportedOperationException e) {
                throw new DocumentStorageUnavailableException("POSIX permissions are not supported for staging directory", e);
            }
        }
    }

    public static void verifyPermissions(Path path) {
        if (path == null) {
            throw new DocumentStorageUnavailableException("Multipart staging directory is not configured");
        }
        new MultipartStagingManager(path).validateExistingOnly();
    }

    private void validateExistingOnly() {
        try {
            if (windowsPlatform) {
                try (var pin = validateAndPinWindows(false)) {
                    if (pin == null) {
                        throw new DocumentStorageUnavailableException("Multipart staging path does not exist");
                    }
                }
            } else {
                validatePosixDirectory(stagingDirectory, false);
            }
        } catch (DocumentStorageUnavailableException e) {
            throw e;
        } catch (UnsupportedOperationException e) {
            throw new DocumentStorageUnavailableException("POSIX file security attributes are not supported", e);
        } catch (IOException e) {
            log.warn("Insecure multipart staging permissions detected: exceptionClass={}", e.getClass().getName());
            throw new DocumentStorageUnavailableException("Insecure multipart staging permissions", e);
        }
    }

    public static void setRestrictedWindowsDacl(Path path) throws IOException {
        var token = new WinNT.HANDLEByReference();
        if (!Advapi32.INSTANCE.OpenProcessToken(Kernel32.INSTANCE.GetCurrentProcess(), WinNT.TOKEN_QUERY, token)) {
            throw new IOException("Failed to open current process token");
        }
        try {
            var sid = new WinNT.PSID(Advapi32Util.getTokenAccount(token.getValue()).sid);
            var acl = buildRestrictedWindowsAcl(sid);
            if (Advapi32.INSTANCE.SetNamedSecurityInfo(path.toString(), SE_FILE_OBJECT,
                    WinNT.DACL_SECURITY_INFORMATION | WinNT.PROTECTED_DACL_SECURITY_INFORMATION,
                    null, null, acl.getPointer(), null) != 0) {
                throw new IOException("Failed to set security info on staging directory");
            }
        } finally {
            Kernel32Util.closeHandleRef(token);
        }
    }

    public int cleanAbandonedFiles(Duration olderThan) {
        if (stagingDirectory == null) {
            return 0;
        }
        Duration threshold = olderThan != null ? olderThan : DEFAULT_ABANDONED_AGE;
        Instant cutoff = Instant.now().minus(threshold);

        if (windowsPlatform) {
            try (StagingDirectoryPin pin = validateAndPinWindows(false)) {
                if (pin == null) {
                    return 0;
                }
                return cleanAbandonedWindowsFiles(cutoff);
            } catch (DocumentStorageUnavailableException e) {
                throw e;
            } catch (IOException e) {
                log.warn("Failed to validate or pin multipart staging directory for cleanup: exceptionClass={}",
                        e.getClass().getName());
                throw new DocumentStorageUnavailableException("Insecure or unavailable multipart staging directory", e);
            }
        }

        try {
            if (!Files.exists(stagingDirectory, LinkOption.NOFOLLOW_LINKS)) {
                validatePosixAncestorsOnly(stagingDirectory);
                return 0;
            }
            validatePosixDirectory(stagingDirectory, false);
            return cleanAbandonedPosixFiles(cutoff);
        } catch (DocumentStorageUnavailableException e) {
            throw e;
        } catch (UnsupportedOperationException e) {
            throw new DocumentStorageUnavailableException("POSIX file security attributes are not supported", e);
        } catch (IOException e) {
            log.warn("Failed to validate multipart staging directory for cleanup: exceptionClass={}",
                    e.getClass().getName());
            throw new DocumentStorageUnavailableException("Insecure or unavailable multipart staging directory", e);
        }
    }

    private int cleanAbandonedWindowsFiles(Instant cutoff) {
        int deletedCount = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(stagingDirectory)) {
            for (Path entry : stream) {
                String filename = entry.getFileName().toString();
                if (!isAbandonedCandidateName(filename)) {
                    continue;
                }
                try (Pin file = pinWindowsEntry(entry, false, WinNT.DELETE)) {
                    var lastWrite = new WinBase.FILETIME();
                    if (!Kernel32.INSTANCE.GetFileTime(file.handle(), null, null, lastWrite)) {
                        throw winFailure("Failed to read staging entry timestamp", Kernel32.INSTANCE.GetLastError());
                    }
                    if (lastWrite.toDate().toInstant().isBefore(cutoff)) {
                        var disposition = new WinBase.FILE_DISPOSITION_INFO(true);
                        disposition.write();
                        if (!Kernel32.INSTANCE.SetFileInformationByHandle(file.handle(), DISPOSITION_INFO,
                                disposition.getPointer(), new DWORD(disposition.size()))) {
                            throw winFailure("Failed to mark abandoned staging file for deletion",
                                    Kernel32.INSTANCE.GetLastError());
                        }
                        deletedCount++;
                    }
                } catch (NoSuchFileException ignored) {
                    // Entry disappeared concurrently.
                } catch (DocumentStorageUnavailableException e) {
                    log.debug("Skipping unsafe staging entry during cleanup: exceptionClass={}",
                            e.getClass().getName());
                } catch (IOException e) {
                    log.debug("Skipping or failing cleanup for staging candidate: exceptionClass={}",
                            e.getClass().getName());
                }
            }
        } catch (IOException e) {
            log.warn("Failed to list multipart staging directory for cleanup: exceptionClass={}", e.getClass().getName());
        }
        return deletedCount;
    }

    private int cleanAbandonedPosixFiles(Instant cutoff) {
        int deletedCount = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(stagingDirectory)) {
            if (stream instanceof SecureDirectoryStream<Path> secureStream) {
                for (Path entry : secureStream) {
                    Path fileName = entry.getFileName();
                    if (!isAbandonedCandidateName(fileName.toString())) {
                        continue;
                    }
                    try {
                        BasicFileAttributeView view = secureStream.getFileAttributeView(
                                fileName, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                        if (view == null) {
                            continue;
                        }
                        BasicFileAttributes attrs = view.readAttributes();
                        if (attrs.isSymbolicLink() || !attrs.isRegularFile()) {
                            log.debug("Skipping non-regular file or symbolic link during staging cleanup");
                            continue;
                        }
                        if (attrs.lastModifiedTime().toInstant().isBefore(cutoff)) {
                            secureStream.deleteFile(fileName);
                            deletedCount++;
                        }
                    } catch (NoSuchFileException ignored) {
                        // Entry disappeared concurrently.
                    } catch (IOException e) {
                        log.warn("Failed to delete abandoned multipart staging entry: exceptionClass={}",
                                e.getClass().getName());
                    }
                }
            } else {
                for (Path entry : stream) {
                    if (!isAbandonedCandidateName(entry.getFileName().toString())) {
                        continue;
                    }
                    try {
                        BasicFileAttributes attrs = Files.readAttributes(
                                entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                        if (attrs.isSymbolicLink() || !attrs.isRegularFile()) {
                            log.debug("Skipping non-regular file or symbolic link during staging cleanup");
                            continue;
                        }
                        if (attrs.lastModifiedTime().toInstant().isBefore(cutoff)) {
                            Files.deleteIfExists(entry);
                            deletedCount++;
                        }
                    } catch (IOException e) {
                        log.warn("Failed to delete abandoned multipart staging entry: exceptionClass={}",
                                e.getClass().getName());
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Failed to list multipart staging directory for cleanup: exceptionClass={}", e.getClass().getName());
        }
        return deletedCount;
    }

    private static boolean isAbandonedCandidateName(String filename) {
        return filename.endsWith(".tmp") || filename.startsWith("upload_");
    }

    private StagingDirectoryPin validateAndPinWindows(boolean createIfMissing) throws IOException {
        validateWindowsPathSyntax(stagingDirectory);

        var processAccount = windowsSecurityReader.processAccount();
        Set<String> stagingSids = new HashSet<>();
        stagingSids.add(processAccount.sidString());
        stagingSids.add(SYSTEM_SID);
        stagingSids.add(ADMINISTRATORS_SID);
        Set<String> trustedStagingSids = Set.copyOf(stagingSids);

        Set<String> ancestorOwners = new HashSet<>(stagingSids);
        ancestorOwners.add(TRUSTED_INSTALLER_SID);
        Set<String> trustedAncestorOwners = Set.copyOf(ancestorOwners);

        Pins pins = new Pins();
        try {
            Path root = stagingDirectory.getRoot();
            if (Kernel32.INSTANCE.GetDriveType(root.toString()) != WinBase.DRIVE_FIXED
                    || !"NTFS".equalsIgnoreCase(Files.getFileStore(root).type())) {
                throw new DocumentStorageUnavailableException("Multipart staging requires a local fixed NTFS drive");
            }

            // Pin and inspect drive root and every ancestor directory above stagingDirectory.
            Pin rootPin = pinWindowsEntry(root, true, WinNT.READ_CONTROL);
            pins.add(rootPin);
            AncestorCheckResult prevCheck = inspectWindowsAncestor(rootPin, true, false, trustedAncestorOwners);

            int nameCount = stagingDirectory.getNameCount();
            Path current = root;
            for (int i = 0; i < nameCount - 1; i++) {
                current = current.resolve(stagingDirectory.getName(i));
                Pin ancestorPin;
                try {
                    ancestorPin = pinWindowsEntry(current, true, WinNT.READ_CONTROL);
                } catch (NoSuchFileException missingAncestor) {
                    throw new DocumentStorageUnavailableException(
                            "Multipart staging parent directory does not exist", missingAncestor);
                }
                pins.add(ancestorPin);
                prevCheck = inspectWindowsAncestor(
                        ancestorPin, false, prevCheck.allowsUntrustedSubdirCreate(), trustedAncestorOwners);
            }

            // Reject staging paths inside Git checkouts or web public directories.
            requireOutsideProject(stagingDirectory);

            // Pin existing staging directory or atomically create inside pinned parent.
            Pin targetPin;
            try {
                targetPin = pinWindowsEntry(stagingDirectory, true, WinNT.READ_CONTROL);
                pins.add(targetPin);
            } catch (NoSuchFileException missingTarget) {
                if (!createIfMissing) {
                    pins.close();
                    return null;
                }
                if (prevCheck.allowsBroadSubdirCreate()) {
                    throw new DocumentStorageUnavailableException(
                            "Multipart staging parent allows untrusted directory creation");
                }
                var principal = stagingDirectory.getFileSystem()
                        .getUserPrincipalLookupService().lookupPrincipalByName(processAccount.fqn());
                var entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(principal)
                        .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
                FileAttribute<List<AclEntry>> privateAcl = new FileAttribute<>() {
                    @Override public String name() { return "acl:acl"; }
                    @Override public List<AclEntry> value() { return List.of(entry); }
                };
                try {
                    Files.createDirectory(stagingDirectory, privateAcl);
                } catch (FileAlreadyExistsException raced) {
                    // Handled immediately below by pinning and verifying owner/ACL without overwriting.
                }
                targetPin = pinWindowsEntry(stagingDirectory, true, WinNT.READ_CONTROL | WinNT.WRITE_DAC);
                pins.add(targetPin);
                WindowsSecuritySnapshot createdSecurity = windowsSecurityReader.readSecurity(targetPin.handle());
                if (processAccount.sidString().equals(createdSecurity.ownerSid())) {
                    var psid = new WinNT.PSID(processAccount.sidBytes());
                    var protectedAcl = buildRestrictedWindowsAcl(psid);
                    int err = Advapi32.INSTANCE.SetSecurityInfo(targetPin.handle(), SE_FILE_OBJECT,
                            WinNT.DACL_SECURITY_INFORMATION | WinNT.PROTECTED_DACL_SECURITY_INFORMATION,
                            null, null, protectedAcl.getPointer(), null);
                    if (err != 0) {
                        throw winFailure("Failed to protect new staging directory ACL", err);
                    }
                }
            }

            WindowsSecuritySnapshot targetSecurity = windowsSecurityReader.readSecurity(targetPin.handle());
            validateWindowsStagingSecurity(targetSecurity, trustedStagingSids);

            return new StagingDirectoryPin(pins, stagingDirectory);
        } catch (IOException | RuntimeException failure) {
            try {
                pins.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            if (failure instanceof DocumentStorageUnavailableException dsu) {
                throw dsu;
            }
            if (failure instanceof NoSuchFileException) {
                throw new DocumentStorageUnavailableException("Multipart staging path is not a directory", failure);
            }
            throw failure;
        }
    }

    private AncestorCheckResult inspectWindowsAncestor(
            Pin handlePin,
            boolean isDriveRoot,
            boolean parentPermitsUntrustedSubdirCreate,
            Set<String> trustedAncestorOwners
    ) throws IOException {
        WindowsSecuritySnapshot security = windowsSecurityReader.readSecurity(handlePin.handle());
        return validateWindowsAncestorSecurity(
                security, isDriveRoot, parentPermitsUntrustedSubdirCreate, trustedAncestorOwners);
    }

    static AncestorCheckResult validateWindowsAncestorSecurity(
            WindowsSecuritySnapshot security,
            boolean isDriveRoot,
            boolean parentPermitsUntrustedSubdirCreate,
            Set<String> trustedAncestorOwners
    ) {
        if (security == null || security.ownerSid() == null
                || !trustedAncestorOwners.contains(security.ownerSid())) {
            throw new DocumentStorageUnavailableException("Multipart staging ancestor has an untrusted Windows owner");
        }
        if (security.daclNull() || security.aces().isEmpty()) {
            throw new DocumentStorageUnavailableException("Multipart staging ancestor has an unrestricted DACL");
        }

        boolean allowsUntrustedSubdirCreate = false;
        boolean allowsBroadSubdirCreate = false;

        for (WindowsAce ace : security.aces()) {
            if (!ace.supported()) {
                if ((ace.aceFlags() & INHERIT_ONLY_ACE) != 0) {
                    continue;
                }
                throw new DocumentStorageUnavailableException(
                        "Multipart staging ancestor DACL contains unsupported ACE");
            }
            if (!ace.accessAllowed() || (ace.aceFlags() & INHERIT_ONLY_ACE) != 0) {
                continue;
            }
            if (trustedAncestorOwners.contains(ace.sid())) {
                continue;
            }
            int mask = ace.mask();
            if ((mask & HIGH_RISK_ANCESTOR_MASK) != 0) {
                throw new DocumentStorageUnavailableException(
                        "Multipart staging ancestor grants unsafe modification permissions");
            }
            boolean isBroadPrincipal = BROAD_UNTRUSTED_SIDS.contains(ace.sid());
            if (!isDriveRoot && isBroadPrincipal && (mask & BROAD_ANCESTOR_WRITE_MASK) != 0) {
                throw new DocumentStorageUnavailableException(
                        "Multipart staging ancestor grants broad untrusted write permissions");
            }
            if (!isDriveRoot && parentPermitsUntrustedSubdirCreate && (mask & WinNT.DELETE) != 0) {
                throw new DocumentStorageUnavailableException(
                        "Multipart staging ancestor is replaceable by an untrusted principal");
            }
            if (!isDriveRoot && (mask & PARENT_SUBDIR_CREATE_MASK) != 0) {
                allowsUntrustedSubdirCreate = true;
                if (isBroadPrincipal) {
                    allowsBroadSubdirCreate = true;
                }
            }
        }
        return new AncestorCheckResult(allowsUntrustedSubdirCreate, allowsBroadSubdirCreate);
    }

    static void validateWindowsStagingSecurity(WindowsSecuritySnapshot security, Set<String> trustedStagingSids) {
        if (security == null
                || security.ownerSid() == null
                || !trustedStagingSids.contains(security.ownerSid())
                || security.daclNull()
                || security.aces().isEmpty()
                || !security.daclProtected()) {
            throw new DocumentStorageUnavailableException(
                    "Multipart staging directory requires a protected ACL and a trusted owner");
        }
        for (WindowsAce ace : security.aces()) {
            if (!ace.supported()) {
                throw new DocumentStorageUnavailableException("Multipart staging DACL contains unsupported ACE");
            }
            if (!ace.accessAllowed()) {
                continue;
            }
            if (!trustedStagingSids.contains(ace.sid())) {
                throw new DocumentStorageUnavailableException("Insecure multipart staging permissions");
            }
        }
    }

    private static void validateWindowsPathSyntax(Path path) {
        if (path == null || !path.isAbsolute() || path.getNameCount() == 0
                || path.getRoot() == null
                || !path.getRoot().toString().matches("[A-Za-z]:\\\\")
                || !path.equals(path.normalize())) {
            throw new DocumentStorageUnavailableException("Multipart staging requires an absolute local Windows path");
        }
        for (Path component : path) {
            String text = component.toString();
            if (text.endsWith(".") || text.endsWith(" ")) {
                throw new DocumentStorageUnavailableException(
                        "Multipart staging path must use canonical path components");
            }
        }
    }

    private static final int FILE_LIST_DIRECTORY_MASK = 0x00000001;
    private static final int FILE_TRAVERSE_MASK = 0x00000020;

    private static Pin pinWindowsEntry(Path path, boolean directory, int access) throws IOException {
        int flags = WinNT.FILE_FLAG_OPEN_REPARSE_POINT | (directory ? WinNT.FILE_FLAG_BACKUP_SEMANTICS : 0);
        // Requesting FILE_LIST_DIRECTORY (dirs) / FILE_READ_DATA (files) engages Windows ShareAccess enforcement
        // so omitting FILE_SHARE_DELETE prevents rename/move or deletion while pinned.
        int desiredAccess = (directory ? FILE_LIST_DIRECTORY_MASK : WinNT.FILE_READ_DATA)
                | WinNT.FILE_READ_ATTRIBUTES | access;
        int shareMode = directory
                ? (WinNT.FILE_SHARE_READ | WinNT.FILE_SHARE_WRITE)
                : WinNT.FILE_SHARE_READ;
        WinNT.HANDLE handle = Kernel32.INSTANCE.CreateFile(
                path.toString(),
                desiredAccess,
                shareMode,
                null,
                WinNT.OPEN_EXISTING,
                flags,
                null);
        if ((handle == null || WinBase.INVALID_HANDLE_VALUE.equals(handle))
                && directory && Kernel32.INSTANCE.GetLastError() == WinNT.ERROR_ACCESS_DENIED) {
            handle = Kernel32.INSTANCE.CreateFile(
                    path.toString(),
                    FILE_TRAVERSE_MASK | WinNT.FILE_READ_ATTRIBUTES | access,
                    shareMode,
                    null,
                    WinNT.OPEN_EXISTING,
                    flags,
                    null);
        }
        if (handle == null || WinBase.INVALID_HANDLE_VALUE.equals(handle)) {
            int error = Kernel32.INSTANCE.GetLastError();
            if (error == ERROR_FILE_NOT_FOUND || error == ERROR_PATH_NOT_FOUND) {
                throw new NoSuchFileException("Staging file or directory is missing");
            }
            throw winFailure("Staging file or directory is unavailable", error);
        }
        Pin pin = new Pin(handle);
        try {
            var tag = new WinBase.FILE_ATTRIBUTE_TAG_INFO();
            query(handle, ATTRIBUTE_TAG_INFO, tag);
            int attributes = tag.FileAttributes;
            if ((attributes & WinNT.FILE_ATTRIBUTE_REPARSE_POINT) != 0) {
                throw new DocumentStorageUnavailableException("Multipart staging refuses reparse points");
            }
            boolean isDir = (attributes & WinNT.FILE_ATTRIBUTE_DIRECTORY) != 0;
            if (directory && !isDir) {
                throw new DocumentStorageUnavailableException("Multipart staging path is not a directory");
            }
            if (isDir != directory || Kernel32.INSTANCE.GetFileType(handle) != WinNT.FILE_TYPE_DISK) {
                throw new DocumentStorageUnavailableException("Multipart staging path has an unexpected file type");
            }
            if (!directory) {
                var standard = new WinBase.FILE_STANDARD_INFO();
                query(handle, STANDARD_INFO, standard);
                if (standard.NumberOfLinks != 1) {
                    throw new DocumentStorageUnavailableException("Multipart staging refuses hard-linked files");
                }
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

    private void validatePosixDirectory(Path target, boolean createIfMissing) throws IOException {
        if (target == null || !target.isAbsolute() || target.getNameCount() == 0) {
            throw new DocumentStorageUnavailableException("Multipart staging requires an absolute POSIX path");
        }
        requireOutsideProject(target);

        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (!createIfMissing) {
                throw new DocumentStorageUnavailableException("Multipart staging directory does not exist");
            }
            validatePosixAncestorsOnly(target);
            try {
                Files.createDirectory(target,
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            } catch (UnsupportedOperationException e) {
                throw new DocumentStorageUnavailableException("POSIX permissions are not supported", e);
            } catch (FileAlreadyExistsException ignored) {
                // Re-checked below with NOFOLLOW_LINKS, owner, and mode inspection.
            }
        } else {
            validatePosixAncestorsOnly(target);
        }

        int currentUid = posixSecurityReader.currentUid();
        PosixAttributes targetAttrs = posixSecurityReader.readAttributes(target);
        if (targetAttrs.symbolicLink() || !targetAttrs.directory()) {
            throw new DocumentStorageUnavailableException("Multipart staging path is not a regular directory");
        }
        if (targetAttrs.uid() != currentUid) {
            throw new DocumentStorageUnavailableException("Multipart staging directory has an untrusted owner");
        }
        if ((targetAttrs.mode() & POSIX_GROUP_OR_OTHERS_ANY) != 0) {
            throw new DocumentStorageUnavailableException(
                    "Multipart staging directory must not be accessible to group or others");
        }
    }

    private void validatePosixAncestorsOnly(Path target) throws IOException {
        int currentUid = posixSecurityReader.currentUid();
        Path root = target.getRoot();
        PosixAttributes rootAttrs = posixSecurityReader.readAttributes(root);
        validatePosixAncestor(rootAttrs, currentUid);

        Path current = root;
        for (int i = 0; i < target.getNameCount() - 1; i++) {
            current = current.resolve(target.getName(i));
            PosixAttributes attrs = posixSecurityReader.readAttributes(current);
            validatePosixAncestor(attrs, currentUid);
        }
    }

    private static void validatePosixAncestor(PosixAttributes attrs, int currentUid) {
        if (attrs.symbolicLink() || !attrs.directory()) {
            throw new DocumentStorageUnavailableException("Multipart staging ancestor is invalid or a symbolic link");
        }
        int uid = attrs.uid();
        if (uid != currentUid && uid != 0) {
            throw new DocumentStorageUnavailableException("Multipart staging ancestor has an untrusted owner");
        }
        int mode = attrs.mode();
        boolean groupOrOthersWritable = (mode & (POSIX_GROUP_WRITE | POSIX_OTHERS_WRITE)) != 0;
        boolean rootOwnedSticky = uid == 0 && (mode & POSIX_STICKY_BIT) != 0;
        if (groupOrOthersWritable && !rootOwnedSticky) {
            throw new DocumentStorageUnavailableException(
                    "Multipart staging ancestor permits unsticky group or world write access");
        }
    }

    private static WinNT.ACL buildRestrictedWindowsAcl(WinNT.PSID sid) throws IOException {
        var acl = new WinNT.ACL(1024);
        if (!Advapi32.INSTANCE.InitializeAcl(acl, 1024, WinNT.ACL_REVISION)) {
            throw new IOException("Failed to initialize ACL");
        }
        if (!Advapi32.INSTANCE.AddAccessAllowedAceEx(acl, WinNT.ACL_REVISION,
                WinNT.OBJECT_INHERIT_ACE | WinNT.CONTAINER_INHERIT_ACE, WinNT.FILE_ALL_ACCESS, sid)) {
            throw new IOException("Failed to add ACE");
        }
        return acl;
    }

    private static void query(WinNT.HANDLE handle, int informationClass, com.sun.jna.Structure info) throws IOException {
        if (!Kernel32.INSTANCE.GetFileInformationByHandleEx(handle, informationClass, info.getPointer(), new DWORD(info.size()))) {
            throw winFailure("Unable to inspect a staging handle", Kernel32.INSTANCE.GetLastError());
        }
        info.read();
    }

    private static void requireOutsideProject(Path directory) throws IOException {
        for (Path current = directory; current != null; current = current.getParent()) {
            try {
                Files.readAttributes(current.resolve(".git"), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                throw new DocumentStorageUnavailableException("Multipart staging cannot be inside a Git checkout");
            } catch (NoSuchFileException ignored) {
                // Continue checking every ancestor, including worktree .git files.
            }
            if (current.endsWith(Path.of("frontend", "public"))) {
                throw new DocumentStorageUnavailableException("Multipart staging cannot be inside frontend/public");
            }
        }
    }

    private static IOException winFailure(String message, int error) {
        return new IOException(message + " (Windows error " + error + ")");
    }

    public static final class StagingDirectoryPin implements AutoCloseable {
        private final Pins pins;
        private final Path directory;

        private StagingDirectoryPin(Pins pins, Path directory) {
            this.pins = pins;
            this.directory = directory;
        }

        public Path directory() {
            return directory;
        }

        @Override
        public void close() {
            if (pins != null) {
                try {
                    pins.close();
                } catch (IOException e) {
                    log.warn("Failed to release staging directory pins: exceptionClass={}", e.getClass().getName());
                }
            }
        }
    }

    private record Pin(WinNT.HANDLE handle) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            if (!Kernel32.INSTANCE.CloseHandle(handle)) {
                throw winFailure("Unable to release a staging handle", Kernel32.INSTANCE.GetLastError());
            }
        }
    }

    private static final class Pins implements AutoCloseable {
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

    record ProcessAccountInfo(String sidString, String fqn, byte[] sidBytes) {
    }

    record WindowsAce(boolean supported, boolean accessAllowed, int aceType, int aceFlags, int mask, String sid) {
        WindowsAce(boolean accessAllowed, int aceFlags, int mask, String sid) {
            this(true, accessAllowed,
                    accessAllowed ? WinNT.ACCESS_ALLOWED_ACE_TYPE : WinNT.ACCESS_DENIED_ACE_TYPE,
                    aceFlags, mask, sid);
        }
    }

    record WindowsSecuritySnapshot(String ownerSid, boolean daclNull, boolean daclProtected, List<WindowsAce> aces) {
    }

    record AncestorCheckResult(boolean allowsUntrustedSubdirCreate, boolean allowsBroadSubdirCreate) {
    }

    interface WindowsSecurityReader {
        ProcessAccountInfo processAccount() throws IOException;

        WindowsSecuritySnapshot readSecurity(WinNT.HANDLE handle) throws IOException;
    }

    static WindowsSecuritySnapshot toSecuritySnapshot(WinNT.SECURITY_DESCRIPTOR_RELATIVE security) {
        String ownerSid = security.getOwner() != null ? security.getOwner().getSidString() : null;
        boolean daclProtected = (security.Control & WinNT.SE_DACL_PROTECTED) != 0;
        var acl = security.getDiscretionaryACL();
        if (acl == null) {
            return new WindowsSecuritySnapshot(ownerSid, true, daclProtected, List.of());
        }
        var rawAces = acl.getACEs();
        List<WindowsAce> aces = new ArrayList<>(rawAces.length);
        for (var ace : rawAces) {
            if (ace instanceof WinNT.ACCESS_ALLOWED_ACE allowed) {
                aces.add(new WindowsAce(true, true, allowed.AceType & 0xFF,
                        allowed.AceFlags & 0xFF, allowed.Mask, allowed.getSidString()));
            } else if (ace instanceof WinNT.ACCESS_DENIED_ACE denied) {
                aces.add(new WindowsAce(true, false, denied.AceType & 0xFF,
                        denied.AceFlags & 0xFF, denied.Mask, denied.getSidString()));
            } else {
                aces.add(new WindowsAce(false, false, ace.AceType & 0xFF,
                        ace.AceFlags & 0xFF, 0, null));
            }
        }
        return new WindowsSecuritySnapshot(ownerSid, false, daclProtected, List.copyOf(aces));
    }

    private enum SystemWindowsSecurityReader implements WindowsSecurityReader {
        INSTANCE;

        @Override
        public ProcessAccountInfo processAccount() throws IOException {
            var token = new WinNT.HANDLEByReference();
            if (!Advapi32.INSTANCE.OpenProcessToken(Kernel32.INSTANCE.GetCurrentProcess(), WinNT.TOKEN_QUERY, token)) {
                throw new DocumentStorageUnavailableException(
                        "Failed to inspect Windows process token for staging security");
            }
            try {
                var account = Advapi32Util.getTokenAccount(token.getValue());
                return new ProcessAccountInfo(account.sidString, account.fqn, account.sid);
            } catch (Win32Exception failure) {
                throw winFailure("Unable to identify Windows process account", failure.getErrorCode());
            } finally {
                Kernel32Util.closeHandleRef(token);
            }
        }

        @Override
        public WindowsSecuritySnapshot readSecurity(WinNT.HANDLE handle) throws IOException {
            var descriptor = new PointerByReference();
            int error = Advapi32.INSTANCE.GetSecurityInfo(handle, SE_FILE_OBJECT,
                    WinNT.OWNER_SECURITY_INFORMATION | WinNT.DACL_SECURITY_INFORMATION,
                    null, null, null, null, descriptor);
            if (error != 0) {
                throw winFailure("Unable to inspect Windows staging security", error);
            }
            try {
                return toSecuritySnapshot(new WinNT.SECURITY_DESCRIPTOR_RELATIVE(descriptor.getValue()));
            } finally {
                Kernel32Util.freeLocalMemory(descriptor.getValue());
            }
        }
    }

    record PosixAttributes(int uid, int mode, boolean directory, boolean symbolicLink) {
    }

    interface PosixSecurityReader {
        int currentUid() throws IOException;

        PosixAttributes readAttributes(Path path) throws IOException;
    }

    @FunctionalInterface
    interface PosixUidAttributeReader {
        Object readUid(Path path) throws IOException;
    }

    @FunctionalInterface
    interface UidCommandExecutor {
        int executeIdCommand() throws IOException;
    }

    static int readCurrentUid(
            Path procSelfPath,
            PosixUidAttributeReader attributeReader,
            UidCommandExecutor fallbackCommand
    ) throws IOException {
        try {
            // Do NOT pass LinkOption.NOFOLLOW_LINKS: /proc/self is a root-owned magic symlink
            // pointing to /proc/<pid>, which is owned by the process UID.
            Object uidAttr = attributeReader.readUid(procSelfPath);
            if (uidAttr instanceof Number num) {
                int uid = num.intValue();
                if (uid > 0) {
                    return uid;
                }
                // Cross-check uid == 0 in case PR_SET_DUMPABLE=0 causes /proc/<pid> to appear root-owned.
                return fallbackCommand.executeIdCommand();
            }
        } catch (UnsupportedOperationException e) {
            throw new DocumentStorageUnavailableException("Unix security inspection is not supported", e);
        } catch (IOException ignored) {
            // Fall back to `id -u` when /proc/self is absent (e.g., macOS).
        }
        return fallbackCommand.executeIdCommand();
    }

    private enum SystemPosixSecurityReader implements PosixSecurityReader {
        INSTANCE;

        @Override
        public int currentUid() throws IOException {
            return readCurrentUid(
                    Path.of("/proc/self"),
                    path -> Files.getAttribute(path, "unix:uid"),
                    SystemPosixSecurityReader::runIdMinusU
            );
        }

        private static int runIdMinusU() throws IOException {
            try {
                Process process = new ProcessBuilder("id", "-u").start();
                try {
                    if (!process.waitFor(5, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                        throw new DocumentStorageUnavailableException("Timed out inspecting current POSIX uid");
                    }
                    if (process.exitValue() != 0) {
                        throw new DocumentStorageUnavailableException("Unable to inspect current POSIX uid");
                    }
                    String uidText = new String(
                            process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
                    return Integer.parseInt(uidText);
                } finally {
                    process.destroy();
                }
            } catch (DocumentStorageUnavailableException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DocumentStorageUnavailableException("Interrupted while inspecting current POSIX uid", e);
            } catch (Exception e) {
                throw new DocumentStorageUnavailableException("Unable to inspect current POSIX uid", e);
            }
        }

        @Override
        public PosixAttributes readAttributes(Path path) throws IOException {
            try {
                if (!Files.getFileStore(path).supportsFileAttributeView(PosixFileAttributeView.class)) {
                    throw new DocumentStorageUnavailableException(
                            "POSIX file security attributes are not supported on staging filesystem");
                }
                Map<String, Object> attrs = Files.readAttributes(
                        path, "unix:uid,mode,isDirectory,isSymbolicLink", LinkOption.NOFOLLOW_LINKS);
                Object uidObj = attrs.get("uid");
                Object modeObj = attrs.get("mode");
                Object dirObj = attrs.get("isDirectory");
                Object symObj = attrs.get("isSymbolicLink");
                if (!(uidObj instanceof Number uid) || !(modeObj instanceof Number mode)
                        || !(dirObj instanceof Boolean dir) || !(symObj instanceof Boolean sym)) {
                    throw new DocumentStorageUnavailableException("Incomplete POSIX security attributes");
                }
                return new PosixAttributes(uid.intValue(), mode.intValue(), dir, sym);
            } catch (UnsupportedOperationException e) {
                throw new DocumentStorageUnavailableException("POSIX/Unix security inspection is not supported", e);
            }
        }
    }
}

package com.harsh.hospitalmanagement.storage;

import com.harsh.hospitalmanagement.exception.DocumentStorageUnavailableException;
import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Kernel32Util;
import com.sun.jna.platform.win32.WinNT;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.sun.jna.platform.win32.AccCtrl.SE_OBJECT_TYPE.SE_FILE_OBJECT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MultipartStagingManagerTest {

    @Test
    void validateStagingDirectoryCreatesRestrictedDirectory(@TempDir Path tempDir) throws IOException {
        isolateTempRoot(tempDir);
        Path staging = tempDir.resolve("sub-staging");
        MultipartStagingManager manager = new MultipartStagingManager(staging);

        assertThat(Files.exists(staging)).isFalse();
        manager.validateStagingDirectory();
        assertThat(Files.exists(staging)).isTrue();
        assertThat(Files.isDirectory(staging)).isTrue();
    }

    @Test
    void validateStagingDirectoryFailsWhenNull() {
        MultipartStagingManager manager = new MultipartStagingManager((Path) null);
        assertThatThrownBy(manager::validateStagingDirectory)
                .isInstanceOf(DocumentStorageUnavailableException.class)
                .hasMessageContaining("not configured");
    }

    @Test
    void validateStagingDirectoryFailsWhenNotDirectory(@TempDir Path tempDir) throws IOException {
        isolateTempRoot(tempDir);
        Path file = tempDir.resolve("some-file.txt");
        Files.writeString(file, "hello");

        MultipartStagingManager manager = new MultipartStagingManager(file);
        assertThatThrownBy(manager::validateStagingDirectory)
                .isInstanceOf(DocumentStorageUnavailableException.class)
                .hasMessageContaining("not a directory");
    }

    @Test
    void cleanAbandonedFilesDeletesOldTmpFilesAndPreservesRecent(@TempDir Path tempDir) throws IOException {
        isolateTempRoot(tempDir);
        Path stagingDir = tempDir.resolve("staging");
        MultipartStagingManager.createRestrictedDirectory(stagingDir);
        MultipartStagingManager manager = new MultipartStagingManager(stagingDir);

        Path oldTmp = stagingDir.resolve("upload_12345.tmp");
        Files.writeString(oldTmp, "old content");
        Files.setLastModifiedTime(oldTmp, FileTime.from(Instant.now().minus(Duration.ofHours(2))));

        Path recentTmp = stagingDir.resolve("upload_67890.tmp");
        Files.writeString(recentTmp, "recent content");
        Files.setLastModifiedTime(recentTmp, FileTime.from(Instant.now().minus(Duration.ofMinutes(10))));

        Path regularFile = stagingDir.resolve("keep_me.txt");
        Files.writeString(regularFile, "important text");
        Files.setLastModifiedTime(regularFile, FileTime.from(Instant.now().minus(Duration.ofHours(2))));

        int deleted = manager.cleanAbandonedFiles(Duration.ofHours(1));

        assertThat(deleted).isEqualTo(1);
        assertThat(Files.exists(oldTmp)).isFalse();
        assertThat(Files.exists(recentTmp)).isTrue();
        assertThat(Files.exists(regularFile)).isTrue();
    }

    @Test
    void cleanAbandonedFilesSkipsSymbolicLinksAndNeverTouchesTarget(@TempDir Path tempDir) throws IOException {
        isolateTempRoot(tempDir);
        Path externalDir = tempDir.resolve("external");
        Files.createDirectories(externalDir);
        Path externalTarget = externalDir.resolve("target_secret.txt");
        String initialContent = "confidential external evidence";
        Files.writeString(externalTarget, initialContent);
        Instant oldTime = Instant.now().minus(Duration.ofHours(3));
        Files.setLastModifiedTime(externalTarget, FileTime.from(oldTime));

        Path stagingDir = tempDir.resolve("staging");
        MultipartStagingManager.createRestrictedDirectory(stagingDir);

        Path symlinkInStaging = stagingDir.resolve("upload_trap.tmp");
        try {
            Files.createSymbolicLink(symlinkInStaging, externalTarget);
        } catch (UnsupportedOperationException | FileSystemException | SecurityException e) {
            Assumptions.assumeTrue(false,
                    "Skipping symlink test: symbolic link creation not supported or requires elevation on this platform: " + e.getMessage());
            return;
        }

        Path validStaleTmp = stagingDir.resolve("upload_stale.tmp");
        Files.writeString(validStaleTmp, "stale content");
        Files.setLastModifiedTime(validStaleTmp, FileTime.from(Instant.now().minus(Duration.ofHours(2))));

        MultipartStagingManager manager = new MultipartStagingManager(stagingDir);
        int deleted = manager.cleanAbandonedFiles(Duration.ofHours(1));

        assertThat(deleted).isEqualTo(1);
        assertThat(Files.exists(validStaleTmp)).isFalse();

        // Verify external target remains completely untouched
        assertThat(Files.exists(externalTarget)).isTrue();
        assertThat(Files.readString(externalTarget)).isEqualTo(initialContent);
        assertThat(Files.getLastModifiedTime(externalTarget)).isEqualTo(FileTime.from(oldTime));

        // Verify symlink was not followed or deleted
        assertThat(Files.exists(symlinkInStaging, LinkOption.NOFOLLOW_LINKS)).isTrue();
    }

    @Test
    void cleanAbandonedFilesSkipsNonRegularDirectories(@TempDir Path tempDir) throws IOException {
        isolateTempRoot(tempDir);
        Path stagingDir = tempDir.resolve("staging");
        MultipartStagingManager.createRestrictedDirectory(stagingDir);

        Path subDir = stagingDir.resolve("upload_sub.tmp");
        Files.createDirectories(subDir);
        Files.setLastModifiedTime(subDir, FileTime.from(Instant.now().minus(Duration.ofHours(2))));

        MultipartStagingManager manager = new MultipartStagingManager(stagingDir);
        int deleted = manager.cleanAbandonedFiles(Duration.ofHours(1));

        assertThat(deleted).isEqualTo(0);
        assertThat(Files.exists(subDir)).isTrue();
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void rejectsUnprotectedPreexistingStagingDirectoryAndLeavesAclIntact(@TempDir Path tempDir) throws IOException {
        isolateTempRoot(tempDir);
        Path stagingDir = tempDir.resolve("unprotected-staging");
        Files.createDirectory(stagingDir);
        grantSidAccess(stagingDir, "S-1-5-32-545", WinNT.FILE_ALL_ACCESS); // BUILTIN\Users
        Path staleTmp = stagingDir.resolve("upload_untrusted.tmp");
        Files.writeString(staleTmp, "stale");
        Files.setLastModifiedTime(staleTmp, FileTime.from(Instant.now().minus(Duration.ofHours(3))));

        MultipartStagingManager manager = new MultipartStagingManager(stagingDir);
        assertThatThrownBy(manager::validateStagingDirectory)
                .isInstanceOf(DocumentStorageUnavailableException.class);
        assertThatThrownBy(() -> manager.cleanAbandonedFiles(Duration.ofHours(1)))
                .isInstanceOf(DocumentStorageUnavailableException.class);
        assertThat(Files.exists(staleTmp)).isTrue();
        // Pre-existing unprotected ACL must not be overwritten on rejection.
        var aclView = Files.getFileAttributeView(
                stagingDir, java.nio.file.attribute.AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        assertThat(aclView.getAcl())
                .anySatisfy(ace -> assertThat(ace.principal().getName()).isEqualTo("BUILTIN\\Users"));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void rejectsAttackerOwnedStagingDirectoryAndUntrustedDaclWithoutOverwriting(@TempDir Path tempDir) throws IOException {
        isolateTempRoot(tempDir);
        Path stagingDir = tempDir.resolve("staging");
        MultipartStagingManager.createRestrictedDirectory(stagingDir);
        var realReader = realProcessWindowsReader(tempDir);
        String processSid = realReader.processAccount().sidString();
        String attackerSid = "S-1-5-21-999999999-888888888-777777777-1001";
        int targetCallIndex = stagingDir.toAbsolutePath().normalize().getNameCount();

        MultipartStagingManager.WindowsSecurityReader attackerOwnedReader = new MultipartStagingManager.WindowsSecurityReader() {
            private int reads = 0;

            @Override
            public MultipartStagingManager.ProcessAccountInfo processAccount() throws IOException {
                return realReader.processAccount();
            }

            @Override
            public MultipartStagingManager.WindowsSecuritySnapshot readSecurity(WinNT.HANDLE handle) throws IOException {
                if (reads++ < targetCallIndex) {
                    return realReader.readSecurity(handle);
                }
                return new MultipartStagingManager.WindowsSecuritySnapshot(
                        attackerSid,
                        false,
                        true,
                        List.of(new MultipartStagingManager.WindowsAce(true, 0, WinNT.FILE_ALL_ACCESS, processSid)));
            }
        };
        MultipartStagingManager untrustedOwnerManager = new MultipartStagingManager(stagingDir, attackerOwnedReader, null);
        assertThatThrownBy(untrustedOwnerManager::validateStagingDirectory)
                .isInstanceOf(DocumentStorageUnavailableException.class)
                .hasMessageContaining("trusted owner");

        MultipartStagingManager.WindowsSecurityReader untrustedAceReader = new MultipartStagingManager.WindowsSecurityReader() {
            private int reads = 0;

            @Override
            public MultipartStagingManager.ProcessAccountInfo processAccount() throws IOException {
                return realReader.processAccount();
            }

            @Override
            public MultipartStagingManager.WindowsSecuritySnapshot readSecurity(WinNT.HANDLE handle) throws IOException {
                if (reads++ < targetCallIndex) {
                    return realReader.readSecurity(handle);
                }
                return new MultipartStagingManager.WindowsSecuritySnapshot(
                        processSid,
                        false,
                        true,
                        List.of(
                                new MultipartStagingManager.WindowsAce(true, 0, WinNT.FILE_ALL_ACCESS, processSid),
                                new MultipartStagingManager.WindowsAce(true, 0, WinNT.FILE_ALL_ACCESS, "S-1-5-32-545")));
            }
        };
        MultipartStagingManager untrustedDaclManager = new MultipartStagingManager(stagingDir, untrustedAceReader, null);
        assertThatThrownBy(untrustedDaclManager::validateStagingDirectory)
                .isInstanceOf(DocumentStorageUnavailableException.class)
                .hasMessageContaining("Insecure multipart staging permissions");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void rejectsWritableParentOrReplaceableAncestorDirectory(@TempDir Path tempDir) throws IOException {
        isolateTempRoot(tempDir);
        Path parent = tempDir.resolve("shared-parent");
        Files.createDirectory(parent);
        grantSidAccess(parent, "S-1-5-32-545", WinNT.FILE_ALL_ACCESS); // BUILTIN\Users

        // Creating new staging directory under writable parent fails.
        MultipartStagingManager newUnderWritable = new MultipartStagingManager(parent.resolve("staging"));
        assertThatThrownBy(newUnderWritable::validateStagingDirectory)
                .isInstanceOf(DocumentStorageUnavailableException.class)
                .hasMessageContaining("ancestor");

        // Even an already-protected child directory under a writable parent fails.
        Path precreated = parent.resolve("already-protected");
        MultipartStagingManager.createRestrictedDirectory(precreated);
        MultipartStagingManager existingUnderWritable = new MultipartStagingManager(precreated);
        assertThatThrownBy(existingUnderWritable::validateStagingDirectory)
                .isInstanceOf(DocumentStorageUnavailableException.class)
                .hasMessageContaining("ancestor");
    }

    @Test
    void validateWindowsAncestorSecurityRejectsReplaceableAndBroadWriteAncestors() {
        String processSid = "S-1-5-21-1000-1000-1000-1001";
        String attackerSid = "S-1-5-21-1000-1000-1000-1002";
        Set<String> trustedAncestors = Set.of(processSid, "S-1-5-18", "S-1-5-32-544",
                "S-1-5-80-956008885-3418522649-1831038044-1853292631-2271478464");

        // Parent grants narrow FILE_ADD_SUBDIRECTORY (0x4) to another principal, child allows DELETE (0x10000) to them -> replaceable.
        var parentSnap = new MultipartStagingManager.WindowsSecuritySnapshot(
                processSid, false, true,
                List.of(new MultipartStagingManager.WindowsAce(true, 0, 0x00000004, attackerSid)));
        var parentResult = MultipartStagingManager.validateWindowsAncestorSecurity(
                parentSnap, false, false, trustedAncestors);
        assertThat(parentResult.allowsUntrustedSubdirCreate()).isTrue();

        var childSnap = new MultipartStagingManager.WindowsSecuritySnapshot(
                processSid, false, true,
                List.of(new MultipartStagingManager.WindowsAce(true, 0, WinNT.DELETE, attackerSid)));
        assertThatThrownBy(() -> MultipartStagingManager.validateWindowsAncestorSecurity(
                childSnap, false, parentResult.allowsUntrustedSubdirCreate(), trustedAncestors))
                .isInstanceOf(DocumentStorageUnavailableException.class)
                .hasMessageContaining("replaceable");

        // INHERIT_ONLY_ACE does not apply to the directory itself.
        var inheritOnlySnap = new MultipartStagingManager.WindowsSecuritySnapshot(
                processSid, false, false,
                List.of(new MultipartStagingManager.WindowsAce(true, 0x08, WinNT.GENERIC_ALL, attackerSid)));
        assertThat(MultipartStagingManager.validateWindowsAncestorSecurity(
                inheritOnlySnap, false, false, trustedAncestors).allowsUntrustedSubdirCreate()).isFalse();

        // Unsupported grant ACEs on ancestor (non-inherit-only) and staging directory must fail closed.
        var unsupportedActiveSnap = new MultipartStagingManager.WindowsSecuritySnapshot(
                processSid, false, true,
                List.of(
                        new MultipartStagingManager.WindowsAce(true, 0, WinNT.FILE_ALL_ACCESS, processSid),
                        new MultipartStagingManager.WindowsAce(false, false, 9, 0, WinNT.FILE_ALL_ACCESS, null)));
        assertThatThrownBy(() -> MultipartStagingManager.validateWindowsAncestorSecurity(
                unsupportedActiveSnap, false, false, trustedAncestors))
                .isInstanceOf(DocumentStorageUnavailableException.class)
                .hasMessageContaining("unsupported ACE");
        assertThatThrownBy(() -> MultipartStagingManager.validateWindowsStagingSecurity(
                unsupportedActiveSnap, Set.of(processSid, "S-1-5-18", "S-1-5-32-544")))
                .isInstanceOf(DocumentStorageUnavailableException.class)
                .hasMessageContaining("unsupported ACE");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void parseAndRejectRawUnsupportedCallbackAndObjectAces() {
        byte[] sidBytes = Advapi32Util.convertStringSidToSid("S-1-5-18");
        for (int unsupportedAceType : List.of(9, 5, 11)) {
            WinNT.SECURITY_DESCRIPTOR_RELATIVE sd = buildRelativeSdWithTrustedAndRawAce(sidBytes, unsupportedAceType);
            MultipartStagingManager.WindowsSecuritySnapshot snapshot = MultipartStagingManager.toSecuritySnapshot(sd);

            assertThat(snapshot.daclProtected()).isTrue();
            assertThat(snapshot.aces()).anySatisfy(ace -> {
                assertThat(ace.supported()).isFalse();
                assertThat(ace.aceType()).isEqualTo(unsupportedAceType);
            });
            assertThatThrownBy(() -> MultipartStagingManager.validateWindowsStagingSecurity(
                    snapshot, Set.of("S-1-5-18", "S-1-5-32-544")))
                    .isInstanceOf(DocumentStorageUnavailableException.class)
                    .hasMessageContaining("unsupported ACE");
            assertThatThrownBy(() -> MultipartStagingManager.validateWindowsAncestorSecurity(
                    snapshot, false, false, Set.of("S-1-5-18", "S-1-5-32-544")))
                    .isInstanceOf(DocumentStorageUnavailableException.class)
                    .hasMessageContaining("unsupported ACE");
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void validateStagingDirectorySucceedsWhenProcessRunsAsLocalSystem(@TempDir Path tempDir) throws IOException {
        isolateTempRoot(tempDir);
        Path stagingDir = tempDir.resolve("localsystem-staging");
        MultipartStagingManager.createRestrictedDirectory(stagingDir);
        byte[] systemSidBytes = Advapi32Util.convertStringSidToSid("S-1-5-18");
        var localSystemAccount = new MultipartStagingManager.ProcessAccountInfo(
                "S-1-5-18", "NT AUTHORITY\\SYSTEM", systemSidBytes);
        MultipartStagingManager.WindowsSecurityReader localSystemReader = new MultipartStagingManager.WindowsSecurityReader() {
            @Override
            public MultipartStagingManager.ProcessAccountInfo processAccount() {
                return localSystemAccount;
            }

            @Override
            public MultipartStagingManager.WindowsSecuritySnapshot readSecurity(WinNT.HANDLE handle) {
                return new MultipartStagingManager.WindowsSecuritySnapshot(
                        "S-1-5-18", false, true,
                        List.of(new MultipartStagingManager.WindowsAce(true, 0, WinNT.FILE_ALL_ACCESS, "S-1-5-18")));
            }
        };

        MultipartStagingManager manager = new MultipartStagingManager(stagingDir, true, localSystemReader, null);
        manager.validateStagingDirectory();
    }

    @Test
    void readCurrentUidFollowsProcSelfTargetAndHandlesNonDumpableFallback() throws IOException {
        Path procSelf = Path.of("/proc/self");

        // Simulates /proc/self resolved without NOFOLLOW_LINKS to /proc/<pid> owned by non-root uid 1000.
        int nonRootUid = MultipartStagingManager.readCurrentUid(
                procSelf,
                path -> 1000,
                () -> {
                    throw new AssertionError("Should not spawn id -u when followed /proc/self reports uid > 0");
                });
        assertThat(nonRootUid).isEqualTo(1000);

        // When /proc/self reports 0 (root OR PR_SET_DUMPABLE=0), fallback `id -u` determines effective UID.
        int nonDumpableUid = MultipartStagingManager.readCurrentUid(
                procSelf,
                path -> 0,
                () -> 1000);
        assertThat(nonDumpableUid).isEqualTo(1000);

        // When /proc/self does not exist (e.g. macOS), fallback `id -u` succeeds.
        int macFallbackUid = MultipartStagingManager.readCurrentUid(
                procSelf,
                path -> {
                    throw new java.nio.file.NoSuchFileException("/proc/self");
                },
                () -> 501);
        assertThat(macFallbackUid).isEqualTo(501);

        // When Unix attributes are unsupported, fails closed.
        assertThatThrownBy(() -> MultipartStagingManager.readCurrentUid(
                procSelf,
                path -> {
                    throw new UnsupportedOperationException("No unix view");
                },
                () -> 1000))
                .isInstanceOf(DocumentStorageUnavailableException.class);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void rejectsJunctionStagingDirectoryAndJunctionAncestor(@TempDir Path tempDir) throws Exception {
        isolateTempRoot(tempDir);
        Path outside = tempDir.resolve("outside");
        MultipartStagingManager.createRestrictedDirectory(outside);

        Path targetJunction = tempDir.resolve("junction-staging");
        createJunction(targetJunction, outside);
        try {
            MultipartStagingManager manager = new MultipartStagingManager(targetJunction);
            assertThatThrownBy(manager::validateStagingDirectory)
                    .isInstanceOf(DocumentStorageUnavailableException.class)
                    .hasMessageContaining("reparse");
            assertThatThrownBy(() -> manager.cleanAbandonedFiles(Duration.ofHours(1)))
                    .isInstanceOf(DocumentStorageUnavailableException.class)
                    .hasMessageContaining("reparse");
        } finally {
            Files.deleteIfExists(targetJunction);
        }

        Path ancestorJunction = tempDir.resolve("ancestor-junction");
        createJunction(ancestorJunction, outside);
        try {
            MultipartStagingManager nestedManager = new MultipartStagingManager(ancestorJunction.resolve("staging"));
            assertThatThrownBy(nestedManager::validateStagingDirectory)
                    .isInstanceOf(DocumentStorageUnavailableException.class)
                    .hasMessageContaining("reparse");
        } finally {
            Files.deleteIfExists(ancestorJunction);
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void validateAndPinStagingDirectoryBlocksMoveOrDeleteWhileAllowingChildWrites(@TempDir Path tempDir) throws IOException {
        isolateTempRoot(tempDir);
        Path stagingDir = tempDir.resolve("pinned-staging");
        MultipartStagingManager manager = new MultipartStagingManager(stagingDir);

        try (var pin = manager.validateAndPinStagingDirectory()) {
            assertThat(pin.directory()).isEqualTo(stagingDir.toAbsolutePath().normalize());
            assertThatThrownBy(() -> Files.move(stagingDir, tempDir.resolve("renamed-staging")))
                    .isInstanceOf(IOException.class);
            assertThatThrownBy(() -> Files.delete(stagingDir))
                    .isInstanceOf(IOException.class);

            // Writing and cleaning child temp files inside the pinned staging dir still works.
            Path childTmp = stagingDir.resolve("upload_active.tmp");
            Files.writeString(childTmp, "payload");
            assertThat(Files.readString(childTmp)).isEqualTo("payload");
            Files.delete(childTmp);

            // Concurrent read/pin from another request thread/call is allowed.
            try (var secondPin = manager.validateAndPinStagingDirectory()) {
                assertThat(secondPin.directory()).isEqualTo(pin.directory());
            }
        }

        // Once the pin closes, normal directory operations succeed again.
        Path moved = tempDir.resolve("renamed-staging");
        Files.move(stagingDir, moved);
        assertThat(Files.isDirectory(moved)).isTrue();
    }

    @Test
    void validatePosixStagingSecurityFailsClosedOnUnsupportedOrInsecurePaths(@TempDir Path tempDir) throws IOException {
        Path stagingDir = tempDir.resolve("posix-staging");
        Files.createDirectories(stagingDir);
        Path parent = stagingDir.getParent();

        // 1. Unsupported POSIX filesystem fails closed.
        MultipartStagingManager.PosixSecurityReader unsupportedReader = new MultipartStagingManager.PosixSecurityReader() {
            @Override
            public int currentUid() {
                return 1000;
            }

            @Override
            public MultipartStagingManager.PosixAttributes readAttributes(Path path) {
                throw new UnsupportedOperationException("No unix view");
            }
        };
        MultipartStagingManager unsupportedManager = new MultipartStagingManager(stagingDir, false, null, unsupportedReader);
        assertThatThrownBy(unsupportedManager::validateStagingDirectory)
                .isInstanceOf(DocumentStorageUnavailableException.class);

        // 2. Mutable attribute table to test ancestor/target security checks cleanly.
        Map<Path, MultipartStagingManager.PosixAttributes> attrs = new HashMap<>();
        for (Path cur = stagingDir.getRoot(); cur != null && !cur.equals(stagingDir); cur = nextToward(cur, stagingDir)) {
            attrs.put(cur, new MultipartStagingManager.PosixAttributes(0, 0755, true, false));
        }
        attrs.put(parent, new MultipartStagingManager.PosixAttributes(1000, 0755, true, false));
        attrs.put(stagingDir, new MultipartStagingManager.PosixAttributes(1000, 0700, true, false));

        MultipartStagingManager.PosixSecurityReader mapReader = new MultipartStagingManager.PosixSecurityReader() {
            @Override
            public int currentUid() {
                return 1000;
            }

            @Override
            public MultipartStagingManager.PosixAttributes readAttributes(Path path) {
                return attrs.get(path);
            }
        };
        MultipartStagingManager manager = new MultipartStagingManager(stagingDir, false, null, mapReader);
        manager.validateStagingDirectory();

        // World-writable unsticky parent rejected.
        attrs.put(parent, new MultipartStagingManager.PosixAttributes(0, 0777, true, false));
        assertThatThrownBy(manager::validateStagingDirectory)
                .isInstanceOf(DocumentStorageUnavailableException.class)
                .hasMessageContaining("unsticky");

        // Root-owned sticky parent (/tmp style 01777) permitted.
        attrs.put(parent, new MultipartStagingManager.PosixAttributes(0, 01777, true, false));
        manager.validateStagingDirectory();

        // Untrusted ancestor owner rejected.
        attrs.put(parent, new MultipartStagingManager.PosixAttributes(2000, 0755, true, false));
        assertThatThrownBy(manager::validateStagingDirectory)
                .isInstanceOf(DocumentStorageUnavailableException.class)
                .hasMessageContaining("untrusted owner");

        // Symlink ancestor rejected.
        attrs.put(parent, new MultipartStagingManager.PosixAttributes(1000, 0755, true, true));
        assertThatThrownBy(manager::validateStagingDirectory)
                .isInstanceOf(DocumentStorageUnavailableException.class)
                .hasMessageContaining("symbolic link");

        // Restore valid parent, test insecure target mode (0755) and wrong uid and symlink target.
        attrs.put(parent, new MultipartStagingManager.PosixAttributes(1000, 0755, true, false));
        attrs.put(stagingDir, new MultipartStagingManager.PosixAttributes(1000, 0755, true, false));
        assertThatThrownBy(manager::validateStagingDirectory)
                .isInstanceOf(DocumentStorageUnavailableException.class)
                .hasMessageContaining("group or others");

        attrs.put(stagingDir, new MultipartStagingManager.PosixAttributes(2000, 0700, true, false));
        assertThatThrownBy(manager::validateStagingDirectory)
                .isInstanceOf(DocumentStorageUnavailableException.class)
                .hasMessageContaining("untrusted owner");

        attrs.put(stagingDir, new MultipartStagingManager.PosixAttributes(1000, 0700, true, true));
        assertThatThrownBy(manager::validateStagingDirectory)
                .isInstanceOf(DocumentStorageUnavailableException.class)
                .hasMessageContaining("not a regular directory");
    }

    private static Path nextToward(Path current, Path target) {
        if (current.equals(target)) {
            return null;
        }
        int currentDepth = current.getNameCount();
        return currentDepth < target.getNameCount() ? current.resolve(target.getName(currentDepth)) : null;
    }

    private static void isolateTempRoot(Path tempDir) throws IOException {
        if (Platform.isWindows()) {
            MultipartStagingManager.setRestrictedWindowsDacl(tempDir);
        }
    }

    private static void grantSidAccess(Path path, String sidString, int mask) throws IOException {
        var token = new WinNT.HANDLEByReference();
        if (!Advapi32.INSTANCE.OpenProcessToken(Kernel32.INSTANCE.GetCurrentProcess(), WinNT.TOKEN_QUERY, token)) {
            throw new IOException("Unable to inspect current process token");
        }
        try {
            var processSid = new WinNT.PSID(Advapi32Util.getTokenAccount(token.getValue()).sid);
            var grantedSid = new WinNT.PSID(Advapi32Util.convertStringSidToSid(sidString));
            var acl = new WinNT.ACL(2048);
            if (!Advapi32.INSTANCE.InitializeAcl(acl, 2048, WinNT.ACL_REVISION)
                    || !Advapi32.INSTANCE.AddAccessAllowedAceEx(acl, WinNT.ACL_REVISION,
                            WinNT.OBJECT_INHERIT_ACE | WinNT.CONTAINER_INHERIT_ACE, WinNT.FILE_ALL_ACCESS, processSid)
                    || !Advapi32.INSTANCE.AddAccessAllowedAceEx(acl, WinNT.ACL_REVISION,
                            WinNT.OBJECT_INHERIT_ACE | WinNT.CONTAINER_INHERIT_ACE, mask, grantedSid)) {
                throw new IOException("Failed to build test DACL");
            }
            if (Advapi32.INSTANCE.SetNamedSecurityInfo(path.toString(), SE_FILE_OBJECT,
                    WinNT.DACL_SECURITY_INFORMATION | WinNT.PROTECTED_DACL_SECURITY_INFORMATION,
                    null, null, acl.getPointer(), null) != 0) {
                throw new IOException("Failed to apply test DACL");
            }
        } finally {
            Kernel32Util.closeHandleRef(token);
        }
    }

    private static MultipartStagingManager.WindowsSecurityReader realProcessWindowsReader(Path tempDir) throws IOException {
        var token = new WinNT.HANDLEByReference();
        if (!Advapi32.INSTANCE.OpenProcessToken(Kernel32.INSTANCE.GetCurrentProcess(), WinNT.TOKEN_QUERY, token)) {
            throw new IOException("Failed to open current process token");
        }
        try {
            var account = Advapi32Util.getTokenAccount(token.getValue());
            var info = new MultipartStagingManager.ProcessAccountInfo(account.sidString, account.fqn, account.sid);
            return new MultipartStagingManager.WindowsSecurityReader() {
                @Override
                public MultipartStagingManager.ProcessAccountInfo processAccount() {
                    return info;
                }

                @Override
                public MultipartStagingManager.WindowsSecuritySnapshot readSecurity(WinNT.HANDLE handle) {
                    return new MultipartStagingManager.WindowsSecuritySnapshot(
                            info.sidString(), false, true,
                            List.of(new MultipartStagingManager.WindowsAce(true, 0, WinNT.FILE_ALL_ACCESS, info.sidString())));
                }
            };
        } finally {
            Kernel32Util.closeHandleRef(token);
        }
    }

    private static WinNT.SECURITY_DESCRIPTOR_RELATIVE buildRelativeSdWithTrustedAndRawAce(
            byte[] sidBytes,
            int rawAceType
    ) {
        int allowAceSize = 8 + sidBytes.length;
        int rawAceSize = 8 + sidBytes.length;
        int daclSize = 8 + allowAceSize + rawAceSize;
        int ownerOffset = 20;
        int daclOffset = ownerOffset + sidBytes.length;
        byte[] sdBytes = new byte[daclOffset + daclSize];

        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(sdBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        // SECURITY_DESCRIPTOR_RELATIVE header (20 bytes)
        buf.put((byte) 1); // Revision
        buf.put((byte) 0); // Sbz1
        short control = (short) (WinNT.SE_SELF_RELATIVE | WinNT.SE_DACL_PRESENT | WinNT.SE_DACL_PROTECTED);
        buf.putShort(control);
        buf.putInt(ownerOffset); // Owner offset
        buf.putInt(0);           // Group offset
        buf.putInt(0);           // Sacl offset
        buf.putInt(daclOffset);  // Dacl offset
        buf.put(sidBytes);       // Owner SID

        // ACL header (8 bytes)
        buf.put((byte) WinNT.ACL_REVISION);
        buf.put((byte) 0);
        buf.putShort((short) daclSize);
        buf.putShort((short) 2); // AceCount = 2
        buf.putShort((short) 0);

        // ACE 0: standard ACCESS_ALLOWED_ACE_TYPE (0) for trusted SID
        buf.put((byte) WinNT.ACCESS_ALLOWED_ACE_TYPE);
        buf.put((byte) 0);
        buf.putShort((short) allowAceSize);
        buf.putInt(WinNT.FILE_ALL_ACCESS);
        buf.put(sidBytes);

        // ACE 1: unsupported grant ACE (e.g. ACCESS_ALLOWED_CALLBACK_ACE_TYPE = 9 or OBJECT = 5)
        buf.put((byte) rawAceType);
        buf.put((byte) 0);
        buf.putShort((short) rawAceSize);
        buf.putInt(WinNT.FILE_ALL_ACCESS);
        buf.put(sidBytes);

        return new WinNT.SECURITY_DESCRIPTOR_RELATIVE(sdBytes);
    }

    private static void createJunction(Path junction, Path target) throws Exception {
        String command = "New-Item -ItemType Junction -Path '" + junction.toString().replace("'", "''")
                + "' -Target '" + target.toString().replace("'", "''") + "' -ErrorAction Stop | Out-Null";
        String script = Base64.getEncoder().encodeToString(command.getBytes(StandardCharsets.UTF_16LE));
        Path powershell = Path.of(System.getenv("SystemRoot"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        var process = new ProcessBuilder(powershell.toString(), "-NoProfile", "-NonInteractive", "-EncodedCommand", script)
                .redirectErrorStream(true).start();
        try {
            assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).withFailMessage(new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8)).isZero();
        } finally {
            process.destroy();
        }
    }
}

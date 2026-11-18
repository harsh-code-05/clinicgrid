package com.harsh.hospitalmanagement.storage;

import com.harsh.hospitalmanagement.service.DoctorVerificationService.DocumentKind;
import com.sun.jna.platform.win32.Crypt32Util;
import com.sun.jna.platform.win32.WinCrypt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.crypto.AEADBadTagException;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledOnOs(OS.WINDOWS)
class LocalVolumeStorageIntegrationTest {

    @TempDir
    Path temp;

    private Path documentsDir;
    private Path keysDir;
    private WindowsPrivateFiles documentFiles;
    private WindowsPrivateFiles keyFiles;
    private VerificationImageValidator.ValidatedImage syntheticIdProof;
    private VerificationImageValidator.ValidatedImage syntheticQualification;

    @BeforeEach
    void setUp() throws IOException {
        MultipartStagingManager.setRestrictedWindowsDacl(temp);
        documentsDir = WindowsDocumentStorageTest.protectedDirectory(temp.resolve("documents"));
        keysDir = WindowsDocumentStorageTest.protectedDirectory(temp.resolve("keys"));

        documentFiles = new WindowsPrivateFiles(documentsDir);
        keyFiles = new WindowsPrivateFiles(keysDir);

        provisionDpapiMasterKey(keyFiles, 1);

        var validator = new VerificationImageValidator();
        syntheticIdProof = validator.validate("image/png",
                new ByteArrayInputStream(createSyntheticImageBytes("png", Color.BLUE)));
        syntheticQualification = validator.validate("image/jpeg",
                new ByteArrayInputStream(createSyntheticImageBytes("jpeg", Color.GREEN)));
    }

    @Nested
    @DisplayName("Stage 1 & 2: Volume Architecture & Fail-Closed Preflight")
    class VolumeArchitectureTests {

        private final ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(DocumentStorageConfiguration.class);

        @Test
        @DisplayName("DocumentStorageConfiguration fails closed when documents and keys share the same NTFS volume")
        void storageFailsClosedWhenOnSameVolume() {
            runner.withPropertyValues(
                    "hms.document-storage.enabled=true",
                    "hms.document-storage.root=" + documentsDir,
                    "hms.document-storage.key-directory=" + keysDir,
                    "hms.document-storage.master-key-version=1"
            ).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("The DPAPI master-key directory must be on a separate NTFS volume");
            });
        }

        @Test
        @DisplayName("DocumentStorageConfiguration succeeds and executes lifecycle when configured with distinct volumes")
        void storageSucceedsWhenConfiguredWithDistinctVolumes() throws Exception {
            Path distinctDocRoot = WindowsDocumentStorageTest.protectedDirectory(temp.resolve("configured-docs"));
            Path distinctKeyRoot = WindowsDocumentStorageTest.protectedDirectory(temp.resolve("configured-keys"));

            byte[] masterKey = new byte[32];
            new SecureRandom().nextBytes(masterKey);
            byte[] validBlob = Crypt32Util.cryptProtectData(masterKey, WindowsDpapiMasterKeyProvider.purpose(1),
                    WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, null, null);
            Files.write(distinctKeyRoot.resolve("v1.dpapi"), validBlob);

            var docFiles = new WindowsPrivateFiles(distinctDocRoot, "VOL_DOCUMENTS_REHEARSAL_1");
            var keyFiles = new WindowsPrivateFiles(distinctKeyRoot, "VOL_KEYS_REHEARSAL_2");

            DocumentStorageConfiguration.PrivateFilesFactory factory = path ->
                    path.equals(distinctDocRoot) ? docFiles : keyFiles;

            new ApplicationContextRunner()
                    .withBean(DocumentStorageConfiguration.class, () -> new DocumentStorageConfiguration(factory))
                    .withPropertyValues(
                            "hms.document-storage.enabled=true",
                            "hms.document-storage.root=" + distinctDocRoot.toString().replace('\\', '/'),
                            "hms.document-storage.key-directory=" + distinctKeyRoot.toString().replace('\\', '/'),
                            "hms.document-storage.master-key-version=1"
                    ).run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).hasSingleBean(DocumentStorage.class);

                        DocumentStorage configuredStorage = context.getBean(DocumentStorage.class);
                        DocumentKey key = configuredStorage.store(999, DocumentKind.ID_PROOF, syntheticIdProof);

                        assertThat(key).isNotNull();
                        assertThat(key.doctorId()).isEqualTo(999);
                        assertThat(key.kind()).isEqualTo(DocumentKind.ID_PROOF);

                        DocumentStorage.ReadDocument retrieved = configuredStorage.read(key);
                        assertThat(retrieved.bytes()).isEqualTo(syntheticIdProof.encoded());

                        assertThat(configuredStorage.delete(key)).isTrue();
                        assertThat(configuredStorage.exists(key)).isFalse();
                        assertThatThrownBy(() -> configuredStorage.read(key))
                                .isInstanceOf(java.nio.file.NoSuchFileException.class);
                    });
        }

        @Test
        @DisplayName("Private storage remains disabled by default")
        void storageIsDisabledByDefault() {
            runner.run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(DocumentStorage.class);
            });
        }

        @Test
        @DisplayName("WindowsPrivateFiles enforces canonical path and rejects path traversal")
        void rejectsPathTraversalAndNonCanonicalPaths() {
            // Unnormalized root path with '..' is rejected
            assertThatThrownBy(() -> new WindowsPrivateFiles(documentsDir.resolve("..")))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("absolute local Windows directory");

            // Trailing dot in directory component is rejected
            assertThatThrownBy(() -> new WindowsPrivateFiles(documentsDir.resolve("bad-dot.")))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("canonical");

            // Relative path traversal attempts are rejected
            assertThatThrownBy(() -> documentFiles.exists(Path.of("../outside.txt")))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Invalid private storage address");
        }
    }

    @Nested
    @DisplayName("Stage 2: DPAPI Master Key Security")
    class DpapiSecurityTests {

        @Test
        @DisplayName("DPAPI key is protected in CurrentUser scope and unlocks with 256 bits")
        void dpapiMasterKeyProtection() throws IOException {
            var provider = new WindowsDpapiMasterKeyProvider(keyFiles, 1);
            assertThat(provider.activeVersion()).isEqualTo(1);
        }

        @Test
        @DisplayName("Corrupt or tampered DPAPI blob fails decryption cleanly")
        void corruptDpapiBlobFailsDecryption() throws IOException {
            Path keyPath = keysDir.resolve("v1.dpapi");
            byte[] blob = Files.readAllBytes(keyPath);
            blob[blob.length - 1] ^= 0xFF;
            Files.write(keyPath, blob);

            assertThatThrownBy(() -> new WindowsDpapiMasterKeyProvider(keyFiles, 1))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("DPAPI");
        }

        @Test
        @DisplayName("DPAPI blob protected in LocalMachine scope is rejected")
        void rejectsLocalMachineProtectedBlob() throws IOException {
            byte[] raw = new byte[32];
            new SecureRandom().nextBytes(raw);
            byte[] machineBlob = Crypt32Util.cryptProtectData(raw, WindowsDpapiMasterKeyProvider.purpose(2),
                    WinCrypt.CRYPTPROTECT_LOCAL_MACHINE | WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, null, null);
            keyFiles.writeNew(Path.of("v2.dpapi"), machineBlob);

            assertThatThrownBy(() -> new WindowsDpapiMasterKeyProvider(keyFiles, 2))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("CurrentUser");
        }

        @Test
        @DisplayName("DPAPI key of invalid size is rejected")
        void invalidKeySizeRejected() throws IOException {
            byte[] invalidKey = new byte[16]; // 128 bits instead of 256 bits
            byte[] blob = Crypt32Util.cryptProtectData(invalidKey,
                    WindowsDpapiMasterKeyProvider.purpose(2),
                    WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, null, null);
            keyFiles.writeNew(Path.of("v2.dpapi"), blob);

            assertThatThrownBy(() -> new WindowsDpapiMasterKeyProvider(keyFiles, 2))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("256 bits");
        }
    }

    @Nested
    @DisplayName("Stage 4: Encrypted Document Lifecycle")
    class DocumentLifecycleTests {

        @Test
        @DisplayName("Synthetic ID proof and Qualification documents are encrypted with AES-256-GCM")
        void fullEncryptedLifecycle() throws IOException {
            var store = new FilesystemDocumentStorage(
                    documentFiles,
                    new WindowsDpapiMasterKeyProvider(keyFiles, 1),
                    UUID::randomUUID
            );

            long doctorId = 42L;

            // 1. Upload ID Proof
            DocumentKey idProofKey = store.store(doctorId, DocumentKind.ID_PROOF, syntheticIdProof);
            assertThat(idProofKey.doctorId()).isEqualTo(doctorId);
            assertThat(idProofKey.kind()).isEqualTo(DocumentKind.ID_PROOF);
            assertThat(store.exists(idProofKey)).isTrue();

            // Verify physical ciphertext properties
            Path idProofDiskPath = documentsDir.resolve(idProofKey.relativePath());
            byte[] ciphertext = Files.readAllBytes(idProofDiskPath);

            // Magic check: 'HMSD' = 0x484D5344
            assertThat(ciphertext[0]).isEqualTo((byte) 'H');
            assertThat(ciphertext[1]).isEqualTo((byte) 'M');
            assertThat(ciphertext[2]).isEqualTo((byte) 'S');
            assertThat(ciphertext[3]).isEqualTo((byte) 'D');
            assertThat(ciphertext[4]).isEqualTo((byte) 1); // Format version

            // Verify plaintext is NEVER written to the storage file
            String rawCiphertextStr = new String(ciphertext, StandardCharsets.ISO_8859_1);
            String rawPlaintextStr = new String(syntheticIdProof.encoded(), StandardCharsets.ISO_8859_1);
            assertThat(rawCiphertextStr).doesNotContain(rawPlaintextStr);

            // 2. Upload Qualification Document
            DocumentKey qualKey = store.store(doctorId, DocumentKind.QUALIFICATION, syntheticQualification);
            assertThat(qualKey.doctorId()).isEqualTo(doctorId);
            assertThat(qualKey.kind()).isEqualTo(DocumentKind.QUALIFICATION);
            assertThat(store.exists(qualKey)).isTrue();

            // Verify both coexist and have different ciphertext
            byte[] qualCiphertext = Files.readAllBytes(documentsDir.resolve(qualKey.relativePath()));
            assertThat(qualCiphertext).isNotEqualTo(ciphertext);

            // 3. Decrypt and verify contents
            var decryptedId = store.read(idProofKey);
            assertThat(decryptedId.contentType()).isEqualTo("image/png");
            assertThat(decryptedId.downloadFilename()).isEqualTo("id-proof.png");
            assertThat(decryptedId.bytes()).isEqualTo(syntheticIdProof.encoded());

            var decryptedQual = store.read(qualKey);
            assertThat(decryptedQual.contentType()).isEqualTo("image/jpeg");
            assertThat(decryptedQual.downloadFilename()).isEqualTo("qualification.jpeg");
            assertThat(decryptedQual.bytes()).isEqualTo(syntheticQualification.encoded());

            // 4. Verify integrity
            store.verifyIntegrity(idProofKey);
            store.verifyIntegrity(qualKey);

            // 5. Deletion
            assertThat(store.delete(idProofKey)).isTrue();
            assertThat(store.exists(idProofKey)).isFalse();
            assertThat(store.exists(qualKey)).isTrue();
        }
    }

    @Nested
    @DisplayName("Stage 5: Tamper Detection & Failure-Path Security")
    class TamperAndFailureTests {

        @Test
        @DisplayName("Ciphertext byte tampering triggers cryptographic integrity failure")
        void tamperingFailsIntegrity() throws IOException {
            var store = new FilesystemDocumentStorage(
                    documentFiles,
                    new WindowsDpapiMasterKeyProvider(keyFiles, 1),
                    UUID::randomUUID
            );

            DocumentKey key = store.store(99L, DocumentKind.ID_PROOF, syntheticIdProof);
            Path target = documentsDir.resolve(key.relativePath());
            byte[] encrypted = Files.readAllBytes(target);

            // Flip 1 bit in the ciphertext body
            encrypted[encrypted.length - 5] ^= 0x01;
            Files.write(target, encrypted);

            assertThatThrownBy(() -> store.read(key))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("integrity");

            assertThatThrownBy(() -> store.verifyIntegrity(key))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("integrity");
        }

        @Test
        @DisplayName("Tampered AAD binding (wrong doctor ID in key) fails AEAD authentication")
        void wrongDoctorIdAadMismatchFails() throws IOException {
            var store = new FilesystemDocumentStorage(
                    documentFiles,
                    new WindowsDpapiMasterKeyProvider(keyFiles, 1),
                    UUID::randomUUID
            );

            DocumentKey keyDoc1 = store.store(1L, DocumentKind.ID_PROOF, syntheticIdProof);
            // Construct a DocumentKey pointing to the same file but claiming doctor 2
            DocumentKey tamperedKey = new DocumentKey(2L, DocumentKind.ID_PROOF, keyDoc1.version());

            // Write ciphertext under doctor 2's key with proper private ACL
            byte[] ciphertext = Files.readAllBytes(documentsDir.resolve(keyDoc1.relativePath()));
            documentFiles.writeNew(tamperedKey.relativePath(), ciphertext);

            // Reading under tampered key must fail because AAD contains doctorId
            assertThatThrownBy(() -> store.read(tamperedKey))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("integrity");
        }

        @Test
        @DisplayName("Multipart staging manager cleans abandoned temporary files")
        void stagingCleanupRemovesAbandonedFiles() throws IOException {
            Path stagingDir = temp.resolve("staging");
            MultipartStagingManager.createRestrictedDirectory(stagingDir);
            var stagingManager = new MultipartStagingManager(stagingDir);

            // Create an abandoned .tmp file with old timestamp
            Path abandoned = stagingDir.resolve("upload_abandoned.tmp");
            Files.writeString(abandoned, "abandoned content");
            Files.setLastModifiedTime(abandoned, java.nio.file.attribute.FileTime.from(Instant.now().minus(Duration.ofHours(3))));

            // Create a recent active .tmp file
            Path recent = stagingDir.resolve("upload_active.tmp");
            Files.writeString(recent, "active content");

            int cleaned = stagingManager.cleanAbandonedFiles(Duration.ofHours(1));
            assertThat(cleaned).isEqualTo(1);
            assertThat(Files.exists(abandoned)).isFalse();
            assertThat(Files.exists(recent)).isTrue();
        }
    }

    private static void provisionDpapiMasterKey(WindowsPrivateFiles keyFiles, int version) throws IOException {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        try {
            byte[] blob = Crypt32Util.cryptProtectData(raw,
                    WindowsDpapiMasterKeyProvider.purpose(version),
                    WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, null, null);
            keyFiles.writeNew(Path.of("v" + version + ".dpapi"), blob);
        } finally {
            Arrays.fill(raw, (byte) 0);
        }
    }

    private static byte[] createSyntheticImageBytes(String format, Color color) throws IOException {
        BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, 100, 100);
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, format, baos);
        return baos.toByteArray();
    }
}

package com.harsh.hospitalmanagement.storage;

import com.harsh.hospitalmanagement.service.DoctorVerificationService.DocumentKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class S3DocumentStorageTest {

    private static final String BUCKET = "test-hms-documents";
    private InMemoryS3Client s3Client;
    private EnvMasterKeyProvider keyProvider;
    private S3DocumentStorage storage;
    private VerificationImageValidator.ValidatedImage sampleImage;

    @BeforeEach
    void setUp() throws Exception {
        s3Client = new InMemoryS3Client();
        byte[] masterKey = new byte[32];
        new SecureRandom().nextBytes(masterKey);
        String hexKey = HexFormat.of().formatHex(masterKey);
        keyProvider = EnvMasterKeyProvider.fromMap(Map.of("HMS_DOCUMENT_MASTER_KEY_V1", hexKey), 1);
        storage = new S3DocumentStorage(BUCKET, s3Client, keyProvider, UUID::randomUUID);

        byte[] pngBytes = VerificationImageValidatorTest.imageBytes("png");
        sampleImage = new VerificationImageValidator().validate("image/png", new ByteArrayInputStream(pngBytes));
    }

    @Test
    void storesAndReadsEncryptedDocument() throws Exception {
        long doctorId = 42L;
        DocumentKind kind = DocumentKind.ID_PROOF;

        DocumentKey key = storage.store(doctorId, kind, sampleImage);
        assertThat(key).isNotNull();
        assertThat(key.doctorId()).isEqualTo(doctorId);
        assertThat(key.kind()).isEqualTo(kind);

        // Verify object was written to S3 in encrypted form
        String fullKey = BUCKET + "/" + key.toString();
        assertThat(s3Client.storage).containsKey(fullKey);

        byte[] encryptedBlob = s3Client.storage.get(fullKey);
        // Ciphertext should not match raw image bytes
        assertThat(encryptedBlob).isNotEqualTo(sampleImage.encoded());

        // Read decrypted document
        DocumentStorage.ReadDocument doc = storage.read(key);
        assertThat(doc.contentType()).isEqualTo("image/png");
        assertThat(doc.bytes()).isEqualTo(sampleImage.encoded());
    }

    @Test
    void storePreventsAccidentalOverwriteWhenObjectExists() throws Exception {
        UUID fixedVersion = UUID.randomUUID();
        S3DocumentStorage fixedStorage = new S3DocumentStorage(BUCKET, s3Client, keyProvider, () -> fixedVersion);

        long doctorId = 55L;
        DocumentKind kind = DocumentKind.QUALIFICATION;

        DocumentKey key1 = fixedStorage.store(doctorId, kind, sampleImage);
        assertThat(key1.version()).isEqualTo(fixedVersion);

        // Attempting to store again with same fixed version must throw FileAlreadyExistsException
        assertThatThrownBy(() -> fixedStorage.store(doctorId, kind, sampleImage))
                .isInstanceOf(FileAlreadyExistsException.class)
                .hasMessageContaining(key1.toString());
    }

    @Test
    void storeHandlesPutFailureFromS3() {
        s3Client.setFailNextPut(true);
        assertThatThrownBy(() -> storage.store(99L, DocumentKind.ID_PROOF, sampleImage))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Failed to securely store document in S3");
    }

    @Test
    void readThrowsNoSuchFileExceptionWhenNotFound() {
        DocumentKey nonExistent = new DocumentKey(123L, DocumentKind.ID_PROOF, UUID.randomUUID());
        assertThatThrownBy(() -> storage.read(nonExistent))
                .isInstanceOf(NoSuchFileException.class);
    }

    @Test
    void readFailsWhenCiphertextTampered() throws Exception {
        DocumentKey key = storage.store(77L, DocumentKind.ID_PROOF, sampleImage);
        String fullKey = BUCKET + "/" + key.toString();

        byte[] original = s3Client.storage.get(fullKey);
        byte[] corrupted = Arrays.copyOf(original, original.length);
        corrupted[corrupted.length - 1] ^= 0x01; // flip last bit
        s3Client.storage.put(fullKey, corrupted);

        assertThatThrownBy(() -> storage.read(key))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("integrity checks");
    }

    @Test
    void existsAndDeletesProperly() throws Exception {
        DocumentKey key = storage.store(88L, DocumentKind.ID_PROOF, sampleImage);

        assertThat(storage.exists(key)).isTrue();
        assertThat(storage.delete(key)).isTrue();
        assertThat(storage.exists(key)).isFalse();
        assertThat(storage.delete(key)).isFalse();
    }

    @Test
    void verifyIntegrityValidatesWithoutLeaking() throws Exception {
        DocumentKey key = storage.store(99L, DocumentKind.ID_PROOF, sampleImage);

        // Should succeed without exception
        storage.verifyIntegrity(key);

        // Tamper and verify failure
        String fullKey = BUCKET + "/" + key.toString();
        byte[] original = s3Client.storage.get(fullKey);
        byte[] corrupted = Arrays.copyOf(original, original.length);
        corrupted[corrupted.length - 5] ^= 0x55;
        s3Client.storage.put(fullKey, corrupted);

        assertThatThrownBy(() -> storage.verifyIntegrity(key))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("integrity checks");
    }

    @Test
    void listStoredDocumentsPaginatesAndFiltersValidKeys() throws Exception {
        DocumentKey k1 = storage.store(101L, DocumentKind.ID_PROOF, sampleImage);
        DocumentKey k2 = storage.store(101L, DocumentKind.QUALIFICATION, sampleImage);
        DocumentKey k3 = storage.store(102L, DocumentKind.ID_PROOF, sampleImage);

        // Inject some unrelated noise keys
        s3Client.putObject(BUCKET, "verification/noise.txt", new byte[10], "text/plain", false);
        s3Client.putObject(BUCKET, "verification/not-a-doctor/id/fake", new byte[10], "application/octet-stream", false);

        DocumentStorage.DocumentScanPage page = storage.listStoredDocuments(null, 100);
        assertThat(page.documents()).hasSize(3);
        assertThat(page.documents()).extracting(DocumentStorage.StoredDocument::key).contains(k1, k2, k3);
    }

    /**
     * In-memory mock implementation of S3Client for contract testing.
     */
    private static class InMemoryS3Client implements S3Client {
        final Map<String, byte[]> storage = new ConcurrentHashMap<>();
        private boolean failNextPut = false;

        void setFailNextPut(boolean fail) {
            this.failNextPut = fail;
        }

        private String fullKey(String bucket, String key) {
            return bucket + "/" + key;
        }

        @Override
        public void putObject(String bucket, String key, byte[] content, String contentType, boolean ifNoneMatch)
                throws IOException {
            if (failNextPut) {
                failNextPut = false;
                throw new IOException("Simulated network failure on putObject");
            }
            String path = fullKey(bucket, key);
            if (ifNoneMatch && storage.containsKey(path)) {
                throw new IOException("HTTP 412 Precondition Failed");
            }
            storage.put(path, Arrays.copyOf(content, content.length));
        }

        @Override
        public byte[] getObject(String bucket, String key) throws IOException {
            String path = fullKey(bucket, key);
            byte[] data = storage.get(path);
            if (data == null) {
                throw new NoSuchFileException("s3://" + path);
            }
            return Arrays.copyOf(data, data.length);
        }

        @Override
        public boolean headObject(String bucket, String key) {
            return storage.containsKey(fullKey(bucket, key));
        }

        @Override
        public boolean deleteObject(String bucket, String key) {
            return storage.remove(fullKey(bucket, key)) != null;
        }

        @Override
        public S3ListResult listObjects(String bucket, String prefix, String continuationToken, int maxKeys) {
            String bucketPrefix = bucket + "/" + (prefix != null ? prefix : "");
            List<S3ObjectEntry> matching = new ArrayList<>();
            for (Map.Entry<String, byte[]> entry : storage.entrySet()) {
                if (entry.getKey().startsWith(bucketPrefix)) {
                    String objKey = entry.getKey().substring(bucket.length() + 1);
                    matching.add(new S3ObjectEntry(objKey, Instant.now(), entry.getValue().length));
                }
            }
            return new S3ListResult(matching, null, false);
        }
    }
}

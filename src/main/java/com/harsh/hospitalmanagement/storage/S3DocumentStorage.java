package com.harsh.hospitalmanagement.storage;

import com.harsh.hospitalmanagement.service.DoctorVerificationService.DocumentKind;
import com.harsh.hospitalmanagement.storage.VerificationImageValidator.ValidatedImage;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Production-ready S3-compatible private document storage adapter.
 * Envelope encryption with AES-256-GCM and authenticated AAD is enforced
 * in memory before uploading to S3; the remote bucket never receives plaintext.
 */
public final class S3DocumentStorage implements DocumentStorage {

    private static final String VERIFICATION_PREFIX = "verification/";

    private final String bucket;
    private final S3Client s3Client;
    private final DocumentCipher cipher;
    private final Supplier<UUID> versions;

    public S3DocumentStorage(String bucket, S3Client s3Client, MasterKeyProvider keys) {
        this(bucket, s3Client, keys, UUID::randomUUID);
    }

    public S3DocumentStorage(String bucket, S3Client s3Client, MasterKeyProvider keys, Supplier<UUID> versions) {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("S3 bucket name must not be blank");
        }
        this.bucket = bucket.trim();
        this.s3Client = Objects.requireNonNull(s3Client, "s3Client must not be null");
        this.cipher = new DocumentCipher(Objects.requireNonNull(keys, "keys must not be null"));
        this.versions = versions != null ? versions : UUID::randomUUID;
    }

    @Override
    public DocumentKey store(long doctorId, DocumentKind kind, ValidatedImage image) throws IOException {
        DocumentKey key = new DocumentKey(doctorId, kind, versions.get());
        String objectKey = key.toString();

        // Accidental overwrite prevention: check if object already exists
        if (s3Client.headObject(bucket, objectKey)) {
            throw new FileAlreadyExistsException("Document object already exists at S3 key: " + objectKey);
        }

        byte[] encryptedBlob = cipher.encrypt(key, image);
        try {
            s3Client.putObject(bucket, objectKey, encryptedBlob, "application/octet-stream", true);
        } catch (IOException e) {
            throw new IOException("Failed to securely store document in S3: " + objectKey, e);
        }
        return key;
    }

    @Override
    public ReadDocument read(DocumentKey key) throws IOException {
        Objects.requireNonNull(key, "key must not be null");
        byte[] encryptedBlob = fetchBlob(key);
        DocumentCipher.Decoded decrypted = cipher.decrypt(key, encryptedBlob);
        var format = decrypted.format();
        return new ReadDocument(format.mime, key.kind().pathSegment() + "." + format.extension, decrypted.bytes());
    }

    @Override
    public void verifyIntegrity(DocumentKey key) throws IOException {
        Objects.requireNonNull(key, "key must not be null");
        byte[] encryptedBlob = fetchBlob(key);
        DocumentCipher.Decoded decrypted = cipher.decrypt(key, encryptedBlob);
        Arrays.fill(decrypted.bytes(), (byte) 0);
    }

    @Override
    public boolean exists(DocumentKey key) throws IOException {
        if (key == null) return false;
        return s3Client.headObject(bucket, key.toString());
    }

    @Override
    public boolean delete(DocumentKey key) throws IOException {
        if (key == null) return false;
        return s3Client.deleteObject(bucket, key.toString());
    }

    @Override
    public DocumentScanPage listStoredDocuments(String cursor, int maxEntries) throws IOException {
        int boundedMax = Math.clamp(maxEntries, 1, 1000);
        S3Client.S3ListResult result = s3Client.listObjects(bucket, VERIFICATION_PREFIX, cursor, boundedMax);
        if (result == null || (result.objects().isEmpty() && !result.isTruncated())) {
            return DocumentScanPage.EMPTY;
        }

        List<StoredDocument> documents = new ArrayList<>(result.objects().size());
        for (S3Client.S3ObjectEntry entry : result.objects()) {
            try {
                DocumentKey key = DocumentKey.parse(entry.key());
                Instant lastMod = entry.lastModified() != null ? entry.lastModified() : Instant.now();
                documents.add(new StoredDocument(key, lastMod));
            } catch (IllegalArgumentException ignored) {
                // Skip non-canonical object keys safely without failing the page
            }
        }
        return new DocumentScanPage(List.copyOf(documents), result.nextContinuationToken(), result.isTruncated());
    }

    private byte[] fetchBlob(DocumentKey key) throws IOException {
        String objectKey = key.toString();
        byte[] blob = s3Client.getObject(bucket, objectKey);
        if (blob == null || blob.length == 0) {
            throw new NoSuchFileException("Document object missing or empty in S3: " + objectKey);
        }
        return blob;
    }
}

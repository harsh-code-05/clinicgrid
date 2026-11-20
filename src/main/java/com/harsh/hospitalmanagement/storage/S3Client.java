package com.harsh.hospitalmanagement.storage;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * Minimal, provider-agnostic S3-compatible client contract.
 */
public interface S3Client {

    void putObject(String bucket, String key, byte[] content, String contentType, boolean ifNoneMatch) throws IOException;

    byte[] getObject(String bucket, String key) throws IOException;

    boolean headObject(String bucket, String key) throws IOException;

    boolean deleteObject(String bucket, String key) throws IOException;

    S3ListResult listObjects(String bucket, String prefix, String continuationToken, int maxKeys) throws IOException;

    record S3ObjectEntry(String key, Instant lastModified, long size) {}

    record S3ListResult(List<S3ObjectEntry> objects, String nextContinuationToken, boolean isTruncated) {
        public static final S3ListResult EMPTY = new S3ListResult(List.of(), null, false);
    }
}

package com.harsh.hospitalmanagement.storage;

import com.harsh.hospitalmanagement.service.DoctorVerificationService.DocumentKind;
import com.harsh.hospitalmanagement.storage.VerificationImageValidator.ValidatedImage;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** Internal adapter. Callers must authorize access before resolving or using a document key. */
public interface DocumentStorage {

    DocumentKey store(long doctorId, DocumentKind kind, ValidatedImage image) throws IOException;

    ReadDocument read(DocumentKey key) throws IOException;

    /** Throws if the file is missing or cannot be authenticated with its recorded key version. */
    void verifyIntegrity(DocumentKey key) throws IOException;

    /** Checks for a safely addressable file. Use verifyIntegrity to authenticate its contents. */
    boolean exists(DocumentKey key) throws IOException;

    /** Returns false for an absent document. Reference and retention checks belong to the caller. */
    boolean delete(DocumentKey key) throws IOException;

    /**
     * Enumerates stored document candidates after {@code cursor}, bounded by {@code maxEntries}.
     * Used by retention reconciliation; callers must re-check catalog references under DB lock.
     */
    default DocumentScanPage listStoredDocuments(String cursor, int maxEntries) throws IOException {
        return DocumentScanPage.EMPTY;
    }

    record ReadDocument(String contentType, String downloadFilename, byte[] bytes) {
    }

    record StoredDocument(DocumentKey key, Instant lastModifiedAt) {
    }

    record DocumentScanPage(List<StoredDocument> documents, String nextCursor, boolean hasMore) {
        public static final DocumentScanPage EMPTY = new DocumentScanPage(List.of(), null, false);
    }

    static DocumentStorage createFilesystemStorage(Path root, MasterKeyProvider keys, Supplier<UUID> versions) throws IOException {
        return new FilesystemDocumentStorage(new WindowsPrivateFiles(root), keys, versions != null ? versions : UUID::randomUUID);
    }
}

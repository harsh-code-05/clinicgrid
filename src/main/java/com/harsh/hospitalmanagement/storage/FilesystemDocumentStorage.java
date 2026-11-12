package com.harsh.hospitalmanagement.storage;

import com.harsh.hospitalmanagement.service.DoctorVerificationService.DocumentKind;
import com.harsh.hospitalmanagement.storage.VerificationImageValidator.ValidatedImage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

final class FilesystemDocumentStorage implements DocumentStorage {

    private static final Path VERIFICATION_PREFIX = Path.of("verification");

    private final PrivateFiles files;
    private final DocumentCipher cipher;
    private final Supplier<UUID> versions;

    FilesystemDocumentStorage(PrivateFiles files, MasterKeyProvider keys, Supplier<UUID> versions) {
        this.files = files;
        cipher = new DocumentCipher(keys);
        this.versions = versions;
    }

    @Override
    public DocumentKey store(long doctorId, DocumentKind kind, ValidatedImage image) throws IOException {
        var key = new DocumentKey(doctorId, kind, versions.get());
        files.writeNew(key.relativePath(), cipher.encrypt(key, image));
        return key;
    }

    @Override
    public ReadDocument read(DocumentKey key) throws IOException {
        var document = decrypt(key);
        var format = document.format();
        return new ReadDocument(format.mime, key.kind().pathSegment() + "." + format.extension, document.bytes());
    }

    @Override
    public void verifyIntegrity(DocumentKey key) throws IOException {
        Arrays.fill(decrypt(key).bytes(), (byte) 0);
    }

    @Override
    public boolean exists(DocumentKey key) throws IOException {
        return files.exists(key.relativePath());
    }

    @Override
    public boolean delete(DocumentKey key) throws IOException {
        return files.delete(key.relativePath());
    }

    @Override
    public DocumentScanPage listStoredDocuments(String cursor, int maxEntries) throws IOException {
        PrivateFiles.FileScanResult scan = files.scanFiles(VERIFICATION_PREFIX, cursor, maxEntries);
        if (scan == null || (scan.entries().isEmpty() && !scan.hasMore())) {
            return DocumentScanPage.EMPTY;
        }
        List<StoredDocument> documents = new ArrayList<>(scan.entries().size());
        for (PrivateFiles.FileEntry entry : scan.entries()) {
            String keyStr = entry.relativePath().toString().replace('\\', '/');
            try {
                documents.add(new StoredDocument(DocumentKey.parse(keyStr), entry.lastModifiedAt()));
            } catch (IllegalArgumentException ignored) {
                // Skip non-canonical filenames without exposing internal paths.
            }
        }
        return new DocumentScanPage(List.copyOf(documents), scan.nextCursor(), scan.hasMore());
    }

    private DocumentCipher.Decoded decrypt(DocumentKey key) throws IOException {
        return cipher.decrypt(key, files.read(key.relativePath(), DocumentCipher.MAX_ENCRYPTED_BYTES));
    }
}

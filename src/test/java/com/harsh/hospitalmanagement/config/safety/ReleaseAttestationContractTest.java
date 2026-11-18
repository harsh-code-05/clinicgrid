package com.harsh.hospitalmanagement.config.safety;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReleaseAttestationContractTest {
    private static final Path SOURCE = Path.of("src/main/java/com/harsh/hospitalmanagement/config/safety/ReleaseAttestationGate.java");

    @Test
    void usesCanonicalClasspathDigestAndOneConnection() throws Exception {
        String source = Files.readString(SOURCE);
        assertThat(source).contains("database/migration-catalog-digest.sql");
        assertThat(source).contains("pg_temp.hms_catalog_digest()");
        assertThat(source).contains("jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>) connection ->");
        assertThat(source).doesNotContain("hms_migration.hms_catalog_digest()");
    }

    @Test
    void privilegeContractCoversEffectiveRolesAndTemporaryFunctions() throws Exception {
        String source = Files.readString(SOURCE);
        assertThat(source).contains("pg_has_role(current_user, r.oid, 'USAGE')");
        assertThat(source).contains("pg_has_role(current_user, r.oid, 'SET')");
        assertThat(source).contains("rolbypassrls");
        assertThat(source).contains("has_database_privilege(current_user, current_database(), 'TEMP')");
        assertThat(source).contains("pg_catalog.pg_class");
        assertThat(source).contains("pg_catalog.pg_proc");
    }

    @Test
    void acceptsExactAuxiliaryResourceBytes() throws Exception {
        byte[] bytes = "SELECT 1".getBytes(StandardCharsets.UTF_8);
        String checksum = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        assertThatCode(() -> ReleaseAttestationGate.validatePackagedResource("database/catalog.sql", checksum,
                resourceLoader(bytes))).doesNotThrowAnyException();
    }

    @Test
    void rejectsAuxiliaryResourceChecksumMismatch() {
        assertThatThrownBy(() -> ReleaseAttestationGate.validatePackagedResource("database/catalog.sql", "0".repeat(64),
                resourceLoader("SELECT 1".getBytes(StandardCharsets.UTF_8)))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsCrlfResourceWhenLfChecksumExpected() throws Exception {
        byte[] lfBytes = "SELECT 1;\n".getBytes(StandardCharsets.UTF_8);
        byte[] crlfBytes = "SELECT 1;\r\n".getBytes(StandardCharsets.UTF_8);
        String lfChecksum = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(lfBytes));
        assertThatThrownBy(() -> ReleaseAttestationGate.validatePackagedResource("database/catalog.sql", lfChecksum,
                resourceLoader(crlfBytes)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("checksum mismatch");
    }

    @Test
    void rejectsExtraTrailingByte() throws Exception {
        byte[] baseBytes = "SELECT 1;\n".getBytes(StandardCharsets.UTF_8);
        byte[] extraByte = "SELECT 1;\n ".getBytes(StandardCharsets.UTF_8);
        String baseChecksum = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(baseBytes));
        assertThatThrownBy(() -> ReleaseAttestationGate.validatePackagedResource("database/catalog.sql", baseChecksum,
                resourceLoader(extraByte)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("checksum mismatch");
    }

    @Test
    void rejectsModifiedByte() throws Exception {
        byte[] original = "SELECT 1;\n".getBytes(StandardCharsets.UTF_8);
        byte[] modified = "SELECT 2;\n".getBytes(StandardCharsets.UTF_8);
        String originalChecksum = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(original));
        assertThatThrownBy(() -> ReleaseAttestationGate.validatePackagedResource("database/catalog.sql", originalChecksum,
                resourceLoader(modified)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("checksum mismatch");
    }

    @Test
    void rejectsMissingAuxiliaryResource() {
        assertThatThrownBy(() -> ReleaseAttestationGate.validatePackagedResource("database/missing.sql", "0".repeat(64),
                resourceLoader(null))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsMalformedAndTraversalResourceReferences() throws Exception {
        byte[] bytes = "x".getBytes(StandardCharsets.UTF_8);
        String checksum = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        assertThatThrownBy(() -> ReleaseAttestationGate.validatePackagedResource("../secret.sql", checksum, resourceLoader(bytes)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ReleaseAttestationGate.validatePackagedResource("database\\secret.sql", checksum, resourceLoader(bytes)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ReleaseAttestationGate.validatePackagedResource("database/secret.sql", "not-a-checksum", resourceLoader(bytes)))
                .isInstanceOf(IllegalStateException.class);
    }

    private static ClassLoader resourceLoader(byte[] bytes) {
        return new ClassLoader() {
            @Override public java.io.InputStream getResourceAsStream(String name) {
                return bytes == null ? null : new ByteArrayInputStream(bytes);
            }
        };
    }
}

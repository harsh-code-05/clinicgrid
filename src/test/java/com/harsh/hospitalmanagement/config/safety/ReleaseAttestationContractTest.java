package com.harsh.hospitalmanagement.config.safety;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ReleaseAttestationContractTest {
    private static final Path SOURCE = Path.of("src/main/java/com/harsh/hospitalmanagement/config/safety/ReleaseAttestationGate.java");

    @Test
    void usesUnmodifiedCanonicalQueryBodyWithoutTheFunctionWrapper() throws Exception {
        String sql = Files.readString(Path.of("database/migration-catalog-digest.sql"));
        String query = ReleaseAttestationGate.extractCatalogDigestQuery(sql);
        int bodyStart = sql.indexOf("    WITH namespaces AS");
        int bodyEnd = sql.lastIndexOf("\n$digest$;");
        assertThat(query).isEqualTo(sql.substring(bodyStart, bodyEnd).stripTrailing())
                .contains("ORDER BY nspname, relname, kind, name", "sha256(convert_to(")
                .doesNotContain("CREATE FUNCTION", "pg_temp.hms_catalog_digest()");
        assertThatThrownBy(() -> ReleaseAttestationGate.extractCatalogDigestQuery(
                sql.replace("SET search_path TO pg_catalog", "SET search_path TO public")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("wrapper");
    }

    @Test
    void executesOnlyTheDigestQueryInOneReadOnlyTransactionAndRestoresTheConnection() throws Exception {
        String query = "WITH fixture AS (SELECT 1) SELECT 'fixture' FROM fixture";
        String expected = "a".repeat(64);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet result = mock(ResultSet.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.execute(org.mockito.ArgumentMatchers.<ConnectionCallback<String>>any()))
                .thenAnswer(call -> call.<ConnectionCallback<String>>getArgument(0).doInConnection(connection));
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(query)).thenReturn(result);
        when(result.next()).thenReturn(true, false);
        when(result.getString(1)).thenReturn(expected);

        assertThat(ReleaseAttestationGate.calculateCatalogDigest(jdbc, query)).isEqualTo(expected);

        var calls = inOrder(connection, statement, result);
        calls.verify(connection).getAutoCommit();
        calls.verify(connection).setAutoCommit(false);
        calls.verify(connection).createStatement();
        calls.verify(statement).execute("SET TRANSACTION READ ONLY");
        calls.verify(statement).execute("SET LOCAL search_path TO pg_catalog");
        calls.verify(statement).executeQuery(query);
        calls.verify(result).next();
        calls.verify(result).getString(1);
        calls.verify(result).next();
        calls.verify(result).close();
        calls.verify(statement).close();
        calls.verify(connection).rollback();
        calls.verify(connection).setAutoCommit(true);
        verifyNoMoreInteractions(connection, statement, result);
    }

    @Test
    void failsClosedAndRollsBackWhenDigestQueryDoesNotReturnAResult() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet result = mock(ResultSet.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.execute(org.mockito.ArgumentMatchers.<ConnectionCallback<String>>any()))
                .thenAnswer(call -> call.<ConnectionCallback<String>>getArgument(0).doInConnection(connection));
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT fixture")).thenReturn(result);

        assertThatThrownBy(() -> ReleaseAttestationGate.calculateCatalogDigest(jdbc, "SELECT fixture"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not returned");

        var calls = inOrder(connection);
        calls.verify(connection).rollback();
        calls.verify(connection).setAutoCommit(true);
    }

    @Test
    void privilegeContractStillCoversEffectiveRolesAndDdlRestrictions() throws Exception {
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

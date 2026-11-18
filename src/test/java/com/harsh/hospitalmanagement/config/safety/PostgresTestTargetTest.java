package com.harsh.hospitalmanagement.config.safety;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostgresTestTargetTest {
    private static final String DATABASE = "hms_dev_test_0123456789abcdef0123456789abcdef";
    private static final String USER = "synthetic_test_user";
    private static final String PASSWORD = "synthetic test password";
    private static final long OID = 20202L;

    private final PostgresTestTarget target = PostgresTestTarget.fromEnvironment(environment());
    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final Statement statement = mock(Statement.class);
    private final ResultSet identity = mock(ResultSet.class);
    private final ResultSet attestation = mock(ResultSet.class);
    private final ResultSet ledger = mock(ResultSet.class);

    @BeforeEach
    void stubReadOnlyConnection() throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(contains("pg_catalog.pg_database"))).thenReturn(identity);
        when(statement.executeQuery(contains("hms_migration.release_target"))).thenReturn(attestation);
        when(statement.executeQuery(contains("hms_migration.migration_ledger"))).thenReturn(ledger);
        when(identity.next()).thenReturn(true, false);
        when(identity.getString(1)).thenReturn(DATABASE);
        when(identity.getLong(2)).thenReturn(OID);
        when(identity.getString(3)).thenReturn(USER);
        when(identity.getString(4)).thenReturn(USER);
        when(identity.getString(5)).thenReturn("127.0.0.1");
        when(identity.getInt(6)).thenReturn(5433);
        when(identity.getInt(7)).thenReturn(180001);
        when(identity.getString(8)).thenReturn("on");
        when(attestation.next()).thenReturn(true, false);
        when(attestation.getString(1)).thenReturn(DATABASE);
        when(attestation.getLong(2)).thenReturn(OID);
        when(attestation.getString(3)).thenReturn("DISPOSABLE_TEST");
        when(ledger.next()).thenReturn(true, true, true, true, true, true, true, true, true, false);
        when(ledger.getInt(1)).thenReturn(1, 2, 3, 4, 5, 6, 7, 8, 9);
        when(ledger.getString(2)).thenReturn(
                "BASELINE", "SCH-02", "DISCO-01", "DISCO-02", "DOC-01", "AI-01", "AI-02", "APPT-CANCEL-01", "BILL-01");
        when(ledger.getString(3)).thenReturn("a".repeat(64));
        when(ledger.getString(4)).thenReturn("b".repeat(64));
        when(ledger.getBoolean(5)).thenReturn(true);
    }

    @Test
    void noOptInWithoutExactTrue() {
        assertThat(PostgresTestTarget.enabled(Map.of())).isFalse();
        assertThat(PostgresTestTarget.enabled(Map.of(PostgresTestTarget.ENABLED, "TRUE"))).isFalse();
        assertThat(PostgresTestTarget.enabled(Map.of(PostgresTestTarget.ENABLED, "true"))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ENABLED", "DATABASE", "OID", "CONFIRM", "USER", "PASSWORD"})
    void everyOptInVariableIsRequired(String suffix) {
        var environment = environment();
        String key = "HMS_POSTGRES_TEST_" + suffix;
        environment.remove(key);

        assertThatIllegalArgumentException().isThrownBy(() -> PostgresTestTarget.fromEnvironment(environment))
                .withMessageContaining(key).withMessageNotContaining(PASSWORD);
    }

    @ParameterizedTest
    @CsvSource({
            "DATABASE,hospital_management",
            "DATABASE,hms_dev_test_0123456789ABCDEF0123456789abcdef",
            "DATABASE,hms_dev_test_0123456789abcdef0123456789abcdef?sslmode=require",
            "OID,16560",
            "OID,4294967296",
            "OID,0",
            "CONFIRM,hms_dev_test_0123456789abcdef0123456789abcdef:20203"
    })
    void rejectsAmbiguousOrForbiddenValuesBeforeConnecting(String suffix, String value) {
        var environment = environment();
        environment.put("HMS_POSTGRES_TEST_" + suffix, value);

        assertThatIllegalArgumentException().isThrownBy(() -> PostgresTestTarget.fromEnvironment(environment))
                .withMessageNotContaining(PASSWORD);
    }

    @Test
    void verifiesIdentityAndSingleAttestationInsideReadOnlyTransaction() throws SQLException {
        assertThat(target.url()).isEqualTo("jdbc:postgresql://127.0.0.1:5433/" + DATABASE);
        target.verify(dataSource);

        var calls = org.mockito.Mockito.inOrder(connection, statement);
        calls.verify(connection).setReadOnly(true);
        calls.verify(connection).setAutoCommit(false);
        calls.verify(statement, times(3)).executeQuery(anyString());
        calls.verify(connection).rollback();
        calls.verify(connection).close();
    }

    @ParameterizedTest
    @CsvSource({"1,hospital_management", "3,another_user", "4,another_login", "5,::1", "8,off"})
    void rejectsMismatchedTextIdentityBeforeReadingAttestation(int column, String value) throws SQLException {
        when(identity.getString(column)).thenReturn(value);
        assertThatIllegalStateException().isThrownBy(() -> target.verify(dataSource))
                .withMessageContaining("identity");
        verify(statement, never()).executeQuery(contains("hms_migration.release_target"));
    }

    @ParameterizedTest
    @CsvSource({"6,5432", "7,180000"})
    void rejectsWrongPortOrVersion(int column, int value) throws SQLException {
        when(identity.getInt(column)).thenReturn(value);
        assertThatIllegalStateException().isThrownBy(() -> target.verify(dataSource))
                .withMessageContaining("identity");
    }

    @Test
    void rejectsWrongOid() throws SQLException {
        when(identity.getLong(2)).thenReturn(16560L);
        assertThatIllegalStateException().isThrownBy(() -> target.verify(dataSource))
                .withMessageContaining("identity");
    }

    @Test
    void rejectsMissingAttestation() throws SQLException {
        when(attestation.next()).thenReturn(false);
        assertThatIllegalStateException().isThrownBy(() -> target.verify(dataSource))
                .withMessageContaining("attestation");
    }

    @Test
    void rejectsDuplicateAttestation() throws SQLException {
        when(attestation.next()).thenReturn(true, true);
        assertThatIllegalStateException().isThrownBy(() -> target.verify(dataSource))
                .withMessageContaining("attestation");
    }

    @Test
    void rejectsDifferentAttestationPurpose() throws SQLException {
        when(attestation.getString(3)).thenReturn("RELEASE");
        assertThatIllegalStateException().isThrownBy(() -> target.verify(dataSource))
                .withMessageContaining("attestation");
    }

    @Test
    void rejectsIncompleteMigrationLedger() throws SQLException {
        when(ledger.next()).thenReturn(false);
        assertThatIllegalStateException().isThrownBy(() -> target.verify(dataSource))
                .withMessageContaining("incomplete migration ledger");
    }

    @Test
    void rejectsUnexpectedAdditionalMigrations() throws SQLException {
        when(ledger.next()).thenReturn(true);
        assertThatIllegalStateException().isThrownBy(() -> target.verify(dataSource))
                .withMessageContaining("unexpected migrations");
    }

    @Test
    void doesNotIncludeRawConnectionErrors() throws SQLException {
        when(dataSource.getConnection()).thenThrow(new SQLException(PASSWORD));
        assertThatIllegalStateException().isThrownBy(() -> target.verify(dataSource))
                .withMessageNotContaining(PASSWORD).withNoCause();
    }

    private static Map<String, String> environment() {
        return new HashMap<>(Map.of(
                PostgresTestTarget.ENABLED, "true",
                "HMS_POSTGRES_TEST_DATABASE", DATABASE,
                "HMS_POSTGRES_TEST_OID", Long.toString(OID),
                "HMS_POSTGRES_TEST_CONFIRM", DATABASE + ":" + OID,
                "HMS_POSTGRES_TEST_USER", USER,
                "HMS_POSTGRES_TEST_PASSWORD", PASSWORD));
    }
}

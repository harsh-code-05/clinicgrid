package com.harsh.hospitalmanagement.config.safety;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/** Selects and attests the synthetic PostgreSQL database before Spring initializes JPA. */
public final class PostgresTestTarget {

    static final String ENABLED = "HMS_POSTGRES_TEST_ENABLED";
    private static final String DATABASE = "HMS_POSTGRES_TEST_DATABASE";
    private static final String OID = "HMS_POSTGRES_TEST_OID";
    private static final String CONFIRM = "HMS_POSTGRES_TEST_CONFIRM";
    private static final String USER = "HMS_POSTGRES_TEST_USER";
    private static final String PASSWORD = "HMS_POSTGRES_TEST_PASSWORD";
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 5433;
    private static final String H2_URL = "jdbc:h2:mem:doctor_audit_atomicity;DB_CLOSE_DELAY=-1;"
            + "DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL";
    private static final List<String> MIGRATIONS = List.of(
            "BASELINE", "SCH-02", "DISCO-01", "DISCO-02", "DOC-01", "AI-01", "AI-02", "APPT-CANCEL-01", "BILL-01");

    private final String database;
    private final long oid;
    private final String user;
    private final String password;

    private PostgresTestTarget(String database, long oid, String user, String password) {
        this.database = database;
        this.oid = oid;
        this.user = user;
        this.password = password;
    }

    static boolean enabled(Map<String, String> environment) {
        return "true".equals(environment.get(ENABLED));
    }

    static PostgresTestTarget fromEnvironment(Map<String, String> environment) {
        if (!enabled(environment)) {
            throw new IllegalArgumentException(ENABLED + " must be exactly true");
        }
        String database = required(environment, DATABASE);
        if (!database.matches("hms_dev_test_[0-9a-f]{32}")) {
            throw new IllegalArgumentException(DATABASE + " must match hms_dev_test_[0-9a-f]{32}");
        }
        String oidText = required(environment, OID);
        if (!oidText.matches("[1-9][0-9]{0,9}")) {
            throw new IllegalArgumentException(OID + " must be a positive decimal OID");
        }
        long oid = Long.parseLong(oidText);
        if (oid > 0xffffffffL || oid == 16560L) {
            throw new IllegalArgumentException(OID + " is outside the permitted OID range");
        }
        if (!(database + ":" + oidText).equals(required(environment, CONFIRM))) {
            throw new IllegalArgumentException(CONFIRM + " must exactly match the disposable database name and OID");
        }
        return new PostgresTestTarget(database, oid, required(environment, USER), required(environment, PASSWORD));
    }

    private static String required(Map<String, String> environment, String key) {
        String value = environment.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Required test environment variable is missing: " + key);
        }
        return value;
    }

    String url() {
        return "jdbc:postgresql://" + HOST + ":" + PORT + "/" + database;
    }

    public static void registerProperties(DynamicPropertyRegistry registry) {
        Map<String, String> environment = System.getenv();
        PostgresTestTarget target = enabled(environment) ? fromEnvironment(environment) : null;
        registry.add("spring.datasource.url", () -> target == null ? H2_URL : target.url());
        registry.add("spring.jpa.hibernate.ddl-auto", () -> target == null ? "create-drop" : "validate");
        registry.add("spring.jpa.generate-ddl", () -> false);
        registry.add("spring.sql.init.mode", () -> "never");
        registry.add("spring.flyway.enabled", () -> false);
        registry.add("spring.liquibase.enabled", () -> false);
        registry.add("hms.ai.cloudflare.account-id", () -> "");
        registry.add("hms.ai.cloudflare.api-token", () -> "");
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class TestDataSourceConfiguration {

        @Bean(destroyMethod = "close")
        HikariDataSource dataSource() {
            Map<String, String> environment = System.getenv();
            if (enabled(environment)) {
                return fromEnvironment(environment).attestedDataSource();
            }
            // The hybrid audit suite uses only this in-memory datasource without PostgreSQL opt-in.
            return PostgresTestTarget.dataSource(H2_URL, "sa", "", "org.h2.Driver");
        }
    }

    HikariDataSource attestedDataSource() {
        return attestedDataSource(false);
    }

    HikariDataSource attestedDataSource(boolean readOnly) {
        HikariDataSource dataSource = dataSource(url(), user, password, "org.postgresql.Driver");
        dataSource.setReadOnly(readOnly);
        dataSource.addDataSourceProperty("sslmode", "disable");
        dataSource.addDataSourceProperty("gssEncMode", "disable");
        dataSource.addDataSourceProperty("connectTimeout", "5");
        try {
            verify(dataSource);
            return dataSource;
        } catch (RuntimeException failure) {
            dataSource.close();
            throw failure;
        }
    }

    private static HikariDataSource dataSource(String url, String user, String password, String driver) {
        var dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(url);
        dataSource.setUsername(user);
        dataSource.setPassword(password);
        dataSource.setDriverClassName(driver);
        dataSource.setConnectionTimeout(5000);
        dataSource.setMinimumIdle(0);
        return dataSource;
    }

    void verify(DataSource dataSource) {
        try (var connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(5);
                try (var result = statement.executeQuery("""
                        SELECT pg_catalog.current_database(), d.oid::bigint, current_user, session_user,
                            pg_catalog.host(pg_catalog.inet_server_addr()), pg_catalog.inet_server_port(),
                            pg_catalog.current_setting('server_version_num')::integer,
                            pg_catalog.current_setting('transaction_read_only')
                        FROM pg_catalog.pg_database d WHERE d.datname = pg_catalog.current_database()
                        """)) {
                    if (!result.next()
                            || !database.equals(result.getString(1)) || oid != result.getLong(2)
                            || !user.equals(result.getString(3)) || !user.equals(result.getString(4))
                            || !HOST.equals(result.getString(5)) || result.getInt(6) != PORT
                            || result.getInt(7) < 180001 || !"on".equals(result.getString(8))
                            || result.next()) {
                        throw new IllegalStateException("PostgreSQL test target identity did not match the explicit opt-in");
                    }
                }
                try (var result = statement.executeQuery("""
                        SELECT database_name::text, database_oid::bigint, purpose
                        FROM hms_migration.release_target LIMIT 2
                        """)) {
                    if (!result.next() || !database.equals(result.getString(1))
                            || oid != result.getLong(2) || !"DISPOSABLE_TEST".equals(result.getString(3))
                            || result.next()) {
                        throw new IllegalStateException("PostgreSQL test target must have exactly one matching disposable attestation");
                    }
                }
                try (var result = statement.executeQuery("""
                        SELECT version, migration_id, sha256, catalog_sha256, applied_at IS NOT NULL
                        FROM hms_migration.migration_ledger ORDER BY version LIMIT 10
                        """)) {
                    for (int i = 0; i < MIGRATIONS.size(); i++) {
                        if (!result.next() || result.getInt(1) != i + 1 || !MIGRATIONS.get(i).equals(result.getString(2))
                                || !isSha256(result.getString(3)) || !isSha256(result.getString(4))
                                || !result.getBoolean(5)) {
                            throw new IllegalStateException("PostgreSQL test target has an incomplete migration ledger");
                        }
                    }
                    if (result.next()) {
                        throw new IllegalStateException("PostgreSQL test target has unexpected migrations");
                    }
                }
            } finally {
                connection.rollback();
            }
        } catch (SQLException | com.zaxxer.hikari.pool.HikariPool.PoolInitializationException failure) {
            throw new IllegalStateException("Could not verify the authenticated disposable PostgreSQL test connection");
        }
    }

    private static boolean isSha256(String checksum) {
        return checksum != null && checksum.matches("[0-9a-f]{64}");
    }
}

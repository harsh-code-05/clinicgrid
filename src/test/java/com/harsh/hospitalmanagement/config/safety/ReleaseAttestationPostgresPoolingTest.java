package com.harsh.hospitalmanagement.config.safety;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises real PostgreSQL backends through Hikari, without a dependency on a hosted database.
 * The opt-in requires a fresh, nonce-marked local cluster with synthetic trust authentication.
 */
@EnabledIfSystemProperty(named = "hms.attestation.pg.test.enabled", matches = "true")
class ReleaseAttestationPostgresPoolingTest {
    private static final String ADMIN = "hms_attestation_admin";
    private static final String RUNTIME = "hms_attestation_runtime";
    private static final Pattern MIGRATION = Pattern.compile("""
            "version"\\s*:\\s*(\\d+),\\s*"id"\\s*:\\s*"([^"]+)",\\s*"path"\\s*:\\s*"[^"]+",\\s*"sha256"\\s*:\\s*"([0-9a-f]{64})"
            """.strip());

    private static String url;
    private static String oid;
    private static String digestQuery;
    private static String expectedDigest;

    @BeforeAll
    static void createLocalFixture() throws Exception {
        String database = requireProperty("database", "hms_attestation_test_[a-f0-9]{32}");
        String nonce = requireProperty("nonce", "[a-f0-9]{32}");
        oid = requireProperty("oid", "[1-9][0-9]{0,9}");
        long databaseOid = Long.parseLong(oid);
        int port = Integer.parseInt(requireProperty("port", "[1-9][0-9]{3,4}"));
        if (port < 1024 || port > 65535 || port == 5432 || port == 5433
                || databaseOid < 16384 || databaseOid > 0xffffffffL || databaseOid == 16560L) {
            throw new IllegalArgumentException("Run only against a fresh synthetic database on a dedicated loopback port");
        }
        url = "jdbc:postgresql://127.0.0.1:" + port + "/" + database
                + "?sslmode=disable&gssEncMode=disable&connectTimeout=5";
        String reviewedSql = resource("database/migration-catalog-digest.sql");
        digestQuery = ReleaseAttestationGate.extractCatalogDigestQuery(reviewedSql);

        try (Connection connection = DriverManager.getConnection(url, ADMIN, "")) {
            JdbcTemplate admin = pinned(connection);
            verifyFixture(admin, database, databaseOid, port, nonce);
            createAttestedCatalog(admin);
            admin.execute("CREATE TEMP TABLE hms_attestation_legacy_marker (id integer)");
            admin.execute(reviewedSql);
            expectedDigest = admin.queryForObject("SELECT pg_temp.hms_catalog_digest()", String.class);
            assertThat(expectedDigest).matches("[0-9a-f]{64}");
            populateLedger(admin, resource("database/migration-manifest.json"));
        }
    }

    @Test
    void executesCanonicalDigestWithSameResultAsMigrationHelper() {
        try (HikariDataSource pool = runtimePool()) {
            JdbcTemplate runtime = new JdbcTemplate(pool);
            assertThat(ReleaseAttestationGate.calculateCatalogDigest(runtime, digestQuery)).isEqualTo(expectedDigest);
            new ReleaseAttestationGate(runtime, oid).verifyBeforeWebServerStartup();
        }
    }

    @Test
    void existingOldTemporaryFunctionDoesNotBlockOrOverrideAttestationOnReusedSession() throws SQLException {
        try (HikariDataSource pool = runtimePool(); Connection connection = pool.getConnection()) {
            JdbcTemplate runtime = pinned(connection);
            runtime.execute("CREATE TEMP TABLE hms_attestation_prior_session (id integer)");
            runtime.execute("""
                    CREATE FUNCTION pg_temp.hms_catalog_digest() RETURNS text
                    LANGUAGE sql AS 'SELECT pg_catalog.repeat(''0'', 64)'
                    """);
            runtime.execute("SET search_path TO public, hms_migration, pg_catalog");
            String originalSearchPath = runtime.queryForObject("SHOW search_path", String.class);
            int backendPid = backendPid(runtime);

            new ReleaseAttestationGate(runtime, oid).verifyBeforeWebServerStartup();
            new ReleaseAttestationGate(runtime, oid).verifyBeforeWebServerStartup();

            assertThat(backendPid(runtime)).isEqualTo(backendPid);
            assertThat(runtime.queryForObject("SELECT pg_temp.hms_catalog_digest()", String.class))
                    .isEqualTo("0".repeat(64));
            assertThat(runtime.queryForObject("SHOW search_path", String.class)).isEqualTo(originalSearchPath);
        }
    }

    @Test
    void independentPooledBackendsReturnSameDigestAndCreateNoTemporaryFunction() throws SQLException {
        try (HikariDataSource pool = runtimePool();
             Connection first = pool.getConnection();
             Connection second = pool.getConnection()) {
            JdbcTemplate firstBackend = pinned(first);
            JdbcTemplate secondBackend = pinned(second);
            assertThat(backendPid(firstBackend)).isNotEqualTo(backendPid(secondBackend));

            for (JdbcTemplate runtime : List.of(firstBackend, secondBackend, firstBackend)) {
                assertThat(ReleaseAttestationGate.calculateCatalogDigest(runtime, digestQuery)).isEqualTo(expectedDigest);
                new ReleaseAttestationGate(runtime, oid).verifyBeforeWebServerStartup();
                assertThat(runtime.queryForObject("""
                        SELECT count(*)
                        FROM pg_catalog.pg_proc
                        WHERE proname = 'hms_catalog_digest'
                          AND pronamespace = pg_catalog.pg_my_temp_schema()
                        """, Integer.class)).isZero();
            }
        }
    }

    @Test
    void changedCanonicalCatalogFailsClosed() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, ADMIN, "");
             HikariDataSource pool = runtimePool()) {
            JdbcTemplate admin = pinned(connection);
            JdbcTemplate runtime = new JdbcTemplate(pool);
            admin.execute("ALTER TABLE public.hms_attestation_child ADD COLUMN drift_probe integer");
            try {
                assertThat(ReleaseAttestationGate.calculateCatalogDigest(runtime, digestQuery))
                        .isNotEqualTo(expectedDigest);
                assertStartupBlocked(runtime, oid);
            } finally {
                admin.execute("ALTER TABLE public.hms_attestation_child DROP COLUMN drift_probe");
            }
            assertThat(ReleaseAttestationGate.calculateCatalogDigest(runtime, digestQuery)).isEqualTo(expectedDigest);
        }
    }

    @Test
    void missingExpectedDigestFailsClosed() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, ADMIN, "");
             HikariDataSource pool = runtimePool()) {
            JdbcTemplate admin = pinned(connection);
            admin.execute("ALTER TABLE hms_migration.migration_ledger ALTER COLUMN catalog_sha256 DROP NOT NULL");
            try {
                admin.update("UPDATE hms_migration.migration_ledger SET catalog_sha256 = NULL WHERE version = 9");
                assertStartupBlocked(new JdbcTemplate(pool), oid);
            } finally {
                admin.update("UPDATE hms_migration.migration_ledger SET catalog_sha256 = ? WHERE version = 9", expectedDigest);
                admin.execute("ALTER TABLE hms_migration.migration_ledger ALTER COLUMN catalog_sha256 SET NOT NULL");
            }
        }
    }

    @Test
    void wrongDatabaseOidFailsClosed() {
        try (HikariDataSource pool = runtimePool()) {
            assertStartupBlocked(new JdbcTemplate(pool), Long.toString(Long.parseLong(oid) - 1));
        }
    }

    @Test
    void wrongDeploymentPurposeFailsClosed() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, ADMIN, "");
             HikariDataSource pool = runtimePool()) {
            JdbcTemplate admin = pinned(connection);
            admin.update("UPDATE hms_migration.release_target SET purpose = 'DISPOSABLE_TEST'");
            try {
                assertStartupBlocked(new JdbcTemplate(pool), oid);
            } finally {
                admin.update("UPDATE hms_migration.release_target SET purpose = 'DEPLOYMENT'");
            }
        }
    }

    @Test
    void runtimeCanAttestWithoutPermanentSchemaDdlPrivileges() throws SQLException {
        try (HikariDataSource pool = runtimePool(); Connection connection = pool.getConnection()) {
            JdbcTemplate runtime = pinned(connection);
            assertThat(runtime.queryForObject("SELECT current_user", String.class)).isEqualTo(RUNTIME);
            assertThat(runtime.queryForObject(
                    "SELECT pg_catalog.has_schema_privilege(current_user, 'public', 'CREATE')", Boolean.class))
                    .isFalse();
            new ReleaseAttestationGate(runtime, oid).verifyBeforeWebServerStartup();

            // Rollback also keeps the fixture clean if a misconfigured role unexpectedly permits DDL.
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                assertThatThrownBy(() -> statement.execute("CREATE TABLE public.hms_attestation_ddl_probe (id integer)"))
                        .isInstanceOfSatisfying(SQLException.class,
                                error -> assertThat(error.getSQLState()).isEqualTo("42501"));
            } finally {
                connection.rollback();
            }
        }
    }

    private static void verifyFixture(JdbcTemplate admin, String database, long databaseOid, int port, String nonce) {
        Boolean identityVerified = admin.queryForObject("""
                SELECT current_database() = ? AND d.oid::bigint = ?
                    AND session_user = ? AND current_user = ?
                    AND pg_catalog.host(pg_catalog.inet_server_addr()) = '127.0.0.1'
                    AND pg_catalog.inet_server_port() = ?
                    AND pg_catalog.current_setting('server_version_num')::integer >= 180001
                    AND pg_catalog.to_regnamespace('hms_migration') IS NULL
                    AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = ?)
                FROM pg_catalog.pg_database d WHERE d.datname = current_database()
                """, Boolean.class, database, databaseOid, ADMIN, ADMIN, port, RUNTIME);
        if (!Boolean.TRUE.equals(identityVerified) || !admin.queryForList(
                "SELECT nonce FROM hms_attestation_fixture.guard LIMIT 2", String.class).equals(List.of(nonce))) {
            throw new IllegalStateException("PostgreSQL target is not the confirmed fresh attestation fixture");
        }
    }

    private static void createAttestedCatalog(JdbcTemplate admin) {
        admin.execute("CREATE ROLE " + RUNTIME + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT");
        admin.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
        admin.execute("CREATE SCHEMA hms_migration");
        admin.execute("REVOKE ALL ON SCHEMA hms_migration FROM PUBLIC");
        admin.execute("""
                CREATE TABLE hms_migration.release_target (
                    database_name name PRIMARY KEY,
                    database_oid oid NOT NULL,
                    purpose text NOT NULL CHECK (purpose IN ('DISPOSABLE_TEST', 'LOCAL_DEVELOPMENT', 'DEPLOYMENT'))
                )
                """);
        admin.execute("""
                INSERT INTO hms_migration.release_target
                SELECT current_database(), oid, 'DEPLOYMENT'
                FROM pg_catalog.pg_database WHERE datname = current_database()
                """);
        admin.execute("""
                CREATE TABLE hms_migration.migration_ledger (
                    version integer PRIMARY KEY CHECK (version > 0),
                    migration_id text UNIQUE NOT NULL,
                    sha256 text NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
                    catalog_sha256 text NOT NULL CHECK (catalog_sha256 ~ '^[0-9a-f]{64}$'),
                    applied_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        admin.execute("GRANT USAGE ON SCHEMA hms_migration TO " + RUNTIME);
        admin.execute("GRANT SELECT ON hms_migration.release_target, hms_migration.migration_ledger TO " + RUNTIME);

        admin.execute("CREATE TYPE public.hms_attestation_status AS ENUM ('ready', 'pending')");
        admin.execute("CREATE TABLE public.hms_attestation_parent (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY)");
        admin.execute("""
                CREATE TABLE public.hms_attestation_child (
                    parent_id bigint NOT NULL REFERENCES public.hms_attestation_parent,
                    status public.hms_attestation_status NOT NULL DEFAULT 'ready'
                )
                """);
        admin.execute("""
                CREATE INDEX hms_attestation_status_idx ON public.hms_attestation_child (status)
                WHERE status = 'pending'
                """);
        admin.execute("""
                CREATE VIEW public.hms_attestation_view AS
                SELECT parent_id, status FROM public.hms_attestation_child
                """);
        admin.execute("""
                CREATE FUNCTION public.hms_attestation_trigger() RETURNS trigger
                LANGUAGE plpgsql AS $$BEGIN RETURN NEW; END;$$
                """);
        admin.execute("""
                CREATE TRIGGER hms_attestation_touch BEFORE INSERT ON public.hms_attestation_child
                FOR EACH ROW EXECUTE FUNCTION public.hms_attestation_trigger()
                """);
        admin.execute("ALTER TABLE public.hms_attestation_child ENABLE ROW LEVEL SECURITY");
        admin.execute("""
                CREATE POLICY hms_attestation_read ON public.hms_attestation_child FOR SELECT TO hms_attestation_runtime
                USING (status = 'ready')
                """);
    }

    private static void populateLedger(JdbcTemplate admin, String manifest) {
        var migrations = MIGRATION.matcher(manifest);
        int count = 0;
        while (migrations.find()) {
            admin.update("""
                    INSERT INTO hms_migration.migration_ledger (version, migration_id, sha256, catalog_sha256)
                    VALUES (?, ?, ?, ?)
                    """, Integer.parseInt(migrations.group(1)), migrations.group(2), migrations.group(3), expectedDigest);
            count++;
        }
        assertThat(count).isEqualTo(9);
    }

    private static void assertStartupBlocked(JdbcTemplate runtime, String expectedOid) {
        assertThatThrownBy(() -> new ReleaseAttestationGate(runtime, expectedOid).verifyBeforeWebServerStartup())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("startup blocked");
    }

    private static JdbcTemplate pinned(Connection connection) {
        return new JdbcTemplate(new SingleConnectionDataSource(connection, true));
    }

    private static int backendPid(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT pg_catalog.pg_backend_pid()", Integer.class);
    }

    private static HikariDataSource runtimePool() {
        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl(url);
        pool.setUsername(RUNTIME);
        pool.setPassword("");
        pool.setMinimumIdle(0);
        pool.setMaximumPoolSize(2);
        pool.setConnectionTimeout(5000);
        return pool;
    }

    private static String resource(String path) throws IOException {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String requireProperty(String name, String pattern) {
        String key = "hms.attestation.pg.test." + name;
        String value = System.getProperty(key, "");
        if (!value.matches(pattern)) {
            throw new IllegalArgumentException("Missing or invalid synthetic PostgreSQL fixture property: " + key);
        }
        return value;
    }
}

package com.harsh.hospitalmanagement.config.safety;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Blocks production context refresh until the database is the fully attested HMS release.
 * No migration is executed here. The migration runner remains the only writer.
 */
@Component
@Profile("prod")
public final class ReleaseAttestationGate {
    private static final Pattern CATALOG_DIGEST_WRAPPER = Pattern.compile(
            "\\A(?:--[^\\r\\n]*\\R)*CREATE FUNCTION pg_temp\\.hms_catalog_digest\\(\\) RETURNS text\\R"
                    + "LANGUAGE sql STABLE\\RSET search_path TO pg_catalog\\RAS \\$digest\\$\\R"
                    + "(\\s*WITH namespaces AS \\([\\s\\S]+?)\\R\\$digest\\$;\\R?\\z");

    private final JdbcTemplate jdbc;
    private final String expectedOid;

    public ReleaseAttestationGate(JdbcTemplate jdbc,
                                  @Value("${hms.release-attestation.expected-database-oid:}") String expectedOid) {
        this.jdbc = jdbc;
        this.expectedOid = expectedOid == null ? "" : expectedOid.trim();
    }

    @PostConstruct
    void verifyBeforeWebServerStartup() {
        try {
            Identity identity = jdbc.queryForObject("""
                    SELECT current_database(), current_user, d.oid::bigint,
                           current_setting('server_version_num')::integer
                    FROM pg_catalog.pg_database d
                    WHERE d.datname = current_database()
                    """, (rs, row) -> new Identity(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getInt(4)));
            long parsedExpectedOid;
            try {
                parsedExpectedOid = Long.parseLong(expectedOid);
            } catch (NumberFormatException e) {
                parsedExpectedOid = -1;
            }
            if (identity == null || identity.version() < 180001 || identity.database().isBlank()
                    || identity.user().isBlank() || parsedExpectedOid <= 0 || parsedExpectedOid != identity.oid()) {
                throw new IllegalStateException("Database release attestation failed");
            }

            Integer targets = jdbc.queryForObject("""
                    SELECT count(*) FROM hms_migration.release_target
                    WHERE database_name = current_database()
                      AND database_oid = (SELECT oid FROM pg_catalog.pg_database WHERE datname = current_database())
                      AND purpose = 'DEPLOYMENT'
                    """, Integer.class);
            if (!Integer.valueOf(1).equals(targets)) {
                throw new IllegalStateException("Database release attestation failed");
            }

            AttestationManifest manifest = loadManifest();
            List<Migration> recorded = jdbc.query("""
                    SELECT version, migration_id, sha256
                    FROM hms_migration.migration_ledger ORDER BY version
                    """, (rs, row) -> new Migration(rs.getInt(1), rs.getString(2), rs.getString(3)));
            if (!manifest.migrations().equals(recorded)) {
                throw new IllegalStateException("Database release attestation failed");
            }
            String digest = jdbc.queryForObject("""
                    SELECT catalog_sha256 FROM hms_migration.migration_ledger WHERE version = 9
                    """, String.class);
            if (digest == null || !digest.matches("[0-9a-f]{64}")) {
                throw new IllegalStateException("Database release attestation failed");
            }
            String calculated = calculateCatalogDigest(jdbc, manifest.catalogDigestQuery());
            if (!digest.equals(calculated)) {
                throw new IllegalStateException("Database release attestation failed");
            }
            Boolean unsafeRole = jdbc.queryForObject("""
                    SELECT
                        EXISTS (
                            SELECT 1
                            FROM pg_catalog.pg_roles r
                            WHERE pg_catalog.pg_has_role(current_user, r.oid, 'USAGE')
                              AND (r.rolsuper OR r.rolcreatedb OR r.rolcreaterole
                                   OR r.rolreplication OR r.rolbypassrls)
                        )
                        OR EXISTS (
                            SELECT 1
                            FROM pg_catalog.pg_roles r
                            WHERE pg_catalog.pg_has_role(current_user, r.oid, 'SET')
                              AND (r.rolsuper OR r.rolcreatedb OR r.rolcreaterole
                                   OR r.rolreplication OR r.rolbypassrls)
                        )
                        OR pg_catalog.pg_get_userbyid(d.datdba) = current_user
                        OR has_database_privilege(current_user, current_database(), 'CREATE')
                        OR NOT has_database_privilege(current_user, current_database(), 'TEMP')
                        OR EXISTS (
                            SELECT 1
                            FROM pg_catalog.pg_namespace n
                            WHERE n.nspname NOT LIKE 'pg_%'
                              AND n.nspname <> 'information_schema'
                              AND (pg_catalog.pg_has_role(current_user, n.nspowner, 'USAGE')
                                   OR has_schema_privilege(current_user, n.nspname, 'CREATE'))
                        )
                        OR EXISTS (
                            SELECT 1
                            FROM pg_catalog.pg_class c
                            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                            WHERE n.nspname NOT LIKE 'pg_%'
                              AND n.nspname <> 'information_schema'
                              AND pg_catalog.pg_has_role(current_user, c.relowner, 'USAGE')
                        )
                        OR EXISTS (
                            SELECT 1
                            FROM pg_catalog.pg_proc p
                            JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
                            WHERE n.nspname NOT LIKE 'pg_%'
                              AND n.nspname <> 'information_schema'
                              AND pg_catalog.pg_has_role(current_user, p.proowner, 'USAGE')
                        )
                    FROM pg_catalog.pg_database d
                    WHERE d.datname = current_database()
                    """, Boolean.class);
            if (!Boolean.FALSE.equals(unsafeRole)) {
                throw new IllegalStateException("Database release attestation failed");
            }
        } catch (RuntimeException failure) {
            throw new IllegalStateException("HMS startup blocked: database release is not attested", failure);
        }
    }

    private AttestationManifest loadManifest() {
        try (var input = new ClassPathResource("database/migration-manifest.json").getInputStream()) {
            String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            if (!json.contains("\"formatVersion\": 1") || !json.contains("\"migrations\"")) {
                throw new IllegalStateException("Invalid migration manifest");
            }
            List<Migration> result = new java.util.ArrayList<>();
            Matcher matcher = Pattern.compile("\\{ \\\"version\\\": (\\d+), \\\"id\\\": \\\"([^\\\"]+)\\\", \\\"path\\\": \\\"([^\\\"]+)\\\", \\\"sha256\\\": \\\"([0-9a-f]{64})\\\"").matcher(json);
            while (matcher.find()) {
                int version = Integer.parseInt(matcher.group(1));
                String id = matcher.group(2);
                String path = matcher.group(3);
                String checksum = matcher.group(4);
                if (version != result.size() + 1 || id.isBlank() || !path.startsWith("database/")
                        || !checksum.matches("[0-9a-f]{64}")) throw new IllegalStateException("Invalid migration manifest");
                try (var sql = new ClassPathResource(path).getInputStream()) {
                    byte[] rawBytes = sql.readAllBytes();
                    String actual = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                            .digest(rawBytes));
                    if (!checksum.equals(actual)) throw new IllegalStateException("Migration checksum mismatch");
                }
                result.add(new Migration(version, id, checksum));
            }
            if (result.size() != 9) throw new IllegalStateException("Invalid migration manifest");
            byte[] digestResource = validateManifestResource(json, "catalogDigest");
            validateManifestResource(json, "releaseVerifier");
            Matcher verifierMatcher = Pattern.compile("\\\"verifier\\\"\\s*:\\s*\\{\\s*\\\"path\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"\\s*,\\s*\\\"sha256\\\"\\s*:\\s*\\\"([0-9a-f]{64})\\\"\\s*\\}").matcher(json);
            while (verifierMatcher.find()) {
                validatePackagedResource(verifierMatcher.group(1), verifierMatcher.group(2), getClass().getClassLoader());
            }
            String digestQuery = extractCatalogDigestQuery(new String(digestResource, StandardCharsets.UTF_8));
            return new AttestationManifest(List.copyOf(result), digestQuery);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid migration manifest", e);
        }
    }

    private byte[] validateManifestResource(String json, String section) {
        Matcher reference = Pattern.compile("\\\"" + Pattern.quote(section)
                + "\\\"\\s*:\\s*\\{\\s*\\\"path\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"\\s*,\\s*\\\"sha256\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"\\s*\\}")
                .matcher(json);
        if (!reference.find()) throw new IllegalStateException("Invalid " + section + " resource reference");
        return validatePackagedResource(reference.group(1), reference.group(2), getClass().getClassLoader());
    }

    static byte[] validatePackagedResource(String path, String expectedChecksum, ClassLoader loader) {
        if (path == null || !path.startsWith("database/") || path.contains("..")
                || path.contains("\\") || path.contains("//")
                || expectedChecksum == null || !expectedChecksum.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("Invalid packaged migration resource reference");
        }
        try (var resource = loader.getResourceAsStream(path)) {
            if (resource == null) throw new IllegalStateException("Missing packaged migration resource");
            byte[] bytes = resource.readAllBytes();
            String actual = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(bytes));
            if (!expectedChecksum.equals(actual)) throw new IllegalStateException("Packaged migration resource checksum mismatch");
            return bytes;
        } catch (Exception e) {
            if (e instanceof IllegalStateException failure) throw failure;
            throw new IllegalStateException("Unable to validate packaged migration resource", e);
        }
    }

    static String extractCatalogDigestQuery(String reviewedSql) {
        Matcher wrapper = CATALOG_DIGEST_WRAPPER.matcher(reviewedSql);
        if (!wrapper.matches()) throw new IllegalStateException("Unexpected catalog digest SQL wrapper");
        return wrapper.group(1);
    }

    static String calculateCatalogDigest(JdbcTemplate jdbc, String digestQuery) {
        return jdbc.execute((ConnectionCallback<String>) connection -> {
            if (!connection.getAutoCommit()) {
                throw new IllegalStateException("Catalog digest requires an independent transaction");
            }
            // Pin one PostgreSQL backend under transaction pooling and preserve the legacy
            // function's catalog deparsing context without leaving a session setting behind.
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("SET TRANSACTION READ ONLY");
                statement.execute("SET LOCAL search_path TO pg_catalog");
                try (var result = statement.executeQuery(digestQuery)) {
                    if (!result.next()) throw new IllegalStateException("Catalog digest was not returned");
                    String digest = result.getString(1);
                    if (digest == null || !digest.matches("[0-9a-f]{64}") || result.next()) {
                        throw new IllegalStateException("Invalid catalog digest result");
                    }
                    return digest;
                }
            } finally {
                // If rollback fails, do not risk committing when restoring auto-commit.
                connection.rollback();
                connection.setAutoCommit(true);
            }
        });
    }

    private record AttestationManifest(List<Migration> migrations, String catalogDigestQuery) { }
    record Migration(int version, String id, String checksum) { }
    record Identity(String database, String user, long oid, int version) { }

}

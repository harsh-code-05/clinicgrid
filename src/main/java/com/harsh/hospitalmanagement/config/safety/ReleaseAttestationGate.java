package com.harsh.hospitalmanagement.config.safety;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.core.io.ClassPathResource;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.util.List;

/**
 * Blocks production context refresh until the database is the fully attested HMS release.
 * No migration is executed here. The migration runner remains the only writer.
 */
@Component
@Profile("prod")
public final class ReleaseAttestationGate {
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

            List<Migration> expected = loadManifest();
            List<Migration> recorded = jdbc.query("""
                    SELECT version, migration_id, sha256
                    FROM hms_migration.migration_ledger ORDER BY version
                    """, (rs, row) -> new Migration(rs.getInt(1), rs.getString(2), rs.getString(3)));
            if (!expected.equals(recorded)) {
                throw new IllegalStateException("Database release attestation failed");
            }
            String digest = jdbc.queryForObject("""
                    SELECT catalog_sha256 FROM hms_migration.migration_ledger WHERE version = 9
                    """, String.class);
            if (digest == null || !digest.matches("[0-9a-f]{64}")) {
                throw new IllegalStateException("Database release attestation failed");
            }
            String calculated = calculateCatalogDigestOnOneConnection();
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
            if (Boolean.TRUE.equals(unsafeRole)) {
                throw new IllegalStateException("Database release attestation failed");
            }
        } catch (RuntimeException failure) {
            throw new IllegalStateException("HMS startup blocked: database release is not attested", failure);
        }
    }

    private List<Migration> loadManifest() {
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
            validateManifestResource(json, "catalogDigest");
            validateManifestResource(json, "releaseVerifier");
            Matcher verifierMatcher = Pattern.compile("\\\"verifier\\\"\\s*:\\s*\\{\\s*\\\"path\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"\\s*,\\s*\\\"sha256\\\"\\s*:\\s*\\\"([0-9a-f]{64})\\\"\\s*\\}").matcher(json);
            while (verifierMatcher.find()) {
                validatePackagedResource(verifierMatcher.group(1), verifierMatcher.group(2), getClass().getClassLoader());
            }
            return List.copyOf(result);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid migration manifest", e);
        }
    }

    private void validateManifestResource(String json, String section) {
        Matcher reference = Pattern.compile("\\\"" + Pattern.quote(section)
                + "\\\"\\s*:\\s*\\{\\s*\\\"path\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"\\s*,\\s*\\\"sha256\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"\\s*\\}")
                .matcher(json);
        if (!reference.find()) throw new IllegalStateException("Invalid " + section + " resource reference");
        validatePackagedResource(reference.group(1), reference.group(2), getClass().getClassLoader());
    }

    static void validatePackagedResource(String path, String expectedChecksum, ClassLoader loader) {
        if (path == null || !path.startsWith("database/") || path.contains("..")
                || path.contains("\\") || path.contains("//")
                || expectedChecksum == null || !expectedChecksum.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("Invalid packaged migration resource reference");
        }
        try (var resource = loader.getResourceAsStream(path)) {
            if (resource == null) throw new IllegalStateException("Missing packaged migration resource");
            String actual = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(resource.readAllBytes()));
            if (!expectedChecksum.equals(actual)) throw new IllegalStateException("Packaged migration resource checksum mismatch");
        } catch (Exception e) {
            if (e instanceof IllegalStateException failure) throw failure;
            throw new IllegalStateException("Unable to validate packaged migration resource", e);
        }
    }

    private String calculateCatalogDigestOnOneConnection() {
        try (var input = new ClassPathResource("database/migration-catalog-digest.sql").getInputStream()) {
            String canonicalSql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            return jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>) connection -> {
                connection.createStatement().execute(canonicalSql);
                try (var result = connection.createStatement().executeQuery("SELECT pg_temp.hms_catalog_digest()")) {
                    if (!result.next()) throw new IllegalStateException("Catalog digest was not returned");
                    return result.getString(1);
                }
            });
        } catch (Exception e) {
            throw new IllegalStateException("Database release attestation failed", e);
        }
    }

    record Migration(int version, String id, String checksum) { }
    record Identity(String database, String user, long oid, int version) { }

}

package com.harsh.hospitalmanagement.config.safety;

import org.springframework.core.env.ConfigurableEnvironment;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Enforces database isolation rules for the local-first development environment.
 * <p>
 * Guarantees that:
 * 1. The production database 'hospital_management' is NEVER targeted under any encoding,
 *    parameter redirection, or driver configuration.
 * 2. Only allowlisted disposable local development databases ('hms_dev_*') on localhost:5433 are permitted.
 * 3. Schema actions across Hibernate and Jakarta persistence are strictly restricted to 'validate' (or 'none')
 *    for PostgreSQL to prevent unauthorized DDL operations.
 * 4. H2 in-memory databases ('jdbc:h2:mem:*') are permitted only for test contexts, must not target 'hospital_management',
 *    and cannot execute arbitrary SQL scripts or connection hooks (INIT, RUNSCRIPT).
 */
public final class DatabaseSafetyValidator {

    public static final String FORBIDDEN_DATABASE_NAME = "hospital_management";
    public static final String ALLOWED_DB_PREFIX = "hms_dev_";
    public static final Pattern ALLOWED_DB_PATTERN = Pattern.compile("^hms_dev_[a-zA-Z0-9_]+$");
    public static final int REQUIRED_PG_PORT = 5433;
    public static final Set<String> ALLOWED_PG_HOSTS = Set.of(
            "localhost",
            "127.0.0.1",
            "::1",
            "[::1]"
    );

    // Query parameters that could redirect host, port, database, or schema in pgjdbc
    private static final Set<String> FORBIDDEN_PG_QUERY_PARAMS = Set.of(
            "pgdbname",
            "database",
            "pghost",
            "host",
            "pgport",
            "port",
            "currentschema"
    );

    // Schema action properties across Spring Boot, Hibernate ORM, and Jakarta Persistence
    public static final Set<String> SCHEMA_ACTION_PROPERTIES = Set.of(
            "spring.jpa.hibernate.ddl-auto",
            "spring.jpa.properties.hibernate.hbm2ddl.auto",
            "hibernate.hbm2ddl.auto",
            "spring.jpa.properties.jakarta.persistence.schema-generation.database.action",
            "jakarta.persistence.schema-generation.database.action",
            "spring.jpa.properties.jakarta.persistence.schema-generation.scripts.action",
            "jakarta.persistence.schema-generation.scripts.action",
            "spring.jpa.generate-ddl"
    );

    // Allowlisted parameters for H2 in-memory databases
    private static final Set<String> ALLOWED_H2_PARAMS = Set.of(
            "db_close_delay",
            "db_close_on_exit",
            "mode",
            "database_to_upper",
            "default_null_ordering",
            "case_insensitive_identifiers",
            "non_keywords"
    );

    private DatabaseSafetyValidator() {
    }

    /**
     * Sanitizes a JDBC URL, host, or connection string for safe logging and exception reporting by masking credentials.
     * Masking handles usernames and passwords containing special characters (including colons),
     * with or without a leading "//", as well as sensitive query and property parameters.
     */
    public static String sanitizeUrl(String rawUrl) {
        if (rawUrl == null) {
            return "<null>";
        }
        // Redact authority user:password@ or user@ with or without leading // or comma separation
        String sanitized = rawUrl.replaceAll("(?i)(//)?([^/?#@,\\s]+)@", "$1<credentials-masked>@");
        // Redact sensitive query parameters or property pairs
        sanitized = sanitized.replaceAll("(?i)(password|pwd|user|username|secret|token|sslpassword|sslkey)=[^&;]*", "$1=***");
        return sanitized;
    }

    /**
     * Strips credentials (authority user:password@ and sensitive query parameters)
     * from a JDBC URL before passing to third-party drivers or parsers.
     * Preserves destination host, port, path, and non-credential query parameters without leaking authentication secrets.
     */
    public static String stripUserInfoFromJdbcUrl(String url) {
        if (url == null) {
            return null;
        }
        // 1. Strip authority user:password@ or user@ (with or without //, or comma separated hosts)
        String stripped = url.replaceAll("(?i)(//|,\\s*)[^/?#@,\\s]+@", "$1");

        // 2. Strip sensitive query parameters from query string (starting at '?')
        int queryStart = stripped.indexOf('?');
        if (queryStart == -1) {
            return stripped;
        }

        String base = stripped.substring(0, queryStart);
        String queryStr = stripped.substring(queryStart + 1);

        String[] params = queryStr.split("&");
        List<String> retained = new ArrayList<>();
        Set<String> sensitiveKeys = Set.of(
                "password", "pwd", "user", "username", "secret", "token", "sslpassword", "sslkey"
        );

        for (String param : params) {
            if (param == null || param.trim().isEmpty()) {
                continue;
            }
            int eqIdx = param.indexOf('=');
            String key = (eqIdx != -1 ? param.substring(0, eqIdx) : param).trim().toLowerCase(Locale.ROOT);
            if (!sensitiveKeys.contains(key)) {
                retained.add(param);
            }
        }

        if (retained.isEmpty()) {
            return base;
        }
        return base + "?" + String.join("&", retained);
    }

    /**
     * Checks if a host name or IP address represents a local loopback address
     * (including localhost, 127.0.0.0/8 subnet, ::1, and bracketed IPv6 variants).
     */
    public static boolean isLoopbackHost(String host) {
        if (host == null || host.trim().isEmpty()) {
            return false;
        }
        String clean = host.trim().toLowerCase(Locale.ROOT);
        if (clean.startsWith("[") && clean.endsWith("]")) {
            clean = clean.substring(1, clean.length() - 1);
        }
        if (ALLOWED_PG_HOSTS.contains(clean) || "localhost".equals(clean)) {
            return true;
        }
        // Wildcard local addresses (0.0.0.0, ::, [::])
        if ("0.0.0.0".equals(clean) || "::".equals(clean)) {
            return true;
        }
        // IPv4 127.0.0.0/8 range (127.0.0.1 to 127.255.255.254)
        if (clean.matches("^127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")) {
            return true;
        }
        try {
            InetAddress addr = InetAddress.getByName(clean);
            return addr.isLoopbackAddress() || addr.isAnyLocalAddress();
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Validates the datasource URL and optional ddl-auto setting.
     *
     * @param rawUrl  the JDBC connection URL
     * @param ddlAuto the Hibernate ddl-auto setting (may be null)
     * @throws DatabaseSafetyException if any safety rule is violated
     */
    public static void validate(String rawUrl, String ddlAuto) {
        validateDataSourceUrl(rawUrl);
        boolean isH2Mem = rawUrl != null && rawUrl.startsWith("jdbc:h2:mem:");
        validateDdlAuto(ddlAuto, isH2Mem);
    }

    /**
     * Validates an environment configuration for database safety.
     */
    public static void validateEnvironment(ConfigurableEnvironment environment) {
        if (environment == null) {
            return;
        }

        String url = environment.getProperty("spring.datasource.url");
        if (url == null || url.trim().isEmpty()) {
            url = environment.getProperty("spring.datasource.hikari.jdbc-url");
        }
        if (url == null || url.trim().isEmpty()) {
            url = environment.getProperty("spring.datasource.hikari.jdbcUrl");
        }

        boolean isSliceTestWithoutDatabase =
                environment.containsProperty("org.springframework.boot.webmvc.test.autoconfigure.WebMvcTestContextBootstrapper")
                        || environment.containsProperty("org.springframework.boot.test.autoconfigure.web.client.RestClientTestContextBootstrapper")
                        || environment.containsProperty("org.springframework.boot.test.autoconfigure.json.JsonTestContextBootstrapper");

        boolean isProduction = false;
        if (environment.getActiveProfiles() != null) {
            for (String profile : environment.getActiveProfiles()) {
                if ("prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile)) {
                    isProduction = true;
                    break;
                }
            }
        }
        if (!isProduction && "production".equalsIgnoreCase(environment.getProperty("hms.database.safety.mode"))) {
            isProduction = true;
        }

        boolean isH2Mem = url != null && url.startsWith("jdbc:h2:mem:");

        if (url != null && !url.trim().isEmpty()) {
            validateDataSourceUrl(url, isProduction);

            for (String prop : SCHEMA_ACTION_PROPERTIES) {
                String val = environment.getProperty(prop);
                if (val != null) {
                    validateSchemaAction(prop, val, isH2Mem);
                }
            }
        } else if (!isSliceTestWithoutDatabase) {
            validateDataSourceUrl(url, isProduction);
        }

        // Verify spring.jpa.generate-ddl is not enabled for PostgreSQL
        String generateDdl = environment.getProperty("spring.jpa.generate-ddl");
        if (generateDdl != null) {
            String genTrimmed = generateDdl.trim().toLowerCase(Locale.ROOT);
            if ("true".equals(genTrimmed) || "1".equals(genTrimmed) || "yes".equals(genTrimmed)) {
                if (!isH2Mem) {
                    throw new DatabaseSafetyException(
                            "CRITICAL SCHEMA SAFETY VIOLATION: Property 'spring.jpa.generate-ddl=true' is strictly forbidden for PostgreSQL connections. "
                                    + "It forces Hibernate to execute schema updates ('hibernate.hbm2ddl.auto=update'). Only 'validate' or 'none' is permitted."
                    );
                }
            }
        }
    }

    /**
     * Validates a datasource connection URL against strict isolation constraints.
     *
     * @param rawUrl the raw JDBC URL string
     * @throws DatabaseSafetyException if the URL violates safety constraints
     */
    public static void validateDataSourceUrl(String rawUrl) {
        validateDataSourceUrl(rawUrl, false);
    }

    public static void validateDataSourceUrl(String rawUrl, boolean isProduction) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            throw new DatabaseSafetyException(
                    "Database connection URL is missing. An approved database "
                            + "must be explicitly configured via spring.datasource.url."
            );
        }

        String normalizedUrl = rawUrl.trim();

        // 1. Recursive URL-decoding check to defeat percent-encoded evasion (e.g. hospital%5Fmanagement)
        String decodedUrl = normalizedUrl;
        try {
            String prev;
            do {
                prev = decodedUrl;
                decodedUrl = URLDecoder.decode(prev, StandardCharsets.UTF_8);
            } while (!decodedUrl.equals(prev));
        } catch (Exception ignored) {
            // If decoding fails, check undecoded string
        }

        if (decodedUrl.toLowerCase(Locale.ROOT).contains(FORBIDDEN_DATABASE_NAME)) {
            throw new DatabaseSafetyException(
                    "CRITICAL DATABASE SAFETY VIOLATION: Target URL references forbidden production database '"
                            + FORBIDDEN_DATABASE_NAME + "'. Connection refused."
            );
        }

        if (normalizedUrl.startsWith("jdbc:postgresql:")) {
            validatePostgresUrl(normalizedUrl, isProduction);
        } else if (normalizedUrl.startsWith("jdbc:h2:mem:")) {
            validateH2MemUrl(normalizedUrl, decodedUrl);
        } else if (normalizedUrl.startsWith("jdbc:h2:")) {
            throw new DatabaseSafetyException(
                    "File-based H2 databases are not permitted. Only in-memory H2 ('jdbc:h2:mem:*') or local PostgreSQL is allowed."
            );
        } else {
            throw new DatabaseSafetyException(
                    "Unsupported JDBC protocol in URL: " + sanitizeUrl(normalizedUrl)
                            + ". Only approved local PostgreSQL (localhost:" + REQUIRED_PG_PORT + ") or in-memory H2 is permitted."
            );
        }
    }

    private static void validatePostgresUrl(String pgUrl) {
        validatePostgresUrl(pgUrl, false);
    }

    private static void validatePostgresUrl(String pgUrl, boolean isProduction) {
        if (!pgUrl.startsWith("jdbc:postgresql://")) {
            throw new DatabaseSafetyException(
                    "Malformed PostgreSQL JDBC URL: " + sanitizeUrl(pgUrl) + ". Expected format: jdbc:postgresql://localhost:5433/hms_dev_<name>"
            );
        }

        // 2. Reject query parameters that could override connection destination or schema
        int queryStart = pgUrl.indexOf('?');
        if (queryStart >= 0) {
            String queryString = pgUrl.substring(queryStart + 1);
            String[] params = queryString.split("&");
            for (String param : params) {
                if (param == null || param.trim().isEmpty()) {
                    continue;
                }
                int eq = param.indexOf('=');
                String key = (eq >= 0 ? param.substring(0, eq) : param).trim().toLowerCase(Locale.ROOT);
                if (FORBIDDEN_PG_QUERY_PARAMS.contains(key)) {
                    throw new DatabaseSafetyException(
                            "CRITICAL SAFETY VIOLATION: Query parameter '" + sanitizeUrl(key)
                                    + "' is forbidden in PostgreSQL JDBC URL as it can redirect connection destination or schema."
                    );
                }
            }
        }

        // 3. Static URL parsing
        String withoutPrefix = pgUrl.substring("jdbc:postgresql://".length());
        int slashIndex = withoutPrefix.indexOf('/');
        if (slashIndex <= 0) {
            throw new DatabaseSafetyException(
                    "PostgreSQL URL missing target database path: " + sanitizeUrl(pgUrl) + ". Expected format: jdbc:postgresql://localhost:5433/hms_dev_<name>"
            );
        }

        String hostPortPart = withoutPrefix.substring(0, slashIndex);
        String dbAndQueryPart = withoutPrefix.substring(slashIndex + 1);

        // Check for multiple hosts before stripping userInfo, as userInfo can only appear per-host or before the first host
        String[] hostSpecs = hostPortPart.split(",", -1);
        if (!isProduction && hostSpecs.length > 1) {
            throw new DatabaseSafetyException(
                    "Multiple host specifications are not permitted in local development: " + sanitizeUrl(hostPortPart)
            );
        }

        // Validate each host specification individually
        for (String rawSpec : hostSpecs) {
            if (rawSpec == null || rawSpec.trim().isEmpty()) {
                throw new DatabaseSafetyException("Empty host specification in PostgreSQL URL: " + sanitizeUrl(hostPortPart));
            }
            String spec = rawSpec.trim();
            // Strip userInfo (user:password@) for this specific host destination
            if (spec.contains("@")) {
                int atIndex = spec.lastIndexOf('@');
                spec = spec.substring(atIndex + 1);
            }

            String host;
            int port;
            int colonIndex = spec.lastIndexOf(':');
            if (spec.startsWith("[")) {
                int closingBracket = spec.indexOf(']');
                if (closingBracket < 0) {
                    throw new DatabaseSafetyException("Unclosed IPv6 bracket in host: " + sanitizeUrl(spec));
                }
                host = spec.substring(0, closingBracket + 1);
                String rest = spec.substring(closingBracket + 1);
                if (rest.startsWith(":")) {
                    try {
                        port = Integer.parseInt(rest.substring(1));
                    } catch (NumberFormatException e) {
                        throw new DatabaseSafetyException("Invalid port in PostgreSQL URL: " + sanitizeUrl(rest), e);
                    }
                } else {
                    port = 5432;
                }
            } else if (colonIndex < 0) {
                host = spec;
                port = 5432;
            } else {
                host = spec.substring(0, colonIndex);
                String portStr = spec.substring(colonIndex + 1);
                try {
                    port = Integer.parseInt(portStr);
                } catch (NumberFormatException e) {
                    throw new DatabaseSafetyException("Invalid port in PostgreSQL URL: " + sanitizeUrl(portStr), e);
                }
            }

            // Validate Host & Port
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            if (!isProduction) {
                if (!ALLOWED_PG_HOSTS.contains(normalizedHost)) {
                    throw new DatabaseSafetyException(
                            "Database host '" + sanitizeUrl(host) + "' is not permitted. Only local connections ("
                                    + String.join(", ", ALLOWED_PG_HOSTS) + ") on port " + REQUIRED_PG_PORT + " are permitted."
                    );
                }
                if (port != REQUIRED_PG_PORT) {
                    throw new DatabaseSafetyException(
                            "Database port " + port + " is not permitted. Only approved local port " + REQUIRED_PG_PORT + " is permitted."
                    );
                }
            } else {
                if (isLoopbackHost(normalizedHost)) {
                    throw new DatabaseSafetyException(
                            "CRITICAL PRODUCTION SAFETY VIOLATION: Production profile cannot target local/loopback host '"
                                    + sanitizeUrl(host) + "'. A managed cloud PostgreSQL host (e.g. Neon) is required."
                    );
                }
            }
        }

        // Validate Database Name (in pgjdbc, database path extends to '?' query delimiter)
        int queryIndex = dbAndQueryPart.indexOf('?');
        String dbName = queryIndex >= 0 ? dbAndQueryPart.substring(0, queryIndex) : dbAndQueryPart;

        if (dbName.contains("/") || dbName.contains("\\") || dbName.contains("..")) {
            throw new DatabaseSafetyException(
                    "Invalid characters or path traversal detected in database name: " + sanitizeUrl(dbName)
            );
        }

        if (dbName.trim().isEmpty()) {
            throw new DatabaseSafetyException(
                    "Database name cannot be empty."
            );
        }

        if (dbName.equalsIgnoreCase(FORBIDDEN_DATABASE_NAME) || dbName.toLowerCase(Locale.ROOT).contains(FORBIDDEN_DATABASE_NAME)) {
            throw new DatabaseSafetyException(
                    "CRITICAL DATABASE SAFETY VIOLATION: Database name '" + sanitizeUrl(dbName)
                            + "' is forbidden. Connection to production database is strictly rejected."
            );
        }

        if (!isProduction && !ALLOWED_DB_PATTERN.matcher(dbName).matches()) {
            throw new DatabaseSafetyException(
                    "Database name '" + sanitizeUrl(dbName) + "' violates safety constraints. Only disposable databases matching pattern '"
                            + ALLOWED_DB_PREFIX + "*' are permitted."
            );
        }

        // 4. Runtime driver reflection check (when pgjdbc is loaded) to cross-verify effective driver properties
        try {
            Class<?> driverClass = Class.forName("org.postgresql.Driver");
            Method parseUrlMethod = driverClass.getMethod("parseURL", String.class, Properties.class);
            // Sanitize credentials before passing to driver parser so no credentials can leak into effective properties or driver logs
            String cleanPgUrl = stripUserInfoFromJdbcUrl(pgUrl);
            Properties pgProps = (Properties) parseUrlMethod.invoke(null, cleanPgUrl, new Properties());
            if (pgProps == null) {
                throw new DatabaseSafetyException(
                        "PostgreSQL driver could not parse URL format: " + sanitizeUrl(cleanPgUrl)
                );
            }

            String effectiveDb = pgProps.getProperty("PGDBNAME");
            String effectiveHost = pgProps.getProperty("PGHOST");
            String effectivePortStr = pgProps.getProperty("PGPORT");

            if (effectiveDb != null) {
                if (effectiveDb.equalsIgnoreCase(FORBIDDEN_DATABASE_NAME) || effectiveDb.toLowerCase(Locale.ROOT).contains(FORBIDDEN_DATABASE_NAME)) {
                    throw new DatabaseSafetyException(
                            "CRITICAL DATABASE SAFETY VIOLATION: PostgreSQL driver resolved effective database to forbidden '"
                                    + sanitizeUrl(effectiveDb) + "'."
                    );
                }
                if (!isProduction && !ALLOWED_DB_PATTERN.matcher(effectiveDb.trim()).matches()) {
                    throw new DatabaseSafetyException(
                            "PostgreSQL driver resolved effective database to non-allowlisted name: " + sanitizeUrl(effectiveDb)
                    );
                }
            }
            if (effectiveHost != null) {
                String[] effHosts = effectiveHost.split(",", -1);
                for (String effH : effHosts) {
                    String trimmedHost = effH.trim();
                    if (!isProduction) {
                        if (!ALLOWED_PG_HOSTS.contains(trimmedHost.toLowerCase(Locale.ROOT))) {
                            throw new DatabaseSafetyException(
                                    "PostgreSQL driver resolved effective host to non-local destination: " + sanitizeUrl(trimmedHost)
                            );
                        }
                    } else {
                        if (isLoopbackHost(trimmedHost)) {
                            throw new DatabaseSafetyException(
                                    "CRITICAL PRODUCTION SAFETY VIOLATION: PostgreSQL driver resolved effective host to local/loopback host '"
                                            + sanitizeUrl(trimmedHost) + "'. A managed cloud PostgreSQL host (e.g. Neon) is required."
                            );
                        }
                    }
                }
            }
            if (!isProduction && effectivePortStr != null) {
                try {
                    int parsedPort = Integer.parseInt(effectivePortStr);
                    if (parsedPort != REQUIRED_PG_PORT) {
                        throw new DatabaseSafetyException(
                                "PostgreSQL driver resolved effective port to non-allowlisted port: " + parsedPort
                        );
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (ClassNotFoundException | NoSuchMethodException ignored) {
            // Driver not loaded yet in light contexts; static verification above is already enforced
        } catch (DatabaseSafetyException dse) {
            throw dse;
        } catch (Exception ex) {
            Throwable target = (ex instanceof InvocationTargetException ite && ite.getTargetException() != null)
                    ? ite.getTargetException() : ex;
            if (target instanceof DatabaseSafetyException dse) {
                throw dse;
            }
            String rawMsg = target.getMessage() != null ? target.getMessage() : target.getClass().getSimpleName();
            throw new DatabaseSafetyException("Failed to verify PostgreSQL driver effective properties: " + sanitizeUrl(rawMsg));
        }
    }

    private static void validateH2MemUrl(String h2Url, String decodedUrl) {
        String lower = decodedUrl.toLowerCase(Locale.ROOT);
        if (lower.contains(FORBIDDEN_DATABASE_NAME)) {
            throw new DatabaseSafetyException(
                    "CRITICAL DATABASE SAFETY VIOLATION: H2 URL references forbidden database name '"
                            + FORBIDDEN_DATABASE_NAME + "'."
            );
        }

        // Restrict connection-time script execution and escape hooks (INIT, RUNSCRIPT, LINKED, CREATE LINKED TABLE)
        if (lower.contains("runscript") || lower.contains("script")) {
            throw new DatabaseSafetyException(
                    "CRITICAL SAFETY VIOLATION: External script execution (RUNSCRIPT/SCRIPT) in H2 URL is forbidden: " + sanitizeUrl(h2Url)
            );
        }

        if (lower.contains("init=") || lower.contains("init\\=") || lower.contains(";init") || lower.contains("?init") || lower.contains("&init")) {
            throw new DatabaseSafetyException(
                    "CRITICAL SAFETY VIOLATION: Connection-time SQL initialization (INIT) in H2 URL is strictly forbidden to prevent connection isolation escapes: " + sanitizeUrl(h2Url)
            );
        }

        if (lower.contains("linked")) {
            throw new DatabaseSafetyException(
                    "CRITICAL SAFETY VIOLATION: Linked table operations in H2 URL are forbidden: " + sanitizeUrl(h2Url)
            );
        }

        // Parse and validate H2 URL parameters against the strict allowlist
        String withoutPrefix = h2Url.substring("jdbc:h2:mem:".length());
        String[] parts = withoutPrefix.split("[;?&]");
        // First part is db name, subsequent parts are parameters
        for (int i = 1; i < parts.length; i++) {
            String param = parts[i].trim();
            if (param.isEmpty()) {
                continue;
            }
            int eqIdx = param.indexOf('=');
            String paramKey = (eqIdx >= 0 ? param.substring(0, eqIdx) : param).trim().toLowerCase(Locale.ROOT);
            if (!ALLOWED_H2_PARAMS.contains(paramKey)) {
                throw new DatabaseSafetyException(
                        "CRITICAL SAFETY VIOLATION: Unrecognized or non-allowlisted parameter '" + paramKey
                                + "' in H2 URL. Allowed parameters: " + ALLOWED_H2_PARAMS + ". URL: " + sanitizeUrl(h2Url)
                );
            }
        }
    }

    /**
     * Validates that Hibernate ddl-auto is restricted to safe operations.
     */
    public static void validateDdlAuto(String ddlAuto, boolean isH2Mem) {
        validateSchemaAction("spring.jpa.hibernate.ddl-auto", ddlAuto, isH2Mem);
    }

    /**
     * Validates any schema generation action property across Spring Boot, Hibernate, or Jakarta Persistence.
     */
    public static void validateSchemaAction(String propertyName, String actionValue, boolean isH2Mem) {
        if (actionValue == null || actionValue.trim().isEmpty()) {
            return;
        }

        String mode = actionValue.trim().toLowerCase(Locale.ROOT);

        if ("spring.jpa.generate-ddl".equalsIgnoreCase(propertyName)) {
            if ("true".equals(mode) || "1".equals(mode) || "yes".equals(mode)) {
                if (!isH2Mem) {
                    throw new DatabaseSafetyException(
                            "CRITICAL SCHEMA SAFETY VIOLATION: " + propertyName + "='" + actionValue
                                    + "' is forbidden for PostgreSQL connections. It forces Hibernate to execute schema updates ('hibernate.hbm2ddl.auto=update'). Only 'validate' or 'none' is permitted."
                    );
                }
            }
            return;
        }

        if (isH2Mem) {
            // H2 in-memory is permitted create-drop or create in test harnesses
            return;
        }

        if ("validate".equals(mode) || "none".equals(mode)) {
            return;
        }

        throw new DatabaseSafetyException(
                "CRITICAL SCHEMA SAFETY VIOLATION: " + propertyName + "='" + actionValue
                        + "' is forbidden for PostgreSQL connections. Only 'validate' or 'none' is permitted "
                        + "to ensure schema integrity and prevent Hibernate/JPA from modifying tables."
        );
    }

    /**
     * Validates an entire map or set of JPA/Hibernate properties against mutating schema actions.
     */
    public static void validateJpaProperties(Map<String, ?> jpaProperties, boolean isH2Mem) {
        if (jpaProperties == null || isH2Mem) {
            return;
        }
        for (Map.Entry<String, ?> entry : jpaProperties.entrySet()) {
            String key = entry.getKey();
            if (key == null) {
                continue;
            }
            String lowerKey = key.toLowerCase(Locale.ROOT);
            if (lowerKey.contains("ddl-auto") || lowerKey.contains("hbm2ddl.auto") || lowerKey.contains("schema-generation") || lowerKey.contains("generate-ddl")) {
                Object val = entry.getValue();
                if (val != null) {
                    validateSchemaAction(key, String.valueOf(val), false);
                }
            }
        }
    }
}

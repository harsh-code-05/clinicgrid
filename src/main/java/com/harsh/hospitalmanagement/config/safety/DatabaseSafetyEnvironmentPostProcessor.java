package com.harsh.hospitalmanagement.config.safety;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Early-stage environment validator that executes immediately after application.properties
 * and profile configurations are loaded, but before any Spring ApplicationContext
 * or beans (including DataSource and EntityManagerFactory) are initialized.
 */
public class DatabaseSafetyEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final Logger log = LoggerFactory.getLogger(DatabaseSafetyEnvironmentPostProcessor.class);

    // Run immediately after ConfigDataEnvironmentPostProcessor (which has ORDER = HIGHEST_PRECEDENCE + 10)
    // so application.properties and test property overrides are loaded, but still before any bean creation.
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 20;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        boolean deploymentMode = "deployment".equalsIgnoreCase(environment.getProperty("hms.deployment.mode", "local"))
                || Boolean.parseBoolean(environment.getProperty("hms.deployment.mode", "false"));
        boolean attestationRequired = deploymentMode
                || Boolean.parseBoolean(environment.getProperty("hms.release-attestation.required", "false"));
        if (attestationRequired && (!environment.acceptsProfiles(org.springframework.core.env.Profiles.of("prod"))
                || Boolean.parseBoolean(environment.getProperty("spring.main.lazy-initialization", "false")))) {
            throw new IllegalStateException("Release attestation requires the prod profile and eager bean initialization");
        }
        log.info("HMS Database Safety Gate: early-stage environment inspection...");
        DatabaseSafetyValidator.validateEnvironment(environment);
        log.info("HMS Database Safety Gate: PASSED (URL and schema actions satisfy local development isolation constraints)");
    }
}

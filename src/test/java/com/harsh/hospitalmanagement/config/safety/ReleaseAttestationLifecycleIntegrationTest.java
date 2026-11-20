package com.harsh.hospitalmanagement.config.safety;

import jakarta.annotation.PostConstruct;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReleaseAttestationLifecycleIntegrationTest {
    private static final String OAUTH_AUTO_CONFIG = "org.springframework.boot.security.oauth2.client.autoconfigure.servlet.OAuth2ClientWebSecurityAutoConfiguration";
    @Test
    void successfulGateAllowsEmbeddedTomcatToServe() throws Exception {
        try (var context = new SpringApplication(SuccessfulFixture.class).run("--server.port=0", "--spring.autoconfigure.exclude=" + OAUTH_AUTO_CONFIG)) {
            assertThat(context).isInstanceOf(WebServerApplicationContext.class);
            var port = ((WebServerApplicationContext) context).getWebServer().getPort();
            assertThat(port).isPositive();
            var connection = (java.net.HttpURLConnection) new java.net.URI("http://127.0.0.1:" + port + "/probe").toURL().openConnection();
            connection.setConnectTimeout(2_000);
            connection.setReadTimeout(2_000);
            assertThat(connection.getResponseCode()).isLessThan(500);
        }
    }

    @Test
    void failedGatePreventsEmbeddedTomcatStartup() {
        assertThatThrownBy(() -> new SpringApplication(FailingFixture.class).run("--server.port=0", "--spring.autoconfigure.exclude=" + OAUTH_AUTO_CONFIG))
                .hasRootCauseMessage("controlled attestation failure");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class, JdbcTemplateAutoConfiguration.class, SecurityAutoConfiguration.class})
    @Import(ProbeController.class)
    static class SuccessfulFixture {
        @Bean Gate gate() { return new Gate(false); }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class, JdbcTemplateAutoConfiguration.class, SecurityAutoConfiguration.class})
    static class FailingFixture {
        @Bean Gate gate() { return new Gate(true); }
        @Bean ProbeController probeController() { return new ProbeController(); }
    }

    static final class Gate {
        private final boolean fail;
        Gate(boolean fail) { this.fail = fail; }
        @PostConstruct void verify() {
            if (fail) throw new IllegalStateException("controlled attestation failure");
        }
    }

    @RestController
    static class ProbeController {
        @GetMapping("/probe") String probe() { return "ok"; }
    }
}

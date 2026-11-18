package com.harsh.hospitalmanagement.config;

import com.harsh.hospitalmanagement.HospitalManagementSystemApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.core.io.FileSystemResource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class DatasourceConfigurationTest {

    @Test
    void mainApplicationPropertiesHasExternalizedUsernameWithPostgresDefault() throws Exception {
        MockEnvironment env = new MockEnvironment();
        PropertiesPropertySourceLoader loader = new PropertiesPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load("mainAppProps", new FileSystemResource("src/main/resources/application.properties"));
        for (PropertySource<?> source : sources) {
            env.getPropertySources().addLast(source);
        }

        // Resolves to default "postgres" when SPRING_DATASOURCE_USERNAME is unset
        assertThat(env.resolvePlaceholders(env.getProperty("spring.datasource.username"))).isEqualTo("postgres");

        // Resolves to custom value when SPRING_DATASOURCE_USERNAME is set
        env.getPropertySources().addFirst(new MapPropertySource("customEnv", Map.of("SPRING_DATASOURCE_USERNAME", "neondb_owner")));
        assertThat(env.resolvePlaceholders(env.getProperty("spring.datasource.username"))).isEqualTo("neondb_owner");
    }

    @Test
    void prodApplicationPropertiesRequiresExternalizedUsername() throws Exception {
        MockEnvironment env = new MockEnvironment();
        PropertiesPropertySourceLoader loader = new PropertiesPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load("prodAppProps", new FileSystemResource("src/main/resources/application-prod.properties"));
        for (PropertySource<?> source : sources) {
            env.getPropertySources().addLast(source);
        }

        // Without environment variables, resolving placeholder fails closed
        assertThatThrownBy(() -> env.resolveRequiredPlaceholders(env.getProperty("spring.datasource.username")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SPRING_DATASOURCE_USERNAME");
    }

    @Test
    void productionProfileFailsClosedWhenDatasourceCredentialsAreMissing() {
        assertThatThrownBy(() -> {
            try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(HospitalManagementSystemApplication.class)
                    .web(WebApplicationType.SERVLET)
                    .run(
                            "--server.port=0",
                            "--spring.profiles.active=prod"
                    )) {
                // should not reach here
            }
        }).satisfies(ex -> {
            assertThat(ex.getMessage()).containsAnyOf("SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME", "DB_PASSWORD", "FRONTEND_URL");
        });
    }
}

package com.harsh.hospitalmanagement.config.safety;

import com.harsh.hospitalmanagement.HospitalManagementSystemApplication;
import com.harsh.hospitalmanagement.repository.DoctorVerificationDocumentEventRepository;
import com.harsh.hospitalmanagement.repository.DoctorVerificationHistoryRepository;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.Repository;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@RequiresDisposablePostgres
class DisposablePostgresValidationTest {

    @Test
    void applicationStartsAndRepositoriesInitializeAgainstTheAttestedDatabase() {
        var target = PostgresTestTarget.fromEnvironment(System.getenv());
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("disposablePostgresValidation", Map.ofEntries(
                Map.entry("spring.datasource.url", target.url()),
                Map.entry("spring.jpa.hibernate.ddl-auto", "validate"),
                Map.entry("spring.jpa.generate-ddl", false),
                Map.entry("spring.jpa.show-sql", false),
                Map.entry("spring.sql.init.mode", "never"),
                Map.entry("spring.flyway.enabled", false),
                Map.entry("spring.liquibase.enabled", false),
                Map.entry("hms.document-storage.enabled", false),
                Map.entry("hms.document-storage.purge.enabled", false),
                Map.entry("hms.document-storage.purge-cron", "-"),
                Map.entry("hms.document-storage.multipart-staging-dir", ""),
                Map.entry("hms.ai.cloudflare.account-id", ""),
                Map.entry("hms.ai.cloudflare.api-token", ""),
                Map.entry("server.address", "127.0.0.1"),
                Map.entry("server.port", 0),
                Map.entry("app.frontend.url", "http://localhost:5173/"),
                Map.entry("spring.security.oauth2.client.registration.google.client-id", "test-client-id"),
                Map.entry("spring.security.oauth2.client.registration.google.client-secret", "test-client-secret"),
                Map.entry("spring.security.oauth2.client.registration.google.scope", "openid,profile,email")
        )));

        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(HospitalManagementSystemApplication.class)
                .environment(environment)
                .initializers(application -> {
                    var genericContext = (GenericApplicationContext) application;
                    genericContext.registerBean("dataSource", HikariDataSource.class,
                            () -> target.attestedDataSource(true), definition -> definition.setDestroyMethodName("close"));
                    genericContext.addBeanFactoryPostProcessor(factory -> {
                        var definitions = (BeanDefinitionRegistry) factory;
                        // Hibernate validates the schema. Startup seeders are separate database writers.
                        definitions.removeBeanDefinition("adminInitializer");
                        definitions.removeBeanDefinition("specializationInitializer");
                    });
                })
                .run()) {
            assertThat(context.isRunning()).isTrue();
            assertThat(context.containsBean("adminInitializer")).isFalse();
            assertThat(context.containsBean("specializationInitializer")).isFalse();
            assertThat(context.containsBean("documentStorage")).isFalse();

            var repositories = context.getBeansOfType(Repository.class);
            assertThat(repositories).hasSize(17).containsKeys("billRepository", "paymentRecordRepository");
            repositories.values().forEach(repository -> {
                if (repository instanceof JpaRepository<?, ?> jpaRepository) {
                    assertThat(jpaRepository.count()).isNotNegative();
                }
            });
            assertThat(context.getBean(DoctorVerificationDocumentEventRepository.class)
                    .findByDoctorIdOrderByOccurredAtDescIdDesc(0L)).isEmpty();
            assertThat(context.getBean(DoctorVerificationHistoryRepository.class)
                    .findByDoctor_IdOrderByOccurredAtDescIdDesc(0L)).isEmpty();
        }
    }
}

package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.config.SpecializationInitializer;
import com.harsh.hospitalmanagement.entity.Specialization;
import com.harsh.hospitalmanagement.repository.SpecializationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.datasource.url=jdbc:h2:mem:spec_catalog_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL"
})
public class SpecializationCatalogIntegrationTest {

    @Autowired
    private SpecializationRepository specializationRepository;

    @Autowired
    private SpecializationService specializationService;

    @Autowired
    private SpecializationInitializer specializationInitializer;

    private static final List<String> EXPECTED_16_CANONICAL = List.of(
            "Cardiology",
            "Neurology",
            "Orthopedics",
            "Dermatology",
            "Pediatrics",
            "Gynecology",
            "General Medicine",
            "Ophthalmology",
            "ENT (Otolaryngology)",
            "Psychiatry",
            "Gastroenterology",
            "Endocrinology",
            "Urology",
            "Pulmonology",
            "Nephrology",
            "Oncology"
    );

    @Test
    @DisplayName("Catalog contains all 16 canonical specialties with clean display names and descriptions")
    void all16CanonicalSpecialtiesArePresent() {
        List<Specialization> all = specializationService.getAllSpecializations();

        List<String> names = all.stream().map(Specialization::getName).toList();
        assertThat(names).containsAll(EXPECTED_16_CANONICAL);

        for (String expected : EXPECTED_16_CANONICAL) {
            Specialization spec = specializationService.getSpecializationByName(expected).orElse(null);
            assertThat(spec)
                    .withFailMessage("Expected canonical specialty '%s' to be present", expected)
                    .isNotNull();
            assertThat(spec.getDescription())
                    .withFailMessage("Expected specialty '%s' to have a non-blank description", expected)
                    .isNotBlank();
        }
    }

    @Test
    @DisplayName("No placeholder records, 'Doc Catalog Spec', 'Doc Svc Spec', or UUIDs appear in active catalog")
    void noPlaceholderOrUuidRecordsInCatalog() {
        List<Specialization> all = specializationService.getAllSpecializations();

        for (Specialization spec : all) {
            String lower = spec.getName().toLowerCase(Locale.ROOT);
            assertThat(lower).doesNotStartWith("doc ");
            assertThat(lower).doesNotContain("catalog spec");
            assertThat(lower).doesNotContain("svc spec");
            assertThat(lower).doesNotMatch(".*[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}.*");
        }
    }

    @Test
    @DisplayName("Catalog contains zero duplicates (case-insensitive uniqueness)")
    void zeroDuplicatesInCatalog() {
        List<Specialization> all = specializationService.getAllSpecializations();

        Set<String> seen = new HashSet<>();
        for (Specialization spec : all) {
            String normalized = spec.getName().trim().toLowerCase(Locale.ROOT);
            assertThat(seen.add(normalized))
                    .withFailMessage("Duplicate specialty found: %s", spec.getName())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("Idempotency: Re-running SpecializationInitializer does not create duplicate entries")
    void reRunningInitializerDoesNotDuplicateRecords() {
        int initialCount = specializationService.getAllSpecializations().size();

        // Simulate application restart by executing the initializer again
        specializationInitializer.run();
        specializationInitializer.run();

        int afterCount = specializationService.getAllSpecializations().size();
        assertThat(afterCount).isEqualTo(initialCount);

        for (String expected : EXPECTED_16_CANONICAL) {
            long count = specializationRepository.findAll().stream()
                    .filter(s -> s.getName().equalsIgnoreCase(expected))
                    .count();
            assertThat(count).isEqualTo(1L);
        }
    }

    @Test
    @DisplayName("SpecializationService.isPlaceholder accurately identifies test and UUID names")
    void isPlaceholderAccuratelyIdentifiesGarbageNames() {
        assertThat(SpecializationService.isPlaceholder("Doc Catalog Spec 93ece05f-ae27-4d6e-a879-fb433ed60363")).isTrue();
        assertThat(SpecializationService.isPlaceholder("Doc Svc Spec 5a4c26a9-905b-4b6a-931e-500c69ba7bf0")).isTrue();
        assertThat(SpecializationService.isPlaceholder("93ece05f-ae27-4d6e-a879-fb433ed60363")).isTrue();
        assertThat(SpecializationService.isPlaceholder("   ")).isTrue();
        assertThat(SpecializationService.isPlaceholder(null)).isTrue();

        assertThat(SpecializationService.isPlaceholder("Cardiology")).isFalse();
        assertThat(SpecializationService.isPlaceholder("ENT (Otolaryngology)")).isFalse();
        assertThat(SpecializationService.isPlaceholder("General Medicine")).isFalse();
    }
}

package com.harsh.hospitalmanagement.config;

import com.harsh.hospitalmanagement.entity.Specialization;
import com.harsh.hospitalmanagement.repository.SpecializationRepository;
import com.harsh.hospitalmanagement.service.SpecializationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
@Order(10)
public class SpecializationInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SpecializationInitializer.class);

    public record CanonicalSpecialty(String name, String description) {}

    public static final List<CanonicalSpecialty> CANONICAL_SPECIALTIES = List.of(
            new CanonicalSpecialty("Cardiology", "Comprehensive cardiovascular care for heart health, hypertension, arrhythmias, and coronary conditions."),
            new CanonicalSpecialty("Neurology", "Advanced diagnosis and treatment of brain, spinal cord, and nervous system disorders."),
            new CanonicalSpecialty("Orthopedics", "Expert care for musculoskeletal system, joint replacements, fractures, and sports injuries."),
            new CanonicalSpecialty("Dermatology", "Clinical and cosmetic care for skin, hair, and nail conditions, allergies, and lesions."),
            new CanonicalSpecialty("Pediatrics", "Specialized, compassionate medical care for infants, children, and adolescents."),
            new CanonicalSpecialty("Gynecology", "Comprehensive women's reproductive health, obstetrics, and preventive gynecological care."),
            new CanonicalSpecialty("General Medicine", "Primary healthcare, preventive medicine, chronic illness management, and diagnostic evaluations."),
            new CanonicalSpecialty("Ophthalmology", "Complete eye care, vision examinations, corrective treatments, and ocular surgery."),
            new CanonicalSpecialty("ENT (Otolaryngology)", "Specialized care for ear, nose, throat, sinuses, head, and neck conditions."),
            new CanonicalSpecialty("Psychiatry", "Mental health assessment, psychotherapy, behavioral medicine, and psychiatric care."),
            new CanonicalSpecialty("Gastroenterology", "Diagnosis and treatment of digestive tract, stomach, liver, pancreas, and intestinal diseases."),
            new CanonicalSpecialty("Endocrinology", "Specialized hormone, diabetes, thyroid, metabolism, and endocrine gland management."),
            new CanonicalSpecialty("Urology", "Comprehensive care for urinary tract, bladder, kidneys, and male reproductive health."),
            new CanonicalSpecialty("Pulmonology", "Expert respiratory care for lungs, asthma, COPD, chronic cough, and breathing disorders."),
            new CanonicalSpecialty("Nephrology", "Specialized kidney health, chronic kidney disease, dialysis, and electrolyte disorder management."),
            new CanonicalSpecialty("Oncology", "Comprehensive multidisciplinary cancer care, oncology consultations, and treatment therapies.")
    );

    private final SpecializationRepository specializationRepository;
    private final JdbcTemplate jdbcTemplate;

    public SpecializationInitializer(SpecializationRepository specializationRepository, JdbcTemplate jdbcTemplate) {
        this.specializationRepository = specializationRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void run(String... args) {
        log.info("HMS Specialization Initializer: verifying canonical medical specialization catalog...");

        // 1. Seed canonical specialties idempotently
        for (CanonicalSpecialty spec : CANONICAL_SPECIALTIES) {
            Specialization existing = specializationRepository.findByNameIgnoreCase(spec.name()).orElse(null);
            if (existing == null) {
                Specialization created = new Specialization();
                created.setName(spec.name());
                created.setDescription(spec.description());
                specializationRepository.save(created);
                log.info("HMS Specialization Catalog: registered canonical specialty '{}'", spec.name());
            } else {
                if (existing.getDescription() == null || existing.getDescription().isBlank()
                        || existing.getDescription().startsWith("Specialization for")) {
                    existing.setDescription(spec.description());
                    specializationRepository.save(existing);
                }
            }
        }

        // 2. Clean up and remap any legacy test placeholder records
        Specialization defaultFallback = specializationRepository.findByNameIgnoreCase("General Medicine")
                .or(() -> specializationRepository.findByNameIgnoreCase("Cardiology"))
                .orElse(null);

        if (defaultFallback != null) {
            List<Specialization> all = specializationRepository.findAll();
            for (Specialization s : all) {
                if (SpecializationService.isPlaceholder(s.getName())) {
                    log.warn("HMS Specialization Catalog: detected legacy placeholder record id={}, name='{}'. Remapping and removing...",
                            s.getId(), s.getName());
                    try {
                        jdbcTemplate.update("UPDATE doctors SET specialization_id = ? WHERE specialization_id = ?",
                                defaultFallback.getId(), s.getId());
                        jdbcTemplate.update("UPDATE doctor_applications SET specialization_id = ? WHERE specialization_id = ?",
                                defaultFallback.getId(), s.getId());
                        specializationRepository.delete(s);
                    } catch (Exception ex) {
                        log.error("Failed to delete placeholder specialization {}: {}", s.getId(), ex.getMessage());
                    }
                }
            }
        }

        log.info("HMS Specialization Initializer: completed successfully. Canonical catalog active with {} specialties.",
                CANONICAL_SPECIALTIES.size());
    }
}

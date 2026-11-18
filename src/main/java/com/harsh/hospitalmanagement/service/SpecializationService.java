package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.entity.Specialization;
import com.harsh.hospitalmanagement.exception.ResourceNotFoundException;
import com.harsh.hospitalmanagement.repository.SpecializationRepository;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class SpecializationService {

    private final SpecializationRepository specializationRepository;

    public SpecializationService(SpecializationRepository specializationRepository) {
        this.specializationRepository = specializationRepository;
    }

    public static boolean isPlaceholder(String name) {
        if (name == null || name.isBlank()) {
            return true;
        }
        String lower = name.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("doc ")
                || lower.contains("catalog spec")
                || lower.contains("svc spec")
                || lower.matches(".*[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}.*");
    }

    public Specialization getSpecializationById(Long id) {
        return specializationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Specialization not found"));
    }

    public Optional<Specialization> getSpecializationByName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return specializationRepository.findByNameIgnoreCase(name.trim());
    }

    public List<Specialization> getAllSpecializations() {
        return specializationRepository.findAll().stream()
                .filter(s -> !isPlaceholder(s.getName()))
                .sorted(Comparator.comparing(Specialization::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public Specialization saveSpecialization(Specialization specialization) {
        return specializationRepository.save(specialization);
    }
}

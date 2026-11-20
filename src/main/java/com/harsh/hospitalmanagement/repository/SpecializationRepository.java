package com.harsh.hospitalmanagement.repository;

import com.harsh.hospitalmanagement.entity.Specialization;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SpecializationRepository extends JpaRepository<Specialization, Long> {

    Optional<Specialization> findByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCase(String name);

    List<Specialization> findAllByOrderByNameAsc();
}

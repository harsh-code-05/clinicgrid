package com.harsh.hospitalmanagement.repository;

import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.enums.VerificationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Optional;

public interface DoctorRepository extends JpaRepository<Doctor, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM Doctor d WHERE d.id = :id")
    Optional<Doctor> findByIdForUpdate(@Param("id") Long id);

    @Query(
            value = """
                    SELECT d FROM Doctor d
                    LEFT JOIN FETCH d.specialization
                    LEFT JOIN FETCH d.user
                    WHERE d.verificationStatus IN :statuses
                    ORDER BY CASE d.verificationStatus
                        WHEN com.harsh.hospitalmanagement.enums.VerificationStatus.SUBMITTED THEN 0
                        WHEN com.harsh.hospitalmanagement.enums.VerificationStatus.UNDER_REVIEW THEN 1
                        WHEN com.harsh.hospitalmanagement.enums.VerificationStatus.UNVERIFIED THEN 2
                        WHEN com.harsh.hospitalmanagement.enums.VerificationStatus.REJECTED THEN 3
                        WHEN com.harsh.hospitalmanagement.enums.VerificationStatus.VERIFIED THEN 4
                        ELSE 5
                    END ASC,
                    COALESCE((SELECT MAX(h.id) FROM DoctorVerificationHistory h WHERE h.doctor.id = d.id), 0L) DESC,
                    d.id DESC
                    """,
            countQuery = "SELECT COUNT(d) FROM Doctor d WHERE d.verificationStatus IN :statuses"
    )
    Page<Doctor> findVerificationQueue(@Param("statuses") Collection<VerificationStatus> statuses, Pageable pageable);

    Optional<Doctor> findByUser_Email(String email);

    Optional<Doctor> findByUser_Id(Long userId);

    boolean existsByUser_Id(Long userId);
}

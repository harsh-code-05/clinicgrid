package com.harsh.hospitalmanagement.repository;

import com.harsh.hospitalmanagement.entity.DoctorVerificationHistory;
import org.springframework.data.repository.Repository;

import java.util.Collection;
import java.util.List;

public interface DoctorVerificationHistoryRepository extends Repository<DoctorVerificationHistory, Long> {

    DoctorVerificationHistory save(DoctorVerificationHistory event);

    List<DoctorVerificationHistory> findByDoctor_IdOrderByOccurredAtDescIdDesc(Long doctorId);

    List<DoctorVerificationHistory> findByDoctor_IdInOrderByOccurredAtDescIdDesc(Collection<Long> doctorIds);
}

package com.harsh.hospitalmanagement.repository;

import com.harsh.hospitalmanagement.entity.PaymentRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PaymentRecordRepository extends JpaRepository<PaymentRecord, Long> {
    List<PaymentRecord> findByBill_IdOrderByRecordedAtDesc(Long billId);
}

package com.harsh.hospitalmanagement.repository;

import com.harsh.hospitalmanagement.entity.Bill;
import com.harsh.hospitalmanagement.enums.BillStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BillRepository extends JpaRepository<Bill, Long> {

    Optional<Bill> findByInvoiceNumber(String invoiceNumber);

    Optional<Bill> findByAppointment_Id(Long appointmentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Bill b WHERE b.appointment.id = :appointmentId")
    Optional<Bill> findByAppointmentIdForUpdate(@Param("appointmentId") Long appointmentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Bill b WHERE b.id = :id")
    Optional<Bill> findByIdForUpdate(@Param("id") Long id);

    List<Bill> findByPatient_User_IdOrderByIssuedAtDesc(Long patientUserId);

    List<Bill> findByPatient_IdOrderByIssuedAtDesc(Long patientId);

    List<Bill> findAllByOrderByIssuedAtDesc();

    List<Bill> findByStatusOrderByIssuedAtDesc(BillStatus status);

    boolean existsByAppointment_Id(Long appointmentId);

    boolean existsByInvoiceNumber(String invoiceNumber);
}

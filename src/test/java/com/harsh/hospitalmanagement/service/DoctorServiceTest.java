package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.dto.DoctorRequest;
import com.harsh.hospitalmanagement.dto.DoctorUpdateRequest;
import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.Specialization;
import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.enums.AuditEventType;
import com.harsh.hospitalmanagement.enums.VerificationStatus;
import com.harsh.hospitalmanagement.exception.ResourceNotFoundException;
import com.harsh.hospitalmanagement.repository.DoctorRepository;
import com.harsh.hospitalmanagement.repository.SpecializationRepository;
import com.harsh.hospitalmanagement.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DoctorServiceTest {

    @Mock
    private DoctorRepository doctorRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private SpecializationRepository specializationRepository;

    @Mock
    private AuditLogService auditLogService;

    private DoctorService doctorService;

    @BeforeEach
    void setUp() {
        doctorService = new DoctorService(
                doctorRepository,
                userRepository,
                specializationRepository,
                auditLogService
        );
    }

    @Test
    void createDoctor_shouldSaveAndLogAuditEvent_whenValid() {
        User user = new User();
        user.setId(1L);

        Specialization spec = new Specialization();
        spec.setId(2L);

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(specializationRepository.findById(2L)).thenReturn(Optional.of(spec));
        when(doctorRepository.save(any(Doctor.class))).thenAnswer(inv -> {
            Doctor d = inv.getArgument(0);
            d.setId(10L);
            return d;
        });

        DoctorRequest req = new DoctorRequest();
        req.setUserId(1L);
        req.setSpecializationId(2L);
        req.setFirstName("John");
        req.setLastName("Doe");
        req.setQualification("MD");
        req.setExperience(10);

        Doctor saved = doctorService.createDoctor(req, "admin@test.com");

        assertThat(saved.getId()).isEqualTo(10L);
        verify(auditLogService).logEvent(
                eq(AuditEventType.ADMIN_CREATED_DOCTOR),
                eq("admin@test.com"),
                eq("ROLE_ADMIN"),
                eq("Doctor"),
                eq(10L),
                contains("Admin created doctor profile"),
                isNull()
        );
    }

    @Test
    void createDoctor_shouldNotLogAuditEvent_whenUserNotFound() {
        when(userRepository.findById(1L)).thenReturn(Optional.empty());

        DoctorRequest req = new DoctorRequest();
        req.setUserId(1L);
        req.setSpecializationId(2L);

        assertThatThrownBy(() -> doctorService.createDoctor(req, "admin@test.com"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("User not found");

        verify(doctorRepository, never()).save(any());
        verify(auditLogService, never()).logEvent(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void updateDoctor_shouldSaveAndLogAuditEvent_whenValid() {
        Doctor doctor = new Doctor();
        doctor.setId(5L);
        doctor.setVerificationStatus(VerificationStatus.VERIFIED);
        doctor.setRegistrationNumber("REG-KEEP");
        doctor.setMedicalCouncil("Keep Council");
        doctor.setRegistrationYear(2019);
        doctor.setIdProofDocumentPath("verification/5/id-proof/11111111-1111-1111-1111-111111111111");
        doctor.setQualificationDocumentPath("verification/5/qualification/22222222-2222-2222-2222-222222222222");

        Specialization spec = new Specialization();
        spec.setId(2L);

        when(doctorRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(doctor));
        when(specializationRepository.findById(2L)).thenReturn(Optional.of(spec));
        when(doctorRepository.save(any(Doctor.class))).thenAnswer(inv -> inv.getArgument(0));

        DoctorUpdateRequest req = new DoctorUpdateRequest();
        req.setSpecializationId(2L);
        req.setFirstName("Jane");
        req.setLastName("Doe");
        req.setQualification("MBBS, MD");
        req.setExperience(12);

        Doctor updated = doctorService.updateDoctor(5L, req, "admin@test.com");

        verify(doctorRepository).findByIdForUpdate(5L);
        assertThat(updated.getFirstName()).isEqualTo("Jane");
        assertThat(updated.getVerificationStatus()).isEqualTo(VerificationStatus.VERIFIED);
        assertThat(updated.getRegistrationNumber()).isEqualTo("REG-KEEP");
        assertThat(updated.getMedicalCouncil()).isEqualTo("Keep Council");
        assertThat(updated.getRegistrationYear()).isEqualTo(2019);
        assertThat(updated.getIdProofDocumentPath()).isEqualTo("verification/5/id-proof/11111111-1111-1111-1111-111111111111");
        assertThat(updated.getQualificationDocumentPath()).isEqualTo("verification/5/qualification/22222222-2222-2222-2222-222222222222");
        verify(auditLogService).logEvent(
                eq(AuditEventType.ADMIN_UPDATED_DOCTOR),
                eq("admin@test.com"),
                eq("ROLE_ADMIN"),
                eq("Doctor"),
                eq(5L),
                contains("Admin updated doctor profile"),
                isNull()
        );
    }

    @Test
    void saveDoctor_shouldLockAndPreserveVerificationFieldsWhenDetachedUpdateGiven() {
        Doctor existing = new Doctor();
        existing.setId(7L);
        existing.setVerificationStatus(VerificationStatus.VERIFIED);
        existing.setRegistrationNumber("REG-VERIFIED");
        existing.setMedicalCouncil("National Council");
        existing.setRegistrationYear(2021);
        existing.setIdProofDocumentPath("verification/7/id-proof/11111111-1111-1111-1111-111111111111");
        existing.setQualificationDocumentPath("verification/7/qualification/22222222-2222-2222-2222-222222222222");

        Doctor staleDetached = new Doctor();
        staleDetached.setId(7L);
        staleDetached.setFirstName("UpdatedFirst");
        staleDetached.setLastName("UpdatedLast");
        staleDetached.setQualification("DM");
        staleDetached.setExperience(15);
        staleDetached.setBio("New bio");
        staleDetached.setVerificationStatus(VerificationStatus.UNVERIFIED);
        staleDetached.setRegistrationNumber(null);
        staleDetached.setMedicalCouncil(null);
        staleDetached.setRegistrationYear(null);
        staleDetached.setIdProofDocumentPath(null);
        staleDetached.setQualificationDocumentPath(null);

        when(doctorRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(existing));
        when(doctorRepository.save(existing)).thenReturn(existing);

        Doctor saved = doctorService.saveDoctor(staleDetached);

        verify(doctorRepository).findByIdForUpdate(7L);
        assertThat(saved.getFirstName()).isEqualTo("UpdatedFirst");
        assertThat(saved.getBio()).isEqualTo("New bio");
        assertThat(saved.getVerificationStatus()).isEqualTo(VerificationStatus.VERIFIED);
        assertThat(saved.getRegistrationNumber()).isEqualTo("REG-VERIFIED");
        assertThat(saved.getMedicalCouncil()).isEqualTo("National Council");
        assertThat(saved.getRegistrationYear()).isEqualTo(2021);
        assertThat(saved.getIdProofDocumentPath()).isEqualTo("verification/7/id-proof/11111111-1111-1111-1111-111111111111");
        assertThat(saved.getQualificationDocumentPath()).isEqualTo("verification/7/qualification/22222222-2222-2222-2222-222222222222");
    }

    @Test
    void updateDoctor_shouldNotLogAuditEvent_whenDoctorNotFound() {
        when(doctorRepository.findByIdForUpdate(99L)).thenReturn(Optional.empty());

        DoctorUpdateRequest req = new DoctorUpdateRequest();

        assertThatThrownBy(() -> doctorService.updateDoctor(99L, req, "admin@test.com"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Doctor not found");

        verify(doctorRepository, never()).save(any());
        verify(auditLogService, never()).logEvent(any(), any(), any(), any(), any(), any(), any());
    }
}

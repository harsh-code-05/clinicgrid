package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.dto.DoctorRequest;
import com.harsh.hospitalmanagement.dto.DoctorUpdateRequest;
import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.Specialization;
import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.enums.AuditEventType;
import com.harsh.hospitalmanagement.exception.ResourceNotFoundException;
import com.harsh.hospitalmanagement.repository.DoctorRepository;
import com.harsh.hospitalmanagement.repository.SpecializationRepository;
import com.harsh.hospitalmanagement.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class DoctorService {

    private final DoctorRepository doctorRepository;
    private final UserRepository userRepository;
    private final SpecializationRepository specializationRepository;
    private final AuditLogService auditLogService;

    public DoctorService(
            DoctorRepository doctorRepository,
            UserRepository userRepository,
            SpecializationRepository specializationRepository,
            AuditLogService auditLogService) {

        this.doctorRepository = doctorRepository;
        this.userRepository = userRepository;
        this.specializationRepository = specializationRepository;
        this.auditLogService = auditLogService;
    }

    public List<Doctor> getAllDoctors() {
        return doctorRepository.findAll();
    }

    public Doctor getDoctorById(Long id) {
        return doctorRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor not found"));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Doctor saveDoctor(Doctor doctor) {
        if (doctor == null) {
            throw new IllegalArgumentException("Doctor must not be null");
        }
        if (doctor.getId() == null) {
            return doctorRepository.save(doctor);
        }

        // Acquire the same pessimistic lock used by verification workflows and only copy editable profile fields.
        Doctor existing = doctorRepository.findByIdForUpdate(doctor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Doctor not found"));
        existing.setFirstName(doctor.getFirstName());
        existing.setLastName(doctor.getLastName());
        existing.setQualification(doctor.getQualification());
        existing.setExperience(doctor.getExperience());
        existing.setBio(doctor.getBio());
        existing.setGender(doctor.getGender());
        existing.setProfilePhotoUrl(doctor.getProfilePhotoUrl());
        if (doctor.getSpecialization() != null) {
            existing.setSpecialization(doctor.getSpecialization());
        }
        if (doctor.getVideoConsultationFee() != null) {
            existing.setVideoConsultationFee(doctor.getVideoConsultationFee());
        }
        return doctorRepository.save(existing);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Doctor createDoctor(DoctorRequest request, String adminEmail) {

        User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        Specialization specialization =
                specializationRepository.findById(request.getSpecializationId())
                        .orElseThrow(() -> new ResourceNotFoundException("Specialization not found"));

        Doctor doctor = new Doctor();

        doctor.setUser(user);
        doctor.setSpecialization(specialization);
        doctor.setFirstName(request.getFirstName());
        doctor.setLastName(request.getLastName());
        doctor.setQualification(request.getQualification());
        doctor.setExperience(request.getExperience());

        Doctor savedDoctor = doctorRepository.save(doctor);

        auditLogService.logEvent(
                AuditEventType.ADMIN_CREATED_DOCTOR,
                adminEmail != null ? adminEmail : "admin",
                "ROLE_ADMIN",
                "Doctor",
                savedDoctor.getId(),
                "Admin created doctor profile for User ID " + user.getId(),
                null
        );

        return savedDoctor;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Doctor createDoctor(DoctorRequest request) {
        return createDoctor(request, "admin");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Doctor updateDoctor(Long id, DoctorUpdateRequest request, String adminEmail) {

        Doctor doctor = doctorRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor not found"));

        Specialization specialization =
                specializationRepository.findById(request.getSpecializationId())
                        .orElseThrow(() -> new ResourceNotFoundException("Specialization not found"));

        doctor.setSpecialization(specialization);
        doctor.setFirstName(request.getFirstName());
        doctor.setLastName(request.getLastName());
        doctor.setQualification(request.getQualification());
        doctor.setExperience(request.getExperience());

        Doctor savedDoctor = doctorRepository.save(doctor);

        auditLogService.logEvent(
                AuditEventType.ADMIN_UPDATED_DOCTOR,
                adminEmail != null ? adminEmail : "admin",
                "ROLE_ADMIN",
                "Doctor",
                savedDoctor.getId(),
                "Admin updated doctor profile for Doctor ID " + savedDoctor.getId(),
                null
        );

        return savedDoctor;
    }
}

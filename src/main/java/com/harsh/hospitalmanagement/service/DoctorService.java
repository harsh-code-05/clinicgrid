package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.dto.DoctorRegisterRequest;
import com.harsh.hospitalmanagement.dto.DoctorRequest;
import com.harsh.hospitalmanagement.dto.DoctorUpdateRequest;
import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.DoctorApplication;
import com.harsh.hospitalmanagement.entity.Specialization;
import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.enums.ApplicationStatus;
import com.harsh.hospitalmanagement.enums.AuditEventType;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.enums.VerificationStatus;
import com.harsh.hospitalmanagement.exception.BadRequestException;
import com.harsh.hospitalmanagement.exception.ResourceNotFoundException;
import com.harsh.hospitalmanagement.repository.DoctorApplicationRepository;
import com.harsh.hospitalmanagement.repository.DoctorRepository;
import com.harsh.hospitalmanagement.repository.SpecializationRepository;
import com.harsh.hospitalmanagement.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Service
public class DoctorService {

    private final DoctorRepository doctorRepository;
    private final UserRepository userRepository;
    private final SpecializationRepository specializationRepository;
    private final AuditLogService auditLogService;
    private final PasswordEncoder passwordEncoder;
    private final DoctorApplicationRepository doctorApplicationRepository;

    public DoctorService(
            DoctorRepository doctorRepository,
            UserRepository userRepository,
            SpecializationRepository specializationRepository,
            AuditLogService auditLogService) {
        this(doctorRepository, userRepository, specializationRepository, auditLogService,
                new BCryptPasswordEncoder(), null);
    }

    @Autowired
    public DoctorService(
            DoctorRepository doctorRepository,
            UserRepository userRepository,
            SpecializationRepository specializationRepository,
            AuditLogService auditLogService,
            @Autowired(required = false) PasswordEncoder passwordEncoder,
            @Autowired(required = false) DoctorApplicationRepository doctorApplicationRepository) {

        this.doctorRepository = doctorRepository;
        this.userRepository = userRepository;
        this.specializationRepository = specializationRepository;
        this.auditLogService = auditLogService;
        this.passwordEncoder = passwordEncoder != null ? passwordEncoder : new BCryptPasswordEncoder();
        this.doctorApplicationRepository = doctorApplicationRepository;
    }

    public List<Doctor> getAllDoctors() {
        return doctorRepository.findAll();
    }

    public Doctor getDoctorById(Long id) {
        return doctorRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor not found"));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Doctor registerDoctor(DoctorRegisterRequest request) {
        if (request == null) {
            throw new BadRequestException("Registration details are required");
        }

        String normalizedEmail = request.getEmail().trim().toLowerCase(Locale.ROOT);
        if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new BadRequestException("Email already exists");
        }

        Specialization specialization = specializationRepository.findById(request.getSpecializationId())
                .orElseThrow(() -> new ResourceNotFoundException("Specialization not found"));

        User user = new User();
        user.setEmail(normalizedEmail);
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setRole(Role.DOCTOR);
        User savedUser = userRepository.save(user);

        Doctor doctor = new Doctor();
        doctor.setUser(savedUser);
        doctor.setSpecialization(specialization);
        doctor.setFirstName(request.getFirstName().trim());
        doctor.setLastName(request.getLastName().trim());
        doctor.setQualification(request.getQualification().trim());
        doctor.setExperience(request.getExperience());
        doctor.setVerificationStatus(VerificationStatus.UNVERIFIED);
        Doctor savedDoctor = doctorRepository.save(doctor);

        if (doctorApplicationRepository != null) {
            DoctorApplication app = new DoctorApplication();
            app.setUser(savedUser);
            app.setSpecialization(specialization);
            app.setFirstName(doctor.getFirstName());
            app.setLastName(doctor.getLastName());
            app.setQualification(doctor.getQualification());
            app.setExperience(doctor.getExperience());
            app.setStatus(ApplicationStatus.PENDING);
            app.setCreatedAt(LocalDateTime.now());
            doctorApplicationRepository.save(app);
        }

        auditLogService.logEvent(
                AuditEventType.ADMIN_CREATED_DOCTOR,
                savedUser.getEmail(),
                "ROLE_DOCTOR",
                "Doctor",
                savedDoctor.getId(),
                "Doctor self-registered profile pending verification",
                null
        );

        return savedDoctor;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Doctor saveDoctor(Doctor doctor) {
        if (doctor == null) {
            throw new IllegalArgumentException("Doctor must not be null");
        }
        if (doctor.getId() == null) {
            return doctorRepository.save(doctor);
        }

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

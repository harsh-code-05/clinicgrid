package com.harsh.hospitalmanagement.controller;

import com.harsh.hospitalmanagement.config.SensitiveResponseHeaders;
import com.harsh.hospitalmanagement.dto.*;
import com.harsh.hospitalmanagement.entity.*;
import com.harsh.hospitalmanagement.security.LocalAccount;
import com.harsh.hospitalmanagement.service.AppointmentService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/appointments")
public class AppointmentController {

    private final AppointmentService appointmentService;

    public AppointmentController(AppointmentService appointmentService) {
        this.appointmentService = appointmentService;
    }

    @PostMapping
    public ResponseEntity<AppointmentResponse> bookAppointment(
            @Valid @RequestBody AppointmentRequest request,
            Authentication authentication) {

        Appointment appointment = appointmentService.bookAppointment(
                request.getPatientId(),
                request.getDoctorId(),
                request.getSlotId(),
                request.getAppointmentDate(),
                request.getReason(),
                LocalAccount.require(authentication)
        );

        return SensitiveResponseHeaders.apply(ResponseEntity.ok())
                .body(toResponse(appointment));
    }

    @GetMapping
    public ResponseEntity<List<AppointmentResponse>> getAppointments(
            Authentication authentication) {

        String role = getRole(authentication);

        List<Appointment> appointments =
                appointmentService.getAppointments(
                        LocalAccount.require(authentication),
                        role
                );

        List<AppointmentResponse> responses = appointments.stream()
                .map(this::toResponse)
                .toList();

        return SensitiveResponseHeaders.apply(ResponseEntity.ok())
                .body(responses);
    }

    @GetMapping("/{id}")
    public ResponseEntity<AppointmentResponse> getAppointmentById(
            @PathVariable Long id,
            Authentication authentication) {

        String role = getRole(authentication);

        Appointment appointment =
                appointmentService.getAppointmentById(
                        id,
                        LocalAccount.require(authentication),
                        role
                );

        return SensitiveResponseHeaders.apply(ResponseEntity.ok())
                .body(toResponse(appointment));
    }

    private String getRole(Authentication authentication) {
        Set<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        if (authorities.contains("ROLE_ADMIN")) {
            return "ROLE_ADMIN";
        }
        if (authorities.contains("ROLE_DOCTOR")) {
            return "ROLE_DOCTOR";
        }
        if (authorities.contains("ROLE_PATIENT")) {
            return "ROLE_PATIENT";
        }
        return "";
    }

    private AppointmentResponse toResponse(Appointment appointment) {

        Patient patient = appointment.getPatient();
        User patientUser = patient.getUser();

        UserResponse patientUserResponse = new UserResponse();
        patientUserResponse.setId(patientUser.getId());
        patientUserResponse.setEmail(patientUser.getEmail());
        patientUserResponse.setRole(patientUser.getRole());

        PatientResponse patientResponse = new PatientResponse();
        patientResponse.setId(patient.getId());
        patientResponse.setFirstName(patient.getFirstName());
        patientResponse.setLastName(patient.getLastName());
        patientResponse.setDateOfBirth(patient.getDateOfBirth());
        patientResponse.setGender(patient.getGender());
        patientResponse.setPhone(patient.getPhone());
        patientResponse.setUser(patientUserResponse);


        Doctor doctor = appointment.getDoctor();
        User doctorUser = doctor.getUser();
        Specialization specialization = doctor.getSpecialization();

        UserResponse doctorUserResponse = new UserResponse();
        doctorUserResponse.setId(doctorUser.getId());
        doctorUserResponse.setEmail(doctorUser.getEmail());
        doctorUserResponse.setRole(doctorUser.getRole());

        SpecializationResponse specializationResponse =
                new SpecializationResponse();
        specializationResponse.setId(specialization.getId());
        specializationResponse.setName(specialization.getName());
        specializationResponse.setDescription(
                specialization.getDescription()
        );

        DoctorResponse doctorResponse = new DoctorResponse();
        doctorResponse.setId(doctor.getId());
        doctorResponse.setFirstName(doctor.getFirstName());
        doctorResponse.setLastName(doctor.getLastName());
        doctorResponse.setQualification(doctor.getQualification());
        doctorResponse.setExperience(doctor.getExperience());
        doctorResponse.setVerificationStatus(doctor.getVerificationStatus());
        doctorResponse.setUser(doctorUserResponse);
        doctorResponse.setSpecialization(specializationResponse);


        AppointmentSlot slot = appointment.getSlot();

        AppointmentSlotResponse slotResponse =
                new AppointmentSlotResponse();

        slotResponse.setId(slot.getId());
        slotResponse.setDate(slot.getDate());
        slotResponse.setStartTime(slot.getStartTime());
        slotResponse.setEndTime(slot.getEndTime());
        slotResponse.setStatus(slot.getStatus());
        slotResponse.setDoctor(doctorResponse);


        AppointmentResponse response = new AppointmentResponse();

        response.setId(appointment.getId());
        response.setReason(appointment.getReason());
        response.setCreatedAt(appointment.getCreatedAt());
        response.setStatus(appointment.getStatus());
        response.setCancellationReason(appointment.getCancellationReason());
        response.setCancelledAt(appointment.getCancelledAt());
        response.setCancelledByRole(appointment.getCancelledByRole());
        response.setCancellationSlotOutcome(appointment.getCancellationSlotOutcome());
        response.setPatient(patientResponse);
        response.setDoctor(doctorResponse);
        response.setSlot(slotResponse);
        if (appointment.getOfferVersion() != null) {
            response.setOfferSnapshot(new AppointmentResponse.OfferSnapshot(
                    appointment.getLocation() == null ? null : appointment.getLocation().getId(),
                    appointment.getConsultationMode(), appointment.getClinicNameSnapshot(),
                    appointment.getClinicAddressSnapshot(), appointment.getConsultationFee(), appointment.getOfferVersion()));
        }

        return response;
    }

    @PatchMapping("/{id}/cancel")
    public ResponseEntity<AppointmentResponse> cancelAppointment(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) CancelAppointmentRequest request,
            Authentication authentication) {

        String role = getRole(authentication);
        LocalAccount actor = LocalAccount.require(authentication);

        Appointment appointment = request == null
                ? appointmentService.cancelAppointment(id, actor, role)
                : appointmentService.cancelAppointment(id, actor, role, request.getReason());

        return SensitiveResponseHeaders.apply(ResponseEntity.ok())
                .body(toResponse(appointment));
    }

    @PatchMapping("/{id}/complete")
    public ResponseEntity<AppointmentResponse> completeAppointment(
            @PathVariable Long id,
            Authentication authentication) {

        String role = getRole(authentication);

        Appointment appointment = appointmentService.completeAppointment(
                id,
                LocalAccount.require(authentication),
                role
        );

        return SensitiveResponseHeaders.apply(ResponseEntity.ok())
                .body(toResponse(appointment));
    }
}

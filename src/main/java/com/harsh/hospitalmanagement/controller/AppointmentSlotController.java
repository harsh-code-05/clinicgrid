package com.harsh.hospitalmanagement.controller;

import com.harsh.hospitalmanagement.config.SensitiveResponseHeaders;
import com.harsh.hospitalmanagement.dto.AppointmentSlotResponse;
import com.harsh.hospitalmanagement.dto.DoctorResponse;
import com.harsh.hospitalmanagement.dto.SpecializationResponse;
import com.harsh.hospitalmanagement.dto.UserResponse;
import com.harsh.hospitalmanagement.entity.AppointmentSlot;
import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.Specialization;
import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.exception.ForbiddenException;
import com.harsh.hospitalmanagement.security.LocalAccount;
import com.harsh.hospitalmanagement.service.AppointmentSlotService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/appointment-slots")
public class AppointmentSlotController {

    private final AppointmentSlotService appointmentSlotService;

    public AppointmentSlotController(
            AppointmentSlotService appointmentSlotService) {
        this.appointmentSlotService = appointmentSlotService;
    }

    @GetMapping("/available")
    public ResponseEntity<List<AppointmentSlotResponse>> getAvailableSlots(
            @RequestParam Long doctorId,
            @RequestParam(required = false) LocalDate date) {

        List<AppointmentSlot> slots = appointmentSlotService.getAvailableSlots(doctorId, date);

        List<AppointmentSlotResponse> responses = slots.stream()
                .map(this::toResponse)
                .toList();

        return ResponseEntity.ok(responses);
    }

    @GetMapping("/offers")
    public ResponseEntity<List<AppointmentSlotResponse>> getOffers(
            @RequestParam Long doctorId,
            @RequestParam(required = false) LocalDate date) {

        List<AppointmentSlot> slots = appointmentSlotService.getAvailableSlots(doctorId, date);

        List<AppointmentSlotResponse> responses = slots.stream()
                .map(this::toResponse)
                .toList();

        return ResponseEntity.ok(responses);
    }

    @GetMapping("/{id}")
    public ResponseEntity<AppointmentSlotResponse> getSlotById(
            @PathVariable Long id) {

        AppointmentSlot slot = appointmentSlotService.getSlotById(id);

        return ResponseEntity.ok(toResponse(slot));
    }

    @PatchMapping("/{id}/unblock")
    public ResponseEntity<AppointmentSlotResponse> unblockSlot(
            @PathVariable Long id,
            Authentication authentication) {

        boolean admin = authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        if (!admin) {
            throw new ForbiddenException("Only admins can unblock appointment slots");
        }

        AppointmentSlot slot = appointmentSlotService.unblockSlot(id, LocalAccount.require(authentication));
        return SensitiveResponseHeaders.apply(ResponseEntity.ok())
                .body(toResponse(slot));
    }

    private AppointmentSlotResponse toResponse(AppointmentSlot slot) {

        Doctor doctor = slot.getDoctor();
        User user = doctor.getUser();
        Specialization specialization = doctor.getSpecialization();

        UserResponse userResponse = new UserResponse();
        userResponse.setId(user.getId());
        userResponse.setEmail(user.getEmail());
        userResponse.setRole(user.getRole());

        SpecializationResponse specializationResponse =
                new SpecializationResponse();
        specializationResponse.setId(specialization.getId());
        specializationResponse.setName(specialization.getName());
        specializationResponse.setDescription(
                specialization.getDescription());

        DoctorResponse doctorResponse = new DoctorResponse();
        doctorResponse.setId(doctor.getId());
        doctorResponse.setFirstName(doctor.getFirstName());
        doctorResponse.setLastName(doctor.getLastName());
        doctorResponse.setQualification(doctor.getQualification());
        doctorResponse.setExperience(doctor.getExperience());
        doctorResponse.setUser(userResponse);
        doctorResponse.setSpecialization(specializationResponse);

        AppointmentSlotResponse response = new AppointmentSlotResponse();

        response.setId(slot.getId());
        response.setDate(slot.getDate());
        response.setStartTime(slot.getStartTime());
        response.setEndTime(slot.getEndTime());
        response.setStatus(slot.getStatus());
        response.setDoctor(doctorResponse);

        return response;
    }

    @PostMapping("/{availabilityId}/generate")
    public ResponseEntity<List<AppointmentSlotResponse>> generateSlots(
            @PathVariable Long availabilityId,
            Authentication authentication) {

        boolean admin = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));

        List<AppointmentSlot> slots =
                appointmentSlotService.generateSlots(
                        availabilityId,
                        LocalAccount.require(authentication),
                        admin);

        List<AppointmentSlotResponse> responses =
                slots.stream()
                        .map(this::toResponse)
                        .toList();

        return ResponseEntity.ok(responses);
    }
}

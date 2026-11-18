package com.harsh.hospitalmanagement.controller;

import com.harsh.hospitalmanagement.config.SensitiveResponseHeaders;
import com.harsh.hospitalmanagement.dto.DoctorVerificationQueueResponse;
import com.harsh.hospitalmanagement.enums.VerificationStatus;
import com.harsh.hospitalmanagement.exception.BadRequestException;
import com.harsh.hospitalmanagement.service.DoctorVerificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/doctor-verifications")
public class AdminDoctorVerificationController {

    private final DoctorVerificationService verificationService;

    public AdminDoctorVerificationController(DoctorVerificationService verificationService) {
        this.verificationService = verificationService;
    }

    @GetMapping
    public ResponseEntity<DoctorVerificationQueueResponse> listVerificationQueue(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        return SensitiveResponseHeaders.apply(ResponseEntity.ok())
                .body(verificationService.getAdminVerificationQueue(parseStatusFilter(status), page, size, authentication));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Map<String, Object>> invalidQueryParameter() {
        return SensitiveResponseHeaders.apply(ResponseEntity.badRequest())
                .body(Map.of("status", 400, "message", "Invalid verification queue query parameter"));
    }

    private static VerificationStatus parseStatusFilter(String status) {
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status.trim())) {
            return null;
        }
        try {
            return VerificationStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("Invalid verification status filter: " + status);
        }
    }
}

package com.harsh.hospitalmanagement.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DoctorVerificationApprovalRequest(
        @NotBlank(message = "Review version is required") @Size(max = 128) String reviewVersion) {
}

package com.harsh.hospitalmanagement.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecordPaymentRequest {

    @NotBlank(message = "Payment method is required")
    @Size(max = 32, message = "Payment method cannot exceed 32 characters")
    private String paymentMethod = "MANUAL_DEMO";

    @Size(max = 128, message = "Payment reference cannot exceed 128 characters")
    private String paymentReference;

    @Size(max = 500, message = "Notes cannot exceed 500 characters")
    private String notes;
}

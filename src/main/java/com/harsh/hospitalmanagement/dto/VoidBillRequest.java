package com.harsh.hospitalmanagement.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VoidBillRequest {

    @NotBlank(message = "Void reason is required")
    @Size(max = 500, message = "Void reason cannot exceed 500 characters")
    private String reason;
}

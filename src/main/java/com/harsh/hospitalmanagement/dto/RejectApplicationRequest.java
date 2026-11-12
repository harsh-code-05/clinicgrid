package com.harsh.hospitalmanagement.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RejectApplicationRequest {

    @NotBlank(message = "Rejection reason is required")
    @Size(max = 255, message = "Rejection reason must be at most 255 characters")
    private String reason;
}

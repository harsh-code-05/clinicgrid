package com.harsh.hospitalmanagement.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CancelAppointmentRequest {

    @Pattern(regexp = "^[^\\u0000]*$", message = "Cancellation reason cannot contain NUL characters")
    @Size(max = 500, message = "Cancellation reason cannot exceed 500 characters")
    private String reason;
}

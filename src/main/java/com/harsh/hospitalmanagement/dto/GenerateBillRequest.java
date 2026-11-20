package com.harsh.hospitalmanagement.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class GenerateBillRequest {

    @NotNull(message = "Appointment ID is required")
    @Positive(message = "Appointment ID must be positive")
    private Long appointmentId;

    public GenerateBillRequest(Long appointmentId) {
        this.appointmentId = appointmentId;
    }

    @JsonCreator
    public static GenerateBillRequest fromJson(@JsonProperty("appointmentId") JsonNode node) {
        if (node == null || node.isNull()) {
            return new GenerateBillRequest(null);
        }
        if (node.isFloatingPointNumber() || node.isDouble() || node.isFloat()) {
            throw new IllegalArgumentException("Fractional appointmentId is strictly forbidden: " + node.asText());
        }
        if (node.isIntegralNumber()) {
            long val = node.asLong();
            if (val <= 0) {
                throw new IllegalArgumentException("appointmentId must be a positive integer");
            }
            return new GenerateBillRequest(val);
        }
        if (node.isTextual()) {
            String text = node.asText().trim();
            if (!text.matches("^[1-9]\\d*$")) {
                throw new IllegalArgumentException("appointmentId must be a positive integer string: " + text);
            }
            return new GenerateBillRequest(Long.parseLong(text));
        }
        throw new IllegalArgumentException("Invalid appointmentId format");
    }
}

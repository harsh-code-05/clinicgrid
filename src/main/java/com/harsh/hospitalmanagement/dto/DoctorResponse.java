package com.harsh.hospitalmanagement.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DoctorResponse {

    private Long id;
    private String firstName;
    private String lastName;
    private String qualification;
    private Integer experience;
    private com.harsh.hospitalmanagement.enums.VerificationStatus verificationStatus;

    private UserResponse user;

    private SpecializationResponse specialization;
}
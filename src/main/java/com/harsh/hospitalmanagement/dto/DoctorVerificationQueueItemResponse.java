package com.harsh.hospitalmanagement.dto;

import com.harsh.hospitalmanagement.enums.VerificationActorRole;
import com.harsh.hospitalmanagement.enums.VerificationStatus;

import java.time.Instant;

public record DoctorVerificationQueueItemResponse(
        Long doctorId,
        String doctorName,
        String specialization,
        VerificationStatus status,
        Instant submittedAt,
        Instant latestUpdatedAt,
        VerificationActorRole latestActorRole,
        String registrationNumber,
        String medicalCouncil,
        Integer registrationYear,
        String latestRejectionReason) {
}

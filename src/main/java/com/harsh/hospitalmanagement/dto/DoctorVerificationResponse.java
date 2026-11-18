package com.harsh.hospitalmanagement.dto;

import com.harsh.hospitalmanagement.enums.VerificationActorRole;
import com.harsh.hospitalmanagement.enums.VerificationStatus;

import java.time.Instant;
import java.util.List;

public record DoctorVerificationResponse(
        Long doctorId,
        String doctorName,
        String email,
        String specialization,
        String qualification,
        Integer experience,
        VerificationStatus status,
        String registrationNumber,
        String medicalCouncil,
        Integer registrationYear,
        String documentStatus,
        Boolean storageAvailable,
        String latestRejectionReason,
        String reviewVersion,
        List<HistoryEntry> history) {

    public DoctorVerificationResponse(
            Long doctorId,
            VerificationStatus status,
            String registrationNumber,
            String medicalCouncil,
            Integer registrationYear,
            String latestRejectionReason,
            String reviewVersion,
            List<HistoryEntry> history) {
        this(doctorId, null, null, null, null, null, status, registrationNumber, medicalCouncil,
                registrationYear, null, null, latestRejectionReason, reviewVersion, history);
    }

    public record HistoryEntry(VerificationStatus previousStatus, VerificationStatus newStatus,
                               VerificationActorRole actorRole, Instant occurredAt) {
    }
}

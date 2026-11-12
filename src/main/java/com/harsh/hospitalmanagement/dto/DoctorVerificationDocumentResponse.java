package com.harsh.hospitalmanagement.dto;

import com.harsh.hospitalmanagement.entity.DoctorVerificationDocument;
import com.harsh.hospitalmanagement.enums.VerificationDocumentKind;

import java.time.Instant;

public record DoctorVerificationDocumentResponse(
        Long doctorId,
        VerificationDocumentKind documentKind,
        String mediaType,
        long fileSize,
        Instant createdAt,
        Instant retiredAt
) {
    public static DoctorVerificationDocumentResponse from(DoctorVerificationDocument doc) {
        return new DoctorVerificationDocumentResponse(
                doc.getDoctor().getId(),
                doc.getDocumentKind(),
                doc.getMediaType(),
                doc.getFileSize(),
                doc.getCreatedAt(),
                doc.getRetiredAt()
        );
    }
}

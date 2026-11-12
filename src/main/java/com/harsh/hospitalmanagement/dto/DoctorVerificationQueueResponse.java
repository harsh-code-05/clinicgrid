package com.harsh.hospitalmanagement.dto;

import java.util.List;

public record DoctorVerificationQueueResponse(
        List<DoctorVerificationQueueItemResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext,
        boolean hasPrevious) {
}

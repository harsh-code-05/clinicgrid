package com.harsh.hospitalmanagement.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SignalingMessage {
    private String type;
    private Long appointmentId;
    private String senderRole;
    private String senderName;
    private Long generation;
    private String sessionId;
    private Object payload;
}

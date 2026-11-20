package com.harsh.hospitalmanagement.controller;

import com.harsh.hospitalmanagement.dto.SignalingMessage;
import com.harsh.hospitalmanagement.exception.BadRequestException;
import com.harsh.hospitalmanagement.security.LocalAccount;
import com.harsh.hospitalmanagement.service.VideoConsultationService;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.util.Locale;
import java.util.Set;

@Controller
public class VideoSignalingController {

    private static final Set<String> ALLOWED_SIGNAL_TYPES = Set.of(
            "JOIN", "LEAVE", "READY", "OFFER", "ANSWER", "ICE_CANDIDATE", "MEDIA_STATE", "RECONNECT"
    );

    private final SimpMessagingTemplate messagingTemplate;
    private final VideoConsultationService videoConsultationService;

    public VideoSignalingController(
            SimpMessagingTemplate messagingTemplate,
            VideoConsultationService videoConsultationService) {
        this.messagingTemplate = messagingTemplate;
        this.videoConsultationService = videoConsultationService;
    }

    @MessageMapping("/video/signal/{appointmentId}")
    public void handleSignal(
            @DestinationVariable Long appointmentId,
            SignalingMessage message,
            Authentication authentication) {
        if (appointmentId == null || appointmentId <= 0 || appointmentId > 10_000_000_000L) {
            throw new BadRequestException("Invalid appointment ID");
        }
        if (message == null || message.getType() == null || message.getType().isBlank()) {
            throw new BadRequestException("Signaling message type is required");
        }
        if (message.getSessionId() != null && message.getSessionId().length() > 128) {
            throw new BadRequestException("Session ID exceeds maximum length");
        }
        if (message.getGeneration() != null && message.getGeneration() < 0) {
            throw new BadRequestException("Generation must be non-negative");
        }

        String normalizedType = message.getType().trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_SIGNAL_TYPES.contains(normalizedType)) {
            throw new BadRequestException("Unsupported signaling message type: " + message.getType());
        }

        if (Set.of("OFFER", "ANSWER", "ICE_CANDIDATE").contains(normalizedType)) {
            Object payload = message.getPayload();
            if (payload == null) {
                throw new BadRequestException("Signaling payload is required for " + normalizedType);
            }
            if (!(payload instanceof java.util.Map<?, ?> map)) {
                throw new BadRequestException("Signaling payload must be an object for " + normalizedType);
            }
            if (Set.of("OFFER", "ANSWER").contains(normalizedType)) {
                Object sdpType = map.get("type");
                Object sdp = map.get("sdp");
                if (!(sdpType instanceof String sdpTypeStr) || sdpTypeStr.isBlank()) {
                    throw new BadRequestException("SDP payload requires non-blank string 'type'");
                }
                if (!normalizedType.equalsIgnoreCase(sdpTypeStr.trim())) {
                    throw new BadRequestException("SDP type '" + sdpTypeStr + "' does not match signaling message type " + normalizedType);
                }
                if (!(sdp instanceof String sdpStr) || sdpStr.isBlank()) {
                    throw new BadRequestException("SDP payload requires non-blank string 'sdp'");
                }
            } else if ("ICE_CANDIDATE".equals(normalizedType)) {
                if (!map.containsKey("candidate")) {
                    throw new BadRequestException("ICE candidate payload must contain 'candidate'");
                }
                Object candidate = map.get("candidate");
                if (candidate != null && !(candidate instanceof String)) {
                    throw new BadRequestException("ICE candidate 'candidate' field must be a string or null");
                }
                Object sdpMid = map.get("sdpMid");
                if (sdpMid != null && !(sdpMid instanceof String)) {
                    throw new BadRequestException("ICE candidate 'sdpMid' must be a string");
                }
                Object sdpMLineIndex = map.get("sdpMLineIndex");
                if (sdpMLineIndex != null && !(sdpMLineIndex instanceof Number)) {
                    throw new BadRequestException("ICE candidate 'sdpMLineIndex' must be a number");
                }
            }
        }

        LocalAccount actor = LocalAccount.require(authentication);
        VideoConsultationService.SignalingParticipant participant =
                videoConsultationService.validateSignalingAccess(appointmentId, actor);

        message.setType(normalizedType);
        message.setAppointmentId(appointmentId);
        message.setSenderRole(participant.role());
        message.setSenderName(participant.name());

        messagingTemplate.convertAndSend("/topic/video/room/" + appointmentId, message);
    }
}

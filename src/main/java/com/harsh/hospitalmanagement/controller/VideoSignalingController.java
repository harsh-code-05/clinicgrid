package com.harsh.hospitalmanagement.controller;

import com.harsh.hospitalmanagement.dto.SignalingMessage;
import com.harsh.hospitalmanagement.security.LocalAccount;
import com.harsh.hospitalmanagement.service.VideoConsultationService;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

@Controller
public class VideoSignalingController {

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
        LocalAccount actor = LocalAccount.require(authentication);
        videoConsultationService.validateSignalingAccess(appointmentId, actor);

        message.setAppointmentId(appointmentId);
        messagingTemplate.convertAndSend("/topic/video/room/" + appointmentId, message);
    }
}

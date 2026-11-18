package com.harsh.hospitalmanagement.controller;

import com.harsh.hospitalmanagement.dto.VideoRoomResponse;
import com.harsh.hospitalmanagement.security.LocalAccount;
import com.harsh.hospitalmanagement.service.VideoConsultationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/appointments")
public class VideoConsultationController {

    private final VideoConsultationService videoConsultationService;

    public VideoConsultationController(VideoConsultationService videoConsultationService) {
        this.videoConsultationService = videoConsultationService;
    }

    @GetMapping("/{id}/video-room")
    public ResponseEntity<VideoRoomResponse> getRoomDetails(
            @PathVariable Long id,
            Authentication authentication) {
        LocalAccount actor = LocalAccount.require(authentication);
        VideoRoomResponse response = videoConsultationService.getRoomDetails(id, actor);
        return ResponseEntity.ok(response);
    }
}

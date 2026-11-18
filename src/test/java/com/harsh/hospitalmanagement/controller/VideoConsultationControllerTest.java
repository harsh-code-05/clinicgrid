package com.harsh.hospitalmanagement.controller;

import com.harsh.hospitalmanagement.dto.VideoRoomResponse;
import com.harsh.hospitalmanagement.exception.ForbiddenException;
import com.harsh.hospitalmanagement.exception.GlobalExceptionHandler;
import com.harsh.hospitalmanagement.exception.ResourceNotFoundException;
import com.harsh.hospitalmanagement.security.WithLocalUser;
import com.harsh.hospitalmanagement.security.WithMvcSecurity;
import com.harsh.hospitalmanagement.service.VideoConsultationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static com.harsh.hospitalmanagement.security.WithLocalUser.Factory.account;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {VideoConsultationController.class, GlobalExceptionHandler.class})
@WithMvcSecurity
class VideoConsultationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VideoConsultationService videoConsultationService;

    @Test
    @WithLocalUser(username = "patient@test.com", roles = "PATIENT")
    void getRoomDetails_shouldReturn200_whenAuthorizedPatient() throws Exception {
        VideoRoomResponse response = VideoRoomResponse.builder()
                .appointmentId(1L)
                .roomId("room-1-abc123def456")
                .userRole("PATIENT")
                .userName("John Doe")
                .peerName("Dr. Gregory House")
                .doctorVerified(true)
                .canJoin(true)
                .statusMessage("Ready to connect to video consultation.")
                .iceServers(List.of(VideoRoomResponse.IceServerConfig.builder()
                        .urls(List.of("stun:stun.l.google.com:19302"))
                        .build()))
                .build();

        when(videoConsultationService.getRoomDetails(eq(1L), eq(account("patient@test.com"))))
                .thenReturn(response);

        mockMvc.perform(get("/api/appointments/1/video-room"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appointmentId").value(1))
                .andExpect(jsonPath("$.roomId").value("room-1-abc123def456"))
                .andExpect(jsonPath("$.canJoin").value(true))
                .andExpect(jsonPath("$.doctorVerified").value(true))
                .andExpect(jsonPath("$.userRole").value("PATIENT"))
                .andExpect(jsonPath("$.peerName").value("Dr. Gregory House"));
    }

    @Test
    @WithLocalUser(username = "doctor@test.com", roles = "DOCTOR")
    void getRoomDetails_shouldReturn200_whenAuthorizedDoctor() throws Exception {
        VideoRoomResponse response = VideoRoomResponse.builder()
                .appointmentId(1L)
                .roomId("room-1-abc123def456")
                .userRole("DOCTOR")
                .userName("Dr. Gregory House")
                .peerName("John Doe")
                .doctorVerified(true)
                .canJoin(true)
                .statusMessage("Ready to connect to video consultation.")
                .build();

        when(videoConsultationService.getRoomDetails(eq(1L), eq(account("doctor@test.com"))))
                .thenReturn(response);

        mockMvc.perform(get("/api/appointments/1/video-room"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userRole").value("DOCTOR"))
                .andExpect(jsonPath("$.peerName").value("John Doe"));
    }

    @Test
    @WithLocalUser(username = "patient@test.com", roles = "PATIENT")
    void getRoomDetails_shouldReturn200WithCanJoinFalse_whenDoctorUnverified() throws Exception {
        VideoRoomResponse response = VideoRoomResponse.builder()
                .appointmentId(146L)
                .roomId("room-146-xyz789")
                .userRole("PATIENT")
                .userName("Alice Smith")
                .peerName("Dr. Amit Sharma")
                .doctorVerified(false)
                .canJoin(false)
                .statusMessage("Doctor verification is pending. Video consultation room is locked.")
                .build();

        when(videoConsultationService.getRoomDetails(eq(146L), eq(account("patient@test.com"))))
                .thenReturn(response);

        mockMvc.perform(get("/api/appointments/146/video-room"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canJoin").value(false))
                .andExpect(jsonPath("$.doctorVerified").value(false))
                .andExpect(jsonPath("$.statusMessage").value("Doctor verification is pending. Video consultation room is locked."));
    }

    @Test
    @WithLocalUser(username = "stranger@test.com", roles = "PATIENT")
    void getRoomDetails_shouldReturn403_whenUnrelatedUser() throws Exception {
        when(videoConsultationService.getRoomDetails(eq(1L), eq(account("stranger@test.com"))))
                .thenThrow(new ForbiddenException("You are not authorized to access this consultation room"));

        mockMvc.perform(get("/api/appointments/1/video-room"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithLocalUser(username = "patient@test.com", roles = "PATIENT")
    void getRoomDetails_shouldReturn404_whenAppointmentDoesNotExist() throws Exception {
        when(videoConsultationService.getRoomDetails(eq(999L), eq(account("patient@test.com"))))
                .thenThrow(new ResourceNotFoundException("Appointment not found: 999"));

        mockMvc.perform(get("/api/appointments/999/video-room"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getRoomDetails_shouldReturn401_whenUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/appointments/1/video-room"))
                .andExpect(status().isUnauthorized());
    }
}

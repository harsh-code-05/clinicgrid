package com.harsh.hospitalmanagement.controller;

import com.harsh.hospitalmanagement.dto.SignalingMessage;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.exception.BadRequestException;
import com.harsh.hospitalmanagement.exception.ForbiddenException;
import com.harsh.hospitalmanagement.security.LocalAccount;
import com.harsh.hospitalmanagement.service.VideoConsultationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VideoSignalingControllerTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private VideoConsultationService videoConsultationService;

    private VideoSignalingController controller;
    private Authentication doctorAuth;
    private Authentication patientAuth;

    @BeforeEach
    void setUp() {
        controller = new VideoSignalingController(messagingTemplate, videoConsultationService);

        LocalAccount.PasswordPrincipal docPrincipal = new LocalAccount.PasswordPrincipal(
                new LocalAccount(20L, "doctor@test.com"), "hash", Role.DOCTOR);
        doctorAuth = new UsernamePasswordAuthenticationToken(docPrincipal, null,
                List.of(new SimpleGrantedAuthority("ROLE_DOCTOR")));

        LocalAccount.PasswordPrincipal patPrincipal = new LocalAccount.PasswordPrincipal(
                new LocalAccount(10L, "patient@test.com"), "hash", Role.PATIENT);
        patientAuth = new UsernamePasswordAuthenticationToken(patPrincipal, null,
                List.of(new SimpleGrantedAuthority("ROLE_PATIENT")));
    }

    @Test
    void handleSignal_shouldBroadcast_withEnforcedRoleAndName_whenAuthorized() {
        when(videoConsultationService.validateSignalingAccess(eq(1L), any(LocalAccount.class)))
                .thenReturn(new VideoConsultationService.SignalingParticipant("DOCTOR", "Dr. Gregory House"));

        SignalingMessage incoming = SignalingMessage.builder()
                .type("OFFER")
                .appointmentId(999L) // Attacker attempting cross-appointment ID
                .senderRole("PATIENT") // Attacker attempting role spoof
                .senderName("Fake Name")
                .payload(Map.of("type", "offer", "sdp", "v=0..."))
                .build();

        controller.handleSignal(1L, incoming, doctorAuth);

        ArgumentCaptor<SignalingMessage> messageCaptor = ArgumentCaptor.forClass(SignalingMessage.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/video/room/1"), messageCaptor.capture());

        SignalingMessage broadcast = messageCaptor.getValue();
        assertThat(broadcast.getAppointmentId()).isEqualTo(1L);
        assertThat(broadcast.getSenderRole()).isEqualTo("DOCTOR");
        assertThat(broadcast.getSenderName()).isEqualTo("Dr. Gregory House");
        assertThat(broadcast.getType()).isEqualTo("OFFER");
    }

    @Test
    void handleSignal_shouldThrowForbidden_whenWebrtcDisabled() {
        when(videoConsultationService.validateSignalingAccess(eq(1L), any(LocalAccount.class)))
                .thenThrow(new ForbiddenException("Video consultation is temporarily disabled pending security review."));

        SignalingMessage incoming = SignalingMessage.builder()
                .type("JOIN")
                .build();

        assertThatThrownBy(() -> controller.handleSignal(1L, incoming, patientAuth))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("temporarily disabled pending security review");

        verifyNoInteractions(messagingTemplate);
    }

    @Test
    void handleSignal_shouldThrowBadRequest_whenInvalidMessageType() {
        SignalingMessage incoming = SignalingMessage.builder()
                .type("MALICIOUS_TYPE")
                .build();

        assertThatThrownBy(() -> controller.handleSignal(1L, incoming, patientAuth))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Unsupported signaling message type");

        verifyNoInteractions(messagingTemplate);
    }

    @Test
    void handleSignal_shouldThrowBadRequest_whenPayloadMissingForOffer() {
        SignalingMessage incoming = SignalingMessage.builder()
                .type("OFFER")
                .payload(null)
                .build();

        assertThatThrownBy(() -> controller.handleSignal(1L, incoming, patientAuth))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Signaling payload is required for OFFER");

        verifyNoInteractions(messagingTemplate);
    }

    @Test
    void handleSignal_shouldThrowBadRequest_whenAppointmentIdInvalid() {
        SignalingMessage incoming = SignalingMessage.builder()
                .type("JOIN")
                .build();

        assertThatThrownBy(() -> controller.handleSignal(-1L, incoming, patientAuth))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Invalid appointment ID");

        verifyNoInteractions(messagingTemplate);
    }

    @Test
    void handleSignal_shouldThrowBadRequest_whenPayloadMissingTypeOrSdpForOffer() {
        SignalingMessage missingType = SignalingMessage.builder()
                .type("OFFER")
                .payload(Map.of("sdp", "v=0..."))
                .build();

        assertThatThrownBy(() -> controller.handleSignal(1L, missingType, patientAuth))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("SDP payload requires non-blank string 'type'");

        SignalingMessage missingSdp = SignalingMessage.builder()
                .type("OFFER")
                .payload(Map.of("type", "offer"))
                .build();

        assertThatThrownBy(() -> controller.handleSignal(1L, missingSdp, patientAuth))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("SDP payload requires non-blank string 'sdp'");

        verifyNoInteractions(messagingTemplate);
    }

    @Test
    void handleSignal_shouldThrowBadRequest_whenPayloadIsNotMapForOfferOrCandidate() {
        for (Object invalidPayload : List.of("plain string", 12345, true, List.of("element"))) {
            SignalingMessage nonMapMsg = SignalingMessage.builder()
                    .type("OFFER")
                    .payload(invalidPayload)
                    .build();

            assertThatThrownBy(() -> controller.handleSignal(1L, nonMapMsg, patientAuth))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Signaling payload must be an object");
        }

        verifyNoInteractions(messagingTemplate);
    }

    @Test
    void handleSignal_shouldThrowBadRequest_whenSdpTypeMismatchesSignalType() {
        SignalingMessage mismatched = SignalingMessage.builder()
                .type("OFFER")
                .payload(Map.of("type", "answer", "sdp", "v=0..."))
                .build();

        assertThatThrownBy(() -> controller.handleSignal(1L, mismatched, patientAuth))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("does not match signaling message type");

        verifyNoInteractions(messagingTemplate);
    }

    @Test
    void handleSignal_shouldThrowBadRequest_whenIceCandidateMissingCandidate() {
        SignalingMessage missingCandidate = SignalingMessage.builder()
                .type("ICE_CANDIDATE")
                .payload(Map.of("sdpMid", "0"))
                .build();

        assertThatThrownBy(() -> controller.handleSignal(1L, missingCandidate, patientAuth))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("ICE candidate payload must contain 'candidate'");

        verifyNoInteractions(messagingTemplate);
    }

    @Test
    void handleSignal_shouldThrowBadRequest_whenIceCandidateInvalidTypes() {
        SignalingMessage invalidCand = SignalingMessage.builder()
                .type("ICE_CANDIDATE")
                .payload(Map.of("candidate", 12345))
                .build();

        assertThatThrownBy(() -> controller.handleSignal(1L, invalidCand, patientAuth))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("must be a string or null");

        SignalingMessage invalidMid = SignalingMessage.builder()
                .type("ICE_CANDIDATE")
                .payload(Map.of("candidate", "cand...", "sdpMid", 999))
                .build();

        assertThatThrownBy(() -> controller.handleSignal(1L, invalidMid, patientAuth))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("must be a string");

        SignalingMessage invalidMline = SignalingMessage.builder()
                .type("ICE_CANDIDATE")
                .payload(Map.of("candidate", "cand...", "sdpMLineIndex", "not-a-number"))
                .build();

        assertThatThrownBy(() -> controller.handleSignal(1L, invalidMline, patientAuth))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("must be a number");

        verifyNoInteractions(messagingTemplate);
    }

    @Test
    void handleSignal_shouldThrowBadRequest_whenSessionIdExceedsMaxLength() {
        SignalingMessage longSession = SignalingMessage.builder()
                .type("JOIN")
                .sessionId("a".repeat(129))
                .build();

        assertThatThrownBy(() -> controller.handleSignal(1L, longSession, patientAuth))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Session ID exceeds maximum length");

        verifyNoInteractions(messagingTemplate);
    }

    @Test
    void handleSignal_shouldThrowBadRequest_whenGenerationIsNegative() {
        SignalingMessage negativeGen = SignalingMessage.builder()
                .type("JOIN")
                .generation(-1L)
                .build();

        assertThatThrownBy(() -> controller.handleSignal(1L, negativeGen, patientAuth))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Generation must be non-negative");

        verifyNoInteractions(messagingTemplate);
    }
}

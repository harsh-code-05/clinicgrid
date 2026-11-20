package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.dto.VideoRoomResponse;
import com.harsh.hospitalmanagement.entity.Appointment;
import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.Patient;
import com.harsh.hospitalmanagement.enums.AppointmentStatus;
import com.harsh.hospitalmanagement.enums.VerificationStatus;
import com.harsh.hospitalmanagement.exception.ForbiddenException;
import com.harsh.hospitalmanagement.exception.ResourceNotFoundException;
import com.harsh.hospitalmanagement.repository.AppointmentRepository;
import com.harsh.hospitalmanagement.security.LocalAccount;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

@Service
public class VideoConsultationService {

    private final AppointmentRepository appointmentRepository;

    @Value("${hms.webrtc.enabled:false}")
    private boolean webrtcEnabled = false;

    @Value("${hms.webrtc.stun-urls:stun:stun.l.google.com:19302,stun:stun1.l.google.com:19302}")
    private String stunUrls = "stun:stun.l.google.com:19302,stun:stun1.l.google.com:19302";

    @Value("${hms.webrtc.turn-url:}")
    private String turnUrl;

    @Value("${hms.webrtc.turn-username:}")
    private String turnUsername;

    @Value("${hms.webrtc.turn-credential:}")
    private String turnCredential;

    @Value("${hms.webrtc.turn-secret:}")
    private String turnSecret;

    @Value("${hms.webrtc.turn-ttl-seconds:3600}")
    private long turnTtlSeconds = 3600;

    public VideoConsultationService(AppointmentRepository appointmentRepository) {
        this.appointmentRepository = appointmentRepository;
    }

    public boolean isWebrtcEnabled() {
        return webrtcEnabled;
    }

    public void setWebrtcEnabled(boolean webrtcEnabled) {
        this.webrtcEnabled = webrtcEnabled;
    }

    public record SignalingParticipant(String role, String name) {}

    void configureTurnForTest(String turnUrl, String turnSecret, long turnTtlSeconds) {
        this.turnUrl = turnUrl;
        this.turnSecret = turnSecret;
        this.turnTtlSeconds = turnTtlSeconds;
    }

    public static String generateShortLivedTurnCredential(String secret, String username) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            byte[] rawHmac = mac.doFinal(username.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(rawHmac);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to calculate short-lived TURN credential", e);
        }
    }

    @Transactional(readOnly = true)
    public VideoRoomResponse getRoomDetails(Long appointmentId, LocalAccount actor) {
        Appointment appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new ForbiddenException("You are not authorized to access this consultation room"));

        Doctor doctor = appointment.getDoctor();
        Patient patient = appointment.getPatient();

        boolean isDoctor = doctor != null && doctor.getUser() != null && doctor.getUser().getId().equals(actor.id());
        boolean isPatient = patient != null && patient.getUser() != null && patient.getUser().getId().equals(actor.id());

        if (!isDoctor && !isPatient) {
            throw new ForbiddenException("You are not authorized to access this consultation room");
        }

        if (appointment.getConsultationMode() == com.harsh.hospitalmanagement.enums.ConsultationMode.IN_CLINIC) {
            throw new ForbiddenException("This appointment is scheduled for in-clinic consultation, not video.");
        }

        boolean isVerified = doctor != null && doctor.getVerificationStatus() == VerificationStatus.VERIFIED;
        boolean isConfirmed = appointment.getStatus() == AppointmentStatus.CONFIRMED;

        String role = isDoctor ? "DOCTOR" : "PATIENT";
        String userName = isDoctor
                ? "Dr. " + doctor.getFirstName() + " " + doctor.getLastName()
                : patient.getFirstName() + " " + patient.getLastName();
        String peerName = isDoctor
                ? patient.getFirstName() + " " + patient.getLastName()
                : "Dr. " + doctor.getFirstName() + " " + doctor.getLastName();

        boolean canJoin = webrtcEnabled && isVerified && isConfirmed;
        String statusMessage;

        if (!webrtcEnabled) {
            statusMessage = "Video consultation is temporarily disabled pending security review.";
        } else if (!isVerified) {
            statusMessage = "Doctor verification is pending. Video consultation room is locked.";
        } else if (!isConfirmed) {
            statusMessage = "Appointment is " + appointment.getStatus() + ". Video consultation is closed.";
        } else {
            statusMessage = "Ready to connect to video consultation.";
        }

        String roomId = generateDeterministicRoomId(appointmentId);

        return VideoRoomResponse.builder()
                .appointmentId(appointmentId)
                .roomId(roomId)
                .userRole(role)
                .userName(userName)
                .peerName(peerName)
                .doctorVerified(isVerified)
                .canJoin(canJoin)
                .statusMessage(statusMessage)
                .iceServers(canJoin ? buildIceServers(actor) : java.util.Collections.emptyList())
                .build();
    }

    @Transactional(readOnly = true)
    public SignalingParticipant validateSignalingAccess(Long appointmentId, LocalAccount actor) {
        if (!webrtcEnabled) {
            throw new ForbiddenException("Video consultation is temporarily disabled pending security review.");
        }

        Appointment appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new ForbiddenException("You are not authorized to participate in this consultation room"));

        Doctor doctor = appointment.getDoctor();
        Patient patient = appointment.getPatient();

        boolean isDoctor = doctor != null && doctor.getUser() != null && doctor.getUser().getId().equals(actor.id());
        boolean isPatient = patient != null && patient.getUser() != null && patient.getUser().getId().equals(actor.id());

        if (!isDoctor && !isPatient) {
            throw new ForbiddenException("You are not authorized to participate in this consultation room");
        }

        if (appointment.getConsultationMode() == com.harsh.hospitalmanagement.enums.ConsultationMode.IN_CLINIC) {
            throw new ForbiddenException("This appointment is scheduled for in-clinic consultation, not video.");
        }

        if (doctor == null || doctor.getVerificationStatus() != VerificationStatus.VERIFIED) {
            throw new ForbiddenException("Doctor is not verified. Video room access is locked.");
        }

        if (appointment.getStatus() != AppointmentStatus.CONFIRMED) {
            throw new ForbiddenException("Appointment is not confirmed. Video room is closed.");
        }

        String role = isDoctor ? "DOCTOR" : "PATIENT";
        String name = isDoctor
                ? "Dr. " + doctor.getFirstName() + " " + doctor.getLastName()
                : patient.getFirstName() + " " + patient.getLastName();
        return new SignalingParticipant(role, name);
    }

    private List<VideoRoomResponse.IceServerConfig> buildIceServers(LocalAccount actor) {
        if (!webrtcEnabled) {
            return java.util.Collections.emptyList();
        }
        String urls = (stunUrls != null && !stunUrls.isBlank())
                ? stunUrls
                : "stun:stun.l.google.com:19302,stun:stun1.l.google.com:19302";
        List<String> stuns = List.of(urls.split(","));
        var stunConfig = VideoRoomResponse.IceServerConfig.builder()
                .urls(stuns)
                .build();

        if (turnUrl != null && !turnUrl.isBlank()) {
            String username = turnUsername;
            String credential = turnCredential;

            if (turnSecret != null && !turnSecret.isBlank()) {
                long expiry = Instant.now().getEpochSecond() + Math.max(60, turnTtlSeconds);
                username = expiry + ":" + (actor != null ? actor.id() : "user");
                credential = generateShortLivedTurnCredential(turnSecret.trim(), username);
            }

            if (username != null && !username.isBlank() && credential != null && !credential.isBlank()) {
                var turnConfig = VideoRoomResponse.IceServerConfig.builder()
                        .urls(List.of(turnUrl))
                        .username(username)
                        .credential(credential)
                        .build();
                return List.of(stunConfig, turnConfig);
            }
        }

        return List.of(stunConfig);
    }

    private String generateDeterministicRoomId(Long appointmentId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(("hms-room-secret-" + appointmentId).getBytes(StandardCharsets.UTF_8));
            return "room-" + appointmentId + "-" + HexFormat.of().formatHex(hash).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            return "room-" + appointmentId;
        }
    }
}

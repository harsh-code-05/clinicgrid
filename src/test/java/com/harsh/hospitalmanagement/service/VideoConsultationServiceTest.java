package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.dto.VideoRoomResponse;
import com.harsh.hospitalmanagement.entity.Appointment;
import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.Patient;
import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.enums.AppointmentStatus;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.enums.VerificationStatus;
import com.harsh.hospitalmanagement.exception.ForbiddenException;
import com.harsh.hospitalmanagement.exception.ResourceNotFoundException;
import com.harsh.hospitalmanagement.repository.AppointmentRepository;
import com.harsh.hospitalmanagement.security.LocalAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoConsultationServiceTest {

    @Mock
    private AppointmentRepository appointmentRepository;

    private VideoConsultationService service;

    private User patientUser;
    private User doctorUser;
    private User otherUser;
    private Patient patient;
    private Doctor doctor;
    private Appointment confirmedAppointment;

    @BeforeEach
    void setUp() {
        service = new VideoConsultationService(appointmentRepository);

        patientUser = new User();
        patientUser.setId(10L);
        patientUser.setEmail("patient@example.com");
        patientUser.setRole(Role.PATIENT);

        doctorUser = new User();
        doctorUser.setId(20L);
        doctorUser.setEmail("doctor@example.com");
        doctorUser.setRole(Role.DOCTOR);

        otherUser = new User();
        otherUser.setId(99L);
        otherUser.setEmail("stranger@example.com");
        otherUser.setRole(Role.PATIENT);

        patient = new Patient();
        patient.setId(100L);
        patient.setUser(patientUser);
        patient.setFirstName("John");
        patient.setLastName("Doe");

        doctor = new Doctor();
        doctor.setId(200L);
        doctor.setUser(doctorUser);
        doctor.setFirstName("Gregory");
        doctor.setLastName("House");
        doctor.setVerificationStatus(VerificationStatus.VERIFIED);

        confirmedAppointment = new Appointment();
        confirmedAppointment.setId(1L);
        confirmedAppointment.setPatient(patient);
        confirmedAppointment.setDoctor(doctor);
        confirmedAppointment.setStatus(AppointmentStatus.CONFIRMED);
    }

    @Test
    void getRoomDetails_shouldReturnCanJoinTrue_forPatientOnConfirmedVerifiedAppointment() {
        when(appointmentRepository.findById(1L)).thenReturn(Optional.of(confirmedAppointment));

        LocalAccount patientActor = new LocalAccount(10L, "patient@example.com");
        VideoRoomResponse response = service.getRoomDetails(1L, patientActor);

        assertThat(response.getAppointmentId()).isEqualTo(1L);
        assertThat(response.isCanJoin()).isTrue();
        assertThat(response.isDoctorVerified()).isTrue();
        assertThat(response.getUserRole()).isEqualTo("PATIENT");
        assertThat(response.getUserName()).isEqualTo("John Doe");
        assertThat(response.getPeerName()).isEqualTo("Dr. Gregory House");
        assertThat(response.getRoomId()).startsWith("room-1-");
        assertThat(response.getIceServers()).isNotEmpty();
    }

    @Test
    void getRoomDetails_shouldReturnCanJoinTrue_forDoctorOnConfirmedVerifiedAppointment() {
        when(appointmentRepository.findById(1L)).thenReturn(Optional.of(confirmedAppointment));

        LocalAccount doctorActor = new LocalAccount(20L, "doctor@example.com");
        VideoRoomResponse response = service.getRoomDetails(1L, doctorActor);

        assertThat(response.getAppointmentId()).isEqualTo(1L);
        assertThat(response.isCanJoin()).isTrue();
        assertThat(response.getUserRole()).isEqualTo("DOCTOR");
        assertThat(response.getUserName()).isEqualTo("Dr. Gregory House");
        assertThat(response.getPeerName()).isEqualTo("John Doe");
    }

    @Test
    void getRoomDetails_shouldThrowForbidden_forUnrelatedUser() {
        when(appointmentRepository.findById(1L)).thenReturn(Optional.of(confirmedAppointment));

        LocalAccount strangerActor = new LocalAccount(99L, "stranger@example.com");
        assertThatThrownBy(() -> service.getRoomDetails(1L, strangerActor))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("not authorized");
    }

    @Test
    void getRoomDetails_shouldBlockVideoRoom_whenDoctorIsUnverified() {
        doctor.setVerificationStatus(VerificationStatus.UNVERIFIED);
        when(appointmentRepository.findById(146L)).thenReturn(Optional.of(confirmedAppointment));

        LocalAccount patientActor = new LocalAccount(10L, "patient@example.com");
        VideoRoomResponse response = service.getRoomDetails(146L, patientActor);

        assertThat(response.isCanJoin()).isFalse();
        assertThat(response.isDoctorVerified()).isFalse();
        assertThat(response.getStatusMessage()).contains("Doctor verification is pending");
    }

    @Test
    void getRoomDetails_shouldBlockVideoRoom_whenAppointmentIsCancelled() {
        confirmedAppointment.setStatus(AppointmentStatus.CANCELLED);
        when(appointmentRepository.findById(1L)).thenReturn(Optional.of(confirmedAppointment));

        LocalAccount patientActor = new LocalAccount(10L, "patient@example.com");
        VideoRoomResponse response = service.getRoomDetails(1L, patientActor);

        assertThat(response.isCanJoin()).isFalse();
        assertThat(response.getStatusMessage()).contains("Appointment is CANCELLED");
    }

    @Test
    void getRoomDetails_shouldThrowNotFound_whenAppointmentDoesNotExist() {
        when(appointmentRepository.findById(999L)).thenReturn(Optional.empty());

        LocalAccount actor = new LocalAccount(10L, "patient@example.com");
        assertThatThrownBy(() -> service.getRoomDetails(999L, actor))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void validateSignalingAccess_shouldSucceed_forAuthorizedPatientAndVerifiedDoctor() {
        when(appointmentRepository.findById(1L)).thenReturn(Optional.of(confirmedAppointment));

        LocalAccount patientActor = new LocalAccount(10L, "patient@example.com");
        service.validateSignalingAccess(1L, patientActor);
    }

    @Test
    void validateSignalingAccess_shouldThrowForbidden_whenDoctorUnverified() {
        doctor.setVerificationStatus(VerificationStatus.UNVERIFIED);
        when(appointmentRepository.findById(1L)).thenReturn(Optional.of(confirmedAppointment));

        LocalAccount patientActor = new LocalAccount(10L, "patient@example.com");
        assertThatThrownBy(() -> service.validateSignalingAccess(1L, patientActor))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Doctor is not verified");
    }

    @Test
    void validateSignalingAccess_shouldThrowForbidden_whenUnrelatedUser() {
        when(appointmentRepository.findById(1L)).thenReturn(Optional.of(confirmedAppointment));

        LocalAccount strangerActor = new LocalAccount(99L, "stranger@example.com");
        assertThatThrownBy(() -> service.validateSignalingAccess(1L, strangerActor))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("not authorized");
    }

    @Test
    void getRoomDetails_shouldGenerateShortLivedTurnCredential_whenTurnSecretConfigured() {
        when(appointmentRepository.findById(1L)).thenReturn(Optional.of(confirmedAppointment));
        service.configureTurnForTest("turn:turn.example.com:3478", "super-secret-turn-key", 1800);

        LocalAccount patientActor = new LocalAccount(10L, "patient@example.com");
        VideoRoomResponse response = service.getRoomDetails(1L, patientActor);

        assertThat(response.getIceServers()).hasSize(2);
        VideoRoomResponse.IceServerConfig turnConfig = response.getIceServers().get(1);
        assertThat(turnConfig.getUrls()).containsExactly("turn:turn.example.com:3478");
        // Short-lived username format: <expiry>:<actorId>
        assertThat(turnConfig.getUsername()).matches("^\\d+:10$");
        // Credential is Base64 HMAC-SHA1
        assertThat(turnConfig.getCredential()).isNotBlank();

        // Verify HMAC matches expected calculation
        String expectedCred = VideoConsultationService.generateShortLivedTurnCredential("super-secret-turn-key", turnConfig.getUsername());
        assertThat(turnConfig.getCredential()).isEqualTo(expectedCred);
    }

    @Test
    void generateShortLivedTurnCredential_computesDeterministicHmacSha1() {
        String secret = "test-turn-secret-12345";
        String username = "1731934800:42";
        String credential1 = VideoConsultationService.generateShortLivedTurnCredential(secret, username);
        String credential2 = VideoConsultationService.generateShortLivedTurnCredential(secret, username);
        assertThat(credential1).isNotNull().isEqualTo(credential2);
    }
}

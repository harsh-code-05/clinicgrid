package com.harsh.hospitalmanagement.websocket;

import com.harsh.hospitalmanagement.entity.Appointment;
import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.Patient;
import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.enums.AppointmentStatus;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.enums.VerificationStatus;
import com.harsh.hospitalmanagement.exception.ForbiddenException;
import com.harsh.hospitalmanagement.repository.AppointmentRepository;
import com.harsh.hospitalmanagement.security.LocalAccount;
import com.harsh.hospitalmanagement.service.VideoConsultationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.SubscribableChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebSocketChannelInterceptorTest {

    @Mock
    private AppointmentRepository appointmentRepository;

    @Mock
    private SubscribableChannel clientOutboundChannel;

    private VideoConsultationService videoConsultationService;
    private ChannelInterceptor interceptor;
    private ExecutorSubscribableChannel inboundChannel;

    private Appointment confirmedAppointment;
    private Authentication doctorAuth;
    private Authentication patientAuth;
    private Authentication unrelatedAuth;

    @BeforeEach
    void setUp() {
        videoConsultationService = new VideoConsultationService(appointmentRepository);
        videoConsultationService.setWebrtcEnabled(true);

        WebSocketConfig config = new WebSocketConfig(
                videoConsultationService,
                clientOutboundChannel,
                "https://clinicgrid.tech",
                "https://clinicgrid.tech",
                false
        );
        interceptor = config.createInboundChannelInterceptor();

        inboundChannel = new ExecutorSubscribableChannel();
        inboundChannel.addInterceptor(interceptor);

        // Setup confirmed appointment #101 between Doctor 20L and Patient 10L
        User doctorUser = new User();
        doctorUser.setId(20L);
        doctorUser.setEmail("doctor@clinicgrid.tech");

        Doctor doctor = new Doctor();
        doctor.setId(200L);
        doctor.setUser(doctorUser);
        doctor.setFirstName("Sarah");
        doctor.setLastName("Connor");
        doctor.setVerificationStatus(VerificationStatus.VERIFIED);

        User patientUser = new User();
        patientUser.setId(10L);
        patientUser.setEmail("patient@clinicgrid.tech");

        Patient patient = new Patient();
        patient.setId(100L);
        patient.setUser(patientUser);
        patient.setFirstName("John");
        patient.setLastName("Doe");

        confirmedAppointment = new Appointment();
        confirmedAppointment.setId(101L);
        confirmedAppointment.setDoctor(doctor);
        confirmedAppointment.setPatient(patient);
        confirmedAppointment.setStatus(AppointmentStatus.CONFIRMED);

        // Doctor authentication
        LocalAccount doctorAccount = new LocalAccount(20L, "doctor@clinicgrid.tech");
        LocalAccount.PasswordPrincipal doctorPrincipal =
                new LocalAccount.PasswordPrincipal(doctorAccount, "hash", Role.DOCTOR);
        doctorAuth = new UsernamePasswordAuthenticationToken(doctorPrincipal, null, doctorPrincipal.getAuthorities());

        // Patient authentication
        LocalAccount patientAccount = new LocalAccount(10L, "patient@clinicgrid.tech");
        LocalAccount.PasswordPrincipal patientPrincipal =
                new LocalAccount.PasswordPrincipal(patientAccount, "hash", Role.PATIENT);
        patientAuth = new UsernamePasswordAuthenticationToken(patientPrincipal, null, patientPrincipal.getAuthorities());

        // Unrelated user authentication
        LocalAccount unrelatedAccount = new LocalAccount(99L, "unrelated@clinicgrid.tech");
        LocalAccount.PasswordPrincipal unrelatedPrincipal =
                new LocalAccount.PasswordPrincipal(unrelatedAccount, "hash", Role.PATIENT);
        unrelatedAuth = new UsernamePasswordAuthenticationToken(unrelatedPrincipal, null, unrelatedPrincipal.getAuthorities());
    }

    private Message<byte[]> createStompMessage(StompCommand command, String destination, Authentication auth) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId("sess-test-123");
        if (destination != null) {
            accessor.setDestination(destination);
        }
        if (auth != null) {
            accessor.setUser(auth);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    // A. Unauthenticated CONNECT is rejected
    @Test
    @DisplayName("A. Unauthenticated CONNECT is rejected and dispatches ERROR frame to client")
    void unauthenticatedConnect_isRejected() {
        Message<byte[]> msg = createStompMessage(StompCommand.CONNECT, null, null);

        assertThatThrownBy(() -> inboundChannel.send(msg))
                .isInstanceOf(MessageDeliveryException.class)
                .hasCauseInstanceOf(AccessDeniedException.class);

        ArgumentCaptor<Message<?>> captor = ArgumentCaptor.forClass(Message.class);
        verify(clientOutboundChannel).send(captor.capture());
        StompHeaderAccessor errorHeaders = StompHeaderAccessor.wrap(captor.getValue());
        assertThat(errorHeaders.getCommand()).isEqualTo(StompCommand.ERROR);
        assertThat(errorHeaders.getMessage()).contains("Unauthenticated WebSocket connection");
    }

    // B. Authenticated CONNECT is accepted
    @Test
    @DisplayName("B. Authenticated CONNECT is accepted")
    void authenticatedConnect_isAccepted() {
        Message<byte[]> msg = createStompMessage(StompCommand.CONNECT, null, patientAuth);
        boolean sent = inboundChannel.send(msg);
        assertThat(sent).isTrue();
    }

    // C. SUBSCRIBE is rejected when HMS_WEBRTC_ENABLED=false
    @Test
    @DisplayName("C. SUBSCRIBE is rejected when feature gate is disabled")
    void subscribe_isRejected_whenWebrtcDisabled() {
        videoConsultationService.setWebrtcEnabled(false);
        Message<byte[]> msg = createStompMessage(StompCommand.SUBSCRIBE, "/topic/video/room/101", patientAuth);

        assertThatThrownBy(() -> inboundChannel.send(msg))
                .isInstanceOf(MessageDeliveryException.class)
                .hasCauseInstanceOf(ForbiddenException.class);

        verify(clientOutboundChannel).send(any(Message.class));
    }

    // D. SEND is rejected when HMS_WEBRTC_ENABLED=false
    @Test
    @DisplayName("D. SEND is rejected when feature gate is disabled")
    void send_isRejected_whenWebrtcDisabled() {
        videoConsultationService.setWebrtcEnabled(false);
        Message<byte[]> msg = createStompMessage(StompCommand.SEND, "/app/video/signal/101", doctorAuth);

        assertThatThrownBy(() -> inboundChannel.send(msg))
                .isInstanceOf(MessageDeliveryException.class)
                .hasCauseInstanceOf(ForbiddenException.class);

        verify(clientOutboundChannel).send(any(Message.class));
    }

    // E. SUBSCRIBE to another appointment's room is rejected
    @Test
    @DisplayName("E. SUBSCRIBE to another appointment's room is rejected")
    void subscribe_toOtherAppointmentRoom_isRejected() {
        when(appointmentRepository.findById(101L)).thenReturn(Optional.of(confirmedAppointment));
        Message<byte[]> msg = createStompMessage(StompCommand.SUBSCRIBE, "/topic/video/room/101", unrelatedAuth);

        assertThatThrownBy(() -> inboundChannel.send(msg))
                .isInstanceOf(MessageDeliveryException.class)
                .hasCauseInstanceOf(ForbiddenException.class);

        verify(clientOutboundChannel).send(any(Message.class));
    }

    // F. SEND to another appointment's signaling destination is rejected
    @Test
    @DisplayName("F. SEND to another appointment's signaling destination is rejected")
    void send_toOtherAppointmentSignalingDestination_isRejected() {
        when(appointmentRepository.findById(101L)).thenReturn(Optional.of(confirmedAppointment));
        Message<byte[]> msg = createStompMessage(StompCommand.SEND, "/app/video/signal/101", unrelatedAuth);

        assertThatThrownBy(() -> inboundChannel.send(msg))
                .isInstanceOf(MessageDeliveryException.class)
                .hasCauseInstanceOf(ForbiddenException.class);

        verify(clientOutboundChannel).send(any(Message.class));
    }

    // G. Direct SEND to /topic/** and /queue/** is rejected
    @Test
    @DisplayName("G. Direct SEND to /topic/** or /queue/** is rejected with AccessDeniedException")
    void directSend_toTopicOrQueue_isRejected() {
        Message<byte[]> topicMsg = createStompMessage(StompCommand.SEND, "/topic/video/room/101", patientAuth);
        assertThatThrownBy(() -> inboundChannel.send(topicMsg))
                .isInstanceOf(MessageDeliveryException.class)
                .hasCauseInstanceOf(AccessDeniedException.class);

        Message<byte[]> queueMsg = createStompMessage(StompCommand.SEND, "/queue/notifications", patientAuth);
        assertThatThrownBy(() -> inboundChannel.send(queueMsg))
                .isInstanceOf(MessageDeliveryException.class)
                .hasCauseInstanceOf(AccessDeniedException.class);
    }

    // H. Invalid and non-positive appointment IDs are rejected
    @Test
    @DisplayName("H. Invalid and non-positive appointment IDs are rejected")
    void invalidAndNonPositiveAppointmentIds_areRejected() {
        // Zero ID
        Message<byte[]> zeroSub = createStompMessage(StompCommand.SUBSCRIBE, "/topic/video/room/0", patientAuth);
        assertThatThrownBy(() -> inboundChannel.send(zeroSub))
                .isInstanceOf(MessageDeliveryException.class)
                .hasCauseInstanceOf(AccessDeniedException.class);

        Message<byte[]> zeroSend = createStompMessage(StompCommand.SEND, "/app/video/signal/0", patientAuth);
        assertThatThrownBy(() -> inboundChannel.send(zeroSend))
                .isInstanceOf(MessageDeliveryException.class)
                .hasCauseInstanceOf(AccessDeniedException.class);

        // Malformed destinations
        Message<byte[]> badSub = createStompMessage(StompCommand.SUBSCRIBE, "/topic/video/unauthorized", patientAuth);
        assertThatThrownBy(() -> inboundChannel.send(badSub))
                .isInstanceOf(MessageDeliveryException.class)
                .hasCauseInstanceOf(AccessDeniedException.class);

        Message<byte[]> badSend = createStompMessage(StompCommand.SEND, "/app/video/inject/101", patientAuth);
        assertThatThrownBy(() -> inboundChannel.send(badSend))
                .isInstanceOf(MessageDeliveryException.class)
                .hasCauseInstanceOf(AccessDeniedException.class);
    }

    // I. When enabled, only the assigned patient and doctor can join the confirmed appointment room
    @Test
    @DisplayName("I. When enabled, assigned patient and doctor can SUBSCRIBE and SEND")
    void whenEnabled_assignedPatientAndDoctorCanJoin() {
        when(appointmentRepository.findById(101L)).thenReturn(Optional.of(confirmedAppointment));

        // Patient SUBSCRIBE & SEND
        Message<byte[]> patientSub = createStompMessage(StompCommand.SUBSCRIBE, "/topic/video/room/101", patientAuth);
        assertThat(inboundChannel.send(patientSub)).isTrue();

        Message<byte[]> patientSend = createStompMessage(StompCommand.SEND, "/app/video/signal/101", patientAuth);
        assertThat(inboundChannel.send(patientSend)).isTrue();

        // Doctor SUBSCRIBE & SEND
        Message<byte[]> doctorSub = createStompMessage(StompCommand.SUBSCRIBE, "/topic/video/room/101", doctorAuth);
        assertThat(inboundChannel.send(doctorSub)).isTrue();

        Message<byte[]> doctorSend = createStompMessage(StompCommand.SEND, "/app/video/signal/101", doctorAuth);
        assertThat(inboundChannel.send(doctorSend)).isTrue();
    }

    // J. Unrelated users and unverified doctors remain rejected
    @Test
    @DisplayName("J1. Unverified doctor is rejected from signaling")
    void unverifiedDoctor_isRejected() {
        confirmedAppointment.getDoctor().setVerificationStatus(VerificationStatus.UNDER_REVIEW);
        when(appointmentRepository.findById(101L)).thenReturn(Optional.of(confirmedAppointment));

        Message<byte[]> doctorSub = createStompMessage(StompCommand.SUBSCRIBE, "/topic/video/room/101", doctorAuth);
        assertThatThrownBy(() -> inboundChannel.send(doctorSub))
                .isInstanceOf(MessageDeliveryException.class)
                .hasCauseInstanceOf(ForbiddenException.class);

        Message<byte[]> doctorSend = createStompMessage(StompCommand.SEND, "/app/video/signal/101", doctorAuth);
        assertThatThrownBy(() -> inboundChannel.send(doctorSend))
                .isInstanceOf(MessageDeliveryException.class)
                .hasCauseInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("J2. Cancelled appointment room is closed for all participants")
    void cancelledAppointment_isClosed() {
        confirmedAppointment.setStatus(AppointmentStatus.CANCELLED);
        when(appointmentRepository.findById(101L)).thenReturn(Optional.of(confirmedAppointment));

        Message<byte[]> patientSub = createStompMessage(StompCommand.SUBSCRIBE, "/topic/video/room/101", patientAuth);
        assertThatThrownBy(() -> inboundChannel.send(patientSub))
                .isInstanceOf(MessageDeliveryException.class)
                .hasCauseInstanceOf(ForbiddenException.class);

        Message<byte[]> doctorSend = createStompMessage(StompCommand.SEND, "/app/video/signal/101", doctorAuth);
        assertThatThrownBy(() -> inboundChannel.send(doctorSend))
                .isInstanceOf(MessageDeliveryException.class)
                .hasCauseInstanceOf(ForbiddenException.class);
    }

    // K. STOMP alias command handling
    @Test
    @DisplayName("K1. Unauthenticated STOMP alias command is rejected")
    void unauthenticatedStompAlias_isRejected() {
        Message<byte[]> msg = createStompMessage(StompCommand.STOMP, null, null);

        assertThatThrownBy(() -> inboundChannel.send(msg))
                .isInstanceOf(MessageDeliveryException.class)
                .hasCauseInstanceOf(AccessDeniedException.class);

        ArgumentCaptor<Message<?>> captor = ArgumentCaptor.forClass(Message.class);
        verify(clientOutboundChannel).send(captor.capture());
        StompHeaderAccessor errorHeaders = StompHeaderAccessor.wrap(captor.getValue());
        assertThat(errorHeaders.getCommand()).isEqualTo(StompCommand.ERROR);
        assertThat(errorHeaders.getMessage()).contains("Unauthenticated WebSocket connection");
    }

    @Test
    @DisplayName("K2. Authenticated STOMP alias command is accepted")
    void authenticatedStompAlias_isAccepted() {
        Message<byte[]> msg = createStompMessage(StompCommand.STOMP, null, doctorAuth);
        boolean sent = inboundChannel.send(msg);
        assertThat(sent).isTrue();
    }

    // L. Unauthenticated SUBSCRIBE and SEND are rejected
    @Test
    @DisplayName("L1. Unauthenticated SUBSCRIBE is rejected")
    void unauthenticatedSubscribe_isRejected() {
        Message<byte[]> msg = createStompMessage(StompCommand.SUBSCRIBE, "/topic/video/room/101", null);

        assertThatThrownBy(() -> inboundChannel.send(msg))
                .isInstanceOf(MessageDeliveryException.class)
                .hasCauseInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("L2. Unauthenticated SEND is rejected")
    void unauthenticatedSend_isRejected() {
        Message<byte[]> msg = createStompMessage(StompCommand.SEND, "/app/video/signal/101", null);

        assertThatThrownBy(() -> inboundChannel.send(msg))
                .isInstanceOf(MessageDeliveryException.class)
                .hasCauseInstanceOf(AccessDeniedException.class);
    }

    // M. Prohibited STOMP commands are rejected
    @Test
    @DisplayName("M. Non-permitted STOMP client commands are rejected with AccessDeniedException")
    void forbiddenClientCommands_areRejected() {
        for (StompCommand cmd : new StompCommand[]{StompCommand.BEGIN, StompCommand.COMMIT, StompCommand.ABORT, StompCommand.MESSAGE}) {
            Message<byte[]> msg = createStompMessage(cmd, null, patientAuth);
            assertThatThrownBy(() -> inboundChannel.send(msg))
                    .isInstanceOf(MessageDeliveryException.class)
                    .hasCauseInstanceOf(AccessDeniedException.class);
        }
    }

    // N. IN_CLINIC appointment mode is rejected
    @Test
    @DisplayName("N. In-clinic consultation appointment is rejected from video room")
    void inClinicAppointment_isRejected() {
        ReflectionTestUtils.setField(confirmedAppointment, "consultationMode", com.harsh.hospitalmanagement.enums.ConsultationMode.IN_CLINIC);
        when(appointmentRepository.findById(101L)).thenReturn(Optional.of(confirmedAppointment));

        Message<byte[]> sub = createStompMessage(StompCommand.SUBSCRIBE, "/topic/video/room/101", patientAuth);
        assertThatThrownBy(() -> inboundChannel.send(sub))
                .isInstanceOf(MessageDeliveryException.class)
                .hasCauseInstanceOf(ForbiddenException.class);

        Message<byte[]> send = createStompMessage(StompCommand.SEND, "/app/video/signal/101", doctorAuth);
        assertThatThrownBy(() -> inboundChannel.send(send))
                .isInstanceOf(MessageDeliveryException.class)
                .hasCauseInstanceOf(ForbiddenException.class);
    }

    // O. Out-of-bounds appointment IDs (> 10,000,000,000L) are rejected
    @Test
    @DisplayName("O. Out-of-bounds appointment IDs are rejected")
    void outOfBoundsAppointmentIds_areRejected() {
        Message<byte[]> largeSub = createStompMessage(StompCommand.SUBSCRIBE, "/topic/video/room/10000000001", patientAuth);
        assertThatThrownBy(() -> inboundChannel.send(largeSub))
                .isInstanceOf(MessageDeliveryException.class)
                .hasCauseInstanceOf(AccessDeniedException.class);

        Message<byte[]> largeSend = createStompMessage(StompCommand.SEND, "/app/video/signal/10000000001", patientAuth);
        assertThatThrownBy(() -> inboundChannel.send(largeSend))
                .isInstanceOf(MessageDeliveryException.class)
                .hasCauseInstanceOf(AccessDeniedException.class);
    }
}

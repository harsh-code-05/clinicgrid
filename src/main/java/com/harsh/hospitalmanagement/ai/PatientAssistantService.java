package com.harsh.hospitalmanagement.ai;

import com.harsh.hospitalmanagement.entity.Appointment;
import com.harsh.hospitalmanagement.entity.Patient;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.exception.BadRequestException;
import com.harsh.hospitalmanagement.exception.ConflictException;
import com.harsh.hospitalmanagement.exception.ForbiddenException;
import com.harsh.hospitalmanagement.exception.ResourceNotFoundException;
import com.harsh.hospitalmanagement.repository.AppointmentRepository;
import com.harsh.hospitalmanagement.security.LocalAccount;
import com.harsh.hospitalmanagement.service.PatientService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Service
public class PatientAssistantService {

    private final PatientService patients;
    private final AiChatSessionRepository sessions;
    private final AiChatMessageRepository messages;
    private final FaqChatbot chatbot;
    private final AssistantRequestLimiter limiter;
    private final PatientAiEngine aiEngine;
    private final AppointmentRepository appointmentRepository;
    private final TransactionTemplate transactionTemplate;

    public PatientAssistantService(PatientService patients,
                                   AiChatSessionRepository sessions,
                                   AiChatMessageRepository messages,
                                   FaqChatbot chatbot,
                                   AssistantRequestLimiter limiter,
                                   PatientAiEngine aiEngine,
                                   AppointmentRepository appointmentRepository,
                                   PlatformTransactionManager transactionManager) {
        this.patients = patients;
        this.sessions = sessions;
        this.messages = messages;
        this.chatbot = chatbot;
        this.limiter = limiter;
        this.aiEngine = aiEngine;
        this.appointmentRepository = appointmentRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setIsolationLevel(TransactionTemplate.ISOLATION_READ_COMMITTED);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AssistantDtos.Session create(LocalAccount actor, AssistantDtos.CreateSession request) {
        Patient patient = patient(actor);
        String title = request.title() == null || request.title().isBlank() ? "New conversation" : request.title().strip();
        if (title.length() > 80) {
            throw new BadRequestException("Conversation titles must not exceed 80 characters");
        }
        return AssistantDtos.Session.of(sessions.save(new AiChatSession(patient, title)));
    }

    @Transactional(readOnly = true)
    public List<AssistantDtos.Session> list(LocalAccount actor, int page) {
        Patient patient = patient(actor);
        return sessions.findByPatient_IdOrderByUpdatedAtDescIdDesc(patient.getId(), PageRequest.of(page, 30))
                .stream().map(AssistantDtos.Session::of).toList();
    }

    @Transactional(readOnly = true)
    public List<AssistantDtos.Message> history(LocalAccount actor, Long sessionId, Long before) {
        owned(actor, sessionId, false);
        var recent = new ArrayList<>(messages.findRecent(sessionId, before, PageRequest.of(0, 50)));
        Collections.reverse(recent);
        return recent.stream().map(AssistantDtos.Message::of).toList();
    }

    /**
     * Sends a message in a conversation.
     * Remote AI inference is executed outside database transactions/locks to avoid holding connections.
     * Persistence is performed inside a short atomic transaction with row locking.
     */
    public AssistantDtos.Turn send(LocalAccount actor, Long sessionId, AssistantDtos.SendMessage request) {
        if (request.content() == null || request.content().isBlank() || request.content().length() > 2000
                || request.requestId() == null) {
            throw new BadRequestException("A message of 1 to 2000 characters and a request ID are required");
        }

        Patient patient = patient(actor);
        String content = request.content().strip();
        boolean optInAppointments = request.optInAppointments();

        // 1. Validate conversation exists and belongs to authenticated patient before checking idempotency or rate-limiting
        owned(actor, sessionId, false);

        // 2. Idempotency fast-path check (non-locking read)
        var existingTurn = checkExistingTurn(sessionId, request.requestId(), content, optInAppointments);
        if (existingTurn != null) {
            return existingTurn;
        }

        // 3. Rate limit acquisition
        limiter.acquire(actor.id());

        // 3. Deterministic Emergency check (deterministic safety gate, zero external calls)
        if (FaqChatbot.isEmergency(content)) {
            return commitTurn(sessionId, patient.getId(), request.requestId(), content,
                    FaqChatbot.EMERGENCY_RESPONSE, optInAppointments, AiResponseSource.EMERGENCY_ESCALATION);
        }

        // 4. Resolve opt-in appointment context if consented by patient
        String appointmentContext = null;
        if (optInAppointments) {
            appointmentContext = buildSanitizedAppointmentSummary(actor.id());
        }

        // 5. Remote AI inference (EXECUTED OUTSIDE DATABASE TRANSACTION AND ROW LOCK)
        AiResponse aiResponse = aiEngine.generateResponse(content, appointmentContext);

        // 6. Persist turn under PESSIMISTIC_WRITE lock
        return commitTurn(sessionId, patient.getId(), request.requestId(), content,
                aiResponse.content(), optInAppointments, aiResponse.source());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void delete(LocalAccount actor, Long sessionId) {
        AiChatSession session = owned(actor, sessionId, true);
        messages.deleteForSession(sessionId);
        sessions.delete(session);
    }

    private AssistantDtos.Turn commitTurn(Long sessionId, Long patientId, UUID requestId, String userContent,
                                          String assistantContent, boolean includeAppointments, AiResponseSource source) {
        return transactionTemplate.execute(status -> {
            // Acquire row lock on session (NOWAIT)
            AiChatSession session = sessions.findOwnedForUpdate(sessionId, patientId)
                    .orElseThrow(() -> new ResourceNotFoundException("Conversation not found"));

            // Concurrency recheck inside lock: did another request complete this requestId?
            var saved = messages.findBySession_IdAndRequestIdOrderByIdAsc(sessionId, requestId);
            if (!saved.isEmpty()) {
                AiChatMessage original = saved.getFirst();
                if (saved.size() != 2 || original.getRole() != AiChatMessage.Role.USER
                        || saved.getLast().getRole() != AiChatMessage.Role.ASSISTANT) {
                    throw new ConflictException("The saved message is incomplete. Please contact hospital support");
                }
                if (!original.getContent().equals(userContent)) {
                    throw new ConflictException("A request ID cannot be reused for a different message");
                }
                if (original.isIncludeAppointments() != includeAppointments) {
                    throw new ConflictException("A request ID cannot be reused with different consent parameters");
                }
                return new AssistantDtos.Turn(saved.stream().map(AssistantDtos.Message::of).toList());
            }

            var userMsg = messages.save(new AiChatMessage(session, AiChatMessage.Role.USER, userContent,
                    requestId, includeAppointments, AiResponseSource.USER));
            var assistantMsg = messages.save(new AiChatMessage(session, AiChatMessage.Role.ASSISTANT, assistantContent,
                    requestId, includeAppointments, source));
            session.touch();

            return new AssistantDtos.Turn(List.of(AssistantDtos.Message.of(userMsg), AssistantDtos.Message.of(assistantMsg)));
        });
    }

    private AssistantDtos.Turn checkExistingTurn(Long sessionId, UUID requestId, String userContent, boolean optInAppointments) {
        var saved = messages.findBySession_IdAndRequestIdOrderByIdAsc(sessionId, requestId);
        if (!saved.isEmpty()) {
            AiChatMessage original = saved.getFirst();
            if (saved.size() != 2 || original.getRole() != AiChatMessage.Role.USER
                    || saved.getLast().getRole() != AiChatMessage.Role.ASSISTANT) {
                throw new ConflictException("The saved message is incomplete. Please contact hospital support");
            }
            if (!original.getContent().equals(userContent)) {
                throw new ConflictException("A request ID cannot be reused for a different message");
            }
            if (original.isIncludeAppointments() != optInAppointments) {
                throw new ConflictException("A request ID cannot be reused with different consent parameters");
            }
            return new AssistantDtos.Turn(saved.stream().map(AssistantDtos.Message::of).toList());
        }
        return null;
    }

    private String buildSanitizedAppointmentSummary(Long userId) {
        try {
            List<Appointment> appts = appointmentRepository.findByPatient_User_IdOrderByCreatedAtDesc(userId);
            if (appts == null || appts.isEmpty()) {
                return "Patient has no recorded appointments in the system.";
            }
            List<String> list = new ArrayList<>();
            for (Appointment a : appts) {
                if (list.size() >= 3) break;
                String spec = a.getDoctor() != null && a.getDoctor().getSpecialization() != null
                        ? a.getDoctor().getSpecialization().getName() : "General Consultation";
                String date = a.getSlot() != null && a.getSlot().getDate() != null
                        ? a.getSlot().getDate().toString() : "TBD";
                String time = a.getSlot() != null && a.getSlot().getStartTime() != null
                        ? a.getSlot().getStartTime().toString() : "TBD";
                list.add(String.format("Department: %s, Date: %s, Time: %s, Status: %s",
                        spec, date, time, a.getStatus()));
            }
            return String.join("; ", list);
        } catch (Exception ex) {
            return "Appointment information temporarily unavailable.";
        }
    }

    private Patient patient(LocalAccount actor) {
        Patient patient = patients.getPatientByUserId(actor.id());
        if (patient.getUser().getRole() != Role.PATIENT) {
            throw new ForbiddenException("The assistant is available to patients only");
        }
        return patient;
    }

    private AiChatSession owned(LocalAccount actor, Long id, boolean lock) {
        Long patientId = patient(actor).getId();
        return (lock ? sessions.findOwnedForUpdate(id, patientId) : sessions.findByIdAndPatient_Id(id, patientId))
                .orElseThrow(() -> new ResourceNotFoundException("Conversation not found"));
    }
}

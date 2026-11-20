package com.harsh.hospitalmanagement.ai;

import com.harsh.hospitalmanagement.config.safety.PostgresTestTarget;
import com.harsh.hospitalmanagement.config.safety.RequiresDisposablePostgres;
import com.harsh.hospitalmanagement.entity.Patient;
import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.exception.ConflictException;
import com.harsh.hospitalmanagement.exception.ForbiddenException;
import com.harsh.hospitalmanagement.exception.ResourceNotFoundException;
import com.harsh.hospitalmanagement.repository.PatientRepository;
import com.harsh.hospitalmanagement.repository.UserRepository;
import com.harsh.hospitalmanagement.security.LocalAccount;
import com.harsh.hospitalmanagement.service.PatientService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@RequiresDisposablePostgres
@DataJpaTest(properties = {"spring.sql.init.mode=never", "spring.jpa.hibernate.ddl-auto=validate"}, showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestTarget.TestDataSourceConfiguration.class,
        PatientAssistantService.class,
        PatientService.class,
        FaqChatbot.class,
        AssistantRequestLimiter.class,
        PatientAiEngine.class,
        CloudflareAiClient.class,
        CloudflareAiProperties.class,
        PatientAssistantPostgresTest.TestConfig.class
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PatientAssistantPostgresTest {

    @TestConfiguration
    static class TestConfig {
        @Bean
        public RestClient.Builder restClientBuilder() {
            return RestClient.builder();
        }

        @Bean
        public ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Autowired private PatientAssistantService service;
    @Autowired private AiChatSessionRepository sessionRepository;
    @Autowired private AiChatMessageRepository messageRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @MockitoSpyBean private FaqChatbot chatbot;

    private User user1;
    private User user2;
    private User doctorUser;
    private Patient patient1;
    private Patient patient2;
    private LocalAccount account1;
    private LocalAccount account2;
    private LocalAccount doctorAccount;

    private final List<Long> createdSessionIds = new ArrayList<>();
    private final List<Long> createdPatientIds = new ArrayList<>();
    private final List<Long> createdUserIds = new ArrayList<>();

    @DynamicPropertySource
    static void configureDisposablePostgres(DynamicPropertyRegistry registry) {
        PostgresTestTarget.registerProperties(registry);
    }

    @BeforeEach
    void setUp() {
        assertThat(AopUtils.isAopProxy(service)).isTrue();

        String suffix = UUID.randomUUID().toString();
        user1 = createUser("patient1-ai-" + suffix + "@test.invalid", Role.PATIENT);
        user2 = createUser("patient2-ai-" + suffix + "@test.invalid", Role.PATIENT);
        doctorUser = createUser("doctor-ai-" + suffix + "@test.invalid", Role.DOCTOR);

        patient1 = createPatient(user1, "John", "Doe");
        patient2 = createPatient(user2, "Jane", "Smith");

        account1 = new LocalAccount(user1.getId(), user1.getEmail());
        account2 = new LocalAccount(user2.getId(), user2.getEmail());
        doctorAccount = new LocalAccount(doctorUser.getId(), doctorUser.getEmail());
    }

    @AfterEach
    void tearDown() {
        for (Long sessionId : createdSessionIds) {
            jdbc.update("DELETE FROM ai_chat_messages WHERE session_id = ?", sessionId);
            jdbc.update("DELETE FROM ai_chat_sessions WHERE id = ?", sessionId);
        }
        createdSessionIds.clear();

        for (Long patientId : createdPatientIds) {
            jdbc.update("DELETE FROM ai_chat_messages WHERE session_id IN (SELECT id FROM ai_chat_sessions WHERE patient_id = ?)", patientId);
            jdbc.update("DELETE FROM ai_chat_sessions WHERE patient_id = ?", patientId);
            jdbc.update("DELETE FROM patients WHERE id = ?", patientId);
        }
        createdPatientIds.clear();

        for (Long userId : createdUserIds) {
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
        }
        createdUserIds.clear();
    }

    private User createUser(String email, Role role) {
        User user = new User();
        user.setEmail(email);
        user.setRole(role);
        user.setPassword("secure-test-pwd");
        User saved = userRepository.save(user);
        createdUserIds.add(saved.getId());
        return saved;
    }

    private Patient createPatient(User user, String firstName, String lastName) {
        Patient patient = new Patient();
        patient.setUser(user);
        patient.setFirstName(firstName);
        patient.setLastName(lastName);
        patient.setPhone("9876543210");
        patient.setDateOfBirth(LocalDate.of(1990, 1, 1));
        Patient saved = patientRepository.save(patient);
        createdPatientIds.add(saved.getId());
        return saved;
    }

    private AssistantDtos.Session trackSession(AssistantDtos.Session session) {
        createdSessionIds.add(session.id());
        return session;
    }

    @Test
    void test01_sessionPersistenceAndOrdering() {
        AssistantDtos.Session session1 = trackSession(service.create(account1, new AssistantDtos.CreateSession("Billing Query")));
        AssistantDtos.Session session2 = trackSession(service.create(account1, new AssistantDtos.CreateSession("Appointment Query")));

        // Verify persisted in PostgreSQL table ai_chat_sessions
        var rowCount = jdbc.queryForObject(
                "SELECT count(*) FROM ai_chat_sessions WHERE id IN (?, ?) AND patient_id = ?",
                Integer.class, session1.id(), session2.id(), patient1.getId());
        assertThat(rowCount).isEqualTo(2);

        // Verify title check constraint and default localtimestamp
        String title1 = jdbc.queryForObject("SELECT title FROM ai_chat_sessions WHERE id = ?", String.class, session1.id());
        assertThat(title1).isEqualTo("Billing Query");

        // Verify list returns ordered by updatedAt DESC, id DESC
        List<AssistantDtos.Session> list = service.list(account1, 0);
        assertThat(list).extracting(AssistantDtos.Session::id)
                .containsExactly(session2.id(), session1.id());

        // Update session 1 by sending a message, it should become most recently updated
        UUID reqId = UUID.randomUUID();
        service.send(account1, session1.id(), new AssistantDtos.SendMessage(reqId, "How do I book?"));

        List<AssistantDtos.Session> updatedList = service.list(account1, 0);
        assertThat(updatedList.getFirst().id()).isEqualTo(session1.id());
    }

    @Test
    void test02_messagePersistenceAndChronologicalOrdering() {
        AssistantDtos.Session session = trackSession(service.create(account1, new AssistantDtos.CreateSession("Help Chat")));
        UUID reqId = UUID.randomUUID();
        String question = "How do I book an appointment?";

        AssistantDtos.Turn turn = service.send(account1, session.id(), new AssistantDtos.SendMessage(reqId, question));
        assertThat(turn.messages()).hasSize(2);

        AssistantDtos.Message userMsg = turn.messages().get(0);
        AssistantDtos.Message assistantMsg = turn.messages().get(1);

        assertThat(userMsg.role()).isEqualTo(AiChatMessage.Role.USER);
        assertThat(userMsg.content()).isEqualTo(question);
        assertThat(userMsg.requestId()).isEqualTo(reqId);

        assertThat(assistantMsg.role()).isEqualTo(AiChatMessage.Role.ASSISTANT);
        assertThat(assistantMsg.content()).isNotBlank();
        assertThat(assistantMsg.requestId()).isEqualTo(reqId);

        // Verify rows in PostgreSQL table ai_chat_messages
        Integer msgCount = jdbc.queryForObject(
                "SELECT count(*) FROM ai_chat_messages WHERE session_id = ? AND request_id = ?",
                Integer.class, session.id(), reqId);
        assertThat(msgCount).isEqualTo(2);

        Boolean includeAppts = jdbc.queryForObject(
                "SELECT include_appointments FROM ai_chat_messages WHERE id = ?",
                Boolean.class, userMsg.id());
        assertThat(includeAppts).isFalse();

        // Verify history returns messages in ascending chronological order
        List<AssistantDtos.Message> history = service.history(account1, session.id(), Long.MAX_VALUE);
        assertThat(history).hasSize(2);
        assertThat(history.get(0).id()).isEqualTo(userMsg.id());
        assertThat(history.get(1).id()).isEqualTo(assistantMsg.id());

        // Verify history pagination using before ID
        List<AssistantDtos.Message> paginated = service.history(account1, session.id(), assistantMsg.id());
        assertThat(paginated).hasSize(1);
        assertThat(paginated.getFirst().id()).isEqualTo(userMsg.id());
    }

    @Test
    void test03_patientOwnershipAndCrossPatientIsolation() {
        AssistantDtos.Session s1 = trackSession(service.create(account1, new AssistantDtos.CreateSession("Patient 1 Session")));
        AssistantDtos.Session s2 = trackSession(service.create(account2, new AssistantDtos.CreateSession("Patient 2 Session")));

        // Patient 1 sees only s1, Patient 2 sees only s2
        List<AssistantDtos.Session> list1 = service.list(account1, 0);
        List<AssistantDtos.Session> list2 = service.list(account2, 0);

        assertThat(list1).extracting(AssistantDtos.Session::id).containsExactly(s1.id());
        assertThat(list2).extracting(AssistantDtos.Session::id).containsExactly(s2.id());

        // Cross-patient access attempts fail closed with ResourceNotFoundException
        assertThatThrownBy(() -> service.history(account2, s1.id(), Long.MAX_VALUE))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThatThrownBy(() -> service.send(account2, s1.id(), new AssistantDtos.SendMessage(UUID.randomUUID(), "Unauthorized")))
                .isInstanceOf(ResourceNotFoundException.class);

        // Replay attack: Patient 1 sends a turn with a specific requestId
        UUID p1ReqId = UUID.randomUUID();
        service.send(account1, s1.id(), new AssistantDtos.SendMessage(p1ReqId, "Patient 1 private inquiry"));

        // Patient 2 attempts to call send on s1 using Patient 1's exact requestId and content
        // Must fail closed with ResourceNotFoundException before idempotency check can return cached turn
        assertThatThrownBy(() -> service.send(account2, s1.id(), new AssistantDtos.SendMessage(p1ReqId, "Patient 1 private inquiry")))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThatThrownBy(() -> service.delete(account2, s1.id()))
                .isInstanceOf(ResourceNotFoundException.class);

        // Deleting s1 removes s1 without touching s2
        service.delete(account1, s1.id());
        assertThat(service.list(account1, 0)).isEmpty();
        assertThat(service.list(account2, 0)).extracting(AssistantDtos.Session::id).containsExactly(s2.id());

        Integer s1Count = jdbc.queryForObject("SELECT count(*) FROM ai_chat_sessions WHERE id = ?", Integer.class, s1.id());
        assertThat(s1Count).isEqualTo(0);

        Integer s2Count = jdbc.queryForObject("SELECT count(*) FROM ai_chat_sessions WHERE id = ?", Integer.class, s2.id());
        assertThat(s2Count).isEqualTo(1);
    }

    @Test
    void test04_concurrencyAndPessimisticLocking() throws Exception {
        AssistantDtos.Session session = trackSession(service.create(account1, new AssistantDtos.CreateSession("Locking Test")));

        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            // Thread 1: Acquire pessimistic write lock in a transaction
            Future<?> lockHolder = executor.submit(() -> {
                new TransactionTemplate(transactionManager).execute(status -> {
                    // findOwnedForUpdate uses jakarta.persistence.lock.timeout = 0 (PostgreSQL NOWAIT)
                    var locked = sessionRepository.findOwnedForUpdate(session.id(), patient1.getId());
                    assertThat(locked).isPresent();
                    lockAcquired.countDown();
                    try {
                        if (!releaseLock.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Timeout waiting for releaseLock latch");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                });
            });

            assertThat(lockAcquired.await(5, TimeUnit.SECONDS)).isTrue();

            // Thread 2: Concurrently attempt to acquire pessimistic lock on the same session
            Future<?> concurrentAttempt = executor.submit(() -> {
                new TransactionTemplate(transactionManager).execute(status -> {
                    return sessionRepository.findOwnedForUpdate(session.id(), patient1.getId());
                });
            });

            // PostgreSQL NOWAIT throws PessimisticLockingFailureException
            assertThatThrownBy(concurrentAttempt::get)
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(PessimisticLockingFailureException.class);

            releaseLock.countDown();
            lockHolder.get(5, TimeUnit.SECONDS);

            // Once lock is released, subsequent send succeeds without contention
            AssistantDtos.Turn turn = service.send(account1, session.id(),
                    new AssistantDtos.SendMessage(UUID.randomUUID(), "Post-lock test"));
            assertThat(turn.messages()).hasSize(2);
        } finally {
            releaseLock.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void test05_idempotentRetryBehavior() {
        AssistantDtos.Session session = trackSession(service.create(account1, new AssistantDtos.CreateSession("Retry Test")));
        UUID reqId = UUID.randomUUID();
        AssistantDtos.SendMessage request = new AssistantDtos.SendMessage(reqId, "How do I cancel an appointment?");

        // Initial send
        AssistantDtos.Turn firstTurn = service.send(account1, session.id(), request);
        assertThat(firstTurn.messages()).hasSize(2);

        // Verify exactly 2 rows in PostgreSQL
        Integer countBefore = jdbc.queryForObject(
                "SELECT count(*) FROM ai_chat_messages WHERE session_id = ?",
                Integer.class, session.id());
        assertThat(countBefore).isEqualTo(2);

        // Idempotent retry with the exact same request
        AssistantDtos.Turn retryTurn = service.send(account1, session.id(), request);
        assertThat(retryTurn).isEqualTo(firstTurn);

        // Verify no duplicate rows inserted in PostgreSQL
        Integer countAfter = jdbc.queryForObject(
                "SELECT count(*) FROM ai_chat_messages WHERE session_id = ?",
                Integer.class, session.id());
        assertThat(countAfter).isEqualTo(2);

        // Verify chatbot was invoked only once
        verify(chatbot, times(1)).answer(request.content());
    }

    @Test
    void test06_reusedRequestIdWithDifferentContentRejected() {
        AssistantDtos.Session session = trackSession(service.create(account1, new AssistantDtos.CreateSession("Conflict Test")));
        UUID reqId = UUID.randomUUID();

        service.send(account1, session.id(), new AssistantDtos.SendMessage(reqId, "Original question"));

        // Attempting to send different text with the same requestId throws ConflictException
        assertThatThrownBy(() -> service.send(account1, session.id(), new AssistantDtos.SendMessage(reqId, "Different question")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("A request ID cannot be reused for a different message");

        // Attempting to send the same text with different consent parameters (optInAppointments) throws ConflictException
        assertThatThrownBy(() -> service.send(account1, session.id(), new AssistantDtos.SendMessage(reqId, "Original question", true)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("A request ID cannot be reused with different consent parameters");

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM ai_chat_messages WHERE session_id = ?",
                Integer.class, session.id());
        assertThat(count).isEqualTo(2);
    }

    @Test
    void test07_databaseConstraintDuplicatePrevention() {
        AssistantDtos.Session session = trackSession(service.create(account1, new AssistantDtos.CreateSession("DB Constraint Test")));
        UUID reqId = UUID.randomUUID();

        service.send(account1, session.id(), new AssistantDtos.SendMessage(reqId, "Hello world"));

        // PostgreSQL table ai_chat_messages has unique constraint uk_ai_chat_message_request_role
        // on (session_id, request_id, role).
        // Attempting to insert a duplicate USER row must fail with DataIntegrityViolationException.
        assertThatThrownBy(() -> {
            new TransactionTemplate(transactionManager).execute(status -> {
                jdbc.update(
                        "INSERT INTO ai_chat_messages (session_id, role, content, request_id, include_appointments, created_at) " +
                                "VALUES (?, 'USER', 'Duplicate content', ?, false, CURRENT_TIMESTAMP)",
                        session.id(), reqId);
                return null;
            });
        }).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_ai_chat_message_request_role");
    }

    @Test
    void test08_rollbackAndFailureHandling() {
        AssistantDtos.Session session = trackSession(service.create(account1, new AssistantDtos.CreateSession("Rollback Test")));
        UUID reqId = UUID.randomUUID();
        String failingQuestion = "Simulate chatbot error";

        LocalDateTime originalUpdatedAt = jdbc.queryForObject(
                "SELECT updated_at FROM ai_chat_sessions WHERE id = ?",
                LocalDateTime.class, session.id());

        // Part A: Failure before message persistence (e.g. chatbot runtime exception)
        doThrow(new IllegalStateException("Synthetic chatbot runtime failure"))
                .when(chatbot).answer(failingQuestion);

        assertThatThrownBy(() -> service.send(account1, session.id(), new AssistantDtos.SendMessage(reqId, failingQuestion)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Synthetic chatbot runtime failure");

        // Verify transaction rolled back: no user message was left in PostgreSQL
        Integer msgCount = jdbc.queryForObject(
                "SELECT count(*) FROM ai_chat_messages WHERE session_id = ?",
                Integer.class, session.id());
        assertThat(msgCount).isEqualTo(0);

        LocalDateTime postFailUpdatedAt = jdbc.queryForObject(
                "SELECT updated_at FROM ai_chat_sessions WHERE id = ?",
                LocalDateTime.class, session.id());
        assertThat(postFailUpdatedAt).isEqualTo(originalUpdatedAt);

        // Part B: Failure AFTER an executed write (USER message saved, ASSISTANT message fails PostgreSQL column constraint)
        UUID reqId2 = UUID.randomUUID();
        doReturn("x".repeat(8001)).when(chatbot).answer(anyString());

        assertThatThrownBy(() -> service.send(account1, session.id(), new AssistantDtos.SendMessage(reqId2, "Post-write failure question")))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Verify in an independent transaction via JDBC that the executed USER write was completely rolled back from PostgreSQL
        Integer msgCountPostWriteFail = jdbc.queryForObject(
                "SELECT count(*) FROM ai_chat_messages WHERE session_id = ?",
                Integer.class, session.id());
        assertThat(msgCountPostWriteFail).isEqualTo(0);

        // Reset spy and verify session can now be used normally
        doCallRealMethod().when(chatbot).answer(failingQuestion);
        AssistantDtos.Turn turn = service.send(account1, session.id(), new AssistantDtos.SendMessage(reqId, failingQuestion));
        assertThat(turn.messages()).hasSize(2);

        Integer finalMsgCount = jdbc.queryForObject(
                "SELECT count(*) FROM ai_chat_messages WHERE session_id = ?",
                Integer.class, session.id());
        assertThat(finalMsgCount).isEqualTo(2);
    }

    @Test
    void test09_roleAuthorizationEnforcement() {
        // Case A: User without a patient profile (e.g. doctor user) is rejected
        assertThatThrownBy(() -> service.create(doctorAccount, new AssistantDtos.CreateSession("Doctor chat")))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Patient profile not found for user");

        assertThatThrownBy(() -> service.list(doctorAccount, 0))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Patient profile not found for user");

        // Case B: User with patient profile whose database role was changed to non-PATIENT
        User demotedUser = createUser("demoted-ai-" + UUID.randomUUID() + "@test.invalid", Role.PATIENT);
        Patient demotedPatient = createPatient(demotedUser, "Demoted", "User");
        LocalAccount demotedAccount = new LocalAccount(demotedUser.getId(), demotedUser.getEmail());

        AssistantDtos.Session session = trackSession(service.create(demotedAccount, new AssistantDtos.CreateSession("Patient Chat")));

        // Update role to DOCTOR in database
        jdbc.update("UPDATE users SET role = 'DOCTOR' WHERE id = ?", demotedUser.getId());

        assertThatThrownBy(() -> service.create(demotedAccount, new AssistantDtos.CreateSession("Doctor chat")))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("The assistant is available to patients only");

        assertThatThrownBy(() -> service.list(demotedAccount, 0))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("The assistant is available to patients only");

        assertThatThrownBy(() -> service.history(demotedAccount, session.id(), Long.MAX_VALUE))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("The assistant is available to patients only");

        assertThatThrownBy(() -> service.send(demotedAccount, session.id(), new AssistantDtos.SendMessage(UUID.randomUUID(), "Doctor msg")))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("The assistant is available to patients only");

        assertThatThrownBy(() -> service.delete(demotedAccount, session.id()))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("The assistant is available to patients only");
    }

    @Test
    void test10_cascadeDeletionAtDatabaseLevel() {
        // Stage 1: Verify PostgreSQL engine-level foreign key cascade deletion directly
        AssistantDtos.Session session1 = trackSession(service.create(account1, new AssistantDtos.CreateSession("DB Cascade Test")));
        service.send(account1, session1.id(), new AssistantDtos.SendMessage(UUID.randomUUID(), "Message 1"));
        service.send(account1, session1.id(), new AssistantDtos.SendMessage(UUID.randomUUID(), "Message 2"));

        Integer msgCountBefore = jdbc.queryForObject(
                "SELECT count(*) FROM ai_chat_messages WHERE session_id = ?",
                Integer.class, session1.id());
        assertThat(msgCountBefore).isEqualTo(4);

        // Delete parent session directly via JDBC while child messages exist.
        // This exercises ON DELETE CASCADE defined in the database schema (ai_chat_messages_session_id_fkey).
        jdbc.update("DELETE FROM ai_chat_sessions WHERE id = ?", session1.id());

        // Verify session deleted
        Integer sessionCount = jdbc.queryForObject(
                "SELECT count(*) FROM ai_chat_sessions WHERE id = ?",
                Integer.class, session1.id());
        assertThat(sessionCount).isEqualTo(0);

        // Verify all 4 messages were cascadingly deleted by PostgreSQL engine foreign key constraint
        Integer msgCountAfterCascade = jdbc.queryForObject(
                "SELECT count(*) FROM ai_chat_messages WHERE session_id = ?",
                Integer.class, session1.id());
        assertThat(msgCountAfterCascade).isEqualTo(0);

        // Stage 2: Also verify application service-level deletion flow
        AssistantDtos.Session session2 = trackSession(service.create(account1, new AssistantDtos.CreateSession("Service Delete Test")));
        service.send(account1, session2.id(), new AssistantDtos.SendMessage(UUID.randomUUID(), "Message A"));
        service.delete(account1, session2.id());

        assertThat(sessionRepository.existsById(session2.id())).isFalse();
        Integer session2MsgCount = jdbc.queryForObject(
                "SELECT count(*) FROM ai_chat_messages WHERE session_id = ?",
                Integer.class, session2.id());
        assertThat(session2MsgCount).isEqualTo(0);
    }

    @Test
    void test11_emergencyEscalationSourceWithoutRemoteAi() {
        AssistantDtos.Session session = trackSession(service.create(account1, new AssistantDtos.CreateSession("Emergency Test")));
        UUID reqId = UUID.randomUUID();
        String emergencyPrompt = "I am experiencing severe chest pain and cannot breathe, please help!";

        AssistantDtos.Turn turn = service.send(account1, session.id(), new AssistantDtos.SendMessage(reqId, emergencyPrompt));
        assertThat(turn.messages()).hasSize(2);

        AssistantDtos.Message userMsg = turn.messages().get(0);
        AssistantDtos.Message assistantMsg = turn.messages().get(1);

        assertThat(userMsg.source()).isEqualTo("USER");
        assertThat(assistantMsg.source()).isEqualTo("EMERGENCY_ESCALATION");
        assertThat(assistantMsg.content()).isEqualTo(FaqChatbot.EMERGENCY_RESPONSE);

        // Verify persisted in PostgreSQL table with correct source
        String userSource = jdbc.queryForObject(
                "SELECT source FROM ai_chat_messages WHERE id = ?", String.class, userMsg.id());
        String assistantSource = jdbc.queryForObject(
                "SELECT source FROM ai_chat_messages WHERE id = ?", String.class, assistantMsg.id());

        assertThat(userSource).isEqualTo("USER");
        assertThat(assistantSource).isEqualTo("EMERGENCY_ESCALATION");
    }

    @Test
    void test12_messageSourceColumnConstraintAndVerification() {
        AssistantDtos.Session session = trackSession(service.create(account1, new AssistantDtos.CreateSession("Constraint Test")));

        // Valid sources: CLOUDFLARE_LLM, FAQ_FALLBACK, EMERGENCY_ESCALATION, USER
        for (String validSource : List.of("CLOUDFLARE_LLM", "FAQ_FALLBACK", "EMERGENCY_ESCALATION", "USER")) {
            jdbc.update(
                    "INSERT INTO ai_chat_messages (session_id, role, content, request_id, include_appointments, source, created_at) " +
                            "VALUES (?, 'ASSISTANT', ?, ?, false, ?, CURRENT_TIMESTAMP)",
                    session.id(), "Test content for " + validSource, UUID.randomUUID(), validSource);
        }

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM ai_chat_messages WHERE session_id = ?", Integer.class, session.id());
        assertThat(count).isEqualTo(4);

        // Invalid source violates check constraint ck_ai_chat_messages_source
        assertThatThrownBy(() -> {
            new TransactionTemplate(transactionManager).execute(status -> {
                jdbc.update(
                        "INSERT INTO ai_chat_messages (session_id, role, content, request_id, include_appointments, source, created_at) " +
                                "VALUES (?, 'ASSISTANT', 'Invalid source test', ?, false, 'INVALID_SOURCE', CURRENT_TIMESTAMP)",
                        session.id(), UUID.randomUUID());
                return null;
            });
        }).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_ai_chat_messages_source");
    }

    @Test
    void test13_idempotentRetryPreservesSource() {
        AssistantDtos.Session session = trackSession(service.create(account1, new AssistantDtos.CreateSession("Retry Source Test")));
        UUID reqId = UUID.randomUUID();
        AssistantDtos.SendMessage request = new AssistantDtos.SendMessage(reqId, "What are the visiting hours?");

        AssistantDtos.Turn firstTurn = service.send(account1, session.id(), request);
        assertThat(firstTurn.messages().get(1).source()).isEqualTo("FAQ_FALLBACK");

        // Retry same request
        AssistantDtos.Turn retryTurn = service.send(account1, session.id(), request);
        assertThat(retryTurn.messages().get(1).source()).isEqualTo("FAQ_FALLBACK");
        assertThat(retryTurn.messages().get(1).id()).isEqualTo(firstTurn.messages().get(1).id());
    }

    @Test
    void test14_appointmentOptInFlagPersistence() {
        AssistantDtos.Session session = trackSession(service.create(account1, new AssistantDtos.CreateSession("OptIn Test")));
        UUID reqId = UUID.randomUUID();
        AssistantDtos.SendMessage request = new AssistantDtos.SendMessage(reqId, "Tell me about my care", true);

        AssistantDtos.Turn turn = service.send(account1, session.id(), request);
        AssistantDtos.Message userMsg = turn.messages().get(0);

        Boolean persistedOptIn = jdbc.queryForObject(
                "SELECT include_appointments FROM ai_chat_messages WHERE id = ?", Boolean.class, userMsg.id());
        assertThat(persistedOptIn).isTrue();
    }
}

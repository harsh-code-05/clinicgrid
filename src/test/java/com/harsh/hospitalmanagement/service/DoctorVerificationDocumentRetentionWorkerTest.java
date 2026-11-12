package com.harsh.hospitalmanagement.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.DoctorVerificationDocument;
import com.harsh.hospitalmanagement.entity.DoctorVerificationDocumentEvent;
import com.harsh.hospitalmanagement.entity.Specialization;
import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.enums.DocumentEventOutcome;
import com.harsh.hospitalmanagement.enums.DocumentEventType;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.enums.VerificationActorRole;
import com.harsh.hospitalmanagement.enums.VerificationDocumentKind;
import com.harsh.hospitalmanagement.repository.DoctorRepository;
import com.harsh.hospitalmanagement.repository.DoctorVerificationDocumentEventRepository;
import com.harsh.hospitalmanagement.repository.DoctorVerificationDocumentRepository;
import com.harsh.hospitalmanagement.repository.SpecializationRepository;
import com.harsh.hospitalmanagement.repository.UserRepository;
import com.harsh.hospitalmanagement.service.DoctorVerificationService.DocumentKind;
import com.harsh.hospitalmanagement.storage.DocumentKey;
import com.harsh.hospitalmanagement.storage.DocumentStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:retention_worker_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.open-in-view=false",
        "spring.sql.init.mode=never"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DoctorVerificationDocumentRetentionWorkerTest {

    @Autowired private DoctorRepository doctorRepository;
    @Autowired private DoctorVerificationDocumentRepository documentRepository;
    @Autowired private DoctorVerificationDocumentEventRepository eventRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private SpecializationRepository specializationRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired private ApplicationContext applicationContext;
    @Autowired private ScheduledTaskHolder scheduledTaskHolder;
    @Autowired private DoctorVerificationDocumentRetentionWorker configuredWorker;
    @Autowired private DoctorVerificationDocumentService documentService;

    @MockitoBean private DocumentStorage documentStorage;

    private DoctorVerificationDocumentRetentionWorker worker;
    private Doctor doctor;

    @BeforeEach
    void setUp() {
        jdbc.execute("DELETE FROM doctor_verification_document_events");
        jdbc.execute("DELETE FROM doctor_verification_documents");
        org.mockito.Mockito.reset(documentStorage);

        worker = new DoctorVerificationDocumentRetentionWorker(
                doctorRepository, documentRepository, eventRepository, transactionManager, documentStorage);

        var spec = new Specialization();
        spec.setName("spec-" + UUID.randomUUID());
        spec = specializationRepository.save(spec);

        var user = new User();
        user.setEmail("doc-" + UUID.randomUUID() + "@example.invalid");
        user.setRole(Role.DOCTOR);
        user = userRepository.save(user);

        doctor = new Doctor();
        doctor.setUser(user);
        doctor.setSpecialization(spec);
        doctor = doctorRepository.save(doctor);
    }

    @Test
    void purgesExpiredRetiredDocument() throws IOException {
        Instant now = Instant.now();
        Instant retiredAt = now.minus(Duration.ofDays(31));
        Instant purgeDeadline = now.minus(Duration.ofDays(1));

        UUID docId = UUID.randomUUID();
        String storageKey = new DocumentKey(doctor.getId(), DocumentKind.ID_PROOF, docId).toString();
        DoctorVerificationDocument doc = new DoctorVerificationDocument(
                docId, doctor, VerificationDocumentKind.ID_PROOF, storageKey,
                "image/png", 1024, "a".repeat(64), retiredAt.minus(Duration.ofDays(10)));
        doc.retire(retiredAt, purgeDeadline);
        doc = documentRepository.save(doc);

        when(documentStorage.delete(any(DocumentKey.class))).thenReturn(true);

        int purged = worker.purgeExpiredDocuments(now, "retention-trace-1");
        assertThat(purged).isEqualTo(1);

        DoctorVerificationDocument updated = documentRepository.findById(docId).orElseThrow();
        assertThat(updated.isPurged()).isTrue();
        assertThat(updated.getPurgedAt()).isNotNull();

        verify(documentStorage).delete(DocumentKey.parse(storageKey));

        List<DoctorVerificationDocumentEvent> auditEvents = eventRepository
                .findByDoctorIdOrderByOccurredAtDescIdDesc(doctor.getId());
        assertThat(auditEvents).hasSize(1);
        DoctorVerificationDocumentEvent event = auditEvents.getFirst();
        assertThat(event.getEventType()).isEqualTo(DocumentEventType.DOCUMENT_PURGED);
        assertThat(event.getActorRole()).isEqualTo(VerificationActorRole.SYSTEM);
        assertThat(event.getActorUser()).isNull();
        assertThat(event.getOutcome()).isEqualTo(DocumentEventOutcome.SUCCESS);
        assertThat(event.getDocument().getId()).isEqualTo(docId);
    }

    @Test
    void skipsDocumentWithinRetentionPeriod() throws IOException {
        Instant now = Instant.now();
        Instant retiredAt = now.minus(Duration.ofDays(5));
        Instant purgeDeadline = now.plus(Duration.ofDays(25));

        UUID docId = UUID.randomUUID();
        String storageKey = new DocumentKey(doctor.getId(), DocumentKind.ID_PROOF, docId).toString();
        DoctorVerificationDocument doc = new DoctorVerificationDocument(
                docId, doctor, VerificationDocumentKind.ID_PROOF, storageKey,
                "image/png", 1024, "b".repeat(64), retiredAt.minus(Duration.ofDays(1)));
        doc.retire(retiredAt, purgeDeadline);
        documentRepository.save(doc);

        int purged = worker.purgeExpiredDocuments(now, "retention-trace-2");
        assertThat(purged).isEqualTo(0);

        DoctorVerificationDocument updated = documentRepository.findById(docId).orElseThrow();
        assertThat(updated.isPurged()).isFalse();
        assertThat(updated.getPurgedAt()).isNull();

        verify(documentStorage, never()).delete(any());
        assertThat(eventRepository.findByDoctorIdOrderByOccurredAtDescIdDesc(doctor.getId())).isEmpty();
    }

    @Test
    void skipsActiveDocument() throws IOException {
        Instant now = Instant.now();
        UUID docId = UUID.randomUUID();
        String storageKey = new DocumentKey(doctor.getId(), DocumentKind.ID_PROOF, docId).toString();
        DoctorVerificationDocument doc = new DoctorVerificationDocument(
                docId, doctor, VerificationDocumentKind.ID_PROOF, storageKey,
                "image/png", 1024, "c".repeat(64), now);
        documentRepository.save(doc);

        int purged = worker.purgeExpiredDocuments(now, "retention-trace-3");
        assertThat(purged).isEqualTo(0);

        verify(documentStorage, never()).delete(any());
    }

    @Test
    void preservesExpiredDocumentIfStillReferencedByDoctorProfile() throws IOException {
        Instant now = Instant.now();
        Instant retiredAt = now.minus(Duration.ofDays(31));
        Instant purgeDeadline = now.minus(Duration.ofDays(1));

        UUID docId = UUID.randomUUID();
        String storageKey = new DocumentKey(doctor.getId(), DocumentKind.ID_PROOF, docId).toString();
        DoctorVerificationDocument doc = new DoctorVerificationDocument(
                docId, doctor, VerificationDocumentKind.ID_PROOF, storageKey,
                "image/png", 1024, "d".repeat(64), retiredAt.minus(Duration.ofDays(10)));
        doc.retire(retiredAt, purgeDeadline);
        documentRepository.save(doc);

        // Doctor profile still references this storage key
        doctor.setIdProofDocumentPath(storageKey);
        doctorRepository.save(doctor);

        int purged = worker.purgeExpiredDocuments(now, "retention-trace-4");
        assertThat(purged).isEqualTo(0);

        DoctorVerificationDocument updated = documentRepository.findById(docId).orElseThrow();
        assertThat(updated.isPurged()).isFalse();

        verify(documentStorage, never()).delete(any());
    }

    @Test
    void scheduledPurgeDoesNothingWhenStorageDisabled() {
        var disabledWorker = new DoctorVerificationDocumentRetentionWorker(
                doctorRepository, documentRepository, eventRepository, transactionManager, null);
        disabledWorker.scheduledPurge();
        assertThat(eventRepository.findByDoctorIdOrderByOccurredAtDescIdDesc(doctor.getId())).isEmpty();
    }

    @Test
    void purgeFailsAndRollsBackOnStorageIoException() throws IOException {
        Instant now = Instant.now();
        Instant retiredAt = now.minus(Duration.ofDays(31));
        Instant purgeDeadline = now.minus(Duration.ofDays(1));

        UUID docId = UUID.randomUUID();
        String storageKey = new DocumentKey(doctor.getId(), DocumentKind.ID_PROOF, docId).toString();
        DoctorVerificationDocument doc = new DoctorVerificationDocument(
                docId, doctor, VerificationDocumentKind.ID_PROOF, storageKey,
                "image/png", 1024, "e".repeat(64), retiredAt.minus(Duration.ofDays(10)));
        doc.retire(retiredAt, purgeDeadline);
        doc = documentRepository.save(doc);

        when(documentStorage.delete(any(DocumentKey.class))).thenThrow(new IOException("Simulated storage failure"));

        int purged = worker.purgeExpiredDocuments(now, "retention-trace-ioe");
        assertThat(purged).isEqualTo(0);

        DoctorVerificationDocument updated = documentRepository.findById(docId).orElseThrow();
        assertThat(updated.isPurged()).isFalse();
        assertThat(updated.getPurgedAt()).isNull();

        assertThat(eventRepository.findByDoctorIdOrderByOccurredAtDescIdDesc(doctor.getId())).isEmpty();
    }

    @Test
    void purgeRecoversWhenPhysicalFileAlreadyDeleted() throws IOException {
        Instant now = Instant.now();
        Instant retiredAt = now.minus(Duration.ofDays(31));
        Instant purgeDeadline = now.minus(Duration.ofDays(1));

        UUID docId = UUID.randomUUID();
        String storageKey = new DocumentKey(doctor.getId(), DocumentKind.ID_PROOF, docId).toString();
        DoctorVerificationDocument doc = new DoctorVerificationDocument(
                docId, doctor, VerificationDocumentKind.ID_PROOF, storageKey,
                "image/png", 1024, "f".repeat(64), retiredAt.minus(Duration.ofDays(10)));
        doc.retire(retiredAt, purgeDeadline);
        doc = documentRepository.save(doc);

        // Physical file returns false (already deleted from disk on prior attempt)
        when(documentStorage.delete(any(DocumentKey.class))).thenReturn(false);

        int purged = worker.purgeExpiredDocuments(now, "retention-trace-retry");
        assertThat(purged).isEqualTo(1);

        DoctorVerificationDocument updated = documentRepository.findById(docId).orElseThrow();
        assertThat(updated.isPurged()).isTrue();
        assertThat(updated.getPurgedAt()).isNotNull();

        List<DoctorVerificationDocumentEvent> auditEvents = eventRepository
                .findByDoctorIdOrderByOccurredAtDescIdDesc(doctor.getId());
        assertThat(auditEvents).hasSize(1);
        assertThat(auditEvents.getFirst().getEventType()).isEqualTo(DocumentEventType.DOCUMENT_PURGED);
    }

    @Test
    void boundedBatchFetchingLimitsPurgeCount() throws IOException {
        Instant now = Instant.now();
        Instant retiredAt = now.minus(Duration.ofDays(31));
        Instant purgeDeadline = now.minus(Duration.ofDays(1));

        when(documentStorage.delete(any(DocumentKey.class))).thenReturn(true);

        for (int i = 0; i < 5; i++) {
            UUID docId = UUID.randomUUID();
            String storageKey = new DocumentKey(doctor.getId(), DocumentKind.ID_PROOF, docId).toString();
            DoctorVerificationDocument doc = new DoctorVerificationDocument(
                docId, doctor, VerificationDocumentKind.ID_PROOF, storageKey,
                "image/png", 1024, "g".repeat(63) + i, retiredAt.minus(Duration.ofDays(10)));
            doc.retire(retiredAt, purgeDeadline);
            documentRepository.save(doc);
        }

        // Bounded to 2 documents
        int purged = worker.purgeExpiredDocuments(now, 2, "bounded-trace");
        assertThat(purged).isEqualTo(2);

        long unpurgedRemaining = documentRepository.findByDoctorIdOrderByCreatedAtDesc(doctor.getId()).stream()
                .filter(d -> d.isRetired() && !d.isPurged())
                .count();
        assertThat(unpurgedRemaining).isEqualTo(3);
    }

    @Test
    void concurrentPurgeExecutionsAreSafe() throws Exception {
        Instant now = Instant.now();
        Instant retiredAt = now.minus(Duration.ofDays(31));
        Instant purgeDeadline = now.minus(Duration.ofDays(1));

        UUID docId = UUID.randomUUID();
        String storageKey = new DocumentKey(doctor.getId(), DocumentKind.ID_PROOF, docId).toString();
        DoctorVerificationDocument doc = new DoctorVerificationDocument(
                docId, doctor, VerificationDocumentKind.ID_PROOF, storageKey,
                "image/png", 1024, "h".repeat(64), retiredAt.minus(Duration.ofDays(10)));
        doc.retire(retiredAt, purgeDeadline);
        documentRepository.save(doc);

        when(documentStorage.delete(any(DocumentKey.class))).thenReturn(true);

        var executor = Executors.newFixedThreadPool(2);
        var barrier = new CyclicBarrier(2);

        Callable<Integer> task = () -> {
            barrier.await();
            return worker.purgeExpiredDocuments(now, 10, "concurrent-trace");
        };

        Future<Integer> f1 = executor.submit(task);
        Future<Integer> f2 = executor.submit(task);

        int purged1 = f1.get();
        int purged2 = f2.get();
        executor.shutdown();

        assertThat(purged1 + purged2).isEqualTo(1);

        DoctorVerificationDocument updated = documentRepository.findById(docId).orElseThrow();
        assertThat(updated.isPurged()).isTrue();
    }

    @Test
    void defaultSchedulingIsDisabled() {
        // By default, scheduled purge is disabled
        assertThat(worker.isPurgeEnabled()).isFalse();
        worker.scheduledPurge();
        assertThat(eventRepository.findByDoctorIdOrderByOccurredAtDescIdDesc(doctor.getId())).isEmpty();
    }

    @Test
    void rejectsCorruptUnparseableStorageKeyWithoutPurgingOrLoggingSensitiveKey() throws IOException {
        Instant now = Instant.now();
        Instant retiredAt = now.minus(Duration.ofDays(31));
        Instant purgeDeadline = now.minus(Duration.ofDays(1));
        UUID docId = UUID.randomUUID();
        String corruptKey = "../vault/secret-path-token/" + docId;

        DoctorVerificationDocument doc = new DoctorVerificationDocument(
                docId, doctor, VerificationDocumentKind.ID_PROOF, corruptKey,
                "image/png", 1024, "i".repeat(64), retiredAt.minus(Duration.ofDays(5)));
        doc.retire(retiredAt, purgeDeadline);
        documentRepository.save(doc);

        String logs = captureWorkerLogs(() -> {
            int purged = worker.purgeExpiredDocuments(now, "trace-corrupt-key");
            assertThat(purged).isEqualTo(0);
        });

        verify(documentStorage, never()).delete(any());
        DoctorVerificationDocument unchanged = documentRepository.findById(docId).orElseThrow();
        assertThat(unchanged.isPurged()).isFalse();
        assertThat(unchanged.getPurgedAt()).isNull();
        assertThat(eventRepository.findByDoctorIdOrderByOccurredAtDescIdDesc(doctor.getId())).isEmpty();

        assertThat(logs).contains("INVALID_STORAGE_KEY").contains("trace-corrupt-key");
        assertThat(logs).doesNotContain(corruptKey).doesNotContain("secret-path-token").doesNotContain(docId.toString());
    }

    @Test
    void rejectsStorageKeyWithMismatchedDoctorIdWithoutPurgingOrLoggingKey() throws IOException {
        Instant now = Instant.now();
        Instant retiredAt = now.minus(Duration.ofDays(31));
        Instant purgeDeadline = now.minus(Duration.ofDays(1));
        UUID docId = UUID.randomUUID();
        long otherDoctorId = doctor.getId() + 999L;
        String mismatchedKey = new DocumentKey(otherDoctorId, DocumentKind.ID_PROOF, docId).toString();

        DoctorVerificationDocument doc = new DoctorVerificationDocument(
                docId, doctor, VerificationDocumentKind.ID_PROOF, mismatchedKey,
                "image/png", 1024, "j".repeat(64), retiredAt.minus(Duration.ofDays(5)));
        doc.retire(retiredAt, purgeDeadline);
        documentRepository.save(doc);

        String logs = captureWorkerLogs(() -> {
            int purged = worker.purgeExpiredDocuments(now, "trace-doc-mismatch");
            assertThat(purged).isEqualTo(0);
        });

        verify(documentStorage, never()).delete(any());
        DoctorVerificationDocument unchanged = documentRepository.findById(docId).orElseThrow();
        assertThat(unchanged.isPurged()).isFalse();
        assertThat(unchanged.getPurgedAt()).isNull();
        assertThat(eventRepository.findByDoctorIdOrderByOccurredAtDescIdDesc(doctor.getId())).isEmpty();

        assertThat(logs).contains("STORAGE_KEY_DOCTOR_MISMATCH").contains("trace-doc-mismatch");
        assertThat(logs).doesNotContain(mismatchedKey).doesNotContain(docId.toString());
    }

    @Test
    void rejectsStorageKeyWithMismatchedKindWithoutPurgingOrLoggingKey() throws IOException {
        Instant now = Instant.now();
        Instant retiredAt = now.minus(Duration.ofDays(31));
        Instant purgeDeadline = now.minus(Duration.ofDays(1));
        UUID docId = UUID.randomUUID();
        String mismatchedKindKey = new DocumentKey(doctor.getId(), DocumentKind.QUALIFICATION, docId).toString();

        DoctorVerificationDocument doc = new DoctorVerificationDocument(
                docId, doctor, VerificationDocumentKind.ID_PROOF, mismatchedKindKey,
                "image/png", 1024, "k".repeat(64), retiredAt.minus(Duration.ofDays(5)));
        doc.retire(retiredAt, purgeDeadline);
        documentRepository.save(doc);

        String logs = captureWorkerLogs(() -> {
            int purged = worker.purgeExpiredDocuments(now, "trace-kind-mismatch");
            assertThat(purged).isEqualTo(0);
        });

        verify(documentStorage, never()).delete(any());
        DoctorVerificationDocument unchanged = documentRepository.findById(docId).orElseThrow();
        assertThat(unchanged.isPurged()).isFalse();
        assertThat(unchanged.getPurgedAt()).isNull();
        assertThat(eventRepository.findByDoctorIdOrderByOccurredAtDescIdDesc(doctor.getId())).isEmpty();

        assertThat(logs).contains("STORAGE_KEY_KIND_MISMATCH").contains("trace-kind-mismatch");
        assertThat(logs).doesNotContain(mismatchedKindKey).doesNotContain(docId.toString());
    }

    @Test
    void rejectsStorageKeyWithMismatchedUuidVersionWithoutPurgingOrLoggingKey() throws IOException {
        Instant now = Instant.now();
        Instant retiredAt = now.minus(Duration.ofDays(31));
        Instant purgeDeadline = now.minus(Duration.ofDays(1));
        UUID docId = UUID.randomUUID();
        UUID differentVersion = UUID.randomUUID();
        String mismatchedVersionKey = new DocumentKey(doctor.getId(), DocumentKind.ID_PROOF, differentVersion).toString();

        DoctorVerificationDocument doc = new DoctorVerificationDocument(
                docId, doctor, VerificationDocumentKind.ID_PROOF, mismatchedVersionKey,
                "image/png", 1024, "l".repeat(64), retiredAt.minus(Duration.ofDays(5)));
        doc.retire(retiredAt, purgeDeadline);
        documentRepository.save(doc);

        String logs = captureWorkerLogs(() -> {
            int purged = worker.purgeExpiredDocuments(now, "trace-ver-mismatch");
            assertThat(purged).isEqualTo(0);
        });

        verify(documentStorage, never()).delete(any());
        DoctorVerificationDocument unchanged = documentRepository.findById(docId).orElseThrow();
        assertThat(unchanged.isPurged()).isFalse();
        assertThat(unchanged.getPurgedAt()).isNull();
        assertThat(eventRepository.findByDoctorIdOrderByOccurredAtDescIdDesc(doctor.getId())).isEmpty();

        assertThat(logs).contains("STORAGE_KEY_VERSION_MISMATCH").contains("trace-ver-mismatch");
        assertThat(logs).doesNotContain(mismatchedVersionKey)
                .doesNotContain(docId.toString())
                .doesNotContain(differentVersion.toString());
    }

    @Test
    void storageFailureRedactsSensitivePathsAndRemainsRetrySafe() throws IOException {
        Instant now = Instant.now();
        Instant retiredAt = now.minus(Duration.ofDays(31));
        Instant purgeDeadline = now.minus(Duration.ofDays(1));
        UUID docId = UUID.randomUUID();
        DocumentKey key = new DocumentKey(doctor.getId(), DocumentKind.ID_PROOF, docId);
        String storageKey = key.toString();

        DoctorVerificationDocument doc = new DoctorVerificationDocument(
                docId, doctor, VerificationDocumentKind.ID_PROOF, storageKey,
                "image/png", 1024, "m".repeat(64), retiredAt.minus(Duration.ofDays(5)));
        doc.retire(retiredAt, purgeDeadline);
        documentRepository.save(doc);

        when(documentStorage.delete(key))
                .thenThrow(new IOException("C:\\hms-vault\\iv=deadbeef\\doc.enc: Access denied"))
                .thenReturn(true);

        // Attempt 1: storage delete fails -> leaves row unpurged, no DOCUMENT_PURGED event, redacted logs
        String logs = captureWorkerLogs(() -> {
            int firstAttempt = worker.purgeExpiredDocuments(now, "trace-io-fail");
            assertThat(firstAttempt).isEqualTo(0);
        });

        DoctorVerificationDocument afterFailure = documentRepository.findById(docId).orElseThrow();
        assertThat(afterFailure.isPurged()).isFalse();
        assertThat(afterFailure.getPurgedAt()).isNull();
        assertThat(eventRepository.findByDoctorIdOrderByOccurredAtDescIdDesc(doctor.getId())).isEmpty();

        assertThat(logs).contains("STORAGE_DELETE_FAILED").contains("trace-io-fail").contains("java.io.IOException");
        assertThat(logs).doesNotContain("hms-vault")
                .doesNotContain("deadbeef")
                .doesNotContain("Access denied")
                .doesNotContain(storageKey)
                .doesNotContain(docId.toString());

        // Attempt 2: retry succeeds cleanly
        int retryAttempt = worker.purgeExpiredDocuments(now, "trace-io-retry");
        assertThat(retryAttempt).isEqualTo(1);

        DoctorVerificationDocument afterRetry = documentRepository.findById(docId).orElseThrow();
        assertThat(afterRetry.isPurged()).isTrue();
        assertThat(afterRetry.getPurgedAt()).isNotNull();

        List<DoctorVerificationDocumentEvent> events = eventRepository
                .findByDoctorIdOrderByOccurredAtDescIdDesc(doctor.getId());
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().getEventType()).isEqualTo(DocumentEventType.DOCUMENT_PURGED);
        assertThat(events.getFirst().getOutcome()).isEqualTo(DocumentEventOutcome.SUCCESS);
        assertThat(events.getFirst().getDetails()).doesNotContain(storageKey).doesNotContain(docId.toString());
    }

    @Test
    void invalidCandidateInBatchDoesNotBlockValidCandidatePurgeAndFailClosedWhenStorageMissing() throws IOException {
        Instant now = Instant.now();
        Instant retiredAt = now.minus(Duration.ofDays(31));
        Instant earlierDeadline = now.minus(Duration.ofDays(2));
        Instant laterDeadline = now.minus(Duration.ofDays(1));

        UUID badId = UUID.randomUUID();
        DoctorVerificationDocument badDoc = new DoctorVerificationDocument(
                badId, doctor, VerificationDocumentKind.ID_PROOF, "corrupt/key/" + badId,
                "image/png", 1024, "n".repeat(64), retiredAt.minus(Duration.ofDays(5)));
        badDoc.retire(retiredAt, earlierDeadline);
        documentRepository.save(badDoc);

        UUID goodId = UUID.randomUUID();
        DocumentKey goodKey = new DocumentKey(doctor.getId(), DocumentKind.QUALIFICATION, goodId);
        DoctorVerificationDocument goodDoc = new DoctorVerificationDocument(
                goodId, doctor, VerificationDocumentKind.QUALIFICATION, goodKey.toString(),
                "image/png", 1024, "o".repeat(64), retiredAt.minus(Duration.ofDays(5)));
        goodDoc.retire(retiredAt, laterDeadline);
        documentRepository.save(goodDoc);

        when(documentStorage.delete(goodKey)).thenReturn(true);

        int purged = worker.purgeExpiredDocuments(now, 10, "trace-mixed-batch");
        assertThat(purged).isEqualTo(1);
        verify(documentStorage).delete(goodKey);

        assertThat(documentRepository.findById(badId).orElseThrow().isPurged()).isFalse();
        assertThat(documentRepository.findById(goodId).orElseThrow().isPurged()).isTrue();

        // Direct execution with disabled storage must fail closed without purging anything
        UUID disabledId = UUID.randomUUID();
        DoctorVerificationDocument disabledDoc = new DoctorVerificationDocument(
                disabledId, doctor, VerificationDocumentKind.ID_PROOF,
                new DocumentKey(doctor.getId(), DocumentKind.ID_PROOF, disabledId).toString(),
                "image/png", 1024, "p".repeat(64), retiredAt.minus(Duration.ofDays(5)));
        disabledDoc.retire(retiredAt, earlierDeadline);
        documentRepository.save(disabledDoc);

        var disabledWorker = new DoctorVerificationDocumentRetentionWorker(
                doctorRepository, documentRepository, eventRepository, transactionManager, null);
        assertThat(disabledWorker.purgeExpiredDocuments(now, "trace-no-storage")).isEqualTo(0);
        assertThat(documentRepository.findById(disabledId).orElseThrow().isPurged()).isFalse();
    }

    @Test
    void defaultSchedulerRegistersTaskInDisabledState() {
        assertThat(applicationContext.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isNotEmpty();
        assertThat(configuredWorker.isPurgeEnabled()).isFalse();
        assertThat(scheduledTaskHolder.getScheduledTasks()).isEmpty();
    }

    @Test
    void attachDocumentRollbackDeletesNewlyWrittenBlobAndPreservesExistingActiveDocument() throws Exception {
        byte[] pngBytes = createTestPngBytes();
        var validator = new com.harsh.hospitalmanagement.storage.VerificationImageValidator();
        var validated = validator.validate("image/png", new java.io.ByteArrayInputStream(pngBytes));

        UUID initialId = UUID.randomUUID();
        DocumentKey initialKey = new DocumentKey(doctor.getId(), DocumentKind.ID_PROOF, initialId);
        UUID failedReplaceId = UUID.randomUUID();
        DocumentKey failedReplaceKey = new DocumentKey(doctor.getId(), DocumentKind.ID_PROOF, failedReplaceId);
        UUID failedFreshId = UUID.randomUUID();
        DocumentKey failedFreshKey = new DocumentKey(doctor.getId(), DocumentKind.QUALIFICATION, failedFreshId);

        when(documentStorage.store(doctor.getId(), DocumentKind.ID_PROOF, validated))
                .thenReturn(initialKey)
                .thenReturn(failedReplaceKey);
        when(documentStorage.store(doctor.getId(), DocumentKind.QUALIFICATION, validated))
                .thenReturn(failedFreshKey);
        when(documentStorage.delete(any(DocumentKey.class))).thenReturn(true);

        // Commit initial active document cleanly.
        documentService.attachDocument(
                doctor.getId(), VerificationDocumentKind.ID_PROOF, validated,
                doctor.getUser(), VerificationActorRole.DOCTOR, "attach-commit-1");
        verify(documentStorage, never()).delete(initialKey);

        // Rollback a replacement upload inside TransactionTemplate -> failedReplaceKey deleted, initialKey untouched.
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            documentService.attachDocument(
                    doctor.getId(), VerificationDocumentKind.ID_PROOF, validated,
                    doctor.getUser(), VerificationActorRole.DOCTOR, "attach-rollback-replace");
            status.setRollbackOnly();
        });
        verify(documentStorage).delete(failedReplaceKey);
        verify(documentStorage, never()).delete(initialKey);
        assertThat(documentRepository.findByDoctorIdAndDocumentKindAndRetiredAtIsNull(
                doctor.getId(), VerificationDocumentKind.ID_PROOF).orElseThrow().getStorageKey())
                .isEqualTo(initialKey.toString());

        // Rollback an initial upload for another kind -> failedFreshKey deleted, no catalog row left behind.
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            documentService.attachDocument(
                    doctor.getId(), VerificationDocumentKind.QUALIFICATION, validated,
                    doctor.getUser(), VerificationActorRole.DOCTOR, "attach-rollback-fresh");
            status.setRollbackOnly();
        });
        verify(documentStorage).delete(failedFreshKey);
        assertThat(documentRepository.findByDoctorIdAndDocumentKindAndRetiredAtIsNull(
                doctor.getId(), VerificationDocumentKind.QUALIFICATION)).isEmpty();
    }

    @Test
    void reconcileOrphanedDocumentsDeletesAgedUnreferencedBlobsRespectsGraceAndPaginatesCursor() throws Exception {
        Instant now = Instant.now();

        // Active referenced document in DB
        UUID activeId = UUID.randomUUID();
        DocumentKey activeKey = new DocumentKey(doctor.getId(), DocumentKind.ID_PROOF, activeId);
        DoctorVerificationDocument activeDoc = new DoctorVerificationDocument(
                activeId, doctor, VerificationDocumentKind.ID_PROOF, activeKey.toString(),
                "image/png", 512, "b".repeat(64), now.minus(Duration.ofHours(5)));
        documentRepository.saveAndFlush(activeDoc);

        // Retired (unpurged) document in DB
        UUID retiredId = UUID.randomUUID();
        DocumentKey retiredKey = new DocumentKey(doctor.getId(), DocumentKind.QUALIFICATION, retiredId);
        DoctorVerificationDocument retiredDoc = new DoctorVerificationDocument(
                retiredId, doctor, VerificationDocumentKind.QUALIFICATION, retiredKey.toString(),
                "image/png", 512, "c".repeat(64), now.minus(Duration.ofDays(5)));
        retiredDoc.retire(now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(29)));
        documentRepository.saveAndFlush(retiredDoc);

        // Crash leftovers absent from DB
        DocumentKey freshOrphan = new DocumentKey(doctor.getId(), DocumentKind.ID_PROOF, UUID.randomUUID());
        DocumentKey agedOrphan = new DocumentKey(doctor.getId(), DocumentKind.QUALIFICATION, UUID.randomUUID());

        when(documentStorage.exists(any(DocumentKey.class))).thenReturn(true);
        when(documentStorage.delete(any(DocumentKey.class))).thenReturn(true);

        when(documentStorage.listStoredDocuments(null, 2)).thenReturn(new DocumentStorage.DocumentScanPage(
                List.of(
                        new DocumentStorage.StoredDocument(freshOrphan, now.minus(Duration.ofMinutes(10))),
                        new DocumentStorage.StoredDocument(activeKey, now.minus(Duration.ofHours(4)))),
                "cursor-page-2",
                true));
        when(documentStorage.listStoredDocuments("cursor-page-2", 2)).thenReturn(new DocumentStorage.DocumentScanPage(
                List.of(
                        new DocumentStorage.StoredDocument(agedOrphan, now.minus(Duration.ofHours(3))),
                        new DocumentStorage.StoredDocument(retiredKey, now.minus(Duration.ofDays(5)))),
                null,
                false));

        var reconcileWorker = new DoctorVerificationDocumentRetentionWorker(
                doctorRepository, documentRepository, eventRepository,
                transactionManager, documentStorage, documentService);

        int page1Deleted = reconcileWorker.reconcileOrphanedDocuments(2, Duration.ofHours(1), "reconcile-p1");
        assertThat(page1Deleted).isZero();
        verify(documentStorage, never()).delete(freshOrphan);
        verify(documentStorage, never()).delete(activeKey);

        int page2Deleted = reconcileWorker.reconcileOrphanedDocuments(2, Duration.ofHours(1), "reconcile-p2");
        assertThat(page2Deleted).isEqualTo(1);
        verify(documentStorage).delete(agedOrphan);
        verify(documentStorage, never()).delete(retiredKey);
    }

    private static byte[] createTestPngBytes() throws IOException {
        var image = new java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var bytes = new java.io.ByteArrayOutputStream();
        try (var stream = new javax.imageio.stream.MemoryCacheImageOutputStream(bytes)) {
            javax.imageio.ImageIO.write(image, "png", stream);
        }
        return bytes.toByteArray();
    }

    private static String captureWorkerLogs(Runnable action) {
        Logger workerLogger = (Logger) LoggerFactory.getLogger(DoctorVerificationDocumentRetentionWorker.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        workerLogger.addAppender(appender);
        try {
            action.run();
        } finally {
            workerLogger.detachAppender(appender);
        }
        var out = new StringBuilder();
        for (ILoggingEvent event : appender.list) {
            out.append(event.getFormattedMessage()).append('\n');
            if (event.getThrowableProxy() != null) {
                out.append(event.getThrowableProxy().getMessage()).append('\n');
            }
        }
        return out.toString();
    }
}

package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.DoctorVerificationDocument;
import com.harsh.hospitalmanagement.entity.DoctorVerificationDocumentEvent;
import com.harsh.hospitalmanagement.enums.DocumentEventOutcome;
import com.harsh.hospitalmanagement.enums.DocumentEventType;
import com.harsh.hospitalmanagement.enums.VerificationActorRole;
import com.harsh.hospitalmanagement.enums.VerificationDocumentKind;
import com.harsh.hospitalmanagement.repository.DoctorRepository;
import com.harsh.hospitalmanagement.repository.DoctorVerificationDocumentEventRepository;
import com.harsh.hospitalmanagement.repository.DoctorVerificationDocumentRepository;
import com.harsh.hospitalmanagement.storage.DocumentKey;
import com.harsh.hospitalmanagement.storage.DocumentStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class DoctorVerificationDocumentRetentionWorker {

    private static final Logger log = LoggerFactory.getLogger(DoctorVerificationDocumentRetentionWorker.class);

    public static final int DEFAULT_BATCH_SIZE = 100;
    public static final int MAX_BATCH_SIZE = 1000;
    public static final Duration DEFAULT_ORPHAN_GRACE_PERIOD = Duration.ofHours(1);

    private final DoctorRepository doctorRepository;
    private final DoctorVerificationDocumentRepository documentRepository;
    private final DoctorVerificationDocumentEventRepository eventRepository;
    private final PlatformTransactionManager transactionManager;
    private final DocumentStorage documentStorage;
    private final DoctorVerificationDocumentOrphanReconciliation orphanReconciliation;
    private final AtomicReference<String> orphanScanCursor = new AtomicReference<>();

    @Value("${hms.document-storage.purge.enabled:false}")
    private boolean purgeEnabled;

    @Value("${hms.document-storage.purge-cron:-}")
    private String purgeCron = "-";

    @Value("${hms.document-storage.orphan-grace-period:PT1H}")
    private Duration orphanGracePeriod = DEFAULT_ORPHAN_GRACE_PERIOD;

    @Value("${hms.document-storage.orphan-scan-batch-size:100}")
    private int orphanScanBatchSize = DEFAULT_BATCH_SIZE;

    public DoctorVerificationDocumentRetentionWorker(
            DoctorRepository doctorRepository,
            DoctorVerificationDocumentRepository documentRepository,
            DoctorVerificationDocumentEventRepository eventRepository,
            PlatformTransactionManager transactionManager,
            DocumentStorage documentStorage
    ) {
        this(doctorRepository, documentRepository, eventRepository, transactionManager, documentStorage, null);
    }

    @Autowired
    public DoctorVerificationDocumentRetentionWorker(
            DoctorRepository doctorRepository,
            DoctorVerificationDocumentRepository documentRepository,
            DoctorVerificationDocumentEventRepository eventRepository,
            PlatformTransactionManager transactionManager,
            @Autowired(required = false) DocumentStorage documentStorage,
            @Autowired(required = false) DoctorVerificationDocumentOrphanReconciliation orphanReconciliation
    ) {
        this.doctorRepository = doctorRepository;
        this.documentRepository = documentRepository;
        this.eventRepository = eventRepository;
        this.transactionManager = transactionManager;
        this.documentStorage = documentStorage;
        this.orphanReconciliation = orphanReconciliation;
    }

    @Scheduled(cron = "${hms.document-storage.purge-cron:-}")
    public void scheduledPurge() {
        if (!purgeEnabled || "-".equals(purgeCron) || documentStorage == null) {
            log.debug("Document storage retention purge is disabled; skipping scheduled execution");
            return;
        }
        String correlationId = UUID.randomUUID().toString();
        int purged = purgeExpiredDocuments(Instant.now(), DEFAULT_BATCH_SIZE, correlationId);
        int orphansDeleted = reconcileOrphanedDocuments(orphanScanBatchSize, orphanGracePeriod, correlationId);
        log.info("Completed scheduled verification document retention purge: purgedCount={}, orphansDeleted={}",
                purged, orphansDeleted);
    }

    public boolean isPurgeEnabled() {
        return purgeEnabled && !"-".equals(purgeCron) && documentStorage != null;
    }

    public int purgeExpiredDocuments(Instant cutoff, String correlationId) {
        return purgeExpiredDocuments(cutoff, DEFAULT_BATCH_SIZE, correlationId);
    }

    public int purgeExpiredDocuments(Instant cutoff, int batchSize, String correlationId) {
        Instant effectiveCutoff = cutoff != null ? cutoff : Instant.now();
        int effectiveBatchSize = Math.min(Math.max(1, batchSize), MAX_BATCH_SIZE);
        String effectiveCorrelationId = DoctorVerificationDocumentService.sanitizeCorrelationId(correlationId);

        if (documentStorage == null) {
            log.warn("Skipping verification document retention purge: reason=STORAGE_UNAVAILABLE, correlationId={}",
                    effectiveCorrelationId);
            return 0;
        }

        List<DoctorVerificationDocument> expiredCandidates = documentRepository
                .findByRetiredAtIsNotNullAndPurgedAtIsNullAndPurgeDeadlineBeforeOrderByPurgeDeadlineAscIdAsc(
                        effectiveCutoff, PageRequest.of(0, effectiveBatchSize));

        if (expiredCandidates.isEmpty()) {
            return 0;
        }

        int purgedCount = 0;
        for (DoctorVerificationDocument candidate : expiredCandidates) {
            try {
                boolean purged = purgeSingleDocument(candidate.getId(), effectiveCutoff, effectiveCorrelationId);
                if (purged) {
                    purgedCount++;
                }
            } catch (Exception e) {
                log.error("Retention purge aborted unexpectedly: correlationId={}, reason=UNEXPECTED_FAILURE, exceptionClass={}",
                        effectiveCorrelationId, e.getClass().getName());
            }
        }
        return purgedCount;
    }

    public int reconcileOrphanedDocuments(String correlationId) {
        return reconcileOrphanedDocuments(orphanScanBatchSize, orphanGracePeriod, correlationId);
    }

    public int reconcileOrphanedDocuments(int batchSize, Duration gracePeriod, String correlationId) {
        String effectiveCorrelationId = DoctorVerificationDocumentService.sanitizeCorrelationId(correlationId);
        if (documentStorage == null || orphanReconciliation == null) {
            return 0;
        }
        int effectiveBatchSize = Math.min(Math.max(1, batchSize), MAX_BATCH_SIZE);
        Duration effectiveGracePeriod = (gracePeriod != null && !gracePeriod.isNegative() && !gracePeriod.isZero())
                ? gracePeriod : DEFAULT_ORPHAN_GRACE_PERIOD;

        DocumentStorage.DocumentScanPage page;
        try {
            page = documentStorage.listStoredDocuments(orphanScanCursor.get(), effectiveBatchSize);
        } catch (IOException | RuntimeException e) {
            log.warn("Verification document orphan scan failed: correlationId={}, reason=SCAN_FAILED, exceptionClass={}",
                    effectiveCorrelationId, e.getClass().getName());
            return 0;
        }
        if (page == null) {
            return 0;
        }

        orphanScanCursor.set(page.nextCursor());
        if (page.documents() == null || page.documents().isEmpty()) {
            return 0;
        }

        int deletedCount = 0;
        for (DocumentStorage.StoredDocument candidate : page.documents()) {
            try {
                var decision = reconcileSingleOrphan(candidate, effectiveGracePeriod);
                if (decision == DoctorVerificationDocumentOrphanReconciliation.ReconciliationDecision.ORPHAN_DELETED) {
                    deletedCount++;
                    log.info("Deleted unreferenced verification document orphan after grace period: "
                                    + "doctorId={}, kind={}, correlationId={}",
                            candidate.key().doctorId(), candidate.key().kind().name(), effectiveCorrelationId);
                }
            } catch (Exception e) {
                log.warn("Verification document orphan reconciliation failed: doctorId={}, kind={}, "
                                + "correlationId={}, reason=ORPHAN_RECONCILE_FAILED, exceptionClass={}",
                        candidate.key().doctorId(),
                        candidate.key().kind().name(),
                        effectiveCorrelationId,
                        e.getClass().getName());
            }
        }
        return deletedCount;
    }

    private DoctorVerificationDocumentOrphanReconciliation.ReconciliationDecision reconcileSingleOrphan(
            DocumentStorage.StoredDocument candidate,
            Duration gracePeriod
    ) {
        if (transactionManager == null) {
            return orphanReconciliation.reconcileOrphanCandidate(
                    candidate.key(), candidate.lastModifiedAt(), gracePeriod);
        }
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return txTemplate.execute(status -> orphanReconciliation.reconcileOrphanCandidate(
                candidate.key(), candidate.lastModifiedAt(), gracePeriod));
    }

    private boolean purgeSingleDocument(UUID documentId, Instant now, String correlationId) {
        if (documentStorage == null || documentId == null) {
            return false;
        }
        if (transactionManager == null) {
            return purgeInternal(documentId, now, correlationId, null);
        }
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return Boolean.TRUE.equals(txTemplate.execute(
                status -> purgeInternal(documentId, now, correlationId, status)));
    }

    private boolean purgeInternal(
            UUID documentId,
            Instant now,
            String correlationId,
            TransactionStatus txStatus
    ) {
        if (documentStorage == null) {
            rollbackQuietly(txStatus);
            return false;
        }

        Long doctorId = documentRepository.findDoctorIdById(documentId).orElse(null);
        if (doctorId == null) {
            return false;
        }

        // Acquire Doctor lock before locking catalog row to match global service lock order and serialize with attachments.
        Doctor doctor = doctorRepository.findByIdForUpdate(doctorId).orElse(null);
        if (doctor == null) {
            rollbackQuietly(txStatus);
            logRedactedFailure(doctorId, null, correlationId, "DOCTOR_NOT_FOUND", null);
            return false;
        }

        DoctorVerificationDocument doc = documentRepository.findByIdForUpdate(documentId).orElse(null);
        if (doc == null || doc.isPurged() || !doc.isRetired()) {
            return false;
        }

        if (doc.getPurgeDeadline() == null || doc.getPurgeDeadline().isAfter(now)) {
            return false;
        }

        if (doc.getDoctor() == null || !Objects.equals(doc.getDoctor().getId(), doctor.getId())) {
            rollbackQuietly(txStatus);
            logRedactedFailure(doctor.getId(), doc.getDocumentKind(), correlationId, "CATALOG_OWNER_MISMATCH", null);
            return false;
        }

        if (doc.getDocumentKind() == null) {
            rollbackQuietly(txStatus);
            logRedactedFailure(doctor.getId(), null, correlationId, "MISSING_DOCUMENT_KIND", null);
            return false;
        }

        String storageKey = doc.getStorageKey();
        if (storageKey == null || storageKey.isBlank()) {
            rollbackQuietly(txStatus);
            logRedactedFailure(doctor.getId(), doc.getDocumentKind(), correlationId, "MISSING_STORAGE_KEY", null);
            return false;
        }

        // Safety check: verify storage key is not referenced by the locked doctor profile.
        if (constantTimeEquals(storageKey, doctor.getIdProofDocumentPath())
                || constantTimeEquals(storageKey, doctor.getQualificationDocumentPath())) {
            logRedactedFailure(doctor.getId(), doc.getDocumentKind(), correlationId,
                    "REFERENCED_BY_DOCTOR_PROFILE", null);
            return false;
        }

        // Safety check: verify no active document catalog row references this storage key or ID.
        var activeDocOpt = documentRepository.findByDoctorIdAndDocumentKindAndRetiredAtIsNull(
                doctor.getId(), doc.getDocumentKind());
        if (activeDocOpt.isPresent()
                && (constantTimeEquals(storageKey, activeDocOpt.get().getStorageKey())
                    || Objects.equals(doc.getId(), activeDocOpt.get().getId()))) {
            logRedactedFailure(doctor.getId(), doc.getDocumentKind(), correlationId,
                    "REFERENCED_BY_ACTIVE_DOCUMENT", null);
            return false;
        }

        // Validate key syntax, structure, and catalog identity BEFORE deleting anything from storage.
        DocumentKey key;
        try {
            key = DocumentKey.parse(storageKey);
        } catch (IllegalArgumentException e) {
            rollbackQuietly(txStatus);
            logRedactedFailure(doctor.getId(), doc.getDocumentKind(), correlationId,
                    "INVALID_STORAGE_KEY", e.getClass().getName());
            return false;
        }

        if (key.doctorId() != doctor.getId()) {
            rollbackQuietly(txStatus);
            logRedactedFailure(doctor.getId(), doc.getDocumentKind(), correlationId,
                    "STORAGE_KEY_DOCTOR_MISMATCH", null);
            return false;
        }
        if (VerificationDocumentKind.fromServiceKind(key.kind()) != doc.getDocumentKind()) {
            rollbackQuietly(txStatus);
            logRedactedFailure(doctor.getId(), doc.getDocumentKind(), correlationId,
                    "STORAGE_KEY_KIND_MISMATCH", null);
            return false;
        }
        if (!Objects.equals(key.version(), doc.getId())) {
            rollbackQuietly(txStatus);
            logRedactedFailure(doctor.getId(), doc.getDocumentKind(), correlationId,
                    "STORAGE_KEY_VERSION_MISMATCH", null);
            return false;
        }
        if (!constantTimeEquals(storageKey, key.toString())) {
            rollbackQuietly(txStatus);
            logRedactedFailure(doctor.getId(), doc.getDocumentKind(), correlationId,
                    "STORAGE_KEY_STRUCTURE_MISMATCH", null);
            return false;
        }

        // Delete physical file from storage; true = removed, false = already absent.
        boolean deleted;
        try {
            deleted = documentStorage.delete(key);
        } catch (IOException | RuntimeException e) {
            rollbackQuietly(txStatus);
            logRedactedFailure(doctor.getId(), doc.getDocumentKind(), correlationId,
                    "STORAGE_DELETE_FAILED", e.getClass().getName());
            return false;
        }

        // Mark catalog record purged and append DOCUMENT_PURGED audit event only after storage deletion succeeded.
        doc.markPurged(now);
        documentRepository.saveAndFlush(doc);

        String details = deleted
                ? "Purged expired " + doc.getDocumentKind().name() + " document after retention period"
                : "Finalized retention purge for expired " + doc.getDocumentKind().name()
                  + " document already absent from storage";
        DoctorVerificationDocumentEvent event = new DoctorVerificationDocumentEvent(
                DocumentEventType.DOCUMENT_PURGED,
                doctor,
                doc,
                doc.getDocumentKind(),
                null,
                VerificationActorRole.SYSTEM,
                DocumentEventOutcome.SUCCESS,
                correlationId,
                details,
                now
        );
        eventRepository.save(event);

        return true;
    }

    private static void rollbackQuietly(TransactionStatus txStatus) {
        if (txStatus != null) {
            txStatus.setRollbackOnly();
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static void logRedactedFailure(
            Long doctorId,
            VerificationDocumentKind kind,
            String correlationId,
            String reasonCode,
            String exceptionClass
    ) {
        log.warn(
                "Verification document retention purge blocked: doctorId={}, kind={}, correlationId={}, reason={}, exceptionClass={}",
                doctorId,
                kind != null ? kind.name() : "UNKNOWN",
                correlationId,
                reasonCode,
                exceptionClass != null ? exceptionClass : "NONE"
        );
    }
}

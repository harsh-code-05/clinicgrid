package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.dto.DoctorVerificationQueueItemResponse;
import com.harsh.hospitalmanagement.dto.DoctorVerificationQueueResponse;
import com.harsh.hospitalmanagement.dto.DoctorVerificationResponse;
import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.DoctorVerificationDocument;
import com.harsh.hospitalmanagement.entity.DoctorVerificationHistory;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.enums.VerificationActorRole;
import com.harsh.hospitalmanagement.enums.VerificationDocumentKind;
import com.harsh.hospitalmanagement.enums.VerificationStatus;
import com.harsh.hospitalmanagement.exception.BadRequestException;
import com.harsh.hospitalmanagement.exception.ConflictException;
import com.harsh.hospitalmanagement.exception.DocumentIntegrityException;
import com.harsh.hospitalmanagement.exception.ForbiddenException;
import com.harsh.hospitalmanagement.exception.ResourceNotFoundException;
import com.harsh.hospitalmanagement.repository.AppointmentSlotRepository;
import com.harsh.hospitalmanagement.repository.DoctorRepository;
import com.harsh.hospitalmanagement.repository.DoctorVerificationDocumentRepository;
import com.harsh.hospitalmanagement.repository.DoctorVerificationHistoryRepository;
import com.harsh.hospitalmanagement.storage.DocumentKey;
import com.harsh.hospitalmanagement.storage.DocumentStorage;
import com.harsh.hospitalmanagement.storage.VerificationImageValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.Year;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class DoctorVerificationService {

    private static final List<VerificationStatus> ADMIN_QUEUE_STATUSES = List.of(
            VerificationStatus.SUBMITTED,
            VerificationStatus.UNDER_REVIEW,
            VerificationStatus.REJECTED,
            VerificationStatus.VERIFIED
    );
    private static final int MAX_QUEUE_PAGE_SIZE = 100;

    private final DoctorManagementAccess access;
    private final DoctorVerificationHistoryRepository history;
    private final AppointmentSlotRepository slots;
    private final DoctorRepository doctors;
    private final DoctorVerificationDocumentRepository documents;
    private final DocumentStorage documentStorage;
    private final VerificationImageValidator imageValidator;

    DoctorVerificationService(DoctorManagementAccess access, DoctorVerificationHistoryRepository history,
                              AppointmentSlotRepository slots) {
        this(access, history, slots, null, null, null, null);
    }

    DoctorVerificationService(DoctorManagementAccess access, DoctorVerificationHistoryRepository history,
                              AppointmentSlotRepository slots, DoctorRepository doctors) {
        this(access, history, slots, doctors, null, null, null);
    }

    public DoctorVerificationService(DoctorManagementAccess access, DoctorVerificationHistoryRepository history,
                                     AppointmentSlotRepository slots, DoctorRepository doctors,
                                     DoctorVerificationDocumentRepository documents,
                                     @Autowired(required = false) DocumentStorage documentStorage) {
        this(access, history, slots, doctors, documents, documentStorage, null);
    }

    @Autowired
    public DoctorVerificationService(DoctorManagementAccess access, DoctorVerificationHistoryRepository history,
                                     AppointmentSlotRepository slots, DoctorRepository doctors,
                                     DoctorVerificationDocumentRepository documents,
                                     @Autowired(required = false) DocumentStorage documentStorage,
                                     @Autowired(required = false) VerificationImageValidator imageValidator) {
        this.access = access;
        this.history = history;
        this.slots = slots;
        this.doctors = doctors;
        this.documents = documents;
        this.documentStorage = documentStorage;
        this.imageValidator = imageValidator != null ? imageValidator : new VerificationImageValidator();
    }

    // Storage integrations pass server-issued immutable object keys after saving private evidence.
    public void replaceCredentials(Long doctorId, Credentials credentials, Authentication authentication) {
        replaceCredentials(requireDraft(doctorId, authentication), credentials);
    }

    public void updateRegistrationDetails(Long doctorId, String registrationNumber, String medicalCouncil,
                                          Integer registrationYear, Authentication authentication) {
        Doctor doctor = requireDraft(doctorId, authentication);
        // The HTTP client can edit registration details only for evidence already attached to this profile.
        replaceCredentials(doctor, new Credentials(registrationNumber, medicalCouncil, registrationYear,
                doctor.getIdProofDocumentPath(), doctor.getQualificationDocumentPath()));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DoctorVerificationResponse getVerification(Long doctorId, Authentication authentication) {
        Doctor doctor = access.requireManagerForRead(doctorId, authentication).doctor();
        List<DoctorVerificationHistory> historyEntities = history.findByDoctor_IdOrderByOccurredAtDescIdDesc(doctorId);
        var events = historyEntities.stream()
                .map(event -> new DoctorVerificationResponse.HistoryEntry(event.getPreviousStatus(),
                        event.getNewStatus(), event.getActorRole(), event.getOccurredAt()))
                .toList();
        return new DoctorVerificationResponse(
                doctor.getId(),
                doctor.getVerificationStatus(),
                doctor.getRegistrationNumber(),
                doctor.getMedicalCouncil(),
                doctor.getRegistrationYear(),
                extractLatestRejectionReason(historyEntities),
                computeReviewVersion(doctor, historyEntities),
                events
        );
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DoctorVerificationQueueResponse getAdminVerificationQueue(
            VerificationStatus statusFilter,
            int page,
            int size,
            Authentication authentication) {
        access.requireAdmin(authentication);
        if (doctors == null) {
            throw new IllegalStateException("DoctorRepository is required for admin verification queue");
        }
        if (page < 0) {
            throw new BadRequestException("Page index must be 0 or greater");
        }
        if (size < 1 || size > MAX_QUEUE_PAGE_SIZE) {
            throw new BadRequestException("Page size must be between 1 and " + MAX_QUEUE_PAGE_SIZE);
        }
        if (statusFilter == VerificationStatus.UNVERIFIED) {
            throw new BadRequestException("Status filter must be one of: SUBMITTED, UNDER_REVIEW, VERIFIED, REJECTED");
        }

        Collection<VerificationStatus> statuses = statusFilter != null
                ? List.of(statusFilter)
                : ADMIN_QUEUE_STATUSES;
        Page<Doctor> pageResult = doctors.findVerificationQueue(statuses, PageRequest.of(page, size));
        List<Doctor> content = pageResult.getContent();
        List<Long> doctorIds = content.stream().map(Doctor::getId).toList();

        Map<Long, List<DoctorVerificationHistory>> historyByDoctor = doctorIds.isEmpty()
                ? Map.of()
                : history.findByDoctor_IdInOrderByOccurredAtDescIdDesc(doctorIds).stream()
                        .collect(Collectors.groupingBy(
                                event -> event.getDoctor().getId(),
                                LinkedHashMap::new,
                                Collectors.toList()
                        ));

        List<DoctorVerificationQueueItemResponse> items = content.stream()
                .map(doctor -> toQueueItem(doctor, historyByDoctor.getOrDefault(doctor.getId(), List.of())))
                .toList();

        return new DoctorVerificationQueueResponse(
                items,
                pageResult.getNumber(),
                pageResult.getSize(),
                pageResult.getTotalElements(),
                pageResult.getTotalPages(),
                pageResult.hasNext(),
                pageResult.hasPrevious()
        );
    }

    private Doctor requireDraft(Long doctorId, Authentication authentication) {
        var manager = access.requireManager(doctorId, authentication);
        Doctor doctor = manager.doctor();
        if (manager.actor().getRole() != Role.DOCTOR) {
            throw new ForbiddenException("Only the doctor can provide verification evidence");
        }
        if (!isDraft(doctor.getVerificationStatus())) {
            throw new ConflictException("Evidence can only be replaced before submission or after rejection");
        }
        return doctor;
    }

    private void replaceCredentials(Doctor doctor, Credentials credentials) {
        if (credentials.registrationNumber() == null || credentials.registrationNumber().isBlank()
                || credentials.registrationNumber().strip().length() > 100
                || credentials.medicalCouncil() == null || credentials.medicalCouncil().isBlank()
                || credentials.medicalCouncil().strip().length() > 255
                || credentials.registrationYear() == null || credentials.registrationYear() < 1000
                || credentials.registrationYear() > Year.now().getValue()
                || !isOwnedKey(doctor.getId(), DocumentKind.ID_PROOF, credentials.idProofKey())
                || !isOwnedKey(doctor.getId(), DocumentKind.QUALIFICATION, credentials.qualificationKey())) {
            throw new BadRequestException("Valid registration details and doctor-owned verification evidence are required");
        }

        requireConsistentCatalogRecord(doctor.getId(), VerificationDocumentKind.ID_PROOF, credentials.idProofKey());
        requireConsistentCatalogRecord(doctor.getId(), VerificationDocumentKind.QUALIFICATION, credentials.qualificationKey());

        doctor.setRegistrationNumber(credentials.registrationNumber().strip());
        doctor.setMedicalCouncil(credentials.medicalCouncil().strip());
        doctor.setRegistrationYear(credentials.registrationYear());
        doctor.setIdProofDocumentPath(credentials.idProofKey());
        doctor.setQualificationDocumentPath(credentials.qualificationKey());
    }

    // The private storage adapter uses this reference internally. It is not part of any response DTO.
    public String authorizedDocumentKey(Long doctorId, DocumentKind kind, Authentication authentication) {
        Doctor doctor = access.requireManager(doctorId, authentication).doctor();
        String key = kind == DocumentKind.ID_PROOF
                ? doctor.getIdProofDocumentPath() : doctor.getQualificationDocumentPath();
        if (!isOwnedKey(doctorId, kind, key)) {
            throw new ResourceNotFoundException("Verification document not found");
        }
        return key;
    }

    public VerificationStatus transition(Long doctorId, VerificationStatus next, String notes,
                                         Authentication authentication) {
        if (next == VerificationStatus.UNDER_REVIEW
                || next == VerificationStatus.VERIFIED
                || next == VerificationStatus.REJECTED) {
            throw new BadRequestException("Review version is required for review, approval, or rejection decisions");
        }
        return transition(doctorId, next, notes, null, authentication);
    }

    public VerificationStatus transition(Long doctorId, VerificationStatus next, String notes,
                                         String expectedReviewVersion, Authentication authentication) {
        var manager = access.requireManager(doctorId, authentication);
        Doctor doctor = manager.doctor();
        VerificationStatus previous = doctor.getVerificationStatus();
        boolean owner = manager.actor().getRole() == Role.DOCTOR;
        if (owner ? next != VerificationStatus.SUBMITTED : next == VerificationStatus.SUBMITTED) {
            throw new ForbiddenException("This actor cannot make that verification decision");
        }

        String normalizedNotes = notes == null || notes.isBlank() ? null : notes.strip();
        if (next == VerificationStatus.REJECTED && normalizedNotes == null) {
            throw new BadRequestException("Rejection reason is required");
        }

        if (next == VerificationStatus.UNDER_REVIEW
                || next == VerificationStatus.VERIFIED
                || next == VerificationStatus.REJECTED) {
            if (expectedReviewVersion == null || expectedReviewVersion.isBlank()) {
                throw new BadRequestException("Review version is required");
            }
            List<DoctorVerificationHistory> existingHistory = history.findByDoctor_IdOrderByOccurredAtDescIdDesc(doctorId);
            String currentReviewVersion = computeReviewVersion(doctor, existingHistory);
            boolean matches = MessageDigest.isEqual(
                    currentReviewVersion.getBytes(StandardCharsets.UTF_8),
                    expectedReviewVersion.strip().getBytes(StandardCharsets.UTF_8)
            );
            if (!matches) {
                throw new ConflictException(
                        "STALE_REVIEW_VERSION",
                        "Verification state has changed since you loaded it. Refresh and review the latest details before deciding."
                );
            }
        }

        boolean allowed = switch (previous) {
            case UNVERIFIED, REJECTED -> next == VerificationStatus.SUBMITTED;
            case SUBMITTED, VERIFIED -> next == VerificationStatus.UNDER_REVIEW;
            case UNDER_REVIEW -> next == VerificationStatus.VERIFIED || next == VerificationStatus.REJECTED;
        };
        if (!allowed) {
            throw new ConflictException("Invalid verification status transition");
        }

        String idProof = doctor.getIdProofDocumentPath();
        String qualification = doctor.getQualificationDocumentPath();
        if (doctor.getRegistrationNumber() == null || doctor.getMedicalCouncil() == null
                || doctor.getRegistrationYear() == null || !isOwnedKey(doctorId, DocumentKind.ID_PROOF, idProof)
                || !isOwnedKey(doctorId, DocumentKind.QUALIFICATION, qualification)) {
            throw new BadRequestException("Registration details and both verification documents are required");
        }

        if (next == VerificationStatus.SUBMITTED || next == VerificationStatus.VERIFIED) {
            verifyEvidenceCatalogAndStorage(doctor);
        }

        doctor.setVerificationStatus(next);
        history.save(new DoctorVerificationHistory(doctor, manager.actor(),
                owner ? VerificationActorRole.DOCTOR : VerificationActorRole.ADMIN, previous, next, normalizedNotes,
                Map.of("idProofVersion", idProof.substring(idProof.lastIndexOf('/') + 1),
                        "qualificationVersion", qualification.substring(qualification.lastIndexOf('/') + 1))));
        if (previous == VerificationStatus.VERIFIED) {
            slots.retireVersionedOffers(doctorId);
        }
        return next;
    }

    private DoctorVerificationDocument requireConsistentCatalogRecord(
            Long doctorId,
            VerificationDocumentKind kind,
            String expectedStorageKey) {
        if (documents == null) {
            throw new IllegalStateException("DoctorVerificationDocumentRepository is required for catalog verification");
        }
        DoctorVerificationDocument doc = documents.findByDoctorIdAndDocumentKindAndRetiredAtIsNull(doctorId, kind)
                .orElseThrow(() -> new ConflictException(
                        "Active " + kind.name() + " verification document catalog record is missing"));

        if (!doc.isActive() || doc.isPurged()) {
            throw new ConflictException("Verification document is no longer active");
        }
        if (doc.getDoctor() == null
                || !Objects.equals(doc.getDoctor().getId(), doctorId)
                || doc.getDocumentKind() != kind) {
            throw new ConflictException("Verification document catalog ownership or kind mismatch");
        }

        DocumentKey parsedKey;
        try {
            parsedKey = DocumentKey.parse(doc.getStorageKey());
        } catch (IllegalArgumentException exception) {
            throw new DocumentIntegrityException("Verification document catalog reference is invalid", exception);
        }
        if (parsedKey.doctorId() != doctorId
                || parsedKey.kind() != kind.toServiceKind()
                || !Objects.equals(parsedKey.version(), doc.getId())) {
            throw new DocumentIntegrityException("Verification document catalog key does not match record metadata");
        }
        if (expectedStorageKey == null
                || !MessageDigest.isEqual(
                        doc.getStorageKey().getBytes(StandardCharsets.UTF_8),
                        expectedStorageKey.getBytes(StandardCharsets.UTF_8))) {
            throw new ConflictException(
                    "Doctor profile verification document reference does not match active catalog record");
        }
        return doc;
    }

    private void verifyEvidenceCatalogAndStorage(Doctor doctor) {
        DoctorVerificationDocumentService.verifyAndReadEvidence(
                doctor, VerificationDocumentKind.ID_PROOF, documents, documentStorage, imageValidator);
        DoctorVerificationDocumentService.verifyAndReadEvidence(
                doctor, VerificationDocumentKind.QUALIFICATION, documents, documentStorage, imageValidator);
    }

    private static DoctorVerificationQueueItemResponse toQueueItem(
            Doctor doctor,
            List<DoctorVerificationHistory> entries) {
        String fullName = Stream.of(doctor.getFirstName(), doctor.getLastName())
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .collect(Collectors.joining(" "));
        String doctorName = fullName.isEmpty() ? "Doctor #" + doctor.getId() : "Dr. " + fullName;
        String specialization = doctor.getSpecialization() != null ? doctor.getSpecialization().getName() : null;

        DoctorVerificationHistory latestEntry = entries.isEmpty() ? null : entries.getFirst();
        Instant submittedAt = entries.stream()
                .filter(entry -> entry.getNewStatus() == VerificationStatus.SUBMITTED)
                .map(DoctorVerificationHistory::getOccurredAt)
                .findFirst()
                .orElse(null);

        return new DoctorVerificationQueueItemResponse(
                doctor.getId(),
                doctorName,
                specialization,
                doctor.getVerificationStatus(),
                submittedAt,
                latestEntry != null ? latestEntry.getOccurredAt() : null,
                latestEntry != null ? latestEntry.getActorRole() : null,
                doctor.getRegistrationNumber(),
                doctor.getMedicalCouncil(),
                doctor.getRegistrationYear(),
                extractLatestRejectionReason(entries)
        );
    }

    private static String extractLatestRejectionReason(List<DoctorVerificationHistory> entries) {
        return entries.stream()
                .filter(entry -> entry.getNewStatus() == VerificationStatus.REJECTED
                        && entry.getNotes() != null && !entry.getNotes().isBlank())
                .map(entry -> entry.getNotes().strip())
                .findFirst()
                .orElse(null);
    }

    public static String computeReviewVersion(Doctor doctor, List<DoctorVerificationHistory> entries) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updatePart(digest, "hms-verification-review-v1");
            updatePart(digest, doctor.getId() != null ? doctor.getId().toString() : null);
            updatePart(digest, doctor.getVerificationStatus() != null ? doctor.getVerificationStatus().name() : null);
            updatePart(digest, doctor.getRegistrationNumber());
            updatePart(digest, doctor.getMedicalCouncil());
            updatePart(digest, doctor.getRegistrationYear() != null ? doctor.getRegistrationYear().toString() : null);
            updatePart(digest, versionSegment(doctor.getIdProofDocumentPath()));
            updatePart(digest, versionSegment(doctor.getQualificationDocumentPath()));
            updatePart(digest, Integer.toString(entries != null ? entries.size() : 0));

            if (entries != null && !entries.isEmpty()) {
                DoctorVerificationHistory latest = entries.getFirst();
                updatePart(digest, latest.getId() != null ? latest.getId().toString() : null);
                updatePart(digest, latest.getPreviousStatus() != null ? latest.getPreviousStatus().name() : null);
                updatePart(digest, latest.getNewStatus() != null ? latest.getNewStatus().name() : null);
                updatePart(digest, latest.getActorRole() != null ? latest.getActorRole().name() : null);
                Map<String, String> metadata = latest.getEvidenceMetadata();
                if (metadata != null && !metadata.isEmpty()) {
                    metadata.entrySet().stream()
                            .sorted(Map.Entry.comparingByKey(Comparator.nullsFirst(String::compareTo)))
                            .forEach(entry -> {
                                updatePart(digest, entry.getKey());
                                updatePart(digest, entry.getValue());
                            });
                }
            }

            byte[] hash = digest.digest();
            var hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0x0f, 16));
                hex.append(Character.forDigit(b & 0x0f, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", exception);
        }
    }

    private static void updatePart(MessageDigest digest, String value) {
        if (value == null) {
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(-1).array());
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static String versionSegment(String key) {
        if (key == null) {
            return null;
        }
        int lastSlash = key.lastIndexOf('/');
        return lastSlash >= 0 ? key.substring(lastSlash + 1) : key;
    }

    private boolean isDraft(VerificationStatus status) {
        return status == VerificationStatus.UNVERIFIED || status == VerificationStatus.REJECTED;
    }

    private boolean isOwnedKey(Long doctorId, DocumentKind kind, String key) {
        return key != null && key.matches("verification/" + doctorId + "/" + kind.segment
                + "/[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    }

    public enum DocumentKind {
        ID_PROOF("id-proof"), QUALIFICATION("qualification");

        private final String segment;

        DocumentKind(String segment) {
            this.segment = segment;
        }

        public String pathSegment() {
            return segment;
        }
    }

    public record Credentials(String registrationNumber, String medicalCouncil, Integer registrationYear,
                              String idProofKey, String qualificationKey) {
    }
}

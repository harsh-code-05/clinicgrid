package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.DoctorVerificationDocument;
import com.harsh.hospitalmanagement.entity.DoctorVerificationHistory;
import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.enums.VerificationActorRole;
import com.harsh.hospitalmanagement.enums.VerificationDocumentKind;
import com.harsh.hospitalmanagement.enums.VerificationStatus;
import com.harsh.hospitalmanagement.exception.BadRequestException;
import com.harsh.hospitalmanagement.exception.ConflictException;
import com.harsh.hospitalmanagement.exception.DocumentIntegrityException;
import com.harsh.hospitalmanagement.exception.DocumentStorageUnavailableException;
import com.harsh.hospitalmanagement.repository.AppointmentSlotRepository;
import com.harsh.hospitalmanagement.repository.DoctorRepository;
import com.harsh.hospitalmanagement.repository.DoctorVerificationDocumentRepository;
import com.harsh.hospitalmanagement.repository.DoctorVerificationHistoryRepository;
import com.harsh.hospitalmanagement.storage.DocumentKey;
import com.harsh.hospitalmanagement.storage.DocumentStorage;
import com.harsh.hospitalmanagement.storage.VerificationImageValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DoctorVerificationServiceTest {

    private static final long DOCTOR_ID = 42L;
    private static final UUID ID_PROOF_UUID = UUID.fromString("10a497ff-716b-44bb-9a89-e9857ec03e60");
    private static final UUID QUALIFICATION_UUID = UUID.fromString("c229731e-3cc0-4eb3-89fb-b61a63e87f56");
    private static final String ID_PROOF_KEY = "verification/42/id-proof/" + ID_PROOF_UUID;
    private static final String QUALIFICATION_KEY = "verification/42/qualification/" + QUALIFICATION_UUID;

    @Mock private DoctorManagementAccess access;
    @Mock private DoctorVerificationHistoryRepository history;
    @Mock private AppointmentSlotRepository slots;
    @Mock private DoctorRepository doctors;
    @Mock private DoctorVerificationDocumentRepository documents;
    @Mock private DocumentStorage documentStorage;
    @Mock private Authentication authentication;

    private DoctorVerificationService service;
    private Doctor doctor;
    private User actor;
    private DoctorVerificationDocument idProofDoc;
    private DoctorVerificationDocument qualificationDoc;
    private VerificationImageValidator.ValidatedImage validPng;

    @BeforeEach
    void setUp() throws IOException {
        service = new DoctorVerificationService(access, history, slots, doctors, documents, documentStorage);
        validPng = new VerificationImageValidator().validate(
                "image/png", new ByteArrayInputStream(createPngBytes()));
        doctor = new Doctor();
        doctor.setId(DOCTOR_ID);
        doctor.setRegistrationNumber("REG-OLD");
        doctor.setMedicalCouncil("Original Council");
        doctor.setRegistrationYear(2019);
        doctor.setIdProofDocumentPath(ID_PROOF_KEY);
        doctor.setQualificationDocumentPath(QUALIFICATION_KEY);
        actor = new User();
        actor.setRole(Role.DOCTOR);

        idProofDoc = new DoctorVerificationDocument(
                ID_PROOF_UUID, doctor, VerificationDocumentKind.ID_PROOF, ID_PROOF_KEY,
                validPng.contentType(), validPng.size(), validPng.sha256Hex(), Instant.now());
        qualificationDoc = new DoctorVerificationDocument(
                QUALIFICATION_UUID, doctor, VerificationDocumentKind.QUALIFICATION, QUALIFICATION_KEY,
                validPng.contentType(), validPng.size(), validPng.sha256Hex(), Instant.now());

        when(access.requireManager(DOCTOR_ID, authentication))
                .thenReturn(new DoctorManagementAccess.Access(doctor, actor));
        lenient().when(documents.findByDoctorIdAndDocumentKindAndRetiredAtIsNull(DOCTOR_ID, VerificationDocumentKind.ID_PROOF))
                .thenReturn(Optional.of(idProofDoc));
        lenient().when(documents.findByDoctorIdAndDocumentKindAndRetiredAtIsNull(DOCTOR_ID, VerificationDocumentKind.QUALIFICATION))
                .thenReturn(Optional.of(qualificationDoc));
        lenient().when(documentStorage.exists(any(DocumentKey.class))).thenReturn(true);
        lenient().when(documentStorage.read(DocumentKey.parse(ID_PROOF_KEY)))
                .thenAnswer(inv -> new DocumentStorage.ReadDocument(
                        validPng.contentType(), "id-proof.png", validPng.copyBytes()));
        lenient().when(documentStorage.read(DocumentKey.parse(QUALIFICATION_KEY)))
                .thenAnswer(inv -> new DocumentStorage.ReadDocument(
                        validPng.contentType(), "qualification.png", validPng.copyBytes()));
    }

    @Test
    void registrationEditReusesExistingEvidenceAndDoesNotSubmit() {
        service.updateRegistrationDetails(DOCTOR_ID, "  REG-NEW  ", "  Council  ", 2020, authentication);

        assertThat(doctor.getRegistrationNumber()).isEqualTo("REG-NEW");
        assertThat(doctor.getMedicalCouncil()).isEqualTo("Council");
        assertThat(doctor.getRegistrationYear()).isEqualTo(2020);
        assertThat(doctor.getIdProofDocumentPath()).isEqualTo(ID_PROOF_KEY);
        assertThat(doctor.getQualificationDocumentPath()).isEqualTo(QUALIFICATION_KEY);
        assertThat(doctor.getVerificationStatus()).isEqualTo(VerificationStatus.UNVERIFIED);
        verifyNoInteractions(history, slots);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "verification/43/id-proof/10a497ff-716b-44bb-9a89-e9857ec03e60",
            "verification/42/qualification/10a497ff-716b-44bb-9a89-e9857ec03e60",
            "verification/42/id-proof/../10a497ff-716b-44bb-9a89-e9857ec03e60",
            "verification/42/id-proof/10a497ff-716b-44bb-9a89-e9857ec03e60.exe"
    })
    void missingForeignOrMalformedEvidenceBlocksEditingAndSubmission(String key) {
        doctor.setIdProofDocumentPath(key);

        assertThatThrownBy(() -> service.updateRegistrationDetails(
                DOCTOR_ID, "REG-NEW", "Council", 2020, authentication))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.transition(DOCTOR_ID, VerificationStatus.SUBMITTED, null, authentication))
                .isInstanceOf(BadRequestException.class);

        assertThat(doctor.getRegistrationNumber()).isEqualTo("REG-OLD");
        assertThat(doctor.getVerificationStatus()).isEqualTo(VerificationStatus.UNVERIFIED);
        verifyNoInteractions(history, slots);
    }

    @Test
    void retiredOrMismatchedCatalogRecordFailsClosedDuringCredentialsUpdateAndSubmission() {
        idProofDoc.retire(Instant.now(), Instant.now().plus(Duration.ofDays(30)));

        assertThatThrownBy(() -> service.updateRegistrationDetails(
                DOCTOR_ID, "REG-NEW", "Council", 2020, authentication))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.transition(DOCTOR_ID, VerificationStatus.SUBMITTED, null, authentication))
                .isInstanceOf(ConflictException.class);

        assertThat(doctor.getRegistrationNumber()).isEqualTo("REG-OLD");
        verifyNoInteractions(history, slots);
    }

    @Test
    void storageFailureAndMissingBlobFailClosedWithoutLeakingKeyOrChecksum() throws IOException {
        when(documentStorage.exists(DocumentKey.parse(ID_PROOF_KEY))).thenReturn(false);
        assertThatThrownBy(() -> service.transition(DOCTOR_ID, VerificationStatus.SUBMITTED, null, authentication))
                .isInstanceOf(ConflictException.class)
                .hasMessageNotContaining(ID_PROOF_KEY);

        when(documentStorage.exists(any())).thenReturn(true);
        doThrow(new IOException("Simulated GCM auth tag mismatch " + "b".repeat(64)))
                .when(documentStorage).read(DocumentKey.parse(QUALIFICATION_KEY));

        assertThatThrownBy(() -> service.transition(DOCTOR_ID, VerificationStatus.SUBMITTED, null, authentication))
                .isInstanceOf(DocumentIntegrityException.class)
                .hasMessageNotContaining(QUALIFICATION_KEY)
                .hasMessageNotContaining("b".repeat(16));

        var disabledService = new DoctorVerificationService(access, history, slots, doctors, documents, null);
        assertThatThrownBy(() -> disabledService.transition(DOCTOR_ID, VerificationStatus.SUBMITTED, null, authentication))
                .isInstanceOf(DocumentStorageUnavailableException.class);
    }

    @Test
    void tamperedEvidenceMetadataOrImageStructureFailsClosedOnSubmissionAndApproval() throws IOException {
        // 1. Mismatched SHA-256 checksum on submission & approval
        String wrongChecksum = "0".repeat(64);
        DoctorVerificationDocument checksumTamperedQual = new DoctorVerificationDocument(
                QUALIFICATION_UUID, doctor, VerificationDocumentKind.QUALIFICATION, QUALIFICATION_KEY,
                validPng.contentType(), validPng.size(), wrongChecksum, Instant.now());
        when(documents.findByDoctorIdAndDocumentKindAndRetiredAtIsNull(DOCTOR_ID, VerificationDocumentKind.QUALIFICATION))
                .thenReturn(Optional.of(checksumTamperedQual));
        assertTamperedFailsClosedOnSubmitAndApprove(wrongChecksum.substring(0, 16), QUALIFICATION_KEY);

        // 2. Mismatched file size on submission & approval
        DoctorVerificationDocument sizeMismatchQual = new DoctorVerificationDocument(
                QUALIFICATION_UUID, doctor, VerificationDocumentKind.QUALIFICATION, QUALIFICATION_KEY,
                validPng.contentType(), validPng.size() + 50, validPng.sha256Hex(), Instant.now());
        when(documents.findByDoctorIdAndDocumentKindAndRetiredAtIsNull(DOCTOR_ID, VerificationDocumentKind.QUALIFICATION))
                .thenReturn(Optional.of(sizeMismatchQual));
        assertTamperedFailsClosedOnSubmitAndApprove(validPng.sha256Hex().substring(0, 16), QUALIFICATION_KEY);

        // Restore consistent catalog record for MIME & corrupt structure checks
        when(documents.findByDoctorIdAndDocumentKindAndRetiredAtIsNull(DOCTOR_ID, VerificationDocumentKind.QUALIFICATION))
                .thenReturn(Optional.of(qualificationDoc));

        // 3. Mismatched MIME type from storage on submission & approval
        when(documentStorage.read(DocumentKey.parse(QUALIFICATION_KEY)))
                .thenAnswer(inv -> new DocumentStorage.ReadDocument("image/jpeg", "qual.jpg", validPng.copyBytes()));
        assertTamperedFailsClosedOnSubmitAndApprove(validPng.sha256Hex().substring(0, 16), QUALIFICATION_KEY);

        // 4. Corrupt image structure even when file size, MIME type, and SHA-256 checksum all match catalog
        byte[] corruptBytes = "NOT-A-VALID-PNG-PAYLOAD-BUT-SIZE-AND-HASH-MATCH".getBytes(StandardCharsets.UTF_8);
        String corruptSha = sha256Hex(corruptBytes);
        DoctorVerificationDocument corruptCatalogQual = new DoctorVerificationDocument(
                QUALIFICATION_UUID, doctor, VerificationDocumentKind.QUALIFICATION, QUALIFICATION_KEY,
                "image/png", corruptBytes.length, corruptSha, Instant.now());
        when(documents.findByDoctorIdAndDocumentKindAndRetiredAtIsNull(DOCTOR_ID, VerificationDocumentKind.QUALIFICATION))
                .thenReturn(Optional.of(corruptCatalogQual));
        when(documentStorage.read(DocumentKey.parse(QUALIFICATION_KEY)))
                .thenAnswer(inv -> new DocumentStorage.ReadDocument("image/png", "qual.png", corruptBytes.clone()));
        assertTamperedFailsClosedOnSubmitAndApprove(corruptSha.substring(0, 16), QUALIFICATION_KEY);
    }

    private void assertTamperedFailsClosedOnSubmitAndApprove(String secretChecksumFragment, String secretKey) {
        doctor.setVerificationStatus(VerificationStatus.UNVERIFIED);
        actor.setRole(Role.DOCTOR);
        assertThatThrownBy(() -> service.transition(DOCTOR_ID, VerificationStatus.SUBMITTED, null, authentication))
                .isInstanceOf(DocumentIntegrityException.class)
                .hasMessageNotContaining(secretChecksumFragment)
                .hasMessageNotContaining(secretKey);
        assertThat(doctor.getVerificationStatus()).isEqualTo(VerificationStatus.UNVERIFIED);

        doctor.setVerificationStatus(VerificationStatus.UNDER_REVIEW);
        actor.setRole(Role.ADMIN);
        lenient().when(history.findByDoctor_IdOrderByOccurredAtDescIdDesc(DOCTOR_ID)).thenReturn(List.of());
        String version = DoctorVerificationService.computeReviewVersion(doctor, List.of());
        assertThatThrownBy(() -> service.transition(DOCTOR_ID, VerificationStatus.VERIFIED, null, version, authentication))
                .isInstanceOf(DocumentIntegrityException.class)
                .hasMessageNotContaining(secretChecksumFragment)
                .hasMessageNotContaining(secretKey);
        assertThat(doctor.getVerificationStatus()).isEqualTo(VerificationStatus.UNDER_REVIEW);
    }

    @Test
    void reopeningVerifiedDoctorRecordsAdminTransitionAndRetiresOffers() {
        doctor.setVerificationStatus(VerificationStatus.VERIFIED);
        actor.setRole(Role.ADMIN);
        when(history.findByDoctor_IdOrderByOccurredAtDescIdDesc(DOCTOR_ID)).thenReturn(List.of());
        String expectedVersion = DoctorVerificationService.computeReviewVersion(doctor, List.of());

        assertThat(service.transition(DOCTOR_ID, VerificationStatus.UNDER_REVIEW, null, expectedVersion, authentication))
                .isEqualTo(VerificationStatus.UNDER_REVIEW);

        var event = ArgumentCaptor.forClass(DoctorVerificationHistory.class);
        verify(history).save(event.capture());
        assertThat(event.getValue().getActorUser()).isSameAs(actor);
        assertThat(event.getValue().getActorRole()).isEqualTo(VerificationActorRole.ADMIN);
        assertThat(event.getValue().getPreviousStatus()).isEqualTo(VerificationStatus.VERIFIED);
        assertThat(event.getValue().getNewStatus()).isEqualTo(VerificationStatus.UNDER_REVIEW);
        verify(slots).retireVersionedOffers(DOCTOR_ID);
    }

    @Test
    void reviewTransitionRejectsMissingOrStaleVersionBeforeRetiringOffers() {
        doctor.setVerificationStatus(VerificationStatus.VERIFIED);
        actor.setRole(Role.ADMIN);
        lenient().when(history.findByDoctor_IdOrderByOccurredAtDescIdDesc(DOCTOR_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.transition(DOCTOR_ID, VerificationStatus.UNDER_REVIEW, null, authentication))
                .isInstanceOf(BadRequestException.class);

        assertThatThrownBy(() -> service.transition(DOCTOR_ID, VerificationStatus.UNDER_REVIEW, null, " ", authentication))
                .isInstanceOf(BadRequestException.class);

        assertThatThrownBy(() -> service.transition(
                DOCTOR_ID, VerificationStatus.UNDER_REVIEW, null, "stale-version-token", authentication))
                .isInstanceOfSatisfying(ConflictException.class, ex -> assertThat(ex.getCode()).isEqualTo("STALE_REVIEW_VERSION"));

        assertThat(doctor.getVerificationStatus()).isEqualTo(VerificationStatus.VERIFIED);
        verify(slots, never()).retireVersionedOffers(any());
        verify(history, never()).save(any());
    }

    private static byte[] createPngBytes() throws IOException {
        var image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        image.setRGB(2, 2, Color.BLUE.getRGB());
        var bytes = new ByteArrayOutputStream();
        try (var stream = new MemoryCacheImageOutputStream(bytes)) {
            ImageIO.write(image, "png", stream);
        }
        return bytes.toByteArray();
    }

    private static String sha256Hex(byte[] input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input);
            var hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0x0f, 16));
                hex.append(Character.forDigit(b & 0x0f, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.dto.DoctorUpdateRequest;
import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.Specialization;
import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.enums.VerificationDocumentKind;
import com.harsh.hospitalmanagement.enums.VerificationStatus;
import com.harsh.hospitalmanagement.repository.DoctorRepository;
import com.harsh.hospitalmanagement.repository.DoctorVerificationDocumentEventRepository;
import com.harsh.hospitalmanagement.repository.DoctorVerificationDocumentRepository;
import com.harsh.hospitalmanagement.repository.DoctorVerificationHistoryRepository;
import com.harsh.hospitalmanagement.repository.SpecializationRepository;
import com.harsh.hospitalmanagement.repository.UserRepository;
import com.harsh.hospitalmanagement.security.LocalAccount;
import com.harsh.hospitalmanagement.storage.DocumentKey;
import com.harsh.hospitalmanagement.storage.DocumentStorage;
import com.harsh.hospitalmanagement.storage.VerificationImageValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:doc_profile_concurrency;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.open-in-view=false",
        "spring.sql.init.mode=never"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DoctorProfileUpdateConcurrencyTest {

    @Autowired private DoctorService doctorService;
    @Autowired private DoctorVerificationService verificationService;
    @Autowired private DoctorVerificationDocumentService documentService;
    @Autowired private DoctorRepository doctors;
    @Autowired private UserRepository users;
    @Autowired private SpecializationRepository specializations;
    @Autowired private DoctorVerificationDocumentRepository documents;
    @Autowired private DoctorVerificationDocumentEventRepository documentEvents;
    @Autowired private DoctorVerificationHistoryRepository history;
    @Autowired private PlatformTransactionManager transactionManager;

    @MockitoBean private DocumentStorage documentStorage;

    private byte[] validPngBytes;

    @BeforeEach
    void setUp() throws IOException {
        reset(documentStorage);
        validPngBytes = createPngBytes();
        when(documentStorage.exists(any())).thenReturn(true);
        when(documentStorage.read(any())).thenAnswer(invocation ->
                new DocumentStorage.ReadDocument("image/png", "evidence.png", validPngBytes.clone()));
    }

    @Test
    void updateDoctorHoldingLockFirstBlocksVerificationApprovalAndPreservesBothUpdates() throws Exception {
        Fixture fixture = createDoctorFixture(true);
        Specialization newSpec = createSpecialization("Cardiology");
        String reviewVersion = verificationService.getVerification(fixture.doctor().getId(), auth(fixture.admin()))
                .reviewVersion();

        TransactionTemplate tx = readCommittedTx();
        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch contenderStarted = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);

        try {
            DoctorUpdateRequest updateRequest = profileUpdateRequest(newSpec.getId(), "UpdatedFirst", "UpdatedLast", "MD-FACC", 14);
            var updateFuture = executor.submit(() -> tx.execute(ignored -> {
                Doctor updated = doctorService.updateDoctor(fixture.doctor().getId(), updateRequest, "admin@example.invalid");
                lockAcquired.countDown();
                await(releaseFirst);
                return updated;
            }));
            await(lockAcquired);

            var verifyFuture = executor.submit(() -> {
                contenderStarted.countDown();
                return verificationService.transition(
                        fixture.doctor().getId(),
                        VerificationStatus.VERIFIED,
                        null,
                        reviewVersion,
                        auth(fixture.admin())
                );
            });
            await(contenderStarted);

            assertThrows(TimeoutException.class, () -> verifyFuture.get(250, TimeUnit.MILLISECONDS),
                    "Verification transition must wait while DoctorService.updateDoctor holds the doctor lock");

            releaseFirst.countDown();
            updateFuture.get(10, TimeUnit.SECONDS);
            assertThat(verifyFuture.get(10, TimeUnit.SECONDS)).isEqualTo(VerificationStatus.VERIFIED);

            Doctor persisted = doctors.findById(fixture.doctor().getId()).orElseThrow();
            assertThat(persisted.getFirstName()).isEqualTo("UpdatedFirst");
            assertThat(persisted.getLastName()).isEqualTo("UpdatedLast");
            assertThat(persisted.getQualification()).isEqualTo("MD-FACC");
            assertThat(persisted.getExperience()).isEqualTo(14);
            assertThat(persisted.getSpecialization().getId()).isEqualTo(newSpec.getId());
            assertThat(persisted.getVerificationStatus()).isEqualTo(VerificationStatus.VERIFIED);
            assertThat(persisted.getRegistrationNumber()).isEqualTo("REG-PROF-1");
            assertThat(persisted.getMedicalCouncil()).isEqualTo("National Medical Commission");
            assertThat(persisted.getRegistrationYear()).isEqualTo(2018);
            assertThat(persisted.getIdProofDocumentPath()).isEqualTo(fixture.idProofKey().toString());
            assertThat(persisted.getQualificationDocumentPath()).isEqualTo(fixture.qualKey().toString());
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void verificationApprovalHoldingLockFirstBlocksUpdateDoctorAndProfileUpdateDoesNotOverwriteVerification() throws Exception {
        Fixture fixture = createDoctorFixture(true);
        Specialization newSpec = createSpecialization("Neurology");
        String reviewVersion = verificationService.getVerification(fixture.doctor().getId(), auth(fixture.admin()))
                .reviewVersion();

        TransactionTemplate tx = readCommittedTx();
        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch contenderStarted = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);

        try {
            var verifyFuture = executor.submit(() -> tx.execute(ignored -> {
                VerificationStatus next = verificationService.transition(
                        fixture.doctor().getId(),
                        VerificationStatus.VERIFIED,
                        null,
                        reviewVersion,
                        auth(fixture.admin())
                );
                lockAcquired.countDown();
                await(releaseFirst);
                return next;
            }));
            await(lockAcquired);

            DoctorUpdateRequest updateRequest = profileUpdateRequest(newSpec.getId(), "SecondFirst", "SecondLast", "DM-Neuro", 9);
            var updateFuture = executor.submit(() -> {
                contenderStarted.countDown();
                return doctorService.updateDoctor(fixture.doctor().getId(), updateRequest, "admin@example.invalid");
            });
            await(contenderStarted);

            assertThrows(TimeoutException.class, () -> updateFuture.get(250, TimeUnit.MILLISECONDS),
                    "DoctorService.updateDoctor must wait while verification transition holds the doctor lock");

            releaseFirst.countDown();
            assertThat(verifyFuture.get(10, TimeUnit.SECONDS)).isEqualTo(VerificationStatus.VERIFIED);
            updateFuture.get(10, TimeUnit.SECONDS);

            Doctor persisted = doctors.findById(fixture.doctor().getId()).orElseThrow();
            assertThat(persisted.getFirstName()).isEqualTo("SecondFirst");
            assertThat(persisted.getLastName()).isEqualTo("SecondLast");
            assertThat(persisted.getQualification()).isEqualTo("DM-Neuro");
            assertThat(persisted.getExperience()).isEqualTo(9);
            assertThat(persisted.getSpecialization().getId()).isEqualTo(newSpec.getId());
            assertThat(persisted.getVerificationStatus()).isEqualTo(VerificationStatus.VERIFIED);
            assertThat(persisted.getRegistrationNumber()).isEqualTo("REG-PROF-1");
            assertThat(persisted.getIdProofDocumentPath()).isEqualTo(fixture.idProofKey().toString());
            assertThat(persisted.getQualificationDocumentPath()).isEqualTo(fixture.qualKey().toString());
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void documentAttachmentAndUpdateDoctorSerializeCleanlyInBothOrderings() throws Exception {
        // Ordering 1: updateDoctor holds lock first, attachDocument blocks then commits with both updates intact
        Fixture draft1 = createDoctorFixture(false);
        DocumentKey replacementKey1 = new DocumentKey(draft1.doctor().getId(), DoctorVerificationService.DocumentKind.ID_PROOF, UUID.randomUUID());
        when(documentStorage.store(eq(draft1.doctor().getId()), eq(DoctorVerificationService.DocumentKind.ID_PROOF), any()))
                .thenReturn(replacementKey1);
        var validator = new VerificationImageValidator();
        var validatedImg = validator.validate("image/png", new ByteArrayInputStream(validPngBytes));

        runLockRace(
                tx -> tx.execute(ignored -> doctorService.updateDoctor(
                        draft1.doctor().getId(),
                        profileUpdateRequest(draft1.doctor().getSpecialization().getId(), "RaceA", "Doc", "MBBS", 6),
                        "admin@example.invalid")),
                () -> documentService.attachDocument(
                        draft1.doctor().getId(), VerificationDocumentKind.ID_PROOF, validatedImg, auth(draft1.doctor().getUser()), "corr-1")
        );

        Doctor reloaded1 = doctors.findById(draft1.doctor().getId()).orElseThrow();
        assertThat(reloaded1.getFirstName()).isEqualTo("RaceA");
        assertThat(reloaded1.getIdProofDocumentPath()).isEqualTo(replacementKey1.toString());

        // Ordering 2: attachDocument holds lock first, updateDoctor blocks then commits without clobbering new document pointer
        Fixture draft2 = createDoctorFixture(false);
        DocumentKey replacementKey2 = new DocumentKey(draft2.doctor().getId(), DoctorVerificationService.DocumentKind.ID_PROOF, UUID.randomUUID());
        when(documentStorage.store(eq(draft2.doctor().getId()), eq(DoctorVerificationService.DocumentKind.ID_PROOF), any()))
                .thenReturn(replacementKey2);

        runLockRace(
                tx -> tx.execute(ignored -> documentService.attachDocument(
                        draft2.doctor().getId(), VerificationDocumentKind.ID_PROOF, validatedImg, auth(draft2.doctor().getUser()), "corr-2")),
                () -> doctorService.updateDoctor(
                        draft2.doctor().getId(),
                        profileUpdateRequest(draft2.doctor().getSpecialization().getId(), "RaceB", "Doc", "MS", 11),
                        "admin@example.invalid")
        );

        Doctor reloaded2 = doctors.findById(draft2.doctor().getId()).orElseThrow();
        assertThat(reloaded2.getFirstName()).isEqualTo("RaceB");
        assertThat(reloaded2.getQualification()).isEqualTo("MS");
        assertThat(reloaded2.getIdProofDocumentPath()).isEqualTo(replacementKey2.toString());
    }

    @Test
    void saveDoctorDetachedInstanceOnlyUpdatesProfileFieldsUnderLock() throws IOException {
        Fixture fixture = createDoctorFixture(true);
        String version = verificationService.getVerification(fixture.doctor().getId(), auth(fixture.admin()))
                .reviewVersion();
        verificationService.transition(fixture.doctor().getId(), VerificationStatus.VERIFIED, null, version, auth(fixture.admin()));

        // Simulate stale detached snapshot loaded before verification completed
        Doctor staleDetached = new Doctor();
        staleDetached.setId(fixture.doctor().getId());
        staleDetached.setFirstName("StaleSaveFirst");
        staleDetached.setLastName("StaleSaveLast");
        staleDetached.setQualification("MD-Updated");
        staleDetached.setExperience(20);
        staleDetached.setBio("Updated bio");
        staleDetached.setVerificationStatus(VerificationStatus.UNVERIFIED);
        staleDetached.setIdProofDocumentPath(null);
        staleDetached.setQualificationDocumentPath("forged/key");
        staleDetached.setRegistrationNumber("FORGED");

        doctorService.saveDoctor(staleDetached);

        Doctor persisted = doctors.findById(fixture.doctor().getId()).orElseThrow();
        assertThat(persisted.getFirstName()).isEqualTo("StaleSaveFirst");
        assertThat(persisted.getBio()).isEqualTo("Updated bio");
        assertThat(persisted.getVerificationStatus()).isEqualTo(VerificationStatus.VERIFIED);
        assertThat(persisted.getRegistrationNumber()).isEqualTo("REG-PROF-1");
        assertThat(persisted.getIdProofDocumentPath()).isEqualTo(fixture.idProofKey().toString());
        assertThat(persisted.getQualificationDocumentPath()).isEqualTo(fixture.qualKey().toString());
    }

    private void runLockRace(
            java.util.function.Function<TransactionTemplate, ?> firstInTx,
            java.util.concurrent.Callable<?> second) throws Exception {
        TransactionTemplate tx = readCommittedTx();
        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch contenderStarted = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);

        try {
            var firstFuture = executor.submit(() -> tx.execute(ignored -> {
                Object res = firstInTx.apply(tx);
                lockAcquired.countDown();
                await(releaseFirst);
                return res;
            }));
            await(lockAcquired);

            var secondFuture = executor.submit(() -> {
                contenderStarted.countDown();
                return second.call();
            });
            await(contenderStarted);

            assertThrows(TimeoutException.class, () -> secondFuture.get(250, TimeUnit.MILLISECONDS),
                    "Concurrent writer must block on doctor row lock");

            releaseFirst.countDown();
            firstFuture.get(10, TimeUnit.SECONDS);
            secondFuture.get(10, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Fixture createDoctorFixture(boolean advanceToUnderReview) throws IOException {
        Specialization spec = createSpecialization("General Medicine");
        User doctorUser = user(Role.DOCTOR);
        User admin = user(Role.ADMIN);

        Doctor doc = new Doctor();
        doc.setFirstName("Initial");
        doc.setLastName("Doctor");
        doc.setQualification("MBBS");
        doc.setExperience(5);
        doc.setUser(doctorUser);
        doc.setSpecialization(spec);
        doc = doctors.save(doc);

        DocumentKey idProofKey = new DocumentKey(doc.getId(), DoctorVerificationService.DocumentKind.ID_PROOF, UUID.randomUUID());
        DocumentKey qualKey = new DocumentKey(doc.getId(), DoctorVerificationService.DocumentKind.QUALIFICATION, UUID.randomUUID());
        when(documentStorage.store(eq(doc.getId()), eq(DoctorVerificationService.DocumentKind.ID_PROOF), any()))
                .thenReturn(idProofKey);
        when(documentStorage.store(eq(doc.getId()), eq(DoctorVerificationService.DocumentKind.QUALIFICATION), any()))
                .thenReturn(qualKey);

        var validator = new VerificationImageValidator();
        var validatedIdProof = validator.validate("image/png", new ByteArrayInputStream(validPngBytes));
        var validatedQual = validator.validate("image/png", new ByteArrayInputStream(validPngBytes));

        documentService.attachDocument(doc.getId(), VerificationDocumentKind.ID_PROOF, validatedIdProof, auth(doctorUser), "setup-id");
        documentService.attachDocument(doc.getId(), VerificationDocumentKind.QUALIFICATION, validatedQual, auth(doctorUser), "setup-qual");
        verificationService.updateRegistrationDetails(doc.getId(), "REG-PROF-1", "National Medical Commission", 2018, auth(doctorUser));

        if (advanceToUnderReview) {
            verificationService.transition(doc.getId(), VerificationStatus.SUBMITTED, null, auth(doctorUser));
            String submittedVersion = verificationService.getVerification(doc.getId(), auth(admin)).reviewVersion();
            verificationService.transition(doc.getId(), VerificationStatus.UNDER_REVIEW, null, submittedVersion, auth(admin));
        }

        return new Fixture(doc, idProofKey, qualKey, admin);
    }

    private TransactionTemplate readCommittedTx() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return tx;
    }

    private Specialization createSpecialization(String prefix) {
        Specialization spec = new Specialization();
        spec.setName(prefix + " " + UUID.randomUUID());
        return specializations.save(spec);
    }

    private User user(Role role) {
        User u = new User();
        u.setEmail("profile-conc-" + UUID.randomUUID() + "@example.invalid");
        u.setRole(role);
        return users.save(u);
    }

    private static DoctorUpdateRequest profileUpdateRequest(
            Long specId, String firstName, String lastName, String qualification, int experience) {
        DoctorUpdateRequest request = new DoctorUpdateRequest();
        request.setSpecializationId(specId);
        request.setFirstName(firstName);
        request.setLastName(lastName);
        request.setQualification(qualification);
        request.setExperience(experience);
        return request;
    }

    private static Authentication auth(User user) {
        var principal = new LocalAccount.PasswordPrincipal(
                new LocalAccount(user.getId(), user.getEmail()), "unused", user.getRole());
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while coordinating concurrency test", exception);
        }
    }

    private static byte[] createPngBytes() throws IOException {
        var image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        image.setRGB(3, 3, Color.CYAN.getRGB());
        var bytes = new ByteArrayOutputStream();
        try (var stream = new MemoryCacheImageOutputStream(bytes)) {
            ImageIO.write(image, "png", stream);
        }
        return bytes.toByteArray();
    }

    private record Fixture(Doctor doctor, DocumentKey idProofKey, DocumentKey qualKey, User admin) {}
}

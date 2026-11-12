package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.DoctorVerificationHistory;
import com.harsh.hospitalmanagement.entity.Specialization;
import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.enums.DocumentEventType;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.enums.VerificationDocumentKind;
import com.harsh.hospitalmanagement.enums.VerificationStatus;
import com.harsh.hospitalmanagement.exception.ConflictException;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:doc_verification_concurrency;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.open-in-view=false",
        "spring.sql.init.mode=never"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DoctorVerificationConcurrencyTest {

    @Autowired private DoctorVerificationService verificationService;
    @Autowired private DoctorVerificationDocumentService documentService;
    @Autowired private DoctorRepository doctors;
    @Autowired private UserRepository users;
    @Autowired private SpecializationRepository specializations;
    @Autowired private DoctorVerificationHistoryRepository history;
    @Autowired private DoctorVerificationDocumentRepository documents;
    @Autowired private DoctorVerificationDocumentEventRepository documentEvents;
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
    void concurrentApproveAndRejectOnlyOneSucceedsAndSecondFailsWith409() throws Exception {
        Fixture fixture = createUnderReviewDoctor();
        String initialVersion = verificationService.getVerification(fixture.doctor().getId(), auth(fixture.admin1()))
                .reviewVersion();

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);

        CountDownLatch approveLocked = new CountDownLatch(1);
        CountDownLatch allowApproveCommit = new CountDownLatch(1);
        CountDownLatch contenderStarted = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);

        try {
            var winner = executor.submit(() -> tx.execute(ignored -> {
                VerificationStatus result = verificationService.transition(
                        fixture.doctor().getId(),
                        VerificationStatus.VERIFIED,
                        null,
                        initialVersion,
                        auth(fixture.admin1())
                );
                approveLocked.countDown();
                await(allowApproveCommit);
                return result;
            }));
            await(approveLocked);

            var contender = executor.submit(() -> {
                contenderStarted.countDown();
                return verificationService.transition(
                        fixture.doctor().getId(),
                        VerificationStatus.REJECTED,
                        "Late rejection reason",
                        initialVersion,
                        auth(fixture.admin2())
                );
            });
            await(contenderStarted);

            assertThrows(TimeoutException.class, () -> contender.get(250, TimeUnit.MILLISECONDS),
                    "Concurrent rejection must wait on the locked doctor row");

            allowApproveCommit.countDown();
            assertThat(winner.get(10, TimeUnit.SECONDS)).isEqualTo(VerificationStatus.VERIFIED);

            assertThatThrownBy(() -> contender.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(ConflictException.class);

            Doctor updated = doctors.findById(fixture.doctor().getId()).orElseThrow();
            assertThat(updated.getVerificationStatus()).isEqualTo(VerificationStatus.VERIFIED);

            List<DoctorVerificationHistory> entries =
                    history.findByDoctor_IdOrderByOccurredAtDescIdDesc(fixture.doctor().getId());
            assertThat(entries.getFirst().getNewStatus()).isEqualTo(VerificationStatus.VERIFIED);
            assertThat(entries).filteredOn(e -> e.getNewStatus() == VerificationStatus.REJECTED).isEmpty();
        } finally {
            allowApproveCommit.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void documentReadBlocksConcurrentApprovalUntilAuditCommits() throws Exception {
        Fixture fixture = createUnderReviewDoctor();
        String version = verificationService.getVerification(fixture.doctor().getId(), auth(fixture.admin1()))
                .reviewVersion();

        CountDownLatch readHoldingLock = new CountDownLatch(1);
        CountDownLatch allowReadFinish = new CountDownLatch(1);
        CountDownLatch approveStarted = new CountDownLatch(1);

        doAnswer(invocation -> {
            readHoldingLock.countDown();
            await(allowReadFinish);
            return new DocumentStorage.ReadDocument("image/png", "id-proof.png", fixture.idProofBytes());
        }).when(documentStorage).read(eq(fixture.idProofKey()));

        var executor = Executors.newFixedThreadPool(2);
        try {
            var readFuture = executor.submit(() -> documentService.readDocument(
                    fixture.doctor().getId(),
                    VerificationDocumentKind.ID_PROOF,
                    version,
                    auth(fixture.admin1()),
                    "corr-read-first"
            ));
            await(readHoldingLock);

            var approveFuture = executor.submit(() -> {
                approveStarted.countDown();
                return verificationService.transition(
                        fixture.doctor().getId(),
                        VerificationStatus.VERIFIED,
                        null,
                        version,
                        auth(fixture.admin2())
                );
            });
            await(approveStarted);

            assertThrows(TimeoutException.class, () -> approveFuture.get(250, TimeUnit.MILLISECONDS),
                    "Approval must wait until in-flight document read commits under UNDER_REVIEW");

            allowReadFinish.countDown();

            var readDoc = readFuture.get(10, TimeUnit.SECONDS);
            assertThat(readDoc.bytes()).isEqualTo(fixture.idProofBytes());
            assertThat(approveFuture.get(10, TimeUnit.SECONDS)).isEqualTo(VerificationStatus.VERIFIED);

            var docEvents = documentEvents.findByDoctorIdOrderByOccurredAtDescIdDesc(fixture.doctor().getId());
            assertThat(docEvents).anyMatch(event -> event.getEventType() == DocumentEventType.ADMIN_ACCESS_GRANTED);
        } finally {
            allowReadFinish.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void approvalBlocksConcurrentDocumentReadAndPreventsReturningBytesAfterDecision() throws Exception {
        Fixture fixture = createUnderReviewDoctor();
        String version = verificationService.getVerification(fixture.doctor().getId(), auth(fixture.admin1()))
                .reviewVersion();

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);

        CountDownLatch decisionHoldingLock = new CountDownLatch(1);
        CountDownLatch allowDecisionCommit = new CountDownLatch(1);
        CountDownLatch readStarted = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);

        try {
            var decisionFuture = executor.submit(() -> tx.execute(ignored -> {
                VerificationStatus next = verificationService.transition(
                        fixture.doctor().getId(),
                        VerificationStatus.VERIFIED,
                        null,
                        version,
                        auth(fixture.admin1())
                );
                decisionHoldingLock.countDown();
                await(allowDecisionCommit);
                return next;
            }));
            await(decisionHoldingLock);

            var readFuture = executor.submit(() -> {
                readStarted.countDown();
                return documentService.readDocument(
                        fixture.doctor().getId(),
                        VerificationDocumentKind.ID_PROOF,
                        version,
                        auth(fixture.admin2()),
                        "corr-read-after-decision"
                );
            });
            await(readStarted);

            assertThrows(TimeoutException.class, () -> readFuture.get(250, TimeUnit.MILLISECONDS),
                    "Document read must wait for the concurrent decision lock");

            allowDecisionCommit.countDown();
            assertThat(decisionFuture.get(10, TimeUnit.SECONDS)).isEqualTo(VerificationStatus.VERIFIED);

            assertThatThrownBy(() -> readFuture.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(ConflictException.class);

            // Approval reads ID_PROOF once during verification; the blocked read must never call storage.read().
            verify(documentStorage, times(1)).read(eq(fixture.idProofKey()));
            var docEvents = documentEvents.findByDoctorIdOrderByOccurredAtDescIdDesc(fixture.doctor().getId());
            assertThat(docEvents).noneMatch(event -> event.getEventType() == DocumentEventType.ADMIN_ACCESS_GRANTED);
        } finally {
            allowDecisionCommit.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void staleReviewVersionDocumentReadFailsBeforeReadingStorageOrWritingAudit() throws Exception {
        Fixture fixture = createUnderReviewDoctor();

        assertThatThrownBy(() -> documentService.readDocument(
                fixture.doctor().getId(),
                VerificationDocumentKind.ID_PROOF,
                "stale-review-version",
                auth(fixture.admin1()),
                "corr-stale-read"
        ))
                .isInstanceOfSatisfying(ConflictException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("STALE_REVIEW_VERSION"));

        verify(documentStorage, never()).read(any());
        var docEvents = documentEvents.findByDoctorIdOrderByOccurredAtDescIdDesc(fixture.doctor().getId());
        assertThat(docEvents).noneMatch(event -> event.getEventType() == DocumentEventType.ADMIN_ACCESS_GRANTED);
    }

    private Fixture createUnderReviewDoctor() throws IOException {
        Specialization spec = new Specialization();
        spec.setName("conc-spec-" + UUID.randomUUID());
        spec = specializations.save(spec);

        User doctorUser = user(Role.DOCTOR);
        User admin1 = user(Role.ADMIN);
        User admin2 = user(Role.ADMIN);

        Doctor doc = new Doctor();
        doc.setFirstName("Race");
        doc.setLastName("Guard");
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

        documentService.attachDocument(doc.getId(), VerificationDocumentKind.ID_PROOF, validatedIdProof, auth(doctorUser), "setup-1");
        documentService.attachDocument(doc.getId(), VerificationDocumentKind.QUALIFICATION, validatedQual, auth(doctorUser), "setup-2");
        verificationService.updateRegistrationDetails(doc.getId(), "REG-CONC-1", "Medical Council", 2020, auth(doctorUser));
        verificationService.transition(doc.getId(), VerificationStatus.SUBMITTED, null, auth(doctorUser));
        String submittedVersion = verificationService.getVerification(doc.getId(), auth(admin1)).reviewVersion();
        verificationService.transition(doc.getId(), VerificationStatus.UNDER_REVIEW, null, submittedVersion, auth(admin1));
        clearInvocations(documentStorage);

        return new Fixture(doc, idProofKey, validatedIdProof.copyBytes(), admin1, admin2);
    }

    private User user(Role role) {
        User u = new User();
        u.setEmail("conc-" + UUID.randomUUID() + "@example.invalid");
        u.setRole(role);
        return users.save(u);
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
        image.setRGB(3, 3, Color.GREEN.getRGB());
        var bytes = new ByteArrayOutputStream();
        try (var stream = new MemoryCacheImageOutputStream(bytes)) {
            ImageIO.write(image, "png", stream);
        }
        return bytes.toByteArray();
    }

    private record Fixture(Doctor doctor, DocumentKey idProofKey, byte[] idProofBytes, User admin1, User admin2) {}
}

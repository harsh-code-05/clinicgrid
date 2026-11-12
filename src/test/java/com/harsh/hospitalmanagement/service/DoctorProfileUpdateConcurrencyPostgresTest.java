package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.dto.DoctorUpdateRequest;
import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.Specialization;
import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.enums.VerificationDocumentKind;
import com.harsh.hospitalmanagement.enums.VerificationStatus;
import com.harsh.hospitalmanagement.exception.ConflictException;
import com.harsh.hospitalmanagement.repository.DoctorRepository;
import com.harsh.hospitalmanagement.repository.SpecializationRepository;
import com.harsh.hospitalmanagement.repository.UserRepository;
import com.harsh.hospitalmanagement.security.LocalAccount;
import com.harsh.hospitalmanagement.storage.DocumentKey;
import com.harsh.hospitalmanagement.storage.DocumentStorage;
import com.harsh.hospitalmanagement.storage.MasterKeyProvider;
import com.harsh.hospitalmanagement.storage.VerificationImageValidator;
import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Kernel32Util;
import com.sun.jna.platform.win32.WinNT;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.FileSystemUtils;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import static com.sun.jna.platform.win32.AccCtrl.SE_OBJECT_TYPE.SE_FILE_OBJECT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

@EnabledIfEnvironmentVariable(named = "HMS_DOCTOR_TEST_DATABASE", matches = ".+")
@DataJpaTest(properties = "spring.sql.init.mode=never", showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({DoctorService.class, AuditLogService.class, DoctorManagementAccess.class,
        DoctorVerificationService.class, DoctorVerificationDocumentService.class,
        DoctorProfileUpdateConcurrencyPostgresTest.StorageTestConfig.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DoctorProfileUpdateConcurrencyPostgresTest {

    @Autowired private DoctorService doctorService;
    @Autowired private DoctorVerificationService verificationService;
    @Autowired private DoctorVerificationDocumentService documentService;
    @Autowired private DoctorRepository doctors;
    @Autowired private UserRepository users;
    @Autowired private SpecializationRepository specializations;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private final List<Long> createdDoctorIds = new ArrayList<>();
    private final List<Long> createdUserIds = new ArrayList<>();
    private final List<Long> createdSpecializationIds = new ArrayList<>();
    private byte[] validPngBytes;

    @TestConfiguration
    static class StorageTestConfig {
        static Path testBaseDir;

        @Bean
        DocumentStorage documentStorage() throws IOException {
            if (testBaseDir == null) {
                testBaseDir = Files.createTempDirectory("hms-pg-profile-conc-");
            }
            Path root = protectedDirectory(testBaseDir.resolve("documents"));
            return DocumentStorage.createFilesystemStorage(root, new EphemeralMasterKeyProvider(1), UUID::randomUUID);
        }
    }

    @DynamicPropertySource
    static void configureDisposablePostgres(DynamicPropertyRegistry registry) {
        String database = System.getenv("HMS_DOCTOR_TEST_DATABASE");
        if (database == null || !database.matches("hms_reconcile_validation_[0-9]{8}_[0-9]{6}_[0-9a-f]{8}")) {
            throw new IllegalArgumentException("A disposable reconciliation database name is required");
        }
        if ("hospital_management".equalsIgnoreCase(database)) {
            throw new IllegalStateException("Refusing to run against hospital_management database");
        }
        String password = System.getenv("HMS_DOCTOR_TEST_PASSWORD");
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("HMS_DOCTOR_TEST_PASSWORD is required for PostgreSQL");
        }
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:5433/" + database);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @BeforeEach
    void setUp() throws IOException {
        String currentDb = jdbc.queryForObject("SELECT current_database()", String.class);
        assertThat(currentDb).matches("hms_reconcile_validation_[0-9]{8}_[0-9]{6}_[0-9a-f]{8}");
        assertThat(currentDb).isNotEqualToIgnoringCase("hospital_management");

        assertThat(AopUtils.isAopProxy(doctorService)).isTrue();
        assertThat(AopUtils.isAopProxy(verificationService)).isTrue();
        assertThat(AopUtils.isAopProxy(documentService)).isTrue();
        validPngBytes = createPngBytes();
    }

    @AfterEach
    void tearDown() {
        for (Long docId : createdDoctorIds) {
            jdbc.update("DELETE FROM audit_logs WHERE entity_name = 'Doctor' AND entity_id = ?", docId);
            jdbc.update("DELETE FROM doctor_verification_document_events WHERE doctor_id = ?", docId);
            jdbc.update("DELETE FROM doctor_verification_documents WHERE doctor_id = ?", docId);
            jdbc.update("DELETE FROM doctor_verification_history WHERE doctor_id = ?", docId);
            jdbc.update("DELETE FROM doctors WHERE id = ?", docId);
        }
        createdDoctorIds.clear();
        for (Long userId : createdUserIds) {
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
        }
        createdUserIds.clear();
        for (Long specId : createdSpecializationIds) {
            jdbc.update("DELETE FROM specializations WHERE id = ?", specId);
        }
        createdSpecializationIds.clear();
    }

    @AfterAll
    static void cleanTempStorage() throws IOException {
        if (StorageTestConfig.testBaseDir != null) {
            FileSystemUtils.deleteRecursively(StorageTestConfig.testBaseDir);
            StorageTestConfig.testBaseDir = null;
        }
    }

    @Test
    void updateDoctorFirstBlocksVerificationTransitionAndPreservesBothInPostgres() throws Exception {
        Fixture fixture = createDoctorFixture(true);
        Specialization updatedSpec = createSpecialization("PG Cardiology");
        String reviewVersion = verificationService.getVerification(fixture.doctor().getId(), auth(fixture.admin()))
                .reviewVersion();

        DoctorUpdateRequest updateRequest = profileUpdateRequest(
                updatedSpec.getId(), "PgFirstA", "PgLastA", "MD-PG", 15);

        VerificationStatus status = runLockRace(
                tx -> doctorService.updateDoctor(fixture.doctor().getId(), updateRequest, "pg-admin@example.invalid"),
                () -> verificationService.transition(
                        fixture.doctor().getId(), VerificationStatus.VERIFIED, null, reviewVersion, auth(fixture.admin()))
        );

        assertThat(status).isEqualTo(VerificationStatus.VERIFIED);
        Doctor persisted = doctors.findById(fixture.doctor().getId()).orElseThrow();
        assertThat(persisted.getFirstName()).isEqualTo("PgFirstA");
        assertThat(persisted.getLastName()).isEqualTo("PgLastA");
        assertThat(persisted.getQualification()).isEqualTo("MD-PG");
        assertThat(persisted.getExperience()).isEqualTo(15);
        assertThat(persisted.getSpecialization().getId()).isEqualTo(updatedSpec.getId());
        assertThat(persisted.getVerificationStatus()).isEqualTo(VerificationStatus.VERIFIED);
        assertThat(persisted.getRegistrationNumber()).isEqualTo("REG-PG-1");
        assertThat(persisted.getMedicalCouncil()).isEqualTo("PG Medical Council");
        assertThat(persisted.getRegistrationYear()).isEqualTo(2019);
        assertThat(persisted.getIdProofDocumentPath()).isEqualTo(fixture.idProofKey().toString());
        assertThat(persisted.getQualificationDocumentPath()).isEqualTo(fixture.qualKey().toString());
    }

    @Test
    void verificationTransitionFirstBlocksUpdateDoctorAndProfileEditPreservesVerificationInPostgres() throws Exception {
        Fixture fixture = createDoctorFixture(true);
        Specialization updatedSpec = createSpecialization("PG Neurology");
        String reviewVersion = verificationService.getVerification(fixture.doctor().getId(), auth(fixture.admin()))
                .reviewVersion();

        DoctorUpdateRequest updateRequest = profileUpdateRequest(
                updatedSpec.getId(), "PgFirstB", "PgLastB", "DM-PG", 11);

        Doctor updated = runLockRace(
                tx -> verificationService.transition(
                        fixture.doctor().getId(), VerificationStatus.VERIFIED, null, reviewVersion, auth(fixture.admin())),
                () -> doctorService.updateDoctor(fixture.doctor().getId(), updateRequest, "pg-admin@example.invalid")
        );

        assertThat(updated.getFirstName()).isEqualTo("PgFirstB");
        Doctor persisted = doctors.findById(fixture.doctor().getId()).orElseThrow();
        assertThat(persisted.getFirstName()).isEqualTo("PgFirstB");
        assertThat(persisted.getLastName()).isEqualTo("PgLastB");
        assertThat(persisted.getQualification()).isEqualTo("DM-PG");
        assertThat(persisted.getExperience()).isEqualTo(11);
        assertThat(persisted.getSpecialization().getId()).isEqualTo(updatedSpec.getId());
        assertThat(persisted.getVerificationStatus()).isEqualTo(VerificationStatus.VERIFIED);
        assertThat(persisted.getRegistrationNumber()).isEqualTo("REG-PG-1");
        assertThat(persisted.getIdProofDocumentPath()).isEqualTo(fixture.idProofKey().toString());
        assertThat(persisted.getQualificationDocumentPath()).isEqualTo(fixture.qualKey().toString());
    }

    @Test
    void attachDocumentAndUpdateDoctorSerializeInBothOrderingsInPostgres() throws Exception {
        var validator = new VerificationImageValidator();
        var newImage = validator.validate("image/png", new ByteArrayInputStream(validPngBytes));

        // Ordering 1: updateDoctor holds FOR UPDATE first, attachDocument waits and commits replacement
        Fixture draft1 = createDoctorFixture(false);
        var attached1 = runLockRace(
                tx -> doctorService.updateDoctor(
                        draft1.doctor().getId(),
                        profileUpdateRequest(draft1.doctor().getSpecialization().getId(), "PgDraft1", "Doc", "MS", 7),
                        "pg-admin@example.invalid"),
                () -> documentService.attachDocument(
                        draft1.doctor().getId(), VerificationDocumentKind.ID_PROOF, newImage, auth(draft1.doctor().getUser()), "pg-c1")
        );

        Doctor persisted1 = doctors.findById(draft1.doctor().getId()).orElseThrow();
        assertThat(persisted1.getFirstName()).isEqualTo("PgDraft1");
        assertThat(persisted1.getIdProofDocumentPath()).isEqualTo(attached1.getStorageKey());

        // Ordering 2: attachDocument holds FOR UPDATE first, updateDoctor waits and commits without clobbering key
        Fixture draft2 = createDoctorFixture(false);
        var newDocHolder = new DocumentKey[1];
        runLockRace(
                tx -> {
                    var doc = documentService.attachDocument(
                            draft2.doctor().getId(), VerificationDocumentKind.ID_PROOF, newImage, auth(draft2.doctor().getUser()), "pg-c2");
                    newDocHolder[0] = DocumentKey.parse(doc.getStorageKey());
                    return doc;
                },
                () -> doctorService.updateDoctor(
                        draft2.doctor().getId(),
                        profileUpdateRequest(draft2.doctor().getSpecialization().getId(), "PgDraft2", "Doc", "MCh", 12),
                        "pg-admin@example.invalid")
        );

        Doctor persisted2 = doctors.findById(draft2.doctor().getId()).orElseThrow();
        assertThat(persisted2.getFirstName()).isEqualTo("PgDraft2");
        assertThat(persisted2.getIdProofDocumentPath()).isEqualTo(newDocHolder[0].toString());
    }

    @Test
    void saveDoctorDetachedInstanceDoesNotOverwriteVerificationOrDocumentsInPostgres() throws IOException {
        Fixture fixture = createDoctorFixture(true);
        String version = verificationService.getVerification(fixture.doctor().getId(), auth(fixture.admin()))
                .reviewVersion();
        verificationService.transition(
                fixture.doctor().getId(), VerificationStatus.VERIFIED, null, version, auth(fixture.admin()));

        Doctor staleDetached = new Doctor();
        staleDetached.setId(fixture.doctor().getId());
        staleDetached.setFirstName("PgDetachedFirst");
        staleDetached.setLastName("PgDetachedLast");
        staleDetached.setQualification("MD-PG-Saved");
        staleDetached.setExperience(19);
        staleDetached.setBio("Updated PG bio");
        staleDetached.setVerificationStatus(VerificationStatus.UNVERIFIED);
        staleDetached.setIdProofDocumentPath(null);
        staleDetached.setQualificationDocumentPath("forged/pg/key");
        staleDetached.setRegistrationNumber("FORGED-PG-REG");

        doctorService.saveDoctor(staleDetached);

        Doctor persisted = doctors.findById(fixture.doctor().getId()).orElseThrow();
        assertThat(persisted.getFirstName()).isEqualTo("PgDetachedFirst");
        assertThat(persisted.getLastName()).isEqualTo("PgDetachedLast");
        assertThat(persisted.getBio()).isEqualTo("Updated PG bio");
        assertThat(persisted.getVerificationStatus()).isEqualTo(VerificationStatus.VERIFIED);
        assertThat(persisted.getRegistrationNumber()).isEqualTo("REG-PG-1");
        assertThat(persisted.getIdProofDocumentPath()).isEqualTo(fixture.idProofKey().toString());
        assertThat(persisted.getQualificationDocumentPath()).isEqualTo(fixture.qualKey().toString());
    }

    @Test
    void concurrentEvidenceReplacementAndSubmissionSerializeInBothOrderingsInPostgres() throws Exception {
        var validator = new VerificationImageValidator();
        var replacementImage = validator.validate("image/png", new ByteArrayInputStream(validPngBytes));

        // Ordering 1: attachDocument holds FOR UPDATE first; transition(SUBMITTED) blocks then submits against the replacement key
        Fixture draft1 = createDoctorFixture(false);
        var replacementKeyHolder = new String[1];
        VerificationStatus submittedStatus = runLockRace(
                tx -> {
                    var doc = documentService.attachDocument(
                            draft1.doctor().getId(),
                            VerificationDocumentKind.ID_PROOF,
                            replacementImage,
                            auth(draft1.doctor().getUser()),
                            "pg-sub-1"
                    );
                    replacementKeyHolder[0] = doc.getStorageKey();
                    return doc;
                },
                () -> verificationService.transition(
                        draft1.doctor().getId(),
                        VerificationStatus.SUBMITTED,
                        null,
                        auth(draft1.doctor().getUser())
                )
        );

        assertThat(submittedStatus).isEqualTo(VerificationStatus.SUBMITTED);
        Doctor persisted1 = doctors.findById(draft1.doctor().getId()).orElseThrow();
        assertThat(persisted1.getVerificationStatus()).isEqualTo(VerificationStatus.SUBMITTED);
        assertThat(persisted1.getIdProofDocumentPath()).isEqualTo(replacementKeyHolder[0]);
        assertThat(persisted1.getIdProofDocumentPath()).isNotEqualTo(draft1.idProofKey().toString());

        // Ordering 2: transition(SUBMITTED) holds FOR UPDATE first; concurrent attachDocument blocks then fails with ConflictException (409)
        Fixture draft2 = createDoctorFixture(false);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);

        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch contenderStarted = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var submitFuture = executor.submit(() -> tx.execute(ignored -> {
                VerificationStatus status = verificationService.transition(
                        draft2.doctor().getId(),
                        VerificationStatus.SUBMITTED,
                        null,
                        auth(draft2.doctor().getUser())
                );
                lockAcquired.countDown();
                await(releaseFirst);
                return status;
            }));
            await(lockAcquired);

            var attachFuture = executor.submit(() -> {
                contenderStarted.countDown();
                return documentService.attachDocument(
                        draft2.doctor().getId(),
                        VerificationDocumentKind.ID_PROOF,
                        replacementImage,
                        auth(draft2.doctor().getUser()),
                        "pg-sub-2"
                );
            });
            await(contenderStarted);

            assertThrows(TimeoutException.class, () -> attachFuture.get(250, TimeUnit.MILLISECONDS),
                    "PostgreSQL row lock must block concurrent attachDocument during submission");

            releaseFirst.countDown();
            assertThat(submitFuture.get(10, TimeUnit.SECONDS)).isEqualTo(VerificationStatus.SUBMITTED);

            assertThatThrownBy(() -> attachFuture.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(ConflictException.class);

            Doctor persisted2 = doctors.findById(draft2.doctor().getId()).orElseThrow();
            assertThat(persisted2.getVerificationStatus()).isEqualTo(VerificationStatus.SUBMITTED);
            assertThat(persisted2.getIdProofDocumentPath()).isEqualTo(draft2.idProofKey().toString());
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private <T> T runLockRace(
            Function<TransactionTemplate, ?> firstInTx,
            Callable<T> second) throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);

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
                    "PostgreSQL row lock must block concurrent writer");

            releaseFirst.countDown();
            firstFuture.get(10, TimeUnit.SECONDS);
            return secondFuture.get(10, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Fixture createDoctorFixture(boolean advanceToUnderReview) throws IOException {
        Specialization spec = createSpecialization("PG General");
        User doctorUser = user(Role.DOCTOR);
        User admin = user(Role.ADMIN);

        Doctor doc = new Doctor();
        doc.setFirstName("PgInitial");
        doc.setLastName("Doctor");
        doc.setQualification("MBBS");
        doc.setExperience(4);
        doc.setUser(doctorUser);
        doc.setSpecialization(spec);
        doc = doctors.save(doc);
        createdDoctorIds.add(doc.getId());

        var validator = new VerificationImageValidator();
        var img1 = validator.validate("image/png", new ByteArrayInputStream(validPngBytes));
        var img2 = validator.validate("image/png", new ByteArrayInputStream(validPngBytes));

        var idDoc = documentService.attachDocument(
                doc.getId(), VerificationDocumentKind.ID_PROOF, img1, auth(doctorUser), "pg-init-1");
        var qualDoc = documentService.attachDocument(
                doc.getId(), VerificationDocumentKind.QUALIFICATION, img2, auth(doctorUser), "pg-init-2");
        verificationService.updateRegistrationDetails(
                doc.getId(), "REG-PG-1", "PG Medical Council", 2019, auth(doctorUser));

        if (advanceToUnderReview) {
            verificationService.transition(doc.getId(), VerificationStatus.SUBMITTED, null, auth(doctorUser));
            String submittedVersion = verificationService.getVerification(doc.getId(), auth(admin)).reviewVersion();
            verificationService.transition(
                    doc.getId(), VerificationStatus.UNDER_REVIEW, null, submittedVersion, auth(admin));
        }

        return new Fixture(doc, DocumentKey.parse(idDoc.getStorageKey()), DocumentKey.parse(qualDoc.getStorageKey()), admin);
    }

    private Specialization createSpecialization(String prefix) {
        Specialization spec = new Specialization();
        spec.setName(prefix + " " + UUID.randomUUID());
        spec = specializations.save(spec);
        createdSpecializationIds.add(spec.getId());
        return spec;
    }

    private User user(Role role) {
        User u = new User();
        u.setEmail("pg-prof-" + UUID.randomUUID() + "@example.invalid");
        u.setRole(role);
        u = users.save(u);
        createdUserIds.add(u.getId());
        return u;
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
            throw new IllegalStateException("Interrupted while waiting for PostgreSQL concurrency test", exception);
        }
    }

    private static byte[] createPngBytes() throws IOException {
        var image = new BufferedImage(12, 12, BufferedImage.TYPE_INT_RGB);
        image.setRGB(4, 4, Color.MAGENTA.getRGB());
        var bytes = new ByteArrayOutputStream();
        try (var stream = new MemoryCacheImageOutputStream(bytes)) {
            ImageIO.write(image, "png", stream);
        }
        return bytes.toByteArray();
    }

    private record Fixture(Doctor doctor, DocumentKey idProofKey, DocumentKey qualKey, User admin) {}

    static final class EphemeralMasterKeyProvider implements MasterKeyProvider {
        private final SecretKey masterKey;
        private final int version;

        EphemeralMasterKeyProvider(int version) {
            this.version = version;
            byte[] keyBytes = new byte[32];
            new SecureRandom().nextBytes(keyBytes);
            this.masterKey = new SecretKeySpec(keyBytes, "AES");
        }

        @Override
        public int activeVersion() {
            return version;
        }

        @Override
        public byte[] wrapDataKey(int version, byte[] dataKey) throws GeneralSecurityException {
            Cipher cipher = Cipher.getInstance("AESWrap");
            cipher.init(Cipher.WRAP_MODE, masterKey);
            return cipher.wrap(new SecretKeySpec(dataKey, "AES"));
        }

        @Override
        public byte[] unwrapDataKey(int version, byte[] wrappedKey) throws GeneralSecurityException {
            Cipher cipher = Cipher.getInstance("AESWrap");
            cipher.init(Cipher.UNWRAP_MODE, masterKey);
            return cipher.unwrap(wrappedKey, "AES", Cipher.SECRET_KEY).getEncoded();
        }
    }

    private static Path protectedDirectory(Path path) throws IOException {
        Files.createDirectories(path);
        var token = new WinNT.HANDLEByReference();
        if (!Advapi32.INSTANCE.OpenProcessToken(Kernel32.INSTANCE.GetCurrentProcess(), WinNT.TOKEN_QUERY, token)) {
            throw new IOException("Failed to open current process token");
        }
        try {
            var sid = new WinNT.PSID(Advapi32Util.getTokenAccount(token.getValue()).sid);
            var acl = new WinNT.ACL(1024);
            if (!Advapi32.INSTANCE.InitializeAcl(acl, 1024, WinNT.ACL_REVISION)) {
                throw new IOException("Failed to initialize ACL");
            }
            if (!Advapi32.INSTANCE.AddAccessAllowedAceEx(acl, WinNT.ACL_REVISION,
                    WinNT.OBJECT_INHERIT_ACE | WinNT.CONTAINER_INHERIT_ACE, WinNT.FILE_ALL_ACCESS, sid)) {
                throw new IOException("Failed to add ACE");
            }
            if (Advapi32.INSTANCE.SetNamedSecurityInfo(path.toString(), SE_FILE_OBJECT,
                    WinNT.DACL_SECURITY_INFORMATION | WinNT.PROTECTED_DACL_SECURITY_INFORMATION,
                    null, null, acl.getPointer(), null) != 0) {
                throw new IOException("Failed to set security info");
            }
        } finally {
            Kernel32Util.closeHandleRef(token);
        }
        return path;
    }
}

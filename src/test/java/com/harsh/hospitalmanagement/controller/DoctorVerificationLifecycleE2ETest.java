package com.harsh.hospitalmanagement.controller;

import com.harsh.hospitalmanagement.dto.DoctorVerificationApprovalRequest;
import com.harsh.hospitalmanagement.dto.DoctorVerificationCredentialsRequest;
import com.harsh.hospitalmanagement.dto.DoctorVerificationRejectionRequest;
import com.harsh.hospitalmanagement.dto.DoctorVerificationReviewRequest;
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
import com.harsh.hospitalmanagement.service.DoctorVerificationService.DocumentKind;
import com.harsh.hospitalmanagement.storage.DocumentStorage;
import com.harsh.hospitalmanagement.storage.MasterKeyProvider;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:doc_lifecycle_e2e;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.open-in-view=false",
        "spring.sql.init.mode=never",
        "spring.servlet.multipart.max-file-size=5MB",
        "spring.servlet.multipart.max-request-size=11MB"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class DoctorVerificationLifecycleE2ETest {

    @TestConfiguration
    static class StorageConfig {
        static Path testStorageRoot;

        @Bean
        @Primary
        DocumentStorage documentStorage() throws IOException {
            if (testStorageRoot == null) {
                Path temp = Files.createTempDirectory("hms-e2e-docs-");
                com.harsh.hospitalmanagement.storage.MultipartStagingManager.setRestrictedWindowsDacl(temp);
                testStorageRoot = com.harsh.hospitalmanagement.storage.WindowsDocumentStorageTest.protectedDirectory(temp.resolve("documents"));
            }
            return DocumentStorage.createFilesystemStorage(
                    testStorageRoot,
                    new EphemeralKeyProvider(1),
                    UUID::randomUUID
            );
        }

        static final class EphemeralKeyProvider implements MasterKeyProvider {
            private final int activeVersion;
            private final byte[] masterKey = new byte[32];

            EphemeralKeyProvider(int activeVersion) {
                this.activeVersion = activeVersion;
                Arrays.fill(masterKey, (byte) 0x7E);
            }

            @Override public int activeVersion() { return activeVersion; }

            @Override
            public byte[] wrapDataKey(int version, byte[] dataKey) throws GeneralSecurityException {
                var cipher = Cipher.getInstance("AES/KW/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(masterKey, "AES"));
                return cipher.doFinal(dataKey);
            }

            @Override
            public byte[] unwrapDataKey(int version, byte[] wrappedKey) throws GeneralSecurityException {
                var cipher = Cipher.getInstance("AES/KW/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(masterKey, "AES"));
                return cipher.doFinal(wrappedKey);
            }
        }
    }

    @Autowired private MockMvc mvc;
    @Autowired private DoctorRepository doctors;
    @Autowired private UserRepository users;
    @Autowired private SpecializationRepository specializations;
    @Autowired private DoctorVerificationDocumentRepository documents;
    @Autowired private DoctorVerificationDocumentEventRepository events;
    @Autowired private DoctorVerificationHistoryRepository historyRepo;
    @Autowired private DocumentStorage storage;

    private Doctor doctor1;
    private Doctor doctor2;
    private User doctorUser1;
    private User doctorUser2;
    private User adminUser;
    private User patientUser;

    private MockHttpSession doctor1Session;
    private MockHttpSession doctor2Session;
    private MockHttpSession adminSession;
    private MockHttpSession patientSession;

    private byte[] syntheticIdProofPng;
    private byte[] syntheticQualificationJpeg;

    @BeforeEach
    void setUp() throws IOException {
        syntheticIdProofPng = createSyntheticImage("png", Color.BLUE);
        syntheticQualificationJpeg = createSyntheticImage("jpeg", Color.MAGENTA);

        var spec = new Specialization();
        spec.setName("Cardiology-" + UUID.randomUUID());
        spec = specializations.save(spec);

        doctorUser1 = createUser("dr.alice-" + UUID.randomUUID() + "@hms.local", Role.DOCTOR);
        doctorUser2 = createUser("dr.bob-" + UUID.randomUUID() + "@hms.local", Role.DOCTOR);
        adminUser = createUser("admin-" + UUID.randomUUID() + "@hms.local", Role.ADMIN);
        patientUser = createUser("patient-" + UUID.randomUUID() + "@hms.local", Role.PATIENT);

        doctor1 = createDoctor(doctorUser1, spec, "Alice", "Walker");
        doctor2 = createDoctor(doctorUser2, spec, "Bob", "Davis");

        doctor1Session = createSession(doctorUser1);
        doctor2Session = createSession(doctorUser2);
        adminSession = createSession(adminUser);
        patientSession = createSession(patientUser);
    }

    @Test
    @DisplayName("Complete doctor verification lifecycle: upload, credential save, submission, review, preview, approval")
    void fullDoctorVerificationLifecycle() throws Exception {
        long doctorId = doctor1.getId();

        // 1. Initial State: Doctor is UNVERIFIED
        mvc.perform(get("/api/doctors/{id}/verification", doctorId)
                        .session(doctor1Session))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.status").value("UNVERIFIED"))
                .andExpect(jsonPath("$.registrationNumber").isEmpty());

        mvc.perform(get("/api/doctors/{id}/verification/documents", doctorId)
                        .session(doctor1Session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));

        // 2. Doctor uploads synthetic ID Proof
        MockMultipartFile idFile = new MockMultipartFile(
                "file", "id-proof.png", "image/png", syntheticIdProofPng);

        mvc.perform(multipart("/api/doctors/{id}/verification/documents/id-proof", doctorId)
                        .file(idFile)
                        .session(doctor1Session)
                        .with(csrf().asHeader()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.documentKind").value("ID_PROOF"))
                .andExpect(jsonPath("$.mediaType").value("image/png"))
                .andExpect(jsonPath("$.retiredAt").isEmpty());

        // 3. Doctor uploads synthetic Qualification document
        MockMultipartFile qualFile = new MockMultipartFile(
                "file", "qualification.jpg", "image/jpeg", syntheticQualificationJpeg);

        mvc.perform(multipart("/api/doctors/{id}/verification/documents/qualification", doctorId)
                        .file(qualFile)
                        .session(doctor1Session)
                        .with(csrf().asHeader()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.documentKind").value("QUALIFICATION"))
                .andExpect(jsonPath("$.mediaType").value("image/jpeg"))
                .andExpect(jsonPath("$.retiredAt").isEmpty());

        // 4. Doctor saves registration credentials
        String credsJson = """
                {
                    "registrationNumber": "MCI-2026-9988",
                    "medicalCouncil": "Medical Council of India",
                    "registrationYear": 2018
                }
                """;
        mvc.perform(post("/api/doctors/{id}/verification/credentials", doctorId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credsJson)
                        .session(doctor1Session)
                        .with(csrf().asHeader()))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", containsString("no-store")));

        // Verify credentials persisted
        mvc.perform(get("/api/doctors/{id}/verification", doctorId)
                        .session(doctor1Session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registrationNumber").value("MCI-2026-9988"))
                .andExpect(jsonPath("$.medicalCouncil").value("Medical Council of India"))
                .andExpect(jsonPath("$.registrationYear").value(2018));

        mvc.perform(get("/api/doctors/{id}/verification/documents", doctorId)
                        .session(doctor1Session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));

        // 5. Doctor submits verification application
        mvc.perform(post("/api/doctors/{id}/verification/submit", doctorId)
                        .session(doctor1Session)
                        .with(csrf().asHeader()))
                .andExpect(status().isNoContent());

        // Verify state is SUBMITTED
        mvc.perform(get("/api/doctors/{id}/verification", doctorId)
                        .session(doctor1Session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));

        // 6. Admin inspects verification queue
        var queueResult = mvc.perform(get("/api/admin/doctor-verifications")
                        .param("status", "SUBMITTED")
                        .session(adminSession))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].doctorId").value(doctorId))
                .andExpect(jsonPath("$.items[0].status").value("SUBMITTED"));

        var detailBeforeReview = mvc.perform(get("/api/doctors/{id}/verification", doctorId)
                        .session(adminSession))
                .andReturn();
        String reviewVersion = JsonPath.read(detailBeforeReview.getResponse().getContentAsString(), "$.reviewVersion");
        assertThat(reviewVersion).isNotBlank();

        // 7. Admin transitions doctor to UNDER_REVIEW
        String reviewReq = "{\"reviewVersion\": \"" + reviewVersion + "\"}";
        mvc.perform(post("/api/doctors/{id}/verification/review", doctorId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviewReq)
                        .session(adminSession)
                        .with(csrf().asHeader()))
                .andExpect(status().isNoContent());

        // Get updated reviewVersion
        var detailResult = mvc.perform(get("/api/doctors/{id}/verification", doctorId)
                        .session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNDER_REVIEW"))
                .andReturn();

        String activeReviewVersion = JsonPath.read(detailResult.getResponse().getContentAsString(), "$.reviewVersion");

        // 8. Admin previews ID_PROOF document
        var previewIdProof = mvc.perform(get("/api/doctors/{id}/verification/documents/id-proof", doctorId)
                        .param("reviewVersion", activeReviewVersion)
                        .session(adminSession))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("Cache-Control", "no-store, no-cache, must-revalidate, private, max-age=0"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition", "inline; filename=\"doctor-" + doctorId + "-id-proof.png\""))
                .andReturn();

        BufferedImage readId = ImageIO.read(new ByteArrayInputStream(previewIdProof.getResponse().getContentAsByteArray()));
        assertThat(readId).isNotNull();
        assertThat(readId.getWidth()).isEqualTo(100);
        assertThat(readId.getHeight()).isEqualTo(100);

        // 9. Admin previews QUALIFICATION document
        var previewQual = mvc.perform(get("/api/doctors/{id}/verification/documents/qualification", doctorId)
                        .param("reviewVersion", activeReviewVersion)
                        .session(adminSession))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("Cache-Control", "no-store, no-cache, must-revalidate, private, max-age=0"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn();

        BufferedImage readQual = ImageIO.read(new ByteArrayInputStream(previewQual.getResponse().getContentAsByteArray()));
        assertThat(readQual).isNotNull();
        assertThat(readQual.getWidth()).isEqualTo(100);
        assertThat(readQual.getHeight()).isEqualTo(100);

        // 10. Admin approves verification
        String approvalReq = "{\"reviewVersion\": \"" + activeReviewVersion + "\"}";
        mvc.perform(post("/api/doctors/{id}/verification/approve", doctorId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approvalReq)
                        .session(adminSession)
                        .with(csrf().asHeader()))
                .andExpect(status().isNoContent());

        // 11. Final verification: doctor status is VERIFIED
        mvc.perform(get("/api/doctors/{id}/verification", doctorId)
                        .session(doctor1Session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFIED"));

        Doctor reloaded = doctors.findById(doctorId).orElseThrow();
        assertThat(reloaded.getVerificationStatus()).isEqualTo(VerificationStatus.VERIFIED);
    }

    @Test
    @DisplayName("Rejection and Resubmission: Admin rejects with reason, doctor uploads new document and resubmits")
    void rejectionAndResubmissionWorkflow() throws Exception {
        long doctorId = doctor2.getId();

        // Attach ID and Qual
        MockMultipartFile idFile = new MockMultipartFile("file", "id.png", "image/png", syntheticIdProofPng);
        MockMultipartFile qualFile = new MockMultipartFile("file", "qual.jpg", "image/jpeg", syntheticQualificationJpeg);

        mvc.perform(multipart("/api/doctors/{id}/verification/documents/id-proof", doctorId)
                .file(idFile).session(doctor2Session).with(csrf().asHeader())).andExpect(status().isCreated());
        mvc.perform(multipart("/api/doctors/{id}/verification/documents/qualification", doctorId)
                .file(qualFile).session(doctor2Session).with(csrf().asHeader())).andExpect(status().isCreated());

        // Set credentials
        mvc.perform(post("/api/doctors/{id}/verification/credentials", doctorId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"registrationNumber\":\"MCI-99881\",\"medicalCouncil\":\"Maharashtra Medical Council\",\"registrationYear\":2018}")
                        .session(doctor2Session).with(csrf().asHeader()))
                .andExpect(status().isNoContent());

        // Submit
        mvc.perform(post("/api/doctors/{id}/verification/submit", doctorId)
                .session(doctor2Session).with(csrf().asHeader())).andExpect(status().isNoContent());

        // Review
        var detail = mvc.perform(get("/api/doctors/{id}/verification", doctorId).session(adminSession))
                .andReturn();
        String revVer = JsonPath.read(detail.getResponse().getContentAsString(), "$.reviewVersion");

        mvc.perform(post("/api/doctors/{id}/verification/review", doctorId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reviewVersion\":\"" + revVer + "\"}")
                .session(adminSession).with(csrf().asHeader())).andExpect(status().isNoContent());

        // Admin rejects with reason
        var underReviewDetail = mvc.perform(get("/api/doctors/{id}/verification", doctorId).session(adminSession))
                .andReturn();
        String activeRevVer = JsonPath.read(underReviewDetail.getResponse().getContentAsString(), "$.reviewVersion");

        DoctorVerificationRejectionRequest rejReq = new DoctorVerificationRejectionRequest(
                "Document image is blurry and unreadable", activeRevVer);
        String rejJson = String.format("{\"reason\":\"%s\",\"reviewVersion\":\"%s\"}",
                rejReq.reason(), rejReq.reviewVersion());

        mvc.perform(post("/api/doctors/{id}/verification/reject", doctorId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rejJson)
                        .session(adminSession).with(csrf().asHeader()))
                .andExpect(status().isNoContent());

        // Doctor checks status: REJECTED with rejection reason
        mvc.perform(get("/api/doctors/{id}/verification", doctorId).session(doctor2Session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.latestRejectionReason").value("Document image is blurry and unreadable"));

        // Doctor replaces ID proof
        byte[] newIdProofPng = createSyntheticImage("png", Color.RED);
        MockMultipartFile newFile = new MockMultipartFile("file", "new-id.png", "image/png", newIdProofPng);
        mvc.perform(multipart("/api/doctors/{id}/verification/documents/id-proof", doctorId)
                .file(newFile).session(doctor2Session).with(csrf().asHeader())).andExpect(status().isCreated());

        // Doctor resubmits
        mvc.perform(post("/api/doctors/{id}/verification/submit", doctorId)
                .session(doctor2Session).with(csrf().asHeader())).andExpect(status().isNoContent());

        mvc.perform(get("/api/doctors/{id}/verification", doctorId).session(doctor2Session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
    }

    @Test
    @DisplayName("Authorization boundaries: Patient, unauthenticated, and wrong doctor cannot preview or attach")
    void authorizationBoundariesEnforced() throws Exception {
        long doctorId = doctor1.getId();

        // 1. Unauthenticated request to attach document -> 401
        MockMultipartFile file = new MockMultipartFile("file", "test.png", "image/png", syntheticIdProofPng);
        mvc.perform(multipart("/api/doctors/{id}/verification/documents/id-proof", doctorId)
                        .file(file)
                        .with(csrf().asHeader()))
                .andExpect(status().isUnauthorized());

        // 2. Patient attempting to attach document to doctor -> 403
        mvc.perform(multipart("/api/doctors/{id}/verification/documents/id-proof", doctorId)
                        .file(file)
                        .session(patientSession)
                        .with(csrf().asHeader()))
                .andExpect(status().isForbidden());

        // 3. Doctor 2 attempting to attach document to Doctor 1 -> 403
        mvc.perform(multipart("/api/doctors/{id}/verification/documents/id-proof", doctorId)
                        .file(file)
                        .session(doctor2Session)
                        .with(csrf().asHeader()))
                .andExpect(status().isForbidden());

        // 4. Patient attempting to preview doctor document -> 403
        mvc.perform(get("/api/doctors/{id}/verification/documents/id-proof", doctorId)
                        .session(patientSession))
                .andExpect(status().isForbidden());

        // 5. Doctor 2 attempting to preview Doctor 1 document -> 403
        mvc.perform(get("/api/doctors/{id}/verification/documents/id-proof", doctorId)
                        .session(doctor2Session))
                .andExpect(status().isForbidden());
    }

    private User createUser(String email, Role role) {
        var user = new User();
        user.setEmail(email);
        user.setRole(role);
        return users.save(user);
    }

    private Doctor createDoctor(User user, Specialization spec, String first, String last) {
        var doc = new Doctor();
        doc.setUser(user);
        doc.setSpecialization(spec);
        doc.setFirstName(first);
        doc.setLastName(last);
        doc.setVerificationStatus(VerificationStatus.UNVERIFIED);
        return doctors.save(doc);
    }

    private MockHttpSession createSession(User user) {
        var account = new LocalAccount.PasswordPrincipal(
                new LocalAccount(user.getId(), user.getEmail()),
                "N/A",
                user.getRole()
        );
        var auth = new UsernamePasswordAuthenticationToken(
                account, null, account.getAuthorities());
        var session = new MockHttpSession();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        return session;
    }

    private static byte[] createSyntheticImage(String format, Color color) throws IOException {
        BufferedImage img = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, 100, 100);
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, format, baos);
        return baos.toByteArray();
    }
}

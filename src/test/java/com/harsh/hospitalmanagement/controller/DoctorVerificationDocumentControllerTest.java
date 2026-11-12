package com.harsh.hospitalmanagement.controller;

import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.Specialization;
import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.enums.VerificationDocumentKind;
import com.harsh.hospitalmanagement.enums.VerificationStatus;
import com.harsh.hospitalmanagement.repository.DoctorRepository;
import com.harsh.hospitalmanagement.repository.DoctorVerificationDocumentEventRepository;
import com.harsh.hospitalmanagement.repository.DoctorVerificationDocumentRepository;
import com.harsh.hospitalmanagement.repository.SpecializationRepository;
import com.harsh.hospitalmanagement.repository.UserRepository;
import com.harsh.hospitalmanagement.security.LocalAccount;
import com.harsh.hospitalmanagement.service.DoctorVerificationService.DocumentKind;
import com.harsh.hospitalmanagement.storage.DocumentKey;
import com.harsh.hospitalmanagement.storage.DocumentStorage;
import com.harsh.hospitalmanagement.storage.VerificationImageValidator;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.mockito.Mockito;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:doc_verification_api;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL",
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
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DoctorVerificationDocumentControllerTest {

    private static final String BASE_URI = "/api/doctors/{doctorId}/verification/documents";
    private static final String KIND_URI = BASE_URI + "/{kind}";
    private static final String HISTORY_URI = KIND_URI + "/history";

    @Autowired private MockMvc mvc;
    @Autowired private DoctorRepository doctors;
    @Autowired private UserRepository users;
    @Autowired private SpecializationRepository specializations;
    @Autowired private DoctorVerificationDocumentRepository documents;
    @Autowired private DoctorVerificationDocumentEventRepository events;
    @Autowired private VerificationImageValidator imageValidator;

    @MockitoBean private DocumentStorage documentStorage;

    @TestConfiguration
    static class SpyValidatorConfig {
        @Bean
        VerificationImageValidator verificationImageValidator() {
            return Mockito.spy(new VerificationImageValidator());
        }
    }

    private Doctor doctor1;
    private Doctor doctor2;
    private User adminUser;
    private User patientUser;

    private MockHttpSession doctor1Session;
    private MockHttpSession doctor2Session;
    private MockHttpSession adminSession;
    private MockHttpSession patientSession;

    private byte[] validPngBytes;

    @BeforeEach
    void setUp() throws IOException {
        reset(documentStorage);
        when(documentStorage.exists(any())).thenReturn(true);
        validPngBytes = createPngBytes();

        doctor1 = createDoctor();
        doctor2 = createDoctor();
        adminUser = createUser(Role.ADMIN);
        patientUser = createUser(Role.PATIENT);

        doctor1Session = session(doctor1.getUser());
        doctor2Session = session(doctor2.getUser());
        adminSession = session(adminUser);
        patientSession = session(patientUser);
    }

    @Test
    void unauthenticatedCallersReceive401Json() throws Exception {
        mvc.perform(get(BASE_URI, doctor1.getId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Not authenticated"));

        mvc.perform(multipart(KIND_URI, doctor1.getId(), "id-proof")
                        .file(new MockMultipartFile("file", "test.png", "image/png", validPngBytes))
                        .with(csrf().asHeader()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void patientCallersReceive403Forbidden() throws Exception {
        mvc.perform(get(BASE_URI, doctor1.getId()).session(patientSession))
                .andExpect(status().isForbidden());

        mvc.perform(multipart(KIND_URI, doctor1.getId(), "id-proof")
                        .file(new MockMultipartFile("file", "test.png", "image/png", validPngBytes))
                        .session(patientSession)
                        .with(csrf().asHeader()))
                .andExpect(status().isForbidden());
    }

    @Test
    void multipartUploadRejectsParameterOnlyCsrfTokenWithoutValidatingImage() throws Exception {
        reset(imageValidator);
        MockMultipartFile file = new MockMultipartFile("file", "license.png", "image/png", validPngBytes);

        mvc.perform(multipart(KIND_URI, doctor1.getId(), "id-proof")
                        .file(file)
                        .session(doctor1Session)
                        .with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));

        verifyNoInteractions(imageValidator);
    }

    @Test
    void doctorCanUploadOwnVerificationDocument() throws Exception {
        DocumentKey expectedKey = new DocumentKey(doctor1.getId(), DocumentKind.ID_PROOF, UUID.randomUUID());
        when(documentStorage.store(eq(doctor1.getId()), any(), any())).thenReturn(expectedKey);

        MockMultipartFile file = new MockMultipartFile("file", "license.png", "image/png", validPngBytes);

        mvc.perform(multipart(KIND_URI, doctor1.getId(), "id-proof")
                        .file(file)
                        .header("X-Correlation-ID", "trace-upload-123")
                        .session(doctor1Session)
                        .with(csrf().asHeader()))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.doctorId").value(doctor1.getId()))
                .andExpect(jsonPath("$.documentKind").value("ID_PROOF"))
                .andExpect(jsonPath("$.mediaType").value("image/png"))
                .andExpect(jsonPath("$.fileSize").isNumber())
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.checksum").doesNotExist())
                .andExpect(jsonPath("$.storageKey").doesNotExist())
                .andExpect(jsonPath("$.retiredAt").doesNotExist());

        Doctor updated = doctors.findById(doctor1.getId()).orElseThrow();
        assertThat(updated.getIdProofDocumentPath()).isEqualTo(expectedKey.toString());
    }

    @Test
    void doctorCannotUploadAnotherDoctorsVerificationDocument() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "license.png", "image/png", validPngBytes);

        mvc.perform(multipart(KIND_URI, doctor2.getId(), "id-proof")
                        .file(file)
                        .session(doctor1Session)
                        .with(csrf().asHeader()))
                .andExpect(status().isForbidden());
    }

    @Test
    void doctorCannotUploadWhenStatusIsNotDraft() throws Exception {
        doctor1.setVerificationStatus(VerificationStatus.UNDER_REVIEW);
        doctors.save(doctor1);

        MockMultipartFile file = new MockMultipartFile("file", "license.png", "image/png", validPngBytes);

        mvc.perform(multipart(KIND_URI, doctor1.getId(), "id-proof")
                        .file(file)
                        .session(doctor1Session)
                        .with(csrf().asHeader()))
                .andExpect(status().isConflict());
    }

    @Test
    void doctorCanDetachOwnDocument() throws Exception {
        DocumentKey expectedKey = new DocumentKey(doctor1.getId(), DocumentKind.ID_PROOF, UUID.randomUUID());
        when(documentStorage.store(eq(doctor1.getId()), any(), any())).thenReturn(expectedKey);

        MockMultipartFile file = new MockMultipartFile("file", "license.png", "image/png", validPngBytes);
        mvc.perform(multipart(KIND_URI, doctor1.getId(), "id-proof")
                        .file(file)
                        .session(doctor1Session)
                        .with(csrf().asHeader()))
                .andExpect(status().isCreated());

        mvc.perform(delete(KIND_URI, doctor1.getId(), "id-proof")
                        .header("X-Correlation-ID", "detach-trace-456")
                        .session(doctor1Session)
                        .with(csrf()))
                .andExpect(status().isNoContent());

        Doctor updated = doctors.findById(doctor1.getId()).orElseThrow();
        assertThat(updated.getIdProofDocumentPath()).isNull();
    }

    @Test
    void doctorCannotDetachAnotherDoctorsDocument() throws Exception {
        mvc.perform(delete(KIND_URI, doctor2.getId(), "id-proof")
                        .session(doctor1Session)
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void doctorCannotReadRawDocumentBytes() throws Exception {
        mvc.perform(get(KIND_URI, doctor1.getId(), "id-proof")
                        .param("reviewVersion", "any-version")
                        .session(doctor1Session))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanReadRawDocumentBytesWhenDoctorIsUnderReview() throws Exception {
        DocumentKey expectedKey = new DocumentKey(doctor1.getId(), DocumentKind.ID_PROOF, UUID.randomUUID());
        AtomicReference<byte[]> storedBytes = new AtomicReference<>();
        when(documentStorage.store(eq(doctor1.getId()), any(), any())).thenAnswer(invocation -> {
            var validated = invocation.getArgument(2, VerificationImageValidator.ValidatedImage.class);
            storedBytes.set(validated.copyBytes());
            return expectedKey;
        });
        when(documentStorage.read(eq(expectedKey)))
                .thenAnswer(invocation -> new DocumentStorage.ReadDocument("image/png", "id-proof.png", storedBytes.get()));

        MockMultipartFile file = new MockMultipartFile("file", "license.png", "image/png", validPngBytes);
        mvc.perform(multipart(KIND_URI, doctor1.getId(), "id-proof")
                        .file(file)
                        .session(doctor1Session)
                        .with(csrf().asHeader()))
                .andExpect(status().isCreated());

        doctor1 = doctors.findById(doctor1.getId()).orElseThrow();
        doctor1.setVerificationStatus(VerificationStatus.UNDER_REVIEW);
        doctors.save(doctor1);
        String reviewVersion = currentReviewVersion(doctor1.getId(), adminSession);

        mvc.perform(get(KIND_URI, doctor1.getId(), "id-proof")
                        .param("reviewVersion", reviewVersion)
                        .session(adminSession))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "no-store, no-cache, must-revalidate, private, max-age=0"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"doctor-" + doctor1.getId() + "-id-proof.png\""))
                .andExpect(content().bytes(storedBytes.get()));
    }

    @Test
    void adminCannotReadRawDocumentBytesWhenDoctorIsNotUnderReviewOrVersionStaleOrCorrupted() throws Exception {
        String initialVersion = currentReviewVersion(doctor1.getId(), adminSession);
        mvc.perform(get(KIND_URI, doctor1.getId(), "id-proof")
                        .param("reviewVersion", initialVersion)
                        .session(adminSession))
                .andExpect(status().isConflict());

        DocumentKey expectedKey = new DocumentKey(doctor1.getId(), DocumentKind.ID_PROOF, UUID.randomUUID());
        when(documentStorage.store(eq(doctor1.getId()), any(), any())).thenReturn(expectedKey);
        MockMultipartFile file = new MockMultipartFile("file", "license.png", "image/png", validPngBytes);
        mvc.perform(multipart(KIND_URI, doctor1.getId(), "id-proof")
                        .file(file)
                        .session(doctor1Session)
                        .with(csrf().asHeader()))
                .andExpect(status().isCreated());

        doctor1 = doctors.findById(doctor1.getId()).orElseThrow();
        doctor1.setVerificationStatus(VerificationStatus.UNDER_REVIEW);
        doctors.save(doctor1);
        String currentVersion = currentReviewVersion(doctor1.getId(), adminSession);

        // Missing or blank reviewVersion -> 400 Bad Request
        mvc.perform(get(KIND_URI, doctor1.getId(), "id-proof").session(adminSession))
                .andExpect(status().isBadRequest());
        mvc.perform(get(KIND_URI, doctor1.getId(), "id-proof")
                        .param("reviewVersion", " ")
                        .session(adminSession))
                .andExpect(status().isBadRequest());

        // Stale reviewVersion -> 409 STALE_REVIEW_VERSION before storage read
        mvc.perform(get(KIND_URI, doctor1.getId(), "id-proof")
                        .param("reviewVersion", initialVersion)
                        .session(adminSession))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_REVIEW_VERSION"));
        verify(documentStorage, never()).read(any());

        // Corrupted decrypted bytes (checksum mismatch) -> 500 DOCUMENT_INTEGRITY_ERROR with no leaked metadata
        when(documentStorage.read(eq(expectedKey)))
                .thenReturn(new DocumentStorage.ReadDocument("image/png", "id-proof.png", new byte[]{1, 2, 3}));
        mvc.perform(get(KIND_URI, doctor1.getId(), "id-proof")
                        .param("reviewVersion", currentVersion)
                        .session(adminSession))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("DOCUMENT_INTEGRITY_ERROR"))
                .andExpect(content().string(not(containsString(expectedKey.toString()))));
    }

    @Test
    void doctorAndAdminCanListActiveDocuments() throws Exception {
        DocumentKey expectedKey = new DocumentKey(doctor1.getId(), DocumentKind.ID_PROOF, UUID.randomUUID());
        when(documentStorage.store(eq(doctor1.getId()), any(), any())).thenReturn(expectedKey);

        MockMultipartFile file = new MockMultipartFile("file", "license.png", "image/png", validPngBytes);
        mvc.perform(multipart(KIND_URI, doctor1.getId(), "id-proof")
                        .file(file)
                        .session(doctor1Session)
                        .with(csrf().asHeader()))
                .andExpect(status().isCreated());

        mvc.perform(get(BASE_URI, doctor1.getId()).session(doctor1Session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].documentKind").value("ID_PROOF"))
                .andExpect(jsonPath("$[0].id").doesNotExist())
                .andExpect(jsonPath("$[0].checksum").doesNotExist())
                .andExpect(jsonPath("$[0].storageKey").doesNotExist());

        mvc.perform(get(BASE_URI, doctor1.getId()).session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").doesNotExist())
                .andExpect(jsonPath("$[0].checksum").doesNotExist())
                .andExpect(jsonPath("$[0].storageKey").doesNotExist());
    }

    @Test
    void doctorAndAdminCanViewDocumentHistory() throws Exception {
        DocumentKey expectedKey = new DocumentKey(doctor1.getId(), DocumentKind.ID_PROOF, UUID.randomUUID());
        when(documentStorage.store(eq(doctor1.getId()), any(), any())).thenReturn(expectedKey);

        mvc.perform(multipart(KIND_URI, doctor1.getId(), "id-proof")
                        .file(new MockMultipartFile("file", "license.png", "image/png", validPngBytes))
                        .session(doctor1Session)
                        .with(csrf().asHeader()))
                .andExpect(status().isCreated());

        mvc.perform(get(HISTORY_URI, doctor1.getId(), "id-proof").session(doctor1Session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].documentKind").value("ID_PROOF"))
                .andExpect(jsonPath("$[0].id").doesNotExist())
                .andExpect(jsonPath("$[0].checksum").doesNotExist())
                .andExpect(jsonPath("$[0].storageKey").doesNotExist());

        mvc.perform(get(HISTORY_URI, doctor1.getId(), "id-proof").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").doesNotExist())
                .andExpect(jsonPath("$[0].checksum").doesNotExist())
                .andExpect(jsonPath("$[0].storageKey").doesNotExist());
    }

    @Test
    void doctorCannotListAnotherDoctorsDocuments() throws Exception {
        mvc.perform(get(BASE_URI, doctor2.getId()).session(doctor1Session))
                .andExpect(status().isForbidden());
    }

    @Test
    void doctorCannotViewAnotherDoctorsDocumentHistory() throws Exception {
        mvc.perform(get(HISTORY_URI, doctor2.getId(), "id-proof").session(doctor1Session))
                .andExpect(status().isForbidden());
    }

    @Test
    void requestExceedingMaxRequestSizeReturns413() throws Exception {
        byte[] chunk1 = new byte[4 * 1024 * 1024];
        byte[] chunk2 = new byte[4 * 1024 * 1024];
        byte[] chunk3 = new byte[4 * 1024 * 1024]; // 12MB total > 11MB
        MockMultipartFile file1 = new MockMultipartFile("file", "part1.png", "image/png", chunk1);
        MockMultipartFile file2 = new MockMultipartFile("extra1", "part2.png", "image/png", chunk2);
        MockMultipartFile file3 = new MockMultipartFile("extra2", "part3.png", "image/png", chunk3);

        mvc.perform(multipart(KIND_URI, doctor1.getId(), "id-proof")
                        .file(file1)
                        .file(file2)
                        .file(file3)
                        .session(doctor1Session)
                        .with(csrf().asHeader()))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.status").value(413));
    }

    @Test
    void oversizedUploadReturns413PayloadTooLarge() throws Exception {
        byte[] oversized = new byte[VerificationImageValidator.MAX_FILE_BYTES + 10];
        MockMultipartFile file = new MockMultipartFile("file", "too-large.png", "image/png", oversized);

        mvc.perform(multipart(KIND_URI, doctor1.getId(), "id-proof")
                        .file(file)
                        .session(doctor1Session)
                        .with(csrf().asHeader()))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.status").value(413));
    }

    @Test
    void invalidDoctorIdReturns400() throws Exception {
        mvc.perform(get(BASE_URI, -5).session(adminSession))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Doctor ID must be a positive integer"));
    }

    @Test
    void invalidDocumentKindReturns400() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.png", "image/png", validPngBytes);

        mvc.perform(multipart(KIND_URI, doctor1.getId(), "passport")
                        .file(file)
                        .session(doctor1Session)
                        .with(csrf().asHeader()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid verification document kind: passport"));
    }

    @Test
    void unsupportedMediaTypeReturns400() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "doc.pdf", "application/pdf", new byte[]{1, 2, 3});

        mvc.perform(multipart(KIND_URI, doctor1.getId(), "id-proof")
                        .file(file)
                        .session(doctor1Session)
                        .with(csrf().asHeader()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Only PNG and JPEG images are supported for verification documents"));
    }

    @Test
    void missingMultipartFileReturns400() throws Exception {
        mvc.perform(multipart(KIND_URI, doctor1.getId(), "id-proof")
                        .session(doctor1Session)
                        .with(csrf().asHeader()))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    }

    @Test
    void nonOwnerAndNonDraftUploadsDoNotInvokeImageValidation() throws Exception {
        reset(imageValidator);
        MockMultipartFile corruptPng = new MockMultipartFile("file", "corrupt.png", "image/png", new byte[]{1, 2, 3, 4});

        mvc.perform(multipart(KIND_URI, doctor2.getId(), "id-proof")
                        .file(corruptPng)
                        .session(doctor1Session)
                        .with(csrf().asHeader()))
                .andExpect(status().isForbidden());

        doctor1.setVerificationStatus(VerificationStatus.SUBMITTED);
        doctors.save(doctor1);

        mvc.perform(multipart(KIND_URI, doctor1.getId(), "id-proof")
                        .file(corruptPng)
                        .session(doctor1Session)
                        .with(csrf().asHeader()))
                .andExpect(status().isConflict());

        verifyNoInteractions(imageValidator);
    }

    @Test
    void preflightRejectsOversizedRequestForUnderscoreKindAndContextPath() throws Exception {
        MockMultipartFile tinyFile = new MockMultipartFile("file", "tiny.png", "image/png", validPngBytes);

        mvc.perform(multipart("/hms/api/doctors/" + doctor1.getId() + "/verification/documents/id_proof")
                        .file(tinyFile)
                        .contextPath("/hms")
                        .header(HttpHeaders.CONTENT_LENGTH, VerificationImageValidator.MAX_REQUEST_BYTES + 1)
                        .session(doctor1Session)
                        .with(csrf().asHeader()))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value(413));
    }

    @Test
    void stagedMultipartGetInputStreamFailureReturns500WithoutLeakingPath() throws Exception {
        MockMultipartFile brokenOpen = new MockMultipartFile("file", "license.png", "image/png", validPngBytes) {
            @Override
            public InputStream getInputStream() throws IOException {
                throw new IOException("C:\\secret\\staging\\upload_999.tmp: Access denied");
            }
        };

        mvc.perform(multipart(KIND_URI, doctor1.getId(), "id-proof")
                        .file(brokenOpen)
                        .header("X-Correlation-ID", "trace-io-open-999")
                        .session(doctor1Session)
                        .with(csrf().asHeader()))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("DOCUMENT_UPLOAD_ERROR"))
                .andExpect(jsonPath("$.message").value("Verification upload could not be processed. Please try again."))
                .andExpect(content().string(not(containsString("upload_999.tmp"))))
                .andExpect(content().string(not(containsString("C:\\secret\\staging"))))
                .andExpect(content().string(not(containsString("Access denied"))));
    }

    @Test
    void stagedMultipartReadStreamFailureReturns500WithoutLeakingPath() throws Exception {
        MockMultipartFile brokenRead = new MockMultipartFile("file", "license.png", "image/png", validPngBytes) {
            @Override
            public InputStream getInputStream() {
                return new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw new IOException("/var/tmp/hms-staging/upload_999.tmp: Input/output error");
                    }

                    @Override
                    public int read(byte[] b, int off, int len) throws IOException {
                        throw new IOException("/var/tmp/hms-staging/upload_999.tmp: Input/output error");
                    }
                };
            }
        };

        mvc.perform(multipart(KIND_URI, doctor1.getId(), "id-proof")
                        .file(brokenRead)
                        .session(doctor1Session)
                        .with(csrf().asHeader()))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("DOCUMENT_UPLOAD_ERROR"))
                .andExpect(jsonPath("$.message").value("Verification upload could not be processed. Please try again."))
                .andExpect(content().string(not(containsString("/var/tmp/hms-staging"))))
                .andExpect(content().string(not(containsString("upload_999.tmp"))))
                .andExpect(content().string(not(containsString("Input/output error"))));
    }

    @Test
    void storageWriteFailureReturns500WithoutLeakingFilesystemPathOrMessage() throws Exception {
        when(documentStorage.store(eq(doctor1.getId()), any(), any()))
                .thenThrow(new IOException("C:\\hms-data\\private-docs\\vault\\doc.enc (Access is denied)"));

        mvc.perform(multipart(KIND_URI, doctor1.getId(), "id-proof")
                        .file(new MockMultipartFile("file", "license.png", "image/png", validPngBytes))
                        .session(doctor1Session)
                        .with(csrf().asHeader()))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("DOCUMENT_UPLOAD_ERROR"))
                .andExpect(jsonPath("$.message").value("Verification upload could not be processed. Please try again."))
                .andExpect(content().string(not(containsString("private-docs"))))
                .andExpect(content().string(not(containsString("doc.enc"))))
                .andExpect(content().string(not(containsString("Access is denied"))));
    }

    @Test
    void invalidImageStructureErrorsReturnFixedClientSafeMessages() throws Exception {
        doThrow(new VerificationImageValidator.InvalidVerificationImageException(
                VerificationImageValidator.InvalidVerificationImageException.Reason.CORRUPT_IMAGE,
                "Internal decoder path /opt/hms/scratch/bad.png failed"))
                .when(imageValidator).validate(any(), any());
        try {
            mvc.perform(multipart(KIND_URI, doctor1.getId(), "id-proof")
                            .file(new MockMultipartFile("file", "bad.png", "image/png", validPngBytes))
                            .session(doctor1Session)
                            .with(csrf().asHeader()))
                    .andExpect(status().isBadRequest())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                    .andExpect(jsonPath("$.message").value("Invalid verification document image: Uploaded file is not a valid JPEG or PNG image"))
                    .andExpect(content().string(not(containsString("/opt/hms/scratch"))));
        } finally {
            reset(imageValidator);
        }
    }

    @Test
    void routeParsingMatchesPreflightAcrossAllValidAndInvalidDoctorIdSpellingsAndMatrixParams() throws Exception {
        reset(imageValidator);
        DocumentKey expectedKey = new DocumentKey(doctor1.getId(), DocumentKind.ID_PROOF, UUID.randomUUID());
        when(documentStorage.store(eq(doctor1.getId()), any(), any())).thenReturn(expectedKey);

        String id = Long.toString(doctor1.getId());
        String encodedDigits = percentEncodeDigits(id);
        MockMultipartFile validFile = new MockMultipartFile("file", "license.png", "image/png", validPngBytes);

        // Accepted spellings: leading zeros + encoded digits + context path + aliases
        String[] acceptedUris = {
                "/api/doctors/00" + id + "/verification/documents/id_proof",
                "/api/doctors/" + encodedDigits + "/verification/documents/id-proof",
                "/hms/api/doctors/" + encodedDigits + "/verification/documents/ID_PROOF"
        };
        for (int i = 0; i < acceptedUris.length; i++) {
            String uri = acceptedUris[i];
            var req = multipart(java.net.URI.create(uri))
                    .file(validFile)
                    .session(doctor1Session)
                    .with(csrf().asHeader());
            if (uri.startsWith("/hms/")) {
                req.contextPath("/hms");
            }
            mvc.perform(req)
                    .andExpect(status().isCreated())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                    .andExpect(jsonPath("$.doctorId").value(doctor1.getId()));
        }

        // Rejected spellings (including +id, 0, -id, whitespace, overflow, Unicode digits) must return 400 with no-store before touching storage or image validation
        reset(documentStorage, imageValidator);
        String[] rejectedPaths = {
                "/api/doctors/+" + id + "/verification/documents/id-proof",
                "/api/doctors/%2B" + id + "/verification/documents/id-proof",
                "/api/doctors/0/verification/documents/id-proof",
                "/api/doctors/000/verification/documents/id-proof",
                "/api/doctors/-" + id + "/verification/documents/id-proof",
                "/api/doctors/%20" + id + "/verification/documents/id-proof",
                "/api/doctors/" + id + "%20/verification/documents/id-proof",
                "/api/doctors/9223372036854775808/verification/documents/id-proof",
                "/api/doctors/%D9%A4%D9%A2/verification/documents/id-proof",
                "/api/doctors/abc/verification/documents/id-proof"
        };
        for (String path : rejectedPaths) {
            mvc.perform(multipart(java.net.URI.create(path))
                            .file(validFile)
                            .session(doctor1Session)
                            .with(csrf().asHeader()))
                    .andExpect(status().isBadRequest())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                    .andExpect(jsonPath("$.message").value("Doctor ID must be a positive integer"));

            mvc.perform(get(java.net.URI.create("/api/doctors/" + path.split("/")[3] + "/verification/documents"))
                            .session(adminSession))
                    .andExpect(status().isBadRequest())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                    .andExpect(jsonPath("$.message").value("Doctor ID must be a positive integer"));
        }
        // Double-encoded percent (%25) is rejected at the security firewall (400) before DispatcherServlet
        mvc.perform(multipart(java.net.URI.create("/api/doctors/%2534%2532/verification/documents/id-proof"))
                        .file(validFile)
                        .session(doctor1Session)
                        .with(csrf().asHeader()))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(documentStorage, imageValidator);

        // Standalone MVC routing with PathPatternParser confirms matrix parameters and double-encoded IDs resolve identically to preflight
        var docServiceMock = Mockito.mock(com.harsh.hospitalmanagement.service.DoctorVerificationDocumentService.class);
        when(docServiceMock.getActiveDocuments(eq(doctor1.getId()), any())).thenReturn(java.util.List.of());
        var controller = new DoctorVerificationDocumentController(docServiceMock, imageValidator);
        MockMvc standaloneMvc = MockMvcBuilders.standaloneSetup(controller)
                .setPatternParser(new org.springframework.web.util.pattern.PathPatternParser())
                .build();

        standaloneMvc.perform(get(java.net.URI.create("/api/doctors/" + id + ";v=1/verification/documents")))
                .andExpect(status().isOk());
        standaloneMvc.perform(get(java.net.URI.create("/api/doctors/+" + id + ";v=1/verification/documents")))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.message").value("Doctor ID must be a positive integer"));
        standaloneMvc.perform(get(java.net.URI.create("/api/doctors/%2534%2532/verification/documents")))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.message").value("Doctor ID must be a positive integer"));
        verifyNoInteractions(imageValidator);
    }

    @Test
    void allDocumentResponsesAndErrorHandlersIncludeSensitiveResponseHeaders() throws Exception {
        var key = new DocumentKey(doctor1.getId(), DocumentKind.ID_PROOF, UUID.randomUUID());
        when(documentStorage.store(eq(doctor1.getId()), eq(DocumentKind.ID_PROOF), any())).thenReturn(key);

        // 401 unauthenticated
        assertSensitiveHeaders(mvc.perform(get(BASE_URI, doctor1.getId()))
                .andExpect(status().isUnauthorized()));

        // 403 forbidden
        assertSensitiveHeaders(mvc.perform(get(BASE_URI, doctor1.getId()).session(doctor2Session))
                .andExpect(status().isForbidden()));

        // 201 upload
        var upload = new MockMultipartFile("file", "id.png", "image/png", validPngBytes);
        assertSensitiveHeaders(mvc.perform(multipart(KIND_URI, doctor1.getId(), "id-proof")
                        .file(upload).session(doctor1Session).with(csrf().asHeader()))
                .andExpect(status().isCreated()));

        // 200 list + history
        assertSensitiveHeaders(mvc.perform(get(BASE_URI, doctor1.getId()).session(doctor1Session))
                .andExpect(status().isOk()));
        assertSensitiveHeaders(mvc.perform(get(HISTORY_URI, doctor1.getId(), "id-proof").session(doctor1Session))
                .andExpect(status().isOk()));

        // 400 missing part & invalid kind
        assertSensitiveHeaders(mvc.perform(multipart(KIND_URI, doctor1.getId(), "id-proof")
                        .session(doctor1Session).with(csrf().asHeader()))
                .andExpect(status().isBadRequest()));
        assertSensitiveHeaders(mvc.perform(get(HISTORY_URI, doctor1.getId(), "invalid-kind").session(doctor1Session))
                .andExpect(status().isBadRequest()));

        // 204 detach
        assertSensitiveHeaders(mvc.perform(delete(KIND_URI, doctor1.getId(), "id-proof")
                        .session(doctor1Session).with(csrf().asHeader()))
                .andExpect(status().isNoContent()));

        // 409 non-draft conflict
        doctor1.setVerificationStatus(VerificationStatus.SUBMITTED);
        doctors.save(doctor1);
        assertSensitiveHeaders(mvc.perform(multipart(KIND_URI, doctor1.getId(), "id-proof")
                        .file(upload).session(doctor1Session).with(csrf().asHeader()))
                .andExpect(status().isConflict()));
    }

    private static void assertSensitiveHeaders(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        actions.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    private static String percentEncodeDigits(String value) {
        var encoded = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            encoded.append(String.format("%%%02X", (int) value.charAt(i)));
        }
        return encoded.toString();
    }

    private String currentReviewVersion(Long doctorId, MockHttpSession session) throws Exception {
        String body = mvc.perform(get("/api/doctors/{doctorId}/verification", doctorId).session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.reviewVersion");
    }

    private Doctor createDoctor() {
        var spec = new Specialization();
        spec.setName("spec-" + UUID.randomUUID());
        spec = specializations.save(spec);

        var user = createUser(Role.DOCTOR);
        var doc = new Doctor();
        doc.setUser(user);
        doc.setSpecialization(spec);
        return doctors.save(doc);
    }

    private User createUser(Role role) {
        var user = new User();
        user.setEmail("user-" + UUID.randomUUID() + "@example.invalid");
        user.setRole(role);
        return users.save(user);
    }

    private static byte[] createPngBytes() throws IOException {
        var image = new BufferedImage(8, 6, BufferedImage.TYPE_INT_RGB);
        image.setRGB(2, 3, Color.BLUE.getRGB());
        var bytes = new ByteArrayOutputStream();
        try (var stream = new MemoryCacheImageOutputStream(bytes)) {
            ImageIO.write(image, "png", stream);
        }
        return bytes.toByteArray();
    }

    private static Authentication authentication(User user) {
        var principal = new LocalAccount.PasswordPrincipal(
                new LocalAccount(user.getId(), user.getEmail()), "unused", user.getRole());
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
    }

    private static MockHttpSession session(User user) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication(user));
        var session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        return session;
    }
}

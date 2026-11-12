package com.harsh.hospitalmanagement.controller;

import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.DoctorVerificationDocument;
import com.harsh.hospitalmanagement.entity.DoctorVerificationHistory;
import com.harsh.hospitalmanagement.entity.Specialization;
import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.enums.VerificationActorRole;
import com.harsh.hospitalmanagement.enums.VerificationDocumentKind;
import com.harsh.hospitalmanagement.enums.VerificationStatus;
import com.harsh.hospitalmanagement.repository.AppointmentSlotRepository;
import com.harsh.hospitalmanagement.repository.DoctorRepository;
import com.harsh.hospitalmanagement.repository.DoctorVerificationDocumentRepository;
import com.harsh.hospitalmanagement.repository.DoctorVerificationHistoryRepository;
import com.harsh.hospitalmanagement.repository.SpecializationRepository;
import com.harsh.hospitalmanagement.repository.UserRepository;
import com.harsh.hospitalmanagement.security.LocalAccount;
import com.harsh.hospitalmanagement.service.DoctorVerificationService;
import com.harsh.hospitalmanagement.storage.DocumentKey;
import com.harsh.hospitalmanagement.storage.DocumentStorage;
import com.harsh.hospitalmanagement.storage.VerificationImageValidator;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
import java.time.Instant;
import java.time.Year;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:doctor_verification_api;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.open-in-view=false",
        "spring.sql.init.mode=never"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DoctorVerificationControllerTest {

    private static final String VERIFICATION = "/api/doctors/{doctorId}/verification";
    private static final String CREDENTIALS = VERIFICATION + "/credentials";
    private static final String SUBMIT = VERIFICATION + "/submit";
    private static final String REVIEW = VERIFICATION + "/review";
    private static final String APPROVE = VERIFICATION + "/approve";
    private static final String REJECT = VERIFICATION + "/reject";
    private static final String ADMIN_QUEUE = "/api/admin/doctor-verifications";
    private static final String REGISTRATION_DETAILS = """
            {"registrationNumber":"  REG-NEW  ","medicalCouncil":"New Council","registrationYear":2020}
            """;

    @Autowired private MockMvc mvc;
    @Autowired private DoctorRepository doctors;
    @Autowired private UserRepository users;
    @Autowired private SpecializationRepository specializations;
    @Autowired private DoctorVerificationHistoryRepository history;
    @Autowired private DoctorVerificationDocumentRepository documents;
    @Autowired private DoctorVerificationService verificationService;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private AppointmentSlotRepository slots;
    @MockitoBean private DocumentStorage documentStorage;

    private Doctor owner;
    private User admin;
    private MockHttpSession ownerSession;
    private MockHttpSession adminSession;
    private VerificationImageValidator.ValidatedImage validPng;

    @BeforeEach
    void createActors() throws IOException {
        reset(documentStorage);
        validPng = new VerificationImageValidator().validate(
                "image/png", new ByteArrayInputStream(createPngBytes()));
        when(documentStorage.exists(any())).thenReturn(true);
        when(documentStorage.read(any())).thenAnswer(inv ->
                new DocumentStorage.ReadDocument(validPng.contentType(), "evidence.png", validPng.copyBytes()));
        owner = doctor("Owner", "Physician");
        admin = user(Role.ADMIN);
        ownerSession = session(owner.getUser());
        adminSession = session(admin);
    }

    @Test
    void doctorCanReadOwnStatusWithoutEvidence() throws Exception {
        mvc.perform(get(VERIFICATION, owner.getId()).session(ownerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.doctorId").value(owner.getId().intValue()))
                .andExpect(jsonPath("$.status").value("UNVERIFIED"))
                .andExpect(jsonPath("$.latestRejectionReason").doesNotExist())
                .andExpect(jsonPath("$.reviewVersion").isString())
                .andExpect(jsonPath("$.history").isEmpty());
    }

    @Test
    void doctorCanSubmitRegistrationDetailsForAlreadyAttachedEvidence() throws Exception {
        var evidence = attachEvidence();

        mvc.perform(jsonPost(CREDENTIALS, ownerSession, REGISTRATION_DETAILS)).andExpect(status().isNoContent());

        assertThat(storedDoctor()).satisfies(doctor -> {
            assertThat(doctor.getRegistrationNumber()).isEqualTo("REG-NEW");
            assertThat(doctor.getMedicalCouncil()).isEqualTo("New Council");
            assertThat(doctor.getRegistrationYear()).isEqualTo(2020);
            assertThat(doctor.getIdProofDocumentPath()).isEqualTo(evidence.idProofKey());
            assertThat(doctor.getQualificationDocumentPath()).isEqualTo(evidence.qualificationKey());
            assertThat(doctor.getVerificationStatus()).isEqualTo(VerificationStatus.UNVERIFIED);
        });
        assertThat(events()).isEmpty();
    }

    @Test
    void submissionReviewAndApprovalRecordPrincipalDerivedHistory() throws Exception {
        var evidence = attachEvidence();
        mvc.perform(action(SUBMIT, ownerSession)).andExpect(status().isNoContent());
        mvc.perform(get(VERIFICATION, owner.getId()).session(ownerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.history[0].actorRole").value("DOCTOR"));
        String submittedVersion = currentReviewVersion(owner.getId(), adminSession);
        mvc.perform(jsonPost(REVIEW, adminSession, reviewPayload(submittedVersion)))
                .andExpect(status().isNoContent());
        String underReviewVersion = currentReviewVersion(owner.getId(), adminSession);

        mvc.perform(get(VERIFICATION, owner.getId()).session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$.reviewVersion").value(underReviewVersion));
        mvc.perform(jsonPost(APPROVE, adminSession, approvePayload(underReviewVersion)))
                .andExpect(status().isNoContent());

        mvc.perform(get(VERIFICATION, owner.getId()).session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFIED"))
                .andExpect(jsonPath("$.registrationNumber").value("REG-ORIGINAL"))
                .andExpect(jsonPath("$.reviewVersion").value(not(underReviewVersion)))
                .andExpect(jsonPath("$.history.length()").value(3))
                .andExpect(jsonPath("$.history[0].previousStatus").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$.history[0].newStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.history[0].occurredAt").exists())
                .andExpect(jsonPath("$.history[1].newStatus").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$.history[2].newStatus").value("SUBMITTED"))
                .andExpect(jsonPath("$.history[0].actorUser").doesNotExist())
                .andExpect(jsonPath("$.history[0].evidenceMetadata").doesNotExist())
                .andExpect(content().string(not(containsString(evidence.idProofKey()))))
                .andExpect(content().string(not(containsString(evidence.qualificationKey()))));

        new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
            assertThat(storedDoctor().getVerificationStatus()).isEqualTo(VerificationStatus.VERIFIED);
            var events = events();
            assertThat(events).extracting(DoctorVerificationHistory::getActorRole)
                    .containsExactly(VerificationActorRole.ADMIN, VerificationActorRole.ADMIN, VerificationActorRole.DOCTOR);
            assertThat(events).extracting(event -> event.getActorUser().getId())
                    .containsExactly(admin.getId(), admin.getId(), owner.getUser().getId());
        });
    }

    @Test
    void adminRejectsWithReasonAndDoctorCanResubmit() throws Exception {
        prepareReview();
        String underReviewVersion = currentReviewVersion(owner.getId(), adminSession);

        mvc.perform(jsonPost(REJECT, adminSession, rejectPayload(" ", underReviewVersion)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.reason").exists());
        mvc.perform(jsonPost(REJECT, adminSession, "{\"reason\":\"Missing seal\",\"reviewVersion\":\" \"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.reviewVersion").exists());
        assertThat(storedDoctor().getVerificationStatus()).isEqualTo(VerificationStatus.UNDER_REVIEW);
        assertThat(events()).hasSize(2);

        mvc.perform(jsonPost(REJECT, adminSession,
                        rejectPayload("  Registration could not be confirmed  ", underReviewVersion)))
                .andExpect(status().isNoContent());

        mvc.perform(get(VERIFICATION, owner.getId()).session(ownerSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.latestRejectionReason").value("Registration could not be confirmed"))
                .andExpect(jsonPath("$.reviewVersion").value(not(underReviewVersion)))
                .andExpect(jsonPath("$.history[0].newStatus").value("REJECTED"))
                .andExpect(jsonPath("$.history[0].notes").doesNotExist())
                .andExpect(jsonPath("$.history[0].actorUser").doesNotExist())
                .andExpect(jsonPath("$.history[0].evidenceMetadata").doesNotExist());

        mvc.perform(get(VERIFICATION, owner.getId()).session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestRejectionReason").value("Registration could not be confirmed"));

        assertThat(events().getFirst().getNotes()).isEqualTo("Registration could not be confirmed");

        mvc.perform(jsonPost(CREDENTIALS, ownerSession, REGISTRATION_DETAILS)).andExpect(status().isNoContent());
        mvc.perform(action(SUBMIT, ownerSession)).andExpect(status().isNoContent());
        assertThat(storedDoctor().getVerificationStatus()).isEqualTo(VerificationStatus.SUBMITTED);
        assertThat(events().getFirst().getPreviousStatus()).isEqualTo(VerificationStatus.REJECTED);
    }

    @Test
    void rejectionReasonAndDetailsAreRestrictedToOwnerAndAdmin() throws Exception {
        prepareReview();
        String version = currentReviewVersion(owner.getId(), adminSession);
        mvc.perform(jsonPost(REJECT, adminSession, rejectPayload("Blurred registration certificate", version)))
                .andExpect(status().isNoContent());

        var otherDoctorSession = session(doctor("Other", "Doctor").getUser());
        var patientSession = session(user(Role.PATIENT));

        mvc.perform(get(VERIFICATION, owner.getId()).session(otherDoctorSession))
                .andExpect(status().isForbidden())
                .andExpect(content().string(not(containsString("Blurred registration certificate"))));
        mvc.perform(get(VERIFICATION, owner.getId()).session(patientSession))
                .andExpect(status().isForbidden())
                .andExpect(content().string(not(containsString("Blurred registration certificate"))));
    }

    @Test
    void staleOrMissingReviewVersionIsRejectedWithConflictOrBadRequest() throws Exception {
        prepareReview();
        String staleVersion = currentReviewVersion(owner.getId(), adminSession);

        mvc.perform(action(APPROVE, adminSession))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid verification request body"));
        mvc.perform(jsonPost(APPROVE, adminSession, "{\"reviewVersion\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.reviewVersion").exists());

        mvc.perform(jsonPost(APPROVE, adminSession, approvePayload(staleVersion)))
                .andExpect(status().isNoContent());

        // Second decision using the stale token returns 409 Conflict with STALE_REVIEW_VERSION code.
        mvc.perform(jsonPost(APPROVE, adminSession, approvePayload(staleVersion)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_REVIEW_VERSION"));
        mvc.perform(jsonPost(REJECT, adminSession, rejectPayload("Too late", staleVersion)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_REVIEW_VERSION"));

        // Even after reopening and re-entering UNDER_REVIEW, the old reviewVersion stays rejected as stale.
        String verifiedVersion = currentReviewVersion(owner.getId(), adminSession);
        mvc.perform(jsonPost(REVIEW, adminSession, reviewPayload(verifiedVersion))).andExpect(status().isNoContent());
        mvc.perform(jsonPost(APPROVE, adminSession, approvePayload(staleVersion)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_REVIEW_VERSION"));
    }

    @Test
    void adminQueueFiltersPaginatesAndExcludesPrivateDocumentData() throws Exception {
        Doctor submittedDoc = doctor("Aarav", "Mehta");
        Doctor reviewDoc = doctor("Bhavna", "Kulkarni");
        Doctor rejectedDoc = doctor("Charan", "Rao");
        Doctor verifiedDoc = doctor("Divya", "Nair");

        String submittedKey = seedVerificationLifecycle(submittedDoc, VerificationStatus.SUBMITTED, null);
        String reviewKey = seedVerificationLifecycle(reviewDoc, VerificationStatus.UNDER_REVIEW, null);
        String rejectedKey = seedVerificationLifecycle(rejectedDoc, VerificationStatus.REJECTED, "Expired council ID");
        String verifiedKey = seedVerificationLifecycle(verifiedDoc, VerificationStatus.VERIFIED, null);

        var patientSession = session(user(Role.PATIENT));
        mvc.perform(get(ADMIN_QUEUE)).andExpect(status().isUnauthorized());
        mvc.perform(get(ADMIN_QUEUE).session(ownerSession)).andExpect(status().isForbidden());
        mvc.perform(get(ADMIN_QUEUE).session(patientSession)).andExpect(status().isForbidden());

        String allQueueBody = mvc.perform(get(ADMIN_QUEUE).session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.items[*].status", hasItem("SUBMITTED")))
                .andExpect(jsonPath("$.items[*].status", hasItem("UNDER_REVIEW")))
                .andExpect(jsonPath("$.items[*].status", hasItem("REJECTED")))
                .andExpect(jsonPath("$.items[*].status", hasItem("VERIFIED")))
                .andExpect(jsonPath("$.items[?(@.doctorId == " + owner.getId() + ")]", hasSize(0)))
                .andReturn().getResponse().getContentAsString();

        // Private document paths, keys, emails, and internal evidence metadata must never appear in queue results.
        assertThat(allQueueBody)
                .doesNotContain(submittedKey, reviewKey, rejectedKey, verifiedKey)
                .doesNotContain("idProofDocumentPath", "qualificationDocumentPath", "evidenceMetadata")
                .doesNotContain("@example.invalid");

        mvc.perform(get(ADMIN_QUEUE).param("status", "SUBMITTED").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.doctorId == " + submittedDoc.getId() + ")].doctorName", hasItem("Dr. Aarav Mehta")))
                .andExpect(jsonPath("$.items[?(@.doctorId == " + submittedDoc.getId() + ")].specialization", hasItem(submittedDoc.getSpecialization().getName())))
                .andExpect(jsonPath("$.items[?(@.doctorId == " + submittedDoc.getId() + ")].submittedAt").isNotEmpty())
                .andExpect(jsonPath("$.items[?(@.status != 'SUBMITTED')]", hasSize(0)));

        mvc.perform(get(ADMIN_QUEUE).param("status", "REJECTED").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.doctorId == " + rejectedDoc.getId() + ")].latestRejectionReason", hasItem("Expired council ID")))
                .andExpect(jsonPath("$.items[?(@.status != 'REJECTED')]", hasSize(0)));

        mvc.perform(get(ADMIN_QUEUE).param("status", "UNDER_REVIEW").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.status != 'UNDER_REVIEW')]", hasSize(0)));

        mvc.perform(get(ADMIN_QUEUE).param("status", "VERIFIED").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.status != 'VERIFIED')]", hasSize(0)));

        mvc.perform(get(ADMIN_QUEUE).param("status", "UNVERIFIED").session(adminSession))
                .andExpect(status().isBadRequest());
        mvc.perform(get(ADMIN_QUEUE).param("status", "BOGUS").session(adminSession))
                .andExpect(status().isBadRequest());

        String page0Json = mvc.perform(get(ADMIN_QUEUE).param("page", "0").param("size", "2").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.hasPrevious").value(false))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andReturn().getResponse().getContentAsString();
        String page1Json = mvc.perform(get(ADMIN_QUEUE).param("page", "1").param("size", "2").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.hasPrevious").value(true))
                .andReturn().getResponse().getContentAsString();

        List<Number> page0Ids = JsonPath.read(page0Json, "$.items[*].doctorId");
        List<Number> page1Ids = JsonPath.read(page1Json, "$.items[*].doctorId");
        assertThat(page0Ids).doesNotContainAnyElementsOf(page1Ids);
    }

    @Test
    void doctorCannotReadOrChangeAnotherDoctorsVerification() throws Exception {
        attachEvidence();
        var otherSession = session(doctor("Other", "One").getUser());

        mvc.perform(get(VERIFICATION, owner.getId()).session(otherSession)).andExpect(status().isForbidden());
        mvc.perform(jsonPost(CREDENTIALS, otherSession, REGISTRATION_DETAILS)).andExpect(status().isForbidden());
        mvc.perform(action(SUBMIT, otherSession)).andExpect(status().isForbidden());

        assertThat(storedDoctor().getRegistrationNumber()).isEqualTo("REG-ORIGINAL");
        assertThat(storedDoctor().getVerificationStatus()).isEqualTo(VerificationStatus.UNVERIFIED);
        assertThat(events()).isEmpty();
    }

    @Test
    void doctorCannotMakeAdminDecisionsForTheirOwnProfile() throws Exception {
        prepareReview();
        String version = currentReviewVersion(owner.getId(), adminSession);

        mvc.perform(jsonPost(REVIEW, ownerSession, reviewPayload(version))).andExpect(status().isForbidden());
        mvc.perform(jsonPost(APPROVE, ownerSession, approvePayload(version))).andExpect(status().isForbidden());
        mvc.perform(jsonPost(REJECT, ownerSession, rejectPayload("Attempted self-review", version)))
                .andExpect(status().isForbidden());

        assertThat(storedDoctor().getVerificationStatus()).isEqualTo(VerificationStatus.UNDER_REVIEW);
        assertThat(events()).hasSize(2);
    }

    @Test
    void adminCannotEditCredentialsOrSubmitOnBehalfOfTheDoctor() throws Exception {
        attachEvidence();
        mvc.perform(jsonPost(CREDENTIALS, adminSession, REGISTRATION_DETAILS)).andExpect(status().isForbidden());
        mvc.perform(action(SUBMIT, adminSession)).andExpect(status().isForbidden());
        assertThat(storedDoctor().getVerificationStatus()).isEqualTo(VerificationStatus.UNVERIFIED);
        assertThat(events()).isEmpty();
    }

    @Test
    void patientCannotAccessAnyVerificationEndpoint() throws Exception {
        String version = currentReviewVersion(owner.getId(), adminSession);
        var patientSession = session(user(Role.PATIENT));
        for (var request : List.of(
                get(VERIFICATION, owner.getId()).session(patientSession),
                jsonPost(CREDENTIALS, patientSession, REGISTRATION_DETAILS),
                action(SUBMIT, patientSession),
                jsonPost(REVIEW, patientSession, reviewPayload(version)),
                jsonPost(APPROVE, patientSession, approvePayload(version)),
                jsonPost(REJECT, patientSession, rejectPayload("Attempted patient review", version)))) {
            mvc.perform(request).andExpect(status().isForbidden());
        }
        assertThat(events()).isEmpty();
    }

    @Test
    void unauthenticatedRequestsWithValidCsrfReceiveJson401() throws Exception {
        mvc.perform(get(VERIFICATION, owner.getId()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        mvc.perform(post(SUBMIT, owner.getId()).with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        assertThat(events()).isEmpty();
    }

    @Test
    void mutationsRequireValidCsrf() throws Exception {
        attachEvidence();
        mvc.perform(post(SUBMIT, owner.getId()).session(ownerSession))
                .andExpect(status().isForbidden());
        mvc.perform(post(SUBMIT, owner.getId()).session(ownerSession).with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden());
        assertThat(storedDoctor().getVerificationStatus()).isEqualTo(VerificationStatus.UNVERIFIED);
        assertThat(events()).isEmpty();
    }

    @Test
    void invalidTransitionsAndEditsDuringReviewDoNotChangeHistory() throws Exception {
        attachEvidence();
        String initialVersion = currentReviewVersion(owner.getId(), adminSession);
        mvc.perform(jsonPost(REVIEW, adminSession, reviewPayload(initialVersion))).andExpect(status().isConflict());
        mvc.perform(action(SUBMIT, ownerSession)).andExpect(status().isNoContent());

        String submittedVersion = currentReviewVersion(owner.getId(), adminSession);
        mvc.perform(action(SUBMIT, ownerSession)).andExpect(status().isConflict());
        mvc.perform(jsonPost(APPROVE, adminSession, approvePayload(submittedVersion))).andExpect(status().isConflict());
        mvc.perform(jsonPost(CREDENTIALS, ownerSession, REGISTRATION_DETAILS)).andExpect(status().isConflict());

        assertThat(storedDoctor().getVerificationStatus()).isEqualTo(VerificationStatus.SUBMITTED);
        assertThat(events()).hasSize(1);
    }

    @Test
    void credentialEditsAndSubmissionRequireAlreadyAttachedEvidence() throws Exception {
        mvc.perform(jsonPost(CREDENTIALS, ownerSession, REGISTRATION_DETAILS)).andExpect(status().isBadRequest());
        mvc.perform(action(SUBMIT, ownerSession)).andExpect(status().isBadRequest());
        assertThat(storedDoctor().getRegistrationNumber()).isNull();
        assertThat(events()).isEmpty();
    }

    @Test
    void clientCannotSupplyEvenAWellFormedDocumentKey() throws Exception {
        var evidence = attachEvidence();
        String forgedKey = evidenceKey("id-proof");
        String request = """
                {"registrationNumber":"REG-NEW","medicalCouncil":"Council","registrationYear":2020,"idProofKey":"%s"}
                """.formatted(forgedKey);

        mvc.perform(jsonPost(CREDENTIALS, ownerSession, request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid verification request body"))
                .andExpect(content().string(not(containsString(forgedKey))));

        assertThat(storedDoctor().getRegistrationNumber()).isEqualTo("REG-ORIGINAL");
        assertThat(storedDoctor().getIdProofDocumentPath()).isEqualTo(evidence.idProofKey());
    }

    @Test
    void rejectsFileBodiesAndInvalidRegistrationDetails() throws Exception {
        attachEvidence();
        mvc.perform(action(CREDENTIALS, ownerSession).contentType(MediaType.APPLICATION_PDF).content("file"))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(jsonPost(CREDENTIALS, ownerSession, """
                {"registrationNumber":" ","medicalCouncil":"Council","registrationYear":999}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.registrationNumber").exists())
                .andExpect(jsonPath("$.errors.registrationYear").exists());
        mvc.perform(jsonPost(CREDENTIALS, ownerSession, """
                {"registrationNumber":"REG-NEW","medicalCouncil":"Council","registrationYear":%d}
                """.formatted(Year.now().getValue() + 1))).andExpect(status().isBadRequest());
        assertThat(storedDoctor().getRegistrationNumber()).isEqualTo("REG-ORIGINAL");
    }

    @Test
    void invalidAndMissingDoctorIdsReturnClientErrors() throws Exception {
        mvc.perform(get(VERIFICATION, "invalid").session(adminSession))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        mvc.perform(get(VERIFICATION, 0).session(adminSession)).andExpect(status().isBadRequest());
        mvc.perform(get(VERIFICATION, Long.MAX_VALUE).session(adminSession)).andExpect(status().isNotFound());
    }

    @Test
    void failureAfterHistoryWriteRollsBackStatusAndHistory() throws Exception {
        prepareReview();
        String version = verificationService.getVerification(owner.getId(), authentication(admin)).reviewVersion();
        verificationService.transition(owner.getId(), VerificationStatus.VERIFIED, null, version, authentication(admin));
        String reopenVersion = currentReviewVersion(owner.getId(), adminSession);
        doAnswer(invocation -> {
            entityManager.flush();
            assertThat(storedDoctor().getVerificationStatus()).isEqualTo(VerificationStatus.UNDER_REVIEW);
            assertThat(events()).hasSize(4);
            assertThat(events().getFirst().getPreviousStatus()).isEqualTo(VerificationStatus.VERIFIED);
            throw new DataIntegrityViolationException("Fixture slot retirement failure");
        }).when(slots).retireVersionedOffers(owner.getId());

        mvc.perform(jsonPost(REVIEW, adminSession, reviewPayload(reopenVersion))).andExpect(status().isConflict());

        new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
            assertThat(storedDoctor().getVerificationStatus()).isEqualTo(VerificationStatus.VERIFIED);
            assertThat(events()).extracting(DoctorVerificationHistory::getNewStatus)
                    .containsExactly(VerificationStatus.VERIFIED, VerificationStatus.UNDER_REVIEW,
                            VerificationStatus.SUBMITTED);
        });
    }

    @Test
    void startReviewRequiresValidNonStaleReviewVersionBeforeRetiringOffers() throws Exception {
        attachEvidence();
        mvc.perform(action(SUBMIT, ownerSession)).andExpect(status().isNoContent());

        mvc.perform(action(REVIEW, adminSession))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid verification request body"));
        mvc.perform(jsonPost(REVIEW, adminSession, "{\"reviewVersion\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.reviewVersion").exists());
        mvc.perform(jsonPost(REVIEW, adminSession, reviewPayload("stale-submitted-version")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_REVIEW_VERSION"));

        String submittedVersion = currentReviewVersion(owner.getId(), adminSession);
        mvc.perform(jsonPost(REVIEW, adminSession, reviewPayload(submittedVersion)))
                .andExpect(status().isNoContent());
        String underReviewVersion = currentReviewVersion(owner.getId(), adminSession);
        mvc.perform(jsonPost(APPROVE, adminSession, approvePayload(underReviewVersion)))
                .andExpect(status().isNoContent());

        mvc.perform(jsonPost(REVIEW, adminSession, reviewPayload(underReviewVersion)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_REVIEW_VERSION"));
        verify(slots, never()).retireVersionedOffers(owner.getId());
    }

    @Test
    void submissionFailsClosedOnMissingOrCorruptStorageAndDoesNotLeakInternalMetadata() throws Exception {
        var evidence = attachEvidence();

        when(documentStorage.exists(any())).thenReturn(false);
        mvc.perform(action(SUBMIT, ownerSession))
                .andExpect(status().isConflict())
                .andExpect(content().string(not(containsString(evidence.idProofKey()))))
                .andExpect(content().string(not(containsString(evidence.qualificationKey()))));
        assertThat(storedDoctor().getVerificationStatus()).isEqualTo(VerificationStatus.UNVERIFIED);

        when(documentStorage.exists(any())).thenReturn(true);
        doThrow(new IOException("secret/path/checksum failure")).when(documentStorage).read(any());
        mvc.perform(action(SUBMIT, ownerSession))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("DOCUMENT_INTEGRITY_ERROR"))
                .andExpect(content().string(not(containsString("secret/path/checksum"))))
                .andExpect(content().string(not(containsString(evidence.idProofKey()))));
        assertThat(storedDoctor().getVerificationStatus()).isEqualTo(VerificationStatus.UNVERIFIED);
    }

    @Test
    void approvalFailsClosedWithoutLeakingMetadataWhenEncryptedBytesViolateCatalogOrImageStructure() throws Exception {
        var evidence = attachEvidence();
        mvc.perform(action(SUBMIT, ownerSession)).andExpect(status().isNoContent());

        String submittedVersion = currentReviewVersion(owner.getId(), adminSession);
        mvc.perform(jsonPost(REVIEW, adminSession, reviewPayload(submittedVersion)))
                .andExpect(status().isNoContent());
        String underReviewVersion = currentReviewVersion(owner.getId(), adminSession);

        // 1. Mismatched MIME type
        when(documentStorage.read(eq(DocumentKey.parse(evidence.idProofKey()))))
                .thenReturn(new DocumentStorage.ReadDocument("image/jpeg", "id.jpg", validPng.copyBytes()));
        mvc.perform(jsonPost(APPROVE, adminSession, approvePayload(underReviewVersion)))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("DOCUMENT_INTEGRITY_ERROR"))
                .andExpect(content().string(not(containsString(evidence.idProofKey()))))
                .andExpect(content().string(not(containsString(validPng.sha256Hex()))));
        assertThat(storedDoctor().getVerificationStatus()).isEqualTo(VerificationStatus.UNDER_REVIEW);

        // 2. Corrupt image bytes matching catalog size & SHA-256 checksum
        byte[] corrupt = "CORRUPT-IMAGE-BYTES".getBytes(StandardCharsets.UTF_8);
        String corruptSha = sha256Hex(corrupt);
        DoctorVerificationDocument doc = documents
                .findByDoctorIdAndDocumentKindAndRetiredAtIsNull(owner.getId(), VerificationDocumentKind.ID_PROOF)
                .orElseThrow();
        new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
            entityManager.createQuery(
                    "UPDATE DoctorVerificationDocument d SET d.fileSize = :size, d.checksum = :sum WHERE d.id = :id")
                    .setParameter("size", (long) corrupt.length)
                    .setParameter("sum", corruptSha)
                    .setParameter("id", doc.getId())
                    .executeUpdate();
        });
        when(documentStorage.read(eq(DocumentKey.parse(evidence.idProofKey()))))
                .thenReturn(new DocumentStorage.ReadDocument("image/png", "id.png", corrupt.clone()));

        mvc.perform(jsonPost(APPROVE, adminSession, approvePayload(underReviewVersion)))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("DOCUMENT_INTEGRITY_ERROR"))
                .andExpect(content().string(not(containsString(corruptSha))))
                .andExpect(content().string(not(containsString(evidence.idProofKey()))));
        assertThat(storedDoctor().getVerificationStatus()).isEqualTo(VerificationStatus.UNDER_REVIEW);
    }

    private String currentReviewVersion(Long doctorId, MockHttpSession session) throws Exception {
        String body = mvc.perform(get(VERIFICATION, doctorId).session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.reviewVersion");
    }

    private static String reviewPayload(String reviewVersion) {
        return "{\"reviewVersion\":\"" + reviewVersion + "\"}";
    }

    private static String approvePayload(String reviewVersion) {
        return "{\"reviewVersion\":\"" + reviewVersion + "\"}";
    }

    private static String rejectPayload(String reason, String reviewVersion) {
        return "{\"reason\":\"" + reason + "\",\"reviewVersion\":\"" + reviewVersion + "\"}";
    }

    private MockHttpServletRequestBuilder action(String route, MockHttpSession session) {
        return post(route, owner.getId()).session(session).with(csrf());
    }

    private MockHttpServletRequestBuilder jsonPost(String route, MockHttpSession session, String payload) {
        return action(route, session).contentType(MediaType.APPLICATION_JSON).content(payload);
    }

    private DoctorVerificationService.Credentials attachEvidence() {
        return attachEvidenceFor(owner);
    }

    private DoctorVerificationService.Credentials attachEvidenceFor(Doctor target) {
        UUID idUuid = UUID.randomUUID();
        UUID qualUuid = UUID.randomUUID();
        String idProof = new DocumentKey(target.getId(), DoctorVerificationService.DocumentKind.ID_PROOF, idUuid).toString();
        String qualification = new DocumentKey(
                target.getId(), DoctorVerificationService.DocumentKind.QUALIFICATION, qualUuid).toString();
        Instant now = Instant.now();
        documents.save(new DoctorVerificationDocument(
                idUuid, target, VerificationDocumentKind.ID_PROOF, idProof,
                validPng.contentType(), validPng.size(), validPng.sha256Hex(), now));
        documents.save(new DoctorVerificationDocument(
                qualUuid, target, VerificationDocumentKind.QUALIFICATION, qualification,
                validPng.contentType(), validPng.size(), validPng.sha256Hex(), now));
        var credentials = new DoctorVerificationService.Credentials("REG-ORIGINAL", "Council", 2019,
                idProof, qualification);
        verificationService.replaceCredentials(target.getId(), credentials, authentication(target.getUser()));
        return credentials;
    }

    private String seedVerificationLifecycle(Doctor target, VerificationStatus finalStatus, String rejectReason) {
        var credentials = attachEvidenceFor(target);
        verificationService.transition(target.getId(), VerificationStatus.SUBMITTED, null, authentication(target.getUser()));
        if (finalStatus == VerificationStatus.SUBMITTED) {
            return credentials.idProofKey();
        }
        String submittedVersion = verificationService.getVerification(target.getId(), authentication(admin)).reviewVersion();
        verificationService.transition(
                target.getId(), VerificationStatus.UNDER_REVIEW, null, submittedVersion, authentication(admin));
        if (finalStatus == VerificationStatus.UNDER_REVIEW) {
            return credentials.idProofKey();
        }
        String reviewVersion = verificationService.getVerification(target.getId(), authentication(admin)).reviewVersion();
        verificationService.transition(target.getId(), finalStatus, rejectReason, reviewVersion, authentication(admin));
        return credentials.idProofKey();
    }

    private String evidenceKey(String kind) {
        return evidenceKey(owner.getId(), kind);
    }

    private String evidenceKey(Long doctorId, String kind) {
        return "verification/" + doctorId + "/" + kind + "/" + UUID.randomUUID();
    }

    private void prepareReview() {
        attachEvidence();
        verificationService.transition(owner.getId(), VerificationStatus.SUBMITTED, null, authentication(owner.getUser()));
        String submittedVersion = verificationService.getVerification(owner.getId(), authentication(admin)).reviewVersion();
        verificationService.transition(
                owner.getId(), VerificationStatus.UNDER_REVIEW, null, submittedVersion, authentication(admin));
    }

    @Test
    void verificationAndAdminQueueEndpointsEmitSensitiveResponseHeadersOnSuccessAndError() throws Exception {
        attachEvidence();
        assertSensitiveHeaders(mvc.perform(get(VERIFICATION, owner.getId()).session(ownerSession))
                .andExpect(status().isOk()));
        assertSensitiveHeaders(mvc.perform(post(CREDENTIALS, owner.getId()).session(ownerSession)
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REGISTRATION_DETAILS))
                .andExpect(status().isNoContent()));

        assertSensitiveHeaders(mvc.perform(post(CREDENTIALS, owner.getId()).session(ownerSession)
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{invalid"))
                .andExpect(status().isBadRequest()));
        assertSensitiveHeaders(mvc.perform(get(VERIFICATION, "not-a-number").session(ownerSession))
                .andExpect(status().isBadRequest()));
        assertSensitiveHeaders(mvc.perform(get(ADMIN_QUEUE).session(ownerSession))
                .andExpect(status().isForbidden()));

        assertSensitiveHeaders(mvc.perform(post(SUBMIT, owner.getId()).session(ownerSession)
                        .with(csrf().asHeader()))
                .andExpect(status().isNoContent()));

        String submittedVersion = verificationService.getVerification(owner.getId(), authentication(admin)).reviewVersion();
        assertSensitiveHeaders(mvc.perform(get(ADMIN_QUEUE).session(adminSession))
                .andExpect(status().isOk()));
        assertSensitiveHeaders(mvc.perform(post(REVIEW, owner.getId()).session(adminSession)
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewVersion\":\"" + submittedVersion + "\"}"))
                .andExpect(status().isNoContent()));

        String reviewVersion = verificationService.getVerification(owner.getId(), authentication(admin)).reviewVersion();
        assertSensitiveHeaders(mvc.perform(post(APPROVE, owner.getId()).session(adminSession)
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewVersion\":\"" + reviewVersion + "\"}"))
                .andExpect(status().isNoContent()));
    }

    private static void assertSensitiveHeaders(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        actions.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    private Doctor storedDoctor() {
        return doctors.findById(owner.getId()).orElseThrow();
    }

    private List<DoctorVerificationHistory> events() {
        return history.findByDoctor_IdOrderByOccurredAtDescIdDesc(owner.getId());
    }

    private Doctor doctor(String firstName, String lastName) {
        var specialization = new Specialization();
        specialization.setName("verification-test-" + UUID.randomUUID());
        var doctor = new Doctor();
        doctor.setFirstName(firstName);
        doctor.setLastName(lastName);
        doctor.setUser(user(Role.DOCTOR));
        doctor.setSpecialization(specializations.save(specialization));
        return doctors.save(doctor);
    }

    private User user(Role role) {
        var user = new User();
        user.setEmail("verification-test-" + UUID.randomUUID() + "@example.invalid");
        user.setRole(role);
        return users.save(user);
    }

    private static byte[] createPngBytes() throws IOException {
        var image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        image.setRGB(3, 3, Color.BLUE.getRGB());
        var bytes = new ByteArrayOutputStream();
        try (var stream = new MemoryCacheImageOutputStream(bytes)) {
            ImageIO.write(image, "png", stream);
        }
        return bytes.toByteArray();
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static Authentication authentication(User user) {
        var principal = new LocalAccount.PasswordPrincipal(new LocalAccount(user.getId(), user.getEmail()),
                "unused", user.getRole());
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

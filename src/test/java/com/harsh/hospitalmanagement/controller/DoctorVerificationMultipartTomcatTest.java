package com.harsh.hospitalmanagement.controller;

import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.Specialization;
import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.enums.VerificationStatus;
import com.harsh.hospitalmanagement.repository.DoctorRepository;
import com.harsh.hospitalmanagement.repository.SpecializationRepository;
import com.harsh.hospitalmanagement.repository.UserRepository;
import com.harsh.hospitalmanagement.service.DoctorVerificationService.DocumentKind;
import com.harsh.hospitalmanagement.storage.DocumentKey;
import com.harsh.hospitalmanagement.storage.DocumentStorage;
import com.harsh.hospitalmanagement.storage.VerificationImageValidator;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:doc_multipart_tomcat;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.jpa.open-in-view=false",
                "spring.sql.init.mode=never",
                "spring.servlet.multipart.max-file-size=5MB",
                "spring.servlet.multipart.max-request-size=11MB",
                "spring.servlet.multipart.resolve-lazily=true",
                "server.error.include-message=never",
                "server.error.include-stacktrace=never"
        }
)
@Import(MultipartParsingProbe.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DoctorVerificationMultipartTomcatTest {

    private static final String TEST_PASSWORD = "TestPassword#12345";

    @LocalServerPort
    private int port;

    @Autowired private DoctorRepository doctors;
    @Autowired private UserRepository users;
    @Autowired private SpecializationRepository specializations;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private VerificationImageValidator imageValidator;
    @Autowired private MultipartParsingProbe probe;

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
    private User patient;
    private byte[] validPngBytes;

    @BeforeEach
    void setUp() throws IOException {
        reset(documentStorage, imageValidator);
        probe.reset();
        when(documentStorage.exists(any())).thenReturn(true);
        validPngBytes = createPngBytes();
        doctor1 = createDoctor();
        doctor2 = createDoctor();
        patient = createUser(Role.PATIENT);
    }

    @Test
    void parameterOnlyCsrfOnBrokenMultipartReturns403WithoutParsingPartsOrValidating() throws Exception {
        SessionClient client = login(doctor1.getUser().getEmail());

        String boundary = "----BrokenParamCsrfBoundary";
        byte[] body = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"_csrf\"\r\n\r\n"
                + client.csrfToken() + "\r\n--broken-unterminated").getBytes(StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder(uri("/api/doctors/" + doctor1.getId() + "/verification/documents/id-proof"))
                .header(HttpHeaders.CONTENT_TYPE, "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        probe.reset();
        HttpResponse<String> response = client.http().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        assertThat(probe.resolveMultipartCalls()).isZero();
        assertThat(probe.servletPartCalls()).isZero();
        assertThat(probe.servletParameterCalls()).isZero();
        verifyNoInteractions(imageValidator);
    }

    @Test
    void unauthenticatedNonOwnerPatientAndNonDraftFailBeforeParsingBrokenMultipart() throws Exception {
        String boundary = "----BrokenBoundary";
        byte[] malformedMultipart = ("--" + boundary + "\r\nmalformed-without-closing-boundary").getBytes(StandardCharsets.UTF_8);

        // 1. Unauthenticated -> 401 before DispatcherServlet/multipart parsing
        SessionClient anon = anonymousCsrfClient();
        probe.reset();
        HttpResponse<String> anonRes = postRawMultipart(anon, doctor1.getId(), boundary, malformedMultipart);
        assertThat(anonRes.statusCode()).isEqualTo(401);
        assertThat(probe.resolveMultipartCalls()).isZero();
        assertThat(probe.servletPartCalls()).isZero();
        assertThat(probe.servletParameterCalls()).isZero();

        // 2. PATIENT -> 403 before servlet part parsing
        SessionClient patClient = login(patient.getEmail());
        probe.reset();
        HttpResponse<String> patRes = postRawMultipart(patClient, doctor1.getId(), boundary, malformedMultipart);
        assertThat(patRes.statusCode()).isEqualTo(403);
        assertThat(probe.servletPartCalls()).isZero();
        assertThat(probe.servletParameterCalls()).isZero();

        // 3. Non-owner doctor2 -> 403 before servlet part parsing
        SessionClient doc2Client = login(doctor2.getUser().getEmail());
        probe.reset();
        HttpResponse<String> doc2Res = postRawMultipart(doc2Client, doctor1.getId(), boundary, malformedMultipart);
        assertThat(doc2Res.statusCode()).isEqualTo(403);
        assertThat(probe.servletPartCalls()).isZero();
        assertThat(probe.servletParameterCalls()).isZero();

        // 4. Non-draft owner doctor1 -> 409 before servlet part parsing
        doctor1.setVerificationStatus(VerificationStatus.UNDER_REVIEW);
        doctors.save(doctor1);
        SessionClient doc1Client = login(doctor1.getUser().getEmail());
        probe.reset();
        HttpResponse<String> nonDraftRes = postRawMultipart(doc1Client, doctor1.getId(), boundary, malformedMultipart);
        assertThat(nonDraftRes.statusCode()).isEqualTo(409);
        assertThat(probe.servletPartCalls()).isZero();
        assertThat(probe.servletParameterCalls()).isZero();

        verifyNoInteractions(imageValidator);
    }

    @Test
    void draftOwnerMalformedMultipartReturns400AndWrongContentTypeReturns415AndOversizedReturns413WithNoStore() throws Exception {
        SessionClient doc1 = login(doctor1.getUser().getEmail());

        // 1. Wrong Content-Type -> 415 with Cache-Control: no-store
        probe.reset();
        HttpRequest wrongTypeReq = HttpRequest.newBuilder(uri("/api/doctors/" + doctor1.getId() + "/verification/documents/id-proof"))
                .header(doc1.csrfHeader(), doc1.csrfToken())
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        HttpResponse<String> wrongTypeRes = doc1.http().send(wrongTypeReq, HttpResponse.BodyHandlers.ofString());
        assertThat(wrongTypeRes.statusCode()).isEqualTo(415);
        assertThat(wrongTypeRes.headers().firstValue(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        assertThat(wrongTypeRes.headers().firstValue(HttpHeaders.PRAGMA)).contains("no-cache");
        assertThat(wrongTypeRes.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(probe.servletPartCalls()).isZero();

        // 2. Draft owner broken multipart -> Tomcat attempts parseParts() lazily -> 400 Bad Request with no-store
        String boundary = "----BrokenOwnerBoundary";
        byte[] malformedMultipart = "not-a-valid-multipart-stream".getBytes(StandardCharsets.UTF_8);
        probe.reset();
        HttpResponse<String> brokenRes = postRawMultipart(doc1, doctor1.getId(), boundary, malformedMultipart);
        assertThat(brokenRes.statusCode()).isEqualTo(400);
        assertThat(brokenRes.headers().firstValue(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        assertThat(brokenRes.headers().firstValue(HttpHeaders.PRAGMA)).contains("no-cache");
        assertThat(brokenRes.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(probe.servletPartCalls()).isPositive();

        // 3. Oversized request (> 11 MiB) -> 413 Payload Too Large with no-store BEFORE DispatcherServlet/Tomcat parsing
        byte[] oversized = new byte[11 * 1024 * 1024 + 4096];
        byte[] oversizedMultipart = buildMultipartPng(boundary, oversized);
        probe.reset();
        HttpResponse<String> tooLargeRes = postRawMultipart(doc1, doctor1.getId(), boundary, oversizedMultipart);
        assertThat(tooLargeRes.statusCode()).isEqualTo(413);
        assertThat(tooLargeRes.headers().firstValue(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        assertThat(tooLargeRes.headers().firstValue(HttpHeaders.PRAGMA)).contains("no-cache");
        assertThat(tooLargeRes.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(probe.resolveMultipartCalls()).isZero();
        assertThat(probe.servletPartCalls()).isZero();

        // 4. Preflight rejects oversized request across alias forms without touching MultipartResolver
        probe.reset();
        HttpResponse<String> aliasTooLargeRes = postRawMultipartToPath(
                doc1,
                "/api/doctors/00" + doctor1.getId() + "/verification/documents/id%5Fproof",
                boundary,
                oversizedMultipart);
        assertThat(aliasTooLargeRes.statusCode()).isEqualTo(413);
        assertThat(aliasTooLargeRes.headers().firstValue(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        assertThat(probe.resolveMultipartCalls()).isZero();
        assertThat(probe.servletPartCalls()).isZero();

        verifyNoInteractions(imageValidator);
    }

    @Test
    void draftOwnerValidMultipartUploadSucceedsAcrossCanonicalAliasesAndChunkedEncoding() throws Exception {
        DocumentKey idKey = new DocumentKey(doctor1.getId(), DocumentKind.ID_PROOF, UUID.randomUUID());
        DocumentKey qualKey = new DocumentKey(doctor1.getId(), DocumentKind.QUALIFICATION, UUID.randomUUID());
        when(documentStorage.store(eq(doctor1.getId()), eq(DocumentKind.ID_PROOF), any())).thenReturn(idKey);
        when(documentStorage.store(eq(doctor1.getId()), eq(DocumentKind.QUALIFICATION), any())).thenReturn(qualKey);

        SessionClient doc1 = login(doctor1.getUser().getEmail());
        String boundary = "----ValidUploadBoundary" + UUID.randomUUID();
        byte[] body = buildMultipartPng(boundary, validPngBytes);

        String[] idProofAliases = {
                "/api/doctors/" + doctor1.getId() + "/verification/documents/id-proof",
                "/api/doctors/" + doctor1.getId() + "/verification/documents/id_proof",
                "/api/doctors/" + doctor1.getId() + "/verification/documents/ID_PROOF",
                "/api/doctors/00" + doctor1.getId() + "/verification/documents/id%2Dproof"
        };
        for (String path : idProofAliases) {
            probe.reset();
            HttpResponse<String> res = postRawMultipartToPath(doc1, path, boundary, body);
            assertThat(res.statusCode()).isEqualTo(201);
            assertThat(res.headers().firstValue(HttpHeaders.CACHE_CONTROL)).contains("no-store");
            assertThat(JsonPath.<String>read(res.body(), "$.documentKind")).isEqualTo("ID_PROOF");
            assertThat(probe.servletPartCalls()).isPositive();
        }

        // Chunked transfer encoding (no Content-Length header) valid upload -> 201 Created
        probe.reset();
        HttpResponse<String> chunkedValid = postChunkedMultipartToPath(
                doc1,
                "/api/doctors/000" + doctor1.getId() + "/verification/documents/qualification",
                boundary,
                body);
        assertThat(chunkedValid.statusCode()).isEqualTo(201);
        assertThat(chunkedValid.headers().firstValue(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        assertThat(JsonPath.<String>read(chunkedValid.body(), "$.documentKind")).isEqualTo("QUALIFICATION");
        assertThat(probe.servletPartCalls()).isPositive();
        verify(imageValidator, Mockito.atLeastOnce()).validate(eq("image/png"), any());
    }

    @Test
    void chunkedOversizedUploadWithoutContentLengthReturns413WithNoStoreAndSkipsValidation() throws Exception {
        SessionClient doc1 = login(doctor1.getUser().getEmail());
        String boundary = "----ChunkedOversizedBoundary";
        byte[] oversized = new byte[11 * 1024 * 1024 + 4096];
        byte[] oversizedMultipart = buildMultipartPng(boundary, oversized);

        probe.reset();
        MultipartParsingProbe.RawHttpResponse res = MultipartParsingProbe.postChunkedRaw(
                port,
                uri("/api/doctors/" + doctor1.getId() + "/verification/documents/id-proof"),
                doc1.http(),
                doc1.csrfHeader(),
                doc1.csrfToken(),
                boundary,
                oversizedMultipart);
        assertThat(res.statusCode()).isEqualTo(413);
        assertThat(res.hasCacheControlNoStore()).isTrue();
        verifyNoInteractions(imageValidator);
    }

    @Test
    void nonCanonicalDoctorIdsNeverBypassPreflightViaMvcBindingAndEncodedDigitsWorkEndToEnd() throws Exception {
        DocumentKey expectedKey = new DocumentKey(doctor1.getId(), DocumentKind.ID_PROOF, UUID.randomUUID());
        when(documentStorage.store(eq(doctor1.getId()), eq(DocumentKind.ID_PROOF), any())).thenReturn(expectedKey);

        SessionClient doc1 = login(doctor1.getUser().getEmail());
        String boundary = "----CanonicalDoctorIdBoundary";
        byte[] validBody = buildMultipartPng(boundary, validPngBytes);
        byte[] oversizedBody = buildMultipartPng(boundary, new byte[11 * 1024 * 1024 + 4096]);

        String encodedLeadingZeroId = percentEncode("00" + doctor1.getId());
        String encodedPath = "/api/doctors/" + encodedLeadingZeroId + "/verification/documents/id%2Dproof";

        // 1. Valid percent-encoded leading-zero doctorId succeeds
        probe.reset();
        HttpResponse<String> encodedOk = postRawMultipartToPath(doc1, encodedPath, boundary, validBody);
        assertThat(encodedOk.statusCode()).isEqualTo(201);
        assertThat(encodedOk.headers().firstValue(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        assertThat(JsonPath.<Number>read(encodedOk.body(), "$.doctorId").longValue()).isEqualTo(doctor1.getId());
        assertThat(probe.servletPartCalls()).isPositive();

        // 2. Oversized percent-encoded leading-zero doctorId hits 413 preflight BEFORE MultipartResolver
        probe.reset();
        HttpResponse<String> encoded413 = postRawMultipartToPath(doc1, encodedPath, boundary, oversizedBody);
        assertThat(encoded413.statusCode()).isEqualTo(413);
        assertThat(encoded413.headers().firstValue(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        assertThat(probe.resolveMultipartCalls()).isZero();
        assertThat(probe.servletPartCalls()).isZero();

        // 3. Invalid doctorId spellings (+id, %2Bid, 0, -1, %20id, overflow) return 400 without parsing multipart parts
        reset(imageValidator);
        String idStr = Long.toString(doctor1.getId());
        String[] rejectedPaths = {
                "/api/doctors/+" + idStr + "/verification/documents/id-proof",
                "/api/doctors/%2B" + idStr + "/verification/documents/id-proof",
                "/api/doctors/0/verification/documents/id-proof",
                "/api/doctors/-1/verification/documents/id-proof",
                "/api/doctors/%20" + idStr + "/verification/documents/id-proof",
                "/api/doctors/9223372036854775808/verification/documents/id-proof"
        };
        for (String path : rejectedPaths) {
            probe.reset();
            HttpResponse<String> rejectedValid = postRawMultipartToPath(doc1, path, boundary, validBody);
            assertThat(rejectedValid.statusCode()).as("POST %s", path).isEqualTo(400);
            assertThat(rejectedValid.headers().firstValue(HttpHeaders.CACHE_CONTROL)).contains("no-store");
            assertThat(JsonPath.<String>read(rejectedValid.body(), "$.message"))
                    .isEqualTo("Doctor ID must be a positive integer");
            assertThat(probe.servletPartCalls()).isZero();
            assertThat(probe.servletParameterCalls()).isZero();

            probe.reset();
            MultipartParsingProbe.RawHttpResponse rejectedOversized = MultipartParsingProbe.postContentLengthRaw(
                    port, uri(path), doc1.http(), doc1.csrfHeader(), doc1.csrfToken(), boundary, oversizedBody);
            assertThat(rejectedOversized.statusCode()).as("POST oversized %s", path).isEqualTo(400);
            assertThat(rejectedOversized.hasCacheControlNoStore()).isTrue();
            assertThat(probe.servletPartCalls()).isZero();
            assertThat(probe.servletParameterCalls()).isZero();
        }
        verifyNoInteractions(imageValidator);
    }

    private static String percentEncode(String raw) {
        var sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            sb.append(String.format("%%%02X", (int) raw.charAt(i)));
        }
        return sb.toString();
    }

    private HttpResponse<String> postRawMultipart(
            SessionClient client, long targetDoctorId, String boundary, byte[] body) throws Exception {
        return postRawMultipartToPath(
                client, "/api/doctors/" + targetDoctorId + "/verification/documents/id-proof", boundary, body);
    }

    private HttpResponse<String> postRawMultipartToPath(
            SessionClient client, String path, String boundary, byte[] body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(uri(path))
                .header(client.csrfHeader(), client.csrfToken())
                .header(HttpHeaders.CONTENT_TYPE, "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return client.http().send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postChunkedMultipartToPath(
            SessionClient client, String path, String boundary, byte[] body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(uri(path))
                .header(client.csrfHeader(), client.csrfToken())
                .header(HttpHeaders.CONTENT_TYPE, "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)))
                .build();
        return client.http().send(req, HttpResponse.BodyHandlers.ofString());
    }

    private SessionClient anonymousCsrfClient() throws Exception {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();
        HttpResponse<String> csrfRes = http.send(
                HttpRequest.newBuilder(uri("/api/auth/csrf")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(csrfRes.statusCode()).isEqualTo(200);
        return new SessionClient(http, JsonPath.read(csrfRes.body(), "$.headerName"), JsonPath.read(csrfRes.body(), "$.token"));
    }

    private SessionClient login(String email) throws Exception {
        SessionClient pre = anonymousCsrfClient();
        String loginJson = "{\"email\":\"" + email + "\",\"password\":\"" + TEST_PASSWORD + "\"}";
        HttpResponse<String> loginRes = pre.http().send(
                HttpRequest.newBuilder(uri("/api/auth/login"))
                        .header(pre.csrfHeader(), pre.csrfToken())
                        .header(HttpHeaders.CONTENT_TYPE, "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(loginJson))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(loginRes.statusCode()).isEqualTo(200);

        HttpResponse<String> csrfAfterLogin = pre.http().send(
                HttpRequest.newBuilder(uri("/api/auth/csrf")).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(csrfAfterLogin.statusCode()).isEqualTo(200);
        return new SessionClient(
                pre.http(),
                JsonPath.read(csrfAfterLogin.body(), "$.headerName"),
                JsonPath.read(csrfAfterLogin.body(), "$.token")
        );
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private Doctor createDoctor() {
        var spec = new Specialization();
        spec.setName("tomcat-spec-" + UUID.randomUUID());
        spec = specializations.save(spec);

        var user = createUser(Role.DOCTOR);
        var doc = new Doctor();
        doc.setUser(user);
        doc.setSpecialization(spec);
        doc.setFirstName("Tomcat");
        doc.setLastName("Doctor");
        return doctors.save(doc);
    }

    private User createUser(Role role) {
        var user = new User();
        user.setEmail("tomcat-" + UUID.randomUUID() + "@example.invalid");
        user.setPassword(passwordEncoder.encode(TEST_PASSWORD));
        user.setRole(role);
        return users.save(user);
    }

    private static byte[] buildMultipartPng(String boundary, byte[] fileBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"doc.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(fileBytes);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private static byte[] createPngBytes() throws IOException {
        var image = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        image.setRGB(4, 4, Color.ORANGE.getRGB());
        var bytes = new ByteArrayOutputStream();
        try (var stream = new MemoryCacheImageOutputStream(bytes)) {
            ImageIO.write(image, "png", stream);
        }
        return bytes.toByteArray();
    }

    private record SessionClient(HttpClient http, String csrfHeader, String csrfToken) {}
}

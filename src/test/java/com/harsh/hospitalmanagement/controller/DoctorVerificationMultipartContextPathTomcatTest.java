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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.servlet.context-path=/hms",
                "spring.datasource.url=jdbc:h2:mem:doc_multipart_ctx_tomcat;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL",
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
class DoctorVerificationMultipartContextPathTomcatTest {

    private static final String CONTEXT = "/hms";
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
    private byte[] validPngBytes;

    @BeforeEach
    void setUp() throws IOException {
        reset(documentStorage, imageValidator);
        probe.reset();
        when(documentStorage.exists(any())).thenReturn(true);
        validPngBytes = createPngBytes();
        doctor1 = createDoctor();
        doctor2 = createDoctor();
    }

    @Test
    void contextPathSupportsValidAliasesChunkedAndPreflightWhileBlockingAuthBeforePartParsing() throws Exception {
        DocumentKey expectedKey = new DocumentKey(doctor1.getId(), DocumentKind.ID_PROOF, UUID.randomUUID());
        when(documentStorage.store(eq(doctor1.getId()), eq(DocumentKind.ID_PROOF), any())).thenReturn(expectedKey);

        SessionClient doc1 = login(doctor1.getUser().getEmail());
        String boundary = "----ContextPathBoundary";
        byte[] validBody = buildMultipartPng(boundary, validPngBytes);
        byte[] oversizedBody = buildMultipartPng(boundary, new byte[11 * 1024 * 1024 + 4096]);

        // 1. Valid upload with context path, leading zeros, percent-encoded hyphen -> 201
        probe.reset();
        HttpResponse<String> okRes = postMultipart(
                doc1, "/api/doctors/00" + doctor1.getId() + "/verification/documents/id%2Dproof", boundary, validBody);
        assertThat(okRes.statusCode()).isEqualTo(201);
        assertThat(okRes.headers().firstValue(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        assertThat(JsonPath.<String>read(okRes.body(), "$.documentKind")).isEqualTo("ID_PROOF");
        assertThat(probe.servletPartCalls()).isPositive();

        // 2. Chunked valid upload with context path and underscore alias -> 201
        probe.reset();
        HttpResponse<String> chunkedOk = postChunkedMultipart(
                doc1, "/api/doctors/" + doctor1.getId() + "/verification/documents/id_proof", boundary, validBody);
        assertThat(chunkedOk.statusCode()).isEqualTo(201);
        assertThat(chunkedOk.headers().firstValue(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        assertThat(probe.servletPartCalls()).isPositive();

        // 3. Oversized Content-Length under /hms -> 413 preflight BEFORE DispatcherServlet/Tomcat parsing
        probe.reset();
        HttpResponse<String> preflight413 = postMultipart(
                doc1, "/api/doctors/" + doctor1.getId() + "/verification/documents/ID_PROOF", boundary, oversizedBody);
        assertThat(preflight413.statusCode()).isEqualTo(413);
        assertThat(preflight413.headers().firstValue(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        assertThat(probe.resolveMultipartCalls()).isZero();
        assertThat(probe.servletPartCalls()).isZero();

        // 4. Chunked oversized upload (no Content-Length) under /hms -> 413 no-store
        probe.reset();
        MultipartParsingProbe.RawHttpResponse chunked413 = MultipartParsingProbe.postChunkedRaw(
                port,
                uri("/api/doctors/" + doctor1.getId() + "/verification/documents/id-proof"),
                doc1.http(),
                doc1.csrfHeader(),
                doc1.csrfToken(),
                boundary,
                oversizedBody);
        assertThat(chunked413.statusCode()).isEqualTo(413);
        assertThat(chunked413.hasCacheControlNoStore()).isTrue();

        // 5. Parameter-only CSRF -> 403 before MultipartResolver
        byte[] paramCsrfBody = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"_csrf\"\r\n\r\n"
                + doc1.csrfToken() + "\r\n--broken").getBytes(StandardCharsets.UTF_8);
        HttpRequest paramCsrfReq = HttpRequest.newBuilder(
                        uri("/api/doctors/" + doctor1.getId() + "/verification/documents/id-proof"))
                .header(HttpHeaders.CONTENT_TYPE, "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(paramCsrfBody))
                .build();
        probe.reset();
        HttpResponse<String> paramCsrfRes = doc1.http().send(paramCsrfReq, HttpResponse.BodyHandlers.ofString());
        assertThat(paramCsrfRes.statusCode()).isEqualTo(403);
        assertThat(probe.resolveMultipartCalls()).isZero();
        assertThat(probe.servletPartCalls()).isZero();

        // 6. Non-owner doctor2 -> 403 without servlet part parsing
        SessionClient doc2 = login(doctor2.getUser().getEmail());
        probe.reset();
        HttpResponse<String> nonOwnerRes = postMultipart(
                doc2, "/api/doctors/" + doctor1.getId() + "/verification/documents/id-proof", boundary, validBody);
        assertThat(nonOwnerRes.statusCode()).isEqualTo(403);
        assertThat(probe.servletPartCalls()).isZero();

        // 7. Non-draft owner -> 409 without servlet part parsing
        doctor1.setVerificationStatus(VerificationStatus.SUBMITTED);
        doctors.save(doctor1);
        probe.reset();
        HttpResponse<String> nonDraftRes = postMultipart(
                doc1, "/api/doctors/" + doctor1.getId() + "/verification/documents/id-proof", boundary, validBody);
        assertThat(nonDraftRes.statusCode()).isEqualTo(409);
        assertThat(probe.servletPartCalls()).isZero();

        // 8. Plus-sign, whitespace, zero, negative, and overflow IDs under /hms -> 400 without servlet part parsing
        doctor1.setVerificationStatus(VerificationStatus.UNVERIFIED);
        doctors.save(doctor1);
        reset(imageValidator);
        String idStr = Long.toString(doctor1.getId());
        String[] rejectedAppPaths = {
                "/api/doctors/+" + idStr + "/verification/documents/id-proof",
                "/api/doctors/%2B" + idStr + "/verification/documents/id-proof",
                "/api/doctors/0/verification/documents/id-proof",
                "/api/doctors/-1/verification/documents/id-proof",
                "/api/doctors/%20" + idStr + "/verification/documents/id-proof",
                "/api/doctors/9223372036854775808/verification/documents/id-proof"
        };
        for (String appPath : rejectedAppPaths) {
            probe.reset();
            HttpResponse<String> badIdValid = postMultipart(doc1, appPath, boundary, validBody);
            assertThat(badIdValid.statusCode()).as("POST /hms%s", appPath).isEqualTo(400);
            assertThat(badIdValid.headers().firstValue(HttpHeaders.CACHE_CONTROL)).contains("no-store");
            assertThat(JsonPath.<String>read(badIdValid.body(), "$.message"))
                    .isEqualTo("Doctor ID must be a positive integer");
            assertThat(probe.servletPartCalls()).isZero();
            assertThat(probe.servletParameterCalls()).isZero();

            probe.reset();
            MultipartParsingProbe.RawHttpResponse badIdOversized = MultipartParsingProbe.postContentLengthRaw(
                    port, uri(appPath), doc1.http(), doc1.csrfHeader(), doc1.csrfToken(), boundary, oversizedBody);
            assertThat(badIdOversized.statusCode()).as("POST oversized /hms%s", appPath).isEqualTo(400);
            assertThat(badIdOversized.hasCacheControlNoStore()).isTrue();
            assertThat(probe.servletPartCalls()).isZero();
            assertThat(probe.servletParameterCalls()).isZero();
        }
        verifyNoInteractions(imageValidator);
    }

    @Test
    void contextPathOversizedUploadByUnauthenticatedCallerHits401BeforePreflightOrValidation() throws Exception {
        SessionClient anon = anonymousCsrfClient();
        String boundary = "----AnonCtxBoundary";
        byte[] oversized = buildMultipartPng(boundary, new byte[11 * 1024 * 1024 + 4096]);

        probe.reset();
        HttpResponse<String> res = postMultipart(
                anon, "/api/doctors/" + doctor1.getId() + "/verification/documents/id-proof", boundary, oversized);
        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(probe.resolveMultipartCalls()).isZero();
        assertThat(probe.servletPartCalls()).isZero();
        verifyNoInteractions(imageValidator);
    }

    private HttpResponse<String> postMultipart(
            SessionClient client, String appPath, String boundary, byte[] body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(uri(appPath))
                .header(client.csrfHeader(), client.csrfToken())
                .header(HttpHeaders.CONTENT_TYPE, "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return client.http().send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postChunkedMultipart(
            SessionClient client, String appPath, String boundary, byte[] body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(uri(appPath))
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

    private URI uri(String appPath) {
        return URI.create("http://localhost:" + port + CONTEXT + appPath);
    }

    private Doctor createDoctor() {
        var spec = new Specialization();
        spec.setName("tomcat-ctx-spec-" + UUID.randomUUID());
        spec = specializations.save(spec);

        var user = new User();
        user.setEmail("tomcat-ctx-" + UUID.randomUUID() + "@example.invalid");
        user.setPassword(passwordEncoder.encode(TEST_PASSWORD));
        user.setRole(Role.DOCTOR);
        user = users.save(user);

        var doc = new Doctor();
        doc.setUser(user);
        doc.setSpecialization(spec);
        doc.setFirstName("Context");
        doc.setLastName("Doctor");
        return doctors.save(doc);
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
        image.setRGB(4, 4, Color.GREEN.getRGB());
        var bytes = new ByteArrayOutputStream();
        try (var stream = new MemoryCacheImageOutputStream(bytes)) {
            ImageIO.write(image, "png", stream);
        }
        return bytes.toByteArray();
    }

    private record SessionClient(HttpClient http, String csrfHeader, String csrfToken) {}
}

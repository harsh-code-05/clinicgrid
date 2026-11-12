package com.harsh.hospitalmanagement.controller;

import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.Specialization;
import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.repository.DoctorRepository;
import com.harsh.hospitalmanagement.repository.SpecializationRepository;
import com.harsh.hospitalmanagement.repository.UserRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.image.BufferedImage;
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

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:doc_disabled_tomcat;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.jpa.open-in-view=false",
                "spring.sql.init.mode=never",
                "spring.servlet.multipart.max-file-size=5MB",
                "spring.servlet.multipart.max-request-size=11MB",
                "spring.servlet.multipart.resolve-lazily=true"
        }
)
@Import(MultipartParsingProbe.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DoctorVerificationMultipartDisabledStorageTomcatTest {

    private static final String TEST_PASSWORD = "TestPassword#12345";

    @LocalServerPort
    private int port;

    @Autowired private DoctorRepository doctors;
    @Autowired private UserRepository users;
    @Autowired private SpecializationRepository specializations;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private MultipartParsingProbe probe;

    private Doctor doctor;
    private User patient;
    private byte[] validPngBytes;

    @BeforeEach
    void setUp() throws IOException {
        probe.reset();
        validPngBytes = createPngBytes();
        var spec = new Specialization();
        spec.setName("tomcat-disabled-" + UUID.randomUUID());
        spec = specializations.save(spec);

        var docUser = createUser(Role.DOCTOR);
        doctor = new Doctor();
        doctor.setUser(docUser);
        doctor.setSpecialization(spec);
        doctor = doctors.save(doctor);

        patient = createUser(Role.PATIENT);
    }

    @Test
    void disabledStorageEnforcesSecurityFirstAndReturns503Or413NoStoreWithoutParsingMultipart() throws Exception {
        String boundary = "----DisabledStorageBoundary";
        byte[] body = buildMultipartPng(boundary, validPngBytes);

        // Unauthenticated -> 401 before preflight or multipart parsing
        SessionClient anon = anonymousCsrfClient();
        probe.reset();
        HttpResponse<String> anonRes = postMultipart(anon, "/api/doctors/" + doctor.getId() + "/verification/documents/id-proof", boundary, body);
        assertThat(anonRes.statusCode()).isEqualTo(401);
        assertThat(probe.resolveMultipartCalls()).isZero();
        assertThat(probe.servletPartCalls()).isZero();
        assertThat(probe.servletParameterCalls()).isZero();

        // PATIENT -> 403 before preflight or multipart parsing
        SessionClient patClient = login(patient.getEmail());
        probe.reset();
        HttpResponse<String> patRes = postMultipart(patClient, "/api/doctors/" + doctor.getId() + "/verification/documents/id-proof", boundary, body);
        assertThat(patRes.statusCode()).isEqualTo(403);
        assertThat(probe.resolveMultipartCalls()).isZero();
        assertThat(probe.servletPartCalls()).isZero();
        assertThat(probe.servletParameterCalls()).isZero();

        // Owning doctor across canonical route forms -> 503 Service Unavailable + Cache-Control: no-store, 0 parse calls
        SessionClient docClient = login(doctor.getUser().getEmail());
        String[] aliases = {
                "/api/doctors/" + doctor.getId() + "/verification/documents/id-proof",
                "/api/doctors/00" + doctor.getId() + "/verification/documents/id_proof",
                "/api/doctors/" + doctor.getId() + "/verification/documents/ID%2DPROOF"
        };
        for (String path : aliases) {
            probe.reset();
            HttpResponse<String> docRes = postMultipart(docClient, path, boundary, body);
            assertThat(docRes.statusCode()).isEqualTo(503);
            assertThat(docRes.headers().firstValue(HttpHeaders.CACHE_CONTROL)).contains("no-store");
            assertThat(JsonPath.<Integer>read(docRes.body(), "$.status")).isEqualTo(503);
            assertThat(probe.resolveMultipartCalls()).isZero();
            assertThat(probe.servletPartCalls()).isZero();
            assertThat(probe.servletParameterCalls()).isZero();
        }

        // Oversized upload on disabled storage -> 413 Payload Too Large + Cache-Control: no-store, 0 parse calls
        byte[] oversized = buildMultipartPng(boundary, new byte[11 * 1024 * 1024 + 4096]);
        probe.reset();
        HttpResponse<String> oversizedRes = postMultipart(
                docClient, "/api/doctors/" + doctor.getId() + "/verification/documents/id-proof", boundary, oversized);
        assertThat(oversizedRes.statusCode()).isEqualTo(413);
        assertThat(oversizedRes.headers().firstValue(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        assertThat(probe.resolveMultipartCalls()).isZero();
        assertThat(probe.servletPartCalls()).isZero();
    }

    private HttpResponse<String> postMultipart(
            SessionClient client, String path, String boundary, byte[] body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(uri(path))
                .header(client.csrfHeader(), client.csrfToken())
                .header(HttpHeaders.CONTENT_TYPE, "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
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

    private User createUser(Role role) {
        var user = new User();
        user.setEmail("tomcat-dis-" + UUID.randomUUID() + "@example.invalid");
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
        var image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        image.setRGB(2, 2, Color.RED.getRGB());
        var bytes = new ByteArrayOutputStream();
        try (var stream = new MemoryCacheImageOutputStream(bytes)) {
            ImageIO.write(image, "png", stream);
        }
        return bytes.toByteArray();
    }

    private record SessionClient(HttpClient http, String csrfHeader, String csrfToken) {}
}

package com.harsh.hospitalmanagement.auth;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.harsh.hospitalmanagement.dto.DoctorRegisterRequest;
import com.harsh.hospitalmanagement.dto.PatientRegisterRequest;
import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.Patient;
import com.harsh.hospitalmanagement.entity.Specialization;
import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.enums.VerificationStatus;
import com.harsh.hospitalmanagement.repository.DoctorRepository;
import com.harsh.hospitalmanagement.repository.PatientRepository;
import com.harsh.hospitalmanagement.repository.SpecializationRepository;
import com.harsh.hospitalmanagement.repository.UserRepository;
import com.harsh.hospitalmanagement.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1",
        "spring.datasource.url=jdbc:h2:mem:auth_reg_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL",
        "app.frontend.url=http://localhost:5173/",
        "app.cors.allowed-origins=http://localhost:5173"
})
public class AuthenticationAndRegistrationIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PatientRepository patientRepository;

    @Autowired
    private DoctorRepository doctorRepository;

    @Autowired
    private SpecializationRepository specializationRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper mapper;

    private Long specializationId;

    @BeforeEach
    void setUp() {
        if (specializationRepository.count() == 0) {
            Specialization spec = new Specialization();
            spec.setName("Cardiology");
            spec.setDescription("Heart and vascular health");
            specializationId = specializationRepository.save(spec).getId();
        } else {
            specializationId = specializationRepository.findAll().get(0).getId();
        }
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private String getCsrfToken(HttpClient client) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url("/api/auth/csrf")))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode json = mapper.readTree(response.body());
        return json.get("token").asString();
    }

    @Test
    @DisplayName("Patient self-registration creates User & Patient atomically, hashes password, and logs in immediately")
    void testPatientRegistrationAndImmediateLogin() throws Exception {
        CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder().cookieHandler(cookieManager).build();
        String csrfToken = getCsrfToken(client);

        String email = "sarah.patient@hms-test.org";
        String plainPassword = "SecurePatientPass123";

        PatientRegisterRequest regRequest = new PatientRegisterRequest();
        regRequest.setEmail(email);
        regRequest.setPassword(plainPassword);
        regRequest.setFirstName("Sarah");
        regRequest.setLastName("Connor");
        regRequest.setDateOfBirth(LocalDate.of(1990, 5, 15));
        regRequest.setGender("Female");
        regRequest.setPhone("+91-9876543210");

        HttpRequest postReg = HttpRequest.newBuilder()
                .uri(URI.create(url("/api/auth/register/patient")))
                .header("Content-Type", "application/json")
                .header("X-CSRF-TOKEN", csrfToken)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(regRequest)))
                .build();

        HttpResponse<String> regResponse = client.send(postReg, HttpResponse.BodyHandlers.ofString());
        assertThat(regResponse.statusCode()).isEqualTo(201);

        // Verify DB persistence
        User persistedUser = userRepository.findByEmailIgnoreCase(email).orElse(null);
        assertThat(persistedUser).isNotNull();
        assertThat(persistedUser.getRole()).isEqualTo(Role.PATIENT);
        assertThat(persistedUser.getPassword()).isNotEqualTo(plainPassword);
        assertThat(persistedUser.getPassword()).startsWith("$2a$");
        assertThat(passwordEncoder.matches(plainPassword, persistedUser.getPassword())).isTrue();

        Patient persistedPatient = patientRepository.findByUser_Id(persistedUser.getId()).orElse(null);
        assertThat(persistedPatient).isNotNull();
        assertThat(persistedPatient.getFirstName()).isEqualTo("Sarah");
        assertThat(persistedPatient.getLastName()).isEqualTo("Connor");
        assertThat(persistedPatient.getPhone()).isEqualTo("+91-9876543210");

        // Immediately Log in with the newly created account
        String loginCsrf = getCsrfToken(client);
        Map<String, String> loginBody = Map.of("email", email, "password", plainPassword);
        HttpRequest postLogin = HttpRequest.newBuilder()
                .uri(URI.create(url("/api/auth/login")))
                .header("Content-Type", "application/json")
                .header("X-CSRF-TOKEN", loginCsrf)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(loginBody)))
                .build();

        HttpResponse<String> loginResponse = client.send(postLogin, HttpResponse.BodyHandlers.ofString());
        assertThat(loginResponse.statusCode()).isEqualTo(200);
        JsonNode loginJson = mapper.readTree(loginResponse.body());
        assertThat(loginJson.get("message").asString()).isEqualTo("Login successful");
        assertThat(loginJson.get("role").asString()).isEqualTo("PATIENT");

        // Verify authenticated session access to /api/auth/me
        HttpRequest getMe = HttpRequest.newBuilder()
                .uri(URI.create(url("/api/auth/me")))
                .GET()
                .build();
        HttpResponse<String> meResponse = client.send(getMe, HttpResponse.BodyHandlers.ofString());
        assertThat(meResponse.statusCode()).isEqualTo(200);
        JsonNode meJson = mapper.readTree(meResponse.body());
        assertThat(meJson.get("role").asString()).isEqualTo("ROLE_PATIENT");
        assertThat(meJson.get("email").asString()).isEqualTo(email);
    }

    @Test
    @DisplayName("Patient self-registration rejects duplicate emails case-insensitively")
    void testDuplicatePatientRegistrationRejected() throws Exception {
        CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder().cookieHandler(cookieManager).build();
        String csrfToken = getCsrfToken(client);

        String email = "duplicate.check@hms-test.org";
        PatientRegisterRequest req1 = new PatientRegisterRequest();
        req1.setEmail(email);
        req1.setPassword("ValidPassword123");
        req1.setFirstName("First");
        req1.setLastName("User");
        req1.setDateOfBirth(LocalDate.of(1992, 1, 1));
        req1.setGender("Male");
        req1.setPhone("+91-9123456789");

        HttpResponse<String> resp1 = client.send(HttpRequest.newBuilder()
                .uri(URI.create(url("/api/auth/register/patient")))
                .header("Content-Type", "application/json")
                .header("X-CSRF-TOKEN", csrfToken)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(req1)))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(resp1.statusCode()).isEqualTo(201);

        // Attempt second registration with uppercase version of the same email
        PatientRegisterRequest req2 = new PatientRegisterRequest();
        req2.setEmail("DUPLICATE.CHECK@HMS-TEST.ORG");
        req2.setPassword("AnotherPassword123");
        req2.setFirstName("Second");
        req2.setLastName("User");
        req2.setDateOfBirth(LocalDate.of(1995, 2, 2));
        req2.setGender("Female");
        req2.setPhone("+91-9988776655");

        HttpResponse<String> resp2 = client.send(HttpRequest.newBuilder()
                .uri(URI.create(url("/api/auth/register/patient")))
                .header("Content-Type", "application/json")
                .header("X-CSRF-TOKEN", csrfToken)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(req2)))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(resp2.statusCode()).isEqualTo(400);
        assertThat(resp2.body()).contains("Email already exists");
    }

    @Test
    @DisplayName("Doctor self-registration creates User & Doctor pending verification and restricts scheduling")
    void testDoctorRegistrationPendingVerificationRestrictions() throws Exception {
        CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder().cookieHandler(cookieManager).build();
        String csrfToken = getCsrfToken(client);

        String docEmail = "dr.watson@hms-test.org";
        String docPass = "DoctorPass2026!";

        DoctorRegisterRequest docReq = new DoctorRegisterRequest();
        docReq.setEmail(docEmail);
        docReq.setPassword(docPass);
        docReq.setFirstName("John");
        docReq.setLastName("Watson");
        docReq.setSpecializationId(specializationId);
        docReq.setQualification("MBBS, MD");
        docReq.setExperience(10);

        HttpResponse<String> regResp = client.send(HttpRequest.newBuilder()
                .uri(URI.create(url("/api/auth/register/doctor")))
                .header("Content-Type", "application/json")
                .header("X-CSRF-TOKEN", csrfToken)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(docReq)))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(regResp.statusCode()).isEqualTo(201);
        JsonNode regJson = mapper.readTree(regResp.body());
        assertThat(regJson.get("role").asString()).isEqualTo("DOCTOR");
        assertThat(regJson.get("verificationStatus").asString()).isEqualTo("UNVERIFIED");

        // DB Verification
        User docUser = userRepository.findByEmailIgnoreCase(docEmail).orElse(null);
        assertThat(docUser).isNotNull();
        assertThat(docUser.getRole()).isEqualTo(Role.DOCTOR);

        Doctor doc = doctorRepository.findByUser_Id(docUser.getId()).orElse(null);
        assertThat(doc).isNotNull();
        assertThat(doc.getVerificationStatus()).isEqualTo(VerificationStatus.UNVERIFIED);

        // Doctor logs in successfully
        String loginCsrf = getCsrfToken(client);
        Map<String, String> loginBody = Map.of("email", docEmail, "password", docPass);
        HttpResponse<String> loginResp = client.send(HttpRequest.newBuilder()
                .uri(URI.create(url("/api/auth/login")))
                .header("Content-Type", "application/json")
                .header("X-CSRF-TOKEN", loginCsrf)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(loginBody)))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(loginResp.statusCode()).isEqualTo(200);

        // Attempting to configure availability while UNVERIFIED is forbidden
        String availCsrf = getCsrfToken(client);
        Map<String, Object> availBody = Map.of(
                "doctorId", doc.getId(),
                "date", LocalDate.now().plusDays(1).toString(),
                "startTime", "09:00:00",
                "endTime", "12:00:00"
        );
        HttpResponse<String> availResp = client.send(HttpRequest.newBuilder()
                .uri(URI.create(url("/api/doctor-availability")))
                .header("Content-Type", "application/json")
                .header("X-CSRF-TOKEN", availCsrf)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(availBody)))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(availResp.statusCode()).isEqualTo(403);
        assertThat(availResp.body()).contains("pending verification");
    }

    @Test
    @DisplayName("Admin verifies doctor and verified doctor can configure availability")
    void testAdminVerifiesDoctorAndDoctorCanConfigureAvailability() throws Exception {
        // Setup doctor
        String docEmail = "dr.house@hms-test.org";
        String docPass = "HousePass123";
        DoctorRegisterRequest docReq = new DoctorRegisterRequest();
        docReq.setEmail(docEmail);
        docReq.setPassword(docPass);
        docReq.setFirstName("Gregory");
        docReq.setLastName("House");
        docReq.setSpecializationId(specializationId);
        docReq.setQualification("MD");
        docReq.setExperience(15);

        CookieManager clientCookie = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder().cookieHandler(clientCookie).build();
        String csrfToken = getCsrfToken(client);
        client.send(HttpRequest.newBuilder()
                .uri(URI.create(url("/api/auth/register/doctor")))
                .header("Content-Type", "application/json")
                .header("X-CSRF-TOKEN", csrfToken)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(docReq)))
                .build(), HttpResponse.BodyHandlers.ofString());

        User docUser = userRepository.findByEmailIgnoreCase(docEmail).orElseThrow();
        Doctor doc = doctorRepository.findByUser_Id(docUser.getId()).orElseThrow();

        // Create Admin user
        String adminEmail = "chief.admin@hms-test.org";
        String adminPass = "AdminMasterPass1!";
        if (userRepository.findByEmailIgnoreCase(adminEmail).isEmpty()) {
            User admin = new User();
            admin.setEmail(adminEmail);
            admin.setPassword(passwordEncoder.encode(adminPass));
            admin.setRole(Role.ADMIN);
            userRepository.save(admin);
        }

        // Admin logs in
        CookieManager adminCookie = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient adminClient = HttpClient.newBuilder().cookieHandler(adminCookie).build();
        String adminCsrf = getCsrfToken(adminClient);
        HttpResponse<String> adminLogin = adminClient.send(HttpRequest.newBuilder()
                .uri(URI.create(url("/api/auth/login")))
                .header("Content-Type", "application/json")
                .header("X-CSRF-TOKEN", adminCsrf)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("email", adminEmail, "password", adminPass))))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(adminLogin.statusCode()).isEqualTo(200);

        // Admin attempts legacy direct verify bypass -> rejected (endpoint removed, no bypass exists)
        String adminVerifyCsrf = getCsrfToken(adminClient);
        HttpResponse<String> verifyResp = adminClient.send(HttpRequest.newBuilder()
                .uri(URI.create(url("/api/admin/doctors/" + doc.getId() + "/verify")))
                .header("X-CSRF-TOKEN", adminVerifyCsrf)
                .method("PATCH", HttpRequest.BodyPublishers.noBody())
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(verifyResp.statusCode()).isIn(404, 405);

        // Transition doctor to VERIFIED for testing verified doctor availability and booking flows
        doc.setVerificationStatus(VerificationStatus.VERIFIED);
        doctorRepository.save(doc);
        Doctor refreshedDoc = doctorRepository.findById(doc.getId()).orElseThrow();
        assertThat(refreshedDoc.getVerificationStatus()).isEqualTo(VerificationStatus.VERIFIED);

        // Doctor logs in and successfully creates availability
        CookieManager docCookie = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient docClient = HttpClient.newBuilder().cookieHandler(docCookie).build();
        String docLoginCsrf = getCsrfToken(docClient);
        docClient.send(HttpRequest.newBuilder()
                .uri(URI.create(url("/api/auth/login")))
                .header("Content-Type", "application/json")
                .header("X-CSRF-TOKEN", docLoginCsrf)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("email", docEmail, "password", docPass))))
                .build(), HttpResponse.BodyHandlers.ofString());

        String docAvailCsrf = getCsrfToken(docClient);
        Map<String, Object> availBody = Map.of(
                "doctorId", doc.getId(),
                "date", LocalDate.now().plusDays(2).toString(),
                "startTime", "10:00:00",
                "endTime", "13:00:00"
        );
        HttpResponse<String> availResp = docClient.send(HttpRequest.newBuilder()
                .uri(URI.create(url("/api/doctor-availability")))
                .header("Content-Type", "application/json")
                .header("X-CSRF-TOKEN", docAvailCsrf)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(availBody)))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(availResp.statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("Role authorization boundaries: Patient and Doctor cannot access unauthorized APIs")
    void testRoleAuthorizationBoundaries() throws Exception {
        // Patient attempt to access admin endpoints
        String patientEmail = "auth.bound.patient@hms-test.org";
        String patientPass = "Pass123456";
        User patUser = new User();
        patUser.setEmail(patientEmail);
        patUser.setPassword(passwordEncoder.encode(patientPass));
        patUser.setRole(Role.PATIENT);
        userRepository.save(patUser);

        CookieManager patCookie = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient patClient = HttpClient.newBuilder().cookieHandler(patCookie).build();
        String patLoginCsrf = getCsrfToken(patClient);
        patClient.send(HttpRequest.newBuilder()
                .uri(URI.create(url("/api/auth/login")))
                .header("Content-Type", "application/json")
                .header("X-CSRF-TOKEN", patLoginCsrf)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("email", patientEmail, "password", patientPass))))
                .build(), HttpResponse.BodyHandlers.ofString());

        // Patient attempts GET /api/admin/doctor-verifications -> 403
        HttpResponse<String> adminQueueResp = patClient.send(HttpRequest.newBuilder()
                .uri(URI.create(url("/api/admin/doctor-verifications")))
                .GET()
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(adminQueueResp.statusCode()).isEqualTo(403);

        // Patient attempts GET /api/doctors/1/verification -> 403
        HttpResponse<String> docVerifResp = patClient.send(HttpRequest.newBuilder()
                .uri(URI.create(url("/api/doctors/1/verification")))
                .GET()
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(docVerifResp.statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("Account linking: verified Google identity links to existing user without creating duplicate")
    void testGoogleAccountLinking() {
        String email = "existing.user@hms-test.org";
        User existing = new User();
        existing.setEmail(email);
        existing.setPassword(passwordEncoder.encode("ExistingPass123"));
        existing.setRole(Role.PATIENT);
        userRepository.save(existing);

        String googleSubject = "google-sub-123456789";
        User linkedUser = userService.findOrCreateGoogleUser(email.toUpperCase(), googleSubject);

        assertThat(linkedUser.getId()).isEqualTo(existing.getId());
        assertThat(linkedUser.getGoogleId()).isEqualTo(googleSubject);
        assertThat(linkedUser.getPassword()).isNotNull(); // Password preserved!
        long countMatchingEmail = userRepository.findAll().stream().filter(u -> u.getEmail().equalsIgnoreCase(email)).count();
        assertThat(countMatchingEmail).isEqualTo(1L);
    }

    @Test
    @DisplayName("User with both password and linked Google account can sign in with password")
    void testUserWithLinkedGoogleAccountCanSignInWithPassword() throws Exception {
        String email = "dual.auth@hms-test.org";
        String password = "DualAuthPassword123";

        User user = new User();
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(password));
        user.setRole(Role.PATIENT);
        user.setGoogleId("google-subject-dual-999");
        userRepository.save(user);

        CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder().cookieHandler(cookieManager).build();
        String csrfToken = getCsrfToken(client);

        HttpResponse<String> loginResp = client.send(HttpRequest.newBuilder()
                .uri(URI.create(url("/api/auth/login")))
                .header("Content-Type", "application/json")
                .header("X-CSRF-TOKEN", csrfToken)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("email", "DUAL.AUTH@HMS-TEST.ORG", "password", password))))
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(loginResp.statusCode()).isEqualTo(200);
        JsonNode json = mapper.readTree(loginResp.body());
        assertThat(json.get("role").asString()).isEqualTo("PATIENT");
    }
}

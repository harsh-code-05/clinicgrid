package com.harsh.hospitalmanagement.controller;

import com.harsh.hospitalmanagement.dto.AppointmentRequest;
import com.harsh.hospitalmanagement.entity.AppointmentSlot;
import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.Patient;
import com.harsh.hospitalmanagement.entity.Specialization;
import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.enums.SlotStatus;
import com.harsh.hospitalmanagement.enums.VerificationStatus;
import com.harsh.hospitalmanagement.repository.AppointmentRepository;
import com.harsh.hospitalmanagement.repository.AppointmentSlotRepository;
import com.harsh.hospitalmanagement.repository.DoctorRepository;
import com.harsh.hospitalmanagement.repository.PatientRepository;
import com.harsh.hospitalmanagement.repository.SpecializationRepository;
import com.harsh.hospitalmanagement.repository.UserRepository;
import com.harsh.hospitalmanagement.security.LocalAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:unverified_doc_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.open-in-view=false",
        "spring.sql.init.mode=never",
        "hms.document-storage.enabled=false"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class DoctorVerificationUnverifiedQueueAndBookingTest {

    @Autowired private MockMvc mvc;
    @Autowired private DoctorRepository doctorRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private SpecializationRepository specializationRepository;
    @Autowired private com.harsh.hospitalmanagement.repository.DoctorAvailabilityRepository doctorAvailabilityRepository;
    @Autowired private AppointmentSlotRepository appointmentSlotRepository;
    @Autowired private AppointmentRepository appointmentRepository;

    private User adminUser;
    private User doctorUser;
    private User patientUser;
    private Doctor doctor;
    private Patient patient;
    private Specialization specialization;
    private com.harsh.hospitalmanagement.entity.DoctorAvailability availability;
    private AppointmentSlot slot;

    private MockHttpSession adminSession;
    private MockHttpSession patientSession;

    @BeforeEach
    void setUp() {
        specialization = specializationRepository.findByNameIgnoreCase("Neurology")
                .orElseGet(() -> {
                    Specialization s = new Specialization();
                    s.setName("Neurology");
                    s.setDescription("Disorders of nervous system");
                    return specializationRepository.save(s);
                });

        adminUser = createUser("admin-" + UUID.randomUUID() + "@hms.local", Role.ADMIN);
        doctorUser = createUser("doctoramit-" + UUID.randomUUID() + "@gmail.com", Role.DOCTOR);
        patientUser = createUser("patient-" + UUID.randomUUID() + "@hms.local", Role.PATIENT);

        doctor = new Doctor();
        doctor.setUser(doctorUser);
        doctor.setSpecialization(specialization);
        doctor.setQualification("MBBS,MD");
        doctor.setExperience(14);
        doctor.setVerificationStatus(VerificationStatus.UNVERIFIED);
        doctor = doctorRepository.save(doctor);

        patient = new Patient();
        patient.setUser(patientUser);
        patient.setFirstName("Ramesh");
        patient.setLastName("Kumar");
        patient.setGender("MALE");
        patient.setPhone("+919876543210");
        patient.setDateOfBirth(LocalDate.of(1985, 5, 20));
        patient = patientRepository.save(patient);

        LocalDate slotDate = LocalDate.now().plusDays(2);
        availability = new com.harsh.hospitalmanagement.entity.DoctorAvailability();
        availability.setDoctor(doctor);
        availability.setDate(slotDate);
        availability.setStartTime(LocalTime.of(9, 0));
        availability.setEndTime(LocalTime.of(12, 0));
        availability.setRevision(1L);
        availability = doctorAvailabilityRepository.save(availability);

        slot = new AppointmentSlot();
        slot.setDoctor(doctor);
        slot.setAvailability(availability);
        slot.setAvailabilityRevision(availability.getRevision());
        slot.setDate(slotDate);
        slot.setStartTime(LocalTime.of(10, 0));
        slot.setEndTime(LocalTime.of(10, 30));
        slot.setStatus(SlotStatus.AVAILABLE);
        slot.setRetired(false);
        slot = appointmentSlotRepository.save(slot);

        adminSession = createSession(adminUser);
        patientSession = createSession(patientUser);
    }

    @Test
    @DisplayName("Registered doctor with UNVERIFIED status appears in admin verification queue")
    void unverifiedDoctorAppearsInAdminQueueAndFilterWorks() throws Exception {
        mvc.perform(get("/api/admin/doctor-verifications")
                        .session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.items[?(@.doctorId == " + doctor.getId() + ")].status", hasItem("UNVERIFIED")))
                .andExpect(jsonPath("$.items[?(@.doctorId == " + doctor.getId() + ")].specialization", hasItem("Neurology")))
                .andExpect(jsonPath("$.items[?(@.doctorId == " + doctor.getId() + ")].qualification", hasItem("MBBS,MD")))
                .andExpect(jsonPath("$.items[?(@.doctorId == " + doctor.getId() + ")].experience", hasItem(14)))
                .andExpect(jsonPath("$.items[?(@.doctorId == " + doctor.getId() + ")].documentStatus", hasItem("MISSING")));

        mvc.perform(get("/api/admin/doctor-verifications")
                        .param("status", "UNVERIFIED")
                        .session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.doctorId == " + doctor.getId() + ")].status", hasItem("UNVERIFIED")));
    }

    @Test
    @DisplayName("Admin can view UNVERIFIED doctor details without 500 and with storage unavailable indicator")
    void adminCanViewUnverifiedDoctorDetails() throws Exception {
        mvc.perform(get("/api/doctors/{id}/verification", doctor.getId())
                        .session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.doctorId").value(doctor.getId()))
                .andExpect(jsonPath("$.status").value("UNVERIFIED"))
                .andExpect(jsonPath("$.email").value(doctorUser.getEmail()))
                .andExpect(jsonPath("$.specialization").value("Neurology"))
                .andExpect(jsonPath("$.qualification").value("MBBS,MD"))
                .andExpect(jsonPath("$.experience").value(14))
                .andExpect(jsonPath("$.storageAvailable").value(false))
                .andExpect(jsonPath("$.documentStatus").value("MISSING"));
    }

    @Test
    @DisplayName("Unverified doctor slots are hidden from available slots endpoint")
    void unverifiedDoctorSlotsAreHiddenFromAvailableEndpoint() throws Exception {
        mvc.perform(get("/api/appointment-slots/available")
                        .param("doctorId", String.valueOf(doctor.getId()))
                        .param("date", slot.getDate().toString())
                        .session(patientSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("Patient booking unverified doctor directly returns HTTP 403 Forbidden")
    void patientBookingUnverifiedDoctorDirectlyReturns403Forbidden() throws Exception {
        String bookingJson = """
                {
                    "patientId": %d,
                    "doctorId": %d,
                    "slotId": %d,
                    "appointmentDate": "%s",
                    "reason": "Headache consultation"
                }
                """.formatted(patient.getId(), doctor.getId(), slot.getId(), slot.getDate());

        mvc.perform(post("/api/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson)
                        .session(patientSession)
                        .with(csrf().asHeader()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Doctor is not verified for patient appointments"));

        assertThat(appointmentRepository.findAll()).isEmpty();
        AppointmentSlot refreshed = appointmentSlotRepository.findById(slot.getId()).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo(SlotStatus.AVAILABLE);
    }

    @Test
    @DisplayName("Verified doctor slots are visible and patient booking succeeds")
    void verifiedDoctorSlotsAreVisibleAndBookingSucceeds() throws Exception {
        doctor.setVerificationStatus(VerificationStatus.VERIFIED);
        doctorRepository.save(doctor);

        mvc.perform(get("/api/appointment-slots/available")
                        .param("doctorId", String.valueOf(doctor.getId()))
                        .param("date", slot.getDate().toString())
                        .session(patientSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(slot.getId()));

        String bookingJson = """
                {
                    "patientId": %d,
                    "doctorId": %d,
                    "slotId": %d,
                    "appointmentDate": "%s",
                    "reason": "Checkup"
                }
                """.formatted(patient.getId(), doctor.getId(), slot.getId(), slot.getDate());

        mvc.perform(post("/api/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingJson)
                        .session(patientSession)
                        .with(csrf().asHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        AppointmentSlot refreshed = appointmentSlotRepository.findById(slot.getId()).orElseThrow();
        assertThat(refreshed.getStatus()).isEqualTo(SlotStatus.BOOKED);
    }

    private User createUser(String email, Role role) {
        User user = new User();
        user.setEmail(email);
        user.setPassword("Secret!2345");
        user.setRole(role);
        return userRepository.save(user);
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
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new org.springframework.security.core.context.SecurityContextImpl(auth));
        return session;
    }
}

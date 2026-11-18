package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.dto.DoctorAvailabilityRequest;
import com.harsh.hospitalmanagement.entity.Appointment;
import com.harsh.hospitalmanagement.entity.AppointmentSlot;
import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.DoctorAvailability;
import com.harsh.hospitalmanagement.entity.Patient;
import com.harsh.hospitalmanagement.entity.Specialization;
import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.enums.AppointmentSlotOutcome;
import com.harsh.hospitalmanagement.enums.AppointmentStatus;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.enums.SlotStatus;
import com.harsh.hospitalmanagement.enums.VerificationStatus;
import com.harsh.hospitalmanagement.exception.BadRequestException;
import com.harsh.hospitalmanagement.exception.ConflictException;
import com.harsh.hospitalmanagement.repository.AppointmentRepository;
import com.harsh.hospitalmanagement.repository.AppointmentSlotRepository;
import com.harsh.hospitalmanagement.security.LocalAccount;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.EmbeddedDatabaseConnection;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop", showSql = false)
@AutoConfigureTestDatabase(connection = EmbeddedDatabaseConnection.H2,
        replace = AutoConfigureTestDatabase.Replace.ANY)
@Import({DoctorAvailabilityService.class, AppointmentSlotService.class, AppointmentService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AppointmentCancellationReconciliationTest {

    private static final LocalAccount ADMIN = new LocalAccount(0, "admin@test.invalid");

    @Autowired
    private DoctorAvailabilityService availabilityService;

    @Autowired
    private AppointmentSlotService slotService;

    @Autowired
    private AppointmentService appointmentService;

    @Autowired
    private AppointmentRepository appointmentRepository;

    @Autowired
    private AppointmentSlotRepository slotRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private AuditLogService auditLogService;

    private final LocalDate day = LocalDate.now().plusDays(3);

    @Test
    void cancellingOverlappingAppointmentBlocksSlotAndAdminUnblockPermitsExplicitRepublication() {
        Doctor doctor = createDoctor();
        Patient firstPatient = createPatient();
        Patient secondPatient = createPatient();

        DoctorAvailability window = availabilityService.createAvailability(
                request(doctor, day, "09:00", "10:00"), account(doctor.getUser()), false);
        AppointmentSlot primarySlot = slotService.generateSlots(window.getId(), ADMIN, true).getFirst();

        // Overlapping active slot representing a legacy overlapping pair before cancellation.
        AppointmentSlot overlappingSlot = transaction(() -> {
            AppointmentSlot s = new AppointmentSlot();
            s.setDoctor(doctor);
            s.setDate(day);
            s.setStartTime(LocalTime.of(9, 15));
            s.setEndTime(LocalTime.of(9, 45));
            s.setStatus(SlotStatus.BOOKED);
            s.setAvailability(window);
            s.setAvailabilityRevision(window.getRevision());
            s.setRetired(false);
            entityManager.persist(s);
            return s;
        });

        Appointment primaryBooking = appointmentService.bookAppointment(
                firstPatient.getId(), doctor.getId(), primarySlot.getId(), day, "Primary", account(firstPatient.getUser()));

        Appointment overlappingBooking = transaction(() -> {
            Appointment a = new Appointment();
            a.setPatient(secondPatient);
            a.setDoctor(doctor);
            a.setSlot(overlappingSlot);
            a.setStatus(AppointmentStatus.CONFIRMED);
            a.setReason("Legacy overlap");
            entityManager.persist(a);
            return a;
        });

        Appointment cancelledOverlap = appointmentService.cancelAppointment(
                overlappingBooking.getId(), account(secondPatient.getUser()), "ROLE_PATIENT", "Rescheduled");
        assertThat(cancelledOverlap.getCancellationSlotOutcome()).isEqualTo(AppointmentSlotOutcome.OVERLAP);
        assertThat(slotService.getSlotById(overlappingSlot.getId()).getStatus()).isEqualTo(SlotStatus.BLOCKED);
        assertThat(slotService.getAvailableSlots(doctor.getId(), day))
                .extracting(AppointmentSlot::getId)
                .doesNotContain(overlappingSlot.getId());

        // Cannot unblock while primaryBooking still occupies 09:00-09:30.
        assertThatThrownBy(() -> slotService.unblockSlot(overlappingSlot.getId(), ADMIN))
                .isInstanceOf(ConflictException.class)
                .satisfies(ex -> assertThat(((ConflictException) ex).getCode()).isEqualTo("SLOT_CONFLICTING_BOOKING"));

        // Cancel primaryBooking; primary slot releases cleanly, overlappingSlot stays BLOCKED until admin unblocks it.
        Appointment cancelledPrimary = appointmentService.cancelAppointment(
                primaryBooking.getId(), account(firstPatient.getUser()), "ROLE_PATIENT", "Plan changed");
        assertThat(cancelledPrimary.getCancellationSlotOutcome()).isEqualTo(AppointmentSlotOutcome.RELEASED);
        assertThat(slotService.getSlotById(overlappingSlot.getId()).getStatus()).isEqualTo(SlotStatus.BLOCKED);

        // Unblocking moves BLOCKED -> (AVAILABLE, retired=true), preventing direct un-republished booking.
        AppointmentSlot unblocked = slotService.unblockSlot(overlappingSlot.getId(), ADMIN);
        assertThat(unblocked.getStatus()).isEqualTo(SlotStatus.AVAILABLE);
        assertThat(unblocked.isRetired()).isTrue();
        assertThatThrownBy(() -> appointmentService.bookAppointment(
                firstPatient.getId(), doctor.getId(), overlappingSlot.getId(), day, "Direct try", account(firstPatient.getUser())))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void cancellingPublishedSlotReopensSlotAndExcludesOverlapsFromAvailableListing() {
        Doctor doctor = createDoctor();
        Patient patient = createPatient();
        DoctorAvailability window = availabilityService.createAvailability(
                request(doctor, day, "11:00", "12:00"), account(doctor.getUser()), false);
        List<AppointmentSlot> generated = slotService.generateSlots(window.getId(), ADMIN, true);
        AppointmentSlot target = generated.getFirst();

        Appointment booked = appointmentService.bookAppointment(
                patient.getId(), doctor.getId(), target.getId(), day, "Visit", account(patient.getUser()));
        Appointment cancelled = appointmentService.cancelAppointment(
                booked.getId(), account(patient.getUser()), "ROLE_PATIENT", "Feeling better");

        assertThat(cancelled.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(cancelled.getCancellationReason()).isEqualTo("Feeling better");
        assertThat(cancelled.getCancelledByRole()).isEqualTo(Role.PATIENT);
        assertThat(cancelled.getCancellationSlotOutcome()).isEqualTo(AppointmentSlotOutcome.RELEASED);
        assertThat(slotRepository.isBookable(target.getId())).isTrue();
        assertThat(slotService.getAvailableSlots(doctor.getId(), day))
                .extracting(AppointmentSlot::getId)
                .contains(target.getId());
    }

    private <T> T transaction(java.util.function.Supplier<T> operation) {
        return new TransactionTemplate(transactionManager).execute(ignored -> operation.get());
    }

    private DoctorAvailabilityRequest request(Doctor doctor, LocalDate date, String start, String end) {
        DoctorAvailabilityRequest request = new DoctorAvailabilityRequest();
        request.setDoctorId(doctor.getId());
        request.setDate(date);
        request.setStartTime(LocalTime.parse(start));
        request.setEndTime(LocalTime.parse(end));
        return request;
    }

    private Doctor createDoctor() {
        return transaction(() -> {
            Specialization specialization = new Specialization();
            specialization.setName(UUID.randomUUID().toString());
            entityManager.persist(specialization);
            Doctor doctor = new Doctor();
            doctor.setUser(createUser(Role.DOCTOR));
            doctor.setSpecialization(specialization);
            doctor.setVerificationStatus(VerificationStatus.VERIFIED);
            entityManager.persist(doctor);
            return doctor;
        });
    }

    private Patient createPatient() {
        return transaction(() -> {
            Patient patient = new Patient();
            patient.setFirstName("Test");
            patient.setLastName("Patient");
            patient.setUser(createUser(Role.PATIENT));
            entityManager.persist(patient);
            return patient;
        });
    }

    private User createUser(Role role) {
        User user = new User();
        user.setEmail(UUID.randomUUID() + "@test.invalid");
        user.setRole(role);
        entityManager.persist(user);
        return user;
    }

    private static LocalAccount account(User user) {
        return new LocalAccount(user.getId(), user.getEmail());
    }
}

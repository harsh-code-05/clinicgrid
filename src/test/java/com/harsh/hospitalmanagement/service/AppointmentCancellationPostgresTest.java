package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.config.safety.PostgresTestTarget;
import com.harsh.hospitalmanagement.config.safety.RequiresDisposablePostgres;
import com.harsh.hospitalmanagement.dto.DoctorAvailabilityRequest;
import com.harsh.hospitalmanagement.entity.Appointment;
import com.harsh.hospitalmanagement.entity.AppointmentSlot;
import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.DoctorLocation;
import com.harsh.hospitalmanagement.entity.Patient;
import com.harsh.hospitalmanagement.entity.Specialization;
import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.enums.AppointmentSlotOutcome;
import com.harsh.hospitalmanagement.enums.AppointmentStatus;
import com.harsh.hospitalmanagement.enums.ConsultationMode;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.enums.SlotStatus;
import com.harsh.hospitalmanagement.enums.VerificationStatus;
import com.harsh.hospitalmanagement.exception.ConflictException;
import com.harsh.hospitalmanagement.security.LocalAccount;
import com.harsh.hospitalmanagement.repository.DoctorRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

@RequiresDisposablePostgres
@DataJpaTest(properties = "spring.sql.init.mode=never", showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestTarget.TestDataSourceConfiguration.class, AppointmentService.class, AppointmentSlotService.class, DoctorAvailabilityService.class,
        DoctorLocationService.class, DoctorManagementAccess.class, AuditLogService.class, LgdDistrictDirectory.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AppointmentCancellationPostgresTest {

    @Autowired private AppointmentService appointments;
    @Autowired private AppointmentSlotService slots;
    @Autowired private DoctorAvailabilityService availability;
    @Autowired private DoctorLocationService locations;
    @Autowired private DoctorRepository doctorRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    private final LocalDate day = LocalDate.now().plusDays(2);
    private Fixture fixture;

    @DynamicPropertySource
    static void disposablePostgres(DynamicPropertyRegistry registry) {
        PostgresTestTarget.registerProperties(registry);
    }

    @BeforeEach
    void createFixtures() {
        fixture = transaction(() -> {
            Specialization specialization = new Specialization();
            specialization.setName("Cancel workflow PG test " + UUID.randomUUID());
            entityManager.persist(specialization);

            Doctor doctor = new Doctor();
            doctor.setSpecialization(specialization);
            doctor.setVerificationStatus(VerificationStatus.VERIFIED);
            doctor.setUser(createUser(Role.DOCTOR));
            entityManager.persist(doctor);

            Patient patient = new Patient();
            patient.setFirstName("Test");
            patient.setLastName("Patient");
            patient.setUser(createUser(Role.PATIENT));
            entityManager.persist(patient);
            return new Fixture(doctor.getId(), patient.getId(), specialization.getId(),
                    account(doctor.getUser()), account(patient.getUser()));
        });
    }

    @AfterEach
    void removeFixtures() {
        if (fixture == null) {
            return;
        }
        jdbc.update("DELETE FROM audit_logs WHERE entity_name = 'Appointment'");
        jdbc.update("DELETE FROM appointments WHERE doctor_id = ? OR patient_id = ?",
                fixture.doctorId(), fixture.patientId());
        jdbc.update("DELETE FROM appointment_slots WHERE doctor_id = ?", fixture.doctorId());
        jdbc.update("DELETE FROM doctor_availability WHERE doctor_id = ?", fixture.doctorId());
        jdbc.update("DELETE FROM doctor_locations WHERE doctor_id = ?", fixture.doctorId());
        jdbc.update("DELETE FROM patients WHERE id = ?", fixture.patientId());
        jdbc.update("DELETE FROM doctors WHERE id = ?", fixture.doctorId());
        jdbc.update("DELETE FROM specializations WHERE id = ?", fixture.specializationId());
        jdbc.update("DELETE FROM users WHERE id IN (?, ?)", fixture.doctor().id(), fixture.patient().id());
    }

    @Test
    void cancellationPersistsMetadataPreservesOfferSnapshotAndEnforcesUniqueConfirmedSlotInPostgres() {
        DoctorLocation clinic = locations.create(
                fixture.doctorId(),
                new DoctorLocationService.ClinicDetails(
                        "Care Clinic", "100 Health Way", null, "Central", "Pune", "Maharashtra", "411001",
                        "+91 8000000001", new BigDecimal("500.00"), true),
                auth(fixture.doctor(), "ROLE_DOCTOR"));
        var window = createInClinicAvailability(clinic.getId());
        AppointmentSlot offer = slots.generateSlots(window.getId(), fixture.doctor(), false).getFirst();
        UUID initialVersion = offer.getOfferVersion();

        Appointment booked = appointments.bookVersionedOffer(
                offer.getId(), offer.getDate(), initialVersion, "Routine checkup", fixture.patient());

        Appointment cancelled = appointments.cancelAppointment(
                booked.getId(), fixture.patient(), "ROLE_PATIENT", "Rescheduling visit");
        assertThat(cancelled.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(cancelled.getCancellationReason()).isEqualTo("Rescheduling visit");
        assertThat(cancelled.getCancelledAt()).isNotNull();
        assertThat(cancelled.getCancelledByRole()).isEqualTo(Role.PATIENT);
        assertThat(cancelled.getCancellationSlotOutcome()).isEqualTo(AppointmentSlotOutcome.RELEASED);
        assertThat(cancelled.getConsultationFee()).isEqualByComparingTo("500.00");
        assertThat(cancelled.getClinicNameSnapshot()).isEqualTo("Care Clinic");

        // Independent persistence assertion (Finding 3): clear persistence context and reload via JDBC and fresh EntityManager
        entityManager.clear();
        Map<String, Object> reloaded = jdbc.queryForMap(
                "SELECT status, cancellation_reason, cancelled_at, cancelled_by_user_id, cancelled_by_role, " +
                "cancellation_slot_outcome, consultation_fee, clinic_name_snapshot, clinic_address_snapshot, " +
                "location_id, consultation_mode, offer_version FROM appointments WHERE id = ?",
                booked.getId());
        assertThat(reloaded.get("status")).isEqualTo("CANCELLED");
        assertThat(reloaded.get("cancellation_reason")).isEqualTo("Rescheduling visit");
        assertThat(reloaded.get("cancelled_at")).isNotNull();
        assertThat(((Number) reloaded.get("cancelled_by_user_id")).longValue()).isEqualTo(fixture.patient().id());
        assertThat(reloaded.get("cancelled_by_role")).isEqualTo("PATIENT");
        assertThat(reloaded.get("cancellation_slot_outcome")).isEqualTo("RELEASED");
        assertThat(new BigDecimal(reloaded.get("consultation_fee").toString())).isEqualByComparingTo("500.00");
        assertThat(reloaded.get("clinic_name_snapshot")).isEqualTo("Care Clinic");
        assertThat(reloaded.get("clinic_address_snapshot")).isEqualTo("100 Health Way, Central, Pune, Maharashtra, 411001");
        assertThat(((Number) reloaded.get("location_id")).longValue()).isEqualTo(clinic.getId());
        assertThat(reloaded.get("consultation_mode")).isEqualTo("IN_CLINIC");
        assertThat((UUID) reloaded.get("offer_version")).isEqualTo(initialVersion);

        Appointment reloadedEntity = transaction(() -> entityManager.find(Appointment.class, booked.getId()));
        assertThat(reloadedEntity.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(reloadedEntity.getCancellationReason()).isEqualTo("Rescheduling visit");
        assertThat(reloadedEntity.getCancelledAt()).isNotNull();
        assertThat(reloadedEntity.getCancelledByUserId()).isEqualTo(fixture.patient().id());
        assertThat(reloadedEntity.getCancelledByRole()).isEqualTo(Role.PATIENT);
        assertThat(reloadedEntity.getCancellationSlotOutcome()).isEqualTo(AppointmentSlotOutcome.RELEASED);
        assertThat(reloadedEntity.getConsultationFee()).isEqualByComparingTo("500.00");
        assertThat(reloadedEntity.getClinicNameSnapshot()).isEqualTo("Care Clinic");
        assertThat(reloadedEntity.getOfferVersion()).isEqualTo(initialVersion);

        // Rebook after cancellation succeeds because partial unique index excludes CANCELLED.
        AppointmentSlot reopened = slots.getSlotById(offer.getId());
        assertThat(reopened.getStatus()).isEqualTo(SlotStatus.AVAILABLE);
        assertThat(reopened.getOfferVersion()).isNotEqualTo(initialVersion);

        Appointment rebooked = appointments.bookVersionedOffer(
                reopened.getId(), reopened.getDate(), reopened.getOfferVersion(), "Second booking", fixture.patient());

        // Inserting a second CONFIRMED appointment directly for the same slot hits uq_appointments_confirmed_slot.
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO appointments (patient_id, doctor_id, slot_id, status, reason) VALUES (?, ?, ?, 'CONFIRMED', 'Bypass')",
                fixture.patientId(), fixture.doctorId(), rebooked.getSlot().getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void nullSlotStatusFailsPreflightAndVerificationInvariant() {
        Long slotId = transaction(() -> {
            AppointmentSlot slot = new AppointmentSlot();
            slot.setDate(day);
            slot.setStartTime(LocalTime.of(15, 0));
            slot.setEndTime(LocalTime.of(15, 30));
            slot.setStatus(null);
            slot.setDoctor(entityManager.find(Doctor.class, fixture.doctorId()));
            slot.setRetired(false);
            entityManager.persist(slot);

            Appointment appt = new Appointment();
            appt.setDoctor(slot.getDoctor());
            appt.setPatient(entityManager.find(Patient.class, fixture.patientId()));
            appt.setSlot(slot);
            appt.setStatus(AppointmentStatus.CONFIRMED);
            appt.setReason("Preflight invariant probe");
            entityManager.persist(appt);
            return slot.getId();
        });

        // 1. Prove legacy <> evaluates to NULL/false on NULL slot status (undetected)
        Integer legacyCount = jdbc.queryForObject(
                "SELECT count(*) FROM appointments a JOIN appointment_slots s ON s.id = a.slot_id " +
                "WHERE a.status = 'CONFIRMED' AND a.slot_id = ? AND s.status <> 'BOOKED'",
                Integer.class, slotId);
        assertThat(legacyCount).isZero();

        // 2. Prove NULL-safe IS DISTINCT FROM 'BOOKED' reliably catches the violation
        Integer nullSafeCount = jdbc.queryForObject(
                "SELECT count(*) FROM appointments a JOIN appointment_slots s ON s.id = a.slot_id " +
                "WHERE a.status = 'CONFIRMED' AND a.slot_id = ? AND s.status IS DISTINCT FROM 'BOOKED'",
                Integer.class, slotId);
        assertThat(nullSafeCount).isEqualTo(1);
    }

    @Test
    void concurrentCancellationsOnSameAppointment_executeAtomicallyWithSingleAuditAndSingleSlotRelease() throws Exception {
        DoctorLocation clinic = locations.create(
                fixture.doctorId(),
                new DoctorLocationService.ClinicDetails(
                        "Care Clinic", "100 Health Way", null, "Central", "Pune", "Maharashtra", "411001",
                        "+91 8000000001", new BigDecimal("500.00"), true),
                auth(fixture.doctor(), "ROLE_DOCTOR"));
        var window = createInClinicAvailability(clinic.getId());
        AppointmentSlot offer = slots.generateSlots(window.getId(), fixture.doctor(), false).getFirst();

        Appointment booked = appointments.bookVersionedOffer(
                offer.getId(), offer.getDate(), offer.getOfferVersion(), "Concurrent test", fixture.patient());

        Long appointmentId = booked.getId();
        CountDownLatch winnerHasLock = new CountDownLatch(1);
        CountDownLatch releaseWinner = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);

        try {
            // Transaction 1: locks Doctor in PostgreSQL, signals winnerHasLock, and waits until releaseWinner
            Future<Appointment> f1 = executor.submit(() -> transactions.execute(status -> {
                doctorRepository.findByIdForUpdate(fixture.doctorId()).orElseThrow();
                winnerHasLock.countDown();
                try {
                    if (!releaseWinner.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting for release");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
                return appointments.cancelAppointment(appointmentId, fixture.patient(), "ROLE_PATIENT", "Reason A");
            }));

            assertThat(winnerHasLock.await(5, TimeUnit.SECONDS)).isTrue();

            // Transaction 2: attempts to cancel the same appointment while Transaction 1 holds the doctor row lock
            Future<Appointment> f2 = executor.submit(() ->
                    appointments.cancelAppointment(appointmentId, fixture.patient(), "ROLE_PATIENT", "Reason B"));

            // Observe contender backend actively blocked in PostgreSQL by winner's lock before releasing winner
            awaitPostgresLockContention();

            releaseWinner.countDown();

            Appointment res1 = f1.get(10, TimeUnit.SECONDS);
            Appointment res2 = f2.get(10, TimeUnit.SECONDS);

            assertThat(res1.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
            assertThat(res2.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
            // Both results agree on the same winning cancellation reason and timestamp
            assertThat(res1.getCancellationReason()).isEqualTo(res2.getCancellationReason());
            assertThat(res1.getCancelledAt()).isEqualTo(res2.getCancelledAt());
            assertThat(res1.getCancellationSlotOutcome()).isEqualTo(AppointmentSlotOutcome.RELEASED);
            assertThat(res2.getCancellationSlotOutcome()).isEqualTo(AppointmentSlotOutcome.RELEASED);

            // Exactly one audit log event is recorded in PostgreSQL
            Integer auditCount = jdbc.queryForObject(
                    "SELECT count(*) FROM audit_logs WHERE entity_name = 'Appointment' AND entity_id = ? " +
                    "AND event_type = 'PATIENT_CANCELLED_APPOINTMENT'",
                    Integer.class, appointmentId);
            assertThat(auditCount).isEqualTo(1);

            // Slot is released exactly once and remains AVAILABLE
            AppointmentSlot finalSlot = slots.getSlotById(offer.getId());
            assertThat(finalSlot.getStatus()).isEqualTo(SlotStatus.AVAILABLE);
        } finally {
            releaseWinner.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void cancellationRacingWithBookingOnSameSlot_preventsDoubleBookingAndStaleOfferBooking() throws Exception {
        DoctorLocation clinic = locations.create(
                fixture.doctorId(),
                new DoctorLocationService.ClinicDetails(
                        "Care Clinic", "100 Health Way", null, "Central", "Pune", "Maharashtra", "411001",
                        "+91 8000000001", new BigDecimal("500.00"), true),
                auth(fixture.doctor(), "ROLE_DOCTOR"));
        var window = createInClinicAvailability(clinic.getId());
        AppointmentSlot offer = slots.generateSlots(window.getId(), fixture.doctor(), false).getFirst();
        UUID initialOfferVersion = offer.getOfferVersion();

        Appointment booked = appointments.bookVersionedOffer(
                offer.getId(), offer.getDate(), initialOfferVersion, "Race booking", fixture.patient());

        // Create second patient
        Patient patient2 = transaction(() -> {
            Patient p = new Patient();
            p.setFirstName("Second");
            p.setLastName("Patient");
            p.setUser(createUser(Role.PATIENT));
            entityManager.persist(p);
            return p;
        });
        LocalAccount patient2Account = account(patient2.getUser());

        CountDownLatch winnerHasLock = new CountDownLatch(1);
        CountDownLatch releaseWinner = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);

        try {
            // Thread 1: holds PostgreSQL doctor lock, executes cancellation, and rotates offer version
            Future<Appointment> cancelFuture = executor.submit(() -> transactions.execute(status -> {
                doctorRepository.findByIdForUpdate(fixture.doctorId()).orElseThrow();
                winnerHasLock.countDown();
                try {
                    if (!releaseWinner.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting for release");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
                return appointments.cancelAppointment(booked.getId(), fixture.patient(), "ROLE_PATIENT", "Cancelled for race");
            }));

            assertThat(winnerHasLock.await(5, TimeUnit.SECONDS)).isTrue();

            // Thread 2: attempts to book with the stale initial offer version
            Future<Appointment> staleBookFuture = executor.submit(() ->
                    appointments.bookVersionedOffer(offer.getId(), offer.getDate(), initialOfferVersion, "Stale book", patient2Account));

            // Observe contender backend actively blocked in PostgreSQL by cancellation's doctor lock before releasing winner
            awaitPostgresLockContention();

            releaseWinner.countDown();

            Appointment cancelResult = cancelFuture.get(10, TimeUnit.SECONDS);
            assertThat(cancelResult.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);

            // Once cancellation commits and rotates offer version, booking unblocks and fails specifically with OFFER_CHANGED
            try {
                staleBookFuture.get(10, TimeUnit.SECONDS);
                fail("Expected ConflictException(OFFER_CHANGED) on stale offer booking");
            } catch (ExecutionException e) {
                assertThat(e.getCause()).isInstanceOf(ConflictException.class);
                ConflictException ce = (ConflictException) e.getCause();
                assertThat(ce.getCode()).isEqualTo("OFFER_CHANGED");
            }

            // Rebooking with the updated offer version succeeds
            AppointmentSlot reopenedSlot = slots.getSlotById(offer.getId());
            assertThat(reopenedSlot.getStatus()).isEqualTo(SlotStatus.AVAILABLE);
            assertThat(reopenedSlot.getOfferVersion()).isNotEqualTo(initialOfferVersion);

            Appointment validRebooking = appointments.bookVersionedOffer(
                    reopenedSlot.getId(), reopenedSlot.getDate(), reopenedSlot.getOfferVersion(), "Fresh rebooking", patient2Account);
            assertThat(validRebooking.getStatus()).isEqualTo(AppointmentStatus.CONFIRMED);

            // Exactly one CONFIRMED appointment exists on this slot in PostgreSQL
            Integer confirmedCount = jdbc.queryForObject(
                    "SELECT count(*) FROM appointments WHERE slot_id = ? AND status = 'CONFIRMED'",
                    Integer.class, offer.getId());
            assertThat(confirmedCount).isEqualTo(1);
        } finally {
            releaseWinner.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            jdbc.update("DELETE FROM appointments WHERE patient_id = ?", patient2.getId());
            jdbc.update("DELETE FROM patients WHERE id = ?", patient2.getId());
            jdbc.update("DELETE FROM users WHERE id = ?", patient2Account.id());
        }
    }

    @Test
    void cancellationFollowedByRebooking_retryCancellationPreservesNewBookingAndSlot() {
        DoctorLocation clinic = locations.create(
                fixture.doctorId(),
                new DoctorLocationService.ClinicDetails(
                        "Care Clinic", "100 Health Way", null, "Central", "Pune", "Maharashtra", "411001",
                        "+91 8000000001", new BigDecimal("500.00"), true),
                auth(fixture.doctor(), "ROLE_DOCTOR"));
        var window = createInClinicAvailability(clinic.getId());
        AppointmentSlot offer = slots.generateSlots(window.getId(), fixture.doctor(), false).getFirst();

        // 1. Patient 1 books Appointment A
        Appointment apptA = appointments.bookVersionedOffer(
                offer.getId(), offer.getDate(), offer.getOfferVersion(), "Booking A", fixture.patient());

        // 2. Patient 1 cancels Appointment A -> Slot released with new offer version
        Appointment cancelledA = appointments.cancelAppointment(
                apptA.getId(), fixture.patient(), "ROLE_PATIENT", "Cancel A");
        assertThat(cancelledA.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);

        AppointmentSlot reopenedSlot = slots.getSlotById(offer.getId());
        assertThat(reopenedSlot.getStatus()).isEqualTo(SlotStatus.AVAILABLE);

        // 3. Patient 2 books Appointment B on the reopened slot
        Patient patient2 = transaction(() -> {
            Patient p = new Patient();
            p.setFirstName("Patient");
            p.setLastName("Two");
            p.setUser(createUser(Role.PATIENT));
            entityManager.persist(p);
            return p;
        });
        LocalAccount patient2Account = account(patient2.getUser());

        Appointment apptB = appointments.bookVersionedOffer(
                reopenedSlot.getId(), reopenedSlot.getDate(), reopenedSlot.getOfferVersion(), "Booking B", patient2Account);
        assertThat(apptB.getStatus()).isEqualTo(AppointmentStatus.CONFIRMED);

        AppointmentSlot occupiedSlot = slots.getSlotById(offer.getId());
        assertThat(occupiedSlot.getStatus()).isEqualTo(SlotStatus.BOOKED);

        // 4. Retry cancellation on Appointment A
        Appointment retryA = appointments.cancelAppointment(
                apptA.getId(), fixture.patient(), "ROLE_PATIENT", "Different retry reason");
        assertThat(retryA.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(retryA.getCancellationReason()).isEqualTo("Cancel A"); // original reason preserved

        // 5. Assert Appointment B remains CONFIRMED and Slot remains BOOKED for Appointment B
        Appointment currentB = transaction(() -> entityManager.find(Appointment.class, apptB.getId()));
        assertThat(currentB.getStatus()).isEqualTo(AppointmentStatus.CONFIRMED);

        AppointmentSlot currentSlot = slots.getSlotById(offer.getId());
        assertThat(currentSlot.getStatus()).isEqualTo(SlotStatus.BOOKED);

        // Clean up patient2
        jdbc.update("DELETE FROM appointments WHERE patient_id = ?", patient2.getId());
        jdbc.update("DELETE FROM patients WHERE id = ?", patient2.getId());
        jdbc.update("DELETE FROM users WHERE id = ?", patient2Account.id());
    }

    private void awaitPostgresLockContention() {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            Integer blocked = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND cardinality(pg_blocking_pids(pid)) > 0",
                    Integer.class);
            if (blocked != null && blocked > 0) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        throw new AssertionError("Timed out waiting for contender to be blocked in PostgreSQL pg_stat_activity/pg_blocking_pids");
    }

    private com.harsh.hospitalmanagement.entity.DoctorAvailability createInClinicAvailability(Long locationId) {
        DoctorAvailabilityRequest request = new DoctorAvailabilityRequest();
        request.setDoctorId(fixture.doctorId());
        request.setDate(day);
        request.setStartTime(LocalTime.of(10, 0));
        request.setEndTime(LocalTime.of(10, 30));
        request.setConsultationMode(ConsultationMode.IN_CLINIC);
        request.setLocationId(locationId);
        return availability.createAvailability(request, fixture.doctor(), false);
    }

    private User createUser(Role role) {
        User user = new User();
        user.setEmail("cancel-pg-" + UUID.randomUUID() + "@test.invalid");
        user.setRole(role);
        entityManager.persist(user);
        return user;
    }

    private <T> T transaction(Supplier<T> action) {
        return new TransactionTemplate(transactionManager).execute(ignored -> action.get());
    }

    private static LocalAccount account(User user) {
        return new LocalAccount(user.getId(), user.getEmail());
    }

    private static UsernamePasswordAuthenticationToken auth(LocalAccount account, String authority) {
        return UsernamePasswordAuthenticationToken.authenticated(
                new LocalAccount.PasswordPrincipal(account, "", Role.valueOf(authority.replace("ROLE_", ""))),
                null,
                java.util.List.of(() -> authority));
    }

    private record Fixture(Long doctorId, Long patientId, Long specializationId,
                           LocalAccount doctor, LocalAccount patient) {
    }
}

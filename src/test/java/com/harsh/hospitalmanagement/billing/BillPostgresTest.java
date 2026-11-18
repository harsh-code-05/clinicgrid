package com.harsh.hospitalmanagement.billing;

import com.harsh.hospitalmanagement.config.safety.PostgresTestTarget;
import com.harsh.hospitalmanagement.config.safety.RequiresDisposablePostgres;
import com.harsh.hospitalmanagement.dto.BillResponse;
import com.harsh.hospitalmanagement.dto.RecordPaymentRequest;
import com.harsh.hospitalmanagement.dto.VoidBillRequest;
import com.harsh.hospitalmanagement.entity.*;
import com.harsh.hospitalmanagement.enums.*;
import com.harsh.hospitalmanagement.exception.ConflictException;
import com.harsh.hospitalmanagement.exception.ForbiddenException;
import com.harsh.hospitalmanagement.exception.ResourceNotFoundException;
import com.harsh.hospitalmanagement.repository.*;
import com.harsh.hospitalmanagement.security.LocalAccount;
import com.harsh.hospitalmanagement.service.AuditLogService;
import com.harsh.hospitalmanagement.service.BillService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@RequiresDisposablePostgres
@DataJpaTest(properties = "spring.sql.init.mode=never", showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestTarget.TestDataSourceConfiguration.class, BillService.class, AuditLogService.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BillPostgresTest {

    @Autowired private BillService billService;
    @Autowired private BillRepository billRepository;
    @Autowired private PaymentRecordRepository paymentRecordRepository;
    @Autowired private AppointmentRepository appointmentRepository;
    @Autowired private AppointmentSlotRepository appointmentSlotRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private DoctorRepository doctorRepository;
    @Autowired private SpecializationRepository specializationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private User adminUser;
    private User patientUser1;
    private User patientUser2;
    private User doctorUser;
    private Patient patient1;
    private Patient patient2;
    private Doctor doctor;
    private Specialization specialization;
    private LocalAccount adminAccount;
    private LocalAccount patient1Account;
    private LocalAccount patient2Account;
    private LocalAccount doctorAccount;

    private final List<Long> createdBillIds = new ArrayList<>();
    private final List<Long> createdAppointmentIds = new ArrayList<>();
    private final List<Long> createdSlotIds = new ArrayList<>();
    private final List<Long> createdPatientIds = new ArrayList<>();
    private final List<Long> createdDoctorIds = new ArrayList<>();
    private final List<Long> createdUserIds = new ArrayList<>();
    private final List<Long> createdSpecializationIds = new ArrayList<>();

    @DynamicPropertySource
    static void configureDisposablePostgres(DynamicPropertyRegistry registry) {
        PostgresTestTarget.registerProperties(registry);
    }

    @BeforeEach
    void setUp() {
        adminUser = createUser("admin.billing.test@example.com", Role.ADMIN);
        patientUser1 = createUser("patient1.billing.test@example.com", Role.PATIENT);
        patientUser2 = createUser("patient2.billing.test@example.com", Role.PATIENT);
        doctorUser = createUser("doctor.billing.test@example.com", Role.DOCTOR);

        patient1 = createPatient(patientUser1, "Alice", "Walker");
        patient2 = createPatient(patientUser2, "Bob", "Builder");

        specialization = createSpecialization("Cardiology " + UUID.randomUUID().toString().substring(0, 6));
        doctor = createDoctor(doctorUser, specialization, "Gregory", "House", new BigDecimal("750.00"));

        adminAccount = new LocalAccount(adminUser.getId(), adminUser.getEmail());
        patient1Account = new LocalAccount(patientUser1.getId(), patientUser1.getEmail());
        patient2Account = new LocalAccount(patientUser2.getId(), patientUser2.getEmail());
        doctorAccount = new LocalAccount(doctorUser.getId(), doctorUser.getEmail());
    }

    @AfterEach
    void tearDown() {
        for (Long billId : createdBillIds) {
            jdbc.update("DELETE FROM payment_records WHERE bill_id = ?", billId);
            jdbc.update("DELETE FROM bill_items WHERE bill_id = ?", billId);
            jdbc.update("DELETE FROM bills WHERE id = ?", billId);
        }
        createdBillIds.clear();

        for (Long apptId : createdAppointmentIds) {
            jdbc.update("DELETE FROM payment_records WHERE bill_id IN (SELECT id FROM bills WHERE appointment_id = ?)", apptId);
            jdbc.update("DELETE FROM bill_items WHERE bill_id IN (SELECT id FROM bills WHERE appointment_id = ?)", apptId);
            jdbc.update("DELETE FROM bills WHERE appointment_id = ?", apptId);
            jdbc.update("DELETE FROM appointments WHERE id = ?", apptId);
        }
        createdAppointmentIds.clear();

        for (Long slotId : createdSlotIds) {
            jdbc.update("DELETE FROM appointment_slots WHERE id = ?", slotId);
        }
        createdSlotIds.clear();

        for (Long patientId : createdPatientIds) {
            jdbc.update("DELETE FROM patients WHERE id = ?", patientId);
        }
        createdPatientIds.clear();

        for (Long doctorId : createdDoctorIds) {
            jdbc.update("DELETE FROM doctors WHERE id = ?", doctorId);
        }
        createdDoctorIds.clear();

        for (Long specId : createdSpecializationIds) {
            jdbc.update("DELETE FROM specializations WHERE id = ?", specId);
        }
        createdSpecializationIds.clear();

        for (Long userId : createdUserIds) {
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
        }
        createdUserIds.clear();
    }

    private User createUser(String email, Role role) {
        User user = new User();
        user.setEmail(email);
        user.setRole(role);
        user.setPassword("secure-pwd-123");
        User saved = userRepository.save(user);
        createdUserIds.add(saved.getId());
        return saved;
    }

    private Patient createPatient(User user, String firstName, String lastName) {
        Patient patient = new Patient();
        patient.setUser(user);
        patient.setFirstName(firstName);
        patient.setLastName(lastName);
        patient.setPhone("9876543210");
        patient.setDateOfBirth(LocalDate.of(1990, 1, 1));
        Patient saved = patientRepository.save(patient);
        createdPatientIds.add(saved.getId());
        return saved;
    }

    private Specialization createSpecialization(String name) {
        Specialization spec = new Specialization();
        spec.setName(name);
        spec.setDescription("Specialization for billing testing");
        Specialization saved = specializationRepository.save(spec);
        createdSpecializationIds.add(saved.getId());
        return saved;
    }

    private Doctor createDoctor(User user, Specialization spec, String firstName, String lastName, BigDecimal videoFee) {
        Doctor doc = new Doctor();
        doc.setUser(user);
        doc.setSpecialization(spec);
        doc.setFirstName(firstName);
        doc.setLastName(lastName);
        doc.setQualification("MD");
        doc.setExperience(10);
        doc.setRegistrationNumber("REG-" + UUID.randomUUID().toString().substring(0, 8));
        doc.setVerificationStatus(VerificationStatus.VERIFIED);
        doc.setVideoConsultationFee(videoFee);
        Doctor saved = doctorRepository.save(doc);
        createdDoctorIds.add(saved.getId());
        return saved;
    }

    private AppointmentSlot createSlot(Doctor doc, LocalDate date, LocalTime start, LocalTime end, BigDecimal fee) {
        AppointmentSlot slot = new AppointmentSlot();
        slot.setDoctor(doc);
        slot.setDate(date);
        slot.setStartTime(start);
        slot.setEndTime(end);
        slot.setStatus(SlotStatus.BOOKED);
        slot.setConsultationMode(ConsultationMode.VIDEO);
        slot.setFee(fee);
        slot.setOfferVersion(UUID.randomUUID());
        slot.setRetired(false);
        AppointmentSlot saved = appointmentSlotRepository.save(slot);
        createdSlotIds.add(saved.getId());
        return saved;
    }

    private Appointment createAppointment(Patient p, Doctor d, AppointmentSlot slot, AppointmentStatus status, BigDecimal fee) {
        Appointment appt = new Appointment();
        appt.setPatient(p);
        appt.setDoctor(d);
        appt.setSlot(slot);
        appt.setStatus(status);
        appt.setReason("General Consultation");
        appt.setCreatedAt(LocalDateTime.now());
        if (slot != null) {
            appt.captureOffer(slot);
        }
        Appointment saved = appointmentRepository.save(appt);
        createdAppointmentIds.add(saved.getId());
        return saved;
    }

    @Test
    void test01_BillCreation_GeneratesInvoiceWithSnapshotAndItem() {
        AppointmentSlot slot = createSlot(doctor, LocalDate.now().plusDays(1), LocalTime.of(10, 0), LocalTime.of(10, 30), new BigDecimal("500.00"));
        Appointment appt = createAppointment(patient1, doctor, slot, AppointmentStatus.CONFIRMED, new BigDecimal("500.00"));

        BillResponse response = billService.generateBillForAppointment(appt.getId(), adminAccount, "ROLE_ADMIN");
        createdBillIds.add(response.getId());

        assertThat(response).isNotNull();
        assertThat(response.getId()).isNotNull();
        assertThat(response.getInvoiceNumber()).startsWith("INV-");
        assertThat(response.getAmount()).isEqualByComparingTo("500.00");
        assertThat(response.getCurrency()).isEqualTo("INR");
        assertThat(response.getStatus()).isEqualTo(BillStatus.UNPAID);
        assertThat(response.getPatientId()).isEqualTo(patient1.getId());
        assertThat(response.getDoctorId()).isEqualTo(doctor.getId());
        assertThat(response.getAppointmentId()).isEqualTo(appt.getId());
        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).getTotalPrice()).isEqualByComparingTo("500.00");
        assertThat(response.getItems().get(0).getDescription()).contains("Gregory House");

        // Verify in PostgreSQL table
        BigDecimal dbAmount = jdbc.queryForObject("SELECT amount FROM bills WHERE id = ?", BigDecimal.class, response.getId());
        assertThat(dbAmount).isEqualByComparingTo("500.00");
        String dbStatus = jdbc.queryForObject("SELECT status FROM bills WHERE id = ?", String.class, response.getId());
        assertThat(dbStatus).isEqualTo("UNPAID");
    }

    @Test
    void test02_FeeSnapshotImmutability_DoctorFeeChangeDoesNotAffectIssuedBill() {
        AppointmentSlot slot = createSlot(doctor, LocalDate.now().plusDays(2), LocalTime.of(11, 0), LocalTime.of(11, 30), new BigDecimal("600.00"));
        Appointment appt = createAppointment(patient1, doctor, slot, AppointmentStatus.CONFIRMED, new BigDecimal("600.00"));

        BillResponse response = billService.generateBillForAppointment(appt.getId(), adminAccount, "ROLE_ADMIN");
        createdBillIds.add(response.getId());

        assertThat(response.getAmount()).isEqualByComparingTo("600.00");

        // Update doctor's video consultation fee to 1500.00
        doctor.setVideoConsultationFee(new BigDecimal("1500.00"));
        doctorRepository.save(doctor);

        // Fetch bill again and verify snapshot remains exactly 600.00
        BillResponse fetched = billService.getBillById(response.getId(), adminAccount, "ROLE_ADMIN");
        assertThat(fetched.getAmount()).isEqualByComparingTo("600.00");

        BigDecimal rawAmount = jdbc.queryForObject("SELECT amount FROM bills WHERE id = ?", BigDecimal.class, response.getId());
        assertThat(rawAmount).isEqualByComparingTo("600.00");
    }

    @Test
    void test03_DuplicateInvoicePrevention_IdempotentRepeatReturnsSameBill() {
        AppointmentSlot slot = createSlot(doctor, LocalDate.now().plusDays(3), LocalTime.of(12, 0), LocalTime.of(12, 30), new BigDecimal("550.00"));
        Appointment appt = createAppointment(patient1, doctor, slot, AppointmentStatus.CONFIRMED, new BigDecimal("550.00"));

        BillResponse first = billService.generateBillForAppointment(appt.getId(), adminAccount, "ROLE_ADMIN");
        createdBillIds.add(first.getId());

        BillResponse second = billService.generateBillForAppointment(appt.getId(), adminAccount, "ROLE_ADMIN");

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getInvoiceNumber()).isEqualTo(first.getInvoiceNumber());

        Integer rowCount = jdbc.queryForObject("SELECT count(*) FROM bills WHERE appointment_id = ?", Integer.class, appt.getId());
        assertThat(rowCount).isEqualTo(1);
    }

    @Test
    void test04_ConcurrentBillCreation_OnlyOneInvoiceCreated() throws Exception {
        AppointmentSlot slot = createSlot(doctor, LocalDate.now().plusDays(4), LocalTime.of(14, 0), LocalTime.of(14, 30), new BigDecimal("800.00"));
        Appointment appt = createAppointment(patient1, doctor, slot, AppointmentStatus.CONFIRMED, new BigDecimal("800.00"));

        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<Future<BillResponse>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                startLatch.await();
                return billService.generateBillForAppointment(appt.getId(), adminAccount, "ROLE_ADMIN");
            }));
        }

        startLatch.countDown();
        executor.shutdown();
        boolean finished = executor.awaitTermination(10, TimeUnit.SECONDS);
        assertThat(finished).isTrue();

        Set<Long> billIds = new HashSet<>();
        for (Future<BillResponse> future : futures) {
            BillResponse res = future.get();
            assertThat(res).isNotNull();
            billIds.add(res.getId());
            createdBillIds.add(res.getId());
        }

        assertThat(billIds).hasSize(1);
        Integer dbCount = jdbc.queryForObject("SELECT count(*) FROM bills WHERE appointment_id = ?", Integer.class, appt.getId());
        assertThat(dbCount).isEqualTo(1);
    }

    @Test
    void test05_AppointmentEligibility_CompletedAppointmentCanBeBilled() {
        AppointmentSlot slot = createSlot(doctor, LocalDate.now().minusDays(1), LocalTime.of(9, 0), LocalTime.of(9, 30), new BigDecimal("700.00"));
        Appointment appt = createAppointment(patient1, doctor, slot, AppointmentStatus.COMPLETED, new BigDecimal("700.00"));

        BillResponse response = billService.generateBillForAppointment(appt.getId(), adminAccount, "ROLE_ADMIN");
        createdBillIds.add(response.getId());

        assertThat(response.getStatus()).isEqualTo(BillStatus.UNPAID);
        assertThat(response.getAmount()).isEqualByComparingTo("700.00");
        assertThat(response.getAppointmentStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void test06_IneligibleAppointments_CancelledAndNoShowRejected() {
        AppointmentSlot slot1 = createSlot(doctor, LocalDate.now().plusDays(5), LocalTime.of(15, 0), LocalTime.of(15, 30), new BigDecimal("500.00"));
        Appointment cancelledAppt = createAppointment(patient1, doctor, slot1, AppointmentStatus.CANCELLED, new BigDecimal("500.00"));

        assertThatThrownBy(() -> billService.generateBillForAppointment(cancelledAppt.getId(), adminAccount, "ROLE_ADMIN"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("cancelled appointment");

        AppointmentSlot slot2 = createSlot(doctor, LocalDate.now().minusDays(2), LocalTime.of(16, 0), LocalTime.of(16, 30), new BigDecimal("500.00"));
        Appointment noShowAppt = createAppointment(patient1, doctor, slot2, AppointmentStatus.NO_SHOW, new BigDecimal("500.00"));

        assertThatThrownBy(() -> billService.generateBillForAppointment(noShowAppt.getId(), adminAccount, "ROLE_ADMIN"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("no-show appointment");
    }

    @Test
    void test07_PatientOwnershipIsolation_CrossPatientAccessRejectedWithNotFound() {
        AppointmentSlot slot = createSlot(doctor, LocalDate.now().plusDays(6), LocalTime.of(10, 0), LocalTime.of(10, 30), new BigDecimal("500.00"));
        Appointment appt1 = createAppointment(patient1, doctor, slot, AppointmentStatus.CONFIRMED, new BigDecimal("500.00"));

        BillResponse bill1 = billService.generateBillForAppointment(appt1.getId(), adminAccount, "ROLE_ADMIN");
        createdBillIds.add(bill1.getId());

        // Patient 1 can view own bill
        BillResponse p1View = billService.getBillById(bill1.getId(), patient1Account, "ROLE_PATIENT");
        assertThat(p1View.getId()).isEqualTo(bill1.getId());

        // Patient 2 attempting to view Patient 1's bill -> ResourceNotFoundException (fails closed)
        assertThatThrownBy(() -> billService.getBillById(bill1.getId(), patient2Account, "ROLE_PATIENT"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Bill not found");

        // Patient 2's list has 0 bills
        List<BillResponse> p2Bills = billService.getPatientBills(patient2Account, "ROLE_PATIENT");
        assertThat(p2Bills).isEmpty();
    }

    @Test
    void test08_AdminAuthorization_OnlyAdminCanGeneratePayAndVoidBills() {
        AppointmentSlot slot = createSlot(doctor, LocalDate.now().plusDays(7), LocalTime.of(11, 0), LocalTime.of(11, 30), new BigDecimal("500.00"));
        Appointment appt = createAppointment(patient1, doctor, slot, AppointmentStatus.CONFIRMED, new BigDecimal("500.00"));

        // Patient cannot generate bill
        assertThatThrownBy(() -> billService.generateBillForAppointment(appt.getId(), patient1Account, "ROLE_PATIENT"))
                .isInstanceOf(ForbiddenException.class);

        // Doctor cannot generate bill
        assertThatThrownBy(() -> billService.generateBillForAppointment(appt.getId(), doctorAccount, "ROLE_DOCTOR"))
                .isInstanceOf(ForbiddenException.class);

        // Generate bill as admin
        BillResponse bill = billService.generateBillForAppointment(appt.getId(), adminAccount, "ROLE_ADMIN");
        createdBillIds.add(bill.getId());

        // Doctor cannot view bill
        assertThatThrownBy(() -> billService.getBillById(bill.getId(), doctorAccount, "ROLE_DOCTOR"))
                .isInstanceOf(ForbiddenException.class);

        // Patient cannot mark bill as paid
        RecordPaymentRequest payReq = new RecordPaymentRequest("MANUAL_DEMO", "REF-123", "Demo pay");
        assertThatThrownBy(() -> billService.markBillAsPaid(bill.getId(), payReq, patient1Account, "ROLE_PATIENT"))
                .isInstanceOf(ForbiddenException.class);

        // Patient cannot void bill
        VoidBillRequest voidReq = new VoidBillRequest("Patient request");
        assertThatThrownBy(() -> billService.voidBill(bill.getId(), voidReq, patient1Account, "ROLE_PATIENT"))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void test09_ManualPaymentTransitions_UnpaidToPaidAndVoidLifecycle() {
        AppointmentSlot slot = createSlot(doctor, LocalDate.now().plusDays(8), LocalTime.of(13, 0), LocalTime.of(13, 30), new BigDecimal("900.00"));
        Appointment appt = createAppointment(patient1, doctor, slot, AppointmentStatus.CONFIRMED, new BigDecimal("900.00"));

        BillResponse bill = billService.generateBillForAppointment(appt.getId(), adminAccount, "ROLE_ADMIN");
        createdBillIds.add(bill.getId());
        assertThat(bill.getStatus()).isEqualTo(BillStatus.UNPAID);

        // Admin marks as PAID via manual demo payment
        RecordPaymentRequest payReq = new RecordPaymentRequest("MANUAL_CASH", "RCPT-98765", "Cash paid at counter");
        BillResponse paidBill = billService.markBillAsPaid(bill.getId(), payReq, adminAccount, "ROLE_ADMIN");

        assertThat(paidBill.getStatus()).isEqualTo(BillStatus.PAID);
        assertThat(paidBill.getPaidAt()).isNotNull();
        assertThat(paidBill.getPaidByAdminId()).isEqualTo(adminUser.getId());
        assertThat(paidBill.getPaymentMethod()).isEqualTo("MANUAL_CASH");
        assertThat(paidBill.getPaymentReference()).isEqualTo("RCPT-98765");
        assertThat(paidBill.getPaymentRecords()).hasSize(1);
        assertThat(paidBill.getPaymentRecords().get(0).getAmount()).isEqualByComparingTo("900.00");

        // Repeat payment is idempotent
        BillResponse repeatPay = billService.markBillAsPaid(bill.getId(), payReq, adminAccount, "ROLE_ADMIN");
        assertThat(repeatPay.getStatus()).isEqualTo(BillStatus.PAID);

        // Voiding a paid bill is strictly forbidden and throws ConflictException
        VoidBillRequest voidReq = new VoidBillRequest("Payment reversed due to consultation refund");
        assertThatThrownBy(() -> billService.voidBill(bill.getId(), voidReq, adminAccount, "ROLE_ADMIN"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Cannot void an already paid invoice");
    }

    @Test
    void test10_VoidBill_TransitionsToVoidWithReason() {
        AppointmentSlot slot = createSlot(doctor, LocalDate.now().plusDays(9), LocalTime.of(15, 0), LocalTime.of(15, 30), new BigDecimal("450.00"));
        Appointment appt = createAppointment(patient1, doctor, slot, AppointmentStatus.CONFIRMED, new BigDecimal("450.00"));

        BillResponse bill = billService.generateBillForAppointment(appt.getId(), adminAccount, "ROLE_ADMIN");
        createdBillIds.add(bill.getId());

        VoidBillRequest voidReq = new VoidBillRequest("Patient requested billing waiver");
        BillResponse voided = billService.voidBill(bill.getId(), voidReq, adminAccount, "ROLE_ADMIN");

        assertThat(voided.getStatus()).isEqualTo(BillStatus.VOID);
        assertThat(voided.getVoidedAt()).isNotNull();
        assertThat(voided.getVoidedByAdminId()).isEqualTo(adminUser.getId());
        assertThat(voided.getVoidReason()).isEqualTo("Patient requested billing waiver");

        // Idempotent repeat void
        BillResponse repeatVoid = billService.voidBill(bill.getId(), voidReq, adminAccount, "ROLE_ADMIN");
        assertThat(repeatVoid.getStatus()).isEqualTo(BillStatus.VOID);
    }

    @Test
    void test11_InvalidMonetaryAmounts_AndNegativeAmountRejection() {
        AppointmentSlot slot = createSlot(doctor, LocalDate.now().plusDays(10), LocalTime.of(16, 0), LocalTime.of(16, 30), new BigDecimal("500.00"));
        Appointment appt = createAppointment(patient1, doctor, slot, AppointmentStatus.CONFIRMED, new BigDecimal("500.00"));

        // Direct SQL insert with negative amount must fail check constraint ck_bills_amount
        assertThatThrownBy(() -> {
            jdbc.update(
                    "INSERT INTO bills (invoice_number, patient_id, appointment_id, doctor_id, amount, currency, status, issued_at, created_at, updated_at) " +
                            "VALUES (?, ?, ?, ?, ?, 'INR', 'UNPAID', LOCALTIMESTAMP, LOCALTIMESTAMP, LOCALTIMESTAMP)",
                    "INV-TEST-NEG", patient1.getId(), appt.getId(), doctor.getId(), new BigDecimal("-100.00")
            );
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void test12_TransactionRollback_FailureLeavesZeroBills() {
        AppointmentSlot slot = createSlot(doctor, LocalDate.now().plusDays(11), LocalTime.of(17, 0), LocalTime.of(17, 30), new BigDecimal("500.00"));
        Appointment appt = createAppointment(patient1, doctor, slot, AppointmentStatus.CONFIRMED, new BigDecimal("500.00"));

        TransactionTemplate tt = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> {
            tt.execute(status -> {
                // Generate bill inside transaction
                BillResponse res = billService.generateBillForAppointment(appt.getId(), adminAccount, "ROLE_ADMIN");
                createdBillIds.add(res.getId());
                // Force an unexpected exception to trigger rollback
                throw new RuntimeException("Simulated catastrophic failure after bill creation");
            });
        }).hasMessageContaining("Simulated catastrophic failure");

        // Verify that bill was rolled back and 0 bills exist for this appointment
        Integer count = jdbc.queryForObject("SELECT count(*) FROM bills WHERE appointment_id = ?", Integer.class, appt.getId());
        assertThat(count).isEqualTo(0);
    }

    @Test
    void test13_InvoiceNumberUniqueness_EnforcedByDatabase() {
        AppointmentSlot slot1 = createSlot(doctor, LocalDate.now().plusDays(12), LocalTime.of(10, 0), LocalTime.of(10, 30), new BigDecimal("500.00"));
        Appointment appt1 = createAppointment(patient1, doctor, slot1, AppointmentStatus.CONFIRMED, new BigDecimal("500.00"));

        AppointmentSlot slot2 = createSlot(doctor, LocalDate.now().plusDays(12), LocalTime.of(11, 0), LocalTime.of(11, 30), new BigDecimal("500.00"));
        Appointment appt2 = createAppointment(patient2, doctor, slot2, AppointmentStatus.CONFIRMED, new BigDecimal("500.00"));

        BillResponse bill1 = billService.generateBillForAppointment(appt1.getId(), adminAccount, "ROLE_ADMIN");
        createdBillIds.add(bill1.getId());

        // Attempting to insert duplicate invoice number must fail uk_bills_invoice_number
        assertThatThrownBy(() -> {
            jdbc.update(
                    "INSERT INTO bills (invoice_number, patient_id, appointment_id, doctor_id, amount, currency, status, issued_at, created_at, updated_at) " +
                            "VALUES (?, ?, ?, ?, ?, 'INR', 'UNPAID', LOCALTIMESTAMP, LOCALTIMESTAMP, LOCALTIMESTAMP)",
                    bill1.getInvoiceNumber(), patient2.getId(), appt2.getId(), doctor.getId(), new BigDecimal("500.00")
            );
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void test14_AuditLogEvents_RecordedForBillingActions() {
        AppointmentSlot slot = createSlot(doctor, LocalDate.now().plusDays(13), LocalTime.of(14, 0), LocalTime.of(14, 30), new BigDecimal("650.00"));
        Appointment appt = createAppointment(patient1, doctor, slot, AppointmentStatus.CONFIRMED, new BigDecimal("650.00"));

        // 1. BILL_GENERATED
        BillResponse bill = billService.generateBillForAppointment(appt.getId(), adminAccount, "ROLE_ADMIN");
        createdBillIds.add(bill.getId());

        Integer genCount = jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE event_type = 'BILL_GENERATED' AND entity_id = ?",
                Integer.class, bill.getId()
        );
        assertThat(genCount).isGreaterThanOrEqualTo(1);

        // 2. BILL_PAID
        RecordPaymentRequest payReq = new RecordPaymentRequest("MANUAL_DEMO", "TX-AUDIT-1", "Audit test payment");
        billService.markBillAsPaid(bill.getId(), payReq, adminAccount, "ROLE_ADMIN");

        Integer paidCount = jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE event_type = 'BILL_PAID' AND entity_id = ?",
                Integer.class, bill.getId()
        );
        assertThat(paidCount).isGreaterThanOrEqualTo(1);

        // 3. BILL_VOIDED (on an UNPAID bill)
        AppointmentSlot slot2 = createSlot(doctor, LocalDate.now().plusDays(15), LocalTime.of(15, 0), LocalTime.of(15, 30), new BigDecimal("350.00"));
        Appointment appt2 = createAppointment(patient1, doctor, slot2, AppointmentStatus.CONFIRMED, new BigDecimal("350.00"));
        BillResponse bill2 = billService.generateBillForAppointment(appt2.getId(), adminAccount, "ROLE_ADMIN");
        createdBillIds.add(bill2.getId());

        VoidBillRequest voidReq = new VoidBillRequest("Audit test voiding");
        billService.voidBill(bill2.getId(), voidReq, adminAccount, "ROLE_ADMIN");

        Integer voidCount = jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE event_type = 'BILL_VOIDED' AND entity_id = ?",
                Integer.class, bill2.getId()
        );
        assertThat(voidCount).isGreaterThanOrEqualTo(1);
    }

    @Test
    void test15_ZeroFeeInvoice_GeneratesAndSettlesSuccessfully() {
        AppointmentSlot slot = createSlot(doctor, LocalDate.now().plusDays(16), LocalTime.of(16, 0), LocalTime.of(16, 30), BigDecimal.ZERO.setScale(2));
        Appointment appt = createAppointment(patient1, doctor, slot, AppointmentStatus.CONFIRMED, BigDecimal.ZERO.setScale(2));

        BillResponse bill = billService.generateBillForAppointment(appt.getId(), adminAccount, "ROLE_ADMIN");
        createdBillIds.add(bill.getId());

        assertThat(bill.getAmount()).isEqualByComparingTo("0.00");
        assertThat(bill.getStatus()).isEqualTo(BillStatus.UNPAID);

        // Settle zero-fee invoice
        RecordPaymentRequest zeroPayReq = new RecordPaymentRequest("ZERO_FEE_WAIVER", "WAIVER-001", "Complimentary consultation");
        BillResponse settled = billService.markBillAsPaid(bill.getId(), zeroPayReq, adminAccount, "ROLE_ADMIN");

        assertThat(settled.getStatus()).isEqualTo(BillStatus.PAID);
        assertThat(settled.getPaymentMethod()).isEqualTo("ZERO_FEE_WAIVER");
        assertThat(settled.getPaymentRecords()).hasSize(1);
        assertThat(settled.getPaymentRecords().get(0).getAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void test16_FractionalAndNonPositiveAppointmentIds_StrictlyRejected() throws Exception {
        tools.jackson.databind.json.JsonMapper mapper = tools.jackson.databind.json.JsonMapper.builder().build();

        // 1. Fractional appointmentId in JSON must throw IllegalArgumentException and NOT truncate
        String fractionalJson = "{\"appointmentId\": 123.9}";
        assertThatThrownBy(() -> mapper.readValue(fractionalJson, com.harsh.hospitalmanagement.dto.GenerateBillRequest.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Fractional appointmentId is strictly forbidden");

        // 2. Negative appointmentId in JSON must throw IllegalArgumentException
        String negativeJson = "{\"appointmentId\": -5}";
        assertThatThrownBy(() -> mapper.readValue(negativeJson, com.harsh.hospitalmanagement.dto.GenerateBillRequest.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);

        // 3. Zero appointmentId in JSON must throw IllegalArgumentException
        String zeroJson = "{\"appointmentId\": 0}";
        assertThatThrownBy(() -> mapper.readValue(zeroJson, com.harsh.hospitalmanagement.dto.GenerateBillRequest.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);

        // 4. Valid positive integer in JSON must deserialize correctly
        String validJson = "{\"appointmentId\": 42}";
        com.harsh.hospitalmanagement.dto.GenerateBillRequest validReq =
                mapper.readValue(validJson, com.harsh.hospitalmanagement.dto.GenerateBillRequest.class);
        assertThat(validReq.getAppointmentId()).isEqualTo(42L);
    }
}

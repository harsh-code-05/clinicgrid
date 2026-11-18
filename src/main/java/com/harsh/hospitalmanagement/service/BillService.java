package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.dto.BillResponse;
import com.harsh.hospitalmanagement.dto.RecordPaymentRequest;
import com.harsh.hospitalmanagement.dto.VoidBillRequest;
import com.harsh.hospitalmanagement.entity.*;
import com.harsh.hospitalmanagement.enums.AppointmentStatus;
import com.harsh.hospitalmanagement.enums.AuditEventType;
import com.harsh.hospitalmanagement.enums.BillStatus;
import com.harsh.hospitalmanagement.exception.BadRequestException;
import com.harsh.hospitalmanagement.exception.ConflictException;
import com.harsh.hospitalmanagement.exception.ForbiddenException;
import com.harsh.hospitalmanagement.exception.ResourceNotFoundException;
import com.harsh.hospitalmanagement.repository.AppointmentRepository;
import com.harsh.hospitalmanagement.repository.BillRepository;
import com.harsh.hospitalmanagement.repository.PatientRepository;
import com.harsh.hospitalmanagement.repository.UserRepository;
import com.harsh.hospitalmanagement.security.LocalAccount;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class BillService {

    private static final String DEFAULT_ZONE_ID = "Asia/Kolkata";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final BillRepository billRepository;
    private final AppointmentRepository appointmentRepository;
    private final PatientRepository patientRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;
    private final Clock clock;

    @Autowired
    public BillService(
            BillRepository billRepository,
            AppointmentRepository appointmentRepository,
            PatientRepository patientRepository,
            UserRepository userRepository,
            AuditLogService auditLogService) {
        this(
                billRepository,
                appointmentRepository,
                patientRepository,
                userRepository,
                auditLogService,
                Clock.system(ZoneId.of(DEFAULT_ZONE_ID))
        );
    }

    public BillService(
            BillRepository billRepository,
            AppointmentRepository appointmentRepository,
            PatientRepository patientRepository,
            UserRepository userRepository,
            AuditLogService auditLogService,
            Clock clock) {
        this.billRepository = Objects.requireNonNull(billRepository, "BillRepository is required");
        this.appointmentRepository = Objects.requireNonNull(appointmentRepository, "AppointmentRepository is required");
        this.patientRepository = Objects.requireNonNull(patientRepository, "PatientRepository is required");
        this.userRepository = Objects.requireNonNull(userRepository, "UserRepository is required");
        this.auditLogService = Objects.requireNonNull(auditLogService, "AuditLogService is required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public BillResponse generateBillForAppointment(Long appointmentId, LocalAccount actor, String role) {
        if (!"ROLE_ADMIN".equals(role)) {
            throw new ForbiddenException("Only administrators can generate bills");
        }

        if (appointmentId == null) {
            throw new BadRequestException("Appointment ID is required");
        }

        // Lock existing bill if already present (idempotency check under lock)
        Optional<Bill> existing = billRepository.findByAppointmentIdForUpdate(appointmentId);
        if (existing.isPresent()) {
            return toBillResponse(existing.get());
        }

        // Lock appointment row to serialize bill generation against concurrent admins
        Appointment appointment = appointmentRepository.findByIdForUpdate(appointmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Appointment not found"));

        // Re-check after appointment lock
        existing = billRepository.findByAppointment_Id(appointmentId);
        if (existing.isPresent()) {
            return toBillResponse(existing.get());
        }

        // Validate appointment eligibility
        AppointmentStatus apptStatus = appointment.getStatus();
        if (apptStatus == AppointmentStatus.CANCELLED) {
            throw new ConflictException("APPOINTMENT_CANCELLED", "Cannot generate consultation bill for a cancelled appointment");
        }
        if (apptStatus == AppointmentStatus.NO_SHOW) {
            throw new ConflictException("APPOINTMENT_NO_SHOW", "Cannot generate consultation bill for a no-show appointment");
        }
        if (apptStatus != AppointmentStatus.CONFIRMED && apptStatus != AppointmentStatus.COMPLETED) {
            throw new BadRequestException("Appointment is not in an eligible billing status (CONFIRMED or COMPLETED)");
        }

        // Snapshot consultation fee
        BigDecimal fee = appointment.getConsultationFee();
        if (fee == null && appointment.getDoctor() != null && appointment.getDoctor().getVideoConsultationFee() != null) {
            fee = appointment.getDoctor().getVideoConsultationFee();
        }
        if (fee == null) {
            throw new BadRequestException("Appointment does not have a valid consultation fee");
        }
        if (fee.compareTo(BigDecimal.ZERO) < 0) {
            throw new BadRequestException("Consultation fee cannot be negative");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        String invoiceNumber = generateUniqueInvoiceNumber();

        Bill bill = new Bill();
        bill.setInvoiceNumber(invoiceNumber);
        bill.setPatient(appointment.getPatient());
        bill.setAppointment(appointment);
        bill.setDoctor(appointment.getDoctor());
        bill.setAmount(fee);
        bill.setCurrency("INR");
        bill.setStatus(BillStatus.UNPAID);
        bill.setIssuedAt(now);
        bill.setCreatedAt(now);
        bill.setUpdatedAt(now);

        // Add itemized consultation fee
        String docName = appointment.getDoctor() != null
                ? "Dr. " + appointment.getDoctor().getFirstName() + " " + appointment.getDoctor().getLastName()
                : "Assigned Doctor";
        String specName = (appointment.getDoctor() != null && appointment.getDoctor().getSpecialization() != null)
                ? appointment.getDoctor().getSpecialization().getName()
                : "General";

        String rawDesc = "Consultation Fee - " + docName + " (" + specName + ")";
        String itemDesc = rawDesc.length() > 255 ? rawDesc.substring(0, 252) + "..." : rawDesc;

        BillItem item = new BillItem();
        item.setDescription(itemDesc);
        item.setQuantity(1);
        item.setUnitPrice(fee);
        item.setTotalPrice(fee);
        bill.addItem(item);

        Bill saved = billRepository.save(bill);

        auditLogService.logEvent(
                AuditEventType.BILL_GENERATED,
                actor.email(),
                role,
                "Bill",
                saved.getId(),
                "Generated invoice " + saved.getInvoiceNumber() + " for appointment ID " + appointmentId + " amount INR " + fee,
                null
        );

        return toBillResponse(saved);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public BillResponse markBillAsPaid(Long billId, RecordPaymentRequest request, LocalAccount actor, String role) {
        if (!"ROLE_ADMIN".equals(role)) {
            throw new ForbiddenException("Only administrators can record payments");
        }

        Bill bill = billRepository.findByIdForUpdate(billId)
                .orElseThrow(() -> new ResourceNotFoundException("Bill not found"));

        if (bill.getStatus() == BillStatus.PAID) {
            return toBillResponse(bill);
        }

        if (bill.getStatus() == BillStatus.VOID) {
            throw new ConflictException("BILL_VOIDED", "Cannot record payment on a voided bill");
        }

        if (bill.getStatus() != BillStatus.UNPAID) {
            throw new ConflictException("ONLY_UNPAID_BILLS_CAN_BE_PAID", "Only UNPAID bills can be settled");
        }

        User admin = userRepository.findById(actor.id())
                .orElseThrow(() -> new ResourceNotFoundException("Admin user profile not found"));

        LocalDateTime now = LocalDateTime.now(clock);
        String defaultMethod = (bill.getAmount().compareTo(BigDecimal.ZERO) == 0) ? "ZERO_FEE_WAIVER" : "MANUAL_DEMO";
        String paymentMethod = (request != null && request.getPaymentMethod() != null && !request.getPaymentMethod().isBlank())
                ? request.getPaymentMethod()
                : defaultMethod;
        String txRef = (request != null && request.getPaymentReference() != null && !request.getPaymentReference().isBlank())
                ? request.getPaymentReference()
                : "DEMO-TX-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);

        bill.setStatus(BillStatus.PAID);
        bill.setPaidAt(now);
        bill.setPaidByAdmin(admin);
        bill.setPaymentMethod(paymentMethod);
        bill.setPaymentReference(txRef);
        bill.setUpdatedAt(now);

        PaymentRecord record = new PaymentRecord();
        record.setBill(bill);
        record.setAmount(bill.getAmount());
        record.setCurrency("INR");
        record.setPaymentMethod(paymentMethod);
        record.setTransactionReference(txRef);
        record.setRecordedByAdmin(admin);
        record.setRecordedAt(now);
        record.setNotes(request != null ? request.getNotes() : "Manual demo payment recorded at hospital desk");
        bill.addPaymentRecord(record);

        Bill saved = billRepository.save(bill);

        auditLogService.logEvent(
                AuditEventType.BILL_PAID,
                actor.email(),
                role,
                "Bill",
                saved.getId(),
                "Marked invoice " + saved.getInvoiceNumber() + " as PAID via " + paymentMethod + " ref: " + txRef,
                null
        );

        return toBillResponse(saved);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public BillResponse voidBill(Long billId, VoidBillRequest request, LocalAccount actor, String role) {
        if (!"ROLE_ADMIN".equals(role)) {
            throw new ForbiddenException("Only administrators can void bills");
        }

        Bill bill = billRepository.findByIdForUpdate(billId)
                .orElseThrow(() -> new ResourceNotFoundException("Bill not found"));

        if (bill.getStatus() == BillStatus.PAID) {
            throw new ConflictException("BILL_ALREADY_PAID", "Cannot void an already paid invoice");
        }

        if (bill.getStatus() == BillStatus.VOID) {
            return toBillResponse(bill);
        }

        if (bill.getStatus() != BillStatus.UNPAID) {
            throw new ConflictException("ONLY_UNPAID_BILLS_CAN_BE_VOIDED", "Only UNPAID bills can be voided");
        }

        User admin = userRepository.findById(actor.id())
                .orElseThrow(() -> new ResourceNotFoundException("Admin user profile not found"));

        LocalDateTime now = LocalDateTime.now(clock);
        String reason = (request != null && request.getReason() != null) ? request.getReason().trim() : "Administrative void";

        bill.setStatus(BillStatus.VOID);
        bill.setVoidedAt(now);
        bill.setVoidedByAdmin(admin);
        bill.setVoidReason(reason);
        bill.setUpdatedAt(now);

        Bill saved = billRepository.save(bill);

        auditLogService.logEvent(
                AuditEventType.BILL_VOIDED,
                actor.email(),
                role,
                "Bill",
                saved.getId(),
                "Voided invoice " + saved.getInvoiceNumber() + " Reason: " + reason,
                null
        );

        return toBillResponse(saved);
    }

    @Transactional(readOnly = true)
    public BillResponse getBillById(Long billId, LocalAccount actor, String role) {
        Bill bill = billRepository.findById(billId)
                .orElseThrow(() -> new ResourceNotFoundException("Bill not found"));

        if ("ROLE_ADMIN".equals(role)) {
            return toBillResponse(bill);
        }

        if ("ROLE_PATIENT".equals(role)) {
            Long patientUserId = bill.getPatient().getUser().getId();
            if (actor.id() != patientUserId) {
                // Fail closed to prevent probing other patient bills
                throw new ResourceNotFoundException("Bill not found");
            }
            return toBillResponse(bill);
        }

        if ("ROLE_DOCTOR".equals(role)) {
            throw new ForbiddenException("Doctors are not permitted to access patient billing records");
        }

        throw new ForbiddenException("Unauthorized to access billing records");
    }

    @Transactional(readOnly = true)
    public List<BillResponse> getPatientBills(LocalAccount actor, String role) {
        if (!"ROLE_PATIENT".equals(role)) {
            throw new ForbiddenException("Only patients can access their billing history");
        }

        Patient patient = patientRepository.findByUser_Id(actor.id())
                .orElseThrow(() -> new ResourceNotFoundException("Patient profile not found"));

        return billRepository.findByPatient_IdOrderByIssuedAtDesc(patient.getId())
                .stream()
                .map(this::toBillResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<BillResponse> getAllBills(BillStatus statusFilter, LocalAccount actor, String role) {
        if (!"ROLE_ADMIN".equals(role)) {
            throw new ForbiddenException("Only administrators can view all bills");
        }

        List<Bill> bills = (statusFilter != null)
                ? billRepository.findByStatusOrderByIssuedAtDesc(statusFilter)
                : billRepository.findAllByOrderByIssuedAtDesc();

        return bills.stream().map(this::toBillResponse).toList();
    }

    @Transactional(readOnly = true)
    public Optional<BillResponse> getBillForAppointment(Long appointmentId, LocalAccount actor, String role) {
        Optional<Bill> optBill = billRepository.findByAppointment_Id(appointmentId);
        if (optBill.isEmpty()) {
            return Optional.empty();
        }

        Bill bill = optBill.get();
        if ("ROLE_ADMIN".equals(role)) {
            return Optional.of(toBillResponse(bill));
        }

        if ("ROLE_PATIENT".equals(role)) {
            Long patientUserId = bill.getPatient().getUser().getId();
            if (actor.id() == patientUserId) {
                return Optional.of(toBillResponse(bill));
            }
            return Optional.empty();
        }

        return Optional.empty();
    }

    private String generateUniqueInvoiceNumber() {
        String datePart = LocalDate.now(clock).format(DateTimeFormatter.BASIC_ISO_DATE);
        for (int i = 0; i < 20; i++) {
            byte[] bytes = new byte[4];
            RANDOM.nextBytes(bytes);
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) {
                hex.append(String.format("%02X", b));
            }
            String candidate = "INV-" + datePart + "-" + hex;
            if (!billRepository.existsByInvoiceNumber(candidate)) {
                return candidate;
            }
        }
        return "INV-" + datePart + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    public BillResponse toBillResponse(Bill bill) {
        Patient patient = bill.getPatient();
        Doctor doctor = bill.getDoctor();
        Appointment appointment = bill.getAppointment();

        String patientName = (patient != null) ? patient.getFirstName() + " " + patient.getLastName() : "Unknown Patient";
        String patientEmail = (patient != null && patient.getUser() != null) ? patient.getUser().getEmail() : null;

        String doctorName = (doctor != null) ? "Dr. " + doctor.getFirstName() + " " + doctor.getLastName() : "Unknown Doctor";
        String doctorSpecialization = (doctor != null && doctor.getSpecialization() != null) ? doctor.getSpecialization().getName() : null;

        String apptDate = (appointment != null && appointment.getSlot() != null && appointment.getSlot().getDate() != null)
                ? appointment.getSlot().getDate().toString()
                : null;
        String apptTime = (appointment != null && appointment.getSlot() != null && appointment.getSlot().getStartTime() != null)
                ? appointment.getSlot().getStartTime().toString()
                : null;
        String apptStatus = (appointment != null && appointment.getStatus() != null)
                ? appointment.getStatus().name()
                : null;

        List<BillResponse.BillItemDto> itemDtos = bill.getItems().stream()
                .map(item -> new BillResponse.BillItemDto(
                        item.getId(),
                        item.getDescription(),
                        item.getQuantity(),
                        item.getUnitPrice(),
                        item.getTotalPrice()
                )).toList();

        List<BillResponse.PaymentRecordDto> paymentDtos = bill.getPaymentRecords().stream()
                .map(p -> new BillResponse.PaymentRecordDto(
                        p.getId(),
                        p.getAmount(),
                        p.getCurrency(),
                        p.getPaymentMethod(),
                        p.getTransactionReference(),
                        p.getRecordedByAdmin() != null ? p.getRecordedByAdmin().getId() : null,
                        p.getRecordedByAdmin() != null ? p.getRecordedByAdmin().getEmail() : null,
                        p.getRecordedAt(),
                        p.getNotes()
                )).toList();

        return BillResponse.builder()
                .id(bill.getId())
                .invoiceNumber(bill.getInvoiceNumber())
                .patientId(patient != null ? patient.getId() : null)
                .patientName(patientName)
                .patientEmail(patientEmail)
                .doctorId(doctor != null ? doctor.getId() : null)
                .doctorName(doctorName)
                .doctorSpecialization(doctorSpecialization)
                .appointmentId(appointment != null ? appointment.getId() : null)
                .appointmentDate(apptDate)
                .appointmentTime(apptTime)
                .appointmentStatus(apptStatus)
                .amount(bill.getAmount())
                .currency(bill.getCurrency())
                .status(bill.getStatus())
                .paymentMethod(bill.getPaymentMethod())
                .paymentReference(bill.getPaymentReference())
                .issuedAt(bill.getIssuedAt())
                .paidAt(bill.getPaidAt())
                .paidByAdminId(bill.getPaidByAdmin() != null ? bill.getPaidByAdmin().getId() : null)
                .paidByAdminEmail(bill.getPaidByAdmin() != null ? bill.getPaidByAdmin().getEmail() : null)
                .voidedAt(bill.getVoidedAt())
                .voidedByAdminId(bill.getVoidedByAdmin() != null ? bill.getVoidedByAdmin().getId() : null)
                .voidedByAdminEmail(bill.getVoidedByAdmin() != null ? bill.getVoidedByAdmin().getEmail() : null)
                .voidReason(bill.getVoidReason())
                .items(itemDtos)
                .paymentRecords(paymentDtos)
                .demonstrationNotice(true)
                .build();
    }
}

package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.entity.Appointment;
import com.harsh.hospitalmanagement.entity.AppointmentSlot;
import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.DoctorAvailability;
import com.harsh.hospitalmanagement.entity.Patient;
import com.harsh.hospitalmanagement.enums.AppointmentSlotOutcome;
import com.harsh.hospitalmanagement.enums.AppointmentStatus;
import com.harsh.hospitalmanagement.enums.AuditEventType;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.enums.SlotStatus;
import com.harsh.hospitalmanagement.exception.BadRequestException;
import com.harsh.hospitalmanagement.exception.ConflictException;
import com.harsh.hospitalmanagement.exception.ForbiddenException;
import com.harsh.hospitalmanagement.exception.ResourceNotFoundException;
import com.harsh.hospitalmanagement.repository.AppointmentRepository;
import com.harsh.hospitalmanagement.repository.AppointmentSlotRepository;
import com.harsh.hospitalmanagement.repository.DoctorRepository;
import com.harsh.hospitalmanagement.repository.PatientRepository;
import com.harsh.hospitalmanagement.security.LocalAccount;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class AppointmentService {

    private static final String DEFAULT_SCHEDULING_ZONE_ID = "Asia/Kolkata";
    private static final int MAX_CANCELLATION_REASON_LENGTH = 500;

    private final AppointmentRepository appointmentRepository;
    private final AppointmentSlotRepository appointmentSlotRepository;
    private final PatientRepository patientRepository;
    private final DoctorRepository doctorRepository;
    private final AuditLogService auditLogService;
    private final Clock clock;

    @Autowired
    public AppointmentService(
            AppointmentRepository appointmentRepository,
            AppointmentSlotRepository appointmentSlotRepository,
            PatientRepository patientRepository,
            DoctorRepository doctorRepository,
            AuditLogService auditLogService,
            @Value("${hms.scheduling.zone-id:Asia/Kolkata}") String schedulingZoneId) {
        this(
                appointmentRepository,
                appointmentSlotRepository,
                patientRepository,
                doctorRepository,
                auditLogService,
                Clock.system(ZoneId.of(schedulingZoneId))
        );
    }

    public AppointmentService(
            AppointmentRepository appointmentRepository,
            AppointmentSlotRepository appointmentSlotRepository,
            PatientRepository patientRepository,
            DoctorRepository doctorRepository,
            AuditLogService auditLogService) {
        this(
                appointmentRepository,
                appointmentSlotRepository,
                patientRepository,
                doctorRepository,
                auditLogService,
                Clock.system(ZoneId.of(DEFAULT_SCHEDULING_ZONE_ID))
        );
    }

    AppointmentService(
            AppointmentRepository appointmentRepository,
            AppointmentSlotRepository appointmentSlotRepository,
            PatientRepository patientRepository,
            DoctorRepository doctorRepository,
            AuditLogService auditLogService,
            Clock clock) {
        this.appointmentRepository = appointmentRepository;
        this.appointmentSlotRepository = appointmentSlotRepository;
        this.patientRepository = patientRepository;
        this.doctorRepository = doctorRepository;
        this.auditLogService = auditLogService;
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    public Appointment getAppointmentById(
            Long id,
            LocalAccount actor,
            String role) {

        Appointment appointment = appointmentRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Appointment not found"));

        if ("ROLE_ADMIN".equals(role)) {
            return appointment;
        }

        Long patientUserId = appointment.getPatient()
                .getUser()
                .getId();

        Long doctorUserId = appointment.getDoctor()
                .getUser()
                .getId();

        boolean patientOwner = actor.id() == patientUserId;
        boolean doctorOwner = actor.id() == doctorUserId;

        if (!patientOwner && !doctorOwner) {
            throw new ForbiddenException(
                    "You are not allowed to access this appointment");
        }

        return appointment;
    }

    public List<Appointment> getAppointments(
            LocalAccount actor,
            String role) {

        if ("ROLE_ADMIN".equals(role)) {
            return appointmentRepository
                    .findAllByOrderByCreatedAtDesc();
        }

        if ("ROLE_PATIENT".equals(role)) {
            return appointmentRepository
                    .findByPatient_User_IdOrderByCreatedAtDesc(
                            actor.id());
        }

        if ("ROLE_DOCTOR".equals(role)) {
            return appointmentRepository
                    .findByDoctor_User_IdOrderByCreatedAtDesc(
                            actor.id());
        }

        throw new ForbiddenException(
                "You are not allowed to access appointments");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Appointment bookAppointment(
            Long patientId,
            Long doctorId,
            Long slotId,
            LocalDate appointmentDate,
            String reason,
            LocalAccount actor) {

        Patient patient = patientRepository.findById(patientId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Patient not found"));

        Long patientUserId = patient.getUser().getId();

        if (actor.id() != patientUserId) {
            throw new ForbiddenException(
                    "You are not allowed to book an appointment for this patient");
        }

        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Doctor not found"));

        AppointmentSlot slot = lockOffer(slotId);
        if (slot.getOfferVersion() != null) {
            throw new ConflictException("OFFER_REQUIRES_UPGRADE", "Refresh to book this updated offer");
        }

        if (slot.getStatus() != SlotStatus.AVAILABLE) {
            throw new BadRequestException(
                    "Appointment slot is not available");
        }

        if (!slot.getDoctor().getId().equals(doctor.getId())) {
            throw new BadRequestException(
                    "Slot does not belong to this doctor");
        }

        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        if (!now.isBefore(slotStartInstant(slot))) {
            throw new BadRequestException(
                    "Cannot book an appointment slot in the past");
        }

        if (!appointmentSlotRepository.isBookable(slotId)) {
            throw new BadRequestException("Appointment slot is not available");
        }

        if (!slot.getDate().equals(appointmentDate)) {
            throw new BadRequestException("Selected appointment date does not match the slot date");
        }

        if (hasOverlappingBooking(slot)) {
            throw new BadRequestException("Doctor already has an appointment during this time");
        }

        return confirmBooking(patient, slot, reason, actor);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Appointment bookVersionedOffer(Long slotId, LocalDate expectedDate, UUID offerVersion, String reason,
                                           LocalAccount actor) {
        Patient patient = patientRepository.findByUser_Id(actor.id())
                .orElseThrow(() -> new ResourceNotFoundException("Patient profile not found"));
        if (patient.getUser().getRole() != Role.PATIENT) {
            throw new ForbiddenException("Only patients can book an appointment");
        }
        if (expectedDate == null || offerVersion == null) {
            throw new BadRequestException("Expected date and offer version are required");
        }
        AppointmentSlot slot = lockOffer(slotId);
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        if (!offerVersion.equals(slot.getOfferVersion()) || !expectedDate.equals(slot.getDate())
                || !appointmentSlotRepository.isDiscoverableOffer(slotId)
                || !now.isBefore(slotStartInstant(slot))
                || hasOverlappingBooking(slot)) {
            throw new ConflictException("OFFER_CHANGED", "This appointment offer changed. Refresh the available slots");
        }
        return confirmBooking(patient, slot, reason, actor);
    }

    private AppointmentSlot lockOffer(Long slotId) {
        long doctorId = appointmentSlotRepository.findDoctorIdById(slotId)
                .orElseThrow(() -> new ResourceNotFoundException("Appointment slot not found"));
        lockDoctorSchedule(doctorId);
        AppointmentSlot slot = appointmentSlotRepository.findByIdForUpdate(slotId)
                .orElseThrow(() -> new ResourceNotFoundException("Appointment slot not found"));
        if (slot.getDoctor().getId() != doctorId) {
            throw new ConflictException("OFFER_CHANGED", "This appointment offer changed. Refresh the available slots");
        }
        return slot;
    }

    private boolean hasOverlappingBooking(AppointmentSlot slot) {
        return appointmentRepository.existsOverlappingConfirmedAppointment(
                slot.getDoctor().getId(), slot.getDate(), slot.getStartTime(), slot.getEndTime());
    }

    private Appointment confirmBooking(Patient patient, AppointmentSlot slot, String reason, LocalAccount actor) {
        slot.setStatus(SlotStatus.BOOKED);

        Doctor doctor = slot.getDoctor();
        Appointment appointment = new Appointment();

        appointment.setPatient(patient);
        appointment.setDoctor(doctor);
        appointment.setSlot(slot);
        appointment.setReason(reason);
        appointment.setStatus(AppointmentStatus.CONFIRMED);
        appointment.setCreatedAt(LocalDateTime.now(clock));
        if (slot.getOfferVersion() != null) {
            appointment.captureOffer(slot);
        }

        Appointment savedAppointment = appointmentRepository.save(appointment);

        auditLogService.logEvent(
                AuditEventType.PATIENT_BOOKED_APPOINTMENT,
                actor.email(),
                "ROLE_PATIENT",
                "Appointment",
                savedAppointment.getId(),
                "Booked appointment ID " + savedAppointment.getId() + " with Doctor ID " + doctor.getId() + " for Slot ID " + slot.getId(),
                null
        );

        return savedAppointment;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Appointment cancelAppointment(
            Long appointmentId,
            LocalAccount actor,
            String role) {
        return doCancelAppointment(appointmentId, actor, role, null);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Appointment cancelAppointment(
            Long appointmentId,
            LocalAccount actor,
            String role,
            String reason) {
        return doCancelAppointment(appointmentId, actor, role, reason);
    }

    private Appointment doCancelAppointment(
            Long appointmentId,
            LocalAccount actor,
            String role,
            String rawReason) {

        Appointment appointment = lockAppointmentSchedule(appointmentId);

        Role actorRole = resolveActorRole(role);
        boolean admin = actorRole == Role.ADMIN;

        Long patientUserId = appointment.getPatient()
                .getUser()
                .getId();

        Long doctorUserId = appointment.getDoctor()
                .getUser()
                .getId();

        boolean patientOwner = actor.id() == patientUserId;
        boolean doctorOwner = actor.id() == doctorUserId;

        boolean authorized = admin
                || (actorRole == Role.DOCTOR && doctorOwner)
                || (actorRole == Role.PATIENT && patientOwner);
        if (!authorized) {
            throw new ForbiddenException(
                    "You are not allowed to cancel this appointment");
        }

        if (appointment.getStatus() == AppointmentStatus.CANCELLED) {
            return appointment;
        }

        if (appointment.getStatus() != AppointmentStatus.CONFIRMED) {
            throw new ConflictException(
                    "APPOINTMENT_NOT_CANCELLABLE",
                    "Appointment cannot be cancelled in its current status");
        }

        String normalizedReason = normalizeCancellationReason(rawReason);

        // The doctor and appointment locks are already held, so the slot is last.
        AppointmentSlot slot = appointmentSlotRepository.findByIdForUpdate(
                        appointment.getSlot().getId())
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Appointment slot not found"));

        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        if (!now.isBefore(slotStartInstant(slot))) {
            throw new ConflictException(
                    "APPOINTMENT_CANCELLATION_WINDOW_CLOSED",
                    "Appointment can no longer be cancelled because its start time has passed");
        }

        if (slot.getStatus() != SlotStatus.BOOKED) {
            throw new BadRequestException("Appointment slot is not booked");
        }

        AppointmentSlotOutcome slotOutcome = releaseOrWithholdSlot(slot, appointment);
        appointment.markCancelled(normalizedReason, now, actor.id(), actorRole, slotOutcome);

        Appointment savedAppointment = appointmentRepository.save(appointment);

        AuditEventType eventType;
        if (actorRole == Role.ADMIN) {
            eventType = AuditEventType.ADMIN_CANCELLED_APPOINTMENT;
        } else if (actorRole == Role.DOCTOR) {
            eventType = AuditEventType.DOCTOR_CANCELLED_APPOINTMENT;
        } else {
            eventType = AuditEventType.PATIENT_CANCELLED_APPOINTMENT;
        }

        auditLogService.logEvent(
                eventType,
                actor.email(),
                role,
                "Appointment",
                savedAppointment.getId(),
                "Cancelled appointment ID " + savedAppointment.getId() + " [slotOutcome=" + slotOutcome.name() + "]",
                null
        );

        return savedAppointment;
    }

    private AppointmentSlotOutcome releaseOrWithholdSlot(
            AppointmentSlot slot,
            Appointment appointment) {
        if (appointmentRepository.existsOtherConfirmedAppointmentForSlot(slot.getId(), appointment.getId())) {
            return AppointmentSlotOutcome.WITHHELD;
        }
        if (slot.isRetired()) {
            slot.setStatus(SlotStatus.AVAILABLE);
            return AppointmentSlotOutcome.RETAINED_UNAVAILABLE;
        }
        if (appointmentRepository.existsOverlappingConfirmedAppointmentExcludingId(
                appointment.getDoctor().getId(),
                slot.getDate(),
                slot.getStartTime(),
                slot.getEndTime(),
                appointment.getId())) {
            slot.setStatus(SlotStatus.BLOCKED);
            if (slot.getOfferVersion() != null) {
                slot.setOfferVersion(UUID.randomUUID());
            }
            return AppointmentSlotOutcome.OVERLAP;
        }
        if (!hasValidActiveProvenance(slot)) {
            slot.setRetired(true);
            slot.setStatus(SlotStatus.AVAILABLE);
            return AppointmentSlotOutcome.WITHHELD;
        }
        slot.setStatus(SlotStatus.AVAILABLE);
        if (slot.getOfferVersion() != null) {
            slot.setOfferVersion(UUID.randomUUID());
        }
        return AppointmentSlotOutcome.RELEASED;
    }

    private boolean hasValidActiveProvenance(AppointmentSlot slot) {
        DoctorAvailability availability = slot.getAvailability();
        if (availability == null
                || slot.getAvailabilityRevision() == null
                || !slot.getAvailabilityRevision().equals(availability.getRevision())) {
            return false;
        }
        if (!Objects.equals(slot.getDoctor().getId(), availability.getDoctor().getId())
                || !Objects.equals(slot.getDate(), availability.getDate())
                || slot.getStartTime() == null
                || slot.getEndTime() == null
                || !slot.getStartTime().isBefore(slot.getEndTime())
                || slot.getStartTime().isBefore(availability.getStartTime())
                || slot.getEndTime().isAfter(availability.getEndTime())) {
            return false;
        }
        if (slot.getOfferVersion() != null) {
            return appointmentSlotRepository.hasValidActiveOfferProvenance(slot.getId());
        }
        return true;
    }

    private static Role resolveActorRole(String role) {
        if ("ROLE_ADMIN".equals(role)) {
            return Role.ADMIN;
        }
        if ("ROLE_DOCTOR".equals(role)) {
            return Role.DOCTOR;
        }
        if ("ROLE_PATIENT".equals(role)) {
            return Role.PATIENT;
        }
        return null;
    }

    private static String normalizeCancellationReason(String rawReason) {
        if (rawReason == null) {
            return null;
        }
        if (rawReason.indexOf('\0') >= 0) {
            throw new BadRequestException("Cancellation reason cannot contain NUL characters");
        }
        String trimmed = stripUnicodeWhitespace(rawReason);
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_CANCELLATION_REASON_LENGTH) {
            throw new BadRequestException("Cancellation reason cannot exceed 500 characters");
        }
        return trimmed;
    }

    private static String stripUnicodeWhitespace(String value) {
        int start = 0;
        int end = value.length();
        while (start < end) {
            int codePoint = value.codePointAt(start);
            if (!isUnicodeWhitespace(codePoint)) {
                break;
            }
            start += Character.charCount(codePoint);
        }
        while (end > start) {
            int codePoint = value.codePointBefore(end);
            if (!isUnicodeWhitespace(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }
        return value.substring(start, end);
    }

    private static boolean isUnicodeWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint)
                || codePoint == '\u0085'
                || codePoint == '\u00A0'
                || codePoint == '\u2007'
                || codePoint == '\u202F'
                || codePoint == '\uFEFF';
    }

    private Instant slotStartInstant(AppointmentSlot slot) {
        if (slot.getDate() == null || slot.getStartTime() == null) {
            throw new BadRequestException("Appointment slot schedule is incomplete");
        }
        return slot.getDate().atTime(slot.getStartTime()).atZone(clock.getZone()).toInstant();
    }

    private Instant slotEndInstant(AppointmentSlot slot) {
        if (slot.getDate() == null || slot.getEndTime() == null) {
            throw new BadRequestException("Appointment slot schedule is incomplete");
        }
        return slot.getDate().atTime(slot.getEndTime()).atZone(clock.getZone()).toInstant();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Appointment completeAppointment(
            Long appointmentId,
            LocalAccount actor,
            String role) {

        Appointment appointment = lockAppointmentSchedule(appointmentId);

        boolean admin = "ROLE_ADMIN".equals(role);

        Long doctorUserId = appointment.getDoctor()
                .getUser()
                .getId();

        boolean doctorOwner = actor.id() == doctorUserId;

        if (!admin && !doctorOwner) {
            throw new ForbiddenException(
                    "Only the assigned doctor or admin can complete this appointment");
        }

        if (appointment.getStatus() != AppointmentStatus.CONFIRMED) {
            throw new BadRequestException(
                    "Only confirmed appointments can be completed");
        }

        // The doctor and appointment locks are already held, so the slot is last.
        AppointmentSlot slot = appointmentSlotRepository.findByIdForUpdate(
                        appointment.getSlot().getId())
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Appointment slot not found"));

        if (slot.getStatus() != SlotStatus.BOOKED) {
            throw new BadRequestException("Appointment slot is not booked");
        }

        if (Instant.now(clock).isBefore(slotEndInstant(slot))) {
            throw new BadRequestException(
                    "Cannot complete an appointment before its scheduled end time");
        }

        appointment.setStatus(AppointmentStatus.COMPLETED);

        Appointment savedAppointment = appointmentRepository.save(appointment);

        AuditEventType eventType = admin
                ? AuditEventType.ADMIN_COMPLETED_APPOINTMENT
                : AuditEventType.DOCTOR_COMPLETED_APPOINTMENT;

        auditLogService.logEvent(
                eventType,
                actor.email(),
                role,
                "Appointment",
                savedAppointment.getId(),
                "Completed appointment ID " + savedAppointment.getId(),
                null
        );

        return savedAppointment;
    }

    private void lockDoctorSchedule(long doctorId) {
        doctorRepository.findByIdForUpdate(doctorId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor not found"));
    }

    private Appointment lockAppointmentSchedule(Long appointmentId) {
        long doctorId = appointmentRepository.findDoctorIdById(appointmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Appointment not found"));
        lockDoctorSchedule(doctorId);
        return appointmentRepository.findByIdForUpdate(appointmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Appointment not found"));
    }
}

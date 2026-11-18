package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.entity.AppointmentSlot;
import com.harsh.hospitalmanagement.entity.DoctorAvailability;
import com.harsh.hospitalmanagement.enums.SlotStatus;
import com.harsh.hospitalmanagement.exception.BadRequestException;
import com.harsh.hospitalmanagement.exception.ConflictException;
import com.harsh.hospitalmanagement.exception.ForbiddenException;
import com.harsh.hospitalmanagement.exception.ResourceNotFoundException;
import com.harsh.hospitalmanagement.repository.AppointmentRepository;
import com.harsh.hospitalmanagement.repository.AppointmentSlotRepository;
import com.harsh.hospitalmanagement.repository.DoctorAvailabilityRepository;
import com.harsh.hospitalmanagement.repository.DoctorRepository;
import com.harsh.hospitalmanagement.security.LocalAccount;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class AppointmentSlotService {

    private static final String DEFAULT_SCHEDULING_ZONE_ID = "Asia/Kolkata";
    private static final Duration SLOT_DURATION = Duration.ofMinutes(30);

    private final AppointmentSlotRepository appointmentSlotRepository;
    private final DoctorAvailabilityRepository doctorAvailabilityRepository;
    private final DoctorRepository doctorRepository;
    private final AppointmentRepository appointmentRepository;
    private final Clock clock;

    @Autowired
    public AppointmentSlotService(
            AppointmentSlotRepository appointmentSlotRepository,
            DoctorAvailabilityRepository doctorAvailabilityRepository,
            DoctorRepository doctorRepository,
            AppointmentRepository appointmentRepository,
            @Value("${hms.scheduling.zone-id:Asia/Kolkata}") String schedulingZoneId) {
        this(
                appointmentSlotRepository,
                doctorAvailabilityRepository,
                doctorRepository,
                appointmentRepository,
                Clock.system(ZoneId.of(schedulingZoneId))
        );
    }

    public AppointmentSlotService(
            AppointmentSlotRepository appointmentSlotRepository,
            DoctorAvailabilityRepository doctorAvailabilityRepository,
            DoctorRepository doctorRepository,
            AppointmentRepository appointmentRepository) {
        this(
                appointmentSlotRepository,
                doctorAvailabilityRepository,
                doctorRepository,
                appointmentRepository,
                Clock.system(ZoneId.of(DEFAULT_SCHEDULING_ZONE_ID))
        );
    }

    AppointmentSlotService(
            AppointmentSlotRepository appointmentSlotRepository,
            DoctorAvailabilityRepository doctorAvailabilityRepository,
            DoctorRepository doctorRepository,
            AppointmentRepository appointmentRepository,
            Clock clock) {
        this.appointmentSlotRepository = appointmentSlotRepository;
        this.doctorAvailabilityRepository = doctorAvailabilityRepository;
        this.doctorRepository = doctorRepository;
        this.appointmentRepository = appointmentRepository;
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    @Transactional(readOnly = true)
    public List<AppointmentSlot> getAvailableSlots(Long doctorId, LocalDate date) {
        if (doctorId == null) {
            return List.of();
        }
        var doctor = doctorRepository.findById(doctorId).orElse(null);
        if (doctor == null || doctor.getVerificationStatus() != com.harsh.hospitalmanagement.enums.VerificationStatus.VERIFIED) {
            return List.of();
        }
        List<AppointmentSlot> candidates = date != null
                ? appointmentSlotRepository.findBookableByDoctorAndDate(doctorId, date)
                : appointmentSlotRepository.findBookableByDoctor(doctorId);
        Instant now = Instant.now(clock);
        return candidates.stream()
                .filter(slot -> now.isBefore(slotStartInstant(slot)))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AppointmentSlot> getDiscoverableOffers(Long doctorId, LocalDate date) {
        if (doctorId == null) {
            return List.of();
        }
        var doctor = doctorRepository.findById(doctorId).orElse(null);
        if (doctor == null || doctor.getVerificationStatus() != com.harsh.hospitalmanagement.enums.VerificationStatus.VERIFIED) {
            return List.of();
        }
        Instant now = Instant.now(clock);
        return appointmentSlotRepository.findDiscoverableOffers(doctorId, date).stream()
                .filter(slot -> now.isBefore(slotStartInstant(slot)))
                .toList();
    }

    public AppointmentSlot getSlotById(Long id) {
        return appointmentSlotRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Appointment slot not found"));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AppointmentSlot unblockSlot(Long slotId, LocalAccount actor) {
        if (actor == null) {
            throw new ForbiddenException("Admin authentication is required");
        }
        long doctorId = appointmentSlotRepository.findDoctorIdById(slotId)
                .orElseThrow(() -> new ResourceNotFoundException("Appointment slot not found"));
        doctorRepository.findByIdForUpdate(doctorId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor not found"));

        AppointmentSlot slot = appointmentSlotRepository.findByIdForUpdate(slotId)
                .orElseThrow(() -> new ResourceNotFoundException("Appointment slot not found"));
        if (!Objects.equals(slot.getDoctor().getId(), doctorId)) {
            throw new ConflictException("SLOT_CHANGED", "Appointment slot changed while acquiring schedule lock");
        }
        if (slot.getStatus() != SlotStatus.BLOCKED) {
            throw new ConflictException("SLOT_NOT_BLOCKED", "Only blocked appointment slots can be unblocked");
        }
        Instant now = Instant.now(clock);
        if (!now.isBefore(slotStartInstant(slot))) {
            throw new ConflictException("SLOT_PAST", "Cannot unblock an appointment slot in the past");
        }
        if (appointmentRepository.existsConfirmedAppointmentForSlot(slot.getId())
                || appointmentRepository.existsOverlappingConfirmedAppointment(
                        doctorId, slot.getDate(), slot.getStartTime(), slot.getEndTime())) {
            throw new ConflictException(
                    "SLOT_CONFLICTING_BOOKING",
                    "Cannot unblock slot while an overlapping confirmed appointment exists");
        }
        slot.setStatus(SlotStatus.AVAILABLE);
        slot.setRetired(true);
        if (slot.getOfferVersion() != null) {
            slot.setOfferVersion(UUID.randomUUID());
        }
        return appointmentSlotRepository.save(slot);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<AppointmentSlot> generateSlots(
            Long availabilityId,
            LocalAccount actor,
            boolean admin) {

        long doctorId = doctorAvailabilityRepository.findDoctorIdById(availabilityId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor availability not found"));
        doctorRepository.findByIdForUpdate(doctorId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor not found"));

        DoctorAvailability availability =
                doctorAvailabilityRepository.findById(availabilityId)
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "Doctor availability not found"));
        if (availability.getDoctor().getId() != doctorId) {
            throw new ConflictException("Availability changed while acquiring the schedule lock. Retry the request.");
        }

        if (availability.getDoctor().getVerificationStatus() != com.harsh.hospitalmanagement.enums.VerificationStatus.VERIFIED) {
            throw new ForbiddenException("Doctor profile is pending verification. Slots cannot be generated until approved.");
        }

        if (!admin) {
            long doctorUserId =
                    availability.getDoctor().getUser().getId();

            if (doctorUserId != actor.id()) {
                throw new ForbiddenException(
                        "You are not allowed to generate slots for this doctor's availability");
            }
        }

        ConsultationTerms terms = ConsultationTerms.forSchedule(
                availability.getDoctor(), availability.getConsultationMode(), availability.getLocation());
        LocalTime currentTime = availability.getStartTime();
        LocalTime endTime = availability.getEndTime();
        if (!currentTime.isBefore(endTime)) {
            throw new BadRequestException("Start time must be before end time");
        }

        long slotCount = Duration.between(currentTime, endTime).dividedBy(SLOT_DURATION);
        LocalDate date = availability.getDate();
        Instant now = Instant.now(clock);
        List<AppointmentSlot> slots = new ArrayList<>();

        for (long i = 0; i < slotCount; i++) {
            LocalTime slotEndTime = currentTime.plus(SLOT_DURATION);
            Instant slotStart = date.atTime(currentTime).atZone(clock.getZone()).toInstant();

            if (now.isBefore(slotStart)
                    && !appointmentRepository.existsOverlappingConfirmedAppointment(doctorId, date, currentTime, slotEndTime)
                    && !appointmentSlotRepository.existsOverlappingBookableSlot(doctorId, date, currentTime, slotEndTime)) {
                AppointmentSlot slot = appointmentSlotRepository.findByDoctor_IdAndDateAndStartTimeAndEndTime(
                        doctorId, date, currentTime, slotEndTime).orElse(null);
                if (slot == null) {
                    slot = new AppointmentSlot();
                    slot.setDate(date);
                    slot.setStartTime(currentTime);
                    slot.setEndTime(slotEndTime);
                    slot.setStatus(SlotStatus.AVAILABLE);
                    slot.setDoctor(availability.getDoctor());
                }
                // Reissue only an unoccupied, retired row with the exact same coordinates.
                if (slot.isRetired() && slot.getStatus() == SlotStatus.AVAILABLE
                        && (terms.mode() != null || slot.getOfferVersion() == null)) {
                    slot.setAvailability(availability);
                    slot.setAvailabilityRevision(availability.getRevision());
                    terms.publishOn(slot);
                    slots.add(slot);
                }
            }

            currentTime = slotEndTime;
        }

        if (!slots.isEmpty()) {
            return appointmentSlotRepository.saveAll(slots);
        }

        return slots;
    }

    private Instant slotStartInstant(AppointmentSlot slot) {
        return slot.getDate().atTime(slot.getStartTime()).atZone(clock.getZone()).toInstant();
    }
}

package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.entity.AppointmentSlot;
import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.DoctorAvailability;
import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.enums.SlotStatus;
import com.harsh.hospitalmanagement.exception.BadRequestException;
import com.harsh.hospitalmanagement.exception.ConflictException;
import com.harsh.hospitalmanagement.repository.AppointmentRepository;
import com.harsh.hospitalmanagement.repository.AppointmentSlotRepository;
import com.harsh.hospitalmanagement.repository.DoctorAvailabilityRepository;
import com.harsh.hospitalmanagement.repository.DoctorRepository;
import com.harsh.hospitalmanagement.security.LocalAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppointmentSlotServiceTest {

    private static final long AVAILABILITY_ID = 10L;
    private static final long DOCTOR_ID = 20L;
    private static final LocalDate DATE = LocalDate.of(2030, 1, 15);
    private static final LocalAccount DOCTOR_ACCOUNT = new LocalAccount(30L, "doctor@test.invalid");
    private static final LocalAccount ADMIN_ACCOUNT = new LocalAccount(1L, "admin@test.invalid");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Mock
    private AppointmentSlotRepository slotRepository;

    @Mock
    private DoctorAvailabilityRepository availabilityRepository;

    @Mock
    private DoctorRepository doctorRepository;

    @Mock
    private AppointmentRepository appointmentRepository;

    private AppointmentSlotService service;
    private Doctor doctor;

    @BeforeEach
    void setUp() {
        service = new AppointmentSlotService(slotRepository, availabilityRepository, doctorRepository, appointmentRepository);
        User user = new User();
        user.setId(DOCTOR_ACCOUNT.id());
        doctor = new Doctor();
        doctor.setId(DOCTOR_ID);
        doctor.setUser(user);
        doctor.setVerificationStatus(com.harsh.hospitalmanagement.enums.VerificationStatus.VERIFIED);
        lenient().when(doctorRepository.findById(DOCTOR_ID)).thenReturn(Optional.of(doctor));
    }

    @ParameterizedTest
    @CsvSource({
            "22:30, 23:59, 22:30-23:00;23:00-23:30",
            "22:30, 23:30, 22:30-23:00;23:00-23:30",
            "23:29, 23:59, 23:29-23:59",
            "23:30, 23:59, ''",
            "09:15, 10:20, 09:15-09:45;09:45-10:15"
    })
    void generatesOnlyFullSlotsWithinTheSameDay(String start, String end, String intervals) {
        givenAvailability(start, end);
        List<String> expected = intervals.isEmpty() ? List.of() : List.of(intervals.split(";"));
        List<String> checked = new ArrayList<>();
        // The lookup guard is intentionally unused when the window fits no slots.
        lenient().when(slotRepository.findByDoctor_IdAndDateAndStartTimeAndEndTime(eq(DOCTOR_ID), eq(DATE), any(), any()))
                .thenAnswer(invocation -> {
                    // Stop a wraparound regression at its first extra lookup.
                    assertThat(checked.size()).as("number of slot lookups").isLessThan(expected.size());
                    LocalTime slotStart = invocation.getArgument(2);
                    LocalTime slotEnd = invocation.getArgument(3);
                    checked.add(slotStart + "-" + slotEnd);
                    return Optional.empty();
                });
        if (!expected.isEmpty()) {
            when(slotRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        }

        List<AppointmentSlot> slots = assertTimeoutPreemptively(Duration.ofSeconds(3),
                () -> service.generateSlots(AVAILABILITY_ID, DOCTOR_ACCOUNT, false));

        assertThat(checked).containsExactlyElementsOf(expected);
        assertThat(slots).extracting(slot -> slot.getStartTime() + "-" + slot.getEndTime())
                .containsExactlyElementsOf(expected);
        assertThat(slots).allSatisfy(slot -> {
            assertThat(slot.getDate()).isEqualTo(DATE);
            assertThat(slot.getDoctor()).isSameAs(doctor);
            assertThat(slot.getStatus()).isEqualTo(SlotStatus.AVAILABLE);
            assertThat(slot.isRetired()).isFalse();
            assertThat(slot.getAvailability().getId()).isEqualTo(AVAILABILITY_ID);
            assertThat(slot.getAvailabilityRevision()).isEqualTo(7L);
        });
        if (expected.isEmpty()) {
            verify(slotRepository, never()).saveAll(any());
        } else {
            verify(slotRepository).saveAll(slots);
        }
    }

    @ParameterizedTest
    @CsvSource({"22:30, 00:30", "09:00, 09:00"})
    void rejectsUnsupportedOvernightOrZeroLengthAvailability(String start, String end) {
        givenAvailability(start, end);

        assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                assertThatThrownBy(() -> service.generateSlots(AVAILABILITY_ID, DOCTOR_ACCOUNT, false))
                        .isInstanceOf(BadRequestException.class)
                        .hasMessage("Start time must be before end time"));
        verifyNoInteractions(slotRepository);
    }

    @Test
    void getAvailableSlotsFiltersOutStartedAndPastSlotsUsingBusinessTimezone() {
        // 2030-01-15 10:00 IST == 2030-01-15T04:30:00Z
        AppointmentSlotService clockedService = new AppointmentSlotService(
                slotRepository, availabilityRepository, doctorRepository, appointmentRepository,
                Clock.fixed(Instant.parse("2030-01-15T04:30:00Z"), IST));

        AppointmentSlot startingNow = slot(1L, LocalTime.of(10, 0), SlotStatus.AVAILABLE);
        AppointmentSlot future = slot(2L, LocalTime.of(10, 30), SlotStatus.AVAILABLE);
        when(slotRepository.findBookableByDoctorAndDate(DOCTOR_ID, DATE)).thenReturn(List.of(startingNow, future));

        assertThat(clockedService.getAvailableSlots(DOCTOR_ID, DATE))
                .extracting(AppointmentSlot::getId)
                .containsExactly(2L);
    }

    @Test
    void unblockSlotTransitionsBlockedFutureSlotToRetiredAvailable() {
        AppointmentSlotService clockedService = new AppointmentSlotService(
                slotRepository, availabilityRepository, doctorRepository, appointmentRepository,
                Clock.fixed(Instant.parse("2030-01-15T04:00:00Z"), IST));

        UUID oldOffer = UUID.randomUUID();
        AppointmentSlot blocked = slot(77L, LocalTime.of(10, 0), SlotStatus.BLOCKED);
        blocked.setRetired(false);
        blocked.setOfferVersion(oldOffer);

        when(slotRepository.findDoctorIdById(77L)).thenReturn(Optional.of(DOCTOR_ID));
        when(doctorRepository.findByIdForUpdate(DOCTOR_ID)).thenReturn(Optional.of(doctor));
        when(slotRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(blocked));
        when(slotRepository.save(blocked)).thenReturn(blocked);

        AppointmentSlot result = clockedService.unblockSlot(77L, ADMIN_ACCOUNT);

        assertThat(result.getStatus()).isEqualTo(SlotStatus.AVAILABLE);
        assertThat(result.isRetired()).isTrue();
        assertThat(result.getOfferVersion()).isNotNull().isNotEqualTo(oldOffer);
    }

    @Test
    void unblockSlotRejectsOverlappingConfirmedAppointmentOrNonBlockedSlot() {
        AppointmentSlotService clockedService = new AppointmentSlotService(
                slotRepository, availabilityRepository, doctorRepository, appointmentRepository,
                Clock.fixed(Instant.parse("2030-01-15T04:00:00Z"), IST));

        AppointmentSlot available = slot(78L, LocalTime.of(10, 0), SlotStatus.AVAILABLE);
        when(slotRepository.findDoctorIdById(78L)).thenReturn(Optional.of(DOCTOR_ID));
        when(doctorRepository.findByIdForUpdate(DOCTOR_ID)).thenReturn(Optional.of(doctor));
        when(slotRepository.findByIdForUpdate(78L)).thenReturn(Optional.of(available));

        assertThatThrownBy(() -> clockedService.unblockSlot(78L, ADMIN_ACCOUNT))
                .isInstanceOf(ConflictException.class)
                .satisfies(ex -> assertThat(((ConflictException) ex).getCode()).isEqualTo("SLOT_NOT_BLOCKED"));

        AppointmentSlot blocked = slot(79L, LocalTime.of(10, 0), SlotStatus.BLOCKED);
        when(slotRepository.findDoctorIdById(79L)).thenReturn(Optional.of(DOCTOR_ID));
        when(slotRepository.findByIdForUpdate(79L)).thenReturn(Optional.of(blocked));
        when(appointmentRepository.existsConfirmedAppointmentForSlot(79L)).thenReturn(false);
        when(appointmentRepository.existsOverlappingConfirmedAppointment(
                DOCTOR_ID, DATE, LocalTime.of(10, 0), LocalTime.of(10, 30))).thenReturn(true);

        assertThatThrownBy(() -> clockedService.unblockSlot(79L, ADMIN_ACCOUNT))
                .isInstanceOf(ConflictException.class)
                .satisfies(ex -> assertThat(((ConflictException) ex).getCode()).isEqualTo("SLOT_CONFLICTING_BOOKING"));
    }

    private AppointmentSlot slot(long id, LocalTime start, SlotStatus status) {
        AppointmentSlot slot = new AppointmentSlot();
        slot.setId(id);
        slot.setDoctor(doctor);
        slot.setDate(DATE);
        slot.setStartTime(start);
        slot.setEndTime(start.plusMinutes(30));
        slot.setStatus(status);
        return slot;
    }

    private void givenAvailability(String start, String end) {
        DoctorAvailability availability = new DoctorAvailability();
        availability.setId(AVAILABILITY_ID);
        availability.setDoctor(doctor);
        availability.setDate(DATE);
        availability.setStartTime(LocalTime.parse(start));
        availability.setEndTime(LocalTime.parse(end));
        availability.setRevision(7);
        when(availabilityRepository.findDoctorIdById(AVAILABILITY_ID)).thenReturn(Optional.of(DOCTOR_ID));
        when(doctorRepository.findByIdForUpdate(DOCTOR_ID)).thenReturn(Optional.of(doctor));
        when(availabilityRepository.findById(AVAILABILITY_ID)).thenReturn(Optional.of(availability));
    }
}

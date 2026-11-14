package com.harsh.hospitalmanagement.controller;

import com.harsh.hospitalmanagement.entity.AppointmentSlot;
import com.harsh.hospitalmanagement.entity.Doctor;
import com.harsh.hospitalmanagement.entity.Specialization;
import com.harsh.hospitalmanagement.entity.User;
import com.harsh.hospitalmanagement.enums.Role;
import com.harsh.hospitalmanagement.enums.SlotStatus;
import com.harsh.hospitalmanagement.exception.ConflictException;
import com.harsh.hospitalmanagement.exception.GlobalExceptionHandler;
import com.harsh.hospitalmanagement.security.WithLocalUser;
import com.harsh.hospitalmanagement.security.WithMvcSecurity;
import com.harsh.hospitalmanagement.service.AppointmentSlotService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalTime;

import static com.harsh.hospitalmanagement.security.WithLocalUser.Factory.account;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {AppointmentSlotController.class, GlobalExceptionHandler.class})
@WithMvcSecurity
class AppointmentSlotControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AppointmentSlotService slotService;

    @Test
    @WithLocalUser(username = "admin@test.com", roles = "ADMIN")
    void unblockSlot_shouldReturn200WithSensitiveHeaders_whenAdmin() throws Exception {
        User doctorUser = new User();
        doctorUser.setId(20L);
        doctorUser.setEmail("doctor@test.com");
        doctorUser.setRole(Role.DOCTOR);

        Specialization specialization = new Specialization();
        specialization.setId(1L);
        specialization.setName("Cardiology");
        specialization.setDescription("Heart care");

        Doctor doctor = new Doctor();
        doctor.setId(2L);
        doctor.setFirstName("Sarah");
        doctor.setLastName("Doctor");
        doctor.setUser(doctorUser);
        doctor.setSpecialization(specialization);

        AppointmentSlot slot = new AppointmentSlot();
        slot.setId(9L);
        slot.setDate(LocalDate.of(2026, 12, 11));
        slot.setStartTime(LocalTime.of(10, 0));
        slot.setEndTime(LocalTime.of(10, 30));
        slot.setStatus(SlotStatus.AVAILABLE);
        slot.setDoctor(doctor);

        when(slotService.unblockSlot(9L, account("admin@test.com"))).thenReturn(slot);

        mockMvc.perform(patch("/api/appointment-slots/9/unblock").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(jsonPath("$.id").value(9L))
                .andExpect(jsonPath("$.status").value("AVAILABLE"));
    }

    @Test
    @WithLocalUser(username = "doctor@test.com", roles = "DOCTOR")
    void unblockSlot_shouldReturn403_whenDoctor() throws Exception {
        mockMvc.perform(patch("/api/appointment-slots/9/unblock").with(csrf()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(slotService);
    }

    @Test
    @WithLocalUser(username = "patient@test.com", roles = "PATIENT")
    void unblockSlot_shouldReturn403_whenPatient() throws Exception {
        mockMvc.perform(patch("/api/appointment-slots/9/unblock").with(csrf()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(slotService);
    }

    @Test
    void unblockSlot_shouldReturn401_whenUnauthenticated() throws Exception {
        mockMvc.perform(patch("/api/appointment-slots/9/unblock").with(csrf()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(slotService);
    }

    @Test
    @WithLocalUser(username = "admin@test.com", roles = "ADMIN")
    void unblockSlot_shouldReturn409_whenSlotConflictThrown() throws Exception {
        when(slotService.unblockSlot(9L, account("admin@test.com")))
                .thenThrow(new ConflictException("SLOT_NOT_BLOCKED", "Only blocked appointment slots can be unblocked"));

        mockMvc.perform(patch("/api/appointment-slots/9/unblock").with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("SLOT_NOT_BLOCKED"));
    }
}

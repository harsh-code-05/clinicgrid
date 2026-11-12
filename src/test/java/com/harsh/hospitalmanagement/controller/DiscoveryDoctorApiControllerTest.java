package com.harsh.hospitalmanagement.controller;

import com.harsh.hospitalmanagement.dto.DoctorDiscoveryResponse;
import com.harsh.hospitalmanagement.dto.DoctorDiscoveryResponse.ClinicLocationInfo;
import com.harsh.hospitalmanagement.dto.DoctorDiscoveryResponse.SpecializationInfo;
import com.harsh.hospitalmanagement.dto.LocationSuggestion;
import com.harsh.hospitalmanagement.enums.ConsultationMode;
import com.harsh.hospitalmanagement.exception.BadRequestException;
import com.harsh.hospitalmanagement.security.WithMvcSecurity;
import com.harsh.hospitalmanagement.service.AppointmentService;
import com.harsh.hospitalmanagement.service.AppointmentSlotService;
import com.harsh.hospitalmanagement.service.DoctorDiscoveryService;
import com.harsh.hospitalmanagement.service.LocationSearchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DiscoveryAppointmentController.class)
@WithMvcSecurity
class DiscoveryDoctorApiControllerTest {

    private static final String DISCOVERY_DOCTORS = "/api/v2/discovery/doctors";

    @Autowired private MockMvc mvc;
    @MockitoBean private AppointmentSlotService slots;
    @MockitoBean private AppointmentService appointments;
    @MockitoBean private DoctorDiscoveryService discoveryService;
    @MockitoBean private LocationSearchService locationSearch;

    @Test
    void anonymousGetCanDiscoverDoctors() throws Exception {
        DoctorDiscoveryResponse doc = new DoctorDiscoveryResponse(
                1L, "Alice", "Smith", "Dr. Alice Smith",
                new SpecializationInfo(10L, "Cardiology"),
                12, "MBBS, MD", "Expert cardiologist", "Female", null,
                List.of(ConsultationMode.IN_CLINIC, ConsultationMode.VIDEO),
                new BigDecimal("500.00"), new BigDecimal("600.00"), "INR",
                List.of(new ClinicLocationInfo(
                        101L, "Heart Clinic", "123 Main St, Bandra, Mumbai, Maharashtra, 400050",
                        "123 Main St", null, "Bandra", "Mumbai", "Maharashtra", "400050",
                        new BigDecimal("600.00")
                ))
        );

        when(discoveryService.discoverDoctors(null, null, null)).thenReturn(List.of(doc));

        mvc.perform(get(DISCOVERY_DOCTORS).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].doctorId").value(1))
                .andExpect(jsonPath("$[0].displayName").value("Dr. Alice Smith"))
                .andExpect(jsonPath("$[0].specialization.name").value("Cardiology"))
                .andExpect(jsonPath("$[0].experience").value(12))
                .andExpect(jsonPath("$[0].consultationModes[0]").value("IN_CLINIC"))
                .andExpect(jsonPath("$[0].consultationModes[1]").value("VIDEO"))
                .andExpect(jsonPath("$[0].videoConsultationFee").value(500.00))
                .andExpect(jsonPath("$[0].startingInClinicFee").value(600.00))
                .andExpect(jsonPath("$[0].currency").value("INR"))
                .andExpect(jsonPath("$[0].clinics[0].clinicName").value("Heart Clinic"))
                // Verify sensitive and unnecessary fields are not present
                .andExpect(jsonPath("$[0].clinics[0].contactPhone").doesNotExist())
                .andExpect(jsonPath("$[0].password").doesNotExist())
                .andExpect(jsonPath("$[0].user").doesNotExist())
                .andExpect(jsonPath("$[0].email").doesNotExist());
    }

    @Test
    void discoverDoctorsForwardsQueryParameters() throws Exception {
        when(discoveryService.discoverDoctors(10L, "Mumbai", "VIDEO")).thenReturn(List.of());

        mvc.perform(get(DISCOVERY_DOCTORS)
                        .param("specializationId", "10")
                        .param("city", "Mumbai")
                        .param("mode", "VIDEO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void invalidSpecializationIdTypeReturns400Json() throws Exception {
        mvc.perform(get(DISCOVERY_DOCTORS).param("specializationId", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Invalid value for parameter 'specializationId'."));
    }

    @Test
    void negativeSpecializationIdReturns400Json() throws Exception {
        when(discoveryService.discoverDoctors(-5L, null, null))
                .thenThrow(new BadRequestException("Query parameter 'specializationId' must be a positive integer."));

        mvc.perform(get(DISCOVERY_DOCTORS).param("specializationId", "-5"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Query parameter 'specializationId' must be a positive integer."));
    }

    @Test
    void invalidModeReturns400Json() throws Exception {
        when(discoveryService.discoverDoctors(null, null, "INVALID"))
                .thenThrow(new BadRequestException("Query parameter 'mode' must be one of: IN_CLINIC, VIDEO."));

        mvc.perform(get(DISCOVERY_DOCTORS).param("mode", "INVALID"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Query parameter 'mode' must be one of: IN_CLINIC, VIDEO."));
    }

    @Test
    void anonymousGetCanDiscoverCities() throws Exception {
        var area = new LocationSuggestion("area_id", "Example Area", "Uttar Pradesh", "Kanpur",
                LocationSuggestion.Type.AREA, LocationSuggestion.Source.GOOGLE_PLACES, "area_id");
        var district = new LocationSuggestion("157", "Kanpur Nagar", "Uttar Pradesh", null,
                LocationSuggestion.Type.DISTRICT, LocationSuggestion.Source.LGD, null);
        when(locationSearch.search("kan", null)).thenReturn(new LocationSearchService.Results(List.of(area, district),
                LocationSearchService.GoogleStatus.AVAILABLE));

        mvc.perform(get("/api/v2/discovery/cities").param("q", "kan").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.googleStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.suggestions[0].type").value("AREA"))
                .andExpect(jsonPath("$.suggestions[0].parentCity").value("Kanpur"))
                .andExpect(jsonPath("$.suggestions[0].state").value("Uttar Pradesh"))
                .andExpect(jsonPath("$.suggestions[1].id").value("157"))
                .andExpect(jsonPath("$.suggestions[1].placeId").doesNotExist());
    }

    @Test
    void discoverCitiesBlankQueryReturns400() throws Exception {
        when(locationSearch.search("   ", null))
                .thenThrow(new BadRequestException("Query parameter 'q' must not be blank."));

        mvc.perform(get("/api/v2/discovery/cities").param("q", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Query parameter 'q' must not be blank."));
    }

    @Test
    void discoverCitiesShortQueryReturns400() throws Exception {
        when(locationSearch.search("k", null))
                .thenThrow(new BadRequestException("Query parameter 'q' must be at least 2 characters."));

        mvc.perform(get("/api/v2/discovery/cities").param("q", "k"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Query parameter 'q' must be at least 2 characters."));
    }

    @Test
    void discoverCitiesMissingQueryParamReturns400() throws Exception {
        when(locationSearch.search(null, null))
                .thenThrow(new BadRequestException("Query parameter 'q' must not be blank."));

        mvc.perform(get("/api/v2/discovery/cities"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Query parameter 'q' must not be blank."));
    }

    @Test
    void publicLocationResolutionRequiresCsrfAndReturnsCanonicalLocation() throws Exception {
        String token = "7a0b3948-1abc-4a11-812e-5537b3ad0861";
        String payload = "{\"placeId\":\"area_id\",\"sessionToken\":\"" + token + "\"}";
        String path = "/api/v2/discovery/locations/resolve";
        var area = new LocationSuggestion("area_id", "Mumbai Central", "Maharashtra", "Mumbai",
                LocationSuggestion.Type.AREA, LocationSuggestion.Source.GOOGLE_PLACES, "area_id");
        when(locationSearch.resolvePrediction("area_id", token)).thenReturn(Optional.of(area));
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isForbidden());
        mvc.perform(post(path).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parentCity").value("Mumbai"));
    }

    @Test
    void publicNearbyValidatesCoordinatesAndReturnsOnlyCity() throws Exception {
        String path = "/api/v2/discovery/locations/nearby";
        var city = new LocationSuggestion("mumbai_id", "Mumbai", "Maharashtra", null,
                LocationSuggestion.Type.CITY, LocationSuggestion.Source.GOOGLE_GEOCODING, "mumbai_id");
        when(locationSearch.nearby(new BigDecimal("19.076"), new BigDecimal("72.878")))
                .thenReturn(Optional.of(city));
        mvc.perform(post(path).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":19.076,\"longitude\":72.878}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Mumbai"))
                .andExpect(jsonPath("$.latitude").doesNotExist());
        mvc.perform(post(path).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":123,\"longitude\":72.878}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(path).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":\"abc\",\"longitude\":72.878}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Invalid request body."));
        mvc.perform(post("/api/v2/discovery/locations/resolve").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Invalid request body."));
    }

    @Test
    void passesCanonicalAreaToExistingDoctorSearch() throws Exception {
        var area = new LocationSearchService.Filter("mumbai", Set.of("maharashtra"), null, "mumbai central");
        when(locationSearch.resolveFilter("area_id", "AREA", "Mumbai Central", "Maharashtra", "Mumbai"))
                .thenReturn(area);
        when(discoveryService.discoverDoctors(null, null, "IN_CLINIC", area)).thenReturn(List.of());
        mvc.perform(get(DISCOVERY_DOCTORS).param("locationId", "area_id").param("locationType", "AREA")
                        .param("locationName", "Mumbai Central").param("locationState", "Maharashtra")
                        .param("locationParentCity", "Mumbai").param("mode", "IN_CLINIC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}

package com.harsh.hospitalmanagement.controller;

import com.harsh.hospitalmanagement.dto.DoctorDiscoveryResponse;
import com.harsh.hospitalmanagement.dto.LocationSuggestion;
import com.harsh.hospitalmanagement.enums.AppointmentStatus;
import com.harsh.hospitalmanagement.enums.ConsultationMode;
import com.harsh.hospitalmanagement.security.LocalAccount;
import com.harsh.hospitalmanagement.service.AppointmentService;
import com.harsh.hospitalmanagement.service.AppointmentSlotService;
import com.harsh.hospitalmanagement.service.DoctorDiscoveryService;
import com.harsh.hospitalmanagement.service.LocationSearchService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v2")
public class DiscoveryAppointmentController {

    private final AppointmentSlotService slots;
    private final AppointmentService appointments;
    private final DoctorDiscoveryService discoveryService;
    private final LocationSearchService locationSearch;

    public DiscoveryAppointmentController(
            AppointmentSlotService slots,
            AppointmentService appointments,
            DoctorDiscoveryService discoveryService,
            LocationSearchService locationSearch
    ) {
        this.slots = slots;
        this.appointments = appointments;
        this.discoveryService = discoveryService;
        this.locationSearch = locationSearch;
    }

    @GetMapping("/discovery/doctors")
    public List<DoctorDiscoveryResponse> discoverDoctors(
            @RequestParam(required = false) Long specializationId,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String mode,
            @RequestParam(required = false) String locationId,
            @RequestParam(required = false) String locationType,
            @RequestParam(required = false) String locationName,
            @RequestParam(required = false) String locationState,
            @RequestParam(required = false) String locationParentCity
    ) {
        if (locationId != null || locationType != null || locationName != null || locationState != null
                || locationParentCity != null) {
            var filter = locationSearch.resolveFilter(locationId, locationType, locationName, locationState, locationParentCity);
            return discoveryService.discoverDoctors(specializationId, city, mode, filter);
        }
        return discoveryService.discoverDoctors(specializationId, city, mode);
    }

    @GetMapping("/discovery/cities")
    public ResponseEntity<LocationSearchService.Results> discoverLocations(@RequestParam(required = false) String q,
            @RequestParam(required = false) String sessionToken) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(locationSearch.search(q, sessionToken));
    }

    @PostMapping("/discovery/locations/resolve")
    public ResponseEntity<?> resolveLocation(@Valid @RequestBody ResolveLocationRequest request) {
        return locationResponse(locationSearch.resolvePrediction(request.placeId(), request.sessionToken()),
                "Could not confirm this location. Search again or select a district.");
    }

    @PostMapping("/discovery/locations/nearby")
    public ResponseEntity<?> nearbyLocation(@Valid @RequestBody NearbyLocationRequest request) {
        return locationResponse(locationSearch.nearby(request.latitude(), request.longitude()),
                "Could not find an Indian city near your location. Search for a city or district instead.");
    }

    private ResponseEntity<?> locationResponse(Optional<LocationSuggestion> location, String message) {
        if (location.isPresent()) {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(location.get());
        }
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).cacheControl(CacheControl.noStore())
                .body(Map.of("status", HttpStatus.UNPROCESSABLE_CONTENT.value(), "message", message));
    }

    @GetMapping("/discovery/doctors/{doctorId}/slots")
    public List<SlotOffer> getOffers(@PathVariable Long doctorId, @RequestParam LocalDate date) {
        return slots.getDiscoverableOffers(doctorId, date).stream()
                .map(slot -> new SlotOffer(slot.getId(), slot.getDate(), slot.getStartTime(), slot.getEndTime(),
                        slot.getLocation() == null ? null : slot.getLocation().getId(), slot.getConsultationMode(),
                        slot.getFee().toPlainString(), "INR", slot.getOfferVersion()))
                .toList();
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<Map<String, Object>> handleMissingParameter(MissingServletRequestParameterException failure) {
        return invalidParameter(failure.getParameterName());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Map<String, Object>> handleInvalidParameter(MethodArgumentTypeMismatchException failure) {
        return invalidParameter(failure.getName());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> handleUnreadableBody() {
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("status", 400, "message", "Invalid request body."));
    }

    private ResponseEntity<Map<String, Object>> invalidParameter(String parameter) {
        String message = "date".equals(parameter)
                ? "Query parameter 'date' is required and must be a valid date in YYYY-MM-DD format."
                : "Invalid value for parameter '" + parameter + "'.";
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("status", 400, "message", message));
    }

    @PostMapping("/appointments")
    public BookingResponse book(@Valid @RequestBody BookingRequest request, Authentication authentication) {
        var appointment = appointments.bookVersionedOffer(request.slotId(), request.expectedDate(),
                request.offerVersion(), request.reason(), LocalAccount.require(authentication));
        return new BookingResponse(appointment.getId(), appointment.getStatus(),
                appointment.getSlot().getId(), appointment.getSlot().getDate(),
                appointment.getLocation() == null ? null : appointment.getLocation().getId(),
                appointment.getConsultationMode(), appointment.getClinicNameSnapshot(),
                appointment.getClinicAddressSnapshot(), appointment.getConsultationFee(), appointment.getOfferVersion());
    }

    public record SlotOffer(Long slotId, LocalDate date, LocalTime startTime, LocalTime endTime,
                            Long locationId, ConsultationMode consultationMode, String fee, String currency,
                            UUID offerVersion) {
    }

    public record BookingRequest(@NotNull Long slotId, @NotNull LocalDate expectedDate,
                                 @NotNull UUID offerVersion, @NotBlank @Size(max = 255) String reason) {
    }

    public record ResolveLocationRequest(@NotBlank @Size(max = 255) String placeId,
                                         @NotBlank @Size(max = 36) String sessionToken) {}

    public record NearbyLocationRequest(
            @NotNull @DecimalMin("-90") @DecimalMax("90") @Digits(integer = 2, fraction = 8) BigDecimal latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180") @Digits(integer = 3, fraction = 8) BigDecimal longitude) {}

    public record BookingResponse(Long appointmentId, AppointmentStatus status, Long slotId, LocalDate date,
                                  Long locationId, ConsultationMode consultationMode, String clinicName,
                                  String clinicAddress, BigDecimal consultationFee, UUID offerVersion) {
    }
}

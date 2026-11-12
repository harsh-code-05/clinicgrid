package com.harsh.hospitalmanagement.service;

import com.harsh.hospitalmanagement.entity.DoctorLocation;
import com.harsh.hospitalmanagement.exception.BadRequestException;
import com.harsh.hospitalmanagement.exception.ConflictException;
import com.harsh.hospitalmanagement.exception.ResourceNotFoundException;
import com.harsh.hospitalmanagement.repository.AppointmentSlotRepository;
import com.harsh.hospitalmanagement.repository.DoctorLocationRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class DoctorLocationService {

    private final DoctorManagementAccess access;
    private final DoctorLocationRepository locations;
    private final AppointmentSlotRepository slots;
    private final LgdDistrictDirectory districts;

    DoctorLocationService(DoctorManagementAccess access, DoctorLocationRepository locations,
                          AppointmentSlotRepository slots, LgdDistrictDirectory districts) {
        this.access = access;
        this.locations = locations;
        this.slots = slots;
        this.districts = districts;
    }

    public DoctorLocation create(Long doctorId, ClinicDetails details, Authentication authentication) {
        DoctorLocation clinic = new DoctorLocation();
        clinic.setDoctor(access.requireManager(doctorId, authentication).doctor());
        applyDetails(clinic, details);
        return locations.save(clinic);
    }

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public List<DoctorLocation> listActive(Long doctorId, Authentication authentication) {
        access.requireManagerForRead(doctorId, authentication);
        return locations.findAllByDoctor_IdAndActiveTrueAndArchivedFalseOrderByIdAsc(doctorId);
    }

    public DoctorLocation update(Long doctorId, Long locationId, ClinicDetails details, Authentication authentication) {
        access.requireManager(doctorId, authentication);
        DoctorLocation clinic = requireClinic(doctorId, locationId);
        if (clinic.isArchived()) {
            throw new ConflictException("An archived clinic cannot be updated");
        }
        applyDetails(clinic, details);
        slots.retireForLocation(doctorId, locationId);
        return clinic;
    }

    public void archive(Long doctorId, Long locationId, Authentication authentication) {
        access.requireManager(doctorId, authentication);
        DoctorLocation clinic = requireClinic(doctorId, locationId);
        clinic.setActive(false);
        clinic.setArchived(true);
        slots.retireForLocation(doctorId, locationId);
    }

    public void setVideoFee(Long doctorId, BigDecimal fee, Authentication authentication) {
        var doctor = access.requireManager(doctorId, authentication).doctor();
        BigDecimal validated = ConsultationTerms.requireFee(fee);
        if (doctor.getVideoConsultationFee() == null || doctor.getVideoConsultationFee().compareTo(validated) != 0) {
            doctor.setVideoConsultationFee(validated);
            slots.retireVideoOffers(doctorId);
        }
    }

    private DoctorLocation requireClinic(Long doctorId, Long locationId) {
        return locations.findByIdAndDoctor_Id(locationId, doctorId)
                .orElseThrow(() -> new ResourceNotFoundException("Clinic not found for this doctor"));
    }

    private void applyDetails(DoctorLocation clinic, ClinicDetails details) {
        String addressLine1 = required(details.addressLine1(), 255, "Address line 1");
        String addressLine2 = details.addressLine2() == null || details.addressLine2().isBlank() ? null
                : required(details.addressLine2(), 255, "Address line 2");
        String locality = required(details.locality(), 120, "Locality");
        String city = required(details.city(), 120, "City");
        String state = required(details.state(), 120, "State");
        String postalCode = required(details.postalCode(), 20, "Postal code");
        Integer districtCode = details.lgdDistrictCode();
        if (districtCode != null) {
            var district = districts.find(districtCode.toString())
                    .orElseThrow(() -> new BadRequestException("Unknown LGD district code"));
            if (!districts.stateValues(district.state()).contains(LgdDistrictDirectory.normalize(state))) {
                throw new BadRequestException("LGD district does not belong to the clinic state");
            }
        } else if (Objects.equals(clinic.getAddressLine1(), addressLine1)
                && Objects.equals(clinic.getAddressLine2(), addressLine2)
                && Objects.equals(clinic.getLocality(), locality)
                && Objects.equals(clinic.getCity(), city)
                && Objects.equals(clinic.getState(), state)
                && Objects.equals(clinic.getPostalCode(), postalCode)) {
            districtCode = clinic.getLgdDistrictCode();
        }

        clinic.setClinicName(required(details.clinicName(), 255, "Clinic name"));
        clinic.setAddressLine1(addressLine1);
        clinic.setAddressLine2(addressLine2);
        clinic.setLocality(locality);
        clinic.setCity(city);
        clinic.setState(state);
        clinic.setPostalCode(postalCode);
        clinic.setLgdDistrictCode(districtCode);
        clinic.setContactPhone(required(details.contactPhone(), 32, "Contact phone"));
        clinic.setInClinicFee(ConsultationTerms.requireFee(details.inClinicFee()));
        clinic.setActive(details.active());
    }

    private String required(String value, int maxLength, String label) {
        if (value == null || value.isBlank() || value.strip().length() > maxLength) {
            throw new BadRequestException(label + " is required and must be at most " + maxLength + " characters");
        }
        return value.strip();
    }

    public record ClinicDetails(String clinicName, String addressLine1, String addressLine2, String locality,
                                String city, String state, String postalCode, String contactPhone,
                                BigDecimal inClinicFee, boolean active, Integer lgdDistrictCode) {
        public ClinicDetails(String clinicName, String addressLine1, String addressLine2, String locality,
                             String city, String state, String postalCode, String contactPhone,
                             BigDecimal inClinicFee, boolean active) {
            this(clinicName, addressLine1, addressLine2, locality, city, state, postalCode, contactPhone,
                    inClinicFee, active, null);
        }
    }
}

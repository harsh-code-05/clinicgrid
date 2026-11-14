package com.harsh.hospitalmanagement.entity;

import com.harsh.hospitalmanagement.enums.AppointmentSlotOutcome;
import com.harsh.hospitalmanagement.enums.AppointmentStatus;
import com.harsh.hospitalmanagement.enums.ConsultationMode;
import com.harsh.hospitalmanagement.enums.Role;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "appointments")
public class Appointment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String reason;

    private LocalDateTime createdAt;

    @Enumerated(EnumType.STRING)
    private AppointmentStatus status;

    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancelled_by_user_id")
    private Long cancelledByUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancelled_by_role", length = 16)
    private Role cancelledByRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancellation_slot_outcome", length = 32)
    private AppointmentSlotOutcome cancellationSlotOutcome;

    @ManyToOne
    @JoinColumn(name = "patient_id", nullable = false)
    private Patient patient;

    @ManyToOne
    @JoinColumn(name = "doctor_id", nullable = false)
    private Doctor doctor;

    @ManyToOne
    @JoinColumn(name = "slot_id", nullable = false)
    private AppointmentSlot slot;

    @Setter(AccessLevel.NONE)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id", updatable = false, foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private DoctorLocation location;

    @Setter(AccessLevel.NONE)
    @Enumerated(EnumType.STRING)
    @Column(name = "consultation_mode", length = 16, updatable = false)
    private ConsultationMode consultationMode;

    @Setter(AccessLevel.NONE)
    @Column(name = "clinic_name_snapshot", updatable = false)
    private String clinicNameSnapshot;

    @Setter(AccessLevel.NONE)
    @Column(name = "clinic_address_snapshot", columnDefinition = "text", updatable = false)
    private String clinicAddressSnapshot;

    @Setter(AccessLevel.NONE)
    @Column(name = "consultation_fee", precision = 12, scale = 2, updatable = false)
    private BigDecimal consultationFee;

    @Setter(AccessLevel.NONE)
    @Column(name = "offer_version", updatable = false)
    private UUID offerVersion;

    public void markCancelled(
            String reason,
            Instant cancelledAt,
            Long cancelledByUserId,
            Role cancelledByRole,
            AppointmentSlotOutcome slotOutcome) {
        this.status = AppointmentStatus.CANCELLED;
        this.cancellationReason = reason;
        this.cancelledAt = cancelledAt;
        this.cancelledByUserId = cancelledByUserId;
        this.cancelledByRole = cancelledByRole;
        this.cancellationSlotOutcome = slotOutcome;
    }

    public void captureOffer(AppointmentSlot offer) {
        if (id != null || offerVersion != null || offer.getOfferVersion() == null) {
            throw new IllegalStateException("An appointment can capture a versioned offer only once, before insert");
        }
        location = offer.getLocation();
        consultationMode = offer.getConsultationMode();
        consultationFee = offer.getFee();
        offerVersion = offer.getOfferVersion();
        if (location != null) {
            clinicNameSnapshot = location.getClinicName();
            clinicAddressSnapshot = location.formattedAddress();
        }
    }
}

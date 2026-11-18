package com.harsh.hospitalmanagement.dto;

import com.harsh.hospitalmanagement.enums.BillStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BillResponse {
    private Long id;
    private String invoiceNumber;
    private Long patientId;
    private String patientName;
    private String patientEmail;
    private Long doctorId;
    private String doctorName;
    private String doctorSpecialization;
    private Long appointmentId;
    private String appointmentDate;
    private String appointmentTime;
    private String appointmentStatus;
    private BigDecimal amount;
    private String currency;
    private BillStatus status;
    private String paymentMethod;
    private String paymentReference;
    private LocalDateTime issuedAt;
    private LocalDateTime paidAt;
    private Long paidByAdminId;
    private String paidByAdminEmail;
    private LocalDateTime voidedAt;
    private Long voidedByAdminId;
    private String voidedByAdminEmail;
    private String voidReason;
    private List<BillItemDto> items;
    private List<PaymentRecordDto> paymentRecords;
    private boolean demonstrationNotice;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BillItemDto {
        private Long id;
        private String description;
        private int quantity;
        private BigDecimal unitPrice;
        private BigDecimal totalPrice;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PaymentRecordDto {
        private Long id;
        private BigDecimal amount;
        private String currency;
        private String paymentMethod;
        private String transactionReference;
        private Long recordedByAdminId;
        private String recordedByAdminEmail;
        private LocalDateTime recordedAt;
        private String notes;
    }
}

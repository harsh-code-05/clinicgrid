package com.harsh.hospitalmanagement.controller;

import com.harsh.hospitalmanagement.dto.BillResponse;
import com.harsh.hospitalmanagement.dto.GenerateBillRequest;
import com.harsh.hospitalmanagement.dto.RecordPaymentRequest;
import com.harsh.hospitalmanagement.dto.VoidBillRequest;
import com.harsh.hospitalmanagement.enums.BillStatus;
import com.harsh.hospitalmanagement.security.LocalAccount;
import com.harsh.hospitalmanagement.service.BillService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/bills")
public class AdminBillController {

    private final BillService billService;

    @Autowired
    public AdminBillController(BillService billService) {
        this.billService = billService;
    }

    @GetMapping
    public List<BillResponse> getAllBills(
            @RequestParam(required = false) BillStatus status,
            Authentication authentication) {
        LocalAccount actor = LocalAccount.require(authentication);
        String role = getRole(authentication);
        return billService.getAllBills(status, actor, role);
    }

    @GetMapping("/{id}")
    public BillResponse getBillById(@PathVariable Long id, Authentication authentication) {
        LocalAccount actor = LocalAccount.require(authentication);
        String role = getRole(authentication);
        return billService.getBillById(id, actor, role);
    }

    @PostMapping("/generate")
    public ResponseEntity<BillResponse> generateBill(
            @Valid @RequestBody GenerateBillRequest request,
            Authentication authentication) {
        LocalAccount actor = LocalAccount.require(authentication);
        String role = getRole(authentication);
        BillResponse response = billService.generateBillForAppointment(request.getAppointmentId(), actor, role);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{id}/pay")
    public BillResponse recordPayment(
            @PathVariable Long id,
            @Valid @RequestBody RecordPaymentRequest request,
            Authentication authentication) {
        LocalAccount actor = LocalAccount.require(authentication);
        String role = getRole(authentication);
        return billService.markBillAsPaid(id, request, actor, role);
    }

    @PostMapping("/{id}/void")
    public BillResponse voidBill(
            @PathVariable Long id,
            @Valid @RequestBody VoidBillRequest request,
            Authentication authentication) {
        LocalAccount actor = LocalAccount.require(authentication);
        String role = getRole(authentication);
        return billService.voidBill(id, request, actor, role);
    }

    private String getRole(Authentication authentication) {
        if (authentication == null || authentication.getAuthorities() == null) {
            return null;
        }
        return authentication.getAuthorities().stream()
                .findFirst()
                .map(GrantedAuthority::getAuthority)
                .orElse(null);
    }
}

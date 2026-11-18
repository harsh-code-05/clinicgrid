package com.harsh.hospitalmanagement.controller;

import com.harsh.hospitalmanagement.dto.BillResponse;
import com.harsh.hospitalmanagement.security.LocalAccount;
import com.harsh.hospitalmanagement.service.BillService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/patient/bills")
public class PatientBillController {

    private final BillService billService;

    @Autowired
    public PatientBillController(BillService billService) {
        this.billService = billService;
    }

    @GetMapping
    public List<BillResponse> getMyBills(Authentication authentication) {
        LocalAccount actor = LocalAccount.require(authentication);
        String role = getRole(authentication);
        return billService.getPatientBills(actor, role);
    }

    @GetMapping("/{id}")
    public BillResponse getBillById(@PathVariable Long id, Authentication authentication) {
        LocalAccount actor = LocalAccount.require(authentication);
        String role = getRole(authentication);
        return billService.getBillById(id, actor, role);
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

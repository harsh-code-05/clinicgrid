package com.harsh.hospitalmanagement.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Lightweight, unauthenticated health check endpoint for container probes (Docker / Render).
 * Executes zero database queries and zero external calls.
 */
@RestController
@RequestMapping("/api/health")
public class HealthCheckController {

    private static final Map<String, String> HEALTH_STATUS_UP = Map.of("status", "UP");

    @GetMapping
    public ResponseEntity<Map<String, String>> checkHealth() {
        return ResponseEntity.ok(HEALTH_STATUS_UP);
    }
}

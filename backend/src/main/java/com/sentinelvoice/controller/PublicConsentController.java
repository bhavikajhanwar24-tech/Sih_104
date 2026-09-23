package com.sentinelvoice.controller;

import com.sentinelvoice.compliance.ComplianceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * F15 — public OTP-less consent acceptance page API.
 */
@RestController
@RequestMapping("/api/v2/public/consent")
public class PublicConsentController {

    private final ComplianceService complianceService;

    public PublicConsentController(ComplianceService complianceService) {
        this.complianceService = complianceService;
    }

    @GetMapping("/{token}")
    public ResponseEntity<?> preview(@PathVariable String token) {
        try {
            return ResponseEntity.ok(complianceService.publicConsentPreview(token));
        } catch (Exception e) {
            return ResponseEntity.status(404).body(Map.of("error", "NOT_FOUND", "message", e.getMessage()));
        }
    }

    @PostMapping("/{token}/accept")
    public ResponseEntity<?> accept(@PathVariable String token) {
        try {
            return ResponseEntity.ok(complianceService.publicConsentAccept(token));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}

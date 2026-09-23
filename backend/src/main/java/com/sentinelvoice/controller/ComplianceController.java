package com.sentinelvoice.controller;

import com.sentinelvoice.analytics.AnalyticsService;
import com.sentinelvoice.compliance.ComplianceService;
import com.sentinelvoice.passport.ConsentRequiredException;
import com.sentinelvoice.security.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * F15 — tenant compliance: consent, retention, passports, DSR, DPDP mapping.
 */
@RestController
@RequestMapping("/api/v2/compliance")
public class ComplianceController {

    private final ComplianceService complianceService;
    private final AnalyticsService analyticsService;

    public ComplianceController(ComplianceService complianceService, AnalyticsService analyticsService) {
        this.complianceService = complianceService;
        this.analyticsService = analyticsService;
    }

    @GetMapping("/retention")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','AUDITOR','POLICY_APPROVER')")
    public Map<String, Object> retention() {
        return complianceService.retentionSnapshot(TenantContext.require().tenantId());
    }

    @PatchMapping("/settings")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public Map<String, Object> patchSettings(@RequestBody Map<String, Object> body) {
        TenantContext ctx = TenantContext.require();
        return complianceService.patchComplianceSettings(ctx.tenantId(), ctx.userId(), body);
    }

    @PostMapping(value = "/notice-asset", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<?> uploadNotice(
            @RequestPart(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "ttsText", required = false) String ttsText
    ) {
        TenantContext ctx = TenantContext.require();
        try {
            return ResponseEntity.ok(complianceService.uploadNoticeAsset(ctx.tenantId(), ctx.userId(), file, ttsText));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage() == null ? "upload failed" : e.getMessage()));
        }
    }

    @GetMapping("/consents")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','AUDITOR')")
    public Map<String, Object> consents(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String purpose
    ) {
        UUID tenantId = TenantContext.require().tenantId();
        List<Map<String, Object>> items = complianceService.listConsents(tenantId, status, purpose);
        return Map.of("schemaVersion", "2", "tenantId", tenantId.toString(), "items", items);
    }

    @PostMapping("/consents")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<?> upsertConsent(@RequestBody Map<String, Object> body) {
        TenantContext ctx = TenantContext.require();
        try {
            UUID employeeId = UUID.fromString(String.valueOf(body.get("employeeId")));
            String purpose = String.valueOf(body.get("purpose"));
            String status = String.valueOf(body.get("status"));
            String method = body.get("method") == null ? "ADMIN" : String.valueOf(body.get("method"));
            return ResponseEntity.ok(complianceService.upsertConsent(
                    ctx.tenantId(), ctx.userId(), employeeId, purpose, status, method));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/consents/bulk")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<?> bulk(@RequestBody Map<String, Object> body) {
        TenantContext ctx = TenantContext.require();
        try {
            @SuppressWarnings("unchecked")
            List<String> ids = (List<String>) body.getOrDefault("employeeIds", List.of());
            List<UUID> uuids = new ArrayList<>();
            for (String id : ids) {
                uuids.add(UUID.fromString(id));
            }
            return ResponseEntity.ok(complianceService.bulkConsent(
                    ctx.tenantId(),
                    ctx.userId(),
                    uuids,
                    String.valueOf(body.get("purpose")),
                    String.valueOf(body.get("status"))
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/consents/link")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<?> issueLink(@RequestBody Map<String, Object> body) {
        TenantContext ctx = TenantContext.require();
        try {
            UUID employeeId = UUID.fromString(String.valueOf(body.get("employeeId")));
            String purpose = String.valueOf(body.getOrDefault("purpose", "MONITORING"));
            int ttl = body.get("ttlHours") instanceof Number n ? n.intValue() : 72;
            return ResponseEntity.ok(complianceService.issuePublicConsentToken(
                    ctx.tenantId(), ctx.userId(), employeeId, purpose, ttl));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/passports/{employeeId}")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','AUDITOR')")
    public Map<String, Object> passport(@PathVariable UUID employeeId) {
        return complianceService.passportMeta(TenantContext.require().tenantId(), employeeId);
    }

    @PostMapping(value = "/passports/{employeeId}/enrol", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<?> enrolMultipart(
            @PathVariable UUID employeeId,
            @RequestPart(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "audioRef", required = false) String audioRef
    ) {
        return enrolInternal(employeeId, audioRef, file);
    }

    @PostMapping(value = "/passports/{employeeId}/enrol", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<?> enrolJson(
            @PathVariable UUID employeeId,
            @RequestBody(required = false) Map<String, Object> body
    ) {
        String audioRef = body == null || body.get("audioRef") == null
                ? "synthetic:enrol"
                : String.valueOf(body.get("audioRef"));
        return enrolInternal(employeeId, audioRef, null);
    }

    private ResponseEntity<?> enrolInternal(UUID employeeId, String audioRef, MultipartFile file) {
        TenantContext ctx = TenantContext.require();
        try {
            return ResponseEntity.ok(complianceService.enrolPassport(
                    ctx.tenantId(), ctx.userId(), employeeId,
                    audioRef == null || audioRef.isBlank() ? "synthetic:enrol" : audioRef,
                    file));
        } catch (ConsentRequiredException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "CONSENT_REQUIRED", "message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/passports/{employeeId}")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public Map<String, Object> deletePassport(@PathVariable UUID employeeId) {
        TenantContext ctx = TenantContext.require();
        return complianceService.deletePassport(ctx.tenantId(), ctx.userId(), employeeId);
    }

    @GetMapping("/employees/{employeeId}/export")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public Map<String, Object> export(@PathVariable UUID employeeId) {
        TenantContext ctx = TenantContext.require();
        return complianceService.exportEmployee(ctx.tenantId(), ctx.userId(), employeeId);
    }

    @PostMapping("/employees/{employeeId}/erase")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<?> erase(@PathVariable UUID employeeId, @RequestBody Map<String, Object> body) {
        TenantContext ctx = TenantContext.require();
        try {
            String confirm = body.get("confirm") == null ? "" : String.valueOf(body.get("confirm"));
            return ResponseEntity.ok(complianceService.eraseEmployee(
                    ctx.tenantId(), ctx.userId(), employeeId, confirm));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/retention/purge-now")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public Map<String, Object> purgeNow() {
        return complianceService.purgeTenant(TenantContext.require().tenantId());
    }

    @GetMapping("/dpdp-mapping")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','AUDITOR','POLICY_APPROVER')")
    public Map<String, Object> dpdp() {
        return complianceService.dpdpMapping();
    }

    /** Fairness — tenant directory tags + labelled FP rates (F16). Never inferred from voice. */
    @GetMapping("/fairness")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','AUDITOR','POLICY_APPROVER','ANALYST')")
    public Map<String, Object> fairness() {
        return analyticsService.fairnessReport();
    }
}

package com.sentinelvoice.controller;

import com.sentinelvoice.analytics.AnalyticsService;
import com.sentinelvoice.audit.AuditLedgerService;
import com.sentinelvoice.audit.ChainVerificationResult;
import com.sentinelvoice.audit.TenantChainVerification;
import com.sentinelvoice.compliance.ComplianceService;
import com.sentinelvoice.security.TenantContext;
import com.sentinelvoice.tenant.BootstrapTenant;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Legacy v1 compliance paths — re-pointed to F15 {@link ComplianceService} / tenant audit.
 * Prefer {@code /api/v2/compliance/**}.
 */
@RestController
@RequestMapping("/api/v1/compliance")
public class ComplianceAuditController {

    private final AuditLedgerService auditLedgerService;
    private final ComplianceService complianceService;
    private final AnalyticsService analyticsService;

    public ComplianceAuditController(
            AuditLedgerService auditLedgerService,
            ComplianceService complianceService,
            AnalyticsService analyticsService
    ) {
        this.auditLedgerService = auditLedgerService;
        this.complianceService = complianceService;
        this.analyticsService = analyticsService;
    }

    @GetMapping("/verify/{sessionId}")
    public ResponseEntity<ChainVerificationResult> verify(@PathVariable String sessionId) {
        return ResponseEntity.ok(auditLedgerService.verify(sessionId));
    }

    @GetMapping("/audit-chain/{sessionId}")
    public ResponseEntity<Map<String, Object>> auditChain(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        UUIDTenant tenant = resolveTenant();
        TenantChainVerification v = auditLedgerService.verifyTenant(tenant.id());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        body.put("note", "Prefer GET /api/v2/audit — chain is per-tenant");
        body.put("tenantId", tenant.id().toString());
        body.put("valid", v.valid());
        body.put("blocksChecked", v.blocksChecked());
        body.put("blocks", auditLedgerService.listTenant(tenant.id()).stream()
                .map(auditLedgerService::toView)
                .toList());
        body.put("page", Math.max(page, 0));
        body.put("size", Math.min(Math.max(size, 1), 200));
        body.put("totalElements", v.blocksChecked());
        body.put("totalPages", 1);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/sessions")
    public ResponseEntity<Map<String, Object>> sessions() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionIds", java.util.List.of());
        body.put("count", 0);
        body.put("note", "Use GET /api/v2/sessions");
        return ResponseEntity.ok(body);
    }

    @GetMapping("/retention")
    public ResponseEntity<Map<String, Object>> retention() {
        UUIDTenant tenant = resolveTenant();
        return ResponseEntity.ok(TenantContext.runAs(tenant.id(),
                () -> complianceService.retentionSnapshot(tenant.id())));
    }

    @GetMapping("/fairness")
    public ResponseEntity<Map<String, Object>> fairness() {
        UUIDTenant tenant = resolveTenant();
        return ResponseEntity.ok(TenantContext.runAs(tenant.id(), analyticsService::fairnessReport));
    }

    @GetMapping("/consent")
    public ResponseEntity<Map<String, Object>> consents() {
        UUIDTenant tenant = resolveTenant();
        return ResponseEntity.ok(TenantContext.runAs(tenant.id(), () -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("consents", complianceService.listConsents(tenant.id(), null, null));
            body.put("note", "Prefer GET /api/v2/compliance/consents");
            return body;
        }));
    }

    @PostMapping("/consent/{id}/withdraw")
    public ResponseEntity<Map<String, Object>> withdrawConsent(@PathVariable String id) {
        UUIDTenant tenant = resolveTenant();
        return ResponseEntity.ok(TenantContext.runAs(tenant.id(), () -> {
            // id is consent row UUID — look up employee/purpose
            var rows = complianceService.listConsents(tenant.id(), null, null).stream()
                    .filter(c -> id.equals(String.valueOf(c.get("id"))))
                    .toList();
            if (rows.isEmpty()) {
                return Map.<String, Object>of("error", "not found");
            }
            Map<String, Object> c = rows.getFirst();
            return complianceService.upsertConsent(
                    tenant.id(),
                    TenantContext.get() == null ? null : TenantContext.get().userId(),
                    java.util.UUID.fromString(String.valueOf(c.get("employeeId"))),
                    String.valueOf(c.get("purpose")),
                    "WITHDRAWN",
                    "ADMIN"
            );
        }));
    }

    private UUIDTenant resolveTenant() {
        TenantContext ctx = TenantContext.get();
        if (ctx != null && ctx.tenantId() != null) {
            return new UUIDTenant(ctx.tenantId());
        }
        return new UUIDTenant(BootstrapTenant.ID);
    }

    private record UUIDTenant(java.util.UUID id) {
    }
}

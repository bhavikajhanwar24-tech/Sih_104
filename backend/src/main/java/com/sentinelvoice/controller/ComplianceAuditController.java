package com.sentinelvoice.controller;

import com.sentinelvoice.audit.AuditLedgerService;
import com.sentinelvoice.audit.ChainVerificationResult;
import com.sentinelvoice.audit.TenantChainVerification;
import com.sentinelvoice.compliance.ComplianceMetricsService;
import com.sentinelvoice.tenant.BootstrapTenant;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/compliance")
public class ComplianceAuditController {

    private final AuditLedgerService auditLedgerService;
    private final ComplianceMetricsService metricsService;

    public ComplianceAuditController(
            AuditLedgerService auditLedgerService,
            ComplianceMetricsService metricsService
    ) {
        this.auditLedgerService = auditLedgerService;
        this.metricsService = metricsService;
    }

    @GetMapping("/verify/{sessionId}")
    public ResponseEntity<ChainVerificationResult> verify(@PathVariable String sessionId) {
        // Session-scoped verify is legacy; chain is per-tenant as of F1.
        return ResponseEntity.ok(auditLedgerService.verify(sessionId));
    }

    @GetMapping("/audit-chain/{sessionId}")
    public ResponseEntity<Map<String, Object>> auditChain(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        TenantChainVerification v = auditLedgerService.verifyTenant(BootstrapTenant.ID);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        body.put("note", "F1: chain is per-tenant; use GET /api/v2/audit/verify?tenantId=");
        body.put("tenantId", BootstrapTenant.ID.toString());
        body.put("valid", v.valid());
        body.put("blocksChecked", v.blocksChecked());
        body.put("blocks", auditLedgerService.listTenant(BootstrapTenant.ID).stream()
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
        List<String> ids = metricsService.auditSessionIds();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionIds", ids);
        body.put("count", ids.size());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/retention")
    public ResponseEntity<Map<String, Object>> retention() {
        return ResponseEntity.ok(metricsService.retentionDashboard());
    }

    @GetMapping("/fairness")
    public ResponseEntity<Map<String, Object>> fairness() {
        return ResponseEntity.ok(metricsService.fairnessReport());
    }

    @GetMapping("/consent")
    public ResponseEntity<Map<String, Object>> consents() {
        throw new UnsupportedOperationException("re-implemented in F12");
    }

    @PostMapping("/consent/{id}/withdraw")
    public ResponseEntity<Map<String, Object>> withdrawConsent(@PathVariable long id) {
        throw new UnsupportedOperationException("re-implemented in F12");
    }
}

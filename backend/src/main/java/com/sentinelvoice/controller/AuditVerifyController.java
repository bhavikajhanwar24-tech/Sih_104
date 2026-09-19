package com.sentinelvoice.controller;

import com.sentinelvoice.audit.AuditLedgerService;
import com.sentinelvoice.audit.TenantChainVerification;
import com.sentinelvoice.security.TenantContext;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v2/audit")
public class AuditVerifyController {

    private final AuditLedgerService auditLedgerService;

    public AuditVerifyController(AuditLedgerService auditLedgerService) {
        this.auditLedgerService = auditLedgerService;
    }

    @GetMapping("/verify")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','AUDITOR','POLICY_APPROVER','ANALYST')")
    public ResponseEntity<Map<String, Object>> verify(
            @RequestParam(value = "tenantId", required = false) UUID tenantId
    ) {
        TenantContext ctx = TenantContext.require();
        UUID target = tenantId == null ? ctx.tenantId() : tenantId;
        if (!ctx.tenantId().equals(target)) {
            throw new IllegalArgumentException("tenantId must match the authenticated tenant");
        }
        TenantChainVerification result = auditLedgerService.verifyTenant(target);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("valid", result.valid());
        body.put("blocksChecked", result.blocksChecked());
        body.put("firstBrokenSeq", result.firstBrokenSeq());
        return ResponseEntity.ok(body);
    }
}

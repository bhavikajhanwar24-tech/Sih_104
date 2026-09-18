package com.sentinelvoice.controller;

import com.sentinelvoice.audit.AuditBlockView;
import com.sentinelvoice.audit.AuditLedgerService;
import com.sentinelvoice.audit.ChainVerificationResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/compliance")
public class ComplianceAuditController {

    private final AuditLedgerService auditLedgerService;

    public ComplianceAuditController(AuditLedgerService auditLedgerService) {
        this.auditLedgerService = auditLedgerService;
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
        int safeSize = Math.min(Math.max(size, 1), 200);
        int safePage = Math.max(page, 0);
        Pageable pageable = PageRequest.of(safePage, safeSize);
        Page<AuditBlockView> result = auditLedgerService.chain(sessionId, pageable);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", sessionId);
        body.put("blocks", result.getContent());
        body.put("page", result.getNumber());
        body.put("size", result.getSize());
        body.put("totalElements", result.getTotalElements());
        body.put("totalPages", result.getTotalPages());
        return ResponseEntity.ok(body);
    }
}

package com.sentinelvoice.controller;

import com.sentinelvoice.service.HashChainedAuditService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/compliance")
public class ComplianceAuditController {

    private final HashChainedAuditService hashChainedAuditService;

    public ComplianceAuditController(HashChainedAuditService hashChainedAuditService) {
        this.hashChainedAuditService = hashChainedAuditService;
    }

    @GetMapping("/audit-chain")
    public ResponseEntity<Map<String, Object>> getAuditChain() {
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "ledger", hashChainedAuditService.getLedger()
        ));
    }
}

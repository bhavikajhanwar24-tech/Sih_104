package com.sentinelvoice.controller;

import com.sentinelvoice.audit.AuditBlockView;
import com.sentinelvoice.audit.AuditLedgerService;
import com.sentinelvoice.audit.ChainVerificationResult;
import com.sentinelvoice.compliance.ComplianceMetricsService;
import com.sentinelvoice.passport.VoicePassportService;
import com.sentinelvoice.passport.model.ConsentRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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
    private final VoicePassportService voicePassportService;

    public ComplianceAuditController(
            AuditLedgerService auditLedgerService,
            ComplianceMetricsService metricsService,
            VoicePassportService voicePassportService
    ) {
        this.auditLedgerService = auditLedgerService;
        this.metricsService = metricsService;
        this.voicePassportService = voicePassportService;
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
        List<Map<String, Object>> rows = voicePassportService.listConsents().stream()
                .map(this::consentView)
                .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("consents", rows);
        body.put("count", rows.size());
        body.put("dpdpRefs", List.of("§4", "§5", "§6"));
        return ResponseEntity.ok(body);
    }

    @PostMapping("/consent/{id}/withdraw")
    public ResponseEntity<Map<String, Object>> withdrawConsent(@PathVariable long id) {
        ConsentRecord record = voicePassportService.withdrawConsent(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "withdrawn");
        body.put("consent", consentView(record));
        body.put("dpdpRefs", List.of("§6", "§12"));
        return ResponseEntity.ok(body);
    }

    private Map<String, Object> consentView(ConsentRecord record) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", record.getId());
        row.put("employeeId", record.getEmployeeId());
        row.put("purpose", record.getPurpose());
        row.put("noticeVersion", record.getNoticeVersion());
        row.put("grantedAt", record.getGrantedAt() == null ? null : record.getGrantedAt().toString());
        row.put("grantedBy", record.getGrantedBy());
        row.put("method", record.getMethod());
        row.put("withdrawnAt", record.getWithdrawnAt() == null ? null : record.getWithdrawnAt().toString());
        row.put("status", record.isActive() ? "ACTIVE" : "WITHDRAWN");
        row.put("dpdpRefs", List.of("§4", "§5", "§6"));
        return row;
    }
}

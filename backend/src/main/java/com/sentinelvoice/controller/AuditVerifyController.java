package com.sentinelvoice.controller;

import com.sentinelvoice.audit.AuditLedgerService;
import com.sentinelvoice.audit.TenantChainVerification;
import com.sentinelvoice.model.AuditBlock;
import com.sentinelvoice.repository.AuditBlockRepository;
import com.sentinelvoice.security.TenantContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v2/audit")
public class AuditVerifyController {

    private final AuditLedgerService auditLedgerService;
    private final AuditBlockRepository auditBlockRepository;

    public AuditVerifyController(
            AuditLedgerService auditLedgerService,
            AuditBlockRepository auditBlockRepository
    ) {
        this.auditLedgerService = auditLedgerService;
        this.auditBlockRepository = auditBlockRepository;
    }

    @GetMapping("/verify")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','AUDITOR')")
    public ResponseEntity<Map<String, Object>> verify(
            @RequestParam(value = "from", required = false) Long from,
            @RequestParam(value = "to", required = false) Long to
    ) {
        UUID tenantId = TenantContext.require().tenantId();
        TenantChainVerification result = auditLedgerService.verifyTenant(tenantId, from, to);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("valid", result.valid());
        body.put("blocksChecked", result.blocksChecked());
        body.put("firstBrokenSeq", result.firstBrokenSeq());
        return ResponseEntity.ok(body);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','AUDITOR')")
    public Map<String, Object> list(
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            @RequestParam(value = "cursor", required = false) Long cursor,
            @RequestParam(value = "eventType", required = false) String eventType
    ) {
        UUID tenantId = TenantContext.require().tenantId();
        int pageSize = Math.min(Math.max(limit, 1), 200);
        List<AuditBlock> blocks;
        if (eventType != null && !eventType.isBlank()) {
            blocks = auditBlockRepository.findByTenantIdAndEventTypeAndSeqGreaterThanOrderBySeqAsc(
                    tenantId,
                    eventType.trim(),
                    cursor == null ? 0L : cursor,
                    PageRequest.of(0, pageSize + 1)
            );
        } else {
            blocks = auditBlockRepository.findByTenantIdAndSeqGreaterThanOrderBySeqAsc(
                    tenantId,
                    cursor == null ? 0L : cursor,
                    PageRequest.of(0, pageSize + 1)
            );
        }
        boolean hasMore = blocks.size() > pageSize;
        if (hasMore) {
            blocks = blocks.subList(0, pageSize);
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (AuditBlock b : blocks) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", b.getId().toString());
            row.put("seq", b.getSeq());
            row.put("eventType", b.getEventType());
            row.put("actorType", b.getActorType());
            row.put("actorId", b.getActorId());
            row.put("payload", b.getPayload());
            row.put("createdAt", b.getCreatedAt().toString());
            row.put("hash", b.getHash());
            items.add(row);
        }
        Long nextCursor = items.isEmpty() ? cursor : ((Number) items.get(items.size() - 1).get("seq")).longValue();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("nextCursor", hasMore ? nextCursor : null);
        return body;
    }
}

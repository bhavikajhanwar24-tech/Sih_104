package com.sentinelvoice.audit;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Hot-path writer: bounded single-thread queue ({@code auditWriterExecutor}).
 * Per-session order is preserved because the pool size is 1 and {@link AuditLedgerService#append}
 * also locks per session.
 */
@Component
public class AuditWriteDispatcher {

    private final AuditLedgerService auditLedgerService;

    public AuditWriteDispatcher(AuditLedgerService auditLedgerService) {
        this.auditLedgerService = auditLedgerService;
    }

    @Async("auditWriterExecutor")
    public void submit(String sessionId, AuditEventType type, Map<String, Object> payload) {
        auditLedgerService.append(sessionId, type, payload);
    }
}

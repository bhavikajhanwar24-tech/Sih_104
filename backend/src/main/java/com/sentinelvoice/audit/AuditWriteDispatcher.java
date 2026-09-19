package com.sentinelvoice.audit;

import com.sentinelvoice.model.CallSession;
import com.sentinelvoice.security.TenantContext;
import com.sentinelvoice.service.CallSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Hot-path writer: resolves tenant from the in-memory session registry (never from the client).
 */
@Component
public class AuditWriteDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AuditWriteDispatcher.class);

    private final AuditLedgerService auditLedgerService;
    private final CallSessionManager callSessionManager;

    public AuditWriteDispatcher(
            AuditLedgerService auditLedgerService,
            @Lazy CallSessionManager callSessionManager
    ) {
        this.auditLedgerService = auditLedgerService;
        this.callSessionManager = callSessionManager;
    }

    @Async("auditWriterExecutor")
    public void submit(String sessionId, AuditEventType type, Map<String, Object> payload) {
        CallSession session = callSessionManager.getSession(sessionId).orElse(null);
        if (session == null) {
            log.warn("audit_drop reason=unknown_session sessionId={} type={}", sessionId, type);
            return;
        }
        TenantContext.runAs(session.getTenantId(), () ->
                auditLedgerService.append(
                        session.getTenantId(),
                        sessionId,
                        type,
                        "SYSTEM",
                        null,
                        payload
                )
        );
    }
}

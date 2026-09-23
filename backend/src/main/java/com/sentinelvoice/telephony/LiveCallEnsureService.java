package com.sentinelvoice.telephony;

import com.sentinelvoice.model.CallSession;
import com.sentinelvoice.security.TenantContext;
import com.sentinelvoice.service.CallSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Ensures every live Decision Plane session has a {@code call_sessions} row so
 * Live Calls UI (DB-backed) and AGI/bridge (memory) stay in sync.
 */
@Service
public class LiveCallEnsureService {

    private static final Logger log = LoggerFactory.getLogger(LiveCallEnsureService.class);

    private final CallSessionRepository callSessionRepository;
    private final CallSessionManager callSessionManager;

    public LiveCallEnsureService(
            CallSessionRepository callSessionRepository,
            CallSessionManager callSessionManager
    ) {
        this.callSessionRepository = callSessionRepository;
        this.callSessionManager = callSessionManager;
    }

    /**
     * Idempotent: if a call_sessions row already exists for this UUID, no-op.
     */
    public void ensurePersisted(CallSession session) {
        if (session == null || session.getSessionId() == null || session.getSessionId().isBlank()) {
            return;
        }
        UUID tenantId = session.getTenantId();
        if (tenantId == null) {
            TenantContext ctx = TenantContext.get();
            if (ctx != null) {
                tenantId = ctx.tenantId();
            }
        }
        if (tenantId == null) {
            log.warn("live_call_ensure_skip sessionId={} reason=no_tenant", session.getSessionId());
            return;
        }
        UUID sv;
        try {
            sv = UUID.fromString(session.getSessionId());
        } catch (IllegalArgumentException ex) {
            log.warn("live_call_ensure_skip sessionId={} reason=not_uuid", session.getSessionId());
            return;
        }
        final UUID tid = tenantId;
        TenantContext.runAs(tid, () -> {
            if (callSessionRepository.findBySvSession(tid, sv).isPresent()) {
                return null;
            }
            try {
                callSessionRepository.insert(
                        tid,
                        sv,
                        blankToNull(session.getCallerId()),
                        blankToNull(session.getCalleeId()),
                        null,
                        null,
                        "INTERNAL",
                        null,
                        session.getPolicyVersion(),
                        session.getFusionConfigVersion(),
                        session.getResponsePlanVersion(),
                        null
                );
                log.info("live_call_row_ensured sessionId={} tenantId={}", session.getSessionId(), tid);
            } catch (Exception ex) {
                // Race with AGI sessions/start — treat as ok if row now exists.
                if (callSessionRepository.findBySvSession(tid, sv).isEmpty()) {
                    log.warn(
                            "live_call_ensure_failed sessionId={} err={}",
                            session.getSessionId(),
                            ex.toString()
                    );
                }
            }
            return null;
        });
    }

    public void ensurePersisted(String sessionId) {
        callSessionManager.getSession(sessionId).ifPresent(this::ensurePersisted);
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank() || "unknown".equalsIgnoreCase(s)) {
            return null;
        }
        return s;
    }
}

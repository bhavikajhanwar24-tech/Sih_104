package com.sentinelvoice.telephony;

import com.sentinelvoice.service.CallSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Reaps ghost Live Calls: DB rows with {@code ended_at IS NULL} but no in-memory session
 * (hangup missed {@code /sessions/end}). Without this, cut calls stay "active" forever.
 */
@Service
public class LiveCallGhostReaper {

    private static final Logger log = LoggerFactory.getLogger(LiveCallGhostReaper.class);

    /** Grace for answer race: AGI start may land before memory/ensure catches up. */
    static final long GRACE_SECONDS = 60L;

    private final CallSessionRepository callSessionRepository;
    private final CallSessionManager callSessionManager;
    private final CallLifecycleService callLifecycleService;

    public LiveCallGhostReaper(
            CallSessionRepository callSessionRepository,
            CallSessionManager callSessionManager,
            CallLifecycleService callLifecycleService
    ) {
        this.callSessionRepository = callSessionRepository;
        this.callSessionManager = callSessionManager;
        this.callLifecycleService = callLifecycleService;
    }

    @Scheduled(fixedDelay = 15_000L, initialDelay = 20_000L)
    public void reapGhosts() {
        Instant cutoff = Instant.now().minus(GRACE_SECONDS, ChronoUnit.SECONDS);
        int reaped = 0;
        for (UUID tenantId : callSessionRepository.listActiveTenantIds()) {
            List<UUID> open = callSessionRepository.listOpenSvSessionsOlderThan(tenantId, cutoff);
            for (UUID sid : open) {
                if (sid == null) {
                    continue;
                }
                if (callSessionManager.getSession(sid.toString()).isPresent()) {
                    continue;
                }
                try {
                    callLifecycleService.onEnd(sid, "STALE_HANGUP");
                    reaped++;
                    log.info("live_call_ghost_reaped svSessionUuid={} tenantId={}", sid, tenantId);
                } catch (RuntimeException ex) {
                    log.debug("live_call_ghost_reap_failed sid={} cause={}", sid, ex.toString());
                }
            }
        }
        if (reaped > 0) {
            log.info("live_call_ghost_reaper_done reaped={}", reaped);
        }
    }
}

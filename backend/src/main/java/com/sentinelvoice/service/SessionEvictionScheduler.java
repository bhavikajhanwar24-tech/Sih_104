package com.sentinelvoice.service;

import com.sentinelvoice.config.SentinelProperties;
import com.sentinelvoice.telephony.CallLifecycleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class SessionEvictionScheduler {

    private static final Logger log = LoggerFactory.getLogger(SessionEvictionScheduler.class);

    private final CallSessionManager callSessionManager;
    private final SentinelProperties properties;
    private final CallLifecycleService callLifecycleService;

    public SessionEvictionScheduler(
            CallSessionManager callSessionManager,
            SentinelProperties properties,
            @Lazy CallLifecycleService callLifecycleService
    ) {
        this.callSessionManager = callSessionManager;
        this.properties = properties;
        this.callLifecycleService = callLifecycleService;
    }

    @Scheduled(fixedDelay = 60_000)
    public void evictIdleSessions() {
        int ttlMinutes = properties.session().ttlMinutes();
        for (String sessionId : callSessionManager.evictIdleSessions()) {
            log.info("evicted sessionId={} reason=idle_ttl_exceeded ttlMinutes={}", sessionId, ttlMinutes);
            try {
                callLifecycleService.onEnd(UUID.fromString(sessionId), "IDLE_TTL");
            } catch (IllegalArgumentException ignored) {
                // non-UUID scenario sessions — memory already closed
            } catch (RuntimeException ex) {
                log.debug("idle_ttl_telephony_finalize_failed sessionId={} cause={}", sessionId, ex.toString());
            }
        }
    }
}

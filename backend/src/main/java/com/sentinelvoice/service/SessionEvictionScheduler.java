package com.sentinelvoice.service;

import com.sentinelvoice.config.SentinelProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class SessionEvictionScheduler {

    private static final Logger log = LoggerFactory.getLogger(SessionEvictionScheduler.class);

    private final CallSessionManager callSessionManager;
    private final SentinelProperties properties;

    public SessionEvictionScheduler(CallSessionManager callSessionManager, SentinelProperties properties) {
        this.callSessionManager = callSessionManager;
        this.properties = properties;
    }

    @Scheduled(fixedDelay = 60_000)
    public void evictIdleSessions() {
        int ttlMinutes = properties.session().ttlMinutes();
        for (String sessionId : callSessionManager.evictIdleSessions()) {
            log.info("evicted sessionId={} reason=idle_ttl_exceeded ttlMinutes={}", sessionId, ttlMinutes);
        }
    }
}

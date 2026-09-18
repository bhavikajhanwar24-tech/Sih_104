package com.sentinelvoice.actuation;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Maps Decision Plane {@code sessionId} → Asterisk channel id for ARI actuation.
 * Populated by the AudioSocket bridge ({@code POST /api/v1/actuation/channel-map}).
 */
@Component
public class SessionChannelRegistry {

    private final ConcurrentMap<String, String> sessionToChannel = new ConcurrentHashMap<>();

    public void register(String sessionId, String channelId) {
        if (sessionId == null || sessionId.isBlank() || channelId == null || channelId.isBlank()) {
            return;
        }
        sessionToChannel.put(sessionId, channelId);
    }

    public Optional<String> findChannelId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(sessionToChannel.get(sessionId));
    }

    public void remove(String sessionId) {
        if (sessionId != null) {
            sessionToChannel.remove(sessionId);
        }
    }
}

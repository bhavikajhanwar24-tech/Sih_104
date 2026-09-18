package com.sentinelvoice.controller;

import com.sentinelvoice.actuation.SessionChannelRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Bridge registers Asterisk channel ids so ARI actuation can resolve sessionId → channelId.
 */
@RestController
@RequestMapping("/api/v1/actuation")
public class ActuationChannelController {

    private final SessionChannelRegistry channelRegistry;

    public ActuationChannelController(SessionChannelRegistry channelRegistry) {
        this.channelRegistry = channelRegistry;
    }

    /**
     * Body: {@code {"sessionId":"...","channelId":"..."}}.
     */
    @PostMapping("/channel-map")
    public ResponseEntity<Map<String, Object>> registerChannel(@RequestBody Map<String, String> body) {
        String sessionId = body == null ? null : body.get("sessionId");
        String channelId = body == null ? null : body.get("channelId");
        if (sessionId == null || sessionId.isBlank() || channelId == null || channelId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "ok", false,
                    "error", "sessionId and channelId are required"
            ));
        }
        channelRegistry.register(sessionId, channelId);
        return ResponseEntity.ok(Map.of(
                "ok", true,
                "sessionId", sessionId,
                "channelId", channelId
        ));
    }
}

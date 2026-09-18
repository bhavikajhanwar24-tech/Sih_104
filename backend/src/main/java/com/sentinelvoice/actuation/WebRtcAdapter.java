package com.sentinelvoice.actuation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Partial call-control via STOMP to the analyst / agent UI ({@code /topic/actuation/{sessionId}}).
 */
public class WebRtcAdapter implements CallControlPort {

    private static final Logger log = LoggerFactory.getLogger(WebRtcAdapter.class);
    private static final String TOPIC_PREFIX = "/topic/actuation/";

    private static final Set<ActuationAction> CAPS = EnumSet.of(
            ActuationAction.UI_BANNER,
            ActuationAction.TXN_APPROVE_LOCKED,
            ActuationAction.OOB_MFA_SENT,
            ActuationAction.CALL_HELD,
            ActuationAction.ANNOUNCE_HOLD,
            ActuationAction.WHISPER_WARNING,
            ActuationAction.CALL_TERMINATED,
            ActuationAction.HOLD,
            ActuationAction.UNHOLD,
            ActuationAction.WHISPER,
            ActuationAction.ANNOUNCE,
            ActuationAction.TERMINATE
    );

    private final SimpMessagingTemplate messagingTemplate;

    public WebRtcAdapter(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @Override
    public ActuationResult hold(String sessionId) {
        return publish(sessionId, "HOLD", Map.of());
    }

    @Override
    public ActuationResult unhold(String sessionId) {
        return publish(sessionId, "UNHOLD", Map.of());
    }

    @Override
    public ActuationResult whisperToAgent(String sessionId, String soundId) {
        return publish(sessionId, "WHISPER", Map.of("soundId", soundId == null ? "" : soundId));
    }

    @Override
    public ActuationResult bridgeSupervisor(String sessionId, String supervisorEndpoint) {
        log.info("webrtc_actuation bridgeSupervisor unsupported sessionId={}", sessionId);
        return ActuationResult.unsupported("webrtc cannot bridge supervisor SIP endpoint");
    }

    @Override
    public ActuationResult announce(String sessionId, String soundId) {
        return publish(sessionId, "ANNOUNCE", Map.of("soundId", soundId == null ? "" : soundId));
    }

    @Override
    public ActuationResult terminate(String sessionId, String reason) {
        return publish(sessionId, "TERMINATE", Map.of("reason", reason == null ? "" : reason));
    }

    @Override
    public Set<ActuationAction> capabilities() {
        return EnumSet.copyOf(CAPS);
    }

    @Override
    public String adapterName() {
        return "webrtc";
    }

    private ActuationResult publish(String sessionId, String action, Map<String, Object> extra) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("action", action);
            body.put("sessionId", sessionId);
            body.putAll(extra);
            messagingTemplate.convertAndSend(TOPIC_PREFIX + sessionId, body);
            log.info("webrtc_actuation action={} sessionId={}", action, sessionId);
            return ActuationResult.success("stomp-" + action);
        } catch (Exception ex) {
            log.warn("webrtc_actuation_failed action={} sessionId={} cause={}", action, sessionId, ex.toString());
            return ActuationResult.failure(ex.getMessage());
        }
    }
}

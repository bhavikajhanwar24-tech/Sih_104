package com.sentinelvoice.actuation;

import com.sentinelvoice.model.CallSession;
import com.sentinelvoice.service.CallSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Partial actuation: signals the analyst / agent browser over STOMP.
 * Hold / terminate / whisper are UI-side; no Asterisk channel control.
 */
public class WebRtcAdapter implements CallControlPort {

    private static final Logger log = LoggerFactory.getLogger(WebRtcAdapter.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final CallSessionManager callSessionManager;

    public WebRtcAdapter(SimpMessagingTemplate messagingTemplate, CallSessionManager callSessionManager) {
        this.messagingTemplate = messagingTemplate;
        this.callSessionManager = callSessionManager;
    }

    @Override
    public void hold(String sessionId) {
        publish(sessionId, "HOLD", Map.of("muted", true, "holdTone", true));
    }

    @Override
    public void unhold(String sessionId) {
        publish(sessionId, "UNHOLD", Map.of("muted", false));
    }

    @Override
    public void whisperToAgent(String sessionId, String soundId) {
        publish(sessionId, "WHISPER", Map.of("soundId", soundId, "target", "agent"));
    }

    @Override
    public void bridgeSupervisor(String sessionId, String supervisorEndpoint) {
        publish(sessionId, "BRIDGE_SUPERVISOR", Map.of("endpoint", supervisorEndpoint));
    }

    @Override
    public void announce(String sessionId, String soundId) {
        publish(sessionId, "ANNOUNCE", Map.of("soundId", soundId, "target", "caller"));
    }

    @Override
    public void terminate(String sessionId, String reason) {
        publish(sessionId, "TERMINATE", Map.of("reason", reason == null ? "policy" : reason));
    }

    @Override
    public Set<String> capabilities() {
        return Set.of("HOLD", "UNHOLD", "WHISPER", "ANNOUNCE", "BRIDGE_SUPERVISOR", "TERMINATE");
    }

    @Override
    public String adapterName() {
        return "webrtc";
    }

    private void publish(String sessionId, String action, Map<String, Object> detail) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema", "sentinelvoice.ActuationSignal/1");
        body.put("sessionId", sessionId);
        body.put("action", action);
        body.put("adapter", adapterName());
        body.putAll(detail);
        CallSession session = callSessionManager.getSession(sessionId).orElse(null);
        String dest = session == null
                ? "/topic/actuation/" + sessionId
                : "/topic/tenant/" + session.getTenantId() + "/actuation/" + sessionId;
        messagingTemplate.convertAndSend(dest, body);
        log.info("webrtc_actuation action={} sessionId={} dest={}", action, sessionId, dest);
    }
}

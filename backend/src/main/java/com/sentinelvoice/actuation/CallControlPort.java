package com.sentinelvoice.actuation;

import java.util.Set;

/**
 * Telephony call-control port. Implementations: Asterisk ARI, WebRTC STOMP, or no-op.
 */
public interface CallControlPort {

    ActuationResult hold(String sessionId);

    ActuationResult unhold(String sessionId);

    ActuationResult whisperToAgent(String sessionId, String soundId);

    ActuationResult bridgeSupervisor(String sessionId, String supervisorEndpoint);

    ActuationResult announce(String sessionId, String soundId);

    ActuationResult terminate(String sessionId, String reason);

    Set<ActuationAction> capabilities();

    /** Adapter name recorded in audit payloads ({@code asterisk}|{@code webrtc}|{@code noop}). */
    String adapterName();
}

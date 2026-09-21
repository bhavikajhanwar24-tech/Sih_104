package com.sentinelvoice.actuation;

import java.util.Set;

/**
 * Telephony actuation port — Asterisk ARI, WebRTC signalling, or noop.
 * Implementations must never throw into the fusion pipeline; callers treat failures as audit results.
 * Capability strings align with catalogue needs: HOLD, UNHOLD, WHISPER, ANNOUNCE, BRIDGE_SUPERVISOR, TERMINATE.
 */
public interface CallControlPort {

    void hold(String sessionId);

    void unhold(String sessionId);

    void whisperToAgent(String sessionId, String soundId);

    void bridgeSupervisor(String sessionId, String supervisorEndpoint);

    void announce(String sessionId, String soundId);

    void terminate(String sessionId, String reason);

    Set<String> capabilities();

    /** Adapter name recorded in INTERVENTION_ACTION_FIRED audit blocks. */
    String adapterName();
}

package com.sentinelvoice.actuation;

import java.util.Set;

/**
 * Telephony actuation port — Asterisk ARI, WebRTC signalling, or noop (Context §11.6).
 * Implementations must never throw into the fusion pipeline; callers treat failures as audit results.
 */
public interface CallControlPort {

    void hold(String sessionId);

    void unhold(String sessionId);

    void whisperToAgent(String sessionId, String soundId);

    void bridgeSupervisor(String sessionId, String supervisorEndpoint);

    void announce(String sessionId, String soundId);

    void terminate(String sessionId, String reason);

    Set<ActuationAction> capabilities();

    /** Adapter name recorded in INTERVENTION_ACTION_FIRED audit blocks. */
    String adapterName();
}

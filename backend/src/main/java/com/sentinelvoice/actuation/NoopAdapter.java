package com.sentinelvoice.actuation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.Set;

/**
 * Logs actuation only — used in tests and when no telephony plane is present.
 */
public class NoopAdapter implements CallControlPort {

    private static final Logger log = LoggerFactory.getLogger(NoopAdapter.class);

    @Override
    public void hold(String sessionId) {
        log.info("noop_actuation action=HOLD sessionId={}", sessionId);
    }

    @Override
    public void unhold(String sessionId) {
        log.info("noop_actuation action=UNHOLD sessionId={}", sessionId);
    }

    @Override
    public void whisperToAgent(String sessionId, String soundId) {
        log.info("noop_actuation action=WHISPER sessionId={} soundId={}", sessionId, soundId);
    }

    @Override
    public void bridgeSupervisor(String sessionId, String supervisorEndpoint) {
        log.info(
                "noop_actuation action=BRIDGE_SUPERVISOR sessionId={} endpoint={}",
                sessionId,
                supervisorEndpoint
        );
    }

    @Override
    public void announce(String sessionId, String soundId) {
        log.info("noop_actuation action=ANNOUNCE sessionId={} soundId={}", sessionId, soundId);
    }

    @Override
    public void terminate(String sessionId, String reason) {
        log.info("noop_actuation action=TERMINATE sessionId={} reason={}", sessionId, reason);
    }

    @Override
    public Set<ActuationAction> capabilities() {
        // Declare full capability set so ActuationService exercises every path in noop mode.
        return EnumSet.of(
                ActuationAction.HOLD,
                ActuationAction.UNHOLD,
                ActuationAction.WHISPER,
                ActuationAction.ANNOUNCE,
                ActuationAction.BRIDGE_SUPERVISOR,
                ActuationAction.TERMINATE
        );
    }

    @Override
    public String adapterName() {
        return "noop";
    }
}

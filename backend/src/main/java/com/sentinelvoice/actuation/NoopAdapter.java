package com.sentinelvoice.actuation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    public Set<String> capabilities() {
        // Full capability set so PlanRunner exercises every telephony path in noop mode.
        return Set.of("HOLD", "UNHOLD", "WHISPER", "ANNOUNCE", "BRIDGE_SUPERVISOR", "TERMINATE");
    }

    @Override
    public String adapterName() {
        return "noop";
    }
}

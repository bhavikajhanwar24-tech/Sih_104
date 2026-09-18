package com.sentinelvoice.actuation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.Set;

/**
 * Logs-only adapter with a full capability set that succeeds as no-ops.
 * Default for tests and when no telephony is present.
 */
public class NoopAdapter implements CallControlPort {

    private static final Logger log = LoggerFactory.getLogger(NoopAdapter.class);

    private static final Set<ActuationAction> ALL = EnumSet.allOf(ActuationAction.class);

    @Override
    public ActuationResult hold(String sessionId) {
        log.info("noop_actuation action=hold sessionId={}", sessionId);
        return ActuationResult.success("noop-hold");
    }

    @Override
    public ActuationResult unhold(String sessionId) {
        log.info("noop_actuation action=unhold sessionId={}", sessionId);
        return ActuationResult.success("noop-unhold");
    }

    @Override
    public ActuationResult whisperToAgent(String sessionId, String soundId) {
        log.info("noop_actuation action=whisper sessionId={} soundId={}", sessionId, soundId);
        return ActuationResult.success("noop-whisper");
    }

    @Override
    public ActuationResult bridgeSupervisor(String sessionId, String supervisorEndpoint) {
        log.info(
                "noop_actuation action=bridgeSupervisor sessionId={} endpoint={}",
                sessionId,
                supervisorEndpoint
        );
        return ActuationResult.success("noop-bridge-supervisor");
    }

    @Override
    public ActuationResult announce(String sessionId, String soundId) {
        log.info("noop_actuation action=announce sessionId={} soundId={}", sessionId, soundId);
        return ActuationResult.success("noop-announce");
    }

    @Override
    public ActuationResult terminate(String sessionId, String reason) {
        log.info("noop_actuation action=terminate sessionId={} reason={}", sessionId, reason);
        return ActuationResult.success("noop-terminate");
    }

    @Override
    public Set<ActuationAction> capabilities() {
        return EnumSet.copyOf(ALL);
    }

    @Override
    public String adapterName() {
        return "noop";
    }
}

package com.sentinelvoice.scenario;

import com.sentinelvoice.model.Ask;
import com.sentinelvoice.model.FeatureFrame;
import com.sentinelvoice.model.LinguisticFamily;
import com.sentinelvoice.scenario.model.Scenario;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-session scenario context seeds (asks / linguistic priors) applied when ASR has not
 * yet produced a linguistic block — same idea as seeded cross-channel BEC events.
 */
@Service
public class ScenarioSessionContext {

    public record LinguisticSeed(
            Ask ask,
            double urgency,
            double secrecy,
            double authorityInvocation,
            String claimedIdentity,
            String claimedRole,
            String language
    ) {
    }

    private final ConcurrentHashMap<String, LinguisticSeed> bySession = new ConcurrentHashMap<>();

    public void bind(String sessionId, Scenario.TransactionSeed seed, Scenario scenario) {
        if (sessionId == null || sessionId.isBlank() || seed == null || seed.amountInr() == null) {
            return;
        }
        Ask ask = new Ask(
                seed.type() != null ? seed.type() : "wire_transfer",
                seed.amountInr(),
                seed.currency() != null ? seed.currency() : "INR",
                seed.beneficiaryHint(),
                seed.deadline() != null ? seed.deadline() : "immediate"
        );
        String identity = scenario.caller() != null ? scenario.caller().claimedIdentity() : null;
        String role = scenario.caller() != null ? scenario.caller().claimedRole() : null;
        bySession.put(
                sessionId,
                new LinguisticSeed(
                        ask,
                        seed.urgency() != null ? seed.urgency() : 0.92,
                        seed.secrecy() != null ? seed.secrecy() : 0.88,
                        seed.authorityInvocation() != null ? seed.authorityInvocation() : 0.9,
                        identity,
                        role,
                        seed.language() != null ? seed.language() : "en"
                )
        );
    }

    public void clear(String sessionId) {
        if (sessionId != null) {
            bySession.remove(sessionId);
        }
    }

    public FeatureFrame enrich(FeatureFrame frame) {
        if (frame == null || frame.sessionId() == null) {
            return frame;
        }
        LinguisticSeed seed = bySession.get(frame.sessionId());
        if (seed == null) {
            return frame;
        }
        LinguisticFamily ling = frame.linguistic();
        boolean hasAsk = ling != null && ling.available() && ling.ask() != null && ling.ask().amount() != null;
        if (hasAsk) {
            return frame;
        }
        LinguisticFamily seeded = new LinguisticFamily(
                true,
                0L,
                seed.language(),
                seed.urgency(),
                seed.secrecy(),
                seed.authorityInvocation(),
                0.55,
                true,
                seed.ask(),
                seed.claimedIdentity(),
                seed.claimedRole(),
                "[scenario-seeded ask — awaiting ASR]",
                null
        );
        return new FeatureFrame(
                frame.schema(),
                frame.sessionId(),
                frame.seq(),
                frame.windowStartMs(),
                frame.windowEndMs(),
                frame.channelProfile(),
                frame.speechPresent(),
                frame.cumulativeSpeechMs(),
                frame.voice(),
                frame.channel(),
                frame.prosody(),
                frame.speaker(),
                frame.watermark(),
                seeded,
                frame.latencyMs()
        );
    }

    public Map<String, Object> debug(String sessionId) {
        LinguisticSeed seed = bySession.get(sessionId);
        if (seed == null) {
            return Map.of("bound", false);
        }
        return Map.of(
                "bound", true,
                "amount", seed.ask().amount() != null ? seed.ask().amount() : 0,
                "urgency", seed.urgency(),
                "secrecy", seed.secrecy()
        );
    }
}

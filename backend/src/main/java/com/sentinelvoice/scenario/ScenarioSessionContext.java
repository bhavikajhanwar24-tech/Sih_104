package com.sentinelvoice.scenario;

import com.sentinelvoice.model.FeatureFrame;
import com.sentinelvoice.scenario.model.Scenario;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * F18 — session metadata for lab runs. Does <strong>not</strong> inject fake FeatureFrames
 * into the live pipeline (v1 ScenarioTrajectoryRunner removed).
 */
@Service
public class ScenarioSessionContext {

    private final ConcurrentHashMap<String, Map<String, Object>> bySession = new ConcurrentHashMap<>();

    public void bind(String sessionId, Scenario.TransactionSeed seed, Scenario scenario) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("scenarioId", scenario == null ? null : scenario.id());
        if (seed != null) {
            meta.put("amountInr", seed.amountInr());
            meta.put("currency", seed.currency());
            meta.put("beneficiaryHint", seed.beneficiaryHint());
            meta.put("askType", seed.askType() != null ? seed.askType() : seed.type());
            meta.put("urgency", seed.urgency());
            meta.put("secrecy", seed.secrecy());
        }
        if (scenario != null && scenario.caller() != null) {
            meta.put("claimedIdentity", scenario.caller().claimedIdentity());
            meta.put("claimedRole", scenario.caller().claimedRole());
        }
        bySession.put(sessionId, Map.copyOf(meta));
    }

    public void clear(String sessionId) {
        if (sessionId != null) {
            bySession.remove(sessionId);
        }
    }

    /** Pass-through — real WAV / SIP audio must drive FeatureFrames. */
    public FeatureFrame enrich(FeatureFrame frame) {
        return frame;
    }

    public Map<String, Object> debug(String sessionId) {
        Map<String, Object> seed = bySession.get(sessionId);
        if (seed == null) {
            return Map.of("bound", false);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bound", true);
        out.putAll(seed);
        return out;
    }
}

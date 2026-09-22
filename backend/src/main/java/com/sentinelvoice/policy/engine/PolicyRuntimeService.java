package com.sentinelvoice.policy.engine;

import com.sentinelvoice.context.model.RelationshipAssessment;
import com.sentinelvoice.context.model.TransactionAssessment;
import com.sentinelvoice.directory.DirectoryMatch;
import com.sentinelvoice.model.CallSession;
import com.sentinelvoice.model.FeatureFrame;
import com.sentinelvoice.policy.dsl.Condition;
import com.sentinelvoice.policy.sets.PolicySetRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Facade used by ingest + APIs: assemble facts, evaluate ACTIVE (or simulated) rules (F7).
 */
@Service
@EnableConfigurationProperties(PolicyEngineProperties.class)
public class PolicyRuntimeService {

    private final FactAssembler factAssembler;
    private final RuleEngine ruleEngine;
    private final ActivePolicyCache activePolicyCache;
    private final PolicySetRepository policySetRepository;

    public PolicyRuntimeService(
            FactAssembler factAssembler,
            RuleEngine ruleEngine,
            ActivePolicyCache activePolicyCache,
            PolicySetRepository policySetRepository
    ) {
        this.factAssembler = factAssembler;
        this.ruleEngine = ruleEngine;
        this.activePolicyCache = activePolicyCache;
        this.policySetRepository = policySetRepository;
    }

    public RuleEvaluation evaluateLive(
            UUID tenantId,
            CallSession session,
            FeatureFrame frame,
            DirectoryMatch directoryMatch,
            RelationshipAssessment relationship
    ) {
        FactSet facts = factAssembler.assemble(tenantId, session, frame, directoryMatch, relationship);
        return ruleEngine.evaluate(tenantId, facts, false);
    }

    public RuleEvaluation simulate(UUID tenantId, UUID policySetIdOrNull, Map<String, Object> rawFacts) {
        FactSet facts = factAssembler.fromSimulationMap(rawFacts);
        if (policySetIdOrNull == null) {
            return ruleEngine.evaluate(tenantId, facts, true);
        }
        CompiledPolicy compiled = compileSet(tenantId, policySetIdOrNull);
        return ruleEngine.evaluate(tenantId, facts, true, compiled);
    }

    public Map<String, Object> engineStatus(UUID tenantId) {
        Map<String, Object> body = new LinkedHashMap<>();
        Optional<CompiledPolicy> policy = activePolicyCache.get(tenantId);
        Optional<Map<String, Object>> dbActive = policySetRepository.findActiveSet(tenantId);

        Integer dbVersion = null;
        String dbSha = null;
        int dbRuleCount = 0;
        if (dbActive.isPresent()) {
            Map<String, Object> set = dbActive.get();
            dbVersion = ((Number) set.get("version")).intValue();
            UUID setId = UUID.fromString(String.valueOf(set.get("id")));
            Object storedSha = set.get("contentSha256");
            if (storedSha != null && !String.valueOf(storedSha).isBlank()) {
                dbSha = String.valueOf(storedSha);
            } else {
                dbSha = policySetRepository.computeContentSha(tenantId, setId);
            }
            dbRuleCount = policySetRepository.countRuntimeRules(tenantId, setId);
        }

        if (policy.isEmpty()) {
            body.put("schemaVersion", "2");
            body.put("state", RuleEvaluation.STATE_NO_POLICY);
            body.put("activePolicyVersion", null);
            body.put("ruleCount", 0);
            body.put("cacheLoadedAt", null);
            body.put("policySha", null);
            body.put("avgEvalMicros", ruleEngine.avgEvalMicros());
            body.put("lastEvalMicros", ruleEngine.lastEvalMicros());
            body.put("databaseVersion", dbVersion);
            body.put("databaseSha", dbSha);
            body.put("databaseRuleCount", dbRuleCount);
            body.put("engineVersion", null);
            body.put("engineSha", null);
            boolean inSync = dbActive.isEmpty();
            body.put("inSync", inSync);
            body.put("syncState", inSync ? "IN_SYNC" : "MISMATCH");
            return body;
        }
        CompiledPolicy p = policy.get();
        body.put("schemaVersion", "2");
        body.put("state", RuleEvaluation.STATE_OK);
        body.put("activePolicyVersion", p.version());
        body.put("ruleCount", p.ruleCount());
        body.put("cacheLoadedAt", p.loadedAt().toString());
        body.put("policySha", p.contentSha());
        body.put("avgEvalMicros", ruleEngine.avgEvalMicros());
        body.put("lastEvalMicros", ruleEngine.lastEvalMicros());
        body.put("databaseVersion", dbVersion);
        body.put("databaseSha", dbSha);
        body.put("databaseRuleCount", dbRuleCount);
        body.put("engineVersion", p.version());
        body.put("engineSha", p.contentSha());
        boolean versionMatch = dbVersion != null && dbVersion.equals(p.version());
        boolean shaMatch = dbSha != null && dbSha.equals(p.contentSha());
        boolean countMatch = dbRuleCount == p.ruleCount();
        boolean inSync = versionMatch && shaMatch && countMatch;
        body.put("inSync", inSync);
        body.put("syncState", inSync ? "IN_SYNC" : "MISMATCH");
        return body;
    }

    /**
     * Maps a rule evaluation onto the TRANSACTION fusion family score + metadata.
     * NO_POLICY → explicit low-confidence neutral (not "safe silence").
     */
    public TransactionAssessment toTransactionAssessment(RuleEvaluation eval) {
        if (eval == null || RuleEvaluation.STATE_NO_POLICY.equals(eval.state())) {
            return new TransactionAssessment(
                    0.0,
                    List.of("NO_POLICY"),
                    false,
                    true,
                    false,
                    0,
                    null,
                    null
            );
        }
        List<String> codes = new ArrayList<>();
        for (RuleEvaluation.FiredRule f : eval.firedRules()) {
            if (f.reasonCode() != null) {
                codes.add(f.reasonCode());
            }
        }
        if (codes.isEmpty()) {
            codes = List.of("POLICY_NONE_FIRED");
        }
        boolean violation = eval.minLevel() >= 2 || !eval.firedRules().isEmpty();
        return new TransactionAssessment(
                eval.policyScore(),
                codes,
                violation,
                true,
                false,
                0,
                null,
                null
        );
    }

    private CompiledPolicy compileSet(UUID tenantId, UUID setId) {
        Map<String, Object> set = policySetRepository.findSet(tenantId, setId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown policy set"));
        int version = ((Number) set.get("version")).intValue();
        String sha = set.get("contentSha256") == null ? null : String.valueOf(set.get("contentSha256"));
        List<Map<String, Object>> rules = policySetRepository.listRuntimeRules(tenantId, setId);
        List<CompiledPolicy.CompiledRule> compiled = new ArrayList<>();
        Map<String, List<CompiledPolicy.CompiledRule>> byAction = new LinkedHashMap<>();
        for (Map<String, Object> row : rules) {
            try {
                CompiledPolicy.CompiledRule cr = compileOne(row);
                compiled.add(cr);
                String key = cr.actionType() == null ? "*" : cr.actionType();
                byAction.computeIfAbsent(key, k -> new ArrayList<>()).add(cr);
            } catch (Exception ignored) {
                // skip uncompilable
            }
        }
        return new CompiledPolicy(version, sha, Instant.now(), compiled, byAction);
    }

    @SuppressWarnings("unchecked")
    private static CompiledPolicy.CompiledRule compileOne(Map<String, Object> row) {
        String ruleId = String.valueOf(row.getOrDefault("ruleId", "unknown"));
        String title = row.get("title") == null ? ruleId : String.valueOf(row.get("title"));
        String severity = row.get("severity") == null ? "MEDIUM" : String.valueOf(row.get("severity"));
        Map<String, Object> when = row.get("when") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Map<String, Object> then = row.get("then") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Map<String, Object> applies = row.get("appliesTo") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Map<String, Object> source = row.get("source") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        Condition condition = Condition.fromMap(when);
        int minLevel = then.get("minLevel") instanceof Number n ? n.intValue() : 0;
        double boost = then.get("scoreBoost") instanceof Number n ? n.doubleValue() : 0.0;
        String reason = then.get("reasonCode") == null ? "POLICY_GENERIC" : String.valueOf(then.get("reasonCode"));
        String actionType = applies.get("actionType") == null ? null : String.valueOf(applies.get("actionType"));
        return new CompiledPolicy.CompiledRule(
                ruleId, title, severity, condition, minLevel, boost, reason, source, actionType,
                List.copyOf(ConditionEvaluator.referencedFacts(condition))
        );
    }
}

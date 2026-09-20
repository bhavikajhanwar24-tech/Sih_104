package com.sentinelvoice.policy.engine;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.sentinelvoice.policy.compile.PolicySetActivatedEvent;
import com.sentinelvoice.policy.dsl.Condition;
import com.sentinelvoice.policy.sets.PolicySetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-tenant Caffeine cache of immutable compiled ACTIVE policy sets (F7).
 * Invalidated on {@link PolicySetActivatedEvent}; TTL is a safety refresh only.
 */
@Component
public class ActivePolicyCache {

    private static final Logger log = LoggerFactory.getLogger(ActivePolicyCache.class);

    private final PolicySetRepository repository;
    private final LoadingCache<UUID, Optional<CompiledPolicy>> cache;

    public ActivePolicyCache(PolicySetRepository repository, PolicyEngineProperties props) {
        this.repository = repository;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(props.cacheTtlSeconds()))
                .maximumSize(2_000)
                .build(this::loadActive);
    }

    public Optional<CompiledPolicy> get(UUID tenantId) {
        if (tenantId == null) {
            return Optional.empty();
        }
        return cache.get(tenantId);
    }

    public void invalidate(UUID tenantId) {
        if (tenantId != null) {
            cache.invalidate(tenantId);
        }
    }

    @EventListener
    public void onActivated(PolicySetActivatedEvent event) {
        log.info("policy_cache_invalidate tenantId={} version={}", event.tenantId(), event.version());
        invalidate(event.tenantId());
        // Warm immediately so first live frame does not pay compile cost
        cache.get(event.tenantId());
    }

    private Optional<CompiledPolicy> loadActive(UUID tenantId) {
        Optional<Map<String, Object>> active = repository.findActiveSet(tenantId);
        if (active.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> set = active.get();
        UUID setId = UUID.fromString(String.valueOf(set.get("id")));
        int version = ((Number) set.get("version")).intValue();
        String sha = set.get("contentSha256") == null ? null : String.valueOf(set.get("contentSha256"));

        List<Map<String, Object>> rules = repository.listRuntimeRules(tenantId, setId);
        List<CompiledPolicy.CompiledRule> compiled = new ArrayList<>();
        Map<String, List<CompiledPolicy.CompiledRule>> byAction = new LinkedHashMap<>();

        for (Map<String, Object> row : rules) {
            try {
                CompiledPolicy.CompiledRule cr = compileRule(row);
                compiled.add(cr);
                String key = cr.actionType() == null || cr.actionType().isBlank() ? "*" : cr.actionType();
                byAction.computeIfAbsent(key, k -> new ArrayList<>()).add(cr);
            } catch (Exception ex) {
                log.warn("skip_uncompilable_rule tenantId={} ruleId={} err={}",
                        tenantId, row.get("ruleId"), ex.toString());
            }
        }

        CompiledPolicy policy = new CompiledPolicy(version, sha, Instant.now(), compiled, byAction);
        log.info("policy_cache_loaded tenantId={} version={} rules={}", tenantId, version, compiled.size());
        return Optional.of(policy);
    }

    @SuppressWarnings("unchecked")
    private static CompiledPolicy.CompiledRule compileRule(Map<String, Object> row) {
        String ruleId = String.valueOf(row.getOrDefault("ruleId", "unknown"));
        String title = row.get("title") == null ? ruleId : String.valueOf(row.get("title"));
        String severity = row.get("severity") == null ? "MEDIUM" : String.valueOf(row.get("severity"));
        Map<String, Object> when = asMap(row.get("when"));
        Map<String, Object> then = asMap(row.get("then"));
        Map<String, Object> applies = asMap(row.get("appliesTo"));
        Map<String, Object> source = asMap(row.get("source"));

        Condition condition = Condition.fromMap(when);
        int minLevel = then.get("minLevel") instanceof Number n ? n.intValue() : 0;
        double boost = then.get("scoreBoost") instanceof Number n ? n.doubleValue() : 0.0;
        String reason = then.get("reasonCode") == null ? "POLICY_GENERIC" : String.valueOf(then.get("reasonCode"));
        String actionType = applies.get("actionType") == null ? null : String.valueOf(applies.get("actionType"));
        if (actionType != null && (actionType.isBlank() || "*".equals(actionType) || "ANY".equalsIgnoreCase(actionType))) {
            actionType = null;
        }

        return new CompiledPolicy.CompiledRule(
                ruleId,
                title,
                severity,
                condition,
                minLevel,
                Math.max(0.0, Math.min(1.0, boost)),
                reason,
                source,
                actionType,
                List.copyOf(ConditionEvaluator.referencedFacts(condition))
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}

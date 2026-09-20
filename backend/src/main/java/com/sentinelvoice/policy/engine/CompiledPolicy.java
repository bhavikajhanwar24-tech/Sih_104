package com.sentinelvoice.policy.engine;

import com.sentinelvoice.policy.dsl.Condition;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable pre-compiled ACTIVE policy set for one tenant (F7).
 */
public final class CompiledPolicy {

    public record CompiledRule(
            String ruleId,
            String title,
            String severity,
            Condition condition,
            int minLevel,
            double scoreBoost,
            String reasonCode,
            Map<String, Object> sourceRef,
            String actionType,
            List<String> referencedFacts
    ) {
    }

    private final Integer version;
    private final String contentSha;
    private final Instant loadedAt;
    private final List<CompiledRule> rules;
    private final Map<String, List<CompiledRule>> byActionType;

    public CompiledPolicy(
            Integer version,
            String contentSha,
            Instant loadedAt,
            List<CompiledRule> rules,
            Map<String, List<CompiledRule>> byActionType
    ) {
        this.version = version;
        this.contentSha = contentSha;
        this.loadedAt = loadedAt == null ? Instant.now() : loadedAt;
        this.rules = List.copyOf(rules == null ? List.of() : rules);
        Map<String, List<CompiledRule>> idx = new LinkedHashMap<>();
        if (byActionType != null) {
            byActionType.forEach((k, v) -> idx.put(k, List.copyOf(v)));
        }
        this.byActionType = Collections.unmodifiableMap(idx);
    }

    public Integer version() {
        return version;
    }

    public String contentSha() {
        return contentSha;
    }

    public Instant loadedAt() {
        return loadedAt;
    }

    public List<CompiledRule> rules() {
        return rules;
    }

    public int ruleCount() {
        return rules.size();
    }

    public Map<String, List<CompiledRule>> byActionType() {
        return byActionType;
    }

    /** Rules applicable to ask.type (or all if ask.type unknown / no appliesTo filter). */
    public List<CompiledRule> rulesForAction(String actionType) {
        if (actionType == null || actionType.isBlank()) {
            return rules;
        }
        List<CompiledRule> scoped = byActionType.get(actionType);
        List<CompiledRule> unscoped = byActionType.getOrDefault("*", List.of());
        if ((scoped == null || scoped.isEmpty()) && unscoped.isEmpty()) {
            return rules;
        }
        if (scoped == null || scoped.isEmpty()) {
            return unscoped;
        }
        if (unscoped.isEmpty()) {
            return scoped;
        }
        java.util.LinkedHashSet<CompiledRule> merged = new java.util.LinkedHashSet<>(scoped);
        merged.addAll(unscoped);
        return List.copyOf(merged);
    }
}

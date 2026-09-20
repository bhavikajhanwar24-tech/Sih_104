package com.sentinelvoice.policy.engine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable bag of facts for one rule evaluation (F7).
 */
public final class FactSet {

    private final Map<String, FactValue> facts;

    public FactSet(Map<String, FactValue> facts) {
        this.facts = Collections.unmodifiableMap(new LinkedHashMap<>(facts == null ? Map.of() : facts));
    }

    public static FactSet empty() {
        return new FactSet(Map.of());
    }

    public static Builder builder() {
        return new Builder();
    }

    public Optional<FactValue> get(String path) {
        if (path == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(facts.get(path));
    }

    public boolean isPresent(String path) {
        return facts.containsKey(path) && facts.get(path).value() != null;
    }

    public Object raw(String path) {
        FactValue fv = facts.get(path);
        return fv == null ? null : fv.value();
    }

    public Map<String, FactValue> asMap() {
        return facts;
    }

    public Set<String> paths() {
        return facts.keySet();
    }

    public static final class Builder {
        private final Map<String, FactValue> map = new LinkedHashMap<>();

        public Builder put(String path, FactValue value) {
            if (path != null && value != null && value.value() != null) {
                map.put(path, value);
            }
            return this;
        }

        public Builder put(String path, Object value, String source) {
            if (path != null && value != null) {
                map.put(path, FactValue.of(value, source));
            }
            return this;
        }

        public Builder putAll(Map<String, FactValue> other) {
            if (other != null) {
                other.forEach(this::put);
            }
            return this;
        }

        public FactSet build() {
            return new FactSet(map);
        }
    }

    /** Merge overlay on top of base (overlay wins). */
    public FactSet merge(FactSet overlay) {
        if (overlay == null || overlay.facts.isEmpty()) {
            return this;
        }
        Map<String, FactValue> merged = new LinkedHashMap<>(this.facts);
        merged.putAll(overlay.facts);
        return new FactSet(merged);
    }

    public Set<String> missingAmong(Set<String> required) {
        Set<String> missing = new LinkedHashSet<>();
        if (required == null) {
            return missing;
        }
        for (String p : required) {
            if (!isPresent(p)) {
                missing.add(p);
            }
        }
        return missing;
    }
}

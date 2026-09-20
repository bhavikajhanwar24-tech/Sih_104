package com.sentinelvoice.policy.engine;

import java.time.Instant;
import java.util.Objects;

/**
 * One observed fact with provenance metadata (F7).
 */
public record FactValue(
        Object value,
        String source,
        Double confidence,
        Instant observedAt,
        boolean assertedByCaller
) {
    public FactValue {
        Objects.requireNonNull(source, "source");
        observedAt = observedAt == null ? Instant.EPOCH : observedAt;
    }

    public static FactValue of(Object value, String source) {
        return new FactValue(value, source, null, Instant.now(), false);
    }

    public static FactValue of(Object value, String source, Double confidence, Instant observedAt, boolean assertedByCaller) {
        return new FactValue(value, source, confidence, observedAt, assertedByCaller);
    }
}

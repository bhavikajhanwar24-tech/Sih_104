package com.sentinelvoice.model;

import java.util.Map;

/**
 * One Decision Plane telemetry sample. Numbers and enums only — never audio, never verbatim transcript.
 */
public record TelemetryEntry(
        long seq,
        long tsMs,
        double instantaneousRisk,
        double smoothedRisk,
        InterventionLevel level,
        Map<String, Double> familyScores
) {
    public TelemetryEntry {
        familyScores = familyScores == null ? Map.of() : Map.copyOf(familyScores);
    }
}

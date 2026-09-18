package com.sentinelvoice.intervention;

import com.sentinelvoice.model.InterventionLevel;

/**
 * Active analyst pin of the intervention level. While active, automatic transitions are
 * suppressed (but still logged as suppressed intent).
 */
public record OverrideRecord(
        InterventionLevel pinnedLevel,
        String analystId,
        String reason,
        long pinnedAtMs,
        long expiresAtMs
) {
    public boolean isActive(long nowMs) {
        return nowMs < expiresAtMs;
    }
}

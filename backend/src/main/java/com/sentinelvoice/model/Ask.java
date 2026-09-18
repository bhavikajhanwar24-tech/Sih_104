package com.sentinelvoice.model;

/**
 * Frozen FeatureFrame.linguistic.ask. Context §8.1.
 */
public record Ask(
        String type,
        Double amount,
        String currency,
        String beneficiaryHint,
        String deadline
) {
}

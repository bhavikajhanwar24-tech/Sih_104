package com.sentinelvoice.model;

/**
 * Frozen FeatureFrame.linguistic.ask (F11).
 */
public record Ask(
        String type,
        Double amount,
        String currency,
        String beneficiaryHint,
        String deadline,
        Boolean sharesCredential,
        Boolean beneficiaryMentioned
) {
    /** Pre-F11 constructor. */
    public Ask(
            String type,
            Double amount,
            String currency,
            String beneficiaryHint,
            String deadline
    ) {
        this(type, amount, currency, beneficiaryHint, deadline, null, null);
    }
}

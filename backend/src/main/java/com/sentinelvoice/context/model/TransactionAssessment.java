package com.sentinelvoice.context.model;

import java.util.List;

/**
 * Transaction-policy evidence for the TRANSACTION fusion family (Context §10.6 / §12).
 */
public record TransactionAssessment(
        double score,
        List<String> reasonCodes,
        boolean policyViolation,
        boolean channelPermitted,
        boolean beneficiaryNovel,
        int velocityCountToday,
        Double amountInr,
        Double verbalAuthorityLimitInr
) {
}

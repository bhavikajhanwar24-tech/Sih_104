package com.sentinelvoice.model;

public record TransactionContext(
        String sessionId,
        String transactionType,
        double amount,
        String expectedApprover,
        boolean withinAuthority,
        String merchantOrAccount
) {
}

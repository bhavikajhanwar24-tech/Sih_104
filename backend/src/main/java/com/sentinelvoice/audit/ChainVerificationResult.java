package com.sentinelvoice.audit;

public record ChainVerificationResult(
        boolean valid,
        int blockCount,
        Integer brokenAtIndex,
        String expectedHash,
        String actualHash
) {
    public static ChainVerificationResult ok(int blockCount) {
        return new ChainVerificationResult(true, blockCount, null, null, null);
    }

    public static ChainVerificationResult empty() {
        return new ChainVerificationResult(false, 0, null, null, null);
    }

    public static ChainVerificationResult broken(
            int blockCount,
            int brokenAtIndex,
            String expectedHash,
            String actualHash
    ) {
        return new ChainVerificationResult(false, blockCount, brokenAtIndex, expectedHash, actualHash);
    }
}

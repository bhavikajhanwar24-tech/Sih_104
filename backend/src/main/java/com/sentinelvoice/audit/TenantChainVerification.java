package com.sentinelvoice.audit;

/**
 * V2 tenant-scoped chain verification result for {@code GET /api/v2/audit/verify}.
 */
public record TenantChainVerification(
        boolean valid,
        int blocksChecked,
        Long firstBrokenSeq
) {
    public static TenantChainVerification ok(int blocksChecked) {
        return new TenantChainVerification(true, blocksChecked, null);
    }

    public static TenantChainVerification empty() {
        return new TenantChainVerification(true, 0, null);
    }

    public static TenantChainVerification broken(int blocksChecked, long firstBrokenSeq) {
        return new TenantChainVerification(false, blocksChecked, firstBrokenSeq);
    }
}

package com.sentinelvoice.identity;

/**
 * Voice-passport verdicts from Context §12 stage [4].
 */
public enum IdentityVerdict {
    VERIFIED,
    INCONCLUSIVE,
    IMPERSONATION_HUMAN,
    IMPERSONATION_SYNTHETIC
}

package com.sentinelvoice.challenge.model;

/**
 * Challenge evaluation outcome. FAIL_* and TIMEOUT are treated as failures for fusion/actuation.
 */
public enum ChallengeVerdict {
    PASS,
    FAIL_LATENCY,
    FAIL_CONTENT,
    FAIL_ACOUSTIC,
    TIMEOUT;

    public boolean failed() {
        return this != PASS;
    }
}

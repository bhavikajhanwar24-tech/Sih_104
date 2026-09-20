package com.sentinelvoice.policy.engine;

/**
 * Three-valued boolean for missing facts (F7).
 * A predicate on an UNKNOWN fact is neither TRUE nor FALSE.
 */
public enum TriBool {
    TRUE,
    FALSE,
    UNKNOWN;

    public TriBool not() {
        return switch (this) {
            case TRUE -> FALSE;
            case FALSE -> TRUE;
            case UNKNOWN -> UNKNOWN;
        };
    }

    /** Kleene AND. */
    public TriBool and(TriBool other) {
        if (this == FALSE || other == FALSE) {
            return FALSE;
        }
        if (this == UNKNOWN || other == UNKNOWN) {
            return UNKNOWN;
        }
        return TRUE;
    }

    /** Kleene OR. */
    public TriBool or(TriBool other) {
        if (this == TRUE || other == TRUE) {
            return TRUE;
        }
        if (this == UNKNOWN || other == UNKNOWN) {
            return UNKNOWN;
        }
        return FALSE;
    }
}

package com.sentinelvoice.fusion;

/**
 * Typed explainability codes (F12). Human text lives in {@code reasons_*.properties} bundles —
 * not hard-coded in this enum.
 */
public enum ReasonCode {

    POLICY_RULE_FIRED(Severity.HIGH, EvidenceFamily.TRANSACTION),
    SYNTHETIC_VOICE(Severity.HIGH, EvidenceFamily.VOICE),
    SPEAKER_MISMATCH(Severity.CRITICAL, EvidenceFamily.VOICE),
    CHANNEL_INCONSISTENT(Severity.HIGH, EvidenceFamily.CHANNEL),
    NO_PRIOR_RELATIONSHIP(Severity.MEDIUM, EvidenceFamily.RELATIONSHIP),
    EMPLOYEE_ON_LEAVE(Severity.HIGH, EvidenceFamily.RELATIONSHIP),
    UNKNOWN_BENEFICIARY(Severity.HIGH, EvidenceFamily.TRANSACTION),
    SECRECY_REQUESTED(Severity.HIGH, EvidenceFamily.LINGUISTIC),
    URGENCY(Severity.MEDIUM, EvidenceFamily.LINGUISTIC),
    CREDENTIAL_REQUEST(Severity.CRITICAL, EvidenceFamily.LINGUISTIC),
    PROMPT_INJECTION_ATTEMPT(Severity.CRITICAL, EvidenceFamily.LINGUISTIC),
    INSUFFICIENT_EVIDENCE(Severity.INFO, EvidenceFamily.VOICE),
    LLM_UNAVAILABLE(Severity.INFO, EvidenceFamily.LINGUISTIC),

    // Retained specialised codes (still template-backed)
    CLI_CLAIM_MISMATCH(Severity.CRITICAL, EvidenceFamily.RELATIONSHIP),
    NO_BREATH(Severity.MEDIUM, EvidenceFamily.PROSODY),
    OVERSMOOTH_PROSODY(Severity.MEDIUM, EvidenceFamily.PROSODY),
    AUTHORITY_INVOCATION(Severity.HIGH, EvidenceFamily.LINGUISTIC),
    HIERARCHY_ANOMALY(Severity.MEDIUM, EvidenceFamily.RELATIONSHIP),
    PRESENCE_CONFLICT(Severity.HIGH, EvidenceFamily.RELATIONSHIP),
    CROSS_CHANNEL_PRECURSOR(Severity.HIGH, EvidenceFamily.RELATIONSHIP),
    VOICE_DRIFT(Severity.MEDIUM, EvidenceFamily.VOICE),
    CHALLENGE_LATENCY_FAIL(Severity.CRITICAL, EvidenceFamily.VOICE),
    CHALLENGE_CONTENT_FAIL(Severity.CRITICAL, EvidenceFamily.VOICE),
    CHALLENGE_ACOUSTIC_FAIL(Severity.CRITICAL, EvidenceFamily.VOICE),
    WATERMARK_DETECTED(Severity.INFO, EvidenceFamily.VOICE),

    /** @deprecated F12 — use {@link #SYNTHETIC_VOICE} */
    @Deprecated SYNTHETIC_ARTIFACTS(Severity.HIGH, EvidenceFamily.VOICE),
    /** @deprecated F12 — use {@link #SPEAKER_MISMATCH} */
    @Deprecated VOICEPRINT_FAIL(Severity.CRITICAL, EvidenceFamily.VOICE),
    /** @deprecated F12 — use {@link #CHANNEL_INCONSISTENT} */
    @Deprecated NO_ROOM_ACOUSTICS(Severity.HIGH, EvidenceFamily.CHANNEL),
    /** @deprecated F12 — use {@link #CHANNEL_INCONSISTENT} */
    @Deprecated DOUBLE_COMPRESSION(Severity.MEDIUM, EvidenceFamily.CHANNEL),
    /** @deprecated F12 — use {@link #SECRECY_REQUESTED} */
    @Deprecated SECRECY_DEMAND(Severity.HIGH, EvidenceFamily.LINGUISTIC),
    /** @deprecated F12 — use {@link #URGENCY} */
    @Deprecated URGENCY_PRESSURE(Severity.MEDIUM, EvidenceFamily.LINGUISTIC),
    /** @deprecated F12 — use {@link #POLICY_RULE_FIRED} */
    @Deprecated POLICY_VIOLATION(Severity.HIGH, EvidenceFamily.TRANSACTION),
    /** @deprecated F12 — use {@link #NO_PRIOR_RELATIONSHIP} */
    @Deprecated FIRST_CONTACT(Severity.MEDIUM, EvidenceFamily.RELATIONSHIP);

    public enum Severity {
        CRITICAL, HIGH, MEDIUM, INFO;

        public int rank() {
            return ordinal();
        }
    }

    private final Severity severity;
    private final EvidenceFamily family;

    ReasonCode(Severity severity, EvidenceFamily family) {
        this.severity = severity;
        this.family = family;
    }

    public Severity severity() {
        return severity;
    }

    public EvidenceFamily family() {
        return family;
    }

    /** Canonical F12 code for persistence / UI (maps deprecated aliases). */
    public ReasonCode canonical() {
        return switch (this) {
            case SYNTHETIC_ARTIFACTS -> SYNTHETIC_VOICE;
            case VOICEPRINT_FAIL -> SPEAKER_MISMATCH;
            case NO_ROOM_ACOUSTICS, DOUBLE_COMPRESSION -> CHANNEL_INCONSISTENT;
            case SECRECY_DEMAND -> SECRECY_REQUESTED;
            case URGENCY_PRESSURE -> URGENCY;
            case POLICY_VIOLATION -> POLICY_RULE_FIRED;
            case FIRST_CONTACT -> NO_PRIOR_RELATIONSHIP;
            default -> this;
        };
    }
}

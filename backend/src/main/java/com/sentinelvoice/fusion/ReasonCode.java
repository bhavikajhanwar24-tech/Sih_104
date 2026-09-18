package com.sentinelvoice.fusion;

/**
 * Explainability codes for TelemetryFrame.topReasons (Context §8.2).
 * Each entry carries severity, the radar-axis family, and a quantified plain-English template.
 */
public enum ReasonCode {

    CLI_CLAIM_MISMATCH(
            Severity.CRITICAL,
            EvidenceFamily.RELATIONSHIP,
            "Caller claims the role of {0}, but the calling line identity does not match that directory record (mismatch flag = 1)."
    ),
    VOICEPRINT_FAIL(
            Severity.CRITICAL,
            EvidenceFamily.VOICE,
            "Live voiceprint cosine similarity is {0} against the enrolled profile; a match requires at least {1}."
    ),
    SYNTHETIC_ARTIFACTS(
            Severity.HIGH,
            EvidenceFamily.VOICE,
            "Anti-spoof analysis scored synthetic-speech probability at {0}% with model confidence {1}%."
    ),
    NO_BREATH(
            Severity.MEDIUM,
            EvidenceFamily.PROSODY,
            "No breath sounds in {0} seconds of continuous speech (human baseline: {1}-{2} per minute)."
    ),
    OVERSMOOTH_PROSODY(
            Severity.MEDIUM,
            EvidenceFamily.PROSODY,
            "Measured pitch jitter is {0}%, which is below the human conversational range of 0.5-1.5%."
    ),
    NO_ROOM_ACOUSTICS(
            Severity.HIGH,
            EvidenceFamily.CHANNEL,
            "Room reverberation time is {0} ms, which is below the plausible room floor of about {1} ms and indicates missing room acoustics."
    ),
    DOUBLE_COMPRESSION(
            Severity.MEDIUM,
            EvidenceFamily.CHANNEL,
            "Double-compression score is {0} on a 0-1 scale, above the re-encoding threshold of {1}."
    ),
    SECRECY_DEMAND(
            Severity.HIGH,
            EvidenceFamily.LINGUISTIC,
            "Isolation or secrecy language scored {0} on a 0-1 scale (threshold {1})."
    ),
    URGENCY_PRESSURE(
            Severity.MEDIUM,
            EvidenceFamily.LINGUISTIC,
            "Urgency pressure language scored {0} on a 0-1 scale (threshold {1})."
    ),
    AUTHORITY_INVOCATION(
            Severity.HIGH,
            EvidenceFamily.LINGUISTIC,
            "Authority-invocation language scored {0} on a 0-1 scale (threshold {1})."
    ),
    POLICY_VIOLATION(
            Severity.HIGH,
            EvidenceFamily.TRANSACTION,
            "Requested transfer of {0} {1} exceeds the caller's verbal authority limit of {2} {1}."
    ),
    FIRST_CONTACT(
            Severity.MEDIUM,
            EvidenceFamily.RELATIONSHIP,
            "This appears to be a first contact: {0} prior interactions were recorded in the last 365 days."
    ),
    HIERARCHY_ANOMALY(
            Severity.MEDIUM,
            EvidenceFamily.RELATIONSHIP,
            "Organisational hierarchy distance between caller and recipient is {0} levels (anomaly threshold {1})."
    ),
    PRESENCE_CONFLICT(
            Severity.HIGH,
            EvidenceFamily.RELATIONSHIP,
            "Presence conflict: calendar expected {0}, but the call observed {1} (conflict flag = 1)."
    ),
    CROSS_CHANNEL_PRECURSOR(
            Severity.HIGH,
            EvidenceFamily.RELATIONSHIP,
            "Cross-channel precursor risk: {0} related high-risk signals were seen on other channels in the lookback window."
    ),
    VOICE_DRIFT(
            Severity.MEDIUM,
            EvidenceFamily.VOICE,
            "Intra-call voice embedding drift is {0}, above the stability threshold of {1}."
    ),
    CHALLENGE_LATENCY_FAIL(
            Severity.CRITICAL,
            EvidenceFamily.VOICE,
            "Liveness challenge response took {0} ms, which exceeds the allowed budget of {1} ms."
    ),
    CHALLENGE_CONTENT_FAIL(
            Severity.CRITICAL,
            EvidenceFamily.VOICE,
            "Liveness challenge content mismatch: expected phrase was not recognisably spoken (overlap {0})."
    ),
    CHALLENGE_ACOUSTIC_FAIL(
            Severity.CRITICAL,
            EvidenceFamily.VOICE,
            "Liveness challenge acoustic mismatch: response speaker cosine {0} is below the consistency floor of {1}."
    ),
    WATERMARK_DETECTED(
            Severity.INFO,
            EvidenceFamily.VOICE,
            "A known generative-audio watermark from provider {0} was detected with confidence {1}%."
    );

    public enum Severity {
        CRITICAL,
        HIGH,
        MEDIUM,
        INFO;

        /** Lower rank sorts first (CRITICAL before INFO). */
        public int rank() {
            return ordinal();
        }
    }

    private final Severity severity;
    private final EvidenceFamily family;
    private final String template;

    ReasonCode(Severity severity, EvidenceFamily family, String template) {
        this.severity = severity;
        this.family = family;
        this.template = template;
    }

    public Severity severity() {
        return severity;
    }

    public EvidenceFamily family() {
        return family;
    }

    public String template() {
        return template;
    }

    /** Format the template with {@link java.text.MessageFormat}-style `{n}` placeholders. */
    public String format(Object... args) {
        String out = template;
        for (int i = 0; i < args.length; i++) {
            out = out.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return out;
    }
}

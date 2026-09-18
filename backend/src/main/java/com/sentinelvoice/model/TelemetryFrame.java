package com.sentinelvoice.model;

import java.util.List;

/**
 * Frozen wire model for Decision Plane → React via STOMP {@code /topic/telemetry/{sessionId}}.
 * Mirrors {@code docs/contracts/TelemetryFrame.schema.json} / Context §8.2.
 *
 * <p>Nulls for required nullable fields ({@code manualOverride}, identity optionals, etc.)
 * MUST be serialised so Ajv {@code required} checks pass on the React side — do not
 * apply {@code @JsonInclude(NON_ABSENT)} here.
 */
public record TelemetryFrame(
        String schema,
        String sessionId,
        int seq,
        long tsEpochMs,
        long callElapsedMs,
        Risk risk,
        Families families,
        Corroboration corroboration,
        Intervention intervention,
        Identity identity,
        List<Reason> topReasons,
        TranscriptDelta transcriptDelta,
        String auditHash
) {
    public static final String SCHEMA = "sentinelvoice.TelemetryFrame/1";

    public TelemetryFrame {
        topReasons = topReasons == null ? List.of() : List.copyOf(topReasons);
    }

    public record Risk(
            double instantaneous,
            double smoothed,
            String trend,
            String state
    ) {
    }

    public record FamilyScore(
            double score,
            double weight,
            double contribution,
            boolean available
    ) {
    }

    public record Families(
            FamilyScore voice,
            FamilyScore channel,
            FamilyScore prosody,
            FamilyScore linguistic,
            FamilyScore transaction,
            FamilyScore relationship
    ) {
    }

    public record Corroboration(
            List<String> familiesAboveThreshold,
            int independentFamiliesRequired,
            boolean satisfied
    ) {
        public Corroboration {
            familiesAboveThreshold = familiesAboveThreshold == null
                    ? List.of()
                    : List.copyOf(familiesAboveThreshold);
        }
    }

    public record ManualOverride(
            String analystId,
            String reason,
            String targetLevel
    ) {
    }

    public record Intervention(
            String level,
            String previousLevel,
            long changedAtMs,
            long dwellRemainingMs,
            ManualOverride manualOverride,
            List<String> actionsFired
    ) {
        public Intervention {
            actionsFired = actionsFired == null ? List.of() : List.copyOf(actionsFired);
        }
    }

    public record DirectoryRecord(
            String employeeId,
            String role
    ) {
    }

    public record VoicePassport(
            boolean enrolled,
            double cosine,
            String verdict
    ) {
    }

    public record PresenceConflict(
            String expected,
            String observed
    ) {
    }

    public record Identity(
            String cli,
            String cliTrunk,
            Object directoryMatch,
            String claimedIdentity,
            String claimedRole,
            DirectoryRecord directoryRecordForClaim,
            boolean cliVsClaimMismatch,
            VoicePassport voicePassport,
            PresenceConflict presenceConflict
    ) {
    }

    public record Reason(
            String code,
            String severity,
            String text
    ) {
    }

    public record TranscriptDelta(
            long tsMs,
            String text,
            List<String> flags
    ) {
        public TranscriptDelta {
            flags = flags == null ? List.of() : List.copyOf(flags);
        }
    }
}

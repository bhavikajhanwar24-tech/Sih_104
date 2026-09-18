package com.sentinelvoice.forensics.model;

import java.util.List;
import java.util.Map;

/**
 * Court-ready evidence package assembled from Decision Plane retained state only.
 * Never contains audio. Schema mirrors Context §7.3 / §13 dossier expectations.
 */
public record ForensicDossier(
        String schema,
        String sessionId,
        boolean noFindings,
        String summary,
        long generatedAtEpochMs,
        String generatedBy,
        String manifestSha256,
        String pdfSha256,
        CaseHeader caseHeader,
        Map<String, Object> identity,
        String identityMismatchAnalysis,
        List<RiskSample> riskTimeline,
        List<LevelMarker> levelMarkers,
        List<EvidenceItem> evidence,
        List<InterventionEvent> interventionLog,
        List<AnalystAction> analystActions,
        List<ChallengeEvent> challengeResults,
        AuditChainSection auditChain,
        List<MethodologyEntry> methodology,
        String noAudioStatement
) {
    public static final String SCHEMA = "sentinelvoice.ForensicDossier/1";

    public ForensicDossier {
        riskTimeline = riskTimeline == null ? List.of() : List.copyOf(riskTimeline);
        levelMarkers = levelMarkers == null ? List.of() : List.copyOf(levelMarkers);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        interventionLog = interventionLog == null ? List.of() : List.copyOf(interventionLog);
        analystActions = analystActions == null ? List.of() : List.copyOf(analystActions);
        challengeResults = challengeResults == null ? List.of() : List.copyOf(challengeResults);
        methodology = methodology == null ? List.of() : List.copyOf(methodology);
        identity = copyIdentity(identity);
    }

    private static Map<String, Object> copyIdentity(Map<String, Object> identity) {
        if (identity == null || identity.isEmpty()) {
            return Map.of();
        }
        // Map.copyOf rejects null values — identity optionals may be null.
        java.util.LinkedHashMap<String, Object> copy = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> e : identity.entrySet()) {
            if (e.getKey() != null) {
                copy.put(e.getKey(), e.getValue());
            }
        }
        return java.util.Collections.unmodifiableMap(copy);
    }

    public ForensicDossier withDigests(String manifestSha256, String pdfSha256) {
        return new ForensicDossier(
                schema, sessionId, noFindings, summary, generatedAtEpochMs, generatedBy,
                manifestSha256, pdfSha256, caseHeader, identity, identityMismatchAnalysis,
                riskTimeline, levelMarkers, evidence, interventionLog, analystActions,
                challengeResults, auditChain, methodology, noAudioStatement
        );
    }

    public record CaseHeader(
            String sessionId,
            String callerId,
            String calleeId,
            long startEpochMs,
            long endEpochMs,
            long durationMs,
            String channelProfile,
            String adapterUsed,
            String scenarioId,
            String finalInterventionLevel,
            double finalSmoothedRisk
    ) {
    }

    public record RiskSample(long tsEpochMs, double smoothedRisk, double instantaneousRisk, String level) {
    }

    public record LevelMarker(long tsEpochMs, String fromLevel, String toLevel, String trigger) {
    }

    public record InterventionEvent(
            long tsEpochMs,
            String fromLevel,
            String toLevel,
            String trigger,
            List<String> actionsFired,
            Long latencyFromSessionStartMs
    ) {
        public InterventionEvent {
            actionsFired = actionsFired == null ? List.of() : List.copyOf(actionsFired);
        }
    }

    public record AnalystAction(
            long tsEpochMs,
            String analystId,
            String fromLevel,
            String toLevel,
            String reason
    ) {
    }

    public record ChallengeEvent(
            long tsEpochMs,
            String eventType,
            Map<String, Object> payload
    ) {
        public ChallengeEvent {
            // Payload may contain null optionals — avoid Map.copyOf.
            if (payload == null || payload.isEmpty()) {
                payload = Map.of();
            } else {
                java.util.LinkedHashMap<String, Object> copy = new java.util.LinkedHashMap<>();
                for (Map.Entry<String, Object> e : payload.entrySet()) {
                    if (e.getKey() != null) {
                        copy.put(e.getKey(), e.getValue());
                    }
                }
                payload = java.util.Collections.unmodifiableMap(copy);
            }
        }
    }

    public record AuditChainSection(
            int blockCount,
            String genesisHash,
            String finalHash,
            boolean verificationValid,
            String verificationDetail,
            List<AuditBlockSummary> firstBlocks,
            List<AuditBlockSummary> lastBlocks
    ) {
        public AuditChainSection {
            firstBlocks = firstBlocks == null ? List.of() : List.copyOf(firstBlocks);
            lastBlocks = lastBlocks == null ? List.of() : List.copyOf(lastBlocks);
        }
    }

    public record AuditBlockSummary(
            int blockIndex,
            long tsEpochMs,
            String eventType,
            String currentHash,
            String previousHash
    ) {
    }

    public record MethodologyEntry(
            String evidenceFamily,
            String modelOrMethod,
            String version,
            String channelProfile,
            String measuredEer,
            String notes
    ) {
    }
}

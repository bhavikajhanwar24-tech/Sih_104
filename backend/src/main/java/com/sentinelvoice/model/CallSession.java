package com.sentinelvoice.model;

import com.sentinelvoice.fusion.config.FusionConfigDocument;
import com.sentinelvoice.response.ResponsePlanDocument;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Contains NO audio. Raw PCM never enters the Decision Plane. See Context §6.3.
 *
 * <p>In-memory session state for fusion, intervention, and the analyst console.
 * Telemetry history is a bounded ring of scores and enums only.
 *
 * <p>Identity/context fields are resolved per frame by {@code IdentityResolutionService} (P8.1).
 */
public class CallSession {

    private final String sessionId;
    private final UUID tenantId;
    private final String callerId;
    private final String calleeId;
    private final ChannelProfile channelProfile;
    private final String scenarioId;
    private final Instant createdAt;
    private final TelemetryHistory telemetryHistory = new TelemetryHistory();
    private final Map<String, Object> metadata = new ConcurrentHashMap<>();
    private final AtomicLong nextSeq = new AtomicLong(0);

    private volatile Instant lastFrameAt;
    private volatile SessionState state = SessionState.INITIALISING;
    private volatile double smoothedRisk;
    private volatile InterventionLevel currentLevel = InterventionLevel.LEVEL_1_SILENT;
    private volatile long levelChangedAtMs;
    private volatile long cumulativeSpeechMs;
    private volatile FeatureFrame lastFeatureFrame;
    private volatile int lastFeatureSeq = -1;
    /**
     * Wall-clock epoch ms corresponding to call-relative {@code windowEndMs == 0}.
     * Locked on the first relative FeatureFrame so a delayed mic start does not
     * make every frame look stale vs {@link #createdAt}.
     */
    private volatile Long mediaOriginEpochMs;
    /**
     * Every reason code that fired during the session (cumulative), for forensic evidence.
     * Deduped by code+observedAt bucket; never contains audio or verbatim unredacted transcript.
     */
    private final List<FiredReason> firedReasons = new CopyOnWriteArrayList<>();

    /** Snapshot of ACTIVE fusion config at session open (F8) — mid-call config changes do not apply. */
    private volatile Integer fusionConfigVersion;
    private volatile FusionConfigDocument fusionConfigSnapshot;
    private volatile Integer policyVersion;
    /** Snapshot of ACTIVE response plan at session open (F9). */
    private volatile Integer responsePlanVersion;
    private volatile ResponsePlanDocument responsePlanSnapshot;

    /** F11 — last linguistic extraction metadata for gate-check / UI. */
    private volatile String lastLinguisticSource;
    private volatile Long lastLinguisticAgeMs;
    private volatile Boolean lastLinguisticPending;
    private volatile Double lastLinguisticConfidence;
    /** Spoken ACTIVE lexicon terms that matched (not a transcript). */
    private volatile List<String> lastMatchedKeywords = List.of();
    /** Latest Stage B / LLM rationale for operators (Live Calls). */
    private volatile String lastLlmThinking;
    /** Lab: short redacted ASR rolling transcript for Live Calls More info. */
    private volatile String lastAsrTranscript;
    /** ACTIVE policy rule ids that RuleEngine / keyword path marked broken. */
    private volatile List<String> lastBrokenRuleIds = List.of();
    private volatile List<String> lastBrokenRuleTitles = List.of();

    /** One evidence row retained on the Decision Plane (scores / enums / narratives only). */
    public record FiredReason(
            String reasonCode,
            String severity,
            String family,
            long observedAtEpochMs,
            String measuredValue,
            String humanBaseline,
            String narrative
    ) {
    }

    public CallSession(
            UUID tenantId,
            String sessionId,
            String callerId,
            String calleeId,
            ChannelProfile channelProfile,
            String scenarioId
    ) {
        Instant now = Instant.now();
        this.tenantId = tenantId;
        this.sessionId = sessionId;
        this.callerId = callerId;
        this.calleeId = calleeId;
        this.channelProfile = channelProfile;
        this.scenarioId = scenarioId;
        this.createdAt = now;
        this.lastFrameAt = now;
        this.levelChangedAtMs = now.toEpochMilli();
        if (scenarioId != null) {
            this.metadata.put("scenarioId", scenarioId);
        }
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void recordTelemetry(TelemetryEntry entry) {
        telemetryHistory.add(entry);
        this.smoothedRisk = entry.smoothedRisk();
        this.lastFrameAt = Instant.ofEpochMilli(entry.tsMs());
        InterventionLevel previous = this.currentLevel;
        this.currentLevel = entry.level();
        if (previous != entry.level()) {
            this.levelChangedAtMs = entry.tsMs();
        }
        if (this.state == SessionState.INITIALISING) {
            this.state = SessionState.ACTIVE;
        }
        nextSeq.updateAndGet(current -> Math.max(current, entry.seq() + 1));
    }

    public long allocateSeq() {
        return nextSeq.getAndIncrement();
    }

    public void storeFeatureFrame(FeatureFrame frame) {
        this.lastFeatureFrame = frame;
        this.lastFeatureSeq = frame.seq();
        this.cumulativeSpeechMs = frame.cumulativeSpeechMs();
        this.lastFrameAt = Instant.now();
        if (this.state == SessionState.INITIALISING) {
            this.state = SessionState.ACTIVE;
        }
        // Surface keywords/rule ids to Live Calls even when fusion/policy pipeline fails later.
        if (frame != null && frame.linguistic() != null) {
            recordLinguisticMeta(frame.linguistic());
            if (frame.linguistic().matchedRuleIds() != null
                    && !frame.linguistic().matchedRuleIds().isEmpty()) {
                recordBrokenRules(frame.linguistic().matchedRuleIds(), List.of());
            }
        }
    }

    /**
     * Append reason codes that fired on this frame. Cumulative across the call so the
     * forensic dossier evidence table is not limited to the latest TelemetryFrame.topReasons.
     */
    public void recordFiredReasons(long observedAtEpochMs, List<FiredReason> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return;
        }
        for (FiredReason r : reasons) {
            if (r == null || r.reasonCode() == null || r.reasonCode().isBlank()) {
                continue;
            }
            firedReasons.add(new FiredReason(
                    r.reasonCode(),
                    r.severity() == null ? "INFO" : r.severity(),
                    r.family(),
                    observedAtEpochMs,
                    r.measuredValue(),
                    r.humanBaseline(),
                    r.narrative()
            ));
        }
    }

    public List<FiredReason> getFiredReasons() {
        return List.copyOf(firedReasons);
    }

    public FeatureFrame getLastFeatureFrame() {
        return lastFeatureFrame;
    }

    public int getLastFeatureSeq() {
        return lastFeatureSeq;
    }

    public Long getMediaOriginEpochMs() {
        return mediaOriginEpochMs;
    }

    public void setMediaOriginEpochMs(long mediaOriginEpochMs) {
        if (this.mediaOriginEpochMs == null) {
            this.mediaOriginEpochMs = mediaOriginEpochMs;
        }
    }

    public void close() {
        this.state = SessionState.CLOSED;
        this.lastFrameAt = Instant.now();
    }

    public void setLastFrameAt(Instant lastFrameAt) {
        this.lastFrameAt = lastFrameAt;
    }

    public void setCumulativeSpeechMs(long cumulativeSpeechMs) {
        this.cumulativeSpeechMs = cumulativeSpeechMs;
    }

    public String getCallerId() {
        return callerId;
    }

    public String getCalleeId() {
        return calleeId;
    }

    public ChannelProfile getChannelProfile() {
        return channelProfile;
    }

    public String getScenarioId() {
        return scenarioId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastFrameAt() {
        return lastFrameAt;
    }

    public SessionState getState() {
        return state;
    }

    public TelemetryHistory getTelemetryHistory() {
        return telemetryHistory;
    }

    public double getSmoothedRisk() {
        return smoothedRisk;
    }

    public InterventionLevel getCurrentLevel() {
        return currentLevel;
    }

    public long getLevelChangedAtMs() {
        return levelChangedAtMs;
    }

    public long getCumulativeSpeechMs() {
        return cumulativeSpeechMs;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public Integer getFusionConfigVersion() {
        return fusionConfigVersion;
    }

    public void setFusionConfigVersion(Integer fusionConfigVersion) {
        this.fusionConfigVersion = fusionConfigVersion;
    }

    public FusionConfigDocument getFusionConfigSnapshot() {
        return fusionConfigSnapshot;
    }

    public void setFusionConfigSnapshot(FusionConfigDocument fusionConfigSnapshot) {
        this.fusionConfigSnapshot = fusionConfigSnapshot;
    }

    public Integer getPolicyVersion() {
        return policyVersion;
    }

    public void setPolicyVersion(Integer policyVersion) {
        this.policyVersion = policyVersion;
    }

    public Integer getResponsePlanVersion() {
        return responsePlanVersion;
    }

    public void setResponsePlanVersion(Integer responsePlanVersion) {
        this.responsePlanVersion = responsePlanVersion;
    }

    public ResponsePlanDocument getResponsePlanSnapshot() {
        return responsePlanSnapshot;
    }

    public void setResponsePlanSnapshot(ResponsePlanDocument responsePlanSnapshot) {
        this.responsePlanSnapshot = responsePlanSnapshot;
    }

    public String getLastLinguisticSource() {
        return lastLinguisticSource;
    }

    public Long getLastLinguisticAgeMs() {
        return lastLinguisticAgeMs;
    }

    public Boolean getLastLinguisticPending() {
        return lastLinguisticPending;
    }

    public Double getLastLinguisticConfidence() {
        return lastLinguisticConfidence;
    }

    public List<String> getLastMatchedKeywords() {
        return lastMatchedKeywords == null ? List.of() : lastMatchedKeywords;
    }

    public String getLastLlmThinking() {
        return lastLlmThinking;
    }

    public String getLastAsrTranscript() {
        return lastAsrTranscript;
    }

    public List<String> getLastBrokenRuleIds() {
        return lastBrokenRuleIds == null ? List.of() : lastBrokenRuleIds;
    }

    public List<String> getLastBrokenRuleTitles() {
        return lastBrokenRuleTitles == null ? List.of() : lastBrokenRuleTitles;
    }

    public void recordLinguisticMeta(LinguisticFamily ling) {
        if (ling == null) {
            return;
        }
        this.lastLinguisticSource = ling.source();
        this.lastLinguisticAgeMs = ling.ageMs();
        this.lastLinguisticPending = ling.llmPending();
        this.lastLinguisticConfidence = ling.confidence();
        if (ling.matchedKeywords() != null && !ling.matchedKeywords().isEmpty()) {
            this.lastMatchedKeywords = List.copyOf(ling.matchedKeywords());
        }
        if (ling.llmThinking() != null && !ling.llmThinking().isBlank()) {
            this.lastLlmThinking = ling.llmThinking().trim();
        }
        if (ling.redactedSnippet() != null && !ling.redactedSnippet().isBlank()) {
            String snip = ling.redactedSnippet().trim();
            this.lastAsrTranscript = snip.length() > 400 ? snip.substring(snip.length() - 400) : snip;
        }
    }

    public void recordBrokenRules(List<String> ruleIds, List<String> titles) {
        if (ruleIds != null && !ruleIds.isEmpty()) {
            this.lastBrokenRuleIds = List.copyOf(ruleIds);
        }
        if (titles != null && !titles.isEmpty()) {
            this.lastBrokenRuleTitles = List.copyOf(titles);
        }
    }
}

package com.sentinelvoice.model;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

    public CallSession(
            String sessionId,
            String callerId,
            String calleeId,
            ChannelProfile channelProfile,
            String scenarioId
    ) {
        Instant now = Instant.now();
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

    public String getSessionId() {
        return sessionId;
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
}

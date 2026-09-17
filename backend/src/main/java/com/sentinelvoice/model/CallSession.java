package com.sentinelvoice.model;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CallSession {
    private final String sessionId;
    private final String callerId;
    private final String callerName;
    private final String claimedRole;
    private final Deque<byte[]> audioBuffer = new ArrayDeque<>();
    private final Map<String, Object> metadata = new ConcurrentHashMap<>();
    private volatile double rollingRiskScore;
    private volatile InterventionLevel currentInterventionLevel = InterventionLevel.LEVEL_1_SILENT;
    private volatile Instant createdAt = Instant.now();
    private volatile Instant updatedAt = Instant.now();

    public CallSession(String sessionId, String callerId, String callerName, String claimedRole) {
        this.sessionId = sessionId;
        this.callerId = callerId;
        this.callerName = callerName;
        this.claimedRole = claimedRole;
    }

    public void appendAudioChunk(byte[] chunk) {
        this.audioBuffer.addLast(chunk);
        this.updatedAt = Instant.now();
    }

    public byte[] drainAudioBuffer() {
        int size = 0;
        for (byte[] chunk : audioBuffer) {
            size += chunk.length;
        }
        byte[] merged = new byte[size];
        int offset = 0;
        while (!audioBuffer.isEmpty()) {
            byte[] chunk = audioBuffer.removeFirst();
            System.arraycopy(chunk, 0, merged, offset, chunk.length);
            offset += chunk.length;
        }
        return merged;
    }

    public void setRollingRiskScore(double rollingRiskScore) {
        this.rollingRiskScore = rollingRiskScore;
        this.updatedAt = Instant.now();
    }

    public double getRollingRiskScore() {
        return rollingRiskScore;
    }

    public InterventionLevel getCurrentInterventionLevel() {
        return currentInterventionLevel;
    }

    public void setCurrentInterventionLevel(InterventionLevel currentInterventionLevel) {
        this.currentInterventionLevel = currentInterventionLevel;
        this.updatedAt = Instant.now();
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getCallerId() {
        return callerId;
    }

    public String getCallerName() {
        return callerName;
    }

    public String getClaimedRole() {
        return claimedRole;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

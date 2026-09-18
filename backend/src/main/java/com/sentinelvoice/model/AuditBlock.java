package com.sentinelvoice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

@Entity
@Table(
        name = "audit_blocks",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_audit_blocks_session_index",
                columnNames = {"session_id", "block_index"}
        )
)
public class AuditBlock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private String sessionId;

    @Column(name = "block_index", nullable = false)
    private int blockIndex;

    @Column(nullable = false)
    private String eventType;

    /**
     * Canonical JSON of the event payload. Column name stays {@code details}
     * so the live H2 tamper demo can {@code UPDATE audit_blocks SET details = ...}.
     */
    @Column(name = "details", nullable = false, columnDefinition = "TEXT")
    private String details;

    @Column(nullable = false)
    private long tsEpochMs;

    @Column(nullable = false)
    private Instant timestamp = Instant.now();

    @Column(nullable = false)
    private double smoothedRisk;

    @Column(nullable = false)
    private String level;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String previousHash;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String currentHash;

    public AuditBlock() {
    }

    public Long getId() {
        return id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public int getBlockIndex() {
        return blockIndex;
    }

    public void setBlockIndex(int blockIndex) {
        this.blockIndex = blockIndex;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }

    public long getTsEpochMs() {
        return tsEpochMs;
    }

    public void setTsEpochMs(long tsEpochMs) {
        this.tsEpochMs = tsEpochMs;
        this.timestamp = Instant.ofEpochMilli(tsEpochMs);
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
        if (timestamp != null) {
            this.tsEpochMs = timestamp.toEpochMilli();
        }
    }

    public double getSmoothedRisk() {
        return smoothedRisk;
    }

    public void setSmoothedRisk(double smoothedRisk) {
        this.smoothedRisk = smoothedRisk;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getPreviousHash() {
        return previousHash;
    }

    public void setPreviousHash(String previousHash) {
        this.previousHash = previousHash;
    }

    public String getCurrentHash() {
        return currentHash;
    }

    public void setCurrentHash(String currentHash) {
        this.currentHash = currentHash;
    }
}

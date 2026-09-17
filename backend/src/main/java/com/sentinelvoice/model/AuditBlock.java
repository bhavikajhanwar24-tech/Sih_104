package com.sentinelvoice.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "audit_blocks")
public class AuditBlock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String sessionId;

    @Column(nullable = false)
    private String eventType;

    @Column(columnDefinition = "TEXT")
    private String details;

    @Column(nullable = false)
    private Instant timestamp = Instant.now();

    @Column(nullable = false, columnDefinition = "TEXT")
    private String previousHash;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String currentHash;

    public AuditBlock() {
    }

    public AuditBlock(String sessionId, String eventType, String details, String previousHash, String currentHash) {
        this.sessionId = sessionId;
        this.eventType = eventType;
        this.details = details;
        this.previousHash = previousHash;
        this.currentHash = currentHash;
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

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
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

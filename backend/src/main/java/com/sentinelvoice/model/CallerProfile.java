package com.sentinelvoice.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "caller_profiles")
public class CallerProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String callerId;

    @Column(nullable = false)
    private String callerName;

    @Column(nullable = false)
    private String claimedRole;

    @Column(nullable = false)
    private boolean consentGranted = false;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(length = 512)
    private String voiceFingerprint;

    public CallerProfile() {
    }

    public CallerProfile(String callerId, String callerName, String claimedRole, boolean consentGranted) {
        this.callerId = callerId;
        this.callerName = callerName;
        this.claimedRole = claimedRole;
        this.consentGranted = consentGranted;
    }

    public Long getId() {
        return id;
    }

    public String getCallerId() {
        return callerId;
    }

    public void setCallerId(String callerId) {
        this.callerId = callerId;
        this.updatedAt = Instant.now();
    }

    public String getCallerName() {
        return callerName;
    }

    public void setCallerName(String callerName) {
        this.callerName = callerName;
        this.updatedAt = Instant.now();
    }

    public String getClaimedRole() {
        return claimedRole;
    }

    public void setClaimedRole(String claimedRole) {
        this.claimedRole = claimedRole;
        this.updatedAt = Instant.now();
    }

    public boolean isConsentGranted() {
        return consentGranted;
    }

    public void setConsentGranted(boolean consentGranted) {
        this.consentGranted = consentGranted;
        this.updatedAt = Instant.now();
    }

    public String getVoiceFingerprint() {
        return voiceFingerprint;
    }

    public void setVoiceFingerprint(String voiceFingerprint) {
        this.voiceFingerprint = voiceFingerprint;
        this.updatedAt = Instant.now();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

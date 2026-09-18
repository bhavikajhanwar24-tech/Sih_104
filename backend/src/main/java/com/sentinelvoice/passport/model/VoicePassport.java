package com.sentinelvoice.passport.model;

import com.sentinelvoice.model.ChannelProfile;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * Per-employee, per-channel-profile Voice Passport (Context §12 / §13.2).
 * One row per (employeeId, channelProfile) — never cross-compare channels.
 */
@Entity
@Table(
        name = "voice_passports",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_passport_employee_channel",
                columnNames = {"employee_id", "channel_profile"}
        )
)
public class VoicePassport {

    @Id
    @Column(name = "profile_id", nullable = false, length = 64)
    private String profileId;

    @Column(name = "employee_id", nullable = false, length = 32)
    private String employeeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel_profile", nullable = false, length = 32)
    private ChannelProfile channelProfile;

    /**
     * Base64-wrapped float32 LE bytes via {@link com.sentinelvoice.passport.EmbeddingCodec}.
     * Never expose via GET — DPDP §11 is metadata about processing, not the biometric template.
     */
    @Lob
    @Column(name = "embedding", nullable = false, columnDefinition = "BLOB")
    private byte[] embedding;

    @Column(name = "embedding_model_id", nullable = false)
    private String embeddingModelId;

    @Column(name = "enrolled_at", nullable = false)
    private Instant enrolledAt;

    @Column(name = "last_verified_at")
    private Instant lastVerifiedAt;

    @Column(nullable = false)
    private boolean active = true;

    public VoicePassport() {
    }

    public String getProfileId() {
        return profileId;
    }

    public void setProfileId(String profileId) {
        this.profileId = profileId;
    }

    public String getEmployeeId() {
        return employeeId;
    }

    public void setEmployeeId(String employeeId) {
        this.employeeId = employeeId;
    }

    public ChannelProfile getChannelProfile() {
        return channelProfile;
    }

    public void setChannelProfile(ChannelProfile channelProfile) {
        this.channelProfile = channelProfile;
    }

    public byte[] getEmbedding() {
        return embedding;
    }

    public void setEmbedding(byte[] embedding) {
        this.embedding = embedding;
    }

    public String getEmbeddingModelId() {
        return embeddingModelId;
    }

    public void setEmbeddingModelId(String embeddingModelId) {
        this.embeddingModelId = embeddingModelId;
    }

    public Instant getEnrolledAt() {
        return enrolledAt;
    }

    public void setEnrolledAt(Instant enrolledAt) {
        this.enrolledAt = enrolledAt;
    }

    public Instant getLastVerifiedAt() {
        return lastVerifiedAt;
    }

    public void setLastVerifiedAt(Instant lastVerifiedAt) {
        this.lastVerifiedAt = lastVerifiedAt;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}

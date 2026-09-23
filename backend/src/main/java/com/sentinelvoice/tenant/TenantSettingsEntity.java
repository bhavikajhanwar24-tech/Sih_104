package com.sentinelvoice.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "tenant_settings")
public class TenantSettingsEntity {

    @Id
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "retention_days", nullable = false)
    private int retentionDays = 90;

    @Column(name = "allow_external_llm", nullable = false)
    private boolean allowExternalLlm;

    @Column(name = "llm_fail_policy", nullable = false)
    private String llmFailPolicy = "CONTINUE_RULES_ONLY";

    @Column(name = "consent_notice_text")
    private String consentNoticeText;

    @Column(name = "max_concurrent_calls", nullable = false)
    private int maxConcurrentCalls = 20;

    @Column(nullable = false, length = 64)
    private String timezone = "Asia/Kolkata";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> extras = new LinkedHashMap<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "emergency_mode", length = 32)
    private String emergencyMode;

    @Column(name = "emergency_mode_set_at")
    private Instant emergencyModeSetAt;

    @Column(name = "emergency_mode_expires_at")
    private Instant emergencyModeExpiresAt;

    @Column(name = "emergency_mode_set_by")
    private UUID emergencyModeSetBy;

    @Column(name = "monitor_only_ttl_minutes", nullable = false)
    private int monitorOnlyTtlMinutes = 60;

    @Column(name = "play_call_notice", nullable = false)
    private boolean playCallNotice;

    @Column(name = "notice_asset_id")
    private UUID noticeAssetId;

    @Column(name = "notice_version", nullable = false)
    private int noticeVersion = 1;

    @Column(name = "consent_languages", nullable = false)
    private String consentLanguages = "en,hi";

    @Column(name = "last_retention_purge_at")
    private Instant lastRetentionPurgeAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "last_retention_purge_stats", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> lastRetentionPurgeStats = new LinkedHashMap<>();

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        if (retentionDays < 7) {
            this.retentionDays = 7;
        } else if (retentionDays > 365) {
            this.retentionDays = 365;
        } else {
            this.retentionDays = retentionDays;
        }
    }

    public boolean isAllowExternalLlm() {
        return allowExternalLlm;
    }

    public void setAllowExternalLlm(boolean allowExternalLlm) {
        this.allowExternalLlm = allowExternalLlm;
    }

    public String getLlmFailPolicy() {
        return llmFailPolicy;
    }

    public void setLlmFailPolicy(String llmFailPolicy) {
        this.llmFailPolicy = llmFailPolicy;
    }

    public String getConsentNoticeText() {
        return consentNoticeText;
    }

    public void setConsentNoticeText(String consentNoticeText) {
        this.consentNoticeText = consentNoticeText;
    }

    public int getMaxConcurrentCalls() {
        return maxConcurrentCalls;
    }

    public void setMaxConcurrentCalls(int maxConcurrentCalls) {
        this.maxConcurrentCalls = maxConcurrentCalls;
    }

    public String getTimezone() {
        return timezone == null || timezone.isBlank() ? "Asia/Kolkata" : timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone == null || timezone.isBlank() ? "Asia/Kolkata" : timezone;
    }

    public Map<String, Object> getExtras() {
        return extras;
    }

    public void setExtras(Map<String, Object> extras) {
        this.extras = extras == null ? new LinkedHashMap<>() : new LinkedHashMap<>(extras);
    }

    /** F11 — comma-separated Whisper language codes in extras.asrLanguages (default en,hi). */
    public String getAsrLanguages() {
        Object v = extras == null ? null : extras.get("asrLanguages");
        if (v == null || String.valueOf(v).isBlank()) {
            return "en,hi";
        }
        return String.valueOf(v).trim();
    }

    public void setAsrLanguages(String languages) {
        if (extras == null) {
            extras = new LinkedHashMap<>();
        }
        extras.put("asrLanguages", languages == null || languages.isBlank() ? "en,hi" : languages.trim());
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getEmergencyMode() {
        return emergencyMode;
    }

    public void setEmergencyMode(String emergencyMode) {
        this.emergencyMode = emergencyMode;
    }

    public Instant getEmergencyModeSetAt() {
        return emergencyModeSetAt;
    }

    public void setEmergencyModeSetAt(Instant emergencyModeSetAt) {
        this.emergencyModeSetAt = emergencyModeSetAt;
    }

    public Instant getEmergencyModeExpiresAt() {
        return emergencyModeExpiresAt;
    }

    public void setEmergencyModeExpiresAt(Instant emergencyModeExpiresAt) {
        this.emergencyModeExpiresAt = emergencyModeExpiresAt;
    }

    public UUID getEmergencyModeSetBy() {
        return emergencyModeSetBy;
    }

    public void setEmergencyModeSetBy(UUID emergencyModeSetBy) {
        this.emergencyModeSetBy = emergencyModeSetBy;
    }

    public int getMonitorOnlyTtlMinutes() {
        return monitorOnlyTtlMinutes <= 0 ? 60 : monitorOnlyTtlMinutes;
    }

    public void setMonitorOnlyTtlMinutes(int monitorOnlyTtlMinutes) {
        this.monitorOnlyTtlMinutes = monitorOnlyTtlMinutes <= 0 ? 60 : monitorOnlyTtlMinutes;
    }

    public boolean isPlayCallNotice() {
        return playCallNotice;
    }

    public void setPlayCallNotice(boolean playCallNotice) {
        this.playCallNotice = playCallNotice;
    }

    public UUID getNoticeAssetId() {
        return noticeAssetId;
    }

    public void setNoticeAssetId(UUID noticeAssetId) {
        this.noticeAssetId = noticeAssetId;
    }

    public int getNoticeVersion() {
        return noticeVersion <= 0 ? 1 : noticeVersion;
    }

    public void setNoticeVersion(int noticeVersion) {
        this.noticeVersion = noticeVersion <= 0 ? 1 : noticeVersion;
    }

    public String getConsentLanguages() {
        return consentLanguages == null || consentLanguages.isBlank() ? "en,hi" : consentLanguages;
    }

    public void setConsentLanguages(String consentLanguages) {
        this.consentLanguages = consentLanguages == null || consentLanguages.isBlank() ? "en,hi" : consentLanguages.trim();
    }

    public Instant getLastRetentionPurgeAt() {
        return lastRetentionPurgeAt;
    }

    public void setLastRetentionPurgeAt(Instant lastRetentionPurgeAt) {
        this.lastRetentionPurgeAt = lastRetentionPurgeAt;
    }

    public Map<String, Object> getLastRetentionPurgeStats() {
        return lastRetentionPurgeStats == null ? Map.of() : lastRetentionPurgeStats;
    }

    public void setLastRetentionPurgeStats(Map<String, Object> lastRetentionPurgeStats) {
        this.lastRetentionPurgeStats = lastRetentionPurgeStats == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(lastRetentionPurgeStats);
    }
}

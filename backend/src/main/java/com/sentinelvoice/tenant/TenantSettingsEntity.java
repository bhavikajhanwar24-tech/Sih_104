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

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> extras = new LinkedHashMap<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

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
        this.retentionDays = retentionDays;
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

    public Map<String, Object> getExtras() {
        return extras;
    }

    public void setExtras(Map<String, Object> extras) {
        this.extras = extras == null ? new LinkedHashMap<>() : new LinkedHashMap<>(extras);
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
}

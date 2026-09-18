package com.sentinelvoice.context.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * SIEM-fed precursor event (email / SMS / auth / web) correlated to a call target.
 * Contract: {@code sentinelvoice.CrossChannelEvent/1} (Context §7.3 / §8.3).
 */
@Entity
@Table(name = "cross_channel_events")
public class CrossChannelEvent {

    public enum Channel {
        EMAIL,
        SMS,
        AUTH,
        WEB
    }

    public enum Severity {
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }

    @Id
    @Column(length = 36, nullable = false)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Channel channel;

    @Column(name = "target_employee_id", nullable = false, length = 32)
    private String targetEmployeeId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Severity severity;

    /** Sender, sending domain, URL, or number. */
    @Column(nullable = false, length = 512)
    private String indicator;

    @Column(name = "campaign_id", length = 64)
    private String campaignId;

    @Column(nullable = false, length = 1024)
    private String description;

    public CrossChannelEvent() {
    }

    @PrePersist
    void ensureId() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Channel getChannel() {
        return channel;
    }

    public void setChannel(Channel channel) {
        this.channel = channel;
    }

    public String getTargetEmployeeId() {
        return targetEmployeeId;
    }

    public void setTargetEmployeeId(String targetEmployeeId) {
        this.targetEmployeeId = targetEmployeeId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    public Severity getSeverity() {
        return severity;
    }

    public void setSeverity(Severity severity) {
        this.severity = severity;
    }

    public String getIndicator() {
        return indicator;
    }

    public void setIndicator(String indicator) {
        this.indicator = indicator;
    }

    public String getCampaignId() {
        return campaignId;
    }

    public void setCampaignId(String campaignId) {
        this.campaignId = campaignId;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}

package com.sentinelvoice.directory.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "employee_authority")
public class EmployeeAuthorityEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Column(name = "action_type", nullable = false)
    private String actionType;

    @Column(name = "max_amount_inr")
    private BigDecimal maxAmountInr;

    @Column(name = "requires_dual_approval", nullable = false)
    private boolean requiresDualApproval;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "allowed_channels", columnDefinition = "text[]")
    private List<String> allowedChannels = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public UUID getEmployeeId() {
        return employeeId;
    }

    public void setEmployeeId(UUID employeeId) {
        this.employeeId = employeeId;
    }

    public String getActionType() {
        return actionType;
    }

    public void setActionType(String actionType) {
        this.actionType = actionType;
    }

    public BigDecimal getMaxAmountInr() {
        return maxAmountInr;
    }

    public void setMaxAmountInr(BigDecimal maxAmountInr) {
        this.maxAmountInr = maxAmountInr;
    }

    public boolean isRequiresDualApproval() {
        return requiresDualApproval;
    }

    public void setRequiresDualApproval(boolean requiresDualApproval) {
        this.requiresDualApproval = requiresDualApproval;
    }

    public List<String> getAllowedChannels() {
        return allowedChannels;
    }

    public void setAllowedChannels(List<String> allowedChannels) {
        this.allowedChannels = allowedChannels == null ? new ArrayList<>() : new ArrayList<>(allowedChannels);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}

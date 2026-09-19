package com.sentinelvoice.directory.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "beneficiary_accounts")
public class BeneficiaryAccountEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "external_party_id")
    private UUID externalPartyId;

    @Column(name = "account_ref_hash", nullable = false)
    private String accountRefHash;

    private String label;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(nullable = false)
    private boolean verified;

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

    public UUID getExternalPartyId() {
        return externalPartyId;
    }

    public void setExternalPartyId(UUID externalPartyId) {
        this.externalPartyId = externalPartyId;
    }

    public String getAccountRefHash() {
        return accountRefHash;
    }

    public void setAccountRefHash(String accountRefHash) {
        this.accountRefHash = accountRefHash;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    public void setFirstSeenAt(Instant firstSeenAt) {
        this.firstSeenAt = firstSeenAt;
    }

    public boolean isVerified() {
        return verified;
    }

    public void setVerified(boolean verified) {
        this.verified = verified;
    }
}

package com.sentinelvoice.directory.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "known_relationships")
public class KnownRelationshipEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "from_employee_id", nullable = false)
    private UUID fromEmployeeId;

    @Column(name = "to_employee_id")
    private UUID toEmployeeId;

    @Column(name = "to_external_id")
    private UUID toExternalId;

    @Column(name = "relationship_type", nullable = false)
    private String relationshipType = "COLLEAGUE";

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "typical_topics", columnDefinition = "text[]")
    private List<String> typicalTopics = new ArrayList<>();

    @Column(name = "last_contact_at")
    private Instant lastContactAt;

    @Column(name = "contact_count", nullable = false)
    private int contactCount;

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

    public UUID getFromEmployeeId() {
        return fromEmployeeId;
    }

    public void setFromEmployeeId(UUID fromEmployeeId) {
        this.fromEmployeeId = fromEmployeeId;
    }

    public UUID getToEmployeeId() {
        return toEmployeeId;
    }

    public void setToEmployeeId(UUID toEmployeeId) {
        this.toEmployeeId = toEmployeeId;
    }

    public UUID getToExternalId() {
        return toExternalId;
    }

    public void setToExternalId(UUID toExternalId) {
        this.toExternalId = toExternalId;
    }

    public String getRelationshipType() {
        return relationshipType;
    }

    public void setRelationshipType(String relationshipType) {
        this.relationshipType = relationshipType;
    }

    public List<String> getTypicalTopics() {
        return typicalTopics;
    }

    public void setTypicalTopics(List<String> typicalTopics) {
        this.typicalTopics = typicalTopics == null ? new ArrayList<>() : new ArrayList<>(typicalTopics);
    }

    public Instant getLastContactAt() {
        return lastContactAt;
    }

    public void setLastContactAt(Instant lastContactAt) {
        this.lastContactAt = lastContactAt;
    }

    public int getContactCount() {
        return contactCount;
    }

    public void setContactCount(int contactCount) {
        this.contactCount = contactCount;
    }
}

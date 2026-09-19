package com.sentinelvoice.directory.entity;

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
@Table(name = "directory_imports")
public class DirectoryImportEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "uploaded_by")
    private UUID uploadedBy;

    @Column(nullable = false)
    private String filename;

    @Column(nullable = false)
    private String kind;

    @Column(nullable = false)
    private String status = "DRY_RUN";

    @Column(name = "row_count", nullable = false)
    private int rowCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "error_report", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> errorReport = new LinkedHashMap<>();

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

    public UUID getUploadedBy() {
        return uploadedBy;
    }

    public void setUploadedBy(UUID uploadedBy) {
        this.uploadedBy = uploadedBy;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getRowCount() {
        return rowCount;
    }

    public void setRowCount(int rowCount) {
        this.rowCount = rowCount;
    }

    public Map<String, Object> getErrorReport() {
        return errorReport;
    }

    public void setErrorReport(Map<String, Object> errorReport) {
        this.errorReport = errorReport == null ? new LinkedHashMap<>() : new LinkedHashMap<>(errorReport);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}

package com.sentinelvoice.policy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "policy_documents")
public class PolicyDocumentEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String title;

    @Column(name = "doc_type", nullable = false)
    private String docType;

    @Column(name = "original_filename", nullable = false)
    private String originalFilename;

    @Column(name = "mime_type", nullable = false)
    private String mimeType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(nullable = false)
    private String sha256;

    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(nullable = false, columnDefinition = "bytea")
    private byte[] content;

    @Column(name = "extracted_text")
    private String extractedText;

    @Column(name = "text_sha256")
    private String textSha256;

    @Column(name = "language_detected")
    private String languageDetected;

    @Column(nullable = false)
    private String status = "UPLOADED";

    @Column(name = "uploaded_by")
    private UUID uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    @Column(name = "extraction_error")
    private String extractionError;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "injection_flags", nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> injectionFlags = new ArrayList<>();

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

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDocType() {
        return docType;
    }

    public void setDocType(String docType) {
        this.docType = docType;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public void setOriginalFilename(String originalFilename) {
        this.originalFilename = originalFilename;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    public byte[] getContent() {
        return content;
    }

    public void setContent(byte[] content) {
        this.content = content;
    }

    public String getExtractedText() {
        return extractedText;
    }

    public void setExtractedText(String extractedText) {
        this.extractedText = extractedText;
    }

    public String getTextSha256() {
        return textSha256;
    }

    public void setTextSha256(String textSha256) {
        this.textSha256 = textSha256;
    }

    public String getLanguageDetected() {
        return languageDetected;
    }

    public void setLanguageDetected(String languageDetected) {
        this.languageDetected = languageDetected;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public UUID getUploadedBy() {
        return uploadedBy;
    }

    public void setUploadedBy(UUID uploadedBy) {
        this.uploadedBy = uploadedBy;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }

    public void setUploadedAt(Instant uploadedAt) {
        this.uploadedAt = uploadedAt;
    }

    public String getExtractionError() {
        return extractionError;
    }

    public void setExtractionError(String extractionError) {
        this.extractionError = extractionError;
    }

    public List<Map<String, Object>> getInjectionFlags() {
        return injectionFlags;
    }

    public void setInjectionFlags(List<Map<String, Object>> injectionFlags) {
        this.injectionFlags = injectionFlags == null ? new ArrayList<>() : new ArrayList<>(injectionFlags);
    }
}

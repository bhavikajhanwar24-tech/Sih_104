package com.sentinelvoice.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "forensic_dossiers")
public class ForensicDossier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String sessionId;

    @Column(nullable = false)
    private String dossierType;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(nullable = false)
    private Instant generatedAt = Instant.now();

    public ForensicDossier() {
    }

    public ForensicDossier(String sessionId, String dossierType, String summary) {
        this.sessionId = sessionId;
        this.dossierType = dossierType;
        this.summary = summary;
    }

    public Long getId() {
        return id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getDossierType() {
        return dossierType;
    }

    public void setDossierType(String dossierType) {
        this.dossierType = dossierType;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public Instant getGeneratedAt() {
        return generatedAt;
    }
}

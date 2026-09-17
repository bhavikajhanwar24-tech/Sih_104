package com.sentinelvoice.service;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class ForensicDossierService {

    public Map<String, Object> buildDossier(String sessionId, double riskScore, String level) {
        Map<String, Object> dossier = new LinkedHashMap<>();
        dossier.put("sessionId", sessionId);
        dossier.put("riskScore", riskScore);
        dossier.put("interventionLevel", level);
        dossier.put("summary", "Suspicious fraud indicators detected in voice authenticity, urgency language, and transaction mismatch domains.");
        dossier.put("evidence", new String[] {
                "Voice spectral artifact anomaly",
                "Urgency language pattern",
                "Relationship mismatch",
                "Cross-channel correlation"
        });
        return dossier;
    }
}

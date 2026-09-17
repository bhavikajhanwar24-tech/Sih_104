package com.sentinelvoice.controller;

import com.sentinelvoice.service.CallSessionManager;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/forensics")
public class ForensicReportController {

    private final CallSessionManager callSessionManager;

    public ForensicReportController(CallSessionManager callSessionManager) {
        this.callSessionManager = callSessionManager;
    }

    @GetMapping("/{sessionId}/dossier")
    public ResponseEntity<Map<String, Object>> generateDossier(@PathVariable String sessionId) {
        var session = callSessionManager.getSession(sessionId);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(Map.of(
                "sessionId", sessionId,
                "riskScore", session.getRollingRiskScore(),
                "interventionLevel", session.getCurrentInterventionLevel().name(),
                "summary", "Forensic dossier generated for suspicious call pattern with channel and transaction signal anomalies."
        ));
    }
}

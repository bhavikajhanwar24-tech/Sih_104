package com.sentinelvoice.controller;

import com.sentinelvoice.service.CallSessionManager;
import com.sentinelvoice.service.ForensicDossierService;
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
    private final ForensicDossierService forensicDossierService;

    public ForensicReportController(
            CallSessionManager callSessionManager,
            ForensicDossierService forensicDossierService
    ) {
        this.callSessionManager = callSessionManager;
        this.forensicDossierService = forensicDossierService;
    }

    @GetMapping("/{sessionId}/dossier")
    public ResponseEntity<Map<String, Object>> generateDossier(@PathVariable String sessionId) {
        return callSessionManager.getSession(sessionId)
                .map(session -> ResponseEntity.ok(forensicDossierService.buildDossier(
                        session.getSessionId(),
                        session.getSmoothedRisk(),
                        session.getCurrentLevel().name()
                )))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}

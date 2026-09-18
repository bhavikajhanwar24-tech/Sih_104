package com.sentinelvoice.controller;

import com.sentinelvoice.forensics.ForensicDossierService;
import com.sentinelvoice.forensics.model.ForensicDossier;
import com.sentinelvoice.service.CallSessionManager;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Forensic evidence package API — JSON dossier and court-ready PDF (Context §7.3 / §13).
 */
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
    public ResponseEntity<ForensicDossier> dossierJson(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "analyst") String generatedBy
    ) {
        if (callSessionManager.getSession(sessionId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(forensicDossierService.assembleJson(sessionId, generatedBy));
    }

    @GetMapping(value = "/{sessionId}/dossier.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> dossierPdf(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "analyst") String generatedBy
    ) {
        if (callSessionManager.getSession(sessionId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        byte[] pdf = forensicDossierService.renderPdf(sessionId, generatedBy);
        String pdfSha = ForensicDossierService.documentSha256(pdf);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"sentinelvoice-dossier-" + sessionId + ".pdf\"")
                .header("X-Document-SHA256", pdfSha)
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}
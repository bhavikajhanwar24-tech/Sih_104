package com.sentinelvoice.explain;

import com.sentinelvoice.audit.AuditEventType;
import com.sentinelvoice.audit.AuditWriteDispatcher;
import com.sentinelvoice.forensics.ForensicDossierService;
import com.sentinelvoice.forensics.model.ForensicDossier;
import com.sentinelvoice.security.TenantContext;
import com.sentinelvoice.telephony.CallSessionRepository;
import com.sentinelvoice.telephony.TelephonyModels;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * F12 — Call History + per-session explainability + forensic dossier.
 */
@RestController
@RequestMapping("/api/v2/sessions")
public class SessionsController {

    private final CallSessionRepository callSessionRepository;
    private final SessionExplainService sessionExplainService;
    private final ForensicDossierService forensicDossierService;
    private final AuditWriteDispatcher auditWriteDispatcher;

    private static final Set<String> REVIEW_STATUSES = Set.of(
            "UNREVIEWED", "FALSE_POSITIVE", "CONFIRMED_FRAUD"
    );

    public SessionsController(
            CallSessionRepository callSessionRepository,
            SessionExplainService sessionExplainService,
            ForensicDossierService forensicDossierService,
            AuditWriteDispatcher auditWriteDispatcher
    ) {
        this.callSessionRepository = callSessionRepository;
        this.sessionExplainService = sessionExplainService;
        this.forensicDossierService = forensicDossierService;
        this.auditWriteDispatcher = auditWriteDispatcher;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','ANALYST','AUDITOR')")
    public Map<String, Object> list(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String minLevel,
            @RequestParam(required = false) UUID employeeId,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) Boolean reviewed,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset
    ) {
        UUID tenantId = TenantContext.require().tenantId();
        List<TelephonyModels.CallSessionHistoryItem> rows = callSessionRepository.listFiltered(
                tenantId, from, to, minLevel, employeeId, outcome, reviewed, limit, offset
        );
        List<Map<String, Object>> items = rows.stream().map(row -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("schemaVersion", "2");
            m.put("id", row.id().toString());
            m.put("tenantId", row.tenantId().toString());
            m.put("svSessionUuid", row.svSessionUuid() == null ? null : row.svSessionUuid().toString());
            m.put("startedAt", row.startedAt() == null ? null : row.startedAt().toString());
            m.put("endedAt", row.endedAt() == null ? null : row.endedAt().toString());
            m.put("durationMs", row.durationMs());
            m.put("direction", row.direction());
            m.put("callerNumber", row.callerNumber());
            m.put("calleeNumber", row.calleeNumber());
            m.put("callerEmployeeId", row.callerEmployeeId() == null ? null : row.callerEmployeeId().toString());
            m.put("calleeEmployeeId", row.calleeEmployeeId() == null ? null : row.calleeEmployeeId().toString());
            m.put("callerName", displayName(row.callerName(), row.callerTitle(), row.callerNumber()));
            m.put("calleeName", displayName(row.calleeName(), row.calleeTitle(), row.calleeNumber()));
            m.put("peakScore", row.peakScore());
            m.put("peakLevel", row.peakLevel());
            m.put("finalOutcome", row.finalOutcome());
            m.put("reviewStatus", row.reviewStatus() == null ? "UNREVIEWED" : row.reviewStatus());
            return m;
        }).toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemaVersion", "2");
        body.put("tenantId", tenantId.toString());
        body.put("items", items);
        body.put("limit", Math.max(1, Math.min(limit, 200)));
        body.put("offset", Math.max(0, offset));
        return body;
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','ANALYST','AUDITOR')")
    public Map<String, Object> detail(
            @PathVariable String id,
            @RequestParam(defaultValue = "en") String lang
    ) {
        UUID tenantId = TenantContext.require().tenantId();
        Locale locale = "hi".equalsIgnoreCase(lang) ? Locale.forLanguageTag("hi") : Locale.ENGLISH;
        return sessionExplainService.explainPayload(tenantId, id, locale);
    }

    @GetMapping("/{id}/ticks")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','ANALYST','AUDITOR')")
    public Map<String, Object> ticks(@PathVariable String id) {
        UUID tenantId = TenantContext.require().tenantId();
        TelephonyModels.CallSessionDetail cs = sessionExplainService.requireSession(tenantId, id);
        List<Map<String, Object>> ticks = sessionExplainService.listTicks(tenantId, cs.svSessionUuid());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemaVersion", "2");
        body.put("tenantId", tenantId.toString());
        body.put("sessionId", cs.svSessionUuid().toString());
        body.put("items", ticks);
        return body;
    }

    /**
     * Analyst disposition: Mark FP / Confirmed fraud (stored on call_sessions.review_status).
     */
    @PostMapping("/{id}/review")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','ANALYST')")
    public ResponseEntity<Map<String, Object>> review(
            @PathVariable String id,
            @RequestBody Map<String, Object> body
    ) {
        UUID tenantId = TenantContext.require().tenantId();
        UUID userId = TenantContext.require().userId();
        TelephonyModels.CallSessionDetail cs = callSessionRepository
                .findDetailByIdOrSvSession(tenantId, id)
                .orElse(null);
        if (cs == null) {
            return ResponseEntity.notFound().build();
        }
        String status = body == null || body.get("status") == null
                ? ""
                : String.valueOf(body.get("status")).trim().toUpperCase(Locale.ROOT);
        if (!REVIEW_STATUSES.contains(status)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "INVALID_STATUS",
                    "message", "status must be UNREVIEWED, FALSE_POSITIVE, or CONFIRMED_FRAUD"
            ));
        }
        int updated = callSessionRepository.updateReviewStatus(tenantId, cs.id(), status, userId);
        if (updated == 0) {
            return ResponseEntity.notFound().build();
        }
        String sessionKey = cs.svSessionUuid() == null ? cs.id().toString() : cs.svSessionUuid().toString();
        auditWriteDispatcher.submit(sessionKey, AuditEventType.SESSION_REVIEWED, Map.of(
                "callSessionId", cs.id().toString(),
                "reviewStatus", status,
                "reviewedBy", userId == null ? "" : userId.toString(),
                "previousStatus", cs.reviewStatus() == null ? "UNREVIEWED" : cs.reviewStatus()
        ));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemaVersion", "2");
        out.put("id", cs.id().toString());
        out.put("reviewStatus", status);
        out.put("reviewedAt", Instant.now().toString());
        out.put("reviewedBy", userId == null ? null : userId.toString());
        return ResponseEntity.ok(out);
    }

    @GetMapping("/{id}/dossier")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','ANALYST','AUDITOR')")
    public ForensicDossier dossierJson(
            @PathVariable String id,
            @RequestParam(defaultValue = "analyst") String generatedBy
    ) {
        String by = resolveGeneratedBy(generatedBy);
        return forensicDossierService.assembleJson(id, by);
    }

    @GetMapping(value = "/{id}/dossier.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','ANALYST','AUDITOR')")
    public ResponseEntity<byte[]> dossierPdf(
            @PathVariable String id,
            @RequestParam(defaultValue = "analyst") String generatedBy
    ) {
        String by = resolveGeneratedBy(generatedBy);
        byte[] pdf = forensicDossierService.renderPdf(id, by);
        String pdfSha = ForensicDossierService.documentSha256(pdf);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"sentinelvoice-dossier-" + id + ".pdf\"")
                .header("X-Document-SHA256", pdfSha)
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    private static String resolveGeneratedBy(String generatedBy) {
        if (generatedBy != null && !generatedBy.isBlank()) {
            return generatedBy.trim();
        }
        TenantContext ctx = TenantContext.require();
        if (ctx.email() != null && !ctx.email().isBlank()) {
            return ctx.email();
        }
        return ctx.userId() == null ? "analyst" : ctx.userId().toString();
    }

    private static String displayName(String fullName, String title, String fallbackNumber) {
        if (fullName != null && !fullName.isBlank()) {
            if (title != null && !title.isBlank()) {
                return fullName + " (" + title + ")";
            }
            return fullName;
        }
        return fallbackNumber == null || fallbackNumber.isBlank() ? "Unknown" : fallbackNumber;
    }
}

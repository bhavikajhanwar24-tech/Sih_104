package com.sentinelvoice.analytics;

import com.sentinelvoice.security.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * F16 — analytics + session labelling APIs.
 */
@RestController
@RequestMapping("/api/v2")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/sessions/{id}/label")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','ANALYST','SUPERVISOR','AUDITOR')")
    public Map<String, Object> getLabel(@PathVariable String id) {
        UUID tenantId = TenantContext.require().tenantId();
        return analyticsService.getLabel(tenantId, id);
    }

    @PostMapping("/sessions/{id}/label")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','ANALYST','SUPERVISOR')")
    public Map<String, Object> putLabel(
            @PathVariable String id,
            @RequestBody Map<String, Object> body
    ) {
        UUID tenantId = TenantContext.require().tenantId();
        UUID userId = TenantContext.require().userId();
        String label = body == null || body.get("label") == null ? null : String.valueOf(body.get("label"));
        String note = body == null || body.get("note") == null ? null : String.valueOf(body.get("note"));
        return analyticsService.upsertLabel(tenantId, userId, id, label, note, true);
    }

    @GetMapping("/analytics/overview")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','POLICY_APPROVER','ANALYST','SUPERVISOR','AUDITOR')")
    public Map<String, Object> overview(@RequestParam(defaultValue = "30") int days) {
        return analyticsService.overview(days);
    }

    @GetMapping("/analytics/rules")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','POLICY_APPROVER','ANALYST','SUPERVISOR','AUDITOR')")
    public Map<String, Object> rules() {
        return analyticsService.ruleQuality();
    }

    @GetMapping("/analytics/fairness")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','POLICY_APPROVER','ANALYST','SUPERVISOR','AUDITOR')")
    public Map<String, Object> fairness() {
        return analyticsService.fairnessReport();
    }

    @GetMapping("/analytics/threshold-suggestion")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','POLICY_APPROVER','ANALYST','SUPERVISOR','AUDITOR')")
    public Map<String, Object> thresholdSuggestion() {
        return analyticsService.thresholdSuggestion();
    }

    @PostMapping("/analytics/threshold-suggestion/draft")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN')")
    public Map<String, Object> createDraft(@RequestBody(required = false) Map<String, Object> body) {
        Double enter = null;
        if (body != null && body.get("l3Enter") instanceof Number n) {
            enter = n.doubleValue();
        } else if (body != null && body.get("l3Enter") != null) {
            try {
                enter = Double.parseDouble(String.valueOf(body.get("l3Enter")));
            } catch (NumberFormatException ignored) {
                enter = null;
            }
        }
        return analyticsService.createDraftFromSuggestion(enter);
    }

    @ExceptionHandler(AnalyticsException.class)
    public ResponseEntity<Map<String, Object>> handle(AnalyticsException ex) {
        HttpStatus status = switch (ex.getCode()) {
            case "NOT_FOUND" -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.BAD_REQUEST;
        };
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", ex.getCode());
        out.put("message", ex.getMessage());
        return ResponseEntity.status(status).body(out);
    }
}

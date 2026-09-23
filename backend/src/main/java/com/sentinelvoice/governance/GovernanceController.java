package com.sentinelvoice.governance;

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

import java.util.Map;

@RestController
@RequestMapping("/api/v2/governance")
public class GovernanceController {

    private final DashboardService dashboardService;
    private final ApprovalsService approvalsService;
    private final ChangeHistoryService changeHistoryService;
    private final EmergencyModeService emergencyModeService;

    public GovernanceController(
            DashboardService dashboardService,
            ApprovalsService approvalsService,
            ChangeHistoryService changeHistoryService,
            EmergencyModeService emergencyModeService
    ) {
        this.dashboardService = dashboardService;
        this.approvalsService = approvalsService;
        this.changeHistoryService = changeHistoryService;
        this.emergencyModeService = emergencyModeService;
    }

    @GetMapping("/dashboard")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','POLICY_APPROVER','ANALYST','SUPERVISOR','AUDITOR')")
    public Map<String, Object> dashboard() {
        return dashboardService.snapshot();
    }

    @GetMapping("/approvals")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','POLICY_APPROVER')")
    public Map<String, Object> approvals() {
        return approvalsService.list();
    }

    @GetMapping("/approvals/{area}/{id}")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','POLICY_APPROVER')")
    public Map<String, Object> approvalDetail(@PathVariable String area, @PathVariable java.util.UUID id) {
        return approvalsService.detail(area, id);
    }

    @PostMapping("/approvals/{area}/{id}/decide")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','POLICY_APPROVER')")
    public Map<String, Object> decide(
            @PathVariable String area,
            @PathVariable java.util.UUID id,
            @RequestBody Map<String, Object> body
    ) {
        boolean approve = Boolean.TRUE.equals(body.get("approve"))
                || "approve".equalsIgnoreCase(String.valueOf(body.get("decision")));
        String comment = body.get("comment") == null ? "" : String.valueOf(body.get("comment"));
        return approvalsService.decide(area, id, approve, comment);
    }

    @GetMapping("/changes")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','POLICY_APPROVER','AUDITOR')")
    public Map<String, Object> changes(
            @RequestParam(required = false) String area,
            @RequestParam(defaultValue = "80") int limit
    ) {
        return changeHistoryService.list(area, limit);
    }

    @GetMapping("/changes/export.csv")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','POLICY_APPROVER','AUDITOR')")
    public ResponseEntity<byte[]> exportChanges(@RequestParam(required = false) String area) {
        byte[] csv = changeHistoryService.exportCsv(area);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"change-history.csv\"")
                .contentType(new MediaType("text", "csv"))
                .body(csv);
    }

    @GetMapping("/emergency")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','ANALYST','SUPERVISOR','AUDITOR','POLICY_APPROVER')")
    public Map<String, Object> emergencyStatus() {
        return emergencyModeService.status();
    }

    @PostMapping("/emergency/enable")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public Map<String, Object> enableEmergency(@RequestBody Map<String, Object> body) {
        String mode = body.get("mode") == null ? null : String.valueOf(body.get("mode"));
        String password = body.get("password") == null ? null : String.valueOf(body.get("password"));
        Integer ttl = null;
        if (body.get("ttlMinutes") instanceof Number n) {
            ttl = n.intValue();
        } else if (body.get("ttlMinutes") != null) {
            try {
                ttl = Integer.parseInt(String.valueOf(body.get("ttlMinutes")));
            } catch (NumberFormatException ignored) {
                ttl = null;
            }
        }
        return emergencyModeService.enable(mode, password, ttl);
    }

    @PostMapping("/emergency/disable")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public Map<String, Object> disableEmergency(@RequestBody Map<String, Object> body) {
        String password = body.get("password") == null ? null : String.valueOf(body.get("password"));
        return emergencyModeService.disable(password);
    }
}

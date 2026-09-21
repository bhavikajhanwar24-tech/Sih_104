package com.sentinelvoice.response.execute;

import com.sentinelvoice.security.TenantContext;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v2/response/sessions")
public class SessionActionController {

    private final SessionActionRepository repository;
    private final PlanRunner planRunner;

    public SessionActionController(SessionActionRepository repository, PlanRunner planRunner) {
        this.repository = repository;
        this.planRunner = planRunner;
    }

    @GetMapping("/{sessionId}/actions")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','POLICY_APPROVER','AUDITOR','ANALYST')")
    public List<Map<String, Object>> list(@PathVariable String sessionId) {
        UUID tenantId = TenantContext.require().tenantId();
        return repository.listForSession(tenantId, sessionId);
    }

    @PostMapping("/{sessionId}/actions/{actionId}/override")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','ANALYST')")
    public ResponseEntity<Map<String, Object>> override(
            @PathVariable String sessionId,
            @PathVariable UUID actionId,
            @RequestBody Map<String, Object> body
    ) {
        String reason = body.get("reason") == null ? "" : String.valueOf(body.get("reason"));
        String actorId = TenantContext.require().userId() == null
                ? null
                : TenantContext.require().userId().toString();
        try {
            planRunner.overrideAction(sessionId, actionId, reason, actorId);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "OVERRIDDEN");
            out.put("actionId", actionId.toString());
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "OVERRIDE_REJECTED",
                    "message", e.getMessage() == null ? "" : e.getMessage()
            ));
        }
    }
}

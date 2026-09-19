package com.sentinelvoice.policy;

import com.sentinelvoice.policy.compile.PolicyCompileException;
import com.sentinelvoice.policy.compile.PolicyCompileService;
import com.sentinelvoice.policy.dsl.FactCatalogue;
import com.sentinelvoice.policy.sets.PolicySetService;
import com.sentinelvoice.security.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * F6 — compilations, fact catalogue, policy sets / rules / approval.
 */
@RestController
@RequestMapping("/api/v2/policy")
public class PolicyCompileController {

    private final PolicyCompileService compileService;
    private final PolicySetService setService;

    public PolicyCompileController(PolicyCompileService compileService, PolicySetService setService) {
        this.compileService = compileService;
        this.setService = setService;
    }

    @GetMapping("/fact-catalogue")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','POLICY_APPROVER','AUDITOR')")
    public Map<String, Object> factCatalogue() {
        return FactCatalogue.toApiBody();
    }

    @PostMapping("/compilations")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public Map<String, Object> startCompilation(@RequestBody Map<String, Object> body) {
        TenantContext ctx = TenantContext.require();
        @SuppressWarnings("unchecked")
        List<String> rawIds = (List<String>) body.getOrDefault("documentIds", List.of());
        List<UUID> docIds = rawIds.stream().map(UUID::fromString).toList();
        String mode = body.get("mode") == null ? "FULL" : String.valueOf(body.get("mode"));
        return compileService.start(ctx.tenantId(), ctx.userId(), docIds, mode);
    }

    @GetMapping("/compilations")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','POLICY_APPROVER','AUDITOR')")
    public Map<String, Object> listCompilations() {
        TenantContext ctx = TenantContext.require();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", compileService.list(ctx.tenantId()));
        return body;
    }

    @GetMapping("/compilations/{id}")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','POLICY_APPROVER','AUDITOR')")
    public Map<String, Object> getCompilation(@PathVariable UUID id) {
        TenantContext ctx = TenantContext.require();
        return compileService.get(ctx.tenantId(), id);
    }

    @PostMapping("/compilations/{id}/resume")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public Map<String, Object> resume(@PathVariable UUID id) {
        TenantContext ctx = TenantContext.require();
        return compileService.resume(ctx.tenantId(), ctx.userId(), id);
    }

    @PostMapping("/compilations/{id}/rerun-failed")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public Map<String, Object> rerunFailed(@PathVariable UUID id) {
        TenantContext ctx = TenantContext.require();
        return compileService.rerunFailed(ctx.tenantId(), ctx.userId(), id);
    }

    @PostMapping("/compilations/{id}/cancel")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public Map<String, Object> cancel(@PathVariable UUID id) {
        TenantContext ctx = TenantContext.require();
        return compileService.cancel(ctx.tenantId(), ctx.userId(), id);
    }

    @GetMapping("/compile-limits")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','POLICY_APPROVER','AUDITOR')")
    public Map<String, Object> compileLimits() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("maxDocumentsPerCompile", compileService.maxDocumentsPerCompile());
        return body;
    }

    @GetMapping("/sets")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','POLICY_APPROVER','AUDITOR')")
    public Map<String, Object> listSets() {
        TenantContext ctx = TenantContext.require();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", setService.listSets(ctx.tenantId()));
        return body;
    }

    @GetMapping("/sets/{id}")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','POLICY_APPROVER','AUDITOR')")
    public Map<String, Object> getSet(@PathVariable UUID id) {
        TenantContext ctx = TenantContext.require();
        return setService.getSet(ctx.tenantId(), id);
    }

    @GetMapping("/sets/{leftId}/diff/{rightId}")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','POLICY_APPROVER','AUDITOR')")
    public Map<String, Object> diff(@PathVariable UUID leftId, @PathVariable UUID rightId) {
        TenantContext ctx = TenantContext.require();
        return setService.diff(ctx.tenantId(), leftId, rightId);
    }

    @PostMapping("/sets/{id}/rules")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public Map<String, Object> createRule(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        TenantContext ctx = TenantContext.require();
        return setService.createManualRule(ctx.tenantId(), ctx.userId(), id, body);
    }

    @PatchMapping("/rules/{ruleId}")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public Map<String, Object> patchRule(@PathVariable UUID ruleId, @RequestBody Map<String, Object> body) {
        TenantContext ctx = TenantContext.require();
        return setService.updateRule(ctx.tenantId(), ctx.userId(), ruleId, body);
    }

    @PostMapping("/sets/{id}/submit")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public Map<String, Object> submit(@PathVariable UUID id) {
        TenantContext ctx = TenantContext.require();
        return setService.submit(ctx.tenantId(), ctx.userId(), id);
    }

    @DeleteMapping("/sets/{id}")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<Void> deleteDraft(@PathVariable UUID id) {
        TenantContext ctx = TenantContext.require();
        setService.deleteDraft(ctx.tenantId(), ctx.userId(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/sets/{id}/approve")
    @PreAuthorize("hasRole('POLICY_APPROVER')")
    public Map<String, Object> approve(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> body) {
        TenantContext ctx = TenantContext.require();
        String comment = body == null || body.get("comment") == null ? "" : String.valueOf(body.get("comment"));
        return setService.approve(ctx.tenantId(), ctx.userId(), id, comment);
    }

    @PostMapping("/sets/{id}/reject")
    @PreAuthorize("hasRole('POLICY_APPROVER')")
    public Map<String, Object> reject(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        TenantContext ctx = TenantContext.require();
        return setService.reject(ctx.tenantId(), ctx.userId(), id, String.valueOf(body.get("comment")));
    }

    @PostMapping("/sets/{id}/keywords")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public Map<String, Object> addKeyword(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        TenantContext ctx = TenantContext.require();
        setService.addKeyword(ctx.tenantId(), id, body);
        return setService.getSet(ctx.tenantId(), id);
    }

    @DeleteMapping("/keywords/{keywordId}")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<Void> deleteKeyword(@PathVariable UUID keywordId) {
        TenantContext ctx = TenantContext.require();
        setService.deleteKeyword(ctx.tenantId(), keywordId);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(PolicyCompileException.class)
    public ResponseEntity<Map<String, Object>> handle(PolicyCompileException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", ex.getCode());
        body.put("message", ex.getMessage());
        HttpStatus status = switch (ex.getCode()) {
            case "NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "SAME_USER", "BAD_STATE", "NOT_DRAFT", "NO_RULES", "COMMENT_REQUIRED",
                 "NO_DOCUMENTS", "NOT_READY", "BAD_MODE", "BUSY", "UNKNOWN_FACT",
                 "TOO_MANY_DOCUMENTS", "INVALID_EDIT", "HALLUCINATED_QUOTE" -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(body);
    }
}

package com.sentinelvoice.response;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v2/response")
public class ResponsePlanController {

    private final ResponsePlanService service;

    public ResponsePlanController(ResponsePlanService service) {
        this.service = service;
    }

    @GetMapping("/action-catalogue")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','POLICY_APPROVER','AUDITOR','ANALYST')")
    public List<Map<String, Object>> catalogue() {
        return ActionCatalogue.toMaps();
    }

    @GetMapping("/plans/active")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','POLICY_APPROVER','AUDITOR')")
    public Map<String, Object> active() {
        return service.getActive();
    }

    @GetMapping("/plans/history")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','POLICY_APPROVER','AUDITOR')")
    public List<Map<String, Object>> history() {
        return service.history();
    }

    @GetMapping("/plans/{id}")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','POLICY_APPROVER','AUDITOR')")
    public Map<String, Object> get(@PathVariable UUID id) {
        return service.getById(id);
    }

    @PostMapping("/plans/draft")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public Map<String, Object> createDraft(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> plan = body == null ? null : asMap(body.get("plan"));
        if (plan.isEmpty() && body != null && body.containsKey("levels")) {
            plan = body;
        }
        return service.createDraftFromActive(plan.isEmpty() ? null : plan);
    }

    @PutMapping("/plans/{id}")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public Map<String, Object> updateDraft(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        Map<String, Object> plan = asMap(body.get("plan"));
        if (plan.isEmpty() && body.containsKey("levels")) {
            plan = body;
        }
        return service.updateDraft(id, plan);
    }

    @PostMapping("/plans/{id}/submit")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public Map<String, Object> submit(@PathVariable UUID id) {
        return service.submit(id);
    }

    @PostMapping("/plans/{id}/approve")
    @PreAuthorize("hasRole('POLICY_APPROVER')")
    public Map<String, Object> approve(@PathVariable UUID id) {
        return service.approve(id);
    }

    @PostMapping("/plans/{id}/reject")
    @PreAuthorize("hasRole('POLICY_APPROVER')")
    public Map<String, Object> reject(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> body) {
        String comment = body == null || body.get("comment") == null
                ? ""
                : String.valueOf(body.get("comment"));
        return service.reject(id, comment);
    }

    @PostMapping("/plans/validate")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','POLICY_APPROVER','AUDITOR')")
    public Map<String, Object> validate(@RequestBody Map<String, Object> body) {
        Map<String, Object> plan = asMap(body.get("plan"));
        if (plan.isEmpty() && body.containsKey("levels")) {
            plan = body;
        }
        return service.validateOnly(plan);
    }

    @PostMapping("/plans/preview")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','POLICY_APPROVER','AUDITOR')")
    public Map<String, Object> preview(@RequestBody Map<String, Object> body) {
        Map<String, Object> plan = asMap(body.get("plan"));
        if (plan.isEmpty() && body.containsKey("levels")) {
            plan = body;
        }
        String level = body.get("level") == null ? "L3" : String.valueOf(body.get("level"));
        @SuppressWarnings("unchecked")
        Map<String, Object> facts = body.get("facts") instanceof Map<?, ?> m
                ? new LinkedHashMap<>((Map<String, Object>) m)
                : Map.of();
        return service.preview(plan, level, facts);
    }

    @ExceptionHandler(ResponsePlanException.class)
    public ResponseEntity<Map<String, Object>> handle(ResponsePlanException ex) {
        HttpStatus status = switch (ex.code()) {
            case "NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "SAME_USER", "VALIDATION_FAILED", "BAD_STATE", "COMMENT_REQUIRED" -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(Map.of(
                "error", ex.code(),
                "message", ex.getMessage() == null ? "" : ex.getMessage()
        ));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        if (o instanceof Map<?, ?> m) {
            return new LinkedHashMap<>((Map<String, Object>) m);
        }
        return new LinkedHashMap<>();
    }
}

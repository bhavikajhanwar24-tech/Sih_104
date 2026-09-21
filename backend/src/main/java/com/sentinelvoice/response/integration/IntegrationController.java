package com.sentinelvoice.response.integration;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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

@RestController
@RequestMapping("/api/v2/response/integrations")
public class IntegrationController {

    private final TenantIntegrationService service;

    public IntegrationController(TenantIntegrationService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','POLICY_APPROVER','AUDITOR')")
    public List<Map<String, Object>> list() {
        return service.list();
    }

    @GetMapping("/{kind}")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','POLICY_APPROVER','AUDITOR')")
    public Map<String, Object> get(@PathVariable String kind) {
        return service.get(kind.toUpperCase()).orElseGet(() -> {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("kind", kind.toUpperCase());
            empty.put("enabled", false);
            empty.put("config", Map.of());
            empty.put("hasSecrets", false);
            return empty;
        });
    }

    @PutMapping("/{kind}")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public Map<String, Object> upsert(@PathVariable String kind, @RequestBody Map<String, Object> body) {
        return service.upsert(kind.toUpperCase(), body);
    }

    @PostMapping("/{kind}/test")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<Map<String, Object>> test(@PathVariable String kind) {
        Map<String, Object> result = service.test(kind.toUpperCase());
        boolean ok = Boolean.TRUE.equals(result.get("lastTestOk"));
        return ResponseEntity.status(ok ? 200 : 502).body(result);
    }
}

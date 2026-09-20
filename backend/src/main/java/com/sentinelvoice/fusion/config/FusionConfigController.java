package com.sentinelvoice.fusion.config;

import com.sentinelvoice.fusion.engine.FusionRuntimeService;
import com.sentinelvoice.model.CallSession;
import com.sentinelvoice.model.TelemetryEntry;
import com.sentinelvoice.security.TenantContext;
import com.sentinelvoice.service.CallSessionManager;
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
@RequestMapping("/api/v2/fusion-configs")
public class FusionConfigController {

    private final FusionConfigService service;
    private final FusionConfigRepository repository;
    private final CallSessionManager callSessionManager;
    private final FusionRuntimeService fusionRuntimeService;
    private final ActiveFusionConfigCache cache;

    public FusionConfigController(
            FusionConfigService service,
            FusionConfigRepository repository,
            CallSessionManager callSessionManager,
            FusionRuntimeService fusionRuntimeService,
            ActiveFusionConfigCache cache
    ) {
        this.service = service;
        this.repository = repository;
        this.callSessionManager = callSessionManager;
        this.fusionRuntimeService = fusionRuntimeService;
        this.cache = cache;
    }

    @GetMapping("/active")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','POLICY_APPROVER','AUDITOR')")
    public Map<String, Object> active() {
        return service.getActive();
    }

    @GetMapping("/history")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','POLICY_APPROVER','AUDITOR')")
    public List<Map<String, Object>> history() {
        return service.history();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','POLICY_APPROVER','AUDITOR')")
    public Map<String, Object> get(@PathVariable UUID id) {
        return service.getById(id);
    }

    @PostMapping("/draft")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public Map<String, Object> createDraft(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> config = body == null ? null : asMap(body.get("config"));
        if (config.isEmpty() && body != null && body.containsKey("weights")) {
            config = body;
        }
        return service.createDraftFromActive(config.isEmpty() ? null : config);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public Map<String, Object> updateDraft(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        Map<String, Object> config = asMap(body.get("config"));
        if (config.isEmpty() && body.containsKey("weights")) {
            config = body;
        }
        return service.updateDraft(id, config);
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public Map<String, Object> submit(@PathVariable UUID id) {
        return service.submit(id);
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('POLICY_APPROVER')")
    public Map<String, Object> approve(@PathVariable UUID id) {
        return service.approve(id);
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('POLICY_APPROVER')")
    public Map<String, Object> reject(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> body) {
        String comment = body == null || body.get("comment") == null
                ? ""
                : String.valueOf(body.get("comment"));
        return service.reject(id, comment);
    }

    /**
     * What-if replay: apply a candidate config to stored session telemetry family scores.
     * Body: {@code {config?, configId?, sessionId?}}.
     */
    @PostMapping("/what-if")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','POLICY_APPROVER','AUDITOR')")
    public Map<String, Object> whatIf(@RequestBody Map<String, Object> body) {
        UUID tenantId = TenantContext.require().tenantId();
        FusionConfigDocument doc;
        Integer version = null;
        if (body.get("config") instanceof Map<?, ?>) {
            doc = FusionConfigDocument.parse(asMap(body.get("config")));
            List<String> violations = FusionConfigValidator.validate(doc);
            if (!violations.isEmpty()) {
                throw new FusionConfigException("VALIDATION_FAILED", String.join("; ", violations));
            }
        } else if (body.get("configId") != null) {
            UUID configId = UUID.fromString(String.valueOf(body.get("configId")));
            Map<String, Object> row = repository.findById(tenantId, configId)
                    .orElseThrow(() -> new FusionConfigException("NOT_FOUND", "Fusion config not found"));
            doc = FusionConfigDocument.parse(asMap(row.get("config")));
            version = ((Number) row.get("version")).intValue();
        } else {
            ActiveFusionConfigCache.CachedFusionConfig active = cache.get(tenantId)
                    .orElseThrow(() -> new FusionConfigException("NOT_FOUND", "No ACTIVE fusion config"));
            doc = active.document();
            version = active.version();
        }

        if (body.get("sessionId") == null || String.valueOf(body.get("sessionId")).isBlank()) {
            throw new FusionConfigException(
                    "SESSION_REQUIRED",
                    "sessionId is required for what-if replay"
            );
        }
        String sessionId = String.valueOf(body.get("sessionId"));
        CallSession session = callSessionManager.requireSessionForTenant(tenantId, sessionId);
        List<TelemetryEntry> telemetry = session.getTelemetryHistory().snapshot();
        if (telemetry.isEmpty()) {
            throw new FusionConfigException(
                    "NO_TELEMETRY",
                    "No session telemetry available to replay. Start a call and capture frames first."
            );
        }
        List<Map<String, Object>> ticks = fusionRuntimeService.whatIfReplay(doc, version, telemetry);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", sessionId);
        out.put("fusionConfigVersion", version);
        out.put("tickCount", ticks.size());
        out.put("ticks", ticks);
        return out;
    }

    @ExceptionHandler(FusionConfigException.class)
    public ResponseEntity<Map<String, Object>> handle(FusionConfigException ex) {
        HttpStatus status = switch (ex.getCode()) {
            case "NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "SAME_USER", "BAD_STATE", "VALIDATION_FAILED", "COMMENT_REQUIRED",
                    "SESSION_REQUIRED", "NO_TELEMETRY", "INVALID_CONFIG" -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.BAD_REQUEST;
        };
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", ex.getCode());
        body.put("message", ex.getMessage());
        return ResponseEntity.status(status).body(body);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        if (o instanceof Map<?, ?> m) {
            return new LinkedHashMap<>((Map<String, Object>) m);
        }
        return new LinkedHashMap<>();
    }
}

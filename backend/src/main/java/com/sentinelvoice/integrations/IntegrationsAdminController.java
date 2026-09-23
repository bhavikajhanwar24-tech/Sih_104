package com.sentinelvoice.integrations;

import com.sentinelvoice.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * F17 management APIs for API keys + webhooks (JWT / tenant admin).
 */
@RestController
@RequestMapping("/api/v2/integrations")
@Tag(name = "Integrations Admin", description = "Manage API keys and webhook endpoints")
public class IntegrationsAdminController {

    private final ApiKeyService apiKeyService;
    private final WebhookService webhookService;

    public IntegrationsAdminController(ApiKeyService apiKeyService, WebhookService webhookService) {
        this.apiKeyService = apiKeyService;
        this.webhookService = webhookService;
    }

    @GetMapping("/api-keys")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @Operation(summary = "List API keys (secrets never returned)")
    public Map<String, Object> listKeys() {
        return Map.of("items", apiKeyService.list(TenantContext.require().tenantId()));
    }

    @PostMapping("/api-keys")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @Operation(summary = "Create API key — secret shown once")
    public Map<String, Object> createKey(@RequestBody Map<String, Object> body) {
        TenantContext ctx = TenantContext.require();
        @SuppressWarnings("unchecked")
        List<String> scopes = body.get("scopes") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.of();
        Instant expires = null;
        if (body.get("expiresAt") != null) {
            expires = Instant.parse(String.valueOf(body.get("expiresAt")));
        }
        Integer rps = body.get("rateLimitRps") instanceof Number n ? n.intValue() : null;
        try {
            return apiKeyService.create(
                    ctx.tenantId(),
                    ctx.userId(),
                    body.get("name") == null ? null : String.valueOf(body.get("name")),
                    scopes,
                    expires,
                    rps
            );
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    @DeleteMapping("/api-keys/{id}")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @Operation(summary = "Revoke an API key")
    public Map<String, Object> revokeKey(@PathVariable UUID id) {
        TenantContext ctx = TenantContext.require();
        try {
            apiKeyService.revoke(ctx.tenantId(), ctx.userId(), id);
            return Map.of("id", id.toString(), "status", "REVOKED");
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        }
    }

    @GetMapping("/webhooks")
    @PreAuthorize("hasRole('TENANT_ADMIN') or hasAuthority('SCOPE_webhooks:manage')")
    public Map<String, Object> listWebhooks() {
        return Map.of("items", webhookService.listEndpoints(TenantContext.require().tenantId()));
    }

    @PostMapping("/webhooks")
    @PreAuthorize("hasRole('TENANT_ADMIN') or hasAuthority('SCOPE_webhooks:manage')")
    public Map<String, Object> createWebhook(@RequestBody Map<String, Object> body) {
        TenantContext ctx = TenantContext.require();
        @SuppressWarnings("unchecked")
        List<String> events = body.get("events") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.of();
        try {
            return webhookService.createEndpoint(
                    ctx.tenantId(),
                    ctx.userId(),
                    body.get("url") == null ? null : String.valueOf(body.get("url")),
                    events,
                    body.get("description") == null ? null : String.valueOf(body.get("description"))
            );
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    @DeleteMapping("/webhooks/{id}")
    @PreAuthorize("hasRole('TENANT_ADMIN') or hasAuthority('SCOPE_webhooks:manage')")
    public Map<String, Object> disableWebhook(@PathVariable UUID id) {
        TenantContext ctx = TenantContext.require();
        try {
            webhookService.disableEndpoint(ctx.tenantId(), ctx.userId(), id);
            return Map.of("id", id.toString(), "status", "DISABLED");
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        }
    }

    @PostMapping("/webhooks/{id}/test")
    @PreAuthorize("hasRole('TENANT_ADMIN') or hasAuthority('SCOPE_webhooks:manage')")
    public Map<String, Object> testWebhook(@PathVariable UUID id) {
        return webhookService.enqueueTest(TenantContext.require().tenantId(), id);
    }

    @GetMapping("/webhooks/deliveries")
    @PreAuthorize("hasRole('TENANT_ADMIN') or hasAuthority('SCOPE_webhooks:manage')")
    public Map<String, Object> deliveries(
            @RequestParam(required = false) UUID endpointId,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return Map.of(
                "items",
                webhookService.listDeliveries(TenantContext.require().tenantId(), endpointId, limit)
        );
    }

    @GetMapping("/scopes")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public Map<String, Object> scopes() {
        return Map.of("scopes", ApiKeyScopes.ALL, "webhookEvents", WebhookService.EVENT_TYPES);
    }
}

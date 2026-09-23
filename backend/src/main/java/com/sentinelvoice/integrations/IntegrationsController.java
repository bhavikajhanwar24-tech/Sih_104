package com.sentinelvoice.integrations;

import com.sentinelvoice.model.CallSession;
import com.sentinelvoice.security.TenantContext;
import com.sentinelvoice.service.CallSessionManager;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * F17 public inbound integration APIs (API-key authenticated).
 */
@RestController
@RequestMapping("/api/v2/integrations")
@Tag(name = "Integrations", description = "Public REST surface for core systems / HRMS / SIEM")
@SecurityRequirement(name = "ApiKey")
@SecurityRequirement(name = "bearerAuth")
public class IntegrationsController {

    private final IntegrationTransactionService transactionService;
    private final IntegrationInboundService inboundService;
    private final CallSessionManager callSessionManager;

    public IntegrationsController(
            IntegrationTransactionService transactionService,
            IntegrationInboundService inboundService,
            CallSessionManager callSessionManager
    ) {
        this.transactionService = transactionService;
        this.inboundService = inboundService;
        this.callSessionManager = callSessionManager;
    }

    @PostMapping("/transactions/request")
    @Operation(summary = "Pre-transaction gate check (ALLOW | CHALLENGE | BLOCK)")
    @PreAuthorize("hasAuthority('SCOPE_transactions:write') or hasRole('TENANT_ADMIN')")
    public Map<String, Object> transactionRequest(@RequestBody Map<String, Object> body) {
        UUID tenantId = TenantContext.require().tenantId();
        try {
            return transactionService.request(
                    tenantId,
                    apiKeyIdOrNull(),
                    str(body.get("sessionId")),
                    str(body.get("callReference")),
                    str(body.get("actionType")),
                    asDecimal(body.get("amountInr")),
                    str(body.get("beneficiaryRef")),
                    str(body.get("requesterEmployeeRef")),
                    str(body.get("channel"))
            );
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    @PostMapping("/events/cross-channel")
    @Operation(summary = "Ingest a cross-channel event (EMAIL|SMS|CHAT|LOGIN|…)")
    @PreAuthorize("hasAuthority('SCOPE_events:write') or hasRole('TENANT_ADMIN')")
    public Map<String, Object> crossChannel(@RequestBody Map<String, Object> body) {
        UUID tenantId = TenantContext.require().tenantId();
        Instant occurred = null;
        if (body.get("occurredAt") != null) {
            occurred = Instant.parse(String.valueOf(body.get("occurredAt")));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> attrs = body.get("attributes") instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : Map.of();
        try {
            return inboundService.ingestCrossChannel(
                    tenantId,
                    apiKeyIdOrNull(),
                    str(body.get("type")),
                    str(body.get("identityRef")),
                    occurred,
                    attrs
            );
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    @PostMapping("/directory/sync")
    @Operation(summary = "Bulk upsert employees from HRMS/AD (idempotent)")
    @PreAuthorize("hasAuthority('SCOPE_directory:sync') or hasRole('TENANT_ADMIN')")
    public Map<String, Object> directorySync(@RequestBody Map<String, Object> body) {
        UUID tenantId = TenantContext.require().tenantId();
        boolean dryRun = Boolean.TRUE.equals(body.get("dryRun")) || "true".equalsIgnoreCase(str(body.get("dryRun")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> employees = body.get("employees") instanceof List<?> list
                ? (List<Map<String, Object>>) list
                : List.of();
        return inboundService.directorySync(
                tenantId,
                TenantContext.require().userId(),
                apiKeyIdOrNull(),
                employees,
                dryRun
        );
    }

    @GetMapping("/sessions/{id}/risk")
    @Operation(summary = "Current risk level / score / reasons for a session")
    @PreAuthorize("hasAuthority('SCOPE_risk:read') or hasAuthority('SCOPE_sessions:read') or hasRole('TENANT_ADMIN')")
    public Map<String, Object> sessionRisk(@PathVariable("id") String id) {
        UUID tenantId = TenantContext.require().tenantId();
        CallSession session = callSessionManager.requireSessionForTenant(tenantId, id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", session.getSessionId());
        out.put("level", session.getCurrentLevel() == null ? null : session.getCurrentLevel().name());
        out.put("smoothedRisk", session.getSmoothedRisk());
        out.put("callerId", session.getCallerId());
        out.put("calleeId", session.getCalleeId());
        out.put("linguisticSource", session.getLastLinguisticSource());
        out.put("reasons", List.of());
        return out;
    }

    @GetMapping("/sessions")
    @Operation(summary = "List sessions (active=true for in-memory live calls)")
    @PreAuthorize("hasAuthority('SCOPE_sessions:read') or hasRole('TENANT_ADMIN')")
    public Map<String, Object> sessions(@RequestParam(defaultValue = "true") boolean active) {
        UUID tenantId = TenantContext.require().tenantId();
        List<Map<String, Object>> items = callSessionManager.listSessionsForTenant(tenantId).stream()
                .map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("sessionId", s.getSessionId());
                    m.put("level", s.getCurrentLevel() == null ? null : s.getCurrentLevel().name());
                    m.put("smoothedRisk", s.getSmoothedRisk());
                    m.put("callerId", s.getCallerId());
                    m.put("calleeId", s.getCalleeId());
                    m.put("createdAt", s.getCreatedAt() == null ? null : s.getCreatedAt().toString());
                    return m;
                })
                .toList();
        return Map.of("active", active, "items", items, "count", items.size());
    }

    private static UUID apiKeyIdOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof ApiKeyPrincipal p) {
            return p.keyId();
        }
        return null;
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private static BigDecimal asDecimal(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof BigDecimal bd) {
            return bd;
        }
        if (o instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        try {
            return new BigDecimal(String.valueOf(o));
        } catch (Exception ex) {
            return null;
        }
    }
}

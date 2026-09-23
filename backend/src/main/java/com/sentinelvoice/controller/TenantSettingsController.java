package com.sentinelvoice.controller;

import com.sentinelvoice.audit.AuditEventType;
import com.sentinelvoice.audit.AuditLedgerService;
import com.sentinelvoice.llm.LlmGatewayClient;
import com.sentinelvoice.security.TenantContext;
import com.sentinelvoice.tenant.TenantSettingsEntity;
import com.sentinelvoice.tenant.TenantSettingsRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v2/settings")
public class TenantSettingsController {

    private final TenantSettingsRepository settingsRepository;
    private final AuditLedgerService auditLedgerService;
    private final LlmGatewayClient llmGatewayClient;

    public TenantSettingsController(
            TenantSettingsRepository settingsRepository,
            AuditLedgerService auditLedgerService,
            LlmGatewayClient llmGatewayClient
    ) {
        this.settingsRepository = settingsRepository;
        this.auditLedgerService = auditLedgerService;
        this.llmGatewayClient = llmGatewayClient;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','AUDITOR','POLICY_APPROVER')")
    @Transactional(readOnly = true)
    public Map<String, Object> get() {
        TenantContext ctx = TenantContext.require();
        return toBody(requireSettings(ctx.tenantId()));
    }

    @PatchMapping
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @Transactional
    public Map<String, Object> patch(@RequestBody Map<String, Object> body) {
        TenantContext ctx = TenantContext.require();
        TenantSettingsEntity s = requireSettings(ctx.tenantId());
        if (body.containsKey("allowExternalLlm")) {
            s.setAllowExternalLlm(Boolean.TRUE.equals(body.get("allowExternalLlm"))
                    || "true".equalsIgnoreCase(String.valueOf(body.get("allowExternalLlm"))));
        }
        if (body.containsKey("llmFailPolicy") && body.get("llmFailPolicy") != null) {
            s.setLlmFailPolicy(String.valueOf(body.get("llmFailPolicy")));
        }
        if (body.containsKey("asrLanguages") && body.get("asrLanguages") != null) {
            s.setAsrLanguages(String.valueOf(body.get("asrLanguages")));
        }
        if (body.containsKey("consentNoticeText")) {
            s.setConsentNoticeText(body.get("consentNoticeText") == null
                    ? null : String.valueOf(body.get("consentNoticeText")));
        }
        if (body.containsKey("timezone") && body.get("timezone") != null) {
            s.setTimezone(String.valueOf(body.get("timezone")));
        }
        if (body.containsKey("retentionDays") && body.get("retentionDays") != null) {
            int days = body.get("retentionDays") instanceof Number n
                    ? n.intValue()
                    : Integer.parseInt(String.valueOf(body.get("retentionDays")));
            s.setRetentionDays(Math.max(7, Math.min(days, 365)));
        }
        if (body.containsKey("monitorOnlyTtlMinutes") && body.get("monitorOnlyTtlMinutes") != null) {
            int ttl = body.get("monitorOnlyTtlMinutes") instanceof Number n
                    ? n.intValue()
                    : Integer.parseInt(String.valueOf(body.get("monitorOnlyTtlMinutes")));
            s.setMonitorOnlyTtlMinutes(Math.max(5, Math.min(ttl, 24 * 60)));
        }
        Map<String, Object> extras = new LinkedHashMap<>(s.getExtras() == null ? Map.of() : s.getExtras());
        for (String key : List.of(
                "languages", "notificationRecipients", "allowedOrigins",
                "hideCallerNames", "organisationDisplayName"
        )) {
            if (body.containsKey(key)) {
                extras.put(key, body.get(key));
            }
        }
        s.setExtras(extras);
        s.setUpdatedAt(Instant.now());
        settingsRepository.save(s);
        auditLedgerService.append(
                ctx.tenantId(),
                null,
                AuditEventType.TENANT_SETTINGS_UPDATED,
                "USER",
                ctx.userId().toString(),
                Map.of(
                        "change", Map.of(
                                "area", "settings",
                                "after", Map.of(
                                        "allowExternalLlm", s.isAllowExternalLlm(),
                                        "llmFailPolicy", s.getLlmFailPolicy(),
                                        "timezone", s.getTimezone(),
                                        "retentionDays", s.getRetentionDays()
                                )
                        ),
                        "allowExternalLlm", s.isAllowExternalLlm(),
                        "llmFailPolicy", s.getLlmFailPolicy()
                )
        );
        return toBody(s);
    }

    @GetMapping("/llm-health")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','AUDITOR','POLICY_APPROVER')")
    public Map<String, Object> llmHealth() {
        return llmGatewayClient.health();
    }

    @PostMapping("/llm-selftest")
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public Map<String, Object> llmSelftest() {
        return llmGatewayClient.selftest();
    }

    private TenantSettingsEntity requireSettings(UUID tenantId) {
        return settingsRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalStateException("tenant settings missing"));
    }

    private static Map<String, Object> toBody(TenantSettingsEntity s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("retentionDays", s.getRetentionDays());
        m.put("allowExternalLlm", s.isAllowExternalLlm());
        m.put("llmFailPolicy", s.getLlmFailPolicy());
        m.put("asrLanguages", s.getAsrLanguages());
        m.put("consentNoticeText", s.getConsentNoticeText());
        m.put("maxConcurrentCalls", s.getMaxConcurrentCalls());
        m.put("timezone", s.getTimezone());
        m.put("monitorOnlyTtlMinutes", s.getMonitorOnlyTtlMinutes());
        m.put("emergencyMode", s.getEmergencyMode());
        m.put("emergencyModeExpiresAt",
                s.getEmergencyModeExpiresAt() == null ? null : s.getEmergencyModeExpiresAt().toString());
        m.put("extras", s.getExtras());
        Map<String, Object> extras = s.getExtras() == null ? Map.of() : s.getExtras();
        m.put("languages", extras.getOrDefault("languages", s.getAsrLanguages()));
        m.put("notificationRecipients", extras.getOrDefault("notificationRecipients", List.of()));
        m.put("allowedOrigins", extras.getOrDefault("allowedOrigins", List.of()));
        m.put("hideCallerNames", Boolean.TRUE.equals(extras.get("hideCallerNames")));
        m.put("organisationDisplayName", extras.get("organisationDisplayName"));
        m.put("apiKeysEntry", "/app/settings#api-keys");
        return m;
    }
}

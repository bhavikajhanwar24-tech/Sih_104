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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
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
        if (body.containsKey("consentNoticeText")) {
            s.setConsentNoticeText(body.get("consentNoticeText") == null
                    ? null : String.valueOf(body.get("consentNoticeText")));
        }
        s.setUpdatedAt(Instant.now());
        settingsRepository.save(s);
        auditLedgerService.append(
                ctx.tenantId(),
                null,
                AuditEventType.TENANT_SETTINGS_UPDATED,
                "USER",
                ctx.userId().toString(),
                Map.of(
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

    private TenantSettingsEntity requireSettings(UUID tenantId) {
        return settingsRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalStateException("tenant settings missing"));
    }

    private static Map<String, Object> toBody(TenantSettingsEntity s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("retentionDays", s.getRetentionDays());
        m.put("allowExternalLlm", s.isAllowExternalLlm());
        m.put("llmFailPolicy", s.getLlmFailPolicy());
        m.put("consentNoticeText", s.getConsentNoticeText());
        m.put("maxConcurrentCalls", s.getMaxConcurrentCalls());
        m.put("extras", s.getExtras());
        return m;
    }
}

package com.sentinelvoice.fusion.config;

import com.sentinelvoice.audit.AuditEventType;
import com.sentinelvoice.audit.AuditLedgerService;
import com.sentinelvoice.security.TenantContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class FusionConfigService {

    private final FusionConfigRepository repository;
    private final AuditLedgerService auditLedgerService;
    private final ApplicationEventPublisher events;

    public FusionConfigService(
            FusionConfigRepository repository,
            AuditLedgerService auditLedgerService,
            ApplicationEventPublisher events
    ) {
        this.repository = repository;
        this.auditLedgerService = auditLedgerService;
        this.events = events;
    }

    public Map<String, Object> getActive() {
        UUID tenantId = TenantContext.require().tenantId();
        return repository.findActive(tenantId)
                .orElseThrow(() -> new FusionConfigException("NOT_FOUND", "No ACTIVE fusion config for tenant"));
    }

    public List<Map<String, Object>> history() {
        return repository.listHistory(TenantContext.require().tenantId());
    }

    public Map<String, Object> getById(UUID id) {
        UUID tenantId = TenantContext.require().tenantId();
        return repository.findById(tenantId, id)
                .orElseThrow(() -> new FusionConfigException("NOT_FOUND", "Fusion config not found"));
    }

    @Transactional
    public Map<String, Object> createDraftFromActive(Map<String, Object> configOverride) {
        TenantContext ctx = TenantContext.require();
        UUID tenantId = ctx.tenantId();
        UUID userId = ctx.userId();
        Map<String, Object> source = configOverride != null && !configOverride.isEmpty()
                ? configOverride
                : repository.findActive(tenantId)
                .map(row -> asMap(row.get("config")))
                .orElseGet(repository::platformDefaultConfig);

        FusionConfigDocument doc = FusionConfigDocument.parse(source);
        List<String> violations = FusionConfigValidator.validate(doc);
        if (!violations.isEmpty()) {
            throw new FusionConfigException("VALIDATION_FAILED", String.join("; ", violations));
        }
        Map<String, Object> canonical = doc.toCanonicalMap();
        String sha = repository.sha256Canonical(canonical);
        int version = repository.nextVersion(tenantId);
        UUID id = repository.insertDraft(tenantId, version, canonical, sha, userId);
        auditLedgerService.append(
                tenantId, null, AuditEventType.FUSION_CONFIG_DRAFT_CREATED, "USER",
                userId == null ? null : userId.toString(),
                Map.of("fusionConfigId", id.toString(), "version", version, "contentSha256", sha)
        );
        return repository.findById(tenantId, id).orElseThrow();
    }

    @Transactional
    public Map<String, Object> updateDraft(UUID id, Map<String, Object> config) {
        TenantContext ctx = TenantContext.require();
        UUID tenantId = ctx.tenantId();
        Map<String, Object> existing = repository.findById(tenantId, id)
                .orElseThrow(() -> new FusionConfigException("NOT_FOUND", "Fusion config not found"));
        if (!"DRAFT".equals(String.valueOf(existing.get("status")))) {
            throw new FusionConfigException("BAD_STATE", "Only DRAFT fusion configs can be updated");
        }
        FusionConfigDocument doc = FusionConfigDocument.parse(config);
        List<String> violations = FusionConfigValidator.validate(doc);
        if (!violations.isEmpty()) {
            throw new FusionConfigException("VALIDATION_FAILED", String.join("; ", violations));
        }
        Map<String, Object> canonical = doc.toCanonicalMap();
        String sha = repository.sha256Canonical(canonical);
        repository.updateDraftConfig(tenantId, id, canonical, sha);
        return repository.findById(tenantId, id).orElseThrow();
    }

    @Transactional
    public Map<String, Object> submit(UUID id) {
        TenantContext ctx = TenantContext.require();
        UUID tenantId = ctx.tenantId();
        UUID userId = ctx.userId();
        Map<String, Object> existing = repository.findById(tenantId, id)
                .orElseThrow(() -> new FusionConfigException("NOT_FOUND", "Fusion config not found"));
        if (!"DRAFT".equals(String.valueOf(existing.get("status")))) {
            throw new FusionConfigException("BAD_STATE", "Only DRAFT fusion configs can be submitted");
        }
        FusionConfigDocument doc = FusionConfigDocument.parse(asMap(existing.get("config")));
        List<String> violations = FusionConfigValidator.validate(doc);
        if (!violations.isEmpty()) {
            throw new FusionConfigException("VALIDATION_FAILED", String.join("; ", violations));
        }
        repository.submit(tenantId, id, userId);
        String sha = String.valueOf(existing.get("contentSha256"));
        auditLedgerService.append(
                tenantId, null, AuditEventType.FUSION_CONFIG_SUBMITTED, "USER",
                userId.toString(),
                Map.of("fusionConfigId", id.toString(), "contentSha256", sha, "version", existing.get("version"))
        );
        return repository.findById(tenantId, id).orElseThrow();
    }

    @Transactional
    public Map<String, Object> approve(UUID id) {
        TenantContext ctx = TenantContext.require();
        UUID tenantId = ctx.tenantId();
        UUID userId = ctx.userId();
        Map<String, Object> existing = repository.findById(tenantId, id)
                .orElseThrow(() -> new FusionConfigException("NOT_FOUND", "Fusion config not found"));
        if (!"PENDING_APPROVAL".equals(String.valueOf(existing.get("status")))) {
            throw new FusionConfigException("BAD_STATE", "Fusion config is not pending approval");
        }
        Object submittedBy = existing.get("submittedBy");
        if (submittedBy != null && userId.toString().equals(String.valueOf(submittedBy))) {
            throw new FusionConfigException("SAME_USER", "Approver must differ from submitter");
        }
        repository.approve(tenantId, id, userId);
        Map<String, Object> activated = repository.findById(tenantId, id).orElseThrow();
        int version = ((Number) activated.get("version")).intValue();
        events.publishEvent(new FusionConfigActivatedEvent(this, tenantId, id, version));
        auditLedgerService.append(
                tenantId, null, AuditEventType.FUSION_CONFIG_APPROVED, "USER",
                userId.toString(),
                Map.of(
                        "fusionConfigId", id.toString(),
                        "contentSha256", activated.get("contentSha256"),
                        "version", version
                )
        );
        return activated;
    }

    @Transactional
    public Map<String, Object> reject(UUID id, String comment) {
        if (comment == null || comment.isBlank()) {
            throw new FusionConfigException("COMMENT_REQUIRED", "Rejection comment is required");
        }
        TenantContext ctx = TenantContext.require();
        UUID tenantId = ctx.tenantId();
        UUID userId = ctx.userId();
        Map<String, Object> existing = repository.findById(tenantId, id)
                .orElseThrow(() -> new FusionConfigException("NOT_FOUND", "Fusion config not found"));
        if (!"PENDING_APPROVAL".equals(String.valueOf(existing.get("status")))) {
            throw new FusionConfigException("BAD_STATE", "Fusion config is not pending approval");
        }
        Object submittedBy = existing.get("submittedBy");
        if (submittedBy != null && userId.toString().equals(String.valueOf(submittedBy))) {
            throw new FusionConfigException("SAME_USER", "Approver must differ from submitter");
        }
        repository.reject(tenantId, id, userId, comment.trim());
        auditLedgerService.append(
                tenantId, null, AuditEventType.FUSION_CONFIG_REJECTED, "USER",
                userId.toString(),
                Map.of(
                        "fusionConfigId", id.toString(),
                        "contentSha256", existing.get("contentSha256"),
                        "comment", comment.trim()
                )
        );
        return repository.findById(tenantId, id).orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        if (o instanceof Map<?, ?> m) {
            return new LinkedHashMap<>((Map<String, Object>) m);
        }
        return new LinkedHashMap<>();
    }
}

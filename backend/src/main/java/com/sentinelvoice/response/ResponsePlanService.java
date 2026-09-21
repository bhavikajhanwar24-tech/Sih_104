package com.sentinelvoice.response;

import com.sentinelvoice.audit.AuditEventType;
import com.sentinelvoice.audit.AuditLedgerService;
import com.sentinelvoice.policy.engine.ConditionEvaluator;
import com.sentinelvoice.policy.engine.FactAssembler;
import com.sentinelvoice.policy.engine.FactSet;
import com.sentinelvoice.policy.engine.TriBool;
import com.sentinelvoice.response.integration.TenantIntegrationService;
import com.sentinelvoice.security.TenantContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ResponsePlanService {

    private final ResponsePlanRepository repository;
    private final AuditLedgerService auditLedgerService;
    private final ApplicationEventPublisher events;
    private final TenantIntegrationService integrations;
    private final FactAssembler factAssembler;

    public ResponsePlanService(
            ResponsePlanRepository repository,
            AuditLedgerService auditLedgerService,
            ApplicationEventPublisher events,
            TenantIntegrationService integrations,
            FactAssembler factAssembler
    ) {
        this.repository = repository;
        this.auditLedgerService = auditLedgerService;
        this.events = events;
        this.integrations = integrations;
        this.factAssembler = factAssembler;
    }

    public Map<String, Object> getActive() {
        UUID tenantId = TenantContext.require().tenantId();
        Map<String, Object> row = repository.findActive(tenantId)
                .orElseThrow(() -> new ResponsePlanException("NOT_FOUND", "No ACTIVE response plan for tenant"));
        return enrich(row);
    }

    public List<Map<String, Object>> history() {
        return repository.listHistory(TenantContext.require().tenantId()).stream()
                .map(this::enrich)
                .toList();
    }

    public Map<String, Object> getById(UUID id) {
        UUID tenantId = TenantContext.require().tenantId();
        Map<String, Object> row = repository.findById(tenantId, id)
                .orElseThrow(() -> new ResponsePlanException("NOT_FOUND", "Response plan not found"));
        return enrich(row);
    }

    @Transactional
    public Map<String, Object> createDraftFromActive(Map<String, Object> planOverride) {
        TenantContext ctx = TenantContext.require();
        UUID tenantId = ctx.tenantId();
        UUID userId = ctx.userId();
        Map<String, Object> source = planOverride != null && !planOverride.isEmpty()
                ? planOverride
                : repository.findActive(tenantId)
                .map(row -> asMap(row.get("plan")))
                .orElseGet(repository::platformDefaultPlan);

        ResponsePlanDocument doc = ResponsePlanDocument.parse(source);
        ResponsePlanValidator.ValidationResult validation = ResponsePlanValidator.validate(doc);
        if (!validation.ok()) {
            throw new ResponsePlanException("VALIDATION_FAILED", String.join("; ", validation.violations()));
        }
        Map<String, Object> canonical = doc.toCanonicalMap();
        String sha = repository.sha256Canonical(canonical);
        int version = repository.nextVersion(tenantId);
        UUID id = repository.insertDraft(tenantId, version, canonical, sha, userId);
        auditLedgerService.append(
                tenantId, null, AuditEventType.RESPONSE_PLAN_DRAFT_CREATED, "USER",
                userId == null ? null : userId.toString(),
                Map.of("responsePlanId", id.toString(), "version", version, "contentSha256", sha)
        );
        return enrich(repository.findById(tenantId, id).orElseThrow());
    }

    @Transactional
    public Map<String, Object> updateDraft(UUID id, Map<String, Object> plan) {
        TenantContext ctx = TenantContext.require();
        UUID tenantId = ctx.tenantId();
        Map<String, Object> existing = repository.findById(tenantId, id)
                .orElseThrow(() -> new ResponsePlanException("NOT_FOUND", "Response plan not found"));
        if (!"DRAFT".equals(String.valueOf(existing.get("status")))) {
            throw new ResponsePlanException("BAD_STATE", "Only DRAFT response plans can be updated");
        }
        ResponsePlanDocument doc = ResponsePlanDocument.parse(plan);
        ResponsePlanValidator.ValidationResult validation = ResponsePlanValidator.validate(doc);
        if (!validation.ok()) {
            throw new ResponsePlanException("VALIDATION_FAILED", String.join("; ", validation.violations()));
        }
        Map<String, Object> canonical = doc.toCanonicalMap();
        String sha = repository.sha256Canonical(canonical);
        repository.updateDraftPlan(tenantId, id, canonical, sha);
        return enrich(repository.findById(tenantId, id).orElseThrow());
    }

    @Transactional
    public Map<String, Object> submit(UUID id) {
        TenantContext ctx = TenantContext.require();
        UUID tenantId = ctx.tenantId();
        UUID userId = ctx.userId();
        Map<String, Object> existing = repository.findById(tenantId, id)
                .orElseThrow(() -> new ResponsePlanException("NOT_FOUND", "Response plan not found"));
        if (!"DRAFT".equals(String.valueOf(existing.get("status")))) {
            throw new ResponsePlanException("BAD_STATE", "Only DRAFT response plans can be submitted");
        }
        ResponsePlanDocument doc = ResponsePlanDocument.parse(asMap(existing.get("plan")));
        ResponsePlanValidator.ValidationResult validation = ResponsePlanValidator.validate(doc);
        if (!validation.ok()) {
            throw new ResponsePlanException("VALIDATION_FAILED", String.join("; ", validation.violations()));
        }
        Map<String, Object> activePlan = repository.findActive(tenantId)
                .map(r -> asMap(r.get("plan")))
                .orElse(Map.of());
        ResponsePlanDocument activeDoc = activePlan.isEmpty()
                ? null
                : ResponsePlanDocument.parse(activePlan);
        boolean touchesCritical = ResponsePlanValidator.touchesL3OrL4(activeDoc, doc);
        repository.submit(tenantId, id, userId);
        String sha = String.valueOf(existing.get("contentSha256"));
        auditLedgerService.append(
                tenantId, null, AuditEventType.RESPONSE_PLAN_SUBMITTED, "USER",
                userId.toString(),
                Map.of(
                        "responsePlanId", id.toString(),
                        "contentSha256", sha,
                        "version", existing.get("version"),
                        "touchesL3OrL4", touchesCritical
                )
        );
        return enrich(repository.findById(tenantId, id).orElseThrow());
    }

    @Transactional
    public Map<String, Object> approve(UUID id) {
        TenantContext ctx = TenantContext.require();
        UUID tenantId = ctx.tenantId();
        UUID userId = ctx.userId();
        Map<String, Object> existing = repository.findById(tenantId, id)
                .orElseThrow(() -> new ResponsePlanException("NOT_FOUND", "Response plan not found"));
        if (!"PENDING_APPROVAL".equals(String.valueOf(existing.get("status")))) {
            throw new ResponsePlanException("BAD_STATE", "Response plan is not pending approval");
        }
        Object submittedBy = existing.get("submittedBy");
        if (submittedBy != null && userId.toString().equals(String.valueOf(submittedBy))) {
            throw new ResponsePlanException("SAME_USER", "Approver must differ from submitter");
        }
        ResponsePlanDocument doc = ResponsePlanDocument.parse(asMap(existing.get("plan")));
        Map<String, Object> activePlan = repository.findActive(tenantId)
                .map(r -> asMap(r.get("plan")))
                .orElse(Map.of());
        ResponsePlanDocument activeDoc = activePlan.isEmpty()
                ? null
                : ResponsePlanDocument.parse(activePlan);
        if (ResponsePlanValidator.touchesL3OrL4(activeDoc, doc)
                && (submittedBy == null || userId.toString().equals(String.valueOf(submittedBy)))) {
            throw new ResponsePlanException("SAME_USER", "L3/L4 changes require a different POLICY_APPROVER");
        }
        repository.approve(tenantId, id, userId);
        Map<String, Object> activated = repository.findById(tenantId, id).orElseThrow();
        int version = ((Number) activated.get("version")).intValue();
        events.publishEvent(new ResponsePlanActivatedEvent(this, tenantId, id, version));
        auditLedgerService.append(
                tenantId, null, AuditEventType.RESPONSE_PLAN_APPROVED, "USER",
                userId.toString(),
                Map.of(
                        "responsePlanId", id.toString(),
                        "contentSha256", activated.get("contentSha256"),
                        "version", version
                )
        );
        return enrich(activated);
    }

    @Transactional
    public Map<String, Object> reject(UUID id, String comment) {
        if (comment == null || comment.isBlank()) {
            throw new ResponsePlanException("COMMENT_REQUIRED", "Rejection comment is required");
        }
        TenantContext ctx = TenantContext.require();
        UUID tenantId = ctx.tenantId();
        UUID userId = ctx.userId();
        Map<String, Object> existing = repository.findById(tenantId, id)
                .orElseThrow(() -> new ResponsePlanException("NOT_FOUND", "Response plan not found"));
        if (!"PENDING_APPROVAL".equals(String.valueOf(existing.get("status")))) {
            throw new ResponsePlanException("BAD_STATE", "Response plan is not pending approval");
        }
        Object submittedBy = existing.get("submittedBy");
        if (submittedBy != null && userId.toString().equals(String.valueOf(submittedBy))) {
            throw new ResponsePlanException("SAME_USER", "Approver must differ from submitter");
        }
        repository.reject(tenantId, id, userId, comment.trim());
        auditLedgerService.append(
                tenantId, null, AuditEventType.RESPONSE_PLAN_REJECTED, "USER",
                userId.toString(),
                Map.of(
                        "responsePlanId", id.toString(),
                        "contentSha256", existing.get("contentSha256"),
                        "comment", comment.trim()
                )
        );
        return enrich(repository.findById(tenantId, id).orElseThrow());
    }

    public Map<String, Object> validateOnly(Map<String, Object> plan) {
        ResponsePlanDocument doc = ResponsePlanDocument.parse(plan);
        ResponsePlanValidator.ValidationResult result = ResponsePlanValidator.validate(doc);
        UUID tenantId = TenantContext.require().tenantId();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", result.ok());
        out.put("violations", result.violations());
        Map<String, Object> levels = new LinkedHashMap<>();
        result.levels().forEach((k, v) -> {
            Map<String, Object> ls = new LinkedHashMap<>();
            ls.put("ok", v.ok());
            ls.put("reasons", v.reasons());
            levels.put(k, ls);
        });
        out.put("levels", levels);
        out.put("degradationWarnings", integrations.degradationWarnings(tenantId, doc));
        return out;
    }

    public Map<String, Object> preview(Map<String, Object> plan, String levelKey, Map<String, Object> sampleFacts) {
        ResponsePlanDocument doc = ResponsePlanDocument.parse(plan);
        ResponsePlanDocument.LevelPlan lp = doc.level(levelKey);
        if (lp == null) {
            throw new ResponsePlanException("VALIDATION_FAILED", "Unknown level " + levelKey);
        }
        List<Map<String, Object>> timeline = new java.util.ArrayList<>();
        long t = 0;
        FactSet facts = factAssembler.fromSimulationMap(sampleFacts == null ? Map.of() : sampleFacts);
        int idx = 0;
        for (ResponsePlanDocument.PlanStep step : lp.steps()) {
            if (!"ON_ENTER".equals(step.trigger()) && !"WHILE_ACTIVE_EVERY_N_SEC".equals(step.trigger())) {
                continue;
            }
            boolean condOk = true;
            String skipReason = null;
            if (step.condition() != null && !step.condition().isEmpty()) {
                try {
                    var cond = step.parsedCondition();
                    var er = ConditionEvaluator.evaluate(cond, facts);
                    condOk = er.value() == TriBool.TRUE;
                    if (!condOk) {
                        skipReason = "condition not met";
                    }
                } catch (Exception e) {
                    condOk = false;
                    skipReason = e.getMessage();
                }
            }
            t += Math.max(0, step.delayMs());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("stepIndex", idx++);
            entry.put("atMs", t);
            entry.put("action", step.action());
            entry.put("params", step.params());
            entry.put("trigger", step.trigger());
            entry.put("autoExecute", step.autoExecute());
            entry.put("operatorConfirm", step.operatorConfirm());
            entry.put("wouldRun", condOk);
            entry.put("skipReason", skipReason);
            timeline.add(entry);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("level", levelKey);
        out.put("timeline", timeline);
        out.put("operatorOverridePermitted", lp.operatorOverridePermitted());
        out.put("overrideRequiresSupervisor", lp.overrideRequiresSupervisor());
        return out;
    }

    private Map<String, Object> enrich(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>(row);
        try {
            ResponsePlanDocument doc = ResponsePlanDocument.parse(asMap(row.get("plan")));
            ResponsePlanValidator.ValidationResult validation = ResponsePlanValidator.validate(doc);
            Map<String, Object> levels = new LinkedHashMap<>();
            validation.levels().forEach((k, v) -> {
                Map<String, Object> ls = new LinkedHashMap<>();
                ls.put("ok", v.ok());
                ls.put("reasons", v.reasons());
                levels.put(k, ls);
            });
            out.put("validation", Map.of(
                    "ok", validation.ok(),
                    "violations", validation.violations(),
                    "levels", levels
            ));
            UUID tenantId = UUID.fromString(String.valueOf(row.get("tenantId")));
            out.put("degradationWarnings", integrations.degradationWarnings(tenantId, doc));
        } catch (Exception e) {
            out.put("validation", Map.of("ok", false, "violations", List.of(e.getMessage())));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        if (o instanceof Map<?, ?> m) {
            return new LinkedHashMap<>((Map<String, Object>) m);
        }
        return new LinkedHashMap<>();
    }
}

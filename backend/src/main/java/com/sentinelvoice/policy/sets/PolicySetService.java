package com.sentinelvoice.policy.sets;

import com.sentinelvoice.audit.AuditEventType;
import com.sentinelvoice.audit.AuditLedgerService;
import com.sentinelvoice.policy.PolicyDocumentChunkEntity;
import com.sentinelvoice.policy.PolicyDocumentChunkRepository;
import com.sentinelvoice.policy.compile.PolicyCompileException;
import com.sentinelvoice.policy.compile.PolicyCompileService;
import com.sentinelvoice.policy.compile.PolicySetActivatedEvent;
import com.sentinelvoice.policy.compile.RuleValidator;
import com.sentinelvoice.policy.dsl.ConditionEnglish;
import com.sentinelvoice.policy.dsl.FactCatalogue;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class PolicySetService {

    private final PolicySetRepository setRepository;
    private final PolicyCompileService compileService;
    private final PolicyDocumentChunkRepository chunkRepository;
    private final AuditLedgerService auditLedgerService;
    private final ApplicationEventPublisher events;

    public PolicySetService(
            PolicySetRepository setRepository,
            PolicyCompileService compileService,
            PolicyDocumentChunkRepository chunkRepository,
            AuditLedgerService auditLedgerService,
            ApplicationEventPublisher events
    ) {
        this.setRepository = setRepository;
        this.compileService = compileService;
        this.chunkRepository = chunkRepository;
        this.auditLedgerService = auditLedgerService;
        this.events = events;
    }

    public List<Map<String, Object>> listSets(UUID tenantId) {
        return setRepository.listSets(tenantId);
    }

    public Map<String, Object> getSet(UUID tenantId, UUID id) {
        Map<String, Object> set = setRepository.findSet(tenantId, id)
                .orElseThrow(() -> new PolicyCompileException("NOT_FOUND", "Policy set not found"));
        List<Map<String, Object>> rules = setRepository.listRules(tenantId, id);
        set.put("rules", rules);
        set.put("keywords", setRepository.listKeywords(tenantId, id));
        set.put("facts", setRepository.listFacts(tenantId, id));
        set.put("compileDiagnostics", compileService.diagnosticsForPolicySet(tenantId, id));
        long accepted = rules.stream()
                .filter(r -> {
                    String st = String.valueOf(r.get("status"));
                    if (!"ACCEPTED".equals(st) && !"EDITED".equals(st)) {
                        return false;
                    }
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> warnings = r.get("warnings") instanceof List<?> w
                            ? (List<Map<String, Object>>) w
                            : List.of();
                    return !RuleValidator.blocksAcceptance(warnings);
                })
                .count();
        set.put("acceptedRuleCount", accepted);
        set.put("canSubmit", "DRAFT".equals(set.get("status")) && accepted > 0);
        return set;
    }

    @Transactional
    public Map<String, Object> updateRule(
            UUID tenantId, UUID userId, UUID rulePk, Map<String, Object> patch
    ) {
        Map<String, Object> existing = setRepository.findRule(tenantId, rulePk)
                .orElseThrow(() -> new PolicyCompileException("NOT_FOUND", "Rule not found"));
        UUID setId = UUID.fromString(String.valueOf(existing.get("policySetId")));
        Map<String, Object> set = setRepository.findSet(tenantId, setId).orElseThrow();
        if (!"DRAFT".equals(set.get("status"))) {
            throw new PolicyCompileException("NOT_DRAFT", "Only DRAFT sets can be edited");
        }
        Map<String, Object> body = new LinkedHashMap<>(existing);
        if (patch.containsKey("title")) body.put("title", patch.get("title"));
        if (patch.containsKey("description")) body.put("description", patch.get("description"));
        if (patch.containsKey("when")) body.put("when", patch.get("when"));
        if (patch.containsKey("then")) body.put("then", patch.get("then"));
        if (patch.containsKey("appliesTo")) body.put("appliesTo", patch.get("appliesTo"));
        if (patch.containsKey("severity")) body.put("severity", patch.get("severity"));
        if (patch.containsKey("source")) body.put("source", patch.get("source"));

        String priorStatus = String.valueOf(existing.get("status"));
        String status = patch.get("status") == null
                ? priorStatus
                : String.valueOf(patch.get("status")).toUpperCase(Locale.ROOT);
        if ("ACCEPTED".equals(status) || "EDITED".equals(status) || "REJECTED".equals(status)
                || "PROPOSED".equals(status)) {
            body.put("status", status);
        }
        // when-edit on any non-PROPOSED rule (including validator REJECTED) → EDITED so admins
        // can rescue clauses like 6.3 via "Edit to fix".
        if (patch.containsKey("when") && !"PROPOSED".equals(priorStatus)
                && !"REJECTED".equalsIgnoreCase(String.valueOf(patch.getOrDefault("status", "")))) {
            body.put("status", "EDITED");
            status = "EDITED";
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> when = (Map<String, Object>) body.get("when");
        Set<String> unknown = ConditionEnglish.collectFacts(when).stream()
                .filter(f -> !FactCatalogue.isKnown(f))
                .collect(java.util.stream.Collectors.toSet());
        if (!unknown.isEmpty()) {
            throw new PolicyCompileException("UNKNOWN_FACT", "Unknown facts: " + unknown);
        }

        Map<String, Object> source = asMap(body.get("source"));
        String chunkText = loadCitedChunkText(tenantId, source);
        RuleValidator.EditValidation editCheck = RuleValidator.validateEdit(
                when,
                asMap(body.get("then")),
                source,
                chunkText
        );

        List<Map<String, Object>> warnings = new ArrayList<>();
        if (body.get("warnings") instanceof List<?> priorWarnings) {
            for (Object o : priorWarnings) {
                if (o instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> wm = (Map<String, Object>) m;
                    warnings.add(new LinkedHashMap<>(wm));
                }
            }
        }
        warnings.removeIf(w -> {
            String c = String.valueOf(w.get("code"));
            return "HALLUCINATED_QUOTE".equals(c)
                    || "VALUE_NOT_IN_SOURCE".equals(c)
                    || "REJECTED_VALUE_NOT_IN_SOURCE".equals(c)
                    || "INVALID_VALUE".equals(c)
                    || "UNKNOWN_FACT".equals(c)
                    || "EMPTY_WHEN".equals(c)
                    || "NON_DISCRIMINATING".equals(c)
                    || "MISSING_CLAUSE_REF".equals(c)
                    || "UNGBOUNDED_CLAUSE".equals(c)
                    || "INVALID_LEVEL".equals(c);
        });
        warnings.addAll(editCheck.warnings());
        body.put("warnings", warnings);

        boolean groundingFailed = RuleValidator.blocksAcceptance(warnings);
        if (groundingFailed) {
            String msg = editCheck.errors().stream()
                    .filter(e -> e.toLowerCase(Locale.ROOT).contains("quote"))
                    .findFirst()
                    .orElse(editCheck.errors().isEmpty()
                            ? "quote not found in source"
                            : String.join("; ", editCheck.errors()));
            // Explicit Accept is blocked until the quote is fixed
            if ("ACCEPTED".equalsIgnoreCase(String.valueOf(patch.get("status")))) {
                throw new PolicyCompileException("HALLUCINATED_QUOTE", msg);
            }
            // Persist the edit flagged as PROPOSED so the review UI shows the warning
            status = "PROPOSED";
            body.put("status", status);
        } else if (!editCheck.ok()) {
            throw new PolicyCompileException(
                    "INVALID_EDIT",
                    String.join("; ", editCheck.errors())
            );
        } else if ("REJECTED".equals(priorStatus) && patch.containsKey("when")) {
            // Validator-rejected rule rescued by a valid edit
            status = "EDITED";
            body.put("status", status);
        }

        body.put("plainEnglish", ConditionEnglish.render(
                when,
                asMap(body.get("then")),
                asMap(body.get("appliesTo"))
        ));
        setRepository.updateRuleStatus(tenantId, rulePk, status, body);
        auditLedgerService.append(
                tenantId, null, AuditEventType.POLICY_RULE_UPDATED, "USER", userId.toString(),
                Map.of("ruleId", body.get("ruleId"), "status", status, "policySetId", setId.toString())
        );
        Map<String, Object> updated = setRepository.findRule(tenantId, rulePk).orElseThrow();
        if (groundingFailed) {
            updated.put("editError", "quote not found in source");
        }
        return updated;
    }

    private String loadCitedChunkText(UUID tenantId, Map<String, Object> source) {
        if (source == null || source.get("documentId") == null) {
            return null;
        }
        UUID documentId;
        try {
            documentId = UUID.fromString(String.valueOf(source.get("documentId")));
        } catch (Exception e) {
            return null;
        }
        List<PolicyDocumentChunkEntity> chunks =
                chunkRepository.findByTenantIdAndDocumentIdOrderByOrdinalAsc(tenantId, documentId);
        if (chunks.isEmpty()) {
            return null;
        }
        Set<String> citeIds = Set.of();
        if (source.get("chunkIds") instanceof List<?> ids) {
            citeIds = ids.stream().map(String::valueOf).collect(java.util.stream.Collectors.toSet());
        }
        StringBuilder sb = new StringBuilder();
        for (PolicyDocumentChunkEntity c : chunks) {
            if (citeIds.isEmpty() || citeIds.contains(c.getId().toString())) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(c.getText() == null ? "" : c.getText());
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    @Transactional
    public void deleteDraft(UUID tenantId, UUID userId, UUID setId) {
        Map<String, Object> set = setRepository.findSet(tenantId, setId)
                .orElseThrow(() -> new PolicyCompileException("NOT_FOUND", "Policy set not found"));
        if (!"DRAFT".equals(set.get("status"))) {
            throw new PolicyCompileException("BAD_STATE", "Only DRAFT sets can be deleted");
        }
        setRepository.deleteSet(tenantId, setId);
        auditLedgerService.append(
                tenantId, null, AuditEventType.POLICY_SET_DELETED, "USER", userId.toString(),
                Map.of("policySetId", setId.toString(), "version", set.get("version"))
        );
    }

    @Transactional
    public Map<String, Object> createManualRule(UUID tenantId, UUID userId, UUID setId, Map<String, Object> body) {
        Map<String, Object> set = setRepository.findSet(tenantId, setId)
                .orElseThrow(() -> new PolicyCompileException("NOT_FOUND", "Policy set not found"));
        if (!"DRAFT".equals(set.get("status"))) {
            throw new PolicyCompileException("NOT_DRAFT", "Only DRAFT sets accept new rules");
        }
        body = new LinkedHashMap<>(body);
        body.put("origin", "MANUAL");
        body.putIfAbsent("status", "ACCEPTED");
        body.putIfAbsent("ruleId", "R-manual-" + UUID.randomUUID().toString().substring(0, 8));
        setRepository.insertManualRule(tenantId, setId, body);
        auditLedgerService.append(
                tenantId, null, AuditEventType.POLICY_RULE_CREATED, "USER", userId.toString(),
                Map.of("ruleId", body.get("ruleId"), "policySetId", setId.toString())
        );
        return getSet(tenantId, setId);
    }

    @Transactional
    public Map<String, Object> submit(UUID tenantId, UUID userId, UUID setId) {
        Map<String, Object> set = setRepository.findSet(tenantId, setId)
                .orElseThrow(() -> new PolicyCompileException("NOT_FOUND", "Policy set not found"));
        String status = String.valueOf(set.get("status"));
        // DRAFT review submit, or SUPERSEDED rollback via the same approval flow
        if (!"DRAFT".equals(status) && !"SUPERSEDED".equals(status)) {
            throw new PolicyCompileException("BAD_STATE", "Only DRAFT or SUPERSEDED sets can be submitted");
        }
        if (setRepository.countAccepted(tenantId, setId) < 1) {
            throw new PolicyCompileException("NO_RULES", "Accept at least one rule before submit");
        }
        setRepository.submit(tenantId, setId, userId);
        String sha = setRepository.computeContentSha(tenantId, setId);
        auditLedgerService.append(
                tenantId, null, AuditEventType.POLICY_SET_SUBMITTED, "USER", userId.toString(),
                Map.of(
                        "policySetId", setId.toString(),
                        "contentSha256", sha,
                        "rollback", "SUPERSEDED".equals(status)
                )
        );
        return getSet(tenantId, setId);
    }

    @Transactional
    public Map<String, Object> approve(UUID tenantId, UUID userId, UUID setId, String comment) {
        Map<String, Object> set = setRepository.findSet(tenantId, setId)
                .orElseThrow(() -> new PolicyCompileException("NOT_FOUND", "Policy set not found"));
        if (!"PENDING_APPROVAL".equals(set.get("status"))) {
            throw new PolicyCompileException("BAD_STATE", "Set is not pending approval");
        }
        Object submittedBy = set.get("submittedBy");
        if (submittedBy != null && userId.toString().equals(String.valueOf(submittedBy))) {
            throw new PolicyCompileException("SAME_USER", "Approver must differ from submitter");
        }
        String sha = setRepository.computeContentSha(tenantId, setId);
        setRepository.approve(tenantId, setId, userId, comment, sha);
        Map<String, Object> activated = getSet(tenantId, setId);
        events.publishEvent(new PolicySetActivatedEvent(
                this, tenantId, setId, ((Number) activated.get("version")).intValue()
        ));
        auditLedgerService.append(
                tenantId, null, AuditEventType.POLICY_SET_APPROVED, "USER", userId.toString(),
                Map.of("policySetId", setId.toString(), "contentSha256", sha, "comment", comment == null ? "" : comment)
        );
        return activated;
    }

    @Transactional
    public Map<String, Object> reject(UUID tenantId, UUID userId, UUID setId, String comment) {
        if (comment == null || comment.isBlank()) {
            throw new PolicyCompileException("COMMENT_REQUIRED", "Rejection comment is required");
        }
        Map<String, Object> set = setRepository.findSet(tenantId, setId)
                .orElseThrow(() -> new PolicyCompileException("NOT_FOUND", "Policy set not found"));
        if (!"PENDING_APPROVAL".equals(set.get("status"))) {
            throw new PolicyCompileException("BAD_STATE", "Set is not pending approval");
        }
        Object submittedBy = set.get("submittedBy");
        if (submittedBy != null && userId.toString().equals(String.valueOf(submittedBy))) {
            throw new PolicyCompileException("SAME_USER", "Approver must differ from submitter");
        }
        setRepository.reject(tenantId, setId, userId, comment);
        String sha = setRepository.computeContentSha(tenantId, setId);
        auditLedgerService.append(
                tenantId, null, AuditEventType.POLICY_SET_REJECTED, "USER", userId.toString(),
                Map.of("policySetId", setId.toString(), "contentSha256", sha, "comment", comment)
        );
        return getSet(tenantId, setId);
    }

    public Map<String, Object> diff(UUID tenantId, UUID leftId, UUID rightId) {
        List<Map<String, Object>> left = setRepository.listRules(tenantId, leftId);
        List<Map<String, Object>> right = setRepository.listRules(tenantId, rightId);
        Map<String, Map<String, Object>> lmap = indexByRuleId(left);
        Map<String, Map<String, Object>> rmap = indexByRuleId(right);
        List<Map<String, Object>> added = new java.util.ArrayList<>();
        List<Map<String, Object>> removed = new java.util.ArrayList<>();
        List<Map<String, Object>> changed = new java.util.ArrayList<>();
        for (String id : rmap.keySet()) {
            if (!lmap.containsKey(id)) {
                added.add(rmap.get(id));
            } else if (!String.valueOf(lmap.get(id).get("title")).equals(String.valueOf(rmap.get(id).get("title")))
                    || !String.valueOf(lmap.get(id).get("when")).equals(String.valueOf(rmap.get(id).get("when")))) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("before", lmap.get(id));
                row.put("after", rmap.get(id));
                changed.add(row);
            }
        }
        for (String id : lmap.keySet()) {
            if (!rmap.containsKey(id)) {
                removed.add(lmap.get(id));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("added", added);
        out.put("removed", removed);
        out.put("changed", changed);
        return out;
    }

    public void addKeyword(UUID tenantId, UUID setId, Map<String, Object> body) {
        setRepository.addKeyword(
                tenantId,
                setId,
                String.valueOf(body.get("term")),
                body.get("lang") == null ? "en" : String.valueOf(body.get("lang")),
                String.valueOf(body.getOrDefault("category", "CUSTOM")),
                body.get("weight") instanceof Number n ? n.doubleValue() : 1.0
        );
    }

    public void deleteKeyword(UUID tenantId, UUID keywordId) {
        setRepository.deleteKeyword(tenantId, keywordId);
    }

    private Map<String, Map<String, Object>> indexByRuleId(List<Map<String, Object>> rules) {
        Map<String, Map<String, Object>> m = new LinkedHashMap<>();
        for (Map<String, Object> r : rules) {
            m.put(String.valueOf(r.get("ruleId")), r);
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}

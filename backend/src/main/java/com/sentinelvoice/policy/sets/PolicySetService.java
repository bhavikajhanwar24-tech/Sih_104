package com.sentinelvoice.policy.sets;

import com.sentinelvoice.audit.AuditEventType;
import com.sentinelvoice.audit.AuditLedgerService;
import com.sentinelvoice.policy.PolicyDocumentChunkEntity;
import com.sentinelvoice.policy.PolicyDocumentChunkRepository;
import com.sentinelvoice.policy.PolicyDocumentEntity;
import com.sentinelvoice.policy.PolicyDocumentRepository;
import com.sentinelvoice.policy.conflict.ConditionConstraintNormalizer;
import com.sentinelvoice.policy.conflict.RuleConflictService;
import com.sentinelvoice.policy.compile.KeywordExtractor;
import com.sentinelvoice.policy.compile.PolicyCompileException;
import com.sentinelvoice.policy.compile.PolicyCompileService;
import com.sentinelvoice.policy.compile.PolicySetActivatedEvent;
import com.sentinelvoice.policy.compile.RuleSourceAttributor;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class PolicySetService {

    private final PolicySetRepository setRepository;
    private final PolicyCompileService compileService;
    private final PolicyDocumentChunkRepository chunkRepository;
    private final PolicyDocumentRepository documentRepository;
    private final AuditLedgerService auditLedgerService;
    private final ApplicationEventPublisher events;
    private final LiveRulesService liveRulesService;
    private final RuleConflictService conflictService;

    public PolicySetService(
            PolicySetRepository setRepository,
            PolicyCompileService compileService,
            PolicyDocumentChunkRepository chunkRepository,
            PolicyDocumentRepository documentRepository,
            AuditLedgerService auditLedgerService,
            ApplicationEventPublisher events,
            LiveRulesService liveRulesService,
            RuleConflictService conflictService
    ) {
        this.setRepository = setRepository;
        this.compileService = compileService;
        this.chunkRepository = chunkRepository;
        this.documentRepository = documentRepository;
        this.auditLedgerService = auditLedgerService;
        this.events = events;
        this.liveRulesService = liveRulesService;
        this.conflictService = conflictService;
    }

    public List<Map<String, Object>> listSets(UUID tenantId) {
        return setRepository.listSets(tenantId);
    }

    public Map<String, Object> getSet(UUID tenantId, UUID id) {
        Map<String, Object> set = setRepository.findSet(tenantId, id)
                .orElseThrow(() -> new PolicyCompileException("NOT_FOUND", "Policy set not found"));
        List<Map<String, Object>> rules = setRepository.listRules(tenantId, id);
        enrichRulesWithDocumentNames(tenantId, rules);
        set.put("rules", rules);
        set.put("keywords", setRepository.listKeywords(tenantId, id));
        set.put("facts", setRepository.listFacts(tenantId, id));
        set.put("compileDiagnostics", compileService.diagnosticsForPolicySet(tenantId, id));
        long accepted = rules.stream()
                .filter(r -> r.get("deletedAt") == null)
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
        List<Map<String, Object>> deleted = rules.stream()
                .filter(r -> r.get("deletedAt") != null)
                .toList();
        set.put("deletedRules", deleted);
        set.put("activeRules", rules.stream().filter(r -> r.get("deletedAt") == null).toList());
        List<Map<String, Object>> conflicts = conflictService.list(tenantId, id, null);
        set.put("conflicts", conflicts);
        long openBlocking = conflicts.stream()
                .filter(c -> "OPEN".equals(String.valueOf(c.get("status")))
                        && !Boolean.TRUE.equals(c.get("advisory")))
                .count();
        set.put("openConflictCount", openBlocking);
        boolean canSubmit = "DRAFT".equals(set.get("status")) && accepted > 0;
        if (canSubmit) {
            try {
                Map<String, Object> pf = liveRulesService.preflight(tenantId, id);
                set.put("preflight", pf);
                canSubmit = Boolean.TRUE.equals(pf.get("canSubmit"));
            } catch (Exception e) {
                set.put("preflight", Map.of("canSubmit", false, "error", e.getMessage()));
                canSubmit = false;
            }
        }
        set.put("canSubmit", canSubmit);
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

        List<Map<String, Object>> repairWarnings = new ArrayList<>();
        Map<String, Object> repairedWhen = RuleValidator.repairWhen(asMap(body.get("when")), repairWarnings);
        if (!repairedWhen.isEmpty()) {
            body.put("when", repairedWhen);
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
        boolean adminDirective = isAdminDirective(source);
        String chunkText = adminDirective ? null : loadCitedChunkText(tenantId, source);
        RuleValidator.EditValidation editCheck = adminDirective
                ? RuleValidator.validateEditAdmin(when, asMap(body.get("then")), source)
                : RuleValidator.validateEdit(when, asMap(body.get("then")), source, chunkText);

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
                    || "INVALID_LEVEL".equals(c)
                    || "LLM_NOT_IMPORTANT".equals(c)
                    || "LLM_ALREADY_COVERED".equals(c)
                    || "QUOTE_UNVERIFIED".equals(c)
                    || "NUMBER_UNVERIFIED".equals(c)
                    || "BROAD_CONDITION".equals(c);
        });
        warnings.addAll(repairWarnings);
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
        if (("ACCEPTED".equals(status) || "EDITED".equals(status))
                && !hasSimExamples(body.get("simulationExamples"))) {
            body.put("simulationExamples", defaultSimExamples(when));
        }
        setRepository.updateRuleStatus(tenantId, rulePk, status, body);
        if (body.get("simulationExamples") != null) {
            setRepository.updateSimulationExamples(tenantId, rulePk, body.get("simulationExamples"));
        }
        // Accept/reject status-only must NOT wipe compile-time policy_keywords.
        // Only re-harvest when the operator changed the clause content or keyword list.
        boolean keywordRelevant = patch.containsKey("when")
                || patch.containsKey("source")
                || patch.containsKey("keywords")
                || patch.containsKey("description")
                || patch.containsKey("title");
        if (keywordRelevant) {
            String rid = String.valueOf(body.get("ruleId"));
            setRepository.deleteKeywordsForRule(tenantId, setId, rid);
            String harvestText = isAdminDirective(asMap(body.get("source")))
                    ? String.valueOf(asMap(body.get("source")).getOrDefault("basis", ""))
                    : String.valueOf(asMap(body.get("source")).getOrDefault("quote", ""));
            for (Map<String, Object> km : KeywordExtractor.merge(body.get("keywords"), harvestText)) {
                setRepository.addKeywordForRule(
                        tenantId, setId,
                        String.valueOf(km.get("term")),
                        String.valueOf(km.getOrDefault("lang", "en")),
                        String.valueOf(km.getOrDefault("category", "CUSTOM")),
                        km.get("weight") instanceof Number n ? n.doubleValue() : 1.0,
                        rid
                );
            }
        }
        auditLedgerService.append(
                tenantId, null, AuditEventType.POLICY_RULE_UPDATED, "USER", userId.toString(),
                Map.of("ruleId", body.get("ruleId"), "status", status, "policySetId", setId.toString())
        );
        Map<String, Object> updated = setRepository.findRule(tenantId, rulePk).orElseThrow();
        if (groundingFailed) {
            updated.put("editError", "quote not found in source");
        }
        // Re-check conflicts after edit
        List<Map<String, Object>> conflicts = conflictService.checkRule(
                tenantId, userId, setId, updated, false
        );
        updated.put("conflicts", conflicts);
        Map<String, Object> setOut = getSet(tenantId, setId);
        setOut.put("updatedRule", updated);
        setOut.put("newConflicts", conflicts);
        return updated;
    }

    private static boolean isAdminDirective(Map<String, Object> source) {
        if (source == null) {
            return false;
        }
        String kind = String.valueOf(source.getOrDefault("kind", source.getOrDefault("type", "")));
        return "ADMIN_DIRECTIVE".equalsIgnoreCase(kind);
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
        Map<String, Object> draftMeta = ensureEditableDraft(tenantId, userId, setId);
        UUID draftId = UUID.fromString(String.valueOf(draftMeta.get("draftId")));
        body = new LinkedHashMap<>(body == null ? Map.of() : body);
        Map<String, Object> sourceIn = asMap(body.get("source"));
        boolean adminDirective = isAdminDirective(sourceIn)
                || "ADMIN_DIRECTIVE".equalsIgnoreCase(String.valueOf(body.getOrDefault("sourceKind", "")));

        Map<String, Object> attributed;
        if (adminDirective) {
            attributed = buildAdminDirectiveRule(body, sourceIn);
        } else {
            UUID documentId = parseUuid(sourceIn.get("documentId"));
            UUID chunkId = firstChunkId(sourceIn);
            if (documentId == null || chunkId == null) {
                throw new PolicyCompileException(
                        "SOURCE_REQUIRED",
                        "Pick a source chunk from the document viewer (documentId + chunkId), "
                                + "or set source.kind=ADMIN_DIRECTIVE with a basis (min 15 chars)"
                );
            }
            PolicyDocumentChunkEntity chunk = chunkRepository
                    .findByTenantIdAndDocumentIdOrderByOrdinalAsc(tenantId, documentId)
                    .stream()
                    .filter(c -> c.getId().equals(chunkId))
                    .findFirst()
                    .orElseThrow(() -> new PolicyCompileException("NOT_FOUND", "Source chunk not found"));

            String selectedQuote = sourceIn.get("quote") == null ? null : String.valueOf(sourceIn.get("quote")).trim();
            attributed = RuleSourceAttributor.attribute(body, documentId, chunk);
            if (selectedQuote != null && !selectedQuote.isBlank()
                    && chunk.getText() != null
                    && chunk.getText().toLowerCase(Locale.ROOT)
                    .contains(selectedQuote.toLowerCase(Locale.ROOT).strip())) {
                @SuppressWarnings("unchecked")
                Map<String, Object> src = attributed.get("source") instanceof Map<?, ?> m
                        ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
                String q = selectedQuote.length() > 200 ? selectedQuote.substring(0, 200) : selectedQuote;
                src.put("quote", q);
                attributed.put("source", src);
            }
            Map<String, Object> src = asMap(attributed.get("source"));
            src.put("kind", "DOCUMENT_CLAUSE");
            attributed.put("source", src);

            RuleValidator.EditValidation check = RuleValidator.validateEdit(
                    asMap(attributed.get("when")),
                    asMap(attributed.get("then")),
                    src,
                    chunk.getText()
            );
            if (!check.ok()) {
                throw new PolicyCompileException("INVALID_RULE", String.join("; ", check.errors()));
            }
            attributed.put("warnings", check.warnings());
        }

        attributed.put("origin", "MANUAL");
        attributed.put("status", "ACCEPTED");
        if (attributed.get("modality") == null && body.get("modality") != null) {
            attributed.put("modality", body.get("modality"));
        }
        if (body.get("keywords") != null) {
            attributed.put("keywords", body.get("keywords"));
        }
        if (body.get("title") != null && !String.valueOf(body.get("title")).isBlank()) {
            attributed.put("title", body.get("title"));
        }
        attributed.put("plainEnglish", ConditionEnglish.render(
                asMap(attributed.get("when")),
                asMap(attributed.get("then")),
                asMap(attributed.get("appliesTo"))
        ));
        if (!hasSimExamples(attributed.get("simulationExamples"))) {
            attributed.put("simulationExamples", defaultSimExamples(asMap(attributed.get("when"))));
        }

        setRepository.insertManualRule(tenantId, draftId, attributed);
        // Persist keywords for document-clause / admin manual adds
        List<Map<String, Object>> kws = KeywordExtractor.merge(
                attributed.get("keywords"),
                adminDirective
                        ? String.valueOf(asMap(attributed.get("source")).getOrDefault("basis", ""))
                        : String.valueOf(asMap(attributed.get("source")).getOrDefault("quote", ""))
        );
        String newRuleId = String.valueOf(attributed.get("ruleId"));
        for (Map<String, Object> km : kws) {
            setRepository.addKeywordForRule(
                    tenantId, draftId,
                    String.valueOf(km.get("term")),
                    String.valueOf(km.getOrDefault("lang", "en")),
                    String.valueOf(km.getOrDefault("category", "CUSTOM")),
                    km.get("weight") instanceof Number n ? n.doubleValue() : 1.0,
                    newRuleId
            );
        }
        auditLedgerService.append(
                tenantId, null, AuditEventType.POLICY_RULE_ADDED, "USER", userId.toString(),
                Map.of(
                        "ruleId", attributed.get("ruleId"),
                        "policySetId", draftId.toString(),
                        "origin", "MANUAL",
                        "sourceKind", adminDirective ? "ADMIN_DIRECTIVE" : "DOCUMENT_CLAUSE"
                )
        );
        // Also keep POLICY_RULE_CREATED for backward-compatible consumers
        auditLedgerService.append(
                tenantId, null, AuditEventType.POLICY_RULE_CREATED, "USER", userId.toString(),
                Map.of("ruleId", attributed.get("ruleId"), "policySetId", draftId.toString(), "origin", "MANUAL")
        );

        List<Map<String, Object>> conflicts = conflictService.checkRule(
                tenantId, userId, draftId, attributed, true
        );
        Map<String, Object> out = getSet(tenantId, draftId);
        out.put("draftId", draftId.toString());
        out.put("draftMessage", draftMeta.get("message"));
        out.put("newConflicts", conflicts);
        out.put("addedRuleId", attributed.get("ruleId"));
        return out;
    }

    /**
     * ACTIVE sets are immutable — open or reuse a DRAFT copy for edits / manual adds.
     */
    @Transactional
    public Map<String, Object> ensureEditableDraft(UUID tenantId, UUID userId, UUID setId) {
        Map<String, Object> set = setRepository.findSet(tenantId, setId)
                .orElseThrow(() -> new PolicyCompileException("NOT_FOUND", "Policy set not found"));
        String status = String.valueOf(set.get("status"));
        if ("DRAFT".equals(status)) {
            return Map.of(
                    "draftId", setId.toString(),
                    "version", set.get("version"),
                    "message", "Editing draft v" + set.get("version")
            );
        }
        if (!"ACTIVE".equals(status)) {
            throw new PolicyCompileException("NOT_EDITABLE", "Only DRAFT or ACTIVE sets can receive new rules");
        }
        // Reuse open draft that was copied from this ACTIVE set for manual edits
        Optional<Map<String, Object>> existing = setRepository.findOpenDraft(tenantId);
        if (existing.isPresent()) {
            Map<String, Object> meta = asMap(existing.get().get("meta"));
            String from = String.valueOf(meta.getOrDefault("editFromActiveId", ""));
            if (setId.toString().equals(from) || setId.toString().equals(
                    String.valueOf(meta.getOrDefault("removalFromActiveId", "")))) {
                return Map.of(
                        "draftId", String.valueOf(existing.get().get("id")),
                        "version", existing.get().get("version"),
                        "message", "Changes go into draft v" + existing.get().get("version")
                                + " and need approval"
                );
            }
        }
        int version = setRepository.nextVersion(tenantId);
        UUID draftId = setRepository.createDraft(
                tenantId, userId,
                "Edit draft from ACTIVE v" + set.get("version"),
                version
        );
        setRepository.copyRulesExcluding(tenantId, setId, draftId, List.of());
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("editFromActiveId", setId.toString());
        setRepository.updateSetMeta(tenantId, draftId, meta);
        // Conflict check when draft is created from ACTIVE
        conflictService.checkSet(tenantId, userId, draftId, false);
        return Map.of(
                "draftId", draftId.toString(),
                "version", version,
                "message", "Changes go into draft v" + version + " and need approval"
        );
    }

    private Map<String, Object> buildAdminDirectiveRule(
            Map<String, Object> body, Map<String, Object> sourceIn
    ) {
        String basis = sourceIn.get("basis") == null
                ? (body.get("basis") == null ? "" : String.valueOf(body.get("basis")).trim())
                : String.valueOf(sourceIn.get("basis")).trim();
        if (basis.length() < 15) {
            throw new PolicyCompileException(
                    "BASIS_REQUIRED",
                    "Admin directive requires Basis / who decided (min 15 characters)"
            );
        }
        Map<String, Object> when = asMap(body.get("when"));
        if (when.isEmpty()) {
            throw new PolicyCompileException("WHEN_REQUIRED", "Condition (when) is required");
        }
        Map<String, Object> then = asMap(body.get("then"));
        if (then.isEmpty()) {
            then = new LinkedHashMap<>(Map.of("minLevel", 2));
        }
        RuleValidator.EditValidation check = RuleValidator.validateEditAdmin(when, then, sourceIn);
        if (!check.ok()) {
            throw new PolicyCompileException("INVALID_RULE", String.join("; ", check.errors()));
        }
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("kind", "ADMIN_DIRECTIVE");
        source.put("basis", basis);
        if (sourceIn.get("reference") != null) {
            source.put("reference", String.valueOf(sourceIn.get("reference")).trim());
        }
        source.put("adminDirective", true);
        String ruleId = body.get("ruleId") == null
                ? "manual-admin-" + UUID.randomUUID().toString().substring(0, 8)
                : String.valueOf(body.get("ruleId"));
        String title = body.get("title") == null || String.valueOf(body.get("title")).isBlank()
                ? RuleSourceAttributor.generateTitle(when, body.get("modality"), basis, then)
                : String.valueOf(body.get("title"));
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("ruleId", ruleId);
        rule.put("title", title);
        rule.put("when", when);
        rule.put("then", then);
        rule.put("source", source);
        rule.put("modality", body.getOrDefault("modality", "must"));
        rule.put("appliesTo", body.getOrDefault("appliesTo", Map.of("action", "*")));
        rule.put("severity", body.getOrDefault("severity", "MEDIUM"));
        rule.put("warnings", check.warnings());
        rule.put("description", basis);
        return rule;
    }

    /**
     * Simple text-box add: paste a clause → LLM extract → save to draft → conflict-check vs ACTIVE/draft.
     * Deterministic conflict only (low latency). Keywords always harvested.
     */
    @Transactional
    public Map<String, Object> addRuleFromText(
            UUID tenantId, UUID userId, String clauseText, UUID preferredSetId
    ) {
        String text = clauseText == null ? "" : clauseText.strip();
        if (text.length() < 15) {
            throw new PolicyCompileException("TEXT_REQUIRED", "Paste a policy clause (at least 15 characters)");
        }

        UUID draftId;
        String draftMessage;
        if (preferredSetId != null) {
            Map<String, Object> meta = ensureEditableDraft(tenantId, userId, preferredSetId);
            draftId = UUID.fromString(String.valueOf(meta.get("draftId")));
            draftMessage = String.valueOf(meta.get("message"));
        } else {
            Optional<Map<String, Object>> open = setRepository.findOpenDraft(tenantId);
            if (open.isPresent()) {
                draftId = UUID.fromString(String.valueOf(open.get().get("id")));
                draftMessage = "Added to draft v" + open.get().get("version");
            } else {
                Optional<Map<String, Object>> active = setRepository.findActiveSet(tenantId);
                if (active.isPresent()) {
                    Map<String, Object> meta = ensureEditableDraft(
                            tenantId, userId, UUID.fromString(String.valueOf(active.get().get("id")))
                    );
                    draftId = UUID.fromString(String.valueOf(meta.get("draftId")));
                    draftMessage = String.valueOf(meta.get("message"));
                } else {
                    int version = setRepository.nextVersion(tenantId);
                    draftId = setRepository.createDraft(tenantId, userId, "Manual rules v" + version, version);
                    draftMessage = "Created draft v" + version;
                }
            }
        }

        Map<String, Object> extracted = compileService.testClause(tenantId, text);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> validations = extracted.get("validations") instanceof List<?> v
                ? (List<Map<String, Object>>) v : List.of();

        List<Map<String, Object>> added = new ArrayList<>();
        List<Map<String, Object>> allConflicts = new ArrayList<>();
        int rejected = 0;

        for (Map<String, Object> row : validations) {
            if (Boolean.TRUE.equals(row.get("placeholder"))) {
                continue;
            }
            if (Boolean.TRUE.equals(row.get("autoReject"))) {
                rejected++;
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> ruleBody = row.get("rule") instanceof Map<?, ?> m
                    ? new LinkedHashMap<>((Map<String, Object>) m) : null;
            if (ruleBody == null || asMap(ruleBody.get("when")).isEmpty()) {
                continue;
            }

            // Rebuild as admin-directive manual rule (no PDF citation)
            Map<String, Object> sourceIn = new LinkedHashMap<>();
            sourceIn.put("kind", "ADMIN_DIRECTIVE");
            sourceIn.put("basis", text.length() > 500 ? text.substring(0, 500) : text);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("when", ruleBody.get("when"));
            body.put("then", ruleBody.get("then"));
            body.put("modality", ruleBody.getOrDefault("modality", "must"));
            body.put("appliesTo", ruleBody.get("appliesTo"));
            body.put("title", ruleBody.get("title"));
            body.put("source", sourceIn);
            Map<String, Object> attributed = buildAdminDirectiveRule(body, sourceIn);
            attributed.put("origin", "MANUAL");
            attributed.put("status", "ACCEPTED");
            attributed.put("plainEnglish", ConditionEnglish.render(
                    asMap(attributed.get("when")),
                    asMap(attributed.get("then")),
                    asMap(attributed.get("appliesTo"))
            ));
            if (!hasSimExamples(attributed.get("simulationExamples"))) {
                attributed.put("simulationExamples", defaultSimExamples(asMap(attributed.get("when"))));
            }

            // Keywords from LLM raw + heuristic from pasted text
            Object rawKeywords = null;
            // Find matching raw rule keywords if present
            if (extracted.get("rawRules") instanceof List<?> raws) {
                for (Object r : raws) {
                    if (r instanceof Map<?, ?> rm && rm.get("keywords") != null) {
                        rawKeywords = rm.get("keywords");
                        break;
                    }
                }
            }
            List<Map<String, Object>> kws = KeywordExtractor.merge(rawKeywords, text);
            attributed.put("keywords", kws.stream().map(k -> k.get("term")).toList());

            // Reuse equivalent draft rule instead of inserting a duplicate (e.g. after a failed resolve)
            conflictService.cleanupOrphans(tenantId, draftId);
            Optional<Map<String, Object>> existingEq = findEquivalentDraftRule(tenantId, draftId, attributed);
            Map<String, Object> savedRule;
            boolean reused;
            if (existingEq.isPresent()) {
                savedRule = new LinkedHashMap<>(existingEq.get());
                reused = true;
            } else {
                setRepository.insertManualRule(tenantId, draftId, attributed);
                String ruleId = String.valueOf(attributed.get("ruleId"));
                for (Map<String, Object> km : kws) {
                    setRepository.addKeywordForRule(
                            tenantId, draftId,
                            String.valueOf(km.get("term")),
                            String.valueOf(km.getOrDefault("lang", "en")),
                            String.valueOf(km.getOrDefault("category", "CUSTOM")),
                            km.get("weight") instanceof Number n ? n.doubleValue() : 1.0,
                            ruleId
                    );
                }
                auditLedgerService.append(
                        tenantId, null, AuditEventType.POLICY_RULE_ADDED, "USER", userId.toString(),
                        Map.of(
                                "ruleId", ruleId,
                                "policySetId", draftId.toString(),
                                "origin", "MANUAL",
                                "sourceKind", "TEXT_BOX"
                        )
                );
                savedRule = attributed;
                reused = false;
            }

            // Deterministic conflict check only (fast — no LLM advisory)
            List<Map<String, Object>> conflicts = conflictService.checkRule(
                    tenantId, userId, draftId, savedRule, false
            );
            allConflicts.addAll(conflicts);
            Map<String, Object> addedRow = new LinkedHashMap<>(savedRule);
            addedRow.put("conflicts", conflicts);
            addedRow.put("reused", reused);
            added.add(addedRow);
        }

        if (added.isEmpty()) {
            Map<String, Object> fail = new LinkedHashMap<>();
            fail.put("ok", false);
            fail.put("status", extracted.get("status"));
            fail.put("reason", extracted.getOrDefault("reason",
                    rejected > 0 ? "extracted rules failed validation" : "no enforceable rule found"));
            fail.put("draftId", draftId.toString());
            fail.put("extracted", extracted);
            return fail;
        }

        Map<String, Object> out = getSet(tenantId, draftId);
        out.put("ok", true);
        out.put("draftId", draftId.toString());
        out.put("draftMessage", draftMessage);
        out.put("addedRules", added);
        out.put("newConflicts", allConflicts);
        out.put("conflictCount", allConflicts.size());
        out.put("message", allConflicts.isEmpty()
                ? (Boolean.TRUE.equals(added.stream().anyMatch(a -> Boolean.TRUE.equals(a.get("reused"))))
                        ? "Same rule already in this draft — checked against live policy only"
                        : "Rule added — no conflicts with live rules")
                : "Rule added — " + allConflicts.size() + " conflict(s) with live policy; choose which to keep");
        return out;
    }

    /**
     * Reuse only a prior MANUAL add in this draft with the same when+level
     * (avoids stacking duplicates after a failed resolve). Never treats LIVE copies as duplicates here.
     */
    private Optional<Map<String, Object>> findEquivalentDraftRule(
            UUID tenantId, UUID draftId, Map<String, Object> candidate
    ) {
        var candNorm = ConditionConstraintNormalizer.normalize(candidate);
        for (Map<String, Object> existing : setRepository.listActiveRules(tenantId, draftId)) {
            if (!"MANUAL".equals(String.valueOf(existing.get("origin")))) {
                continue;
            }
            if ("REJECTED".equals(String.valueOf(existing.get("status")))) {
                continue;
            }
            if (candNorm.ruleId().equals(String.valueOf(existing.get("ruleId")))) {
                return Optional.of(existing);
            }
            var peerNorm = ConditionConstraintNormalizer.normalize(existing);
            if (ConditionConstraintNormalizer.conditionsEquivalent(candNorm, peerNorm)
                    && candNorm.minLevel() == peerNorm.minLevel()) {
                return Optional.of(existing);
            }
        }
        return Optional.empty();
    }

    /**
     * Re-attach clauseRef/quote/title for DRAFT rules missing a code-attributed source.
     */
    @Transactional
    public Map<String, Object> repairSources(UUID tenantId, UUID userId, UUID setId) {
        Map<String, Object> set = setRepository.findSet(tenantId, setId)
                .orElseThrow(() -> new PolicyCompileException("NOT_FOUND", "Policy set not found"));
        if (!"DRAFT".equals(set.get("status"))) {
            throw new PolicyCompileException("NOT_DRAFT", "Only DRAFT sets can repair sources");
        }
        List<Map<String, Object>> rules = setRepository.listRules(tenantId, setId);
        int repaired = 0;
        int skipped = 0;
        for (Map<String, Object> rule : rules) {
            if (rule.get("deletedAt") != null) {
                continue;
            }
            if (!RuleSourceAttributor.needsSourceRepair(rule)) {
                skipped++;
                continue;
            }
            Map<String, Object> source = asMap(rule.get("source"));
            UUID documentId = parseUuid(source.get("documentId"));
            UUID chunkId = firstChunkId(source);
            if (documentId == null || chunkId == null) {
                skipped++;
                continue;
            }
            PolicyDocumentChunkEntity chunk = chunkRepository
                    .findByTenantIdAndDocumentIdOrderByOrdinalAsc(tenantId, documentId)
                    .stream()
                    .filter(c -> c.getId().equals(chunkId))
                    .findFirst()
                    .orElse(null);
            if (chunk == null) {
                skipped++;
                continue;
            }
            Map<String, Object> attributed = RuleSourceAttributor.reattribute(rule, documentId, chunk);
            UUID pk = UUID.fromString(String.valueOf(rule.get("id")));
            String status = String.valueOf(attributed.getOrDefault("status", "PROPOSED"));
            setRepository.updateRuleStatus(tenantId, pk, status, attributed);
            repaired++;
        }
        auditLedgerService.append(
                tenantId, null, AuditEventType.POLICY_RULE_UPDATED, "USER", userId.toString(),
                Map.of("policySetId", setId.toString(), "repairSources", repaired, "skipped", skipped)
        );
        Map<String, Object> out = getSet(tenantId, setId);
        out.put("repaired", repaired);
        out.put("skipped", skipped);
        return out;
    }

    private void enrichRulesWithDocumentNames(UUID tenantId, List<Map<String, Object>> rules) {
        Map<String, String> titles = new java.util.HashMap<>();
        for (Map<String, Object> rule : rules) {
            Map<String, Object> source = asMap(rule.get("source"));
            Object docId = source.get("documentId");
            if (docId == null) {
                continue;
            }
            String key = String.valueOf(docId);
            if (!titles.containsKey(key)) {
                try {
                    UUID id = UUID.fromString(key);
                    String title = documentRepository.findById(id)
                            .filter(d -> tenantId.equals(d.getTenantId()))
                            .map(PolicyDocumentEntity::getTitle)
                            .orElse(null);
                    titles.put(key, title);
                } catch (Exception e) {
                    titles.put(key, null);
                }
            }
            if (titles.get(key) != null) {
                rule.put("documentTitle", titles.get(key));
                source = new LinkedHashMap<>(source);
                source.put("documentTitle", titles.get(key));
                rule.put("source", source);
            }
        }
    }

    private static UUID parseUuid(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(String.valueOf(raw));
        } catch (Exception e) {
            return null;
        }
    }

    private static UUID firstChunkId(Map<String, Object> source) {
        if (source.get("chunkId") != null) {
            return parseUuid(source.get("chunkId"));
        }
        if (source.get("chunkIds") instanceof List<?> ids && !ids.isEmpty()) {
            return parseUuid(ids.get(0));
        }
        return null;
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
        Map<String, Object> pf = liveRulesService.preflight(tenantId, setId);
        if (!Boolean.TRUE.equals(pf.get("canSubmit"))) {
            throw new PolicyCompileException("PREFLIGHT_FAILED", "Pre-flight checklist is not fully green");
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
        // Append: keep every live rule from the current ACTIVE set that this draft
        // does not already carry. Operator resolves contradictions via Conflicts —
        // approving a new version must never wipe the prior rulebook.
        int kept = 0;
        Optional<Map<String, Object>> priorActive = setRepository.findActiveSet(tenantId);
        if (priorActive.isPresent()) {
            UUID priorId = UUID.fromString(String.valueOf(priorActive.get().get("id")));
            if (!priorId.equals(setId)) {
                kept = setRepository.appendMissingRuntimeRules(tenantId, priorId, setId);
            }
        }
        String sha = setRepository.computeContentSha(tenantId, setId);
        setRepository.approve(tenantId, setId, userId, comment, sha);
        // Re-seed ACTIVE lexicon from accepted/edited rules so Accept-path wipes
        // (and missing compile inserts) cannot leave Stage A with an empty keyword list.
        rebuildKeywordsFromRules(tenantId, setId);
        Map<String, Object> activated = getSet(tenantId, setId);
        events.publishEvent(new PolicySetActivatedEvent(
                this, tenantId, setId, ((Number) activated.get("version")).intValue()
        ));
        // Post-activation: engine reload is driven by PolicySetActivatedEvent; surface sync check
        Map<String, Object> sync = liveRulesService.postActivationCheck(tenantId);
        activated.put("engineSync", sync);
        activated.put("appendedFromPriorActive", kept);
        auditLedgerService.append(
                tenantId, null, AuditEventType.POLICY_SET_APPROVED, "USER", userId.toString(),
                Map.of(
                        "policySetId", setId.toString(),
                        "contentSha256", sha,
                        "comment", comment == null ? "" : comment,
                        "appendedFromPriorActive", kept
                )
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
        String term = body.get("term") == null ? "" : String.valueOf(body.get("term")).trim();
        if (term.isBlank()) {
            throw new PolicyCompileException("BAD_REQUEST", "keyword term required");
        }
        setRepository.addKeyword(
                tenantId,
                setId,
                term,
                body.get("lang") == null ? "en" : String.valueOf(body.get("lang")),
                KeywordExtractor.normalizeCategory(String.valueOf(body.getOrDefault("category", "CUSTOM"))),
                body.get("weight") instanceof Number n ? n.doubleValue() : 1.0
        );
    }

    public void deleteKeyword(UUID tenantId, UUID keywordId) {
        setRepository.deleteKeyword(tenantId, keywordId);
    }

    /**
     * Replace {@code policy_keywords} for a set from ACCEPTED/EDITED/PROPOSED rule bodies.
     * Used on approve so the live lexicon matches what reviewers saw on the version.
     */
    public void rebuildKeywordsFromRules(UUID tenantId, UUID setId) {
        setRepository.deleteAllKeywords(tenantId, setId);
        for (Map<String, Object> rule : setRepository.listActiveRules(tenantId, setId)) {
            String st = String.valueOf(rule.getOrDefault("status", "")).toUpperCase(Locale.ROOT);
            if ("REJECTED".equals(st)) {
                continue;
            }
            String rid = String.valueOf(rule.get("ruleId"));
            String harvestText = isAdminDirective(asMap(rule.get("source")))
                    ? String.valueOf(asMap(rule.get("source")).getOrDefault("basis", ""))
                    : String.valueOf(asMap(rule.get("source")).getOrDefault("quote", ""));
            for (Map<String, Object> km : KeywordExtractor.merge(rule.get("keywords"), harvestText)) {
                String term = String.valueOf(km.getOrDefault("term", "")).trim();
                if (term.isBlank()) {
                    continue;
                }
                setRepository.addKeywordForRule(
                        tenantId, setId,
                        term,
                        String.valueOf(km.getOrDefault("lang", "en")),
                        KeywordExtractor.normalizeCategory(String.valueOf(km.getOrDefault("category", "CUSTOM"))),
                        km.get("weight") instanceof Number n ? n.doubleValue() : 1.0,
                        rid
                );
            }
        }
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

    private static boolean hasSimExamples(Object raw) {
        if (!(raw instanceof List<?> list) || list.size() < 2) {
            return false;
        }
        boolean fire = false;
        boolean noFire = false;
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                if (Boolean.TRUE.equals(m.get("expectFire"))) fire = true;
                if (Boolean.FALSE.equals(m.get("expectFire"))) noFire = true;
            }
        }
        return fire && noFire;
    }

    private static List<Map<String, Object>> defaultSimExamples(Map<String, Object> when) {
        Map<String, Object> fire = new LinkedHashMap<>();
        Map<String, Object> noFire = new LinkedHashMap<>();
        fire.put("ask.type", "WIRE_TRANSFER");
        fire.put("ask.amountInr", 2_500_000);
        fire.put("ask.beneficiaryKnown", false);
        fire.put("time.isBusinessHours", true);
        noFire.put("ask.type", "INFORMATION");
        noFire.put("ask.amountInr", 1000);
        noFire.put("ask.beneficiaryKnown", true);
        noFire.put("time.isBusinessHours", true);
        for (Map<String, Object> leaf : ConditionEnglish.collectLeaves(when)) {
            String fact = String.valueOf(leaf.get("fact"));
            String op = String.valueOf(leaf.get("op"));
            Object value = leaf.get("value");
            if ("EQ".equals(op) || "GTE".equals(op) || "GT".equals(op)) {
                fire.put(fact, value);
            }
        }
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("label", "should fire");
        a.put("expectFire", true);
        a.put("facts", fire);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("label", "should not fire");
        b.put("expectFire", false);
        b.put("facts", noFire);
        return List.of(a, b);
    }
}

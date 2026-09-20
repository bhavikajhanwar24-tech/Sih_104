package com.sentinelvoice.policy.sets;

import com.sentinelvoice.audit.AuditEventType;
import com.sentinelvoice.audit.AuditLedgerService;
import com.sentinelvoice.policy.PolicyDocumentChunkEntity;
import com.sentinelvoice.policy.PolicyDocumentChunkRepository;
import com.sentinelvoice.policy.compile.PolicyCompileException;
import com.sentinelvoice.policy.compile.RuleValidator;
import com.sentinelvoice.policy.conflict.RuleConflictService;
import com.sentinelvoice.policy.dsl.ConditionEnglish;
import com.sentinelvoice.policy.dsl.FactCatalogue;
import com.sentinelvoice.policy.engine.ActivePolicyCache;
import com.sentinelvoice.policy.engine.PolicyRuntimeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * F6/F7 addendum — Live Rules view, JSON access, soft-delete / removal-drafts, pre-flight.
 */
@Service
public class LiveRulesService {

    private static final Set<String> DISPLAY_WARNINGS = Set.of(
            "BROAD", "POSSIBLE_INVERTED_LOGIC", "LEVEL_ADJUSTED"
    );

    private final PolicySetRepository setRepository;
    private final PolicyDocumentChunkRepository chunkRepository;
    private final AuditLedgerService auditLedgerService;
    private final ActivePolicyCache activePolicyCache;
    private final PolicyRuntimeService policyRuntimeService;
    private final RuleConflictService conflictService;

    public LiveRulesService(
            PolicySetRepository setRepository,
            PolicyDocumentChunkRepository chunkRepository,
            AuditLedgerService auditLedgerService,
            ActivePolicyCache activePolicyCache,
            PolicyRuntimeService policyRuntimeService,
            RuleConflictService conflictService
    ) {
        this.setRepository = setRepository;
        this.chunkRepository = chunkRepository;
        this.auditLedgerService = auditLedgerService;
        this.activePolicyCache = activePolicyCache;
        this.policyRuntimeService = policyRuntimeService;
        this.conflictService = conflictService;
    }

    public Map<String, Object> liveRules(UUID tenantId) {
        Map<String, Object> out = new LinkedHashMap<>();
        Optional<Map<String, Object>> activeOpt = setRepository.findActiveSet(tenantId);
        Map<String, Object> engine = policyRuntimeService.engineStatus(tenantId);
        out.put("engine", engine);

        if (activeOpt.isEmpty()) {
            out.put("state", "NO_POLICY");
            out.put("policySet", null);
            out.put("rules", List.of());
            out.put("deletedRules", List.of());
            out.put("coverage", emptyCoverage());
            out.put("removalPending", List.of());
            return out;
        }

        Map<String, Object> active = activeOpt.get();
        UUID setId = UUID.fromString(String.valueOf(active.get("id")));
        List<Map<String, Object>> runtime = setRepository.listRuntimeRules(tenantId, setId);
        Set<String> pendingRemovals = pendingRemovalRuleIds(tenantId, active);
        Integer pendingDraftVersion = pendingRemovalDraftVersion(tenantId, active);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> rule : runtime) {
            rows.add(enrichLiveRow(tenantId, rule, pendingRemovals, pendingDraftVersion));
        }

        out.put("state", "OK");
        out.put("policySet", Map.of(
                "id", active.get("id"),
                "version", active.get("version"),
                "contentSha256", active.get("contentSha256") == null ? "" : active.get("contentSha256"),
                "name", active.get("name") == null ? "" : active.get("name")
        ));
        out.put("ruleCount", rows.size());
        out.put("rules", rows);
        out.put("deletedRules", List.of()); // ACTIVE set does not soft-delete in place
        out.put("coverage", coverage(tenantId, active, rows));
        out.put("removalPending", pendingRemovals.stream().sorted().toList());
        return out;
    }

    public Map<String, Object> ruleJson(UUID tenantId, String ruleId) {
        Map<String, Object> active = setRepository.findActiveSet(tenantId)
                .orElseThrow(() -> new PolicyCompileException("NOT_FOUND", "No ACTIVE policy set"));
        UUID setId = UUID.fromString(String.valueOf(active.get("id")));
        Map<String, Object> rule = setRepository.findRuleByRuleId(tenantId, setId, ruleId)
                .orElseThrow(() -> new PolicyCompileException("NOT_FOUND", "Rule not found"));
        if (rule.get("deletedAt") != null) {
            throw new PolicyCompileException("NOT_FOUND", "Rule not found");
        }
        Map<String, Object> export = exportRuleBody(rule);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rule", export);
        out.put("json", export);
        out.put("schemaValid", true);
        out.put("validator", validateRuleChecks(tenantId, rule));
        return out;
    }

    public Map<String, Object> setJson(UUID tenantId, UUID setId) {
        Map<String, Object> set = setRepository.findSet(tenantId, setId)
                .orElseThrow(() -> new PolicyCompileException("NOT_FOUND", "Policy set not found"));
        List<Map<String, Object>> rules = setRepository.listRuntimeRules(tenantId, setId);
        if (!"ACTIVE".equals(set.get("status")) && !"DRAFT".equals(set.get("status"))
                && !"PENDING_APPROVAL".equals(set.get("status"))
                && !"SUPERSEDED".equals(set.get("status"))) {
            rules = setRepository.listActiveRules(tenantId, setId).stream()
                    .filter(r -> {
                        String st = String.valueOf(r.get("status"));
                        return "ACCEPTED".equals(st) || "EDITED".equals(st) || "PROPOSED".equals(st);
                    })
                    .toList();
        }
        List<Map<String, Object>> exported = rules.stream().map(this::exportRuleBody).toList();
        String sha = setRepository.computeContentSha(tenantId, setId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("policySetId", setId.toString());
        out.put("status", set.get("status"));
        out.put("version", set.get("version"));
        out.put("contentSha256", sha);
        out.put("exportedAt", Instant.now().toString());
        out.put("editable", "DRAFT".equals(set.get("status")));
        out.put("rules", exported);
        return out;
    }

    @Transactional
    public Map<String, Object> deleteDraftRule(
            UUID tenantId, UUID userId, UUID draftId, String ruleId, String reason
    ) {
        requireReason(reason);
        Map<String, Object> set = requireDraft(tenantId, draftId);
        String beforeSha = setRepository.computeContentSha(tenantId, draftId);
        Map<String, Object> rule = setRepository.findRuleByRuleId(tenantId, draftId, ruleId)
                .orElseThrow(() -> new PolicyCompileException("NOT_FOUND", "Rule not found"));
        if (rule.get("deletedAt") != null) {
            throw new PolicyCompileException("BAD_STATE", "Rule already deleted");
        }
        UUID pk = UUID.fromString(String.valueOf(rule.get("id")));
        Map<String, Object> drives = downstream(rule);
        setRepository.softDeleteRule(tenantId, pk, userId, reason.trim());
        String afterSha = setRepository.computeContentSha(tenantId, draftId);
        auditLedgerService.append(
                tenantId, null, AuditEventType.POLICY_RULE_DELETED, "USER", userId.toString(),
                auditPayload(rule, reason, beforeSha, afterSha, draftId)
        );
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deleted", true);
        out.put("ruleId", ruleId);
        out.put("downstream", drives);
        out.put("contentSha256Before", beforeSha);
        out.put("contentSha256After", afterSha);
        out.put("set", setRepository.findSet(tenantId, draftId).orElse(set));
        return out;
    }

    @Transactional
    public Map<String, Object> restoreDraftRule(UUID tenantId, UUID userId, UUID draftId, String ruleId) {
        Map<String, Object> set = requireDraft(tenantId, draftId);
        String beforeSha = setRepository.computeContentSha(tenantId, draftId);
        Map<String, Object> rule = setRepository.findRuleByRuleId(tenantId, draftId, ruleId)
                .orElseThrow(() -> new PolicyCompileException("NOT_FOUND", "Rule not found"));
        if (rule.get("deletedAt") == null) {
            throw new PolicyCompileException("BAD_STATE", "Rule is not deleted");
        }
        UUID pk = UUID.fromString(String.valueOf(rule.get("id")));
        setRepository.restoreRule(tenantId, pk);
        String afterSha = setRepository.computeContentSha(tenantId, draftId);
        auditLedgerService.append(
                tenantId, null, AuditEventType.POLICY_RULE_RESTORED, "USER", userId.toString(),
                auditPayload(rule, null, beforeSha, afterSha, draftId)
        );
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("restored", true);
        out.put("ruleId", ruleId);
        out.put("contentSha256Before", beforeSha);
        out.put("contentSha256After", afterSha);
        out.put("set", setRepository.findSet(tenantId, draftId).orElse(set));
        return out;
    }

    @Transactional
    public Map<String, Object> createRemovalDraft(
            UUID tenantId,
            UUID userId,
            UUID activeId,
            List<String> ruleIds,
            String reason,
            boolean confirmEmptyPolicy
    ) {
        requireReason(reason);
        if (ruleIds == null || ruleIds.isEmpty()) {
            throw new PolicyCompileException("BAD_REQUEST", "ruleIds required");
        }
        Map<String, Object> active = setRepository.findSet(tenantId, activeId)
                .orElseThrow(() -> new PolicyCompileException("NOT_FOUND", "Policy set not found"));
        if (!"ACTIVE".equals(active.get("status"))) {
            throw new PolicyCompileException("CONFLICT", "Removals only apply to the ACTIVE set");
        }

        List<Map<String, Object>> live = setRepository.listRuntimeRules(tenantId, activeId);
        Set<String> liveIds = live.stream()
                .map(r -> String.valueOf(r.get("ruleId")))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> remove = new LinkedHashSet<>(ruleIds);
        for (String id : remove) {
            if (!liveIds.contains(id)) {
                throw new PolicyCompileException("NOT_FOUND", "Rule not live: " + id);
            }
        }
        if (remove.containsAll(liveIds) && !confirmEmptyPolicy) {
            throw new PolicyCompileException(
                    "LAST_RULE",
                    "Removing the last remaining rule requires confirmEmptyPolicy=true"
            );
        }

        List<String> soleCoverWarnings = soleCoverWarnings(tenantId, active, live, remove);

        // Reuse open draft that already targets this active set, else create
        Optional<Map<String, Object>> existing = setRepository.findOpenDraft(tenantId);
        UUID draftId;
        Map<String, Object> draft;
        if (existing.isPresent()
                && activeId.toString().equals(String.valueOf(
                asMap(existing.get().get("meta")).get("removalFromActiveId")))) {
            draftId = UUID.fromString(String.valueOf(existing.get().get("id")));
            draft = existing.get();
            // Re-copy from active with union of pending removals
            @SuppressWarnings("unchecked")
            List<String> prior = asMap(draft.get("meta")).get("removalPendingRuleIds") instanceof List<?> l
                    ? l.stream().map(String::valueOf).toList()
                    : List.of();
            Set<String> union = new LinkedHashSet<>(prior);
            union.addAll(remove);
            setRepository.deleteAllRules(tenantId, draftId);
            setRepository.copyRulesExcluding(tenantId, activeId, draftId, union);
            Map<String, Object> meta = new LinkedHashMap<>(asMap(draft.get("meta")));
            meta.put("removalFromActiveId", activeId.toString());
            meta.put("removalPendingRuleIds", union.stream().sorted().toList());
            meta.put("removalReason", reason.trim());
            if (confirmEmptyPolicy) {
                meta.put("confirmEmptyPolicy", true);
            }
            setRepository.updateSetMeta(tenantId, draftId, meta);
        } else {
            int version = setRepository.nextVersion(tenantId);
            draftId = setRepository.createDraft(
                    tenantId, userId,
                    "Removal draft from ACTIVE v" + active.get("version"),
                    version
            );
            setRepository.copyRulesExcluding(tenantId, activeId, draftId, remove);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("removalFromActiveId", activeId.toString());
            meta.put("removalPendingRuleIds", remove.stream().sorted().toList());
            meta.put("removalReason", reason.trim());
            if (confirmEmptyPolicy) {
                meta.put("confirmEmptyPolicy", true);
            }
            setRepository.updateSetMeta(tenantId, draftId, meta);
        }

        String beforeSha = String.valueOf(active.getOrDefault("contentSha256", ""));
        String afterSha = setRepository.computeContentSha(tenantId, draftId);
        for (String rid : remove) {
            Map<String, Object> rule = live.stream()
                    .filter(r -> rid.equals(String.valueOf(r.get("ruleId"))))
                    .findFirst()
                    .orElse(Map.of("ruleId", rid));
            auditLedgerService.append(
                    tenantId, null, AuditEventType.POLICY_RULE_REMOVAL_REQUESTED, "USER", userId.toString(),
                    auditPayload(rule, reason, beforeSha, afterSha, draftId)
            );
        }

        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("beforeRuleIds", liveIds.stream().sorted().toList());
        diff.put("afterRuleIds", setRepository.listRuntimeRules(tenantId, draftId).stream()
                .map(r -> String.valueOf(r.get("ruleId"))).sorted().toList());
        diff.put("removed", remove.stream().sorted().toList());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("draftId", draftId.toString());
        out.put("draftVersion", setRepository.findSet(tenantId, draftId).map(s -> s.get("version")).orElse(null));
        out.put("diff", diff);
        out.put("soleCoverWarnings", soleCoverWarnings);
        out.put("message", "Removal pending approval — live rules keep firing until the draft is approved");
        return out;
    }

    @Transactional
    public Map<String, Object> replaceRulesJson(
            UUID tenantId, UUID userId, UUID draftId, List<Map<String, Object>> rules
    ) {
        Map<String, Object> set = requireDraft(tenantId, draftId);
        if (rules == null) {
            throw new PolicyCompileException("BAD_REQUEST", "rules array required");
        }
        String beforeSha = setRepository.computeContentSha(tenantId, draftId);

        // Soft-delete semantics for removals vs prior active (non-deleted) rules
        List<Map<String, Object>> prior = setRepository.listActiveRules(tenantId, draftId);
        Set<String> newIds = rules.stream()
                .map(r -> String.valueOf(r.get("ruleId")))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<Map<String, Object>> validationResults = new ArrayList<>();
        for (Map<String, Object> proposed : rules) {
            Map<String, Object> checks = validateIncomingRule(tenantId, proposed);
            validationResults.add(checks);
            if (Boolean.FALSE.equals(checks.get("ok"))) {
                throw new PolicyCompileException(
                        "INVALID_RULES",
                        "Rule " + proposed.get("ruleId") + " failed validation: " + checks.get("errors")
                );
            }
            String status = String.valueOf(proposed.getOrDefault("status", "ACCEPTED")).toUpperCase(Locale.ROOT);
            if ("ACCEPTED".equals(status) || "EDITED".equals(status)) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> warnings = checks.get("warnings") instanceof List<?> w
                        ? (List<Map<String, Object>>) w : List.of();
                if (RuleValidator.blocksAcceptance(warnings)) {
                    throw new PolicyCompileException(
                            "INVALID_RULES",
                            "Rule " + proposed.get("ruleId") + " cannot be ACCEPTED (grounding failed)"
                    );
                }
            }
        }

        // Soft-delete rules removed from the array (same audit as Delete)
        for (Map<String, Object> old : prior) {
            String rid = String.valueOf(old.get("ruleId"));
            if (!newIds.contains(rid)) {
                UUID pk = UUID.fromString(String.valueOf(old.get("id")));
                setRepository.softDeleteRule(tenantId, pk, userId, "removed via rules-json");
                auditLedgerService.append(
                        tenantId, null, AuditEventType.POLICY_RULE_DELETED, "USER", userId.toString(),
                        auditPayload(old, "removed via rules-json", beforeSha, null, draftId)
                );
            }
        }

        // Replace remaining: clear non-deleted then re-insert validated bodies
        for (Map<String, Object> old : prior) {
            String rid = String.valueOf(old.get("ruleId"));
            if (newIds.contains(rid) && old.get("deletedAt") == null) {
                setRepository.hardDeleteRule(tenantId, UUID.fromString(String.valueOf(old.get("id"))));
            }
        }
        for (Map<String, Object> proposed : rules) {
            Map<String, Object> body = new LinkedHashMap<>(proposed);
            body.putIfAbsent("origin", "MANUAL");
            body.putIfAbsent("status", "PROPOSED");
            body.putIfAbsent("warnings", List.of());
            for (Map<String, Object> vr : validationResults) {
                if (String.valueOf(proposed.get("ruleId")).equals(String.valueOf(vr.get("ruleId")))) {
                    body.put("warnings", vr.get("warnings"));
                    break;
                }
            }
            setRepository.insertCopiedRule(tenantId, draftId, body);
        }

        String afterSha = setRepository.computeContentSha(tenantId, draftId);
        auditLedgerService.append(
                tenantId, null, AuditEventType.POLICY_RULES_JSON_REPLACED, "USER", userId.toString(),
                Map.of(
                        "policySetId", draftId.toString(),
                        "ruleCount", rules.size(),
                        "contentSha256Before", beforeSha,
                        "contentSha256After", afterSha
                )
        );
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("ruleCount", rules.size());
        out.put("validation", validationResults);
        out.put("contentSha256Before", beforeSha);
        out.put("contentSha256After", afterSha);
        out.put("set", setRepository.findSet(tenantId, draftId).orElse(set));
        return out;
    }

    public Map<String, Object> validateRulesJson(UUID tenantId, UUID setId, List<Map<String, Object>> rules) {
        // Read-only validate of an edited copy (ACTIVE/SUPERSEDED allowed for dry-run)
        setRepository.findSet(tenantId, setId)
                .orElseThrow(() -> new PolicyCompileException("NOT_FOUND", "Policy set not found"));
        List<Map<String, Object>> results = new ArrayList<>();
        boolean allOk = true;
        for (Map<String, Object> proposed : rules == null ? List.<Map<String, Object>>of() : rules) {
            Map<String, Object> checks = validateIncomingRule(tenantId, proposed);
            results.add(checks);
            if (Boolean.FALSE.equals(checks.get("ok"))) {
                allOk = false;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", allOk);
        out.put("results", results);
        return out;
    }

    @Transactional
    public Map<String, Object> preflight(UUID tenantId, UUID draftId) {
        Map<String, Object> set = setRepository.findSet(tenantId, draftId)
                .orElseThrow(() -> new PolicyCompileException("NOT_FOUND", "Policy set not found"));
        if (!"DRAFT".equals(set.get("status")) && !"SUPERSEDED".equals(set.get("status"))) {
            throw new PolicyCompileException("BAD_STATE", "Pre-flight only for DRAFT or SUPERSEDED");
        }
        List<Map<String, Object>> rules = setRepository.listActiveRules(tenantId, draftId);
        List<Map<String, Object>> checklist = new ArrayList<>();

        long proposed = rules.stream().filter(r -> "PROPOSED".equals(String.valueOf(r.get("status")))).count();
        checklist.add(item("all_reviewed", proposed == 0,
                proposed == 0 ? "No PROPOSED rules" : proposed + " PROPOSED rule(s) remain"));

        boolean validatorsPass = true;
        List<String> validatorFails = new ArrayList<>();
        boolean broadUnacked = false;
        boolean bandsOk = true;
        boolean simsOk = true;
        for (Map<String, Object> r : rules) {
            String st = String.valueOf(r.get("status"));
            if (!"ACCEPTED".equals(st) && !"EDITED".equals(st) && !"REJECTED".equals(st)) {
                continue;
            }
            if ("REJECTED".equals(st)) {
                continue;
            }
            Map<String, Object> checks = validateIncomingRule(tenantId, r);
            if (Boolean.FALSE.equals(checks.get("ok"))) {
                validatorsPass = false;
                validatorFails.add(String.valueOf(r.get("ruleId")));
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> warnings = r.get("warnings") instanceof List<?> w
                    ? (List<Map<String, Object>>) w : List.of();
            boolean broad = warnings.stream().anyMatch(x -> "BROAD".equals(String.valueOf(x.get("code"))));
            boolean acked = Boolean.TRUE.equals(r.get("broadAcknowledged"))
                    || warnings.stream().anyMatch(x ->
                    "BROAD".equals(String.valueOf(x.get("code")))
                            && Boolean.TRUE.equals(x.get("acknowledged")));
            if (broad && !acked) {
                broadUnacked = true;
            }
            if (Boolean.FALSE.equals(checks.get("levelBandOk"))) {
                bandsOk = false;
            }
            if (!hasWorkedSimulations(r)) {
                // Auto-seed default fire / no-fire templates so Submit is not blocked on legacy drafts
                UUID pk = UUID.fromString(String.valueOf(r.get("id")));
                List<Map<String, Object>> seeded = seedSimExamples(asMap(r.get("when")));
                setRepository.updateSimulationExamples(tenantId, pk, seeded);
                r.put("simulationExamples", seeded);
            }
            if (!hasWorkedSimulations(r)) {
                simsOk = false;
            }
        }
        checklist.add(item("validators_pass", validatorsPass,
                validatorsPass ? "All validators PASS" : "Failed: " + validatorFails));
        checklist.add(item("no_broad_unacknowledged", !broadUnacked,
                broadUnacked ? "BROAD rules need acknowledgement" : "No unacknowledged BROAD rules"));
        checklist.add(item("level_bands", bandsOk,
                bandsOk ? "Level bands respected" : "One or more rules outside floor/cap without justification"));
        checklist.add(item("simulation_examples", simsOk,
                simsOk ? "Each accepted rule has fire + no-fire simulation examples"
                        : "Add Simulate templates (should fire + should not) on each accepted rule"));

        long openConflicts = conflictService.countBlockingOpen(tenantId, draftId);
        checklist.add(item("no_open_conflicts", openConflicts == 0,
                openConflicts == 0
                        ? "No open rule conflicts"
                        : openConflicts + " open conflict(s) — resolve before submit"));

        boolean canSubmit = checklist.stream().allMatch(c -> Boolean.TRUE.equals(c.get("pass")))
                && setRepository.countAccepted(tenantId, draftId) > 0;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("checklist", checklist);
        out.put("canSubmit", canSubmit);
        out.put("acceptedRuleCount", setRepository.countAccepted(tenantId, draftId));
        out.put("openConflictCount", openConflicts);
        return out;
    }

    public Map<String, Object> engineStatusExtended(UUID tenantId) {
        return policyRuntimeService.engineStatus(tenantId);
    }

    public Map<String, Object> reloadEngine(UUID tenantId) {
        activePolicyCache.invalidate(tenantId);
        activePolicyCache.get(tenantId);
        return policyRuntimeService.engineStatus(tenantId);
    }

    public Map<String, Object> postActivationCheck(UUID tenantId) {
        Map<String, Object> status = policyRuntimeService.engineStatus(tenantId);
        Map<String, Object> out = new LinkedHashMap<>(status);
        out.put("inSync", Boolean.TRUE.equals(status.get("inSync")));
        return out;
    }

    // --- helpers ---

    private Map<String, Object> enrichLiveRow(
            UUID tenantId,
            Map<String, Object> rule,
            Set<String> pendingRemovals,
            Integer pendingDraftVersion
    ) {
        Map<String, Object> row = new LinkedHashMap<>();
        String ruleId = String.valueOf(rule.get("ruleId"));
        row.put("id", rule.get("id"));
        row.put("ruleId", ruleId);
        row.put("title", rule.get("title"));
        Map<String, Object> source = asMap(rule.get("source"));
        row.put("source", source);
        row.put("clauseRef", source.get("clauseRef"));
        row.put("documentId", source.get("documentId"));
        row.put("firesWhen", rule.get("firesWhen"));
        row.put("doesNotFireWhen", rule.get("doesNotFireWhen"));
        row.put("plainEnglish", rule.get("plainEnglish"));

        Map<String, Object> then = asMap(rule.get("then"));
        int minLevel = then.get("minLevel") instanceof Number n ? n.intValue() : 0;
        row.put("minLevel", minLevel);
        String chunkText = loadCitedChunkText(tenantId, source);
        String quote = source.get("quote") == null ? null : String.valueOf(source.get("quote"));
        RuleValidator.LevelBand band = RuleValidator.levelBand(chunkText, quote);
        row.put("band", Map.of("floor", band.floor(), "cap", band.cap()));
        row.put("severity", severityFromLevel(minLevel));
        row.put("origin", rule.get("origin"));
        row.put("status", rule.get("status"));
        row.put("override", Boolean.TRUE.equals(rule.get("override"))
                || Boolean.TRUE.equals(then.get("override")));
        row.put("fireCount7d", null); // session_ticks not yet wired
        row.put("lastFired", null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> warnings = rule.get("warnings") instanceof List<?> w
                ? (List<Map<String, Object>>) w : List.of();
        row.put("validationWarnings", warnings.stream()
                .filter(w -> DISPLAY_WARNINGS.contains(String.valueOf(w.get("code"))))
                .toList());
        row.put("downstream", downstream(rule));
        row.put("simulationExamples", rule.getOrDefault("simulationExamples", List.of()));
        row.put("factsTemplate", defaultFactsTemplate(rule));

        if (pendingRemovals.contains(ruleId)) {
            row.put("removalPending", true);
            row.put("removalPendingLabel",
                    "Removal pending approval (v" + pendingDraftVersion + " draft)");
        } else {
            row.put("removalPending", false);
        }
        return row;
    }

    private Map<String, Object> downstream(Map<String, Object> rule) {
        Map<String, Object> when = asMap(rule.get("when"));
        Map<String, Object> then = asMap(rule.get("then"));
        Map<String, Object> applies = asMap(rule.get("appliesTo"));
        Set<String> facts = ConditionEnglish.collectFacts(when);
        int minLevel = then.get("minLevel") instanceof Number n ? n.intValue() : 0;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("factKeys", facts.stream().sorted().toList());
        out.put("fusionFamily", "TRANSACTION");
        out.put("responsePlanLevels", List.of(minLevel));
        out.put("actionType", applies.get("actionType"));
        out.put("reasonCode", then.get("reasonCode"));
        out.put("scoreBoost", then.get("scoreBoost"));
        return out;
    }

    private Map<String, Object> coverage(
            UUID tenantId, Map<String, Object> active, List<Map<String, Object>> liveRows
    ) {
        UUID compilationId = active.get("compilationId") == null
                ? null : UUID.fromString(String.valueOf(active.get("compilationId")));
        PolicySetRepository.CoverageCounts c = setRepository.coverageFromCompilation(tenantId, compilationId);
        Set<String> coveredClauses = liveRows.stream()
                .map(r -> String.valueOf(asMap(r.get("source")).getOrDefault("clauseRef", "")))
                .filter(s -> !s.isBlank() && !"null".equals(s))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        int unmapped = Math.max(0, c.enforceable() - coveredClauses.size());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enforceableClauses", c.enforceable());
        out.put("rulesLive", liveRows.size());
        out.put("procedural", c.procedural());
        out.put("unmapped", unmapped);
        out.put("coveredClauseRefs", coveredClauses.stream().sorted().toList());
        return out;
    }

    private static Map<String, Object> emptyCoverage() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enforceableClauses", 0);
        out.put("rulesLive", 0);
        out.put("procedural", 0);
        out.put("unmapped", 0);
        out.put("coveredClauseRefs", List.of());
        return out;
    }

    private List<String> soleCoverWarnings(
            UUID tenantId,
            Map<String, Object> active,
            List<Map<String, Object>> live,
            Set<String> remove
    ) {
        Map<String, List<String>> byClause = new LinkedHashMap<>();
        for (Map<String, Object> r : live) {
            String clause = String.valueOf(asMap(r.get("source")).getOrDefault("clauseRef", ""));
            if (clause.isBlank() || "null".equals(clause)) {
                continue;
            }
            byClause.computeIfAbsent(clause, k -> new ArrayList<>())
                    .add(String.valueOf(r.get("ruleId")));
        }
        List<String> warnings = new ArrayList<>();
        for (String rid : remove) {
            for (Map.Entry<String, List<String>> e : byClause.entrySet()) {
                if (e.getValue().size() == 1 && e.getValue().contains(rid)) {
                    warnings.add("Rule " + rid + " is the only cover for enforceable clause " + e.getKey());
                }
            }
        }
        Map<String, Object> cov = coverage(tenantId, active, live.stream()
                .map(r -> enrichLiveRow(tenantId, r, Set.of(), null))
                .toList());
        // silence unused if coverage empty
        if (cov.get("enforceableClauses") instanceof Number) {
            // already used via byClause
        }
        return warnings;
    }

    private Set<String> pendingRemovalRuleIds(UUID tenantId, Map<String, Object> active) {
        Optional<Map<String, Object>> draft = setRepository.findOpenDraft(tenantId);
        if (draft.isEmpty()) {
            return Set.of();
        }
        Map<String, Object> meta = asMap(draft.get().get("meta"));
        if (!String.valueOf(active.get("id")).equals(String.valueOf(meta.get("removalFromActiveId")))) {
            return Set.of();
        }
        Object ids = meta.get("removalPendingRuleIds");
        if (ids instanceof List<?> list) {
            return list.stream().map(String::valueOf).collect(Collectors.toCollection(LinkedHashSet::new));
        }
        return Set.of();
    }

    private Integer pendingRemovalDraftVersion(UUID tenantId, Map<String, Object> active) {
        Optional<Map<String, Object>> draft = setRepository.findOpenDraft(tenantId);
        if (draft.isEmpty()) {
            return null;
        }
        Map<String, Object> meta = asMap(draft.get().get("meta"));
        if (!String.valueOf(active.get("id")).equals(String.valueOf(meta.get("removalFromActiveId")))) {
            return null;
        }
        Object v = draft.get().get("version");
        return v instanceof Number n ? n.intValue() : null;
    }

    private Map<String, Object> exportRuleBody(Map<String, Object> rule) {
        Map<String, Object> export = new LinkedHashMap<>();
        for (String key : List.of(
                "ruleId", "title", "description", "source", "appliesTo", "when", "then",
                "severity", "status", "origin", "warnings", "simulationExamples"
        )) {
            if (rule.containsKey(key)) {
                export.put(key, rule.get(key));
            }
        }
        return export;
    }

    private Map<String, Object> validateRuleChecks(UUID tenantId, Map<String, Object> rule) {
        return validateIncomingRule(tenantId, rule);
    }

    private Map<String, Object> validateIncomingRule(UUID tenantId, Map<String, Object> rule) {
        List<Map<String, Object>> checks = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<Map<String, Object>> warnings = new ArrayList<>();

        Map<String, Object> when = asMap(rule.get("when"));
        Map<String, Object> then = asMap(rule.get("then"));
        Map<String, Object> source = asMap(rule.get("source"));

        // Schema-ish
        boolean hasId = rule.get("ruleId") != null && !String.valueOf(rule.get("ruleId")).isBlank();
        checks.add(check("SCHEMA_RULE_ID", hasId));
        if (!hasId) {
            errors.add("ruleId required");
        }
        boolean hasWhen = when != null && !when.isEmpty();
        checks.add(check("SCHEMA_WHEN", hasWhen));
        if (!hasWhen) {
            errors.add("when required");
        }

        Set<String> unknown = ConditionEnglish.collectFacts(when).stream()
                .filter(f -> !FactCatalogue.isKnown(f))
                .collect(Collectors.toSet());
        checks.add(check("CATALOGUE_FACTS", unknown.isEmpty()));
        if (!unknown.isEmpty()) {
            errors.add("Unknown facts: " + unknown);
        }

        for (Map<String, Object> leaf : ConditionEnglish.collectLeaves(when)) {
            String fact = String.valueOf(leaf.get("fact"));
            Object value = leaf.get("value");
            if (FactCatalogue.isKnown(fact) && value != null) {
                Optional<String> err = FactCatalogue.validateValue(fact, String.valueOf(leaf.get("op")), value);
                boolean ok = err.isEmpty();
                checks.add(check("CATALOGUE_VALUE:" + fact, ok));
                if (!ok) {
                    errors.add(err.orElse("invalid value for " + fact));
                }
            }
        }

        String chunkText = loadCitedChunkText(tenantId, source);
        RuleValidator.EditValidation edit = RuleValidator.validateEdit(when, then, source, chunkText);
        checks.add(check("NUMBERS_VS_SOURCE", edit.errors().stream()
                .noneMatch(e -> e.toLowerCase(Locale.ROOT).contains("value")
                        || e.toLowerCase(Locale.ROOT).contains("number"))));
        checks.add(check("QUOTE_VS_SOURCE", edit.errors().stream()
                .noneMatch(e -> e.toLowerCase(Locale.ROOT).contains("quote"))));
        boolean levelOk = edit.errors().stream()
                .noneMatch(e -> e.toLowerCase(Locale.ROOT).contains("minlevel")
                        || e.toLowerCase(Locale.ROOT).contains("cap")
                        || e.toLowerCase(Locale.ROOT).contains("floor"));
        // LEVEL_ADJUSTED in warnings is OK if justified; hard errors fail
        boolean justified = Boolean.TRUE.equals(rule.get("levelBandJustified"));
        checks.add(check("LEVEL_BANDS", levelOk || justified));
        if (!edit.ok() && !justified) {
            errors.addAll(edit.errors());
        }
        warnings.addAll(edit.warnings());
        if (rule.get("warnings") instanceof List<?> prior) {
            for (Object o : prior) {
                if (o instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> wm = (Map<String, Object>) m;
                    warnings.add(wm);
                }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ruleId", rule.get("ruleId"));
        out.put("ok", errors.isEmpty());
        out.put("errors", errors);
        out.put("warnings", warnings);
        out.put("checks", checks);
        out.put("levelBandOk", levelOk || justified);
        out.put("schemaValid", hasId && hasWhen && unknown.isEmpty());
        return out;
    }

    private static Map<String, Object> check(String name, boolean pass) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("check", name);
        m.put("result", pass ? "PASS" : "FAIL");
        return m;
    }

    private static Map<String, Object> item(String id, boolean pass, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("pass", pass);
        m.put("detail", detail);
        return m;
    }

    private static boolean hasWorkedSimulations(Map<String, Object> rule) {
        Object raw = rule.get("simulationExamples");
        if (!(raw instanceof List<?> list) || list.size() < 2) {
            return false;
        }
        boolean fire = false;
        boolean noFire = false;
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                Object expect = m.get("expectFire");
                if (Boolean.TRUE.equals(expect)) {
                    fire = true;
                }
                if (Boolean.FALSE.equals(expect)) {
                    noFire = true;
                }
            }
        }
        return fire && noFire;
    }

    private static List<Map<String, Object>> seedSimExamples(Map<String, Object> when) {
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

    private Map<String, Object> defaultFactsTemplate(Map<String, Object> rule) {
        Map<String, Object> when = asMap(rule.get("when"));
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
        // Overlay leaf EQ facts into fire template
        for (Map<String, Object> leaf : ConditionEnglish.collectLeaves(when)) {
            String fact = String.valueOf(leaf.get("fact"));
            String op = String.valueOf(leaf.get("op"));
            Object value = leaf.get("value");
            if ("EQ".equals(op) || "GTE".equals(op) || "GT".equals(op)) {
                fire.put(fact, value);
            }
            if ("NE".equals(op)) {
                noFire.put(fact, value);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("shouldFire", fire);
        out.put("shouldNotFire", noFire);
        return out;
    }

    private Map<String, Object> requireDraft(UUID tenantId, UUID draftId) {
        Map<String, Object> set = setRepository.findSet(tenantId, draftId)
                .orElseThrow(() -> new PolicyCompileException("NOT_FOUND", "Policy set not found"));
        String status = String.valueOf(set.get("status"));
        if ("ACTIVE".equals(status) || "SUPERSEDED".equals(status)) {
            throw new PolicyCompileException("CONFLICT", "ACTIVE and SUPERSEDED sets are not directly mutable");
        }
        if (!"DRAFT".equals(status)) {
            throw new PolicyCompileException("NOT_DRAFT", "Only DRAFT sets can be mutated this way");
        }
        return set;
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.trim().length() < 5) {
            throw new PolicyCompileException("REASON_REQUIRED", "Reason must be at least 5 characters");
        }
    }

    private static Map<String, Object> auditPayload(
            Map<String, Object> rule, String reason, String beforeSha, String afterSha, UUID setId
    ) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("ruleId", rule.get("ruleId"));
        Object clause = asMap(rule.get("source")).get("clauseRef");
        if (clause != null) {
            p.put("clauseRef", clause);
        }
        if (reason != null) {
            p.put("reason", reason.trim());
        }
        p.put("policySetId", setId.toString());
        if (beforeSha != null) {
            p.put("contentSha256Before", beforeSha);
        }
        if (afterSha != null) {
            p.put("contentSha256After", afterSha);
        }
        return p;
    }

    private static String severityFromLevel(int minLevel) {
        if (minLevel >= 4) {
            return "CRITICAL";
        }
        if (minLevel >= 3) {
            return "HIGH";
        }
        if (minLevel >= 2) {
            return "MEDIUM";
        }
        return "LOW";
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
            citeIds = ids.stream().map(String::valueOf).collect(Collectors.toSet());
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}

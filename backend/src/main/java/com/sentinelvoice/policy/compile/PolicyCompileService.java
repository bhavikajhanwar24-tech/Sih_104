package com.sentinelvoice.policy.compile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelvoice.audit.AuditEventType;
import com.sentinelvoice.audit.AuditLedgerService;
import com.sentinelvoice.llm.LlmGatewayClient;
import com.sentinelvoice.policy.PolicyCompileProperties;
import com.sentinelvoice.policy.PolicyDocumentChunkEntity;
import com.sentinelvoice.policy.PolicyDocumentChunkRepository;
import com.sentinelvoice.policy.PolicyDocumentEntity;
import com.sentinelvoice.policy.PolicyDocumentRepository;
import com.sentinelvoice.policy.conflict.RuleConflictService;
import com.sentinelvoice.policy.dsl.ConditionEnglish;
import com.sentinelvoice.policy.dsl.FactCatalogue;
import com.sentinelvoice.policy.sets.PolicySetRepository;
import com.sentinelvoice.security.TenantContext;
import com.sentinelvoice.tenant.TenantSettingsEntity;
import com.sentinelvoice.tenant.TenantSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@EnableConfigurationProperties(PolicyCompileProperties.class)
public class PolicyCompileService {

    private static final Logger log = LoggerFactory.getLogger(PolicyCompileService.class);

    private final PolicyCompileRepository compileRepository;
    private final PolicySetRepository setRepository;
    private final PolicyDocumentRepository documentRepository;
    private final PolicyDocumentChunkRepository chunkRepository;
    private final LlmGatewayClient llmGatewayClient;
    private final TenantSettingsRepository settingsRepository;
    private final AuditLedgerService auditLedgerService;
    private final AsyncTaskExecutor policyExtractionExecutor;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final PolicyCompileProperties compileProperties;
    private final ObjectProvider<RuleConflictService> conflictServiceProvider;
    private final ConcurrentHashMap<UUID, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();

    public PolicyCompileService(
            PolicyCompileRepository compileRepository,
            PolicySetRepository setRepository,
            PolicyDocumentRepository documentRepository,
            PolicyDocumentChunkRepository chunkRepository,
            LlmGatewayClient llmGatewayClient,
            TenantSettingsRepository settingsRepository,
            AuditLedgerService auditLedgerService,
            @Qualifier("policyExtractionExecutor") AsyncTaskExecutor policyExtractionExecutor,
            TransactionTemplate transactionTemplate,
            ObjectMapper objectMapper,
            PolicyCompileProperties compileProperties,
            ObjectProvider<RuleConflictService> conflictServiceProvider
    ) {
        this.compileRepository = compileRepository;
        this.setRepository = setRepository;
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.llmGatewayClient = llmGatewayClient;
        this.settingsRepository = settingsRepository;
        this.auditLedgerService = auditLedgerService;
        this.policyExtractionExecutor = policyExtractionExecutor;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.compileProperties = compileProperties;
        this.conflictServiceProvider = conflictServiceProvider;
    }

    public int maxDocumentsPerCompile() {
        return compileProperties.maxDocumentsPerCompile();
    }

    public Map<String, Object> start(UUID tenantId, UUID userId, List<UUID> documentIds, String mode) {
        if (documentIds == null || documentIds.isEmpty()) {
            throw new PolicyCompileException("NO_DOCUMENTS", "Select at least one document");
        }
        int max = compileProperties.maxDocumentsPerCompile();
        if (documentIds.size() > max) {
            throw new PolicyCompileException(
                    "TOO_MANY_DOCUMENTS",
                    "Select at most " + max + " documents per compile (got " + documentIds.size() + ")"
            );
        }
        String m = mode == null ? "FULL" : mode.trim().toUpperCase(Locale.ROOT);
        if (!"FULL".equals(m) && !"INCREMENTAL".equals(m)) {
            throw new PolicyCompileException("BAD_MODE", "mode must be FULL or INCREMENTAL");
        }
        for (UUID docId : documentIds) {
            PolicyDocumentEntity doc = documentRepository.findByTenantIdAndId(tenantId, docId)
                    .orElseThrow(() -> new PolicyCompileException("NOT_FOUND", "Document not found: " + docId));
            if (!"EXTRACTED".equals(doc.getStatus())) {
                throw new PolicyCompileException("NOT_READY", "Document must be EXTRACTED: " + doc.getTitle());
            }
        }

        UUID compilationId = compileRepository.insertCompilation(tenantId, m, userId, documentIds);
        int version = setRepository.nextVersion(tenantId);
        UUID setId = setRepository.createDraft(tenantId, userId, "Compiled v" + version, version);
        // Append semantics: seed the draft with current ACTIVE live rules, then compile
        // adds newly extracted rules on top. Operator resolves clashes in Conflicts.
        int seeded = 0;
        Optional<Map<String, Object>> active = setRepository.findActiveSet(tenantId);
        if (active.isPresent()) {
            UUID activeId = UUID.fromString(String.valueOf(active.get().get("id")));
            seeded = setRepository.appendMissingRuntimeRules(tenantId, activeId, setId);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("appendedFromActiveId", activeId.toString());
            meta.put("appendedFromActiveVersion", active.get().get("version"));
            meta.put("appendedRuleCount", seeded);
            meta.put("compileMode", m);
            setRepository.updateSetMeta(tenantId, setId, meta);
            if (seeded > 0) {
                setRepository.renameSet(tenantId, setId,
                        "Compiled v" + version + " (keeps ACTIVE v" + active.get().get("version") + ")");
            }
        }
        compileRepository.bindPolicySet(compilationId, setId);
        String contentSha = setRepository.computeContentSha(tenantId, setId);

        int candidates = 0;
        int skipped = 0;
        for (UUID docId : documentIds) {
            List<PolicyDocumentChunkEntity> chunks =
                    chunkRepository.findByTenantIdAndDocumentIdOrderByOrdinalAsc(tenantId, docId);
            for (PolicyDocumentChunkEntity chunk : chunks) {
                boolean ok = ChunkPrefilter.isCandidate(chunk.getText());
                if (ok) {
                    compileRepository.insertChunkJob(tenantId, compilationId, docId, chunk.getId(), "PENDING", null);
                    candidates++;
                } else {
                    compileRepository.insertChunkJob(
                            tenantId, compilationId, docId, chunk.getId(), "SKIPPED", "no_obligation_keywords"
                    );
                    compileRepository.upsertChunkResult(
                            tenantId, compilationId, docId, chunk.getId(),
                            "SKIPPED_PREFILTER", "no_obligation_keywords", 0, 0
                    );
                    skipped++;
                }
            }
        }
        Map<String, Object> progress = baseProgress(candidates + skipped, candidates, skipped);
        compileRepository.updateProgress(compilationId, progress);

        auditLedgerService.append(
                tenantId, null, AuditEventType.POLICY_COMPILE_STARTED, "USER", userId.toString(),
                Map.of(
                        "compilationId", compilationId.toString(),
                        "policySetId", setId.toString(),
                        "mode", m,
                        "appendedFromActive", seeded,
                        "contentSha256", contentSha == null ? "" : contentSha
                )
        );

        cancelFlags.put(compilationId, new AtomicBoolean(false));
        policyExtractionExecutor.execute(() -> runCompilation(tenantId, compilationId));

        return enrichCompilationView(compileRepository.findCompilation(tenantId, compilationId).orElseThrow());
    }

    public Map<String, Object> get(UUID tenantId, UUID id) {
        Map<String, Object> c = compileRepository.findCompilation(tenantId, id)
                .orElseThrow(() -> new PolicyCompileException("NOT_FOUND", "Compilation not found"));
        return enrichCompilationView(c);
    }

    public List<Map<String, Object>> list(UUID tenantId) {
        return compileRepository.listCompilations(tenantId, 50).stream()
                .map(this::enrichCompilationView)
                .toList();
    }

    /**
     * Resume a stopped/failed compilation: re-queue every FAILED job, then continue.
     */
    public Map<String, Object> resume(UUID tenantId, UUID userId, UUID id) {
        Map<String, Object> c = compileRepository.findCompilation(tenantId, id)
                .orElseThrow(() -> new PolicyCompileException("NOT_FOUND", "Compilation not found"));
        String status = String.valueOf(c.get("status"));
        if ("RUNNING".equals(status)) {
            throw new PolicyCompileException("BUSY", "Compilation already running");
        }
        compileRepository.resetAllFailedJobs(id);
        cancelFlags.put(id, new AtomicBoolean(false));
        compileRepository.updateStatus(id, "QUEUED", null);
        policyExtractionExecutor.execute(() -> runCompilation(tenantId, id));
        auditLedgerService.append(
                tenantId, null, AuditEventType.POLICY_COMPILE_RESUMED, "USER", userId.toString(),
                Map.of("compilationId", id.toString())
        );
        return get(tenantId, id);
    }

    /**
     * Re-process only chunks that timed out, failed schema checks, returned empty,
     * or were rejected by validation. Keeps OK / valid-empty / prefilter-skipped chunks
     * and existing rules.
     */
    public Map<String, Object> cancel(UUID tenantId, UUID userId, UUID id) {
        Map<String, Object> c = compileRepository.findCompilation(tenantId, id)
                .orElseThrow(() -> new PolicyCompileException("NOT_FOUND", "Compilation not found"));
        String status = String.valueOf(c.get("status"));
        if (!"QUEUED".equals(status) && !"RUNNING".equals(status)) {
            throw new PolicyCompileException("BAD_STATE", "Only QUEUED or RUNNING compilations can be cancelled");
        }
        cancelFlags.computeIfAbsent(id, k -> new AtomicBoolean(false)).set(true);
        compileRepository.updateStatus(id, "CANCELLED", "Cancelled by user");
        auditLedgerService.append(
                tenantId, null, AuditEventType.POLICY_COMPILE_CANCELLED, "USER", userId.toString(),
                Map.of("compilationId", id.toString())
        );
        return get(tenantId, id);
    }

    public Map<String, Object> rerunFailed(UUID tenantId, UUID userId, UUID id) {
        Map<String, Object> c = compileRepository.findCompilation(tenantId, id)
                .orElseThrow(() -> new PolicyCompileException("NOT_FOUND", "Compilation not found"));
        if ("RUNNING".equals(String.valueOf(c.get("status")))) {
            throw new PolicyCompileException("BUSY", "Compilation already running");
        }
        int reset = compileRepository.resetFailedChunksForRerun(id);
        if (reset == 0) {
            throw new PolicyCompileException(
                    "NO_FAILED_CHUNKS",
                    "No chunks to re-run (timeout / schema / empty / validation only)"
            );
        }
        cancelFlags.put(id, new AtomicBoolean(false));
        compileRepository.updateStatus(id, "QUEUED", null);
        policyExtractionExecutor.execute(() -> runCompilation(tenantId, id));
        auditLedgerService.append(
                tenantId, null, AuditEventType.POLICY_COMPILE_RESUMED, "USER", userId.toString(),
                Map.of("compilationId", id.toString(), "rerunFailed", true, "chunksReset", reset)
        );
        return get(tenantId, id);
    }

    public Map<String, Object> diagnosticsForPolicySet(UUID tenantId, UUID policySetId) {
        return compileRepository.findCompilationIdByPolicySet(tenantId, policySetId)
                .map(id -> {
                    Map<String, Object> c = compileRepository.findCompilation(tenantId, id).orElse(Map.of());
                    return buildDiagnostics(id, c);
                })
                .orElse(Map.of("summary", "No compilation linked to this policy set"));
    }

    void runCompilation(UUID tenantId, UUID compilationId) {
        TenantContext.runAs(tenantId, () -> {
            try {
                compileRepository.updateStatus(compilationId, "RUNNING", null);
                Map<String, Object> compilation = compileRepository.findCompilation(tenantId, compilationId).orElseThrow();
                UUID setId = UUID.fromString(String.valueOf(compilation.get("policySetId")));
                boolean allowExternal = settingsRepository.findById(tenantId)
                        .map(TenantSettingsEntity::isAllowExternalLlm)
                        .orElse(false);

                @SuppressWarnings("unchecked")
                Object progressRaw = compilation.get("progress");
                Map<String, Object> progress = new LinkedHashMap<>(
                        progressRaw instanceof Map<?, ?> pm
                                ? (Map<String, Object>) pm
                                : Map.of()
                );
                int failed = ((Number) progress.getOrDefault("chunksFailed", 0)).intValue();
                int proposed = ((Number) progress.getOrDefault("rulesProposed", 0)).intValue();
                int hallucinated = ((Number) progress.getOrDefault("rulesRejectedHallucinated", 0)).intValue();
                int totalJobs = Math.max(
                        compileRepository.countChunkJobs(compilationId),
                        ((Number) progress.getOrDefault("chunksTotal", 0)).intValue()
                );
                progress.put("chunksTotal", totalJobs);

                AtomicBoolean cancelled = cancelFlags.computeIfAbsent(compilationId, k -> new AtomicBoolean(false));
                List<Map<String, Object>> pending = compileRepository.listPendingChunks(compilationId);
                for (Map<String, Object> job : pending) {
                    if (cancelled.get()) {
                        compileRepository.updateStatus(compilationId, "CANCELLED", "Cancelled by user");
                        return null;
                    }
                    UUID chunkId = UUID.fromString(String.valueOf(job.get("chunkId")));
                    UUID documentId = UUID.fromString(String.valueOf(job.get("documentId")));
                    compileRepository.updateChunk(compilationId, chunkId, "RUNNING", 0, 0, null);
                    long t0 = System.nanoTime();
                    try {
                        PolicyDocumentChunkEntity chunk = chunkRepository
                                .findByTenantIdAndDocumentIdOrderByOrdinalAsc(tenantId, documentId)
                                .stream()
                                .filter(c -> c.getId().equals(chunkId))
                                .findFirst()
                                .orElse(null);
                        if (chunk == null) {
                            int ms = (int) ((System.nanoTime() - t0) / 1_000_000L);
                            compileRepository.updateChunk(compilationId, chunkId, "FAILED", 0, 0, "chunk_missing");
                            compileRepository.upsertChunkResult(
                                    tenantId, compilationId, documentId, chunkId,
                                    "LLM_UNAVAILABLE", "chunk_missing", ms, 0
                            );
                            failed++;
                            syncProgress(progress, compilationId, failed, proposed, hallucinated);
                            compileRepository.updateProgress(compilationId, progress);
                            continue;
                        }
                        boolean injection = chunk.getInjectionFlags() != null && !chunk.getInjectionFlags().isEmpty();
                        LlmChunkOutcome llm = callLlmWithRetry(tenantId, chunk, allowExternal);
                        int ms = (int) ((System.nanoTime() - t0) / 1_000_000L);
                        Map<String, Object> meta = llm.meta() == null ? Map.of() : llm.meta();

                        if (!isSuccessWithRules(llm.status()) && !isValidEmpty(llm.status())) {
                            // LLM_EMPTY (incl. placeholder drops) and EMPTY_SUSPECT are finished, not job failures
                            boolean softEmpty = "LLM_EMPTY".equals(llm.status())
                                    || "LLM_EMPTY_SUSPECT".equals(llm.status());
                            boolean jobFailed = !softEmpty;
                            String jobStatus = softEmpty ? "DONE" : "FAILED";
                            compileRepository.updateChunk(
                                    compilationId, chunkId, jobStatus, 0, 0, llm.reason()
                            );
                            compileRepository.upsertChunkResult(
                                    tenantId, compilationId, documentId, chunkId,
                                    llm.status(), llm.reason(), ms, 0, meta
                            );
                            if (jobFailed) {
                                failed++;
                            }
                            syncProgress(progress, compilationId, failed, proposed, hallucinated);
                            compileRepository.updateProgress(compilationId, progress);
                            continue;
                        }

                        if (isValidEmpty(llm.status())) {
                            compileRepository.updateChunk(compilationId, chunkId, "DONE", 0, 0, "empty");
                            compileRepository.upsertChunkResult(
                                    tenantId, compilationId, documentId, chunkId,
                                    "LLM_OK_EMPTY", llm.reason() == null ? "valid empty rules list" : llm.reason(),
                                    ms, 0, meta
                            );
                            syncProgress(progress, compilationId, failed, proposed, hallucinated);
                            compileRepository.updateProgress(compilationId, progress);
                            continue;
                        }

                        List<Map<String, Object>> rules = llm.rules();
                        int localProposed = 0;
                        int localRejected = 0;
                        int localPlaceholders = 0;
                        for (Map<String, Object> raw : rules) {
                            if (isPlaceholderRule(raw)) {
                                localPlaceholders++;
                                continue;
                            }
                            Map<String, Object> enriched = enrich(raw, documentId, chunk);
                            if (isPlaceholderRule(enriched)) {
                                localPlaceholders++;
                                continue;
                            }
                            RuleValidator.Result validated =
                                    RuleValidator.validate(enriched, chunk.getText(), injection);
                            if (validated.autoReject()) {
                                // Only persist meaningful rejections (have clauseRef + a condition)
                                if (!isMeaningfulRejectionCandidate(validated.rule())) {
                                    localPlaceholders++;
                                    continue;
                                }
                                localRejected++;
                                if (isHallucinationReject(validated.warnings())) {
                                    hallucinated++;
                                }
                                compileRepository.insertRule(tenantId, setId, validated.rule());
                                continue;
                            }
                            compileRepository.insertRule(tenantId, setId, validated.rule());
                            localProposed++;
                            proposed++;
                            persistKeywordsAndFacts(tenantId, setId, validated.rule(), raw, chunk.getText());
                        }
                        if (localProposed == 0 && localRejected == 0 && localPlaceholders > 0) {
                            Map<String, Object> emptyMeta = new LinkedHashMap<>(meta);
                            emptyMeta.put("placeholderDropped", localPlaceholders);
                            String emptyStatus = hasStrongObligationCues(chunk.getText())
                                    ? "LLM_EMPTY_SUSPECT"
                                    : "LLM_EMPTY";
                            String emptyReason = hasStrongObligationCues(chunk.getText())
                                    ? "obligation cues present but only placeholder rules"
                                    : "placeholder_rules_dropped:" + localPlaceholders;
                            compileRepository.updateChunk(compilationId, chunkId, "DONE", 0, 0, emptyReason);
                            compileRepository.upsertChunkResult(
                                    tenantId, compilationId, documentId, chunkId,
                                    emptyStatus, emptyReason, ms, 0, emptyMeta
                            );
                            syncProgress(progress, compilationId, failed, proposed, hallucinated);
                            compileRepository.updateProgress(compilationId, progress);
                            continue;
                        }
                        String resultStatus = localProposed > 0
                                ? "LLM_OK_WITH_RULES"
                                : (localRejected > 0 ? "REJECTED_VALIDATION" : "LLM_OK_EMPTY");
                        String reason = localProposed > 0
                                ? null
                                : (localRejected > 0
                                        ? "all proposed rules failed quote/source validation"
                                        : "no rules retained");
                        compileRepository.updateChunk(
                                compilationId, chunkId, "DONE", localProposed, localRejected, reason
                        );
                        compileRepository.upsertChunkResult(
                                tenantId, compilationId, documentId, chunkId,
                                resultStatus, reason, ms, localProposed, meta
                        );
                    } catch (Exception ex) {
                        int ms = (int) ((System.nanoTime() - t0) / 1_000_000L);
                        log.warn("compile_chunk_failed compilationId={} chunkId={} cause={}",
                                compilationId, chunkId, ex.toString());
                        String status = classifyException(ex);
                        compileRepository.updateChunk(
                                compilationId, chunkId, "FAILED", 0, 0, ex.getMessage()
                        );
                        compileRepository.upsertChunkResult(
                                tenantId, compilationId, documentId, chunkId,
                                status, ex.getMessage(), ms, 0
                        );
                        failed++;
                    }
                    syncProgress(progress, compilationId, failed, proposed, hallucinated);
                    compileRepository.updateProgress(compilationId, progress);
                }

                int ruleCount = compileRepository.countRulesInSet(tenantId, setId);
                flagPossibleDuplicates(tenantId, setId);
                List<Map<String, Object>> results = compileRepository.listChunkResults(compilationId);
                mergeDiagnosticCounts(progress, results);
                String summary = formatSummary(progress);
                progress.put("summary", summary);

                if (ruleCount == 0) {
                    String reason = summary + ". No rules were stored — draft left empty for review / re-run.";
                    progress.put("noRulesReason", reason);
                    compileRepository.updateProgress(compilationId, progress);
                    compileRepository.updateStatus(compilationId, "COMPLETED_NO_RULES", reason);
                    auditLedgerService.append(
                            tenantId, null, AuditEventType.POLICY_COMPILE_COMPLETED, "SYSTEM", "compiler",
                            Map.of(
                                    "compilationId", compilationId.toString(),
                                    "rulesProposed", 0,
                                    "status", "COMPLETED_NO_RULES",
                                    "summary", summary
                            )
                    );
                } else {
                    compileRepository.updateProgress(compilationId, progress);
                    compileRepository.updateStatus(compilationId, "COMPLETED", null);
                    int conflictCount = 0;
                    try {
                        RuleConflictService conflicts = conflictServiceProvider.getIfAvailable();
                        if (conflicts != null) {
                            Map<String, Object> check = conflicts.checkSet(tenantId, null, setId, false);
                            conflictCount = ((Number) check.getOrDefault("openBlocking", 0)).intValue();
                            progress.put("conflictCount", conflictCount);
                            progress.put(
                                    "conflictSummary",
                                    "Compile finished: " + ruleCount + " rules, "
                                            + conflictCount + " conflict"
                                            + (conflictCount == 1 ? "" : "s")
                                            + " with the live policy"
                            );
                            compileRepository.updateProgress(compilationId, progress);
                        }
                    } catch (Exception cex) {
                        log.warn("compile_conflict_check_failed compilationId={} cause={}",
                                compilationId, cex.toString());
                    }
                    auditLedgerService.append(
                            tenantId, null, AuditEventType.POLICY_COMPILE_COMPLETED, "SYSTEM", "compiler",
                            Map.of(
                                    "compilationId", compilationId.toString(),
                                    "rulesProposed", proposed,
                                    "hallucinated", hallucinated,
                                    "conflicts", conflictCount,
                                    "summary", summary
                            )
                    );
                }
            } catch (Exception ex) {
                log.error("compile_failed compilationId={} cause={}", compilationId, ex.toString(), ex);
                compileRepository.updateStatus(compilationId, "FAILED", ex.getMessage());
                auditLedgerService.append(
                        tenantId, null, AuditEventType.POLICY_COMPILE_FAILED, "SYSTEM", "compiler",
                        Map.of("compilationId", compilationId.toString(), "error", String.valueOf(ex.getMessage()))
                );
            } finally {
                cancelFlags.remove(compilationId);
            }
            return null;
        });
    }

    private Map<String, Object> enrichCompilationView(Map<String, Object> c) {
        Map<String, Object> out = new LinkedHashMap<>(c);
        UUID id = UUID.fromString(String.valueOf(c.get("id")));
        out.put("chunks", compileRepository.listChunkJobs(id));
        out.put("chunkResults", compileRepository.listChunkResults(id));
        out.put("diagnostics", buildDiagnostics(id, c));
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildDiagnostics(UUID compilationId, Map<String, Object> compilation) {
        List<Map<String, Object>> results = compileRepository.listChunkResults(compilationId);
        Map<String, Object> counts = countByStatus(results);
        Map<String, Object> progress = compilation.get("progress") instanceof Map<?, ?> p
                ? new LinkedHashMap<>((Map<String, Object>) p)
                : new LinkedHashMap<>();
        mergeDiagnosticCounts(progress, results);
        String summary = formatSummary(progress);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("compilationId", compilationId.toString());
        out.put("compilationStatus", compilation.get("status"));
        out.put("error", compilation.get("error"));
        out.put("summary", summary);
        out.put("counts", counts);
        out.put("chunkResults", results);
        out.put("progress", progress);
        out.put("hasFailedChunks", results.stream().anyMatch(r -> {
            String st = String.valueOf(r.get("status"));
            return "LLM_TIMEOUT".equals(st)
                    || "LLM_TRUNCATED".equals(st)
                    || "LLM_SCHEMA_ERROR".equals(st)
                    || "LLM_EMPTY".equals(st)
                    || "LLM_EMPTY_SUSPECT".equals(st)
                    || "LLM_UNAVAILABLE".equals(st)
                    || "REJECTED_VALIDATION".equals(st);
        }));
        out.put("hasSuspectEmpty", results.stream()
                .anyMatch(r -> "LLM_EMPTY_SUSPECT".equals(String.valueOf(r.get("status")))));
        UUID tenantId = null;
        Object rawTenant = compilation.get("tenantId");
        if (rawTenant != null) {
            try {
                tenantId = UUID.fromString(String.valueOf(rawTenant));
            } catch (Exception ignored) {
                // leave null
            }
        }
        out.put("couldNotRule", buildCouldNotRuleClauses(tenantId, results));
        return out;
    }

    private List<Map<String, Object>> buildCouldNotRuleClauses(
            UUID tenantId, List<Map<String, Object>> results
    ) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : results) {
            if (!"LLM_EMPTY_SUSPECT".equals(String.valueOf(r.get("status")))) {
                continue;
            }
            UUID documentId;
            UUID chunkId;
            try {
                documentId = UUID.fromString(String.valueOf(r.get("documentId")));
                chunkId = UUID.fromString(String.valueOf(r.get("chunkId")));
            } catch (Exception e) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("documentId", documentId.toString());
            row.put("chunkId", chunkId.toString());
            row.put("reason", r.get("reason"));
            row.put("status", r.get("status"));
            if (tenantId != null) {
                chunkRepository.findByTenantIdAndDocumentIdOrderByOrdinalAsc(tenantId, documentId)
                        .stream()
                        .filter(c -> c.getId().equals(chunkId))
                        .findFirst()
                        .ifPresent(c -> {
                            String text = c.getText() == null ? "" : c.getText().strip();
                            String quote = text.replaceAll("\\s+", " ");
                            if (quote.length() > 200) {
                                quote = quote.substring(0, 200);
                            }
                            row.put("headingPath", c.getHeadingPath());
                            row.put("pageNo", c.getPageNo());
                            row.put("quote", quote);
                            row.put("clauseRef", guessClauseRef(c.getHeadingPath(), text));
                            row.put("textPreview", quote);
                        });
            }
            out.add(row);
        }
        return out;
    }

    private static String guessClauseRef(String headingPath, String text) {
        if (headingPath != null) {
            java.util.regex.Matcher m = CLAUSE_NUM.matcher(headingPath);
            if (m.find()) {
                return m.group(1) + "." + m.group(2);
            }
            String h = headingPath.strip();
            if (!h.isBlank() && h.length() <= 32) {
                return h;
            }
        }
        if (text != null) {
            java.util.regex.Matcher m = CLAUSE_NUM.matcher(text);
            if (m.find()) {
                return m.group(1) + "." + m.group(2);
            }
        }
        return "";
    }

    private static Map<String, Object> countByStatus(List<Map<String, Object>> results) {
        Map<String, Object> counts = new LinkedHashMap<>();
        for (String s : List.of(
                "SKIPPED_PREFILTER",
                "LLM_OK", "LLM_OK_WITH_RULES", "LLM_OK_EMPTY",
                "LLM_TIMEOUT", "LLM_TRUNCATED", "LLM_SCHEMA_ERROR",
                "LLM_UNAVAILABLE", "LLM_EMPTY", "LLM_EMPTY_SUSPECT",
                "REJECTED_VALIDATION"
        )) {
            counts.put(s, 0);
        }
        for (Map<String, Object> r : results) {
            String st = String.valueOf(r.get("status"));
            counts.put(st, ((Number) counts.getOrDefault(st, 0)).intValue() + 1);
        }
        return counts;
    }

    private void syncProgress(
            Map<String, Object> progress,
            UUID compilationId,
            int failed,
            int proposed,
            int hallucinated
    ) {
        int total = Math.max(
                num(progress, "chunksTotal"),
                compileRepository.countChunkJobs(compilationId)
        );
        int finished = compileRepository.countFinishedChunkJobs(compilationId);
        List<Map<String, Object>> results = compileRepository.listChunkResults(compilationId);
        int failedFromDb = 0;
        for (Map<String, Object> r : results) {
            String st = String.valueOf(r.get("status"));
            if ("LLM_TIMEOUT".equals(st) || "LLM_TRUNCATED".equals(st) || "LLM_SCHEMA_ERROR".equals(st)
                    || "LLM_UNAVAILABLE".equals(st)
                    || "REJECTED_VALIDATION".equals(st)) {
                failedFromDb++;
            }
        }
        progress.put("chunksTotal", total);
        progress.put("chunksDone", Math.min(finished, total));
        progress.put("chunksFailed", failedFromDb);
        progress.put("rulesProposed", proposed);
        progress.put("rulesRejectedHallucinated", hallucinated);
        mergeDiagnosticCounts(progress, results);
    }

    private static void mergeDiagnosticCounts(Map<String, Object> progress, List<Map<String, Object>> results) {
        Map<String, Object> counts = countByStatus(results);
        progress.put("skippedPrefilter", counts.get("SKIPPED_PREFILTER"));
        int okWith = num(counts, "LLM_OK_WITH_RULES") + num(counts, "LLM_OK");
        progress.put("llmOk", okWith);
        progress.put("llmOkEmpty", counts.get("LLM_OK_EMPTY"));
        progress.put("timedOut", counts.get("LLM_TIMEOUT"));
        progress.put("truncated", counts.get("LLM_TRUNCATED"));
        progress.put("schemaErrors", counts.get("LLM_SCHEMA_ERROR"));
        progress.put("llmUnavailable", counts.get("LLM_UNAVAILABLE"));
        progress.put("llmEmpty", counts.get("LLM_EMPTY"));
        progress.put("llmEmptySuspect", counts.get("LLM_EMPTY_SUSPECT"));
        progress.put("rejectedValidation", counts.get("REJECTED_VALIDATION"));
        progress.put("summary", formatSummary(progress));
    }

    private static String formatSummary(Map<String, Object> progress) {
        int total = num(progress, "chunksTotal");
        int skipped = num(progress, "skippedPrefilter");
        int timedOut = num(progress, "timedOut");
        int truncated = num(progress, "truncated");
        int schema = num(progress, "schemaErrors");
        int empty = num(progress, "llmEmpty");
        int emptySuspect = num(progress, "llmEmptySuspect");
        int okEmpty = num(progress, "llmOkEmpty");
        int unavailable = num(progress, "llmUnavailable");
        int rejected = num(progress, "rejectedValidation");
        int ok = num(progress, "llmOk");
        List<String> parts = new ArrayList<>();
        if (skipped > 0) {
            parts.add(skipped + " skipped by pre-filter");
        }
        if (timedOut > 0) {
            parts.add(timedOut + " timed out");
        }
        if (truncated > 0) {
            parts.add(truncated + " truncated");
        }
        if (schema > 0) {
            parts.add(schema + " schema errors");
        }
        if (unavailable > 0) {
            parts.add(unavailable + " LLM unavailable");
        }
        if (empty > 0 || okEmpty > 0) {
            int noRule = empty + okEmpty;
            parts.add(noRule + " clauses returned no rule");
        }
        if (emptySuspect > 0) {
            parts.add(emptySuspect + " could not be turned into a rule");
        }
        if (rejected > 0) {
            parts.add(rejected + " rejected by validation");
        }
        if (ok > 0) {
            parts.add(ok + " produced rules");
        }
        if (parts.isEmpty()) {
            return total + " chunks: no diagnostics yet";
        }
        return total + " chunks: " + String.join(", ", parts);
    }

    /**
     * Model non-answers: empty/missing when only. Title/source are attached in code.
     */
    @SuppressWarnings("unchecked")
    static boolean isPlaceholderRule(Map<String, Object> rule) {
        if (rule == null || rule.isEmpty()) {
            return true;
        }
        Map<String, Object> when = rule.get("when") instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : Map.of();
        return when.isEmpty() || ConditionEnglish.collectLeaves(when).isEmpty();
    }

    /** Rejected rules worth showing: have a clause ref and a non-empty condition. */
    @SuppressWarnings("unchecked")
    static boolean isMeaningfulRejectionCandidate(Map<String, Object> rule) {
        if (rule == null) {
            return false;
        }
        Map<String, Object> source = rule.get("source") instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : Map.of();
        Object clauseRef = source.get("clauseRef");
        if (clauseRef == null || String.valueOf(clauseRef).isBlank()
                || "null".equalsIgnoreCase(String.valueOf(clauseRef))) {
            return false;
        }
        Map<String, Object> when = rule.get("when") instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : Map.of();
        return !when.isEmpty() && !ConditionEnglish.collectLeaves(when).isEmpty();
    }

    private static int num(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Number n ? n.intValue() : 0;
    }

    private static Map<String, Object> baseProgress(int total, int candidates, int skipped) {
        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("chunksTotal", total);
        progress.put("chunksCandidate", candidates);
        progress.put("chunksSkipped", skipped);
        progress.put("chunksDone", skipped);
        progress.put("chunksFailed", 0);
        progress.put("rulesProposed", 0);
        progress.put("rulesRejectedHallucinated", 0);
        progress.put("skippedPrefilter", skipped);
        progress.put("llmOk", 0);
        progress.put("llmOkEmpty", 0);
        progress.put("timedOut", 0);
        progress.put("truncated", 0);
        progress.put("schemaErrors", 0);
        progress.put("llmUnavailable", 0);
        progress.put("llmEmpty", 0);
        progress.put("llmEmptySuspect", 0);
        progress.put("rejectedValidation", 0);
        progress.put("summary", total + " chunks queued (" + skipped + " pre-filter skips)");
        return progress;
    }

    private record LlmChunkOutcome(
            String status,
            String reason,
            List<Map<String, Object>> rules,
            Map<String, Object> meta
    ) {
        LlmChunkOutcome(String status, String reason, List<Map<String, Object>> rules) {
            this(status, reason, rules, Map.of());
        }
    }

    private static boolean isSuccessWithRules(String status) {
        return "LLM_OK".equals(status) || "LLM_OK_WITH_RULES".equals(status);
    }

    private static boolean isValidEmpty(String status) {
        return "LLM_OK_EMPTY".equals(status);
    }

    private static final java.util.regex.Pattern OBLIGATION_CUES = java.util.regex.Pattern.compile(
            "(?i)\\b(must(?:\\s+not)?|never|shall|requires?|prohibited|above\\s+inr|not\\s+permitted)\\b"
    );

    private static boolean hasStrongObligationCues(String text) {
        return text != null && OBLIGATION_CUES.matcher(text).find();
    }

    private LlmChunkOutcome callLlmWithRetry(
            UUID tenantId, PolicyDocumentChunkEntity chunk, boolean allowExternal
    ) {
        LlmChunkOutcome first = callLlmOnce(tenantId, chunk, allowExternal, false);
        // Valid empty list OR placeholder-only (LLM_EMPTY with placeholderDropped) + obligation cues → one short retry
        boolean emptyish = isValidEmpty(first.status())
                || ("LLM_EMPTY".equals(first.status())
                && first.meta() != null
                && first.meta().get("placeholderDropped") != null);
        if (isSuccessWithRules(first.status()) || isValidEmpty(first.status()) || emptyish) {
            if ((isValidEmpty(first.status()) || emptyish) && hasStrongObligationCues(chunk.getText())) {
                log.info("policy_compile_suspect_empty_retry chunkId={} priorStatus={}",
                        chunk.getId(), first.status());
                LlmChunkOutcome retry = callLlmOnce(tenantId, chunk, allowExternal, true);
                if (isSuccessWithRules(retry.status())) {
                    return retry;
                }
                if (isValidEmpty(retry.status())
                        || "LLM_EMPTY".equals(retry.status())
                        || (retry.rules() != null && retry.rules().isEmpty())) {
                    Map<String, Object> meta = new LinkedHashMap<>(
                            retry.meta() == null ? Map.of() : retry.meta()
                    );
                    meta.put("suspectRetry", true);
                    if (first.meta() != null && first.meta().get("placeholderDropped") != null) {
                        meta.putIfAbsent("placeholderDropped", first.meta().get("placeholderDropped"));
                    }
                    return new LlmChunkOutcome(
                            "LLM_EMPTY_SUSPECT",
                            "obligation cues present but model returned empty/placeholder rules twice",
                            List.of(),
                            meta
                    );
                }
                return retry;
            }
            if (isSuccessWithRules(first.status()) || isValidEmpty(first.status())) {
                return first;
            }
            // placeholder-only without obligation cues: treat as valid empty (LLM_EMPTY)
            return first;
        }
        if ("LLM_TIMEOUT".equals(first.status())
                || "LLM_SCHEMA_ERROR".equals(first.status())
                || "LLM_TRUNCATED".equals(first.status())) {
            log.info("policy_compile_retry chunkId={} priorStatus={}", chunk.getId(), first.status());
            return callLlmOnce(tenantId, chunk, allowExternal, false);
        }
        return first;
    }

    @SuppressWarnings("unchecked")
    private LlmChunkOutcome callLlmOnce(
            UUID tenantId,
            PolicyDocumentChunkEntity chunk,
            boolean allowExternal,
            boolean shortPrompt
    ) {
        var subset = FactCatalogue.subsetForClause(chunk.getText());
        Map<String, Object> schema = FactCatalogue.buildCompileResultSchema(subset);
        String userPrompt = shortPrompt ? buildShortUserPrompt(chunk, subset) : buildUserPrompt(chunk, subset);
        String systemPrompt = shortPrompt ? buildShortSystemPrompt() : buildSystemPrompt();

        int estimatedTokens = estimateTokens(systemPrompt + "\n" + userPrompt + "\n" + chunk.getText());
        int numCtxBudget = 8192;
        if (estimatedTokens > (int) (0.4 * numCtxBudget)) {
            log.warn(
                    "policy_compile_prompt_over_budget chunkId={} estimatedTokens={} numCtx={} budgetPct=40",
                    chunk.getId(), estimatedTokens, numCtxBudget
            );
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("system", systemPrompt);
        payload.put("user", userPrompt);
        payload.put("jsonSchema", schema);
        payload.put("untrustedData", chunk.getText());
        payload.put("allowExternalLlm", allowExternal);

        try {
            Map<String, Object> out = llmGatewayClient.run("policy_compile", tenantId, payload);
            Map<String, Object> meta = extractLlmMeta(out, estimatedTokens);
            log.info(
                    "policy_compile_chunk chunkId={} ok={} error={} promptTokens={} evalCount={} "
                            + "numCtx={} doneReason={} latencyMs={}",
                    chunk.getId(),
                    out.get("ok"),
                    out.get("error"),
                    meta.get("promptTokens"),
                    meta.get("evalCount"),
                    meta.get("numCtx"),
                    meta.get("doneReason"),
                    meta.get("latencyMs")
            );

            String error = out.get("error") == null ? "" : String.valueOf(out.get("error"));
            String errorUp = error.toUpperCase(Locale.ROOT);
            if (!Boolean.TRUE.equals(out.get("ok")) && out.get("result") == null) {
                if (isTimeoutError(error) || errorUp.contains("TIMEOUT")) {
                    return new LlmChunkOutcome("LLM_TIMEOUT", error, List.of(), meta);
                }
                if (errorUp.contains("TRUNCATED")
                        || "length".equalsIgnoreCase(String.valueOf(meta.get("doneReason")))) {
                    return new LlmChunkOutcome(
                            "LLM_TRUNCATED", error.isBlank() ? "truncated" : error, List.of(), meta
                    );
                }
                if (errorUp.contains("SCHEMA")) {
                    return new LlmChunkOutcome("LLM_SCHEMA_ERROR", error, List.of(), meta);
                }
                if (errorUp.contains("NO_LLM") || errorUp.contains("CIRCUIT")
                        || errorUp.contains("UNAVAILABLE") || errorUp.contains("BACKPRESSURE")) {
                    return new LlmChunkOutcome(
                            "LLM_UNAVAILABLE",
                            error.isBlank() ? "llm_unavailable" : error,
                            List.of(),
                            meta
                    );
                }
                if (errorUp.contains("EMPTY_CONTENT")) {
                    return new LlmChunkOutcome("LLM_EMPTY", error, List.of(), meta);
                }
                return new LlmChunkOutcome(
                        "LLM_UNAVAILABLE",
                        error.isBlank() ? "llm_failed" : error,
                        List.of(),
                        meta
                );
            }
            Object result = out.get("result");
            if (!(result instanceof Map<?, ?> map)) {
                return new LlmChunkOutcome("LLM_SCHEMA_ERROR", "result_not_object", List.of(), meta);
            }
            Object rules = map.get("rules");
            if (!(rules instanceof List<?> list)) {
                return new LlmChunkOutcome("LLM_SCHEMA_ERROR", "rules_missing", List.of(), meta);
            }
            List<Map<String, Object>> parsed = new ArrayList<>();
            int placeholderDropped = 0;
            for (Object item : list) {
                if (item instanceof Map<?, ?> rm) {
                    Map<String, Object> rule = new LinkedHashMap<>((Map<String, Object>) rm);
                    if (isPlaceholderRule(rule)) {
                        placeholderDropped++;
                        continue;
                    }
                    normalizeRuleDefaults(rule);
                    // Re-check after defaults — never persist title "rule" + empty when
                    if (isPlaceholderRule(rule)) {
                        placeholderDropped++;
                        continue;
                    }
                    parsed.add(rule);
                }
            }
            String provider = String.valueOf(out.getOrDefault("provider", "LLM"));
            for (Map<String, Object> r : parsed) {
                if ("mock".equalsIgnoreCase(provider)) {
                    r.put("origin", "LLM_MOCK");
                } else {
                    r.putIfAbsent("origin", "LLM");
                }
            }
            if (parsed.isEmpty()) {
                Map<String, Object> emptyMeta = new LinkedHashMap<>(meta);
                if (placeholderDropped > 0) {
                    emptyMeta.put("placeholderDropped", placeholderDropped);
                    // Spec: placeholder non-answers → LLM_EMPTY (valid empty), not rejected rules
                    return new LlmChunkOutcome(
                            "LLM_EMPTY",
                            "placeholder_rules_dropped:" + placeholderDropped,
                            List.of(),
                            emptyMeta
                    );
                }
                return new LlmChunkOutcome("LLM_OK_EMPTY", "zero_rules", List.of(), meta);
            }
            return new LlmChunkOutcome("LLM_OK_WITH_RULES", null, parsed, meta);
        } catch (ResourceAccessException ex) {
            if (isTimeoutException(ex)) {
                return new LlmChunkOutcome("LLM_TIMEOUT", ex.getMessage(), List.of());
            }
            return new LlmChunkOutcome("LLM_UNAVAILABLE", ex.getMessage(), List.of());
        } catch (Exception ex) {
            if (isTimeoutException(ex)) {
                return new LlmChunkOutcome("LLM_TIMEOUT", ex.getMessage(), List.of());
            }
            String msg = ex.getMessage() == null ? ex.toString() : ex.getMessage();
            if (msg.toUpperCase(Locale.ROOT).contains("SCHEMA")) {
                return new LlmChunkOutcome("LLM_SCHEMA_ERROR", msg, List.of());
            }
            return new LlmChunkOutcome("LLM_UNAVAILABLE", msg, List.of());
        }
    }

    private static Map<String, Object> extractLlmMeta(Map<String, Object> out, int estimatedTokens) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("estimatedPromptTokens", estimatedTokens);
        meta.put("latencyMs", out.get("latencyMs"));
        meta.put("doneReason", out.get("doneReason"));
        meta.put("numCtx", out.get("numCtx"));
        meta.put("provider", out.get("provider"));
        meta.put("model", out.get("model"));
        Object usage = out.get("usage");
        if (usage instanceof Map<?, ?> u) {
            meta.put("promptTokens", u.get("prompt_tokens"));
            meta.put("evalCount", u.get("eval_count") != null ? u.get("eval_count") : u.get("completion_tokens"));
            if (meta.get("doneReason") == null) {
                meta.put("doneReason", u.get("done_reason"));
            }
            if (meta.get("numCtx") == null) {
                meta.put("numCtx", u.get("num_ctx"));
            }
        }
        return meta;
    }

    private static int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return Math.max(1, text.length() / 4);
    }

    @SuppressWarnings("unchecked")
    private static void normalizeRuleDefaults(Map<String, Object> rule) {
        if (!(rule.get("then") instanceof Map<?, ?>)) {
            Map<String, Object> then = new LinkedHashMap<>();
            then.put("minLevel", 2);
            then.put("scoreBoost", 0.2);
            then.put("reasonCode", "POLICY_GENERIC");
            rule.put("then", then);
        } else {
            Map<String, Object> then = new LinkedHashMap<>((Map<String, Object>) rule.get("then"));
            then.putIfAbsent("minLevel", 2);
            then.putIfAbsent("scoreBoost", 0.2);
            then.putIfAbsent("reasonCode", "POLICY_GENERIC");
            rule.put("then", then);
        }
        // Strip citation/identity fields — attached later from the chunk
        rule.remove("source");
        rule.remove("title");
        rule.remove("ruleId");
        rule.remove("documentId");
        rule.remove("chunkId");
        if (!(rule.get("appliesTo") instanceof Map<?, ?>)) {
            rule.put("appliesTo", Map.of("actionTypes", List.of("*")));
        }
        rule.putIfAbsent("severity", "MEDIUM");
    }

    private Map<String, Object> enrich(
            Map<String, Object> raw, UUID documentId, PolicyDocumentChunkEntity chunk
    ) {
        Map<String, Object> attributed = RuleSourceAttributor.attribute(raw, documentId, chunk);
        if ("LLM_MOCK".equals(String.valueOf(attributed.get("origin"))) && chunk.getText() != null) {
            groundMockAmounts(attributed, chunk.getText());
            // Re-pick quote after amount grounding so numbers align
            @SuppressWarnings("unchecked")
            Map<String, Object> when = attributed.get("when") instanceof Map<?, ?> m
                    ? (Map<String, Object>) m : Map.of();
            String quote = RuleSourceAttributor.selectQuote(
                    chunk.getText(), when, List.of(), attributed.get("modality")
            );
            @SuppressWarnings("unchecked")
            Map<String, Object> source = attributed.get("source") instanceof Map<?, ?> s
                    ? new LinkedHashMap<>((Map<String, Object>) s) : new LinkedHashMap<>();
            source.put("quote", quote);
            attributed.put("source", source);
            attributed.put("title", RuleSourceAttributor.generateTitle(
                    when, attributed.get("modality"), quote, asMap(attributed.get("then"))
            ));
        }
        attributed.putIfAbsent("status", "PROPOSED");
        attributed.putIfAbsent("origin", "LLM");
        return attributed;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static String classifyException(Exception ex) {
        if (isTimeoutException(ex)) {
            return "LLM_TIMEOUT";
        }
        String msg = ex.getMessage() == null ? "" : ex.getMessage().toUpperCase(Locale.ROOT);
        if (msg.contains("SCHEMA")) {
            return "LLM_SCHEMA_ERROR";
        }
        if (msg.contains("TRUNCATED")) {
            return "LLM_TRUNCATED";
        }
        return "LLM_UNAVAILABLE";
    }

    private static boolean isTimeoutException(Throwable ex) {
        Throwable t = ex;
        while (t != null) {
            if (t instanceof SocketTimeoutException) {
                return true;
            }
            String m = t.getMessage() == null ? "" : t.getMessage().toLowerCase(Locale.ROOT);
            if (m.contains("timed out") || m.contains("timeout") || m.contains("read timed out")) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    private static boolean isTimeoutError(String error) {
        if (error == null) {
            return false;
        }
        String e = error.toUpperCase(Locale.ROOT);
        return e.contains("TIMEOUT") || e.contains("TIMED_OUT") || e.equals("TIMEOUT");
    }

    private static boolean isTimeoutThrowable(String error) {
        return isTimeoutError(error);
    }

    /** Hallucination-class rejects feed rulesRejectedHallucinated (quote / value not in source). */
    private static boolean isHallucinationReject(List<Map<String, Object>> warnings) {
        if (warnings == null) {
            return false;
        }
        for (Map<String, Object> w : warnings) {
            String code = String.valueOf(w.get("code"));
            if ("HALLUCINATED_QUOTE".equals(code)
                    || "VALUE_NOT_IN_SOURCE".equals(code)
                    || "REJECTED_VALUE_NOT_IN_SOURCE".equals(code)) {
                return true;
            }
        }
        return false;
    }

    private static final Pattern CLAUSE_NUM = Pattern.compile("(\\d+)\\.(\\d+)");

    /**
     * Flag near-identical rules from adjacent clauses (e.g. 5.1 vs 5.2) with POSSIBLE_DUPLICATE
     * instead of silently proposing both as independent.
     */
    @SuppressWarnings("unchecked")
    private void flagPossibleDuplicates(UUID tenantId, UUID setId) {
        List<Map<String, Object>> rules = setRepository.listRules(tenantId, setId);
        if (rules.size() < 2) {
            return;
        }
        Set<Integer> flagged = new HashSet<>();
        for (int i = 0; i < rules.size(); i++) {
            if (flagged.contains(i)) {
                continue;
            }
            Map<String, Object> a = rules.get(i);
            if ("REJECTED".equals(String.valueOf(a.get("status")))) {
                continue;
            }
            String whenA = canonicalJson(a.get("when"));
            int levelA = thenLevel(a);
            ClauseRef refA = parseClauseRef(a);
            for (int j = i + 1; j < rules.size(); j++) {
                if (flagged.contains(j)) {
                    continue;
                }
                Map<String, Object> b = rules.get(j);
                if ("REJECTED".equals(String.valueOf(b.get("status")))) {
                    continue;
                }
                String whenB = canonicalJson(b.get("when"));
                if (!whenA.equals(whenB) || levelA != thenLevel(b)) {
                    continue;
                }
                ClauseRef refB = parseClauseRef(b);
                boolean adjacent = refA != null && refB != null
                        && refA.major == refB.major
                        && Math.abs(refA.minor - refB.minor) == 1;
                boolean sameFingerprint = whenA.equals(whenB) && levelA == thenLevel(b);
                if (!adjacent && !sameFingerprint) {
                    continue;
                }
                String peer = refB != null ? refB.raw : String.valueOf(b.get("ruleId"));
                String peerA = refA != null ? refA.raw : String.valueOf(a.get("ruleId"));
                appendDuplicateWarning(tenantId, a, peer);
                appendDuplicateWarning(tenantId, b, peerA);
                flagged.add(i);
                flagged.add(j);
            }
        }
    }

    private void appendDuplicateWarning(UUID tenantId, Map<String, Object> rule, String peerRef) {
        List<Map<String, Object>> warnings = new ArrayList<>();
        if (rule.get("warnings") instanceof List<?> prior) {
            for (Object o : prior) {
                if (o instanceof Map<?, ?> m) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> wm = (Map<String, Object>) m;
                    if ("POSSIBLE_DUPLICATE".equals(String.valueOf(wm.get("code")))) {
                        return;
                    }
                    warnings.add(new LinkedHashMap<>(wm));
                }
            }
        }
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("code", "POSSIBLE_DUPLICATE");
        w.put("message", "Near-identical condition/level as clause " + peerRef
                + " — review before accepting both");
        warnings.add(w);
        rule.put("warnings", warnings);
        UUID rulePk = UUID.fromString(String.valueOf(rule.get("id")));
        setRepository.updateRuleStatus(tenantId, rulePk, String.valueOf(rule.get("status")), rule);
        log.info("rule_validation_flag ruleId={} check=POSSIBLE_DUPLICATE peer={}",
                rule.get("ruleId"), peerRef);
    }

    private String canonicalJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private static int thenLevel(Map<String, Object> rule) {
        Object then = rule.get("then");
        if (then instanceof Map<?, ?> m && m.get("minLevel") instanceof Number n) {
            return n.intValue();
        }
        return -1;
    }

    private record ClauseRef(int major, int minor, String raw) {
    }

    private static ClauseRef parseClauseRef(Map<String, Object> rule) {
        Object source = rule.get("source");
        if (!(source instanceof Map<?, ?> s)) {
            return null;
        }
        Object ref = s.get("clauseRef");
        if (ref == null) {
            return null;
        }
        String raw = String.valueOf(ref).strip();
        Matcher m = CLAUSE_NUM.matcher(raw);
        if (!m.find()) {
            return null;
        }
        return new ClauseRef(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), raw);
    }

    /** Align mock numeric literals with numbers actually present in the chunk. */
    @SuppressWarnings("unchecked")
    private static void groundMockAmounts(Map<String, Object> rule, String chunkText) {
        var nums = SourceNumberParser.extractNumbers(chunkText);
        if (nums.isEmpty()) {
            return;
        }
        long preferred = nums.stream().mapToLong(Long::longValue).max().orElse(0);
        Object whenObj = rule.get("when");
        if (!(whenObj instanceof Map<?, ?>)) {
            return;
        }
        rewriteAmountLeaves((Map<String, Object>) whenObj, preferred, nums);
    }

    @SuppressWarnings("unchecked")
    private static void rewriteAmountLeaves(Map<String, Object> node, long preferred, java.util.Set<Long> nums) {
        if (node.containsKey("all") && node.get("all") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    rewriteAmountLeaves((Map<String, Object>) m, preferred, nums);
                }
            }
        } else if (node.containsKey("any") && node.get("any") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    rewriteAmountLeaves((Map<String, Object>) m, preferred, nums);
                }
            }
        } else if (node.containsKey("not") && node.get("not") instanceof Map<?, ?> m) {
            rewriteAmountLeaves((Map<String, Object>) m, preferred, nums);
        } else if ("ask.amountInr".equals(String.valueOf(node.get("fact")))
                && node.get("value") instanceof Number n
                && !nums.contains(Math.round(n.doubleValue()))) {
            node.put("value", preferred);
        }
    }

    @SuppressWarnings("unchecked")
    private void persistKeywordsAndFacts(
            UUID tenantId, UUID setId, Map<String, Object> rule, Map<String, Object> raw, String clauseText
    ) {
        String ruleId = String.valueOf(rule.get("ruleId"));
        String text = clauseText;
        if (text == null || text.isBlank()) {
            Map<String, Object> source = asMap(rule.get("source"));
            if (source.get("quote") != null) {
                text = String.valueOf(source.get("quote"));
            }
        }
        List<Map<String, Object>> keywords = KeywordExtractor.merge(raw.get("keywords"), text);
        if (keywords.isEmpty() && rule.get("keywords") != null) {
            keywords = KeywordExtractor.merge(rule.get("keywords"), text);
        }
        for (Map<String, Object> km : keywords) {
            String term = String.valueOf(km.get("term"));
            if (term == null || term.isBlank() || "null".equals(term)) {
                continue;
            }
            String lang = km.get("lang") == null ? "en" : String.valueOf(km.get("lang"));
            String cat = KeywordExtractor.normalizeCategory(
                    km.get("category") == null ? "CUSTOM" : String.valueOf(km.get("category")));
            double w = km.get("weight") instanceof Number n ? n.doubleValue() : 1.0;
            compileRepository.insertKeyword(tenantId, setId, term, lang, cat, w, ruleId);
        }
        Object fact = raw.get("policyFact");
        if (fact == null) {
            fact = rule.get("description");
        }
        if (fact != null && !String.valueOf(fact).isBlank()) {
            compileRepository.insertFact(tenantId, setId, ruleId, String.valueOf(fact));
        }
    }

    private static String buildSystemPrompt() {
        return """
                You extract policy rule MEANING from one untrusted clause. Return ONLY JSON {"schemaVersion":"2","rules":[...]}.
                Each rule object has ONLY: when (condition tree), then (minLevel 1-4), optional modality, keywords, appliesTo.
                Do NOT emit title, source, clauseRef, quote, ruleId, documentId, or chunkId — Java attaches those from the chunk.
                Use ONLY catalogue fact paths and enum values listed in the user message.
                Return rules for obligations, prohibitions, and numeric thresholds.
                If the clause has no actionable obligation, return exactly {"schemaVersion":"2","rules":[]}.
                Never invent amounts, durations, or action types not present in the clause.
                """;
    }

    private static String buildShortSystemPrompt() {
        return """
                Extract ONE rule meaning as JSON {"schemaVersion":"2","rules":[{"when":{...},"then":{"minLevel":N}}]} if the clause has must/must not/never/shall/requires/above INR.
                Do not emit title or source. If truly no obligation, return {"schemaVersion":"2","rules":[]}. JSON only.
                """;
    }

    private String buildUserPrompt(PolicyDocumentChunkEntity chunk) {
        return buildUserPrompt(chunk, FactCatalogue.subsetForClause(chunk.getText()));
    }

    private String buildUserPrompt(PolicyDocumentChunkEntity chunk, java.util.List<FactCatalogue.FactDef> subset) {
        String factsJson;
        try {
            factsJson = objectMapper.writeValueAsString(FactCatalogue.subsetToApiBody(subset));
        } catch (Exception e) {
            factsJson = "{}";
        }
        return """
                Catalogue subset for this clause (fact.path must be one of these; respect type/enum):
                %s

                Worked example A (yields a rule — meaning only):
                Clause: "Staff must not process wire transfers above INR 10,00,000 (ten lakh) to unknown beneficiaries without Level 3 verification."
                Output: {"schemaVersion":"2","rules":[{"when":{"all":[{"fact":"ask.type","op":"EQ","value":"WIRE_TRANSFER"},{"fact":"ask.amountInr","op":"GT","value":1000000},{"fact":"ask.beneficiaryKnown","op":"EQ","value":false}]},"then":{"minLevel":3},"modality":"must_not"}]}

                Worked example B (definition/scope only → empty list):
                Clause: "1.1 Definitions. This policy applies to all employees and defines terms used herein."
                Output: {"schemaVersion":"2","rules":[]}
                Do NOT emit placeholders with empty when. Do NOT emit title/source/quote/clauseRef.
                Do NOT return empty for clauses that contain must / must not / never / shall / requires / above INR.

                Chunk heading: %s
                Chunk page: %s
                (Full chunk text is in <untrusted_data>.)
                """.formatted(
                factsJson,
                chunk.getHeadingPath() == null ? "" : chunk.getHeadingPath(),
                chunk.getPageNo() == null ? "" : chunk.getPageNo()
        );
    }

    private String buildShortUserPrompt(
            PolicyDocumentChunkEntity chunk,
            java.util.List<FactCatalogue.FactDef> subset
    ) {
        String factsJson;
        try {
            factsJson = objectMapper.writeValueAsString(FactCatalogue.subsetToApiBody(subset));
        } catch (Exception e) {
            factsJson = "{}";
        }
        return """
                Facts (use only these paths): %s
                Heading: %s
                This clause has obligation language. Propose when + then.minLevel only (no title/source).
                Return {"schemaVersion":"2","rules":[]} ONLY if there is truly no obligation.
                """.formatted(
                factsJson,
                chunk.getHeadingPath() == null ? "" : chunk.getHeadingPath()
        );
    }

    public Map<String, Object> testClause(UUID tenantId, String clauseText) {
        boolean allowExternal = settingsRepository.findById(tenantId)
                .map(TenantSettingsEntity::isAllowExternalLlm)
                .orElse(false);
        return testClause(tenantId, clauseText, allowExternal);
    }

    /**
     * Admin-only smoke test: run the compile LLM pipeline on a single pasted clause.
     */
    public Map<String, Object> testClause(UUID tenantId, String clauseText, boolean allowExternal) {
        long t0 = System.nanoTime();
        String text = clauseText == null ? "" : clauseText.strip();
        if (text.isBlank()) {
            throw new PolicyCompileException("EMPTY", "Clause text is required");
        }
        boolean prefilter = ChunkPrefilter.isCandidate(text);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemaVersion", "2");
        out.put("prefilterCandidate", prefilter);
        out.put("obligationCues", hasStrongObligationCues(text));
        if (!prefilter) {
            out.put("status", "SKIPPED_PREFILTER");
            out.put("reason", "no_obligation_keywords");
            out.put("timingMs", (System.nanoTime() - t0) / 1_000_000L);
            return out;
        }
        PolicyDocumentChunkEntity fake = new PolicyDocumentChunkEntity();
        fake.setId(UUID.randomUUID());
        fake.setText(text);
        fake.setHeadingPath("test-clause");
        fake.setOrdinal(0);
        long llmStart = System.nanoTime();
        LlmChunkOutcome llm = callLlmWithRetry(tenantId, fake, allowExternal);
        long llmMs = (System.nanoTime() - llmStart) / 1_000_000L;
        out.put("status", llm.status());
        out.put("reason", llm.reason());
        out.put("llmMeta", llm.meta());
        out.put("llmMs", llmMs);
        out.put("rawRules", llm.rules());
        List<Map<String, Object>> validations = new ArrayList<>();
        for (Map<String, Object> raw : llm.rules()) {
            if (isPlaceholderRule(raw)) {
                Map<String, Object> skip = new LinkedHashMap<>();
                skip.put("placeholder", true);
                skip.put("rule", raw);
                validations.add(skip);
                continue;
            }
            long v0 = System.nanoTime();
            Map<String, Object> enriched = enrich(raw, null, fake);
            RuleValidator.Result validated = RuleValidator.validate(enriched, text, false);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rule", validated.rule());
            row.put("autoReject", validated.autoReject());
            row.put("warnings", validated.warnings());
            row.put("validationMs", (System.nanoTime() - v0) / 1_000_000L);
            validations.add(row);
        }
        out.put("validations", validations);
        out.put("timingMs", (System.nanoTime() - t0) / 1_000_000L);
        return out;
    }
}

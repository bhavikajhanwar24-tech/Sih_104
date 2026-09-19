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
import com.sentinelvoice.policy.dsl.FactCatalogue;
import com.sentinelvoice.policy.sets.PolicySetRepository;
import com.sentinelvoice.security.TenantContext;
import com.sentinelvoice.tenant.TenantSettingsEntity;
import com.sentinelvoice.tenant.TenantSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
            PolicyCompileProperties compileProperties
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

    public Map<String, Object> resume(UUID tenantId, UUID userId, UUID id) {
        Map<String, Object> c = compileRepository.findCompilation(tenantId, id)
                .orElseThrow(() -> new PolicyCompileException("NOT_FOUND", "Compilation not found"));
        String status = String.valueOf(c.get("status"));
        if ("RUNNING".equals(status)) {
            throw new PolicyCompileException("BUSY", "Compilation already running");
        }
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
     * Re-process only chunks that timed out, schema-failed, returned empty, or failed validation
     * (and any FAILED jobs). Keeps already-OK results and existing rules.
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
        compileRepository.resetFailedChunksForRerun(id);
        cancelFlags.put(id, new AtomicBoolean(false));
        compileRepository.updateStatus(id, "QUEUED", null);
        policyExtractionExecutor.execute(() -> runCompilation(tenantId, id));
        auditLedgerService.append(
                tenantId, null, AuditEventType.POLICY_COMPILE_RESUMED, "USER", userId.toString(),
                Map.of("compilationId", id.toString(), "rerunFailed", true)
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
                int done = ((Number) progress.getOrDefault("chunksDone", 0)).intValue();
                int failed = ((Number) progress.getOrDefault("chunksFailed", 0)).intValue();
                int proposed = ((Number) progress.getOrDefault("rulesProposed", 0)).intValue();
                int hallucinated = ((Number) progress.getOrDefault("rulesRejectedHallucinated", 0)).intValue();

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
                                    "LLM_EMPTY", "chunk_missing", ms, 0
                            );
                            failed++;
                            continue;
                        }
                        boolean injection = chunk.getInjectionFlags() != null && !chunk.getInjectionFlags().isEmpty();
                        LlmChunkOutcome llm = callLlmWithRetry(tenantId, chunk, allowExternal);
                        int ms = (int) ((System.nanoTime() - t0) / 1_000_000L);

                        if (!"LLM_OK".equals(llm.status())) {
                            compileRepository.updateChunk(
                                    compilationId, chunkId, "FAILED", 0, 0, llm.reason()
                            );
                            compileRepository.upsertChunkResult(
                                    tenantId, compilationId, documentId, chunkId,
                                    llm.status(), llm.reason(), ms, 0
                            );
                            failed++;
                            progress.put("chunksDone", ++done);
                            progress.put("chunksFailed", failed);
                            progress.put("rulesProposed", proposed);
                            progress.put("rulesRejectedHallucinated", hallucinated);
                            mergeDiagnosticCounts(progress, compileRepository.listChunkResults(compilationId));
                            compileRepository.updateProgress(compilationId, progress);
                            continue;
                        }

                        List<Map<String, Object>> rules = llm.rules();
                        if (rules.isEmpty()) {
                            compileRepository.updateChunk(compilationId, chunkId, "DONE", 0, 0, "empty");
                            compileRepository.upsertChunkResult(
                                    tenantId, compilationId, documentId, chunkId,
                                    "LLM_EMPTY", "LLM returned zero rules", ms, 0
                            );
                            done++;
                        } else {
                            int localProposed = 0;
                            int localRejected = 0;
                            for (Map<String, Object> raw : rules) {
                                Map<String, Object> enriched = enrich(raw, documentId, chunkId, chunk.getText());
                                RuleValidator.Result validated =
                                        RuleValidator.validate(enriched, chunk.getText(), injection);
                                if (validated.autoReject()) {
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
                                persistKeywordsAndFacts(tenantId, setId, validated.rule(), raw);
                            }
                            String resultStatus = localProposed > 0
                                    ? "LLM_OK"
                                    : (localRejected > 0 ? "REJECTED_VALIDATION" : "LLM_EMPTY");
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
                                    resultStatus, reason, ms, localProposed
                            );
                            done++;
                        }
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
                        done++;
                    }
                    progress.put("chunksDone", done);
                    progress.put("chunksFailed", failed);
                    progress.put("rulesProposed", proposed);
                    progress.put("rulesRejectedHallucinated", hallucinated);
                    mergeDiagnosticCounts(progress, compileRepository.listChunkResults(compilationId));
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
                    auditLedgerService.append(
                            tenantId, null, AuditEventType.POLICY_COMPILE_COMPLETED, "SYSTEM", "compiler",
                            Map.of(
                                    "compilationId", compilationId.toString(),
                                    "rulesProposed", proposed,
                                    "hallucinated", hallucinated,
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
                    || "LLM_SCHEMA_ERROR".equals(st)
                    || "LLM_EMPTY".equals(st)
                    || "REJECTED_VALIDATION".equals(st);
        }));
        return out;
    }

    private static Map<String, Object> countByStatus(List<Map<String, Object>> results) {
        Map<String, Object> counts = new LinkedHashMap<>();
        for (String s : List.of(
                "SKIPPED_PREFILTER", "LLM_OK", "LLM_TIMEOUT", "LLM_SCHEMA_ERROR", "LLM_EMPTY", "REJECTED_VALIDATION"
        )) {
            counts.put(s, 0);
        }
        for (Map<String, Object> r : results) {
            String st = String.valueOf(r.get("status"));
            counts.put(st, ((Number) counts.getOrDefault(st, 0)).intValue() + 1);
        }
        return counts;
    }

    private static void mergeDiagnosticCounts(Map<String, Object> progress, List<Map<String, Object>> results) {
        Map<String, Object> counts = countByStatus(results);
        progress.put("skippedPrefilter", counts.get("SKIPPED_PREFILTER"));
        progress.put("llmOk", counts.get("LLM_OK"));
        progress.put("timedOut", counts.get("LLM_TIMEOUT"));
        progress.put("schemaErrors", counts.get("LLM_SCHEMA_ERROR"));
        progress.put("llmEmpty", counts.get("LLM_EMPTY"));
        progress.put("rejectedValidation", counts.get("REJECTED_VALIDATION"));
        progress.put("chunksTotal", results.size());
        progress.put("summary", formatSummary(progress));
    }

    private static String formatSummary(Map<String, Object> progress) {
        int total = num(progress, "chunksTotal");
        int skipped = num(progress, "skippedPrefilter");
        int timedOut = num(progress, "timedOut");
        int schema = num(progress, "schemaErrors");
        int empty = num(progress, "llmEmpty");
        int rejected = num(progress, "rejectedValidation");
        int ok = num(progress, "llmOk");
        List<String> parts = new ArrayList<>();
        if (skipped > 0) {
            parts.add(skipped + " skipped by pre-filter");
        }
        if (timedOut > 0) {
            parts.add(timedOut + " timed out");
        }
        if (schema > 0) {
            parts.add(schema + " schema errors");
        }
        if (empty > 0) {
            parts.add(empty + " empty LLM replies");
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

    private static int num(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Number n ? n.intValue() : 0;
    }

    private static Map<String, Object> baseProgress(int total, int candidates, int skipped) {
        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("chunksTotal", total);
        progress.put("chunksCandidate", candidates);
        progress.put("chunksSkipped", skipped);
        progress.put("chunksDone", 0);
        progress.put("chunksFailed", 0);
        progress.put("rulesProposed", 0);
        progress.put("rulesRejectedHallucinated", 0);
        progress.put("skippedPrefilter", skipped);
        progress.put("llmOk", 0);
        progress.put("timedOut", 0);
        progress.put("schemaErrors", 0);
        progress.put("llmEmpty", 0);
        progress.put("rejectedValidation", 0);
        progress.put("summary", total + " chunks queued (" + skipped + " pre-filter skips)");
        return progress;
    }

    private record LlmChunkOutcome(String status, String reason, List<Map<String, Object>> rules) {
    }

    private LlmChunkOutcome callLlmWithRetry(
            UUID tenantId, PolicyDocumentChunkEntity chunk, boolean allowExternal
    ) {
        LlmChunkOutcome first = callLlmOnce(tenantId, chunk, allowExternal);
        if ("LLM_OK".equals(first.status()) || "LLM_EMPTY".equals(first.status())) {
            return first;
        }
        if ("LLM_TIMEOUT".equals(first.status()) || "LLM_SCHEMA_ERROR".equals(first.status())) {
            log.info("policy_compile_retry chunkId={} priorStatus={}", chunk.getId(), first.status());
            return callLlmOnce(tenantId, chunk, allowExternal);
        }
        return first;
    }

    @SuppressWarnings("unchecked")
    private LlmChunkOutcome callLlmOnce(
            UUID tenantId, PolicyDocumentChunkEntity chunk, boolean allowExternal
    ) {
        Map<String, Object> schema = FactCatalogue.buildCompileResultSchema();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("system", buildSystemPrompt());
        payload.put("user", buildUserPrompt(chunk));
        payload.put("jsonSchema", schema);
        payload.put("untrustedData", chunk.getText());
        payload.put("allowExternalLlm", allowExternal);

        try {
            Map<String, Object> out = llmGatewayClient.run("policy_compile", tenantId, payload);
            String error = out.get("error") == null ? "" : String.valueOf(out.get("error"));
            if (!Boolean.TRUE.equals(out.get("ok")) && out.get("result") == null) {
                if (isTimeoutError(error) || isTimeoutThrowable(error)) {
                    return new LlmChunkOutcome("LLM_TIMEOUT", error, List.of());
                }
                if (error.toUpperCase(Locale.ROOT).contains("SCHEMA")) {
                    return new LlmChunkOutcome("LLM_SCHEMA_ERROR", error, List.of());
                }
                return new LlmChunkOutcome("LLM_EMPTY", error.isBlank() ? "llm_failed" : error, List.of());
            }
            if (error.toUpperCase(Locale.ROOT).contains("SCHEMA") && out.get("result") == null) {
                return new LlmChunkOutcome("LLM_SCHEMA_ERROR", error, List.of());
            }
            Object result = out.get("result");
            if (!(result instanceof Map<?, ?> map)) {
                return new LlmChunkOutcome("LLM_EMPTY", "result_not_object", List.of());
            }
            Object rules = map.get("rules");
            if (!(rules instanceof List<?> list)) {
                return new LlmChunkOutcome("LLM_EMPTY", "rules_missing", List.of());
            }
            List<Map<String, Object>> parsed = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> rm) {
                    parsed.add(new LinkedHashMap<>((Map<String, Object>) rm));
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
                return new LlmChunkOutcome("LLM_EMPTY", "zero_rules", List.of());
            }
            return new LlmChunkOutcome("LLM_OK", null, parsed);
        } catch (ResourceAccessException ex) {
            if (isTimeoutException(ex)) {
                return new LlmChunkOutcome("LLM_TIMEOUT", ex.getMessage(), List.of());
            }
            throw ex;
        } catch (Exception ex) {
            if (isTimeoutException(ex)) {
                return new LlmChunkOutcome("LLM_TIMEOUT", ex.getMessage(), List.of());
            }
            String msg = ex.getMessage() == null ? ex.toString() : ex.getMessage();
            if (msg.toUpperCase(Locale.ROOT).contains("SCHEMA")) {
                return new LlmChunkOutcome("LLM_SCHEMA_ERROR", msg, List.of());
            }
            throw ex;
        }
    }

    private static String classifyException(Exception ex) {
        if (isTimeoutException(ex)) {
            return "LLM_TIMEOUT";
        }
        String msg = ex.getMessage() == null ? "" : ex.getMessage().toUpperCase(Locale.ROOT);
        if (msg.contains("SCHEMA")) {
            return "LLM_SCHEMA_ERROR";
        }
        return "LLM_EMPTY";
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
                    || "REJECTED_VALUE_NOT_IN_SOURCE".equals(code)
                    || "MISSING_CLAUSE_REF".equals(code)) {
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

    private Map<String, Object> enrich(
            Map<String, Object> raw, UUID documentId, UUID chunkId, String chunkText
    ) {
        Map<String, Object> rule = new LinkedHashMap<>(raw);
        Map<String, Object> source = new LinkedHashMap<>();
        if (raw.get("source") instanceof Map<?, ?> s) {
            s.forEach((k, v) -> source.put(String.valueOf(k), v));
        }
        source.put("documentId", documentId.toString());
        source.put("chunkIds", List.of(chunkId.toString()));
        // Do NOT invent clauseRef or quote for live LLM output — RuleValidator rejects gaps.
        if ("LLM_MOCK".equals(String.valueOf(rule.get("origin"))) && chunkText != null) {
            String q = chunkText.strip().replaceAll("\\s+", " ");
            if (q.length() > 180) {
                q = q.substring(0, 180);
            }
            source.put("quote", q);
            groundMockAmounts(rule, chunkText);
        }
        rule.put("source", source);
        if (rule.get("ruleId") == null) {
            rule.put("ruleId", "R-" + chunkId.toString().substring(0, 8));
        }
        rule.putIfAbsent("status", "PROPOSED");
        rule.putIfAbsent("origin", "LLM");
        rule.putIfAbsent("appliesTo", Map.of("actionTypes", List.of("WIRE_TRANSFER"), "callerRoles", List.of("*")));
        rule.putIfAbsent("severity", "MEDIUM");
        return rule;
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
            UUID tenantId, UUID setId, Map<String, Object> rule, Map<String, Object> raw
    ) {
        String ruleId = String.valueOf(rule.get("ruleId"));
        if (raw.get("keywords") instanceof List<?> kws) {
            for (Object k : kws) {
                if (k instanceof Map<?, ?> km) {
                    String term = String.valueOf(km.get("term"));
                    if (term == null || term.isBlank() || "null".equals(term)) {
                        continue;
                    }
                    String lang = km.get("lang") == null ? "en" : String.valueOf(km.get("lang"));
                    String cat = km.get("category") == null ? "CUSTOM" : String.valueOf(km.get("category"));
                    double w = km.get("weight") instanceof Number n ? n.doubleValue() : 1.0;
                    compileRepository.insertKeyword(tenantId, setId, term, lang, cat, w, ruleId);
                }
            }
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
                You extract policy rules from one untrusted clause. Return ONLY JSON {"schemaVersion":"2","rules":[...]}.
                Use ONLY catalogue fact paths and enum values. Copy a verbatim quote (<=200 chars) and a clauseRef.
                If the clause is general, definitional, or has no obligation/prohibition/threshold, return {"schemaVersion":"2","rules":[]}.
                Never invent amounts, durations, or action types not present in the clause.
                """;
    }

    private String buildUserPrompt(PolicyDocumentChunkEntity chunk) {
        var subset = FactCatalogue.subsetForClause(chunk.getText());
        String factsJson;
        try {
            factsJson = objectMapper.writeValueAsString(FactCatalogue.subsetToApiBody(subset));
        } catch (Exception e) {
            factsJson = "{}";
        }
        return """
                Catalogue subset for this clause (fact.path must be one of these; respect type/enum):
                %s

                Worked example A (yields a rule):
                Clause: "Staff must not process wire transfers above INR 10,00,000 (ten lakh) to unknown beneficiaries without Level 3 verification."
                Output rules[0].when = {"all":[{"fact":"ask.type","op":"EQ","value":"WIRE_TRANSFER"},{"fact":"ask.amountInr","op":"GT","value":1000000},{"fact":"ask.beneficiaryKnown","op":"EQ","value":false}]}
                then.minLevel = 3; source.quote copied from the clause; source.clauseRef = "5.2"

                Worked example B (general / definition / scope → empty list — e.g. clauses 1.1, 1.2, 6.1):
                Clause: "1.1 Definitions. This policy applies to all employees of Demo Bank and defines terms used herein."
                Output: {"schemaVersion":"2","rules":[]}
                Do the same for pure scope statements (1.2) and glossary-only clauses (6.1) with no obligation.

                Chunk heading: %s
                Chunk page: %s
                (Full chunk text is in <untrusted_data>.)
                """.formatted(
                factsJson,
                chunk.getHeadingPath() == null ? "" : chunk.getHeadingPath(),
                chunk.getPageNo() == null ? "" : chunk.getPageNo()
        );
    }
}

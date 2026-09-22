package com.sentinelvoice.explain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelvoice.fusion.EvidenceFamily;
import com.sentinelvoice.fusion.FamilyScore;
import com.sentinelvoice.fusion.ReasonGenerator;
import com.sentinelvoice.model.Ask;
import com.sentinelvoice.model.CallSession;
import com.sentinelvoice.model.FeatureFrame;
import com.sentinelvoice.model.InterventionLevel;
import com.sentinelvoice.model.LinguisticFamily;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * F12 — persists sampled ticks, reasons, and redacted extractions under tenant RLS.
 */
@Service
public class SessionExplainRecorder {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<String, SampleState> samples = new ConcurrentHashMap<>();

    public SessionExplainRecorder(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void onTick(
            UUID tenantId,
            CallSession session,
            long tMs,
            double score,
            InterventionLevel level,
            Map<EvidenceFamily, FamilyScore> families,
            List<String> firedRuleIds,
            String llmState,
            List<ReasonGenerator.GeneratedReason> reasons,
            FeatureFrame frame
    ) {
        if (tenantId == null || session == null || session.getSessionId() == null) {
            return;
        }
        UUID sessionUuid = parseUuid(session.getSessionId());
        if (sessionUuid == null) {
            return;
        }

        Sampling sampling = loadSampling(tenantId);
        SampleState prev = samples.get(session.getSessionId());
        boolean shouldStore = prev == null
                || levelChanged(prev.level, level)
                || Math.abs(score - prev.score) >= sampling.scoreDelta()
                || (tMs - prev.tMs) >= sampling.maxGapMs();

        if (shouldStore) {
            insertTick(tenantId, sessionUuid, tMs, score, level, families, firedRuleIds, llmState);
            samples.put(session.getSessionId(), new SampleState(tMs, score, level == null ? null : level.name()));
        }

        if (reasons != null && !reasons.isEmpty()) {
            persistReasons(tenantId, sessionUuid, tMs, reasons);
        }

        if (frame != null && frame.linguistic() != null) {
            persistExtraction(tenantId, sessionUuid, tMs, frame.linguistic());
        }
    }

    /**
     * Softphone / analyst override path — no FeatureFrame. Still records a tick (+ optional reason)
     * so Call Detail / dossier are not empty after Force L3.
     */
    public void onOperatorLevelChange(
            UUID tenantId,
            CallSession session,
            long tMs,
            double score,
            InterventionLevel level,
            String reasonCode,
            String reasonDetail
    ) {
        if (tenantId == null || session == null || session.getSessionId() == null) {
            return;
        }
        UUID sessionUuid = parseUuid(session.getSessionId());
        if (sessionUuid == null) {
            return;
        }
        insertTick(tenantId, sessionUuid, tMs, score, level, Map.of(), List.of(), "OPERATOR_OVERRIDE");
        samples.put(session.getSessionId(), new SampleState(tMs, score, level == null ? null : level.name()));
        if (reasonCode != null && !reasonCode.isBlank()) {
            try {
                Integer maxSeq = jdbc.query(
                        "SELECT COALESCE(MAX(seq), 0) FROM session_reasons WHERE tenant_id = ? AND session_id = ?",
                        rs -> rs.next() ? rs.getInt(1) : 0,
                        tenantId, sessionUuid
                );
                int seq = (maxSeq == null ? 0 : maxSeq) + 1;
                jdbc.update(
                        """
                        INSERT INTO session_reasons (
                          tenant_id, session_id, seq, t_ms, code, title, detail, contribution,
                          family, severity, evidence, source_clause, rule_id, policy_version
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '{}'::jsonb, NULL, NULL, NULL)
                        ON CONFLICT (tenant_id, session_id, seq) DO NOTHING
                        """,
                        tenantId,
                        sessionUuid,
                        seq,
                        tMs,
                        reasonCode.trim(),
                        reasonCode.trim(),
                        reasonDetail == null ? "" : reasonDetail,
                        1.0,
                        "OPERATOR",
                        level == null ? "INFO" : level.name()
                );
            } catch (Exception ignored) {
                // never break override path
            }
        }
    }

    public void clearSession(String sessionId) {
        if (sessionId != null) {
            samples.remove(sessionId);
        }
    }

    private void insertTick(
            UUID tenantId,
            UUID sessionId,
            long tMs,
            double score,
            InterventionLevel level,
            Map<EvidenceFamily, FamilyScore> families,
            List<String> firedRuleIds,
            String llmState
    ) {
        Map<String, Object> familyJson = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        if (families != null) {
            for (EvidenceFamily f : EvidenceFamily.values()) {
                FamilyScore fs = families.get(f);
                if (fs == null || !fs.available()) {
                    missing.add(f.configKey());
                } else {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("score", fs.score());
                    row.put("contribution", fs.contribution());
                    row.put("weight", fs.weight());
                    familyJson.put(f.configKey(), row);
                }
            }
        }
        String[] rules = firedRuleIds == null ? new String[0] : firedRuleIds.toArray(String[]::new);
        String[] miss = missing.toArray(String[]::new);
        try {
            jdbc.update(
                    """
                    INSERT INTO session_ticks (
                      tenant_id, session_id, t_ms, score, level, family_scores,
                      missing_families, fired_rule_ids, llm_state
                    ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                    ON CONFLICT (tenant_id, session_id, t_ms) DO UPDATE SET
                      score = EXCLUDED.score,
                      level = EXCLUDED.level,
                      family_scores = EXCLUDED.family_scores,
                      missing_families = EXCLUDED.missing_families,
                      fired_rule_ids = EXCLUDED.fired_rule_ids,
                      llm_state = EXCLUDED.llm_state
                    """,
                    tenantId,
                    sessionId,
                    tMs,
                    score,
                    level == null ? "LEVEL_1_SILENT" : level.name(),
                    toJson(familyJson),
                    miss,
                    rules,
                    llmState
            );
        } catch (Exception ignored) {
            // Explainability must not break the ingest hot path.
        }
    }

    private void persistReasons(
            UUID tenantId,
            UUID sessionId,
            long tMs,
            List<ReasonGenerator.GeneratedReason> reasons
    ) {
        Integer maxSeq = jdbc.query(
                "SELECT COALESCE(MAX(seq), 0) FROM session_reasons WHERE tenant_id = ? AND session_id = ?",
                rs -> rs.next() ? rs.getInt(1) : 0,
                tenantId, sessionId
        );
        int seq = maxSeq == null ? 0 : maxSeq;
        for (ReasonGenerator.GeneratedReason r : reasons) {
            if (r == null || r.canonicalCode() == null) {
                continue;
            }
            seq++;
            try {
                jdbc.update(
                        """
                        INSERT INTO session_reasons (
                          tenant_id, session_id, seq, t_ms, code, title, detail, contribution,
                          family, severity, evidence, source_clause, rule_id, policy_version
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
                        ON CONFLICT (tenant_id, session_id, seq) DO NOTHING
                        """,
                        tenantId,
                        sessionId,
                        seq,
                        tMs,
                        r.canonicalCode().name(),
                        r.title() == null ? r.canonicalCode().name() : r.title(),
                        r.text() == null ? "" : r.text(),
                        r.contribution(),
                        r.family() == null ? null : r.family().configKey(),
                        r.severity() == null ? null : r.severity().name(),
                        toJson(r.evidence()),
                        r.sourceClause() == null ? null : toJson(r.sourceClause()),
                        r.ruleId(),
                        r.policyVersion()
                );
            } catch (Exception ignored) {
                // hot path
            }
        }
    }

    private void persistExtraction(UUID tenantId, UUID sessionId, long tMs, LinguisticFamily linguistic) {
        Map<String, Object> ask = new LinkedHashMap<>();
        Ask a = linguistic.ask();
        if (a != null) {
            if (a.amount() != null) {
                // Store amount ranges only — never raw account numbers.
                ask.put("amountRange", amountRange(a.amount()));
            }
            if (a.currency() != null) {
                ask.put("currency", a.currency());
            }
            if (a.type() != null) {
                ask.put("type", a.type());
            }
            if (a.sharesCredential() != null) {
                ask.put("sharesCredential", a.sharesCredential());
            }
            if (a.beneficiaryMentioned() != null) {
                ask.put("beneficiaryMentioned", a.beneficiaryMentioned());
            }
        }
        Map<String, Object> categories = new LinkedHashMap<>();
        if (linguistic.categories() != null) {
            for (Map.Entry<String, Double> e : linguistic.categories().entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    categories.put(e.getKey(), e.getValue());
                }
            }
        }
        // Keep scalar summaries for older readers.
        if (linguistic.secrecy() != null) {
            categories.putIfAbsent("secrecy", linguistic.secrecy());
        }
        if (linguistic.urgency() != null) {
            categories.putIfAbsent("urgency", linguistic.urgency());
        }
        if (linguistic.authorityInvocation() != null) {
            categories.putIfAbsent("authority", linguistic.authorityInvocation());
        }
        if (linguistic.claimedRole() != null) {
            categories.put("claimedRole", linguistic.claimedRole());
        }
        if (linguistic.matchedRuleIds() != null && !linguistic.matchedRuleIds().isEmpty()) {
            categories.put("matchedRuleIds", linguistic.matchedRuleIds());
        }
        if (linguistic.matchedKeywords() != null && !linguistic.matchedKeywords().isEmpty()) {
            categories.put("matchedKeywords", linguistic.matchedKeywords());
        }
        if (linguistic.llmThinking() != null && !linguistic.llmThinking().isBlank()) {
            categories.put("llmThinking", linguistic.llmThinking().trim());
        }
        if (Boolean.TRUE.equals(linguistic.injectionAttempt())) {
            categories.put("injectionAttempt", true);
        }
        if (ask.isEmpty() && categories.isEmpty()) {
            return;
        }
        try {
            jdbc.update(
                    """
                    INSERT INTO session_extractions (tenant_id, session_id, t_ms, ask, categories)
                    VALUES (?, ?, ?, ?::jsonb, ?::jsonb)
                    """,
                    tenantId, sessionId, tMs, toJson(ask), toJson(categories)
            );
        } catch (Exception ignored) {
            // hot path
        }
    }

    private static String amountRange(double amount) {
        if (amount < 10_000) {
            return "<10k";
        }
        if (amount < 100_000) {
            return "10k-100k";
        }
        if (amount < 1_000_000) {
            return "100k-1M";
        }
        if (amount < 10_000_000) {
            return "1M-10M";
        }
        return ">=10M";
    }

    private Sampling loadSampling(UUID tenantId) {
        try {
            return jdbc.query(
                    """
                    SELECT explain_score_delta, explain_tick_max_gap_ms
                    FROM tenant_settings WHERE tenant_id = ?
                    """,
                    rs -> {
                        if (!rs.next()) {
                            return Sampling.defaults();
                        }
                        return new Sampling(rs.getDouble(1), rs.getInt(2));
                    },
                    tenantId
            );
        } catch (Exception e) {
            return Sampling.defaults();
        }
    }

    private static boolean levelChanged(String prev, InterventionLevel next) {
        if (next == null) {
            return false;
        }
        return prev == null || !prev.equals(next.name());
    }

    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o == null ? Map.of() : o);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private record Sampling(double scoreDelta, int maxGapMs) {
        static Sampling defaults() {
            return new Sampling(0.05, 5000);
        }
    }

    private record SampleState(long tMs, double score, String level) {
    }
}

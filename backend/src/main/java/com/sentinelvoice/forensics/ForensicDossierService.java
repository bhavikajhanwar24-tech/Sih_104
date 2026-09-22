package com.sentinelvoice.forensics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelvoice.audit.AuditEventType;
import com.sentinelvoice.audit.AuditLedgerService;
import com.sentinelvoice.audit.CanonicalJson;
import com.sentinelvoice.audit.TenantChainVerification;
import com.sentinelvoice.forensics.model.EvidenceItem;
import com.sentinelvoice.forensics.model.ForensicDossier;
import com.sentinelvoice.model.AuditBlock;
import com.sentinelvoice.repository.AuditBlockRepository;
import com.sentinelvoice.response.execute.SessionActionRepository;
import com.sentinelvoice.security.TenantContext;
import com.sentinelvoice.telephony.CallSessionRepository;
import com.sentinelvoice.telephony.TelephonyModels;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * F12 — assembles forensic dossiers from DB explainability rows (no audio / no transcript).
 */
@Service
public class ForensicDossierService {

    private static final String NO_AUDIO =
            "No call media or speech content is retained for this session. SentinelVoice stores "
                    + "only derived scores, enums, reason codes, and redacted labels "
                    + "(DPDP §8 data minimisation). Investigators must not request raw media or "
                    + "verbatim speech content from this system — none are stored on disk, in the "
                    + "database, or in the audit ledger.";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final CanonicalJson canonicalJson;
    private final CallSessionRepository callSessionRepository;
    private final SessionActionRepository sessionActionRepository;
    private final AuditLedgerService auditLedgerService;
    private final AuditBlockRepository auditBlockRepository;
    private final DossierPdfRenderer pdfRenderer;

    public ForensicDossierService(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            CanonicalJson canonicalJson,
            CallSessionRepository callSessionRepository,
            SessionActionRepository sessionActionRepository,
            AuditLedgerService auditLedgerService,
            AuditBlockRepository auditBlockRepository,
            DossierPdfRenderer pdfRenderer
    ) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.canonicalJson = canonicalJson;
        this.callSessionRepository = callSessionRepository;
        this.sessionActionRepository = sessionActionRepository;
        this.auditLedgerService = auditLedgerService;
        this.auditBlockRepository = auditBlockRepository;
        this.pdfRenderer = pdfRenderer;
    }

    public ForensicDossier assembleJson(String sessionId, String generatedBy) {
        SealedDossier sealed = assembleAndSeal(sessionId, generatedBy, true);
        return sealed.dossier();
    }

    public byte[] renderPdf(String sessionId, String generatedBy) {
        SealedDossier sealed = assembleAndSeal(sessionId, generatedBy, true);
        return sealed.pdf();
    }

    /**
     * SHA-256 of PDF bytes excluding the {@link DossierPdfRenderer#SHA_MARKER} hex window,
     * so the printed footer digest equals the digest of the delivered file.
     */
    public static String documentSha256(byte[] pdf) {
        if (pdf == null) {
            throw new IllegalArgumentException("pdf is required");
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] marker = DossierPdfRenderer.SHA_MARKER.getBytes(StandardCharsets.US_ASCII);
            int idx = DossierPdfRenderer.indexOf(pdf, marker);
            if (idx < 0) {
                md.update(pdf);
            } else {
                int windowStart = idx + marker.length;
                int windowEnd = windowStart + DossierPdfRenderer.SHA_WINDOW;
                if (windowEnd > pdf.length) {
                    md.update(pdf);
                } else {
                    md.update(pdf, 0, windowStart);
                    md.update(pdf, windowEnd, pdf.length - windowEnd);
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public Optional<ForensicDossierRow> findDossierByContentHash(UUID tenantId, String contentSha256) {
        if (tenantId == null || contentSha256 == null || contentSha256.isBlank()) {
            return Optional.empty();
        }
        List<ForensicDossierRow> rows = jdbc.query(
                """
                SELECT id, tenant_id, session_id, generated_at, generated_by,
                       content_sha256, pdf_sha256, audit_seq_from, audit_seq_to,
                       audit_chain_valid, payload::text AS payload
                FROM forensic_dossiers
                WHERE tenant_id = ? AND content_sha256 = ?
                ORDER BY generated_at DESC
                LIMIT 1
                """,
                (rs, i) -> new ForensicDossierRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("session_id", UUID.class),
                        rs.getTimestamp("generated_at").toInstant(),
                        rs.getString("generated_by"),
                        rs.getString("content_sha256"),
                        rs.getString("pdf_sha256"),
                        rs.getObject("audit_seq_from") == null ? null : rs.getLong("audit_seq_from"),
                        rs.getObject("audit_seq_to") == null ? null : rs.getLong("audit_seq_to"),
                        rs.getObject("audit_chain_valid") == null ? null : rs.getBoolean("audit_chain_valid"),
                        rs.getString("payload")
                ),
                tenantId,
                contentSha256.trim().toLowerCase()
        );
        return rows.stream().findFirst();
    }

    private SealedDossier assembleAndSeal(String sessionKey, String generatedBy, boolean persist) {
        UUID tenantId = TenantContext.require().tenantId();
        ResolvedSession resolved = resolveSession(tenantId, sessionKey);
        TelephonyModels.CallSessionDetail cs = resolved.detail();
        UUID svUuid = cs.svSessionUuid();
        String sessionIdStr = svUuid.toString();

        List<TickRow> ticks = loadTicks(tenantId, svUuid);
        List<ReasonRow> reasons = loadReasons(tenantId, svUuid);
        List<Map<String, Object>> actions =
                sessionActionRepository.listForSession(tenantId, sessionIdStr);

        AuditRange auditRange = computeAuditRange(tenantId, sessionIdStr, cs.id().toString());
        TenantChainVerification chain = auditLedgerService.verifySessionBlocks(
                tenantId, sessionIdStr, cs.id().toString()
        );
        ForensicDossier.AuditChainSection auditSection = buildAuditSection(
                tenantId, auditRange, chain
        );

        long now = Instant.now().toEpochMilli();
        long startMs = cs.startedAt() == null ? 0L : cs.startedAt().toEpochMilli();
        long endMs = cs.endedAt() == null ? now : cs.endedAt().toEpochMilli();
        long durationMs = Math.max(0L, endMs - startMs);

        String callerLabel = displayName(cs.callerName(), cs.callerTitle(), cs.callerNumber());
        String calleeLabel = displayName(cs.calleeName(), cs.calleeTitle(), cs.calleeNumber());

        List<ForensicDossier.RiskSample> riskTimeline = new ArrayList<>();
        List<ForensicDossier.LevelMarker> levelMarkers = new ArrayList<>();
        String prevLevel = null;
        for (TickRow t : ticks) {
            riskTimeline.add(new ForensicDossier.RiskSample(
                    t.tMs(), t.score(), t.score(), t.level()
            ));
            if (prevLevel != null && !prevLevel.equals(t.level())) {
                levelMarkers.add(new ForensicDossier.LevelMarker(
                        t.tMs(), prevLevel, t.level(), "LEVEL_CHANGE"
                ));
            }
            prevLevel = t.level();
        }

        List<EvidenceItem> evidence = new ArrayList<>();
        for (ReasonRow r : reasons) {
            String measured = null;
            String baseline = null;
            if (r.evidence() != null) {
                Object m = r.evidence().get("measured");
                if (m == null) {
                    m = r.evidence().get("measuredValue");
                }
                Object b = r.evidence().get("baseline");
                if (b == null) {
                    b = r.evidence().get("humanBaseline");
                }
                measured = m == null ? null : String.valueOf(m);
                baseline = b == null ? null : String.valueOf(b);
            }
            evidence.add(new EvidenceItem(
                    r.code(),
                    r.severity() == null ? "MEDIUM" : r.severity(),
                    r.family(),
                    r.tMs(),
                    measured,
                    baseline,
                    r.detail()
            ));
        }

        List<ForensicDossier.InterventionEvent> interventionLog = new ArrayList<>();
        for (ForensicDossier.LevelMarker m : levelMarkers) {
            List<String> fired = actions.stream()
                    .filter(a -> m.toLevel() != null && m.toLevel().equals(String.valueOf(a.get("level"))))
                    .map(a -> String.valueOf(a.get("action")))
                    .distinct()
                    .toList();
            interventionLog.add(new ForensicDossier.InterventionEvent(
                    m.tsEpochMs(),
                    m.fromLevel(),
                    m.toLevel(),
                    m.trigger(),
                    fired,
                    startMs > 0 ? Math.max(0L, m.tsEpochMs() - startMs) : null
            ));
        }

        List<ForensicDossier.AnalystAction> analystActions = new ArrayList<>();
        for (Map<String, Object> a : actions) {
            if (!"OVERRIDDEN".equals(String.valueOf(a.get("status")))) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> result = a.get("result") instanceof Map<?, ?> map
                    ? (Map<String, Object>) map
                    : Map.of();
            String started = a.get("startedAt") == null ? null : String.valueOf(a.get("startedAt"));
            long ts = started == null ? now : Instant.parse(started).toEpochMilli();
            analystActions.add(new ForensicDossier.AnalystAction(
                    ts,
                    String.valueOf(result.getOrDefault("overriddenBy", "analyst")),
                    null,
                    String.valueOf(a.get("level")),
                    String.valueOf(result.getOrDefault("overrideReason", ""))
            ));
        }

        boolean noFindings = evidence.isEmpty()
                && riskTimeline.stream().allMatch(s -> s.smoothedRisk() < 0.35)
                && (cs.peakLevel() == null || cs.peakLevel().contains("LEVEL_1"));

        String peakLevel = cs.peakLevel() == null
                ? (riskTimeline.isEmpty() ? "LEVEL_1_SILENT" : riskTimeline.getLast().level())
                : cs.peakLevel();
        double peakScore = cs.peakScore() == null
                ? (riskTimeline.isEmpty() ? 0.0 : riskTimeline.getLast().smoothedRisk())
                : cs.peakScore();

        String summary = noFindings
                ? "NO FINDINGS — retained scores and reason codes do not indicate fraud intervention "
                + "beyond silent monitoring for session " + sessionIdStr + "."
                : "FINDINGS PRESENT — peak level " + peakLevel
                + " (score " + String.format("%.3f", peakScore) + "); "
                + evidence.size() + " reason code(s) and " + interventionLog.size()
                + " level change(s) retained for session " + sessionIdStr + ".";

        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("caller", callerLabel);
        identity.put("callee", calleeLabel);
        identity.put("direction", cs.direction());
        if (cs.callerEmployeeId() != null) {
            identity.put("callerEmployeeId", cs.callerEmployeeId().toString());
        }
        if (cs.calleeEmployeeId() != null) {
            identity.put("calleeEmployeeId", cs.calleeEmployeeId().toString());
        }
        identity.put("finalOutcome", cs.finalOutcome());

        ForensicDossier.CaseHeader header = new ForensicDossier.CaseHeader(
                sessionIdStr,
                callerLabel,
                calleeLabel,
                startMs,
                endMs,
                durationMs,
                "TELEPHONY",
                "ASTERISK_PJSIP",
                null,
                peakLevel,
                peakScore
        );

        ForensicDossier draft = new ForensicDossier(
                ForensicDossier.SCHEMA,
                tenantId.toString(),
                sessionIdStr,
                noFindings,
                summary,
                now,
                generatedBy == null || generatedBy.isBlank() ? "analyst" : generatedBy,
                null,
                null,
                header,
                identity,
                "Directory-resolved CLI labels only; no voiceprint verdict stored in F12 dossier.",
                riskTimeline,
                levelMarkers,
                evidence,
                interventionLog,
                analystActions,
                List.of(),
                auditSection,
                defaultMethodology(),
                NO_AUDIO
        );

        String contentSha = sha256Hex(canonicalJson.serialize(dossierAsMap(draft)));
        byte[] pdf = pdfRenderer.render(draft, contentSha, chain.valid() ? "audit-chain:VALID" : "audit-chain:INVALID");
        String pdfSha = documentSha256(pdf);
        ForensicDossier sealed = draft.withDigests(contentSha, pdfSha);

        if (persist) {
            persistDossier(
                    tenantId,
                    svUuid,
                    sealed.generatedBy(),
                    contentSha,
                    pdfSha,
                    auditRange.fromSeq(),
                    auditRange.toSeq(),
                    chain.valid(),
                    sealed
            );
            Map<String, Object> auditPayload = new LinkedHashMap<>();
            auditPayload.put("sessionId", sessionIdStr);
            auditPayload.put("contentSha256", contentSha);
            auditPayload.put("pdfSha256", pdfSha);
            auditPayload.put("manifestSha256", contentSha);
            auditPayload.put("auditSeqFrom", auditRange.fromSeq());
            auditPayload.put("auditSeqTo", auditRange.toSeq());
            auditPayload.put("auditChainValid", chain.valid());
            String actor = TenantContext.require().userId() == null
                    ? "system"
                    : TenantContext.require().userId().toString();
            auditLedgerService.append(
                    tenantId,
                    sessionIdStr,
                    AuditEventType.DOSSIER_GENERATED,
                    "USER",
                    actor,
                    auditPayload
            );
        }

        return new SealedDossier(sealed, pdf, contentSha, pdfSha, auditRange, chain.valid());
    }

    private ResolvedSession resolveSession(UUID tenantId, String sessionKey) {
        Optional<TelephonyModels.CallSessionDetail> found =
                callSessionRepository.findDetailByIdOrSvSession(tenantId, sessionKey);
        if (found.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "session not found");
        }
        return new ResolvedSession(found.get());
    }

    private List<TickRow> loadTicks(UUID tenantId, UUID sessionId) {
        return jdbc.query(
                """
                SELECT t_ms, score, level, family_scores::text AS family_scores,
                       missing_families, fired_rule_ids, llm_state
                FROM session_ticks
                WHERE tenant_id = ? AND session_id = ?
                ORDER BY t_ms ASC
                """,
                (rs, i) -> new TickRow(
                        rs.getLong("t_ms"),
                        rs.getDouble("score"),
                        rs.getString("level"),
                        parseMap(rs.getString("family_scores")),
                        toStringList(rs.getArray("missing_families")),
                        toStringList(rs.getArray("fired_rule_ids")),
                        rs.getString("llm_state")
                ),
                tenantId, sessionId
        );
    }

    private List<ReasonRow> loadReasons(UUID tenantId, UUID sessionId) {
        return jdbc.query(
                """
                SELECT seq, t_ms, code, title, detail, contribution, family, severity,
                       evidence::text AS evidence, rule_id, policy_version
                FROM session_reasons
                WHERE tenant_id = ? AND session_id = ?
                ORDER BY seq ASC
                """,
                (rs, i) -> new ReasonRow(
                        rs.getInt("seq"),
                        rs.getLong("t_ms"),
                        rs.getString("code"),
                        rs.getString("title"),
                        rs.getString("detail"),
                        rs.getObject("contribution") == null ? null : rs.getDouble("contribution"),
                        rs.getString("family"),
                        rs.getString("severity"),
                        parseMap(rs.getString("evidence")),
                        rs.getString("rule_id"),
                        rs.getObject("policy_version") == null ? null : rs.getInt("policy_version")
                ),
                tenantId, sessionId
        );
    }

    private AuditRange computeAuditRange(UUID tenantId, String... sessionKeys) {
        StringBuilder inList = new StringBuilder();
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        for (int i = 0; i < sessionKeys.length; i++) {
            if (i > 0) {
                inList.append(',');
            }
            inList.append('?');
            args.add(sessionKeys[i]);
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                SELECT MIN(seq) AS from_seq, MAX(seq) AS to_seq, COUNT(*) AS cnt
                FROM audit_blocks
                WHERE tenant_id = ?
                  AND payload->>'sessionId' IN (%s)
                """.formatted(inList),
                args.toArray()
        );
        if (rows.isEmpty() || rows.getFirst().get("cnt") == null
                || ((Number) rows.getFirst().get("cnt")).longValue() == 0L) {
            return new AuditRange(null, null, 0);
        }
        Map<String, Object> row = rows.getFirst();
        Long from = row.get("from_seq") == null ? null : ((Number) row.get("from_seq")).longValue();
        Long to = row.get("to_seq") == null ? null : ((Number) row.get("to_seq")).longValue();
        int cnt = ((Number) row.get("cnt")).intValue();
        return new AuditRange(from, to, cnt);
    }

    private ForensicDossier.AuditChainSection buildAuditSection(
            UUID tenantId,
            AuditRange range,
            TenantChainVerification chain
    ) {
        List<AuditBlock> blocks = auditBlockRepository.findByTenantIdOrderBySeqAsc(tenantId);
        if (range.fromSeq() != null || range.toSeq() != null) {
            long from = range.fromSeq() == null ? 1L : range.fromSeq();
            long to = range.toSeq() == null ? Long.MAX_VALUE : range.toSeq();
            blocks = blocks.stream()
                    .filter(b -> b.getSeq() >= from && b.getSeq() <= to)
                    .toList();
        }
        String genesis = blocks.isEmpty() ? null : blocks.getFirst().getHash();
        String fin = blocks.isEmpty() ? null : blocks.getLast().getHash();
        List<ForensicDossier.AuditBlockSummary> first = summarize(blocks.stream().limit(5).toList());
        List<ForensicDossier.AuditBlockSummary> last = blocks.size() <= 5
                ? List.of()
                : summarize(blocks.subList(Math.max(0, blocks.size() - 5), blocks.size()));
        String detail = chain.valid()
                ? "Chain valid over " + chain.blocksChecked() + " block(s) in session range."
                : "Chain INVALID — first broken seq=" + chain.firstBrokenSeq();
        return new ForensicDossier.AuditChainSection(
                blocks.size(),
                genesis,
                fin,
                chain.valid(),
                detail,
                first,
                last
        );
    }

    private static List<ForensicDossier.AuditBlockSummary> summarize(List<AuditBlock> blocks) {
        List<ForensicDossier.AuditBlockSummary> out = new ArrayList<>();
        for (AuditBlock b : blocks) {
            out.add(new ForensicDossier.AuditBlockSummary(
                    (int) b.getSeq(),
                    b.getCreatedAt() == null ? 0L : b.getCreatedAt().toEpochMilli(),
                    b.getEventType(),
                    b.getHash(),
                    b.getPrevHash()
            ));
        }
        return out;
    }

    private void persistDossier(
            UUID tenantId,
            UUID sessionId,
            String generatedBy,
            String contentSha,
            String pdfSha,
            Long auditFrom,
            Long auditTo,
            boolean chainValid,
            ForensicDossier dossier
    ) {
        jdbc.update(
                """
                INSERT INTO forensic_dossiers (
                  tenant_id, session_id, generated_at, generated_by,
                  content_sha256, pdf_sha256, audit_seq_from, audit_seq_to,
                  audit_chain_valid, payload
                ) VALUES (?, ?, now(), ?, ?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (tenant_id, content_sha256) DO UPDATE SET
                  pdf_sha256 = EXCLUDED.pdf_sha256,
                  audit_seq_from = EXCLUDED.audit_seq_from,
                  audit_seq_to = EXCLUDED.audit_seq_to,
                  audit_chain_valid = EXCLUDED.audit_chain_valid,
                  payload = EXCLUDED.payload,
                  generated_by = EXCLUDED.generated_by,
                  generated_at = now()
                """,
                tenantId,
                sessionId,
                generatedBy,
                contentSha,
                pdfSha,
                auditFrom,
                auditTo,
                chainValid,
                toJson(dossierAsMap(dossier))
        );
    }

    private Map<String, Object> dossierAsMap(ForensicDossier d) {
        return mapper.convertValue(d, new TypeReference<>() {
        });
    }

    private static List<ForensicDossier.MethodologyEntry> defaultMethodology() {
        return List.of(
                new ForensicDossier.MethodologyEntry(
                        "voice", "ECAPA / anti-spoof stack", "tenant-config", "TELEPHONY",
                        "lab EER — see fusion config", "Scores only; no media retained"
                ),
                new ForensicDossier.MethodologyEntry(
                        "channel", "Codec / room consistency heuristics", "tenant-config", "TELEPHONY",
                        "n/a", "Derived labels only"
                ),
                new ForensicDossier.MethodologyEntry(
                        "prosody", "Breath / smoothness detectors", "tenant-config", "TELEPHONY",
                        "n/a", "Feature scores only"
                ),
                new ForensicDossier.MethodologyEntry(
                        "linguistic", "Stage A lexicon + Stage B intent enums", "tenant-config", "TELEPHONY",
                        "n/a", "Enums only — no speech text persisted"
                ),
                new ForensicDossier.MethodologyEntry(
                        "transaction", "ACTIVE policy DSL RuleEngine", "snapshot policy_version", "TELEPHONY",
                        "n/a", "Matched rule IDs only"
                ),
                new ForensicDossier.MethodologyEntry(
                        "relationship", "Directory + edges", "tenant-config", "TELEPHONY",
                        "n/a", "Employee IDs / status enums only"
                )
        );
    }

    private Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o == null ? Map.of() : o);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static List<String> toStringList(java.sql.Array array) {
        if (array == null) {
            return List.of();
        }
        try {
            Object raw = array.getArray();
            if (raw instanceof String[] s) {
                return List.of(s);
            }
            if (raw instanceof Object[] objs) {
                List<String> out = new ArrayList<>();
                for (Object o : objs) {
                    if (o != null) {
                        out.add(String.valueOf(o));
                    }
                }
                return out;
            }
        } catch (Exception ignored) {
            // empty
        }
        return List.of();
    }

    private static String displayName(String fullName, String title, String fallbackNumber) {
        if (fullName != null && !fullName.isBlank()) {
            if (title != null && !title.isBlank()) {
                return fullName + " (" + title + ")";
            }
            return fullName;
        }
        return fallbackNumber == null || fallbackNumber.isBlank() ? "Unknown" : fallbackNumber;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record ForensicDossierRow(
            UUID id,
            UUID tenantId,
            UUID sessionId,
            Instant generatedAt,
            String generatedBy,
            String contentSha256,
            String pdfSha256,
            Long auditSeqFrom,
            Long auditSeqTo,
            Boolean auditChainValid,
            String payloadJson
    ) {
    }

    private record SealedDossier(
            ForensicDossier dossier,
            byte[] pdf,
            String contentSha,
            String pdfSha,
            AuditRange auditRange,
            boolean chainValid
    ) {
    }

    private record ResolvedSession(TelephonyModels.CallSessionDetail detail) {
    }

    private record AuditRange(Long fromSeq, Long toSeq, int blockCount) {
    }

    private record TickRow(
            long tMs,
            double score,
            String level,
            Map<String, Object> familyScores,
            List<String> missingFamilies,
            List<String> firedRuleIds,
            String llmState
    ) {
    }

    private record ReasonRow(
            int seq,
            long tMs,
            String code,
            String title,
            String detail,
            Double contribution,
            String family,
            String severity,
            Map<String, Object> evidence,
            String ruleId,
            Integer policyVersion
    ) {
    }
}

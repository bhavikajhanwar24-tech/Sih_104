package com.sentinelvoice.forensics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sentinelvoice.actuation.CallControlPort;
import com.sentinelvoice.actuation.OobMfaService;
import com.sentinelvoice.audit.AuditEventType;
import com.sentinelvoice.audit.AuditLedgerService;
import com.sentinelvoice.audit.CanonicalJson;
import com.sentinelvoice.audit.ChainVerificationResult;
import com.sentinelvoice.forensics.model.EvidenceItem;
import com.sentinelvoice.forensics.model.ForensicDossier;
import com.sentinelvoice.fusion.ReasonCode;
import com.sentinelvoice.identity.IdentityResolutionService;
import com.sentinelvoice.identity.model.IdentityAssessment;
import com.sentinelvoice.model.AuditBlock;
import com.sentinelvoice.model.CallSession;
import com.sentinelvoice.model.ChannelProfile;
import com.sentinelvoice.model.FeatureFrame;
import com.sentinelvoice.model.TelemetryEntry;
import com.sentinelvoice.model.TelemetryFrame;
import com.sentinelvoice.repository.AuditBlockRepository;
import com.sentinelvoice.service.CallSessionManager;
import com.sentinelvoice.telemetry.TelemetryBroadcaster;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Assembles a court-ready evidence package from Decision Plane retained state only (Context §7.3 / §13).
 * Never reads audio.
 */
@Service
public class ForensicDossierService {

    public static final String NO_AUDIO_STATEMENT =
            "NO AUDIO RECORDING EXISTS for this session. SentinelVoice retains fingerprints "
                    + "(calibrated scores, enums, reason codes, and hash-chained audit blocks) only. "
                    + "Raw PCM is confined to a short Inference Plane ring buffer and is overwritten "
                    + "in place — never persisted — pursuant to data minimisation under the Digital "
                    + "Personal Data Protection Act 2023 §8. Investigators should not request a call "
                    + "recording from this system; none can be produced.";

    private final CallSessionManager callSessionManager;
    private final AuditLedgerService auditLedgerService;
    private final AuditBlockRepository auditBlockRepository;
    private final TelemetryBroadcaster telemetryBroadcaster;
    private final IdentityResolutionService identityResolutionService;
    private final CallControlPort callControlPort;
    private final OobMfaService oobMfaService;
    private final DossierPdfRenderer pdfRenderer;
    private final CanonicalJson canonicalJson;
    private final Clock clock;
    private final ObjectMapper mapper;

    public ForensicDossierService(
            CallSessionManager callSessionManager,
            AuditLedgerService auditLedgerService,
            AuditBlockRepository auditBlockRepository,
            TelemetryBroadcaster telemetryBroadcaster,
            IdentityResolutionService identityResolutionService,
            CallControlPort callControlPort,
            OobMfaService oobMfaService,
            DossierPdfRenderer pdfRenderer,
            CanonicalJson canonicalJson,
            Clock clock
    ) {
        this.callSessionManager = callSessionManager;
        this.auditLedgerService = auditLedgerService;
        this.auditBlockRepository = auditBlockRepository;
        this.telemetryBroadcaster = telemetryBroadcaster;
        this.identityResolutionService = identityResolutionService;
        this.callControlPort = callControlPort;
        this.oobMfaService = oobMfaService;
        this.pdfRenderer = pdfRenderer;
        this.canonicalJson = canonicalJson;
        this.clock = clock;
        this.mapper = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public ForensicDossier assembleJson(String sessionId, String generatedBy) {
        CallSession session = callSessionManager.requireSession(sessionId);
        return assemble(session, generatedBy == null || generatedBy.isBlank() ? "system" : generatedBy.trim());
    }

    /**
     * Renders PDF, chain-anchors SHA-256 of delivered bytes via {@link AuditEventType#DOSSIER_GENERATED}.
     */
    public byte[] renderPdf(String sessionId, String generatedBy) {
        CallSession session = callSessionManager.requireSession(sessionId);
        String actor = generatedBy == null || generatedBy.isBlank() ? "system" : generatedBy.trim();
        ForensicDossier base = assemble(session, actor);

        // Pass 1: content seal (footer has manifest only / empty content hash).
        byte[] unsealed = pdfRenderer.render(base, null, null);
        String contentSha = documentSha256(unsealed);

        // Pass 2: imprint content SHA in footer; delivered artifact is chain-anchored.
        String sealNote = "Audit DOSSIER_GENERATED.pdfSha256 seals these exact bytes";
        byte[] sealed = pdfRenderer.render(base, contentSha, sealNote);
        String pdfSha = documentSha256(sealed);
        ForensicDossier withDigests = base.withDigests(base.manifestSha256(), pdfSha);

        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("action", "DOSSIER_GENERATED");
        auditPayload.put("generatedBy", actor);
        auditPayload.put("generatedAtEpochMs", withDigests.generatedAtEpochMs());
        auditPayload.put("manifestSha256", withDigests.manifestSha256());
        auditPayload.put("contentSha256", contentSha);
        auditPayload.put("pdfSha256", pdfSha);
        auditPayload.put("noFindings", withDigests.noFindings());
        auditPayload.put("pdfBytes", sealed.length);
        auditLedgerService.append(sessionId, AuditEventType.DOSSIER_GENERATED, auditPayload);

        return sealed;
    }

    private ForensicDossier assemble(CallSession session, String generatedBy) {
        String sessionId = session.getSessionId();
        long now = clock.millis();
        List<TelemetryEntry> telemetry = session.getTelemetryHistory().snapshot();
        Optional<TelemetryFrame> latestTf = telemetryBroadcaster.latest(sessionId);
        List<AuditBlock> blocks = auditBlockRepository.findBySessionIdOrderByBlockIndexAsc(sessionId);

        ForensicDossier.CaseHeader header = buildCaseHeader(session, telemetry);
        Map<String, Object> identity = buildIdentity(session, latestTf.orElse(null));
        String mismatch = buildMismatchAnalysis(identity, latestTf.orElse(null));

        List<ForensicDossier.RiskSample> riskTimeline = telemetry.stream()
                .map(e -> new ForensicDossier.RiskSample(
                        e.tsMs(),
                        e.smoothedRisk(),
                        e.instantaneousRisk(),
                        e.level() == null ? null : e.level().name()
                ))
                .toList();

        List<ForensicDossier.LevelMarker> levelMarkers = new ArrayList<>();
        List<ForensicDossier.InterventionEvent> interventionLog = new ArrayList<>();
        List<ForensicDossier.AnalystAction> analystActions = new ArrayList<>();
        List<ForensicDossier.ChallengeEvent> challenges = new ArrayList<>();

        for (AuditBlock block : blocks) {
            Map<String, Object> payload = safePayload(block.getDetails());
            long ts = block.getTsEpochMs();
            long latency = Math.max(0L, ts - session.getCreatedAt().toEpochMilli());
            if (AuditEventType.RISK_LEVEL_CHANGED.name().equals(block.getEventType())) {
                String from = str(payload.get("from"));
                String to = str(payload.get("to"));
                String trigger = str(payload.get("trigger"));
                levelMarkers.add(new ForensicDossier.LevelMarker(ts, from, to, trigger));
                interventionLog.add(new ForensicDossier.InterventionEvent(
                        ts, from, to, trigger, List.of(), latency
                ));
            } else if (AuditEventType.INTERVENTION_ACTION_FIRED.name().equals(block.getEventType())) {
                String action = str(payload.get("action"));
                interventionLog.add(new ForensicDossier.InterventionEvent(
                        ts,
                        str(payload.get("previousLevel")),
                        str(payload.get("level")),
                        "ACTION",
                        action == null ? List.of() : List.of(action),
                        payload.get("latencyMs") instanceof Number n ? n.longValue() : latency
                ));
            } else if (AuditEventType.ANALYST_OVERRIDE.name().equals(block.getEventType())) {
                analystActions.add(new ForensicDossier.AnalystAction(
                        ts,
                        str(payload.get("analystId")),
                        str(payload.get("from")),
                        str(payload.get("to")),
                        str(payload.get("reason"))
                ));
            } else if (AuditEventType.CHALLENGE_ISSUED.name().equals(block.getEventType())
                    || AuditEventType.CHALLENGE_RESULT.name().equals(block.getEventType())) {
                challenges.add(new ForensicDossier.ChallengeEvent(ts, block.getEventType(), payload));
            }
        }

        oobMfaService.current(sessionId).ifPresent(c -> challenges.add(new ForensicDossier.ChallengeEvent(
                c.issuedAtMs(),
                "OOB_MFA_" + c.status().name(),
                Map.of(
                        "expiresAtMs", c.expiresAtMs(),
                        "status", c.status().name(),
                        "mock", true
                )
        )));

        List<EvidenceItem> evidence = buildEvidence(session, latestTf.orElse(null), telemetry);
        ForensicDossier.AuditChainSection auditSection = buildAuditSection(sessionId, blocks);
        List<ForensicDossier.MethodologyEntry> methodology = methodologyFor(session.getChannelProfile());

        boolean noFindings = riskTimeline.isEmpty()
                && evidence.isEmpty()
                && interventionLog.isEmpty()
                && analystActions.isEmpty()
                && session.getSmoothedRisk() < 0.35;

        String summary = noFindings
                ? "NO FINDINGS. Session retained no elevated risk events, reason codes, or intervention actions. "
                + "This dossier is issued to document a clean monitoring outcome."
                : "FINDINGS PRESENT. Aggregated Decision Plane evidence for session " + sessionId
                + " at final level " + session.getCurrentLevel().name()
                + " (smoothed risk " + String.format("%.3f", session.getSmoothedRisk()) + ").";

        ForensicDossier withoutDigest = new ForensicDossier(
                ForensicDossier.SCHEMA,
                sessionId,
                noFindings,
                summary,
                now,
                generatedBy,
                null,
                null,
                header,
                identity,
                mismatch,
                riskTimeline,
                levelMarkers,
                evidence,
                interventionLog,
                analystActions,
                challenges,
                auditSection,
                methodology,
                NO_AUDIO_STATEMENT
        );
        String manifest = sha256Hex(canonicalBytes(withoutDigest));
        return withoutDigest.withDigests(manifest, null);
    }

    @SuppressWarnings("unchecked")
    private byte[] canonicalBytes(ForensicDossier dossier) {
        Map<String, Object> asMap = mapper.convertValue(dossier, Map.class);
        asMap.remove("manifestSha256");
        asMap.remove("pdfSha256");
        String json = canonicalJson.serialize(asMap);
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private ForensicDossier.CaseHeader buildCaseHeader(CallSession session, List<TelemetryEntry> telemetry) {
        long start = session.getCreatedAt().toEpochMilli();
        long end = session.getLastFrameAt() == null ? clock.millis() : session.getLastFrameAt().toEpochMilli();
        if (!telemetry.isEmpty()) {
            end = Math.max(end, telemetry.getLast().tsMs());
        }
        return new ForensicDossier.CaseHeader(
                session.getSessionId(),
                session.getCallerId(),
                session.getCalleeId(),
                start,
                end,
                Math.max(0L, end - start),
                session.getChannelProfile() == null ? null : session.getChannelProfile().name(),
                callControlPort.adapterName(),
                session.getScenarioId(),
                session.getCurrentLevel().name(),
                session.getSmoothedRisk()
        );
    }

    private Map<String, Object> buildIdentity(CallSession session, TelemetryFrame latest) {
        Map<String, Object> identity = new LinkedHashMap<>();
        if (latest != null && latest.identity() != null) {
            TelemetryFrame.Identity id = latest.identity();
            identity.put("cli", id.cli());
            identity.put("cliTrunk", id.cliTrunk());
            identity.put("directoryMatch", id.directoryMatch());
            identity.put("claimedIdentity", id.claimedIdentity());
            identity.put("claimedRole", id.claimedRole());
            identity.put("directoryRecordForClaim", id.directoryRecordForClaim());
            identity.put("cliVsClaimMismatch", id.cliVsClaimMismatch());
            identity.put("voicePassport", id.voicePassport());
            identity.put("presenceConflict", id.presenceConflict());
            return identity;
        }
        FeatureFrame frame = session.getLastFeatureFrame();
        if (frame != null) {
            IdentityAssessment assessment = identityResolutionService.resolve(session, frame);
            identity.put("cli", assessment.cli());
            identity.put("cliTrunk", assessment.cliTrunk());
            identity.put("directoryMatch", assessment.directoryMatch());
            identity.put("claimedIdentity", assessment.claimedIdentity());
            identity.put("claimedRole", assessment.claimedRole());
            identity.put("directoryRecordForClaim", assessment.directoryRecordForClaim());
            identity.put("cliVsClaimMismatch", assessment.cliVsClaimMismatch());
            identity.put("voicePassport", assessment.voicePassport());
            identity.put("presenceConflict", assessment.presenceConflict());
            identity.put("identityRiskScore", assessment.identityRiskScore());
            return identity;
        }
        identity.put("cli", session.getCallerId());
        identity.put("claimedIdentity", null);
        identity.put("note", "Identity block not yet resolved — insufficient FeatureFrames.");
        return identity;
    }

    private String buildMismatchAnalysis(Map<String, Object> identity, TelemetryFrame latest) {
        boolean mismatch = Boolean.TRUE.equals(identity.get("cliVsClaimMismatch"))
                || (latest != null && latest.identity() != null && latest.identity().cliVsClaimMismatch());
        Object passport = identity.get("voicePassport");
        String passportNote = passport == null ? "voice passport unavailable" : "voice passport=" + passport;
        if (mismatch) {
            return "CLI versus claimed-role mismatch is TRUE. Calling-line identity does not corroborate "
                    + "the spoken/claimed executive identity. " + passportNote + ".";
        }
        if (identity.isEmpty() || identity.containsKey("note")) {
            return "Insufficient identity evidence retained to assert a mismatch; treated as inconclusive.";
        }
        return "No CLI/claim mismatch flag. " + passportNote + ".";
    }

    private List<EvidenceItem> buildEvidence(
            CallSession session,
            TelemetryFrame latest,
            List<TelemetryEntry> telemetry
    ) {
        // Preserve first-seen order; keep every distinct firing (code + timestamp bucket).
        Set<String> seen = new LinkedHashSet<>();
        List<EvidenceItem> items = new ArrayList<>();

        for (CallSession.FiredReason fired : session.getFiredReasons()) {
            if (fired == null || fired.reasonCode() == null) {
                continue;
            }
            // Deduplicate identical code at the same second to avoid flooding from 500 ms frames.
            String key = fired.reasonCode() + "@" + (fired.observedAtEpochMs() / 1000L);
            if (!seen.add(key)) {
                continue;
            }
            items.add(new EvidenceItem(
                    fired.reasonCode(),
                    fired.severity() == null ? "INFO" : fired.severity(),
                    fired.family() == null ? familyForCode(fired.reasonCode()) : fired.family(),
                    fired.observedAtEpochMs(),
                    fired.measuredValue(),
                    fired.humanBaseline() == null ? baselineForCode(fired.reasonCode()) : fired.humanBaseline(),
                    fired.narrative()
            ));
        }

        // Fall back / merge latest topReasons when cumulative history is empty (e.g. simulated sessions).
        if (items.isEmpty() && latest != null && latest.topReasons() != null) {
            long observedAt = telemetry.isEmpty()
                    ? session.getCreatedAt().toEpochMilli()
                    : telemetry.getLast().tsMs();
            for (TelemetryFrame.Reason reason : latest.topReasons()) {
                if (reason == null || reason.code() == null || !seen.add(reason.code())) {
                    continue;
                }
                items.add(new EvidenceItem(
                        reason.code(),
                        reason.severity() == null ? "INFO" : reason.severity(),
                        familyForCode(reason.code()),
                        observedAt,
                        extractMeasured(reason.text()),
                        baselineForCode(reason.code()),
                        reason.text()
                ));
            }
        }

        items.sort(Comparator
                .comparingLong(EvidenceItem::observedAtEpochMs)
                .thenComparing(EvidenceItem::severity)
                .thenComparing(EvidenceItem::reasonCode));
        return items;
    }

    private ForensicDossier.AuditChainSection buildAuditSection(String sessionId, List<AuditBlock> blocks) {
        ChainVerificationResult verification = auditLedgerService.verify(sessionId);
        String genesis = blocks.isEmpty() ? null : blocks.getFirst().getCurrentHash();
        String fin = blocks.isEmpty() ? null : blocks.getLast().getCurrentHash();
        // Prefer previousHash of block 0 as the true genesis material pointer when present.
        if (!blocks.isEmpty()) {
            genesis = blocks.getFirst().getPreviousHash();
            fin = blocks.getLast().getCurrentHash();
        }
        String detail = verification.valid()
                ? "Chain recomputation matched for all " + verification.blockCount() + " blocks."
                : verification.blockCount() == 0
                ? "No audit blocks yet — empty chain."
                : "Broken at index " + verification.brokenAtIndex()
                + " expected=" + verification.expectedHash()
                + " actual=" + verification.actualHash();

        List<ForensicDossier.AuditBlockSummary> summaries = blocks.stream()
                .map(b -> new ForensicDossier.AuditBlockSummary(
                        b.getBlockIndex(),
                        b.getTsEpochMs(),
                        b.getEventType(),
                        b.getCurrentHash(),
                        b.getPreviousHash()
                ))
                .toList();
        List<ForensicDossier.AuditBlockSummary> first = summaries.stream().limit(5).toList();
        List<ForensicDossier.AuditBlockSummary> last = summaries.size() <= 5
                ? summaries
                : summaries.subList(Math.max(0, summaries.size() - 5), summaries.size());

        return new ForensicDossier.AuditChainSection(
                blocks.size(),
                genesis,
                fin,
                verification.valid(),
                detail,
                first,
                last
        );
    }

    static List<ForensicDossier.MethodologyEntry> methodologyFor(ChannelProfile profile) {
        String channel = profile == null ? "WEBRTC_WIDEBAND" : profile.name();
        boolean narrow = profile == ChannelProfile.PSTN_NARROWBAND;
        return List.of(
                new ForensicDossier.MethodologyEntry(
                        "voice",
                        "AASIST-LFCC anti-spoof (lab)",
                        "aasist-lfcc-v3-codecaug",
                        channel,
                        narrow ? "≈22–28% EER (G.711 / In-the-Wild)" : "≈4–8% EER (ASVspoof 2019 LA in-domain)",
                        "Acoustic score is never sufficient alone; corroboration gate required."
                ),
                new ForensicDossier.MethodologyEntry(
                        "channel",
                        "RIR / double-compression forensics",
                        "sv-channel-forensics-1",
                        channel,
                        narrow ? "≈18% EER proxy (codec-conditioned)" : "≈12% EER proxy (wideband)",
                        "Missing room impulse and double-compression used as injection evidence."
                ),
                new ForensicDossier.MethodologyEntry(
                        "prosody",
                        "F0 / jitter / breath heuristics",
                        "sv-prosody-1",
                        channel,
                        "operating characteristic reported as FPR@TPR=0.90 ≈ 0.15",
                        "Breath absence gated until ≥15 s cumulative speech."
                ),
                new ForensicDossier.MethodologyEntry(
                        "linguistic",
                        "Urgency / secrecy / authority intent",
                        "sv-linguistic-1",
                        channel,
                        "N/A (not a biometric EER; calibrated intent scores)",
                        "Staleness down-weight via linguistic.ageMs."
                ),
                new ForensicDossier.MethodologyEntry(
                        "transaction",
                        "Verbal authority / velocity / beneficiary policy",
                        "sv-txn-policy-1",
                        channel,
                        "N/A (deterministic policy)",
                        "Policy breach scored near 1.0 when verbal limit exceeded."
                ),
                new ForensicDossier.MethodologyEntry(
                        "relationship",
                        "Directory + hierarchy + first-contact graph",
                        "sv-relationship-1",
                        channel,
                        "N/A (graph features)",
                        "CLI/claim mismatch elevates relationship family."
                ),
                new ForensicDossier.MethodologyEntry(
                        "fusion",
                        "Weighted sum + asymmetric EMA + corroboration",
                        "sv-fusion-9.1",
                        channel,
                        "system-level: escalate only with ≥2 independent families",
                        "Prevents single-family false positives from interrupting customers."
                )
        );
    }

    private Map<String, Object> safePayload(String details) {
        try {
            return canonicalJson.deserialize(details == null ? "{}" : details);
        } catch (Exception ex) {
            return Map.of("raw", details == null ? "" : details);
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String familyForCode(String code) {
        try {
            return ReasonCode.valueOf(code).family().name();
        } catch (Exception ex) {
            return "UNKNOWN";
        }
    }

    private static String baselineForCode(String code) {
        return switch (code == null ? "" : code) {
            case "VOICEPRINT_FAIL" -> "match cosine ≥ 0.70";
            case "NO_BREATH" -> "8–20 breaths/min";
            case "OVERSMOOTH_PROSODY" -> "jitter 0.5–1.5%";
            case "NO_ROOM_ACOUSTICS" -> "T60 ≳ 30 ms";
            case "DOUBLE_COMPRESSION" -> "score < 0.55";
            case "SYNTHETIC_ARTIFACTS" -> "spoofProbability < 0.60";
            case "POLICY_VIOLATION" -> "within verbalAuthorityLimit";
            default -> "see methodology appendix";
        };
    }

    private static String extractMeasured(String text) {
        if (text == null || text.isBlank()) {
            return "—";
        }
        return text.length() <= 80 ? text : text.substring(0, 77) + "...";
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            byte[] dig = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(dig);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 required", e);
        }
    }

    /**
     * Document digest for dossier PDFs: SHA-256 of all bytes except the 64-char seal window
     * following {@link DossierPdfRenderer#SHA_MARKER}. Equals the hex printed in the page-1 footer
     * after stamping, and is what {@code DOSSIER_GENERATED.pdfSha256} / {@code X-Document-SHA256} store.
     */
    public static String documentSha256(byte[] pdf) {
        if (pdf == null || pdf.length == 0) {
            return sha256Hex(new byte[0]);
        }
        byte[] marker = DossierPdfRenderer.SHA_MARKER.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int idx = DossierPdfRenderer.indexOf(pdf, marker);
        if (idx < 0) {
            return sha256Hex(pdf);
        }
        int start = idx + marker.length;
        int end = start + DossierPdfRenderer.SHA_WINDOW;
        if (end > pdf.length) {
            return sha256Hex(pdf);
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(pdf, 0, start);
            md.update(pdf, end, pdf.length - end);
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 required", e);
        }
    }
}

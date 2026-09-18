package com.sentinelvoice.forensics;

import com.sentinelvoice.audit.AuditEventType;
import com.sentinelvoice.audit.CanonicalJson;
import com.sentinelvoice.forensics.model.ForensicDossier;
import com.sentinelvoice.model.AuditBlock;
import com.sentinelvoice.model.CallSession;
import com.sentinelvoice.model.ChannelProfile;
import com.sentinelvoice.model.InterventionLevel;
import com.sentinelvoice.model.SessionStartRequest;
import com.sentinelvoice.model.TelemetryEntry;
import com.sentinelvoice.repository.AuditBlockRepository;
import com.sentinelvoice.service.CallSessionManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DossierTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CallSessionManager callSessionManager;

    @Autowired
    private ForensicDossierService forensicDossierService;

    @Autowired
    private AuditBlockRepository auditBlockRepository;

    @Autowired
    private CanonicalJson canonicalJson;

    @Test
    void cleanSessionProducesNoFindingsDossierJsonAndPdf() throws Exception {
        String sessionId = "dossier-clean-" + System.nanoTime();
        callSessionManager.createSession(new SessionStartRequest(
                "sentinelvoice.SessionStartRequest/1",
                sessionId,
                "cli-clean",
                "desk",
                ChannelProfile.WEBRTC_WIDEBAND,
                "legit-cfo"
        ));

        ForensicDossier dossier = forensicDossierService.assembleJson(sessionId, "qa-analyst");
        assertThat(dossier.noFindings()).isTrue();
        assertThat(dossier.summary()).containsIgnoringCase("NO FINDINGS");
        assertThat(dossier.noAudioStatement()).contains("NO AUDIO RECORDING EXISTS");
        assertThat(dossier.methodology()).isNotEmpty();
        assertThat(dossier.manifestSha256()).hasSize(64);

        mockMvc.perform(get("/api/v1/forensics/{sessionId}/dossier", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schema").value(ForensicDossier.SCHEMA))
                .andExpect(jsonPath("$.noFindings").value(true))
                .andExpect(jsonPath("$.methodology").isArray());

        MvcResult pdfResult = mockMvc.perform(get("/api/v1/forensics/{sessionId}/dossier.pdf", sessionId)
                        .param("generatedBy", "qa-analyst"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString(sessionId)))
                .andReturn();

        byte[] pdf = pdfResult.getResponse().getContentAsByteArray();
        assertThat(pdf.length).isGreaterThan(500);
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");

        String recomputed = ForensicDossierService.documentSha256(pdf);
        List<AuditBlock> blocks = auditBlockRepository.findBySessionIdOrderByBlockIndexAsc(sessionId);
        AuditBlock dossierBlock = blocks.stream()
                .filter(b -> AuditEventType.DOSSIER_GENERATED.name().equals(b.getEventType()))
                .reduce((a, b) -> b)
                .orElseThrow();
        Map<String, Object> payload = canonicalJson.deserialize(dossierBlock.getDetails());
        assertThat(payload.get("pdfSha256")).isEqualTo(recomputed);
        assertThat(payload.get("manifestSha256")).isNotNull();
        // Footer carries the sealed digest (window-exclusion) as literal ASCII.
        assertThat(new String(pdf, java.nio.charset.StandardCharsets.ISO_8859_1))
                .contains(DossierPdfRenderer.SHA_MARKER + recomputed);
        assertThat(new String(pdf, java.nio.charset.StandardCharsets.ISO_8859_1))
                .contains("SENTINELVOICE · Forensic Evidence Dossier");
    }

    @Test
    void sessionWithRiskEventsIncludesSectionsAndLegibleChartBytes() {
        String sessionId = "dossier-risk-" + System.nanoTime();
        var session = callSessionManager.createSession(new SessionStartRequest(
                "sentinelvoice.SessionStartRequest/1",
                sessionId,
                "+91-22-4000-9999",
                "EMP-30020",
                ChannelProfile.PSTN_NARROWBAND,
                "cfo-wire-inr"
        ));

        long t0 = session.getCreatedAt().toEpochMilli();
        callSessionManager.recordTelemetry(sessionId, new TelemetryEntry(
                1, t0 + 500, 0.22, 0.22, InterventionLevel.LEVEL_1_SILENT, Map.of("voice", 0.2)
        ));
        callSessionManager.recordTelemetry(sessionId, new TelemetryEntry(
                2, t0 + 1500, 0.55, 0.48, InterventionLevel.LEVEL_2_SOFT_NUDGE, Map.of("voice", 0.5)
        ));
        callSessionManager.recordTelemetry(sessionId, new TelemetryEntry(
                3, t0 + 3000, 0.82, 0.76, InterventionLevel.LEVEL_4_AUTO_HOLD, Map.of("voice", 0.7, "linguistic", 0.9)
        ));

        ForensicDossier dossier = forensicDossierService.assembleJson(sessionId, "investigator-1");
        assertThat(dossier.noFindings()).isFalse();
        assertThat(dossier.riskTimeline()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(dossier.caseHeader().channelProfile()).isEqualTo("PSTN_NARROWBAND");
        assertThat(dossier.methodology()).anyMatch(m -> m.evidenceFamily().equals("voice"));

        byte[] pdf = forensicDossierService.renderPdf(sessionId, "investigator-1");
        assertThat(pdf[0]).isEqualTo((byte) '%');
        // Risk chart PNG is embedded — PDF should be substantially larger than a no-findings cover.
        assertThat(pdf.length).isGreaterThan(8_000);

        String recomputed = ForensicDossierService.documentSha256(pdf);
        List<AuditBlock> blocks = auditBlockRepository.findBySessionIdOrderByBlockIndexAsc(sessionId);
        assertThat(blocks).anyMatch(b -> AuditEventType.DOSSIER_GENERATED.name().equals(b.getEventType()));
        AuditBlock last = blocks.stream()
                .filter(b -> AuditEventType.DOSSIER_GENERATED.name().equals(b.getEventType()))
                .reduce((a, b) -> b)
                .orElseThrow();
        assertThat(canonicalJson.deserialize(last.getDetails()).get("pdfSha256")).isEqualTo(recomputed);
    }

    @Test
    void evidenceTableIncludesEveryFiredReasonNotJustLatest() {
        String sessionId = "dossier-evidence-" + System.nanoTime();
        CallSession session = callSessionManager.createSession(new SessionStartRequest(
                "sentinelvoice.SessionStartRequest/1",
                sessionId,
                "cli-ev",
                "desk",
                ChannelProfile.WEBRTC_WIDEBAND,
                null
        ));
        long t0 = session.getCreatedAt().toEpochMilli();
        session.recordFiredReasons(t0 + 1000, List.of(
                new CallSession.FiredReason(
                        "SYNTHETIC_ARTIFACTS", "CRITICAL", "VOICE", t0 + 1000,
                        "spoof=0.91", "spoofProbability < 0.60", "Synthetic artefacts elevated"
                )
        ));
        session.recordFiredReasons(t0 + 5000, List.of(
                new CallSession.FiredReason(
                        "POLICY_VIOLATION", "HIGH", "TRANSACTION", t0 + 5000,
                        "amount=5e6", "within verbalAuthorityLimit", "Verbal authority exceeded"
                )
        ));
        // Same code one second later should still appear (different second bucket).
        session.recordFiredReasons(t0 + 9000, List.of(
                new CallSession.FiredReason(
                        "SYNTHETIC_ARTIFACTS", "CRITICAL", "VOICE", t0 + 9000,
                        "spoof=0.93", "spoofProbability < 0.60", "Still elevated"
                )
        ));

        ForensicDossier dossier = forensicDossierService.assembleJson(sessionId, "investigator");
        assertThat(dossier.evidence()).hasSize(3);
        assertThat(dossier.evidence()).extracting(e -> e.reasonCode())
                .containsExactly("SYNTHETIC_ARTIFACTS", "POLICY_VIOLATION", "SYNTHETIC_ARTIFACTS");
    }

    @Test
    void unknownSessionReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/forensics/does-not-exist/dossier"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/forensics/does-not-exist/dossier.pdf"))
                .andExpect(status().isNotFound());
    }
}

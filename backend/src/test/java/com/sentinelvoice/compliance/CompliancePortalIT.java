package com.sentinelvoice.compliance;

import com.sentinelvoice.audit.AuditEventType;
import com.sentinelvoice.audit.AuditLedgerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Compliance portal APIs — retention counters are real queries; fairness loads P12 output;
 * verify flips VALID → INVALID after an H2 tamper of {@code audit_blocks.details}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CompliancePortalIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ComplianceMetricsService metricsService;

    @Autowired
    private AuditLedgerService auditLedgerService;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Test
    void retentionCountersComeFromQueries_rawAudioIsZero() throws Exception {
        Map<String, Object> dash = metricsService.retentionDashboard();
        assertThat(dash.get("rawAudioBytesPersisted")).isEqualTo(0L);
        assertThat(dash.get("embeddingsStored")).isInstanceOf(Number.class);
        assertThat(dash.get("erasuresPerformed")).isInstanceOf(Number.class);
        assertThat(dash.get("tombstonesRecorded")).isInstanceOf(Number.class);
        assertThat(dash.get("nextScheduledPurge")).isNotNull();

        mockMvc.perform(get("/api/v1/compliance/retention"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rawAudioBytesPersisted").value(0))
                .andExpect(jsonPath("$.telemetry.ttlDays").value(90))
                .andExpect(jsonPath("$.rawAudioEnforcingPath").isNotEmpty());
    }

    @Test
    void fairnessLoadsP12ResultsOrExplicitNotRunState() throws Exception {
        Map<String, Object> report = metricsService.fairnessReport();
        assertThat(report.get("status")).isIn("ok", "EVALUATION_NOT_RUN", "evaluation_not_yet_run", "unreadable");

        if ("ok".equals(report.get("status"))) {
            @SuppressWarnings("unchecked")
            Map<String, Object> results = (Map<String, Object>) report.get("results");
            assertThat(results).containsKeys("byLanguageGroup", "byGender", "byChannelProfile", "disparityNotes");
            mockMvc.perform(get("/api/v1/compliance/fairness"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ok"))
                    .andExpect(jsonPath("$.results.byLanguageGroup").isArray())
                    .andExpect(jsonPath("$.results.disparityNotes").isNotEmpty());
        } else {
            mockMvc.perform(get("/api/v1/compliance/fairness"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(report.get("status").toString()))
                    .andExpect(jsonPath("$.results").value(nullValue()));
        }
    }

    @Test
    void verifyReportsValidThenInvalidAfterH2Tamper() throws Exception {
        String sessionId = "compliance-tamper-demo";
        auditLedgerService.append(sessionId, AuditEventType.SESSION_OPENED, Map.of(
                "smoothedRisk", 0.1,
                "level", "LEVEL_1_SILENT"
        ));
        auditLedgerService.append(sessionId, AuditEventType.FEATURE_FRAME_SCORED, Map.of(
                "smoothedRisk", 0.4,
                "level", "LEVEL_2_SOFT_NUDGE"
        ));

        mockMvc.perform(get("/api/v1/compliance/verify/" + sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.blockCount").value(2));

        // Live tamper demo: mutate payload in H2 without rehashing.
        int updated = jdbcTemplate.update(
                "UPDATE audit_blocks SET details = ? WHERE session_id = ? AND block_index = 1",
                "{\"smoothedRisk\":0.99,\"level\":\"LEVEL_4_AUTO_HOLD\",\"tampered\":true}",
                sessionId
        );
        assertThat(updated).isEqualTo(1);

        mockMvc.perform(get("/api/v1/compliance/verify/" + sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.brokenAtIndex").value(1));
    }

    @Test
    void consentRegisterEndpointListsRecords() throws Exception {
        mockMvc.perform(get("/api/v1/compliance/consent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consents").isArray())
                .andExpect(jsonPath("$.dpdpRefs").isArray());
    }
}

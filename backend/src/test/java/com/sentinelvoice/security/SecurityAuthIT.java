package com.sentinelvoice.security;

import com.sentinelvoice.audit.AuditEventType;
import com.sentinelvoice.audit.AuditLedgerService;
import com.sentinelvoice.audit.ChainVerificationResult;
import com.sentinelvoice.transcript.BreakGlassTranscriptService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AuthZ matrix + two-principal break-glass (P13). Demo users: analyst/supervisor/compliance/admin,
 * password {@code password} (registered in {@code SecurityConfig}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("demo-mem")
class SecurityAuthIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BreakGlassTranscriptService breakGlassTranscriptService;

    @Autowired
    private AuditLedgerService auditLedgerService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void unauthenticatedApiReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/compliance/retention"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void analystCannotReadComplianceFairness_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/compliance/fairness")
                        .with(httpBasic("analyst", "password")))
                .andExpect(status().isForbidden());
    }

    @Test
    void complianceCanReadFairness_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/compliance/fairness")
                        .with(httpBasic("compliance", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").exists());
    }

    @Test
    void healthRemainsPermitAll() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void breakGlassRequiresDistinctSupervisor() throws Exception {
        String sessionId = "bg-" + UUID.randomUUID();
        breakGlassTranscriptService.rememberSnippet(sessionId, "transfer 123456789012 to account", 12_000L);

        mockMvc.perform(post("/api/v1/session/" + sessionId + "/transcript/request")
                        .with(httpBasic("analyst", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"justification\":\"Suspected deepfake social-engineering attempt\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"));

        mockMvc.perform(post("/api/v1/session/" + sessionId + "/transcript/approve")
                        .with(httpBasic("analyst", "password")))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/session/" + sessionId + "/transcript/approve")
                        .with(httpBasic("supervisor", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.redactedText").isNotEmpty());

        mockMvc.perform(get("/api/v1/session/" + sessionId + "/transcript")
                        .with(httpBasic("analyst", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.redactedText").value(org.hamcrest.Matchers.containsString("REDACTED")));
    }

    @Test
    void tamperVerifyReturnsBrokenAtIndex() {
        String sessionId = "tamper-auth-" + UUID.randomUUID();
        auditLedgerService.append(sessionId, AuditEventType.SESSION_OPENED, Map.of("n", 0));
        auditLedgerService.append(sessionId, AuditEventType.FEATURE_FRAME_SCORED, Map.of("seq", 1));
        assertThat(auditLedgerService.verify(sessionId).valid()).isTrue();

        int updated = jdbcTemplate.update(
                "UPDATE audit_blocks SET details = ? WHERE session_id = ? AND block_index = 1",
                "{\"seq\":1,\"tampered\":true}",
                sessionId
        );
        assertThat(updated).isEqualTo(1);

        ChainVerificationResult broken = auditLedgerService.verify(sessionId);
        assertThat(broken.valid()).isFalse();
        assertThat(broken.brokenAtIndex()).isEqualTo(1);
    }
}

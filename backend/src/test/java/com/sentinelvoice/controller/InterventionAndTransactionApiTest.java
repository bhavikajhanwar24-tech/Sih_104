package com.sentinelvoice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelvoice.audit.AuditEventType;
import com.sentinelvoice.model.ChannelProfile;
import com.sentinelvoice.model.InterventionLevel;
import com.sentinelvoice.model.SessionStartRequest;
import com.sentinelvoice.repository.AuditBlockRepository;
import com.sentinelvoice.service.CallSessionManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class InterventionAndTransactionApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CallSessionManager callSessionManager;

    @Autowired
    private AuditBlockRepository auditBlockRepository;

    @Test
    void approveReturns423WhenLevelAtLeastL3() throws Exception {
        String sessionId = "api-lock-" + UUID.randomUUID();
        callSessionManager.createSession(new SessionStartRequest(
                "sentinelvoice.SessionStartRequest/1",
                sessionId,
                "cli",
                "desk",
                ChannelProfile.WEBRTC_WIDEBAND,
                null
        ));

        mockMvc.perform(post("/api/v1/transaction/{id}/approve", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actorId\":\"curl-demo\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/intervention/{id}/override", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "analystId", "analyst-1",
                                "reason", "Force L3 for curl lock demonstration",
                                "targetLevel", InterventionLevel.LEVEL_3_STEP_UP_MFA.name()
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetLevel").value("LEVEL_3_STEP_UP_MFA"));

        mockMvc.perform(post("/api/v1/transaction/{id}/approve", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actorId\":\"curl-demo\"}"))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.status").value("LOCKED"))
                .andExpect(jsonPath("$.reason").value(org.hamcrest.Matchers.containsString("SentinelVoice")));

        boolean overrideSeen = false;
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            overrideSeen = auditBlockRepository.findBySessionIdOrderByBlockIndexAsc(sessionId).stream()
                    .anyMatch(b -> AuditEventType.ANALYST_OVERRIDE.name().equals(b.getEventType()));
            if (overrideSeen) {
                break;
            }
            Thread.sleep(50L);
        }
        assertThat(overrideSeen).isTrue();
    }

    @Test
    void overrideWithBlankReasonRejected() throws Exception {
        String sessionId = "api-ovr-" + UUID.randomUUID();
        callSessionManager.createSession(new SessionStartRequest(
                "sentinelvoice.SessionStartRequest/1",
                sessionId,
                "cli",
                "desk",
                ChannelProfile.WEBRTC_WIDEBAND,
                null
        ));

        mockMvc.perform(post("/api/v1/intervention/{id}/override", sessionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "analystId", "analyst-1",
                                "reason", "short",
                                "targetLevel", "LEVEL_2_SOFT_NUDGE"
                        ))))
                .andExpect(status().isBadRequest());
    }
}

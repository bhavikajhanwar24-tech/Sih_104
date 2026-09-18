package com.sentinelvoice.context;

import com.sentinelvoice.context.model.CorrelationResult;
import com.sentinelvoice.model.ChannelProfile;
import com.sentinelvoice.model.SessionStartRequest;
import com.sentinelvoice.service.CallSessionManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Acceptance for P13.1 / Context §7.3:
 * Scenario 2 → two correlated precursors; no-precursor scenario → empty; blend raises RELATIONSHIP.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CrossChannelCorrelationIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CallSessionManager callSessionManager;

    @Autowired
    private CrossChannelCorrelationService correlationService;

    @Test
    void scenario2ShowsTwoCorrelatedPrecursorsAndRaisesRelationship() throws Exception {
        var session = callSessionManager.createSession(new SessionStartRequest(
                "sentinelvoice.SessionStartRequest/1",
                "xc-scen2-" + System.nanoTime(),
                "+91-98XXX-44120",
                "+91-22-6655-5040",
                ChannelProfile.PSTN_NARROWBAND,
                "deepfake-ceo-wire"
        ));

        mockMvc.perform(get("/api/v1/cross-channel")
                        .param("sessionId", session.getSessionId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetEmployeeId").value("EMP-50040"))
                .andExpect(jsonPath("$.matchingCampaign").value(true))
                .andExpect(jsonPath("$.events.length()").value(2))
                .andExpect(jsonPath("$.events[0].channel").value("EMAIL"))
                .andExpect(jsonPath("$.events[1].channel").value("SMS"))
                .andExpect(jsonPath("$.correlationScore").value(org.hamcrest.Matchers.greaterThan(0.7)));

        CorrelationResult result = correlationService.correlateSession(session.getSessionId(), 48);
        double graphOnly = 0.55;
        double blended = correlationService.blendRelationshipScore(graphOnly, result);
        assertThat(blended)
                .as("cross-channel sub-component of RELATIONSHIP must raise the family score")
                .isGreaterThan(graphOnly);
    }

    @Test
    void scenarioWithoutPrecursorsReturnsEmptyNotFabricated() throws Exception {
        var session = callSessionManager.createSession(new SessionStartRequest(
                "sentinelvoice.SessionStartRequest/1",
                "xc-empty-" + System.nanoTime(),
                "+91-22-6655-0100",
                "+91-22-6655-2010",
                ChannelProfile.WEBRTC_WIDEBAND,
                "legit-cfo"
        ));

        mockMvc.perform(get("/api/v1/cross-channel")
                        .param("sessionId", session.getSessionId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events").isArray())
                .andExpect(jsonPath("$.events.length()").value(0))
                .andExpect(jsonPath("$.matchingCampaign").value(false))
                .andExpect(jsonPath("$.correlationScore").value(0.0));
    }

    @Test
    void ingestAcceptsSiemStyleEvent() throws Exception {
        long occurred = System.currentTimeMillis() - 2 * 3600_000L;
        String body = """
                {
                  "schema": "sentinelvoice.CrossChannelEvent/1",
                  "channel": "AUTH",
                  "targetEmployeeId": "EMP-50040",
                  "occurredAtEpochMs": %d,
                  "severity": "MEDIUM",
                  "indicator": "failed MFA from 103.x.x.x",
                  "campaignId": "BEC-CFO-2026-09",
                  "description": "SIEM feed: failed step-up MFA correlated to BEC campaign"
                }
                """.formatted(occurred);

        mockMvc.perform(post("/api/v1/cross-channel/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.channel").value("AUTH"))
                .andExpect(jsonPath("$.campaignId").value("BEC-CFO-2026-09"));
    }
}

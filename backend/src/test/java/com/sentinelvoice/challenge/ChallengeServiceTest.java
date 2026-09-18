package com.sentinelvoice.challenge;

import com.sentinelvoice.audit.AuditEventType;
import com.sentinelvoice.model.AuditBlock;
import com.sentinelvoice.model.ChannelProfile;
import com.sentinelvoice.model.SessionStartRequest;
import com.sentinelvoice.repository.AuditBlockRepository;
import com.sentinelvoice.service.CallSessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ChallengeServiceTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ChallengeService challengeService;

    @Autowired
    private ChallengeEvaluator evaluator;

    @Autowired
    private CallSessionManager callSessionManager;

    @Autowired
    private AuditBlockRepository auditBlockRepository;

    private String sessionId;

    @BeforeEach
    void setUp() {
        sessionId = "chal-" + System.nanoTime();
        callSessionManager.createSession(new SessionStartRequest(
                "sentinelvoice.SessionStartRequest/1",
                sessionId,
                "cli-chal",
                "desk",
                ChannelProfile.WEBRTC_WIDEBAND,
                null
        ));
    }

    @Test
    void humanResponsePassesAllThreeSignals() throws Exception {
        MvcResult issued = mockMvc.perform(post("/api/v1/challenge/issue")
                        .param("sessionId", sessionId)
                        .param("language", "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schema").value("sentinelvoice.ChallengeIssued/1"))
                .andExpect(jsonPath("$.nonce").isString())
                .andExpect(jsonPath("$.phrase").isString())
                .andReturn();

        String nonce = read(issued, "nonce");
        String phrase = read(issued, "phrase");

        mockMvc.perform(post("/api/v1/challenge/{nonce}/displayed", nonce))
                .andExpect(status().isOk());

        // Simulate fast human onset (~900 ms) via server mono clock — not client issuedAt.
        long displayed = System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(900);
        challengeService.markDisplayedAt(nonce, displayed);
        challengeService.speechOnsetAt(nonce, displayed + TimeUnit.MILLISECONDS.toNanos(900));

        mockMvc.perform(post("/api/v1/challenge/{nonce}/evidence", nonce)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"transcript":"uh, %s","acousticCosine":0.88}
                                """.formatted(phrase)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("PASS"))
                .andExpect(jsonPath("$.latencyPass").value(true))
                .andExpect(jsonPath("$.contentPass").value(true))
                .andExpect(jsonPath("$.acousticPass").value(true));

        assertAuditEvents();
    }

    @Test
    void delayPast3_5sProducesFailLatency() throws Exception {
        String nonce = issueAndDisplay();
        long displayed = System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(4200);
        challengeService.markDisplayedAt(nonce, displayed);
        challengeService.speechOnsetAt(nonce, displayed + TimeUnit.MILLISECONDS.toNanos(4200));

        mockMvc.perform(post("/api/v1/challenge/{nonce}/evidence", nonce)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"transcript":"Amber Falcon 72","acousticCosine":0.9}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("FAIL_LATENCY"));
    }

    @Test
    void differentPhraseProducesFailContent() throws Exception {
        String nonce = issueAndDisplay();
        long displayed = System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(700);
        challengeService.markDisplayedAt(nonce, displayed);
        challengeService.speechOnsetAt(nonce, displayed + TimeUnit.MILLISECONDS.toNanos(700));

        mockMvc.perform(post("/api/v1/challenge/{nonce}/evidence", nonce)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"transcript":"totally unrelated purple banana ninety","acousticCosine":0.91}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("FAIL_CONTENT"));
    }

    @Test
    void forgedIssuedAtHasNoEffectOnMeasuredLatency() throws Exception {
        String nonce = issueAndDisplay();
        // Server truth: 4.2s onset (should FAIL_LATENCY)
        long displayed = System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(4200);
        challengeService.markDisplayedAt(nonce, displayed);
        challengeService.speechOnsetAt(nonce, displayed + TimeUnit.MILLISECONDS.toNanos(4200));

        // Client forges issuedAt / respondedAt claiming a 200 ms response — must be ignored.
        mockMvc.perform(post("/api/v1/challenge/{nonce}/evidence", nonce)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "transcript":"Amber Falcon 72",
                                  "acousticCosine":0.9,
                                  "issuedAt": 1,
                                  "respondedAt": 201,
                                  "issuedAtEpochMs": 1
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verdict").value("FAIL_LATENCY"))
                .andExpect(jsonPath("$.latencyMs").value(org.hamcrest.Matchers.greaterThan(3500)));
    }

    @Test
    void fuzzyContentAcceptsSpokenDigitsAndFillers() {
        double overlap = evaluator.contentOverlap("Amber Falcon 72", "uh amber falcon seven two");
        assertThat(overlap).isGreaterThanOrEqualTo(0.6);
    }

    @Test
    void phraseGeneratorSpaceIsLargeAndNoSessionRepeat() {
        ChallengePhraseGenerator gen = new ChallengePhraseGenerator();
        assertThat(ChallengePhraseGenerator.spaceSize()).isGreaterThanOrEqualTo(40 * 40 * 90);
        var used = com.sentinelvoice.challenge.model.ActiveChallenge.newPhraseHistory();
        for (int i = 0; i < 80; i++) {
            String p = gen.generate(com.sentinelvoice.challenge.model.ActiveChallenge.Language.EN, used);
            assertThat(p.split(" ")).hasSize(3);
        }
        assertThat(used).hasSize(80);
    }

    private String issueAndDisplay() throws Exception {
        MvcResult issued = mockMvc.perform(post("/api/v1/challenge/issue")
                        .param("sessionId", sessionId))
                .andExpect(status().isOk())
                .andReturn();
        String nonce = read(issued, "nonce");
        mockMvc.perform(post("/api/v1/challenge/{nonce}/displayed", nonce))
                .andExpect(status().isOk());
        return nonce;
    }

    private void assertAuditEvents() {
        List<AuditBlock> blocks = auditBlockRepository.findBySessionIdOrderByBlockIndexAsc(sessionId);
        assertThat(blocks).anyMatch(b -> AuditEventType.CHALLENGE_ISSUED.name().equals(b.getEventType()));
        assertThat(blocks).anyMatch(b -> AuditEventType.CHALLENGE_RESULT.name().equals(b.getEventType()));
    }

    @SuppressWarnings("unchecked")
    private static String read(MvcResult result, String field) throws Exception {
        Map<String, Object> body = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(result.getResponse().getContentAsString(), Map.class);
        return String.valueOf(body.get(field));
    }
}

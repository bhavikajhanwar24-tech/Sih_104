package com.sentinelvoice.audit;

import com.sentinelvoice.model.AuditBlock;
import com.sentinelvoice.repository.AuditBlockRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuditLedgerTest {

    @Autowired
    private AuditLedgerService auditLedgerService;

    @Autowired
    private AuditBlockRepository auditBlockRepository;

    @Autowired
    private CanonicalJson canonicalJson;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void canonicalJsonIsOrderIndependent() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("z", 0.5);
        a.put("a", "x");
        a.put("nested", Map.of("b", 1, "a", 2));

        Map<String, Object> b = new LinkedHashMap<>();
        b.put("a", "x");
        b.put("nested", Map.of("a", 2, "b", 1));
        b.put("z", 0.5);

        assertEquals(canonicalJson.serialize(a), canonicalJson.serialize(b));
        assertTrue(canonicalJson.serialize(a).contains("0.500000"));
    }

    @Test
    void genesisHashIsDeterministic() {
        String first = AuditLedgerService.genesisPreviousHash("SENTINELVOICE-GENESIS-v1", "call-1", 1_700_000_000_000L);
        String second = AuditLedgerService.genesisPreviousHash("SENTINELVOICE-GENESIS-v1", "call-1", 1_700_000_000_000L);
        String otherTime = AuditLedgerService.genesisPreviousHash("SENTINELVOICE-GENESIS-v1", "call-1", 1_700_000_000_001L);
        assertEquals(64, first.length());
        assertEquals(first, second);
        assertNotEquals(first, otherTime);
    }

    @Test
    void chainOfOneHundredBlocksVerifies() {
        String sessionId = "chain-" + UUID.randomUUID();
        auditLedgerService.append(sessionId, AuditEventType.SESSION_OPENED, Map.of("n", 0));
        for (int i = 1; i < 100; i++) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("seq", i);
            payload.put("smoothedRisk", 0.125);
            auditLedgerService.append(sessionId, AuditEventType.FEATURE_FRAME_SCORED, payload);
        }

        ChainVerificationResult result = auditLedgerService.verify(sessionId);
        assertTrue(result.valid());
        assertEquals(100, result.blockCount());

        AuditBlock genesis = auditBlockRepository.findBySessionIdOrderByBlockIndexAsc(sessionId).getFirst();
        assertEquals(0, genesis.getBlockIndex());
        assertEquals(
                AuditLedgerService.genesisPreviousHash("SENTINELVOICE-GENESIS-v1", sessionId, genesis.getTsEpochMs()),
                genesis.getPreviousHash()
        );
    }

    @Test
    void tamperDetectionReportsBrokenIndex() {
        String sessionId = "tamper-" + UUID.randomUUID();
        for (int i = 0; i < 10; i++) {
            AuditEventType type = i == 0 ? AuditEventType.SESSION_OPENED : AuditEventType.FEATURE_FRAME_SCORED;
            auditLedgerService.append(sessionId, type, Map.of("seq", i));
        }
        assertTrue(auditLedgerService.verify(sessionId).valid());

        List<AuditBlock> blocks = auditBlockRepository.findBySessionIdOrderByBlockIndexAsc(sessionId);
        AuditBlock victim = blocks.get(4);
        victim.setDetails(canonicalJson.serialize(Map.of("seq", 4, "tampered", true)));
        auditBlockRepository.saveAndFlush(victim);

        ChainVerificationResult result = auditLedgerService.verify(sessionId);
        assertFalse(result.valid());
        assertEquals(4, result.brokenAtIndex());
        assertEquals(10, result.blockCount());
    }

    @Test
    void sessionStartWritesGenesisBlockAndVerifyEndpointIsValidUntilTamper() throws Exception {
        String sessionId = "http-" + UUID.randomUUID();
        String body = """
                {"schema":"sentinelvoice.SessionStartRequest/1","sessionId":"%s","callerId":"cli","calleeId":"desk","channelProfile":"WEBRTC_WIDEBAND"}
                """.formatted(sessionId);

        mockMvc.perform(post("/api/v1/session/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(sessionId));

        mockMvc.perform(get("/api/v1/compliance/audit-chain/" + sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocks[0].blockIndex").value(0))
                .andExpect(jsonPath("$.blocks[0].eventType").value("SESSION_OPENED"))
                .andExpect(jsonPath("$.blocks[0].schema").value("sentinelvoice.AuditBlock/1"))
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(get("/api/v1/compliance/verify/" + sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.blockCount").value(1));

        jdbcTemplate.update(
                "UPDATE audit_blocks SET details = ? WHERE session_id = ? AND block_index = 0",
                canonicalJson.serialize(Map.of("callerId", "forged")),
                sessionId
        );

        mockMvc.perform(get("/api/v1/compliance/verify/" + sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.brokenAtIndex").value(0));
    }
}

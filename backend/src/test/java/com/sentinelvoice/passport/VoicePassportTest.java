package com.sentinelvoice.passport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelvoice.audit.AuditEventType;
import com.sentinelvoice.audit.AuditLedgerService;
import com.sentinelvoice.audit.ChainVerificationResult;
import com.sentinelvoice.identity.IdentityVerdict;
import com.sentinelvoice.model.ChannelProfile;
import com.sentinelvoice.model.AuditBlock;
import com.sentinelvoice.repository.AuditBlockRepository;
import com.sentinelvoice.repository.VoicePassportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class VoicePassportTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private VoicePassportService voicePassportService;

    @Autowired
    private VoicePassportRepository passportRepository;

    @Autowired
    private AuditLedgerService auditLedgerService;

    @Autowired
    private AuditBlockRepository auditBlockRepository;

    private MockRestServiceServer mlServer;
    private float[] speakerEmbedding;

    @BeforeEach
    void setUp() {
        mlServer = MockRestServiceServer.createServer(restTemplate);
        speakerEmbedding = unitEmbedding(7);
    }

    @Test
    void enrolWithoutConsent_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/passport/enrol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"employeeId":"EMP-NOCONSENT","channelProfile":"WEBRTC_WIDEBAND","audioRef":"synthetic:x"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void enrolWithConsent_storesWidebandAndNarrowband_andVerifyMatches() throws Exception {
        String employeeId = "EMP-10492";
        grantConsent(employeeId);
        stubMlEnrol();

        MvcResult enrolResult = mockMvc.perform(post("/api/v1/passport/enrol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"employeeId":"EMP-10492","channelProfile":"WEBRTC_WIDEBAND","audioRef":"synthetic:cfo"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.profileIds").isArray())
                .andReturn();

        mlServer.verify();

        PassportDtos.EnrolResponse enrol = objectMapper.readValue(
                enrolResult.getResponse().getContentAsString(),
                PassportDtos.EnrolResponse.class
        );
        assertThat(enrol.channelProfiles())
                .contains("WEBRTC_WIDEBAND", "PSTN_NARROWBAND");
        assertThat(passportRepository.findByEmployeeIdAndActiveTrue(employeeId)).hasSizeGreaterThanOrEqualTo(2);

        String profileId = enrol.profileIds().getFirst();
        MvcResult getResult = mockMvc.perform(get("/api/v1/passport/" + profileId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileId").value(profileId))
                .andExpect(jsonPath("$.employeeId").value(employeeId))
                .andReturn();
        String getBody = getResult.getResponse().getContentAsString();
        // Must not expose the biometric vector — embeddingModelId is metadata, not the template.
        assertThat(getBody).doesNotContain("\"embedding\":");
        assertThat(getBody).contains("embeddingModelId");
        assertThat(objectMapper.readTree(getBody).has("embedding")).isFalse();

        PassportDtos.VerifyResult verify = voicePassportService.verify(
                employeeId,
                speakerEmbedding,
                ChannelProfile.WEBRTC_WIDEBAND
        );
        assertThat(verify.reason()).isNull();
        assertThat(verify.cosine()).isGreaterThan(0.70);
        assertThat(verify.verdict()).isEqualTo(IdentityVerdict.VERIFIED);

        PassportDtos.VerifyResult mismatchChannel = voicePassportService.verify(
                employeeId,
                speakerEmbedding,
                ChannelProfile.VOIP_WIDEBAND
        );
        // VOIP may have been enrolled from dual response — if present, OK; if we only store
        // WEBRTC+PSTN, VOIP without row is CHANNEL_MISMATCH when not enrolled.
        // Our stub includes VOIP_WIDEBAND, so it should match.
        assertThat(mismatchChannel.cosine()).isGreaterThan(0.70);
    }

    @Test
    void erase_returnsCertificate_rowGone_tombstoneInValidChain() throws Exception {
        String employeeId = "EMP-ERASE1";
        grantConsent(employeeId);
        stubMlEnrol();

        MvcResult enrolResult = mockMvc.perform(post("/api/v1/passport/enrol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"employeeId":"EMP-ERASE1","channelProfile":"WEBRTC_WIDEBAND","audioRef":"synthetic:e"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        PassportDtos.EnrolResponse enrol = objectMapper.readValue(
                enrolResult.getResponse().getContentAsString(),
                PassportDtos.EnrolResponse.class
        );
        String profileId = enrol.profileIds().getFirst();
        String expectedTombstone = EmbeddingCodec.sha256Hex(speakerEmbedding);

        MvcResult eraseResult = mockMvc.perform(delete("/api/v1/passport/" + profileId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileId").value(profileId))
                .andExpect(jsonPath("$.tombstoneHash").value(expectedTombstone))
                .andExpect(jsonPath("$.auditBlockIndex").isNumber())
                .andReturn();

        PassportDtos.DeletionCertificate cert = objectMapper.readValue(
                eraseResult.getResponse().getContentAsString(),
                PassportDtos.DeletionCertificate.class
        );
        assertThat(passportRepository.findById(profileId)).isEmpty();

        String sessionId = VoicePassportService.auditSessionId(employeeId);
        ChainVerificationResult chain = auditLedgerService.verify(sessionId);
        assertThat(chain.valid()).isTrue();
        assertThat(chain.blockCount()).isGreaterThanOrEqualTo(2);

        AuditBlock erasedBlock = auditBlockRepository.findBySessionIdOrderByBlockIndexAsc(sessionId).stream()
                .filter(b -> AuditEventType.PASSPORT_ERASED.name().equals(b.getEventType()))
                .filter(b -> b.getBlockIndex() == cert.auditBlockIndex())
                .findFirst()
                .orElseThrow();
        assertThat(erasedBlock.getDetails()).contains(expectedTombstone);
        assertThat(cert.tombstoneHash()).isEqualTo(expectedTombstone);
        assertThat(cert.auditBlockIndex()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void verify_missingChannelProfile_returnsChannelMismatch() throws Exception {
        String employeeId = "EMP-CHMISMATCH";
        grantConsent(employeeId);
        stubMlEnrol();
        MvcResult enrolResult = mockMvc.perform(post("/api/v1/passport/enrol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"employeeId":"EMP-CHMISMATCH","channelProfile":"WEBRTC_WIDEBAND","audioRef":"synthetic:m"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        PassportDtos.EnrolResponse enrol = objectMapper.readValue(
                enrolResult.getResponse().getContentAsString(),
                PassportDtos.EnrolResponse.class
        );
        // Leave only WEBRTC — prove we never fall back to another channel's passport.
        for (int i = 0; i < enrol.profileIds().size(); i++) {
            if (!"WEBRTC_WIDEBAND".equals(enrol.channelProfiles().get(i))) {
                passportRepository.deleteById(enrol.profileIds().get(i));
            }
        }

        PassportDtos.VerifyResult result = voicePassportService.verify(
                employeeId,
                speakerEmbedding,
                ChannelProfile.PSTN_NARROWBAND
        );
        assertThat(result.verdict()).isEqualTo(IdentityVerdict.INCONCLUSIVE);
        assertThat(result.reason()).isEqualTo("CHANNEL_MISMATCH");
        assertThat(result.cosine()).isNull();

        PassportDtos.VerifyResult match = voicePassportService.verify(
                employeeId,
                speakerEmbedding,
                ChannelProfile.WEBRTC_WIDEBAND
        );
        assertThat(match.cosine()).isGreaterThan(0.70);
    }

    private void grantConsent(String employeeId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("employeeId", employeeId);
        body.put("purpose", VoicePassportService.PURPOSE_VOICE_PASSPORT);
        body.put("noticeVersion", "notice-v1-2026");
        body.put("grantedBy", "test-officer");
        body.put("method", "AFFIRMATIVE_UI");
        mockMvc.perform(post("/api/v1/passport/consent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    private void stubMlEnrol() throws Exception {
        Map<String, Object> embeddings = new LinkedHashMap<>();
        embeddings.put("WEBRTC_WIDEBAND", floatList(speakerEmbedding));
        embeddings.put("VOIP_WIDEBAND", floatList(speakerEmbedding));
        embeddings.put("PSTN_NARROWBAND", floatList(speakerEmbedding));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("modelId", "speechbrain/spkrec-ecapa-voxceleb");
        payload.put("embeddings", embeddings);
        String json = objectMapper.writeValueAsString(payload);

        mlServer.expect(ExpectedCount.once(), requestTo("http://localhost:8000/enrol"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(json));
    }

    private static float[] unitEmbedding(int seed) {
        float[] emb = new float[EmbeddingCodec.DIM];
        double norm = 0.0;
        for (int i = 0; i < emb.length; i++) {
            emb[i] = (float) Math.sin((i + 1) * (seed + 0.5) * 0.017);
            norm += emb[i] * emb[i];
        }
        norm = Math.sqrt(norm) + 1e-12;
        for (int i = 0; i < emb.length; i++) {
            emb[i] = (float) (emb[i] / norm);
        }
        return emb;
    }

    private static java.util.List<Double> floatList(float[] emb) {
        java.util.List<Double> list = new java.util.ArrayList<>(emb.length);
        for (float v : emb) {
            list.add((double) v);
        }
        return list;
    }
}

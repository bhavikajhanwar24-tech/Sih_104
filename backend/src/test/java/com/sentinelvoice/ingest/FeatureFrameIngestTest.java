package com.sentinelvoice.ingest;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sentinelvoice.audit.AuditLedgerService;
import com.sentinelvoice.config.SentinelProperties;
import com.sentinelvoice.model.AuditBlock;
import com.sentinelvoice.model.CallSession;
import com.sentinelvoice.model.ChannelProfile;
import com.sentinelvoice.model.FeatureFrame;
import com.sentinelvoice.model.SessionStartRequest;
import com.sentinelvoice.service.CallSessionManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FeatureFrameIngestTest {

    /**
     * Context §8.1 example, comments included (jsonc). Contract regression fixture.
     */
    private static final String SECTION_81 = """
            {
              "schema": "sentinelvoice.FeatureFrame/1",
              "sessionId": "call-9012",
              "seq": 47,
              "windowStartMs": 23500,
              "windowEndMs": 24000,
              "channelProfile": "PSTN_NARROWBAND",      // PSTN_NARROWBAND | VOIP_WIDEBAND | WEBRTC_WIDEBAND
              "speechPresent": true,                     // VAD; false frames do not update the score
              "cumulativeSpeechMs": 14200,               // gates INSUFFICIENT_EVIDENCE

              "voice": {
                "spoofProbability": 0.71,                // CALIBRATED probability, not a raw logit
                "modelId": "aasist-lfcc-v3-codecaug",
                "confidence": 0.62,                      // 1 - normalised predictive entropy
                "available": true
              },
              "channel": {
                "rirT60Ms": 18.0,                        // implausibly low => injected audio
                "rirPlausible": false,
                "doubleCompressionScore": 0.66,
                "noiseFloorStationarity": 0.91,          // synthetic silence is too stationary
                "dcOffset": 0.0004,
                "available": true
              },
              "prosody": {
                "f0MeanHz": 118.4, "f0StdHz": 4.1,
                "jitterLocalPct": 0.08,                  // human 0.5-1.5%; <0.2% => over-smooth
                "shimmerLocalPct": 1.9,
                "hnrDb": 27.8,                           // implausibly high HNR => synthetic
                "breathEventsPerMin": 0.0,               // human 8-20
                "disfluencyRate": 0.0,
                "articulationRateSylSec": 5.9,
                "unnaturalnessScore": 0.74,
                "available": true
              },
              "speaker": {
                "embeddingId": "emb-9012-47",            // pointer only; vector stays server-side
                "enrolledProfileId": "EMP-10492",
                "cosineSimilarity": 0.41,
                "intraCallDrift": 0.18,                  // RVC systems drift
                "available": true
              },
              "watermark": {
                "detector": "audioseal-v1",
                "detected": false,
                "provider": null,
                "confidence": 0.03
              },
              "linguistic": {                             // slow path; may be stale - check ageMs
                "ageMs": 900,
                "language": "hi-en",                      // code-switch detected
                "urgency": 0.93,
                "secrecy": 0.88,
                "authorityInvocation": 0.90,
                "emotionalCoercion": 0.71,
                "askDetected": true,
                "ask": {
                  "type": "WIRE_TRANSFER",
                  "amount": 5000000,
                  "currency": "INR",
                  "beneficiaryHint": "vendor account ending 4471",
                  "deadline": "immediate"
                },
                "claimedIdentity": "Rajesh Kumar",
                "claimedRole": "CFO",
                "redactedSnippet": "…this is [NAME], [ROLE]. Transfer [AMOUNT] immediately, don't tell [PERSON]…",
                "available": true
              },
              "latencyMs": {"fastPath": 94, "slowPath": 812}
            }
            """;

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(JsonParser.Feature.ALLOW_COMMENTS)
            .build();

    private CallSessionManager sessions;
    private FeatureFrameIngestService ingest;
    private SimpleMeterRegistry meters;

    @BeforeEach
    void setUp() {
        SentinelProperties properties = mock(SentinelProperties.class);
        when(properties.session()).thenReturn(new SentinelProperties.Session(30, 10));
        when(properties.ml()).thenReturn(new SentinelProperties.Ml(
                "http://localhost:8000",
                "ws://localhost:8000/ingest",
                2000,
                1500
        ));
        AuditLedgerService audit = mock(AuditLedgerService.class);
        when(audit.append(any(), any(), any())).thenReturn(new AuditBlock());
        sessions = new CallSessionManager(properties, audit);
        meters = new SimpleMeterRegistry();
        ingest = new FeatureFrameIngestService(sessions, properties, meters);
    }

    @Test
    void section81ExampleDeserialisesEveryField() throws Exception {
        FeatureFrame frame = MAPPER.readValue(SECTION_81, FeatureFrame.class);

        assertEquals("sentinelvoice.FeatureFrame/1", frame.schema());
        assertEquals("call-9012", frame.sessionId());
        assertEquals(47, frame.seq());
        assertEquals(23500, frame.windowStartMs());
        assertEquals(24000, frame.windowEndMs());
        assertEquals(ChannelProfile.PSTN_NARROWBAND, frame.channelProfile());
        assertTrue(frame.speechPresent());
        assertEquals(14200, frame.cumulativeSpeechMs());

        assertTrue(frame.voice().available());
        assertEquals(0.71, frame.voice().spoofProbability(), 1e-9);
        assertEquals("aasist-lfcc-v3-codecaug", frame.voice().modelId());
        assertEquals(0.62, frame.voice().confidence(), 1e-9);

        assertTrue(frame.channel().available());
        assertEquals(18.0, frame.channel().rirT60Ms(), 1e-9);
        assertFalse(frame.channel().rirPlausible());
        assertEquals(0.66, frame.channel().doubleCompressionScore(), 1e-9);
        assertEquals(0.91, frame.channel().noiseFloorStationarity(), 1e-9);
        assertEquals(0.0004, frame.channel().dcOffset(), 1e-9);

        assertTrue(frame.prosody().available());
        assertEquals(118.4, frame.prosody().f0MeanHz(), 1e-9);
        assertEquals(4.1, frame.prosody().f0StdHz(), 1e-9);
        assertEquals(0.08, frame.prosody().jitterLocalPct(), 1e-9);
        assertEquals(1.9, frame.prosody().shimmerLocalPct(), 1e-9);
        assertEquals(27.8, frame.prosody().hnrDb(), 1e-9);
        assertEquals(0.0, frame.prosody().breathEventsPerMin(), 1e-9);
        assertEquals(0.0, frame.prosody().disfluencyRate(), 1e-9);
        assertEquals(5.9, frame.prosody().articulationRateSylSec(), 1e-9);
        assertEquals(0.74, frame.prosody().unnaturalnessScore(), 1e-9);

        assertTrue(frame.speaker().available());
        assertEquals("emb-9012-47", frame.speaker().embeddingId());
        assertEquals("EMP-10492", frame.speaker().enrolledProfileId());
        assertEquals(0.41, frame.speaker().cosineSimilarity(), 1e-9);
        assertEquals(0.18, frame.speaker().intraCallDrift(), 1e-9);

        assertEquals("audioseal-v1", frame.watermark().detector());
        assertFalse(frame.watermark().detected());
        assertNull(frame.watermark().provider());
        assertEquals(0.03, frame.watermark().confidence(), 1e-9);

        assertTrue(frame.linguistic().available());
        assertEquals(900L, frame.linguistic().ageMs());
        assertEquals("hi-en", frame.linguistic().language());
        assertEquals(0.93, frame.linguistic().urgency(), 1e-9);
        assertEquals(0.88, frame.linguistic().secrecy(), 1e-9);
        assertEquals(0.90, frame.linguistic().authorityInvocation(), 1e-9);
        assertEquals(0.71, frame.linguistic().emotionalCoercion(), 1e-9);
        assertTrue(frame.linguistic().askDetected());
        assertNotNull(frame.linguistic().ask());
        assertEquals("WIRE_TRANSFER", frame.linguistic().ask().type());
        assertEquals(5_000_000.0, frame.linguistic().ask().amount(), 1e-9);
        assertEquals("INR", frame.linguistic().ask().currency());
        assertEquals("vendor account ending 4471", frame.linguistic().ask().beneficiaryHint());
        assertEquals("immediate", frame.linguistic().ask().deadline());
        assertEquals("Rajesh Kumar", frame.linguistic().claimedIdentity());
        assertEquals("CFO", frame.linguistic().claimedRole());
        assertTrue(frame.linguistic().redactedSnippet().contains("[AMOUNT]"));

        assertEquals(94.0, frame.latencyMs().fastPath(), 1e-9);
        assertEquals(812.0, frame.latencyMs().slowPath(), 1e-9);
    }

    @Test
    void ingestStoresFrameAndCountsReceived() throws Exception {
        sessions.createSession(start("call-9012"));
        FeatureFrame frame = MAPPER.readValue(SECTION_81, FeatureFrame.class);
        ingest.ingest(frame);
        CallSession session = sessions.requireSession("call-9012");
        assertEquals(47, session.getLastFeatureSeq());
        assertEquals(14200, session.getCumulativeSpeechMs());
        assertNotNull(session.getLastFeatureFrame());
        assertEquals(1.0, meters.find("sentinel.frames.received").counter().count(), 1e-9);
    }

    @Test
    void dropsUnknownSessionAndOutOfOrder() throws Exception {
        FeatureFrame frame = MAPPER.readValue(SECTION_81, FeatureFrame.class);
        ingest.ingest(frame);
        assertEquals(1.0, meters.find("sentinel.frames.dropped").counter().count(), 1e-9);

        sessions.createSession(start("call-9012"));
        ingest.ingest(frame);
        ingest.ingest(frame);
        assertEquals(2.0, meters.find("sentinel.frames.dropped").counter().count(), 1e-9);
        assertEquals(1.0, meters.find("sentinel.frames.received").counter().count(), 1e-9);
    }

    @Test
    void dropsStaleEpochWindow() throws Exception {
        sessions.createSession(start("stale-1"));
        long oldEnd = Instant.now().toEpochMilli() - 5_000;
        FeatureFrame frame = stubFrame("stale-1", 1, oldEnd);
        ingest.ingest(frame);
        assertEquals(1.0, meters.find("sentinel.frames.stale").counter().count(), 1e-9);
        assertEquals(0.0, meters.find("sentinel.frames.received").counter().count(), 1e-9);
    }

    private static SessionStartRequest start(String sessionId) {
        return new SessionStartRequest(
                "sentinelvoice.SessionStartRequest/1",
                sessionId,
                "cli-1",
                "desk-1",
                ChannelProfile.PSTN_NARROWBAND,
                null
        );
    }

    private static FeatureFrame stubFrame(String sessionId, int seq, long windowEndMs) {
        return new FeatureFrame(
                "sentinelvoice.FeatureFrame/1",
                sessionId,
                seq,
                Math.max(0, windowEndMs - 2000),
                windowEndMs,
                ChannelProfile.WEBRTC_WIDEBAND,
                false,
                0,
                new FeatureFrame.VoiceFamily(true, 0.1, "stub-rms-p2.2", 0.4),
                new FeatureFrame.ChannelFamily(false, null, null, null, null, null),
                new FeatureFrame.ProsodyFamily(false, null, null, null, null, null, null, null, null, null),
                new FeatureFrame.SpeakerFamily(false, null, null, null, null),
                new FeatureFrame.WatermarkFamily(false, null, null, null, null),
                new com.sentinelvoice.model.LinguisticFamily(
                        false, null, null, null, null, null, null, null, null, null, null, null
                ),
                new FeatureFrame.LatencyMs(1.0, 0.0)
        );
    }
}

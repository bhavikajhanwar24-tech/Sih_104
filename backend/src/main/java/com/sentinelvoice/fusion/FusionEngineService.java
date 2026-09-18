package com.sentinelvoice.fusion;

import com.sentinelvoice.config.SentinelProperties;
import com.sentinelvoice.model.ChannelProfile;
import com.sentinelvoice.model.FeatureFrame;
import com.sentinelvoice.model.LinguisticFamily;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Fusion engine implementing Context §9.1–§9.4:
 * availability renormalisation, dual-profile weights, asymmetric EMA, corroboration, emergency.
 */
@Service
public class FusionEngineService {

    private final SentinelProperties.Fusion fusion;
    private final EmaSmoother emaSmoother;
    private final CorroborationGate corroborationGate;
    private final EmergencyTrigger emergencyTrigger;

    public FusionEngineService(
            SentinelProperties properties,
            EmaSmoother emaSmoother,
            CorroborationGate corroborationGate,
            EmergencyTrigger emergencyTrigger
    ) {
        this.fusion = properties.fusion();
        this.emaSmoother = emaSmoother;
        this.corroborationGate = corroborationGate;
        this.emergencyTrigger = emergencyTrigger;
    }

    public FusionResult evaluate(String sessionId, FusionContext context) {
        FeatureFrame frame = context.frame();
        ChannelProfile profile = frame.channelProfile();

        Map<EvidenceFamily, Extracted> extracted = extractFamilies(context);
        Map<EvidenceFamily, FamilyScore> familyScores = new EnumMap<>(EvidenceFamily.class);
        Map<EvidenceFamily, Double> availableScores = new EnumMap<>(EvidenceFamily.class);

        double numerator = 0.0;
        double denominator = 0.0;
        int availableCount = 0;

        for (EvidenceFamily family : EvidenceFamily.values()) {
            Extracted ex = extracted.get(family);
            double weight = family.weight(fusion, profile);
            if (ex.available()) {
                availableCount++;
                double confidence = ex.confidence();
                double score = ex.score();
                double term = weight * confidence;
                numerator += term * score;
                denominator += term;
                availableScores.put(family, score);
                // contribution filled after we know denominator
                familyScores.put(family, new FamilyScore(family, score, weight, confidence, 0.0, true));
            } else {
                familyScores.put(family, new FamilyScore(family, 0.0, weight, 0.0, 0.0, false));
            }
        }

        double instantaneous = denominator > 0.0 ? numerator / denominator : 0.0;

        // Fill contributions: (w * c * S) / denom  (== share of S(t) for available families)
        for (EvidenceFamily family : EvidenceFamily.values()) {
            FamilyScore fs = familyScores.get(family);
            if (fs.available() && denominator > 0.0) {
                double contribution = (fs.weight() * fs.confidence() * fs.score()) / denominator;
                familyScores.put(family, new FamilyScore(
                        family, fs.score(), fs.weight(), fs.confidence(), contribution, true));
            }
        }

        Optional<String> emergency = emergencyTrigger.evaluate(context);
        boolean emergencyFired = emergency.isPresent();

        Double previousSmoothed = emaSmoother.peek(sessionId);
        double smoothed = emaSmoother.update(
                sessionId,
                instantaneous,
                frame.speechPresent(),
                emergencyFired
        );

        FusionResult.Trend trend = trendOf(previousSmoothed, smoothed);
        FusionResult.RiskState state = resolveState(frame.cumulativeSpeechMs(), availableCount);
        FusionResult.CorroborationDetail corroboration = corroborationGate.evaluate(availableScores);

        return new FusionResult(
                instantaneous,
                smoothed,
                trend,
                state,
                Map.copyOf(familyScores),
                corroboration,
                emergency.orElse(null)
        );
    }

    private FusionResult.RiskState resolveState(long cumulativeSpeechMs, int availableCount) {
        if (cumulativeSpeechMs < fusion.minSpeechMsForScoring()) {
            return FusionResult.RiskState.INSUFFICIENT_EVIDENCE;
        }
        if (availableCount < 3) {
            return FusionResult.RiskState.DEGRADED;
        }
        return FusionResult.RiskState.SCORED;
    }

    private static FusionResult.Trend trendOf(Double previous, double current) {
        if (previous == null) {
            return FusionResult.Trend.STABLE;
        }
        double delta = current - previous;
        if (Math.abs(delta) < 1e-12) {
            return FusionResult.Trend.STABLE;
        }
        return delta > 0 ? FusionResult.Trend.RISING : FusionResult.Trend.FALLING;
    }

    private Map<EvidenceFamily, Extracted> extractFamilies(FusionContext context) {
        FeatureFrame frame = context.frame();
        Map<EvidenceFamily, Extracted> map = new EnumMap<>(EvidenceFamily.class);

        FeatureFrame.VoiceFamily voice = frame.voice();
        if (voice != null && voice.available() && voice.spoofProbability() != null) {
            double confidence = voice.confidence() != null ? clamp01(voice.confidence()) : 1.0;
            map.put(EvidenceFamily.VOICE, Extracted.available(voice.spoofProbability(), confidence));
        } else {
            map.put(EvidenceFamily.VOICE, Extracted.unavailable());
        }

        FeatureFrame.ChannelFamily channel = frame.channel();
        if (channel != null && channel.available() && channel.doubleCompressionScore() != null) {
            map.put(EvidenceFamily.CHANNEL, Extracted.available(channel.doubleCompressionScore(), 1.0));
        } else {
            map.put(EvidenceFamily.CHANNEL, Extracted.unavailable());
        }

        FeatureFrame.ProsodyFamily prosody = frame.prosody();
        if (prosody != null && prosody.available() && prosody.unnaturalnessScore() != null) {
            map.put(EvidenceFamily.PROSODY, Extracted.available(prosody.unnaturalnessScore(), 1.0));
        } else {
            map.put(EvidenceFamily.PROSODY, Extracted.unavailable());
        }

        LinguisticFamily linguistic = frame.linguistic();
        if (linguistic != null && linguistic.available()) {
            double score = linguisticComposite(linguistic);
            long ageMs = linguistic.ageMs() != null ? linguistic.ageMs() : 0L;
            double tau = fusion.linguisticStalenessTauMs();
            double confidence = Math.exp(-((double) ageMs) / tau);
            map.put(EvidenceFamily.LINGUISTIC, Extracted.available(score, clamp01(confidence)));
        } else {
            map.put(EvidenceFamily.LINGUISTIC, Extracted.unavailable());
        }

        if (context.transactionAvailable()) {
            map.put(EvidenceFamily.TRANSACTION,
                    Extracted.available(clamp01(context.transactionScore()), 1.0));
        } else {
            map.put(EvidenceFamily.TRANSACTION, Extracted.unavailable());
        }

        if (context.relationshipAvailable()) {
            map.put(EvidenceFamily.RELATIONSHIP,
                    Extracted.available(clamp01(context.relationshipScore()), 1.0));
        } else {
            map.put(EvidenceFamily.RELATIONSHIP, Extracted.unavailable());
        }

        return map;
    }

    private static double linguisticComposite(LinguisticFamily linguistic) {
        double max = 0.0;
        if (linguistic.urgency() != null) {
            max = Math.max(max, linguistic.urgency());
        }
        if (linguistic.secrecy() != null) {
            max = Math.max(max, linguistic.secrecy());
        }
        if (linguistic.authorityInvocation() != null) {
            max = Math.max(max, linguistic.authorityInvocation());
        }
        if (linguistic.emotionalCoercion() != null) {
            max = Math.max(max, linguistic.emotionalCoercion());
        }
        return clamp01(max);
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private record Extracted(boolean available, double score, double confidence) {
        static Extracted available(double score, double confidence) {
            return new Extracted(true, clamp01(score), clamp01(confidence));
        }

        static Extracted unavailable() {
            return new Extracted(false, 0.0, 0.0);
        }
    }
}

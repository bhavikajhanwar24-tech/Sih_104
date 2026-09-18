package com.sentinelvoice.fusion;

import com.sentinelvoice.config.SentinelProperties;
import com.sentinelvoice.model.Ask;
import com.sentinelvoice.model.FeatureFrame;
import com.sentinelvoice.model.LinguisticFamily;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Emergency bypass conditions from Context §9.4. On match: corroboration is bypassed and
 * EMA {@code lambda = 0}. Thresholds come from {@link SentinelProperties.Emergency}.
 */
@Component
public class EmergencyTrigger {

    public static final String REASON_CLI_CLAIM_POLICY = "EMERGENCY_CLI_CLAIM_POLICY";
    public static final String REASON_VOICEPRINT_ASK = "EMERGENCY_VOICEPRINT_ASK";
    public static final String REASON_SECRECY_AUTHORITY_TXN = "EMERGENCY_SECRECY_AUTHORITY_TXN";

    private final SentinelProperties.Emergency emergency;

    public EmergencyTrigger(SentinelProperties properties) {
        this.emergency = properties.fusion().emergency();
    }

    /**
     * @return reason code of the first matched condition, or empty if none fire
     */
    public Optional<String> evaluate(FusionContext context) {
        FeatureFrame frame = context.frame();

        // 1) CLI/claim mismatch AND ask amount exceeds verbal authority limit
        if (context.cliVsClaimMismatch()) {
            Double askAmount = askAmount(frame);
            if (askAmount != null && askAmount > context.verbalAuthorityLimit()) {
                return Optional.of(REASON_CLI_CLAIM_POLICY);
            }
        }

        // 2) Enrolled passport cosine below threshold AND ask detected
        FeatureFrame.SpeakerFamily speaker = frame.speaker();
        if (speaker != null
                && speaker.available()
                && speaker.enrolledProfileId() != null
                && !speaker.enrolledProfileId().isBlank()
                && speaker.cosineSimilarity() != null
                && speaker.cosineSimilarity() < emergency.cosineMismatchThreshold()
                && frame.linguistic() != null
                && Boolean.TRUE.equals(frame.linguistic().askDetected())) {
            return Optional.of(REASON_VOICEPRINT_ASK);
        }

        // 3) High secrecy + authority + transaction family score
        LinguisticFamily linguistic = frame.linguistic();
        if (linguistic != null
                && linguistic.available()
                && linguistic.secrecy() != null
                && linguistic.authorityInvocation() != null
                && linguistic.secrecy() > emergency.secrecyThreshold()
                && linguistic.authorityInvocation() > emergency.authorityThreshold()
                && context.transactionAvailable()
                && context.transactionScore() > emergency.transactionScoreThreshold()) {
            return Optional.of(REASON_SECRECY_AUTHORITY_TXN);
        }

        return Optional.empty();
    }

    private static Double askAmount(FeatureFrame frame) {
        if (frame.linguistic() == null) {
            return null;
        }
        Ask ask = frame.linguistic().ask();
        return ask == null ? null : ask.amount();
    }
}

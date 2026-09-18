package com.sentinelvoice.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.Map;
import java.util.Set;

/**
 * All SentinelVoice tunables. Bound from {@code sentinelvoice.*} in application.yml.
 * Invalid values fail startup via {@code @Validated} — never at request time.
 */
@Validated
@ConfigurationProperties(prefix = "sentinelvoice")
public record SentinelProperties(
        @NotNull @Valid Fusion fusion,
        @NotNull @Valid Intervention intervention,
        @NotNull @Valid Ml ml,
        @NotNull @Valid Session session,
        @NotNull @Valid Audit audit
) {

    private static final Set<String> REQUIRED_FAMILIES = Set.of(
            "voice", "channel", "prosody", "linguistic", "transaction", "relationship"
    );
    private static final double WEIGHT_SUM_TOLERANCE = 0.001;

    public record Fusion(
            @NotNull @Valid Weights weights,
            @DecimalMin(value = "0.0", message = "fusion.lambdaUp must be between 0 and 1 (inclusive)")
            @DecimalMax(value = "1.0", message = "fusion.lambdaUp must be between 0 and 1 (inclusive)")
            double lambdaUp,
            @DecimalMin(value = "0.0", message = "fusion.lambdaDown must be between 0 and 1 (inclusive)")
            @DecimalMax(value = "1.0", message = "fusion.lambdaDown must be between 0 and 1 (inclusive)")
            double lambdaDown,
            @NotEmpty Map<String, @DecimalMin("0.0") @DecimalMax("1.0") Double> familyThresholds,
            @Min(value = 1, message = "fusion.linguisticStalenessTauMs must be >= 1")
            long linguisticStalenessTauMs,
            @Min(value = 0, message = "fusion.minSpeechMsForScoring must be >= 0")
            long minSpeechMsForScoring
    ) {
    }

    public record Weights(
            @NotEmpty Map<String, @DecimalMin("0.0") @DecimalMax("1.0") Double> wideband,
            @NotEmpty Map<String, @DecimalMin("0.0") @DecimalMax("1.0") Double> narrowband
    ) {
        @AssertTrue(message = "fusion.weights.wideband must include all six evidence families and sum to 1.0 ± 0.001")
        public boolean widebandWeightsNormalized() {
            return familiesPresent(wideband) && sumsToOne(wideband);
        }

        @AssertTrue(message = "fusion.weights.narrowband must include all six evidence families and sum to 1.0 ± 0.001")
        public boolean narrowbandWeightsNormalized() {
            return familiesPresent(narrowband) && sumsToOne(narrowband);
        }
    }

    public record Intervention(
            @NotNull @Valid Transition l1ToL2,
            @NotNull @Valid Transition l2ToL1,
            @NotNull @Valid Transition l2ToL3,
            @NotNull @Valid Transition l3ToL2,
            @NotNull @Valid Transition l3ToL4,
            @NotNull @Valid Transition l4ToL3,
            @NotNull @Valid Transition l4ToL5
    ) {
    }

    public record Transition(
            @DecimalMin(value = "0.0", message = "upThreshold must be between 0 and 1 (inclusive)")
            @DecimalMax(value = "1.0", message = "upThreshold must be between 0 and 1 (inclusive)")
            double upThreshold,
            @DecimalMin(value = "0.0", message = "downThreshold must be between 0 and 1 (inclusive)")
            @DecimalMax(value = "1.0", message = "downThreshold must be between 0 and 1 (inclusive)")
            double downThreshold,
            @Min(value = 0, message = "dwellMs must be >= 0")
            long dwellMs
    ) {
    }

    public record Ml(
            @NotBlank String baseUrl,
            @NotBlank String websocketUrl,
            @Min(value = 1, message = "ml.connectTimeoutMs must be >= 1")
            int connectTimeoutMs,
            @Min(value = 1, message = "ml.frameStalenessMs must be >= 1")
            int frameStalenessMs
    ) {
    }

    public record Session(
            @Min(value = 1, message = "session.ttlMinutes must be >= 1")
            int ttlMinutes,
            @Min(value = 1, message = "session.maxConcurrent must be >= 1")
            int maxConcurrent
    ) {
    }

    public record Audit(
            @NotBlank String genesisPrefix
    ) {
    }

    static boolean familiesPresent(Map<String, Double> weights) {
        return weights != null && weights.keySet().containsAll(REQUIRED_FAMILIES);
    }

    static boolean sumsToOne(Map<String, Double> weights) {
        if (weights == null || weights.isEmpty()) {
            return false;
        }
        double sum = 0.0;
        for (Double value : weights.values()) {
            if (value == null) {
                return false;
            }
            sum += value;
        }
        return Math.abs(sum - 1.0) <= WEIGHT_SUM_TOLERANCE;
    }
}

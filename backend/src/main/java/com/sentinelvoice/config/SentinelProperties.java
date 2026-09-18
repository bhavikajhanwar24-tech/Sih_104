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

import java.util.List;
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
        @NotNull @Valid Audit audit,
        @NotNull @Valid Identity identity,
        @NotNull @Valid ContextScoring context,
        @NotNull @Valid Actuation actuation
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
            long minSpeechMsForScoring,
            @NotNull @Valid Emergency emergency
    ) {
    }

    /**
     * Emergency-bypass thresholds from Context §9.4. Bound from YAML — never hardcode in Java.
     */
    public record Emergency(
            @DecimalMin(value = "0.0") @DecimalMax(value = "1.0")
            double cosineMismatchThreshold,
            @DecimalMin(value = "0.0") @DecimalMax(value = "1.0")
            double secrecyThreshold,
            @DecimalMin(value = "0.0") @DecimalMax(value = "1.0")
            double authorityThreshold,
            @DecimalMin(value = "0.0") @DecimalMax(value = "1.0")
            double transactionScoreThreshold
    ) {
    }

    public record Weights(
            @NotEmpty Map<String, @DecimalMin("0.0") @DecimalMax("1.0") Double> wideband,
            @NotEmpty Map<String, @DecimalMin("0.0") @DecimalMax("1.0") Double> narrowband
    ) {
        @AssertTrue(message = "fusion.weights.wideband must include all six evidence families and sum to 1.0 ± 0.001")
        public boolean isWidebandWeightsNormalized() {
            return familiesPresent(wideband) && sumsToOne(wideband);
        }

        @AssertTrue(message = "fusion.weights.narrowband must include all six evidence families and sum to 1.0 ± 0.001")
        public boolean isNarrowbandWeightsNormalized() {
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
            @NotNull @Valid Transition l4ToL5,
            @Min(value = 1, message = "intervention.overridePinDurationMs must be >= 1")
            long overridePinDurationMs
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

    /**
     * Identity pipeline thresholds and trunk classification rules (Context §12 / P8.1).
     */
    public record Identity(
            @NotBlank String internalExtensionPattern,
            @NotNull List<String> registeredExternalClis,
            @DecimalMin("0.0") @DecimalMax("1.0") double cosineMatchMin,
            @DecimalMin("0.0") @DecimalMax("1.0") double cosineMismatchMax,
            @DecimalMin("0.0") @DecimalMax("1.0") double spoofHighThreshold
    ) {
    }

    /**
     * Contextual evidence-family scoring (P8.3 relationship graph + transaction policy + cross-channel).
     */
    public record ContextScoring(
            @NotNull @Valid RelationshipScoring relationship,
            @NotNull @Valid TransactionScoring transaction,
            @NotNull @Valid CrossChannelScoring crossChannel
    ) {
    }

    /**
     * Linear relationship score coefficients:
     * {@code S_graph = w_fc*I_fc + w_hier*hierNorm + w_off*I_off + w_dur*I_dur}.
     * Cross-channel is blended as an <em>additive sub-component</em> of RELATIONSHIP
     * (not a 7th family — keeps the six frozen fusion weights intact):
     * {@code S_relationship = clamp01(S_graph + weightCrossChannel * S_cross)}.
     */
    public record RelationshipScoring(
            @DecimalMin("0.0") @DecimalMax("1.0") double weightFirstContact,
            @DecimalMin("0.0") @DecimalMax("1.0") double weightHierarchy,
            @DecimalMin("0.0") @DecimalMax("1.0") double weightOffHours,
            @DecimalMin("0.0") @DecimalMax("1.0") double weightDurationAnomaly,
            @DecimalMin("0.0") @DecimalMax("1.0") double weightCrossChannel,
            @Min(1) int hierarchyNormalizeLevels,
            @Min(0) int businessHourStart,
            @Min(1) int businessHourEnd,
            @Min(1) int typicalHourDeviationHours,
            @DecimalMin("1.0") double durationAnomalyRatio
    ) {
    }

    /**
     * Cross-channel precursor correlation window and score boosts (Context §7.3).
     */
    public record CrossChannelScoring(
            @Min(1) int windowHours,
            @DecimalMin("0.0") @DecimalMax("1.0") double matchingCampaignBoost,
            @DecimalMin("0.0") @DecimalMax("1.0") double indicatorMatchBoost,
            @DecimalMin("0.0") @DecimalMax("1.0") double multiChannelBoost
    ) {
    }

    public record TransactionScoring(
            @DecimalMin("0.0") @DecimalMax("1.0") double policyViolationScore,
            @DecimalMin("0.0") @DecimalMax("1.0") double weightChannelDenied,
            @DecimalMin("0.0") @DecimalMax("1.0") double weightBeneficiaryNovel,
            @DecimalMin("0.0") @DecimalMax("1.0") double weightVelocity,
            @DecimalMin("0.0") @DecimalMax("1.0") double weightUrgencyAmountProduct,
            @DecimalMin("0.0") double highValueThresholdInr,
            @DecimalMin("1.0") double largeAmountThresholdInr,
            @Min(1) int velocityHighValueLimit,
            @NotNull List<String> knownBeneficiaryHints
    ) {
    }

    /**
     * Call actuation adapter selection and ARI / mock-CBS endpoints (Context §11.6).
     */
    public record Actuation(
            @NotBlank String adapter,
            @NotNull @Valid Ari ari,
            @NotBlank String cbsFreezeUrl,
            @NotBlank String supervisorEndpoint
    ) {
    }

    public record Ari(
            @NotBlank String baseUrl,
            @NotBlank String username,
            @NotBlank String password,
            @Min(1) int connectTimeoutMs,
            @Min(1) int readTimeoutMs
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

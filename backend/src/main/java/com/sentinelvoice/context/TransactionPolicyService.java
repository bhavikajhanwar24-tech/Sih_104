package com.sentinelvoice.context;

import com.sentinelvoice.config.SentinelProperties;
import com.sentinelvoice.context.model.TransactionAssessment;
import com.sentinelvoice.identity.model.DirectoryRecord;
import com.sentinelvoice.model.Ask;
import com.sentinelvoice.model.FeatureFrame;
import com.sentinelvoice.model.LinguisticFamily;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Transaction-policy evidence family (Context §10.6 ask extraction → §12 policy).
 */
@Service
public class TransactionPolicyService {

    private static final ZoneId BANK_ZONE = ZoneId.of("Asia/Kolkata");
    private static final Set<String> IMMEDIATE_DEADLINES = Set.of(
            "immediate", "now", "urgent", "turant", "jaldi", "asap", "today"
    );

    private final SentinelProperties.TransactionScoring cfg;
    private final Set<String> knownBeneficiaries;
    private final Clock clock;
    /** employeeId|yyyy-MM-dd → count of high-value asks today */
    private final ConcurrentHashMap<String, AtomicInteger> velocityByDay = new ConcurrentHashMap<>();

    @Autowired
    public TransactionPolicyService(SentinelProperties properties) {
        this(properties, Clock.system(BANK_ZONE));
    }

    TransactionPolicyService(SentinelProperties properties, Clock clock) {
        this.cfg = properties.context().transaction();
        this.knownBeneficiaries = ConcurrentHashMap.newKeySet();
        this.knownBeneficiaries.addAll(cfg.knownBeneficiaryHints());
        this.clock = clock;
    }

    public TransactionAssessment assess(FeatureFrame frame, DirectoryRecord claimedIdentity) {
        LinguisticFamily linguistic = frame != null ? frame.linguistic() : null;
        Ask ask = linguistic != null ? linguistic.ask() : null;
        if (ask == null || ask.amount() == null) {
            return new TransactionAssessment(
                    0.05, List.of("NO_ASK"), false, true, false, 0, null,
                    claimedIdentity != null ? claimedIdentity.getVerbalAuthorityLimitInr() : null
            );
        }

        double amount = ask.amount();
        double authorityLimit = claimedIdentity != null
                ? claimedIdentity.getVerbalAuthorityLimitInr()
                : Double.POSITIVE_INFINITY;
        List<String> reasons = new ArrayList<>();

        /*
         * KEY INSIGHT (Context §12 / demo Scenario 2):
         * An ask of ₹50,00,000 by voice from a role whose verbalAuthorityLimitInr is ₹0 is a
         * POLICY VIOLATION — a corporate fact — not a probabilistic acoustic suspicion.
         * Push the TRANSACTION family near 1.0 deterministically. Contextual families beat
         * acoustic ones here because policy is binary ground truth, not an inference.
         */
        boolean verbalInstruction = isVerbalInstruction(ask);
        if (verbalInstruction && amount > authorityLimit) {
            reasons.add("POLICY_VIOLATION");
            int velocity = bumpVelocity(claimedIdentity, amount);
            if (velocity >= cfg.velocityHighValueLimit()) {
                reasons.add("VELOCITY_SPIKE");
            }
            return new TransactionAssessment(
                    cfg.policyViolationScore(),
                    List.copyOf(reasons),
                    true,
                    channelPermitted(ask, claimedIdentity),
                    isBeneficiaryNovel(ask),
                    velocity,
                    amount,
                    authorityLimit
            );
        }

        boolean permitted = channelPermitted(ask, claimedIdentity);
        if (!permitted) {
            reasons.add("CHANNEL_NOT_PERMITTED");
        }

        boolean novelBeneficiary = isBeneficiaryNovel(ask);
        if (novelBeneficiary) {
            reasons.add("BENEFICIARY_NOVEL");
        } else if (ask.beneficiaryHint() != null && !ask.beneficiaryHint().isBlank()) {
            knownBeneficiaries.add(normaliseBeneficiary(ask.beneficiaryHint()));
        }

        int velocity = bumpVelocity(claimedIdentity, amount);
        if (velocity >= cfg.velocityHighValueLimit()) {
            reasons.add("VELOCITY_SPIKE");
        }

        double amountNorm = clamp01(amount / Math.max(cfg.largeAmountThresholdInr(), 1.0));
        double urgencyNorm = isImmediateDeadline(ask.deadline()) ? 1.0 : 0.0;

        /*
         * Urgency × amount is MULTIPLICATIVE, not additive:
         * fraud scripts combine a large wire with "do it now". Additive terms understate
         * the joint risk (0.4+0.4=0.8 looks like two medium signals); the product spikes
         * only when both fire together, which is the attack signature.
         */
        double urgencyAmountProduct = amountNorm * urgencyNorm;

        double channelTerm = permitted ? 0.0 : 1.0;
        double novelTerm = novelBeneficiary ? 1.0 : 0.0;
        double velocityTerm = clamp01(
                (double) Math.max(0, velocity - 1) / Math.max(1, cfg.velocityHighValueLimit())
        );

        double score = clamp01(
                cfg.weightChannelDenied() * channelTerm
                        + cfg.weightBeneficiaryNovel() * novelTerm
                        + cfg.weightVelocity() * velocityTerm
                        + cfg.weightUrgencyAmountProduct() * urgencyAmountProduct
        );

        if (urgencyAmountProduct > 0.5) {
            reasons.add("URGENCY_AMOUNT_INTERACTION");
        }
        if (reasons.isEmpty()) {
            reasons.add("WITHIN_POLICY");
        }

        return new TransactionAssessment(
                score,
                List.copyOf(reasons),
                false,
                permitted,
                novelBeneficiary,
                velocity,
                amount,
                authorityLimit
        );
    }

    /** Test helper: clear velocity counters between scenarios. */
    void resetVelocity() {
        velocityByDay.clear();
    }

    private boolean isVerbalInstruction(Ask ask) {
        String type = ask.type() == null ? "" : ask.type().trim().toUpperCase(Locale.ROOT);
        if (type.contains("SCHEDULED") || type.contains("CBS") || type.equals("STANDING_ORDER")) {
            return false;
        }
        return isImmediateDeadline(ask.deadline())
                || type.contains("WIRE")
                || type.contains("TRANSFER")
                || type.contains("IMPS")
                || type.contains("NEFT")
                || type.contains("RTGS");
    }

    private boolean isImmediateDeadline(String deadline) {
        if (deadline == null || deadline.isBlank()) {
            return false;
        }
        String d = deadline.trim().toLowerCase(Locale.ROOT);
        return IMMEDIATE_DEADLINES.contains(d) || d.contains("immediate") || d.contains("now");
    }

    private boolean channelPermitted(Ask ask, DirectoryRecord claimed) {
        if (claimed == null || claimed.getPermittedChannels() == null) {
            return true;
        }
        Set<String> permitted = Set.of(
                claimed.getPermittedChannels().toUpperCase(Locale.ROOT).split("\\s*,\\s*")
        );
        String type = ask.type() == null ? "" : ask.type().toUpperCase(Locale.ROOT);
        if (type.contains("SCHEDULED") || type.contains("CBS") || type.contains("STANDING")) {
            return permitted.contains("CBS")
                    || permitted.contains("SWIFT")
                    || permitted.contains("BOARD");
        }
        if (type.contains("WIRE") || type.contains("TRANSFER") || type.contains("IMPS")
                || type.contains("NEFT") || type.contains("RTGS")) {
            // Remote voice wires need CBS/SWIFT/BOARD — BRANCH-only is a counter channel.
            return permitted.contains("CBS")
                    || permitted.contains("SWIFT")
                    || permitted.contains("BOARD");
        }
        return true;
    }

    private boolean isBeneficiaryNovel(Ask ask) {
        if (ask.beneficiaryHint() == null || ask.beneficiaryHint().isBlank()) {
            return false;
        }
        return !knownBeneficiaries.contains(normaliseBeneficiary(ask.beneficiaryHint()));
    }

    private int bumpVelocity(DirectoryRecord claimed, double amount) {
        if (claimed == null || amount < cfg.highValueThresholdInr()) {
            return 0;
        }
        String day = LocalDate.now(clock).toString();
        String key = claimed.getEmployeeId() + "|" + day;
        return velocityByDay.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
    }

    private static String normaliseBeneficiary(String hint) {
        return hint.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }
}

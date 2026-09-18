package com.sentinelvoice.context;

import com.sentinelvoice.context.model.RelationshipAssessment;
import com.sentinelvoice.context.model.TransactionAssessment;
import com.sentinelvoice.identity.DirectoryService;
import com.sentinelvoice.identity.model.DirectoryRecord;
import com.sentinelvoice.model.Ask;
import com.sentinelvoice.model.ChannelProfile;
import com.sentinelvoice.model.FeatureFrame;
import com.sentinelvoice.model.LinguisticFamily;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class TransactionPolicyTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /** Fixed mid-morning so Scenario 1 is not polluted by off-hours. */
    private static final Clock BUSINESS_HOURS =
            Clock.fixed(Instant.parse("2026-09-18T05:30:00Z"), IST); // 11:00 IST

    @Autowired
    private RelationshipGraphService relationshipGraphService;

    @Autowired
    private TransactionPolicyService transactionPolicyService;

    @Autowired
    private DirectoryService directoryService;

    @BeforeEach
    void fixClocksAndResetVelocity() {
        ReflectionTestUtils.setField(relationshipGraphService, "clock", BUSINESS_HOURS);
        ReflectionTestUtils.setField(transactionPolicyService, "clock", BUSINESS_HOURS);
        transactionPolicyService.resetVelocity();
    }

    @Test
    void scenario2_cfoToTeller_fiftyLakh_highRelationshipAndTransaction() {
        DirectoryRecord cfo = directoryService.findByEmployeeId("EMP-10492").orElseThrow();
        assertThat(cfo.getVerbalAuthorityLimitInr()).isZero();

        RelationshipAssessment relationship = relationshipGraphService.assessEmployees(
                "EMP-10492", "EMP-50040", null
        );
        TransactionAssessment transaction = transactionPolicyService.assess(
                frameWithAsk(
                        new Ask("WIRE_TRANSFER", 5_000_000.0, "INR", "vendor account ending 4471", "immediate")
                ),
                cfo
        );

        assertThat(relationship.firstContact()).isTrue();
        assertThat(relationship.hierarchyDistance()).isEqualTo(4);
        assertThat(relationship.score()).isGreaterThan(0.8);
        assertThat(transaction.policyViolation()).isTrue();
        assertThat(transaction.score()).isGreaterThan(0.9);
        assertThat(transaction.reasonCodes()).contains("POLICY_VIOLATION");
    }

    @Test
    void scenario1_cfoToTreasury_scheduledPayment_bothLow() {
        DirectoryRecord cfo = directoryService.findByEmployeeId("EMP-10492").orElseThrow();

        RelationshipAssessment relationship = relationshipGraphService.assessEmployees(
                "EMP-10492", "EMP-20010", 400
        );
        TransactionAssessment transaction = transactionPolicyService.assess(
                frameWithAsk(
                        new Ask(
                                "SCHEDULED_PAYMENT",
                                500_000.0,
                                "INR",
                                "payroll suspense 1001",
                                "2026-09-25"
                        )
                ),
                cfo
        );

        assertThat(relationship.firstContact()).isFalse();
        assertThat(relationship.interactionCount365d()).isGreaterThan(0);
        assertThat(relationship.score()).isLessThan(0.2);
        assertThat(transaction.policyViolation()).isFalse();
        assertThat(transaction.score()).isLessThan(0.2);
    }

    @Test
    void policyViolation_isDeterministicNearOne() {
        DirectoryRecord cfo = directoryService.findByEmployeeId("EMP-10492").orElseThrow();

        TransactionAssessment first = transactionPolicyService.assess(
                frameWithAsk(new Ask("WIRE_TRANSFER", 5_000_000.0, "INR", "novel acct 9999", "immediate")),
                cfo
        );
        TransactionAssessment second = transactionPolicyService.assess(
                frameWithAsk(new Ask("WIRE_TRANSFER", 1.0, "INR", "another novel", "now")),
                cfo
        );

        assertThat(first.policyViolation()).isTrue();
        assertThat(second.policyViolation()).isTrue();
        assertThat(first.score()).isEqualTo(0.98);
        assertThat(second.score()).isEqualTo(0.98);
        assertThat(first.reasonCodes()).contains("POLICY_VIOLATION");
        assertThat(second.reasonCodes()).contains("POLICY_VIOLATION");
    }

    @Test
    void tellerWithinAuthority_doesNotTripPolicyGate() {
        DirectoryRecord teller = directoryService.findByEmployeeId("EMP-50040").orElseThrow();
        TransactionAssessment assessment = transactionPolicyService.assess(
                frameWithAsk(new Ask("BRANCH_CASH", 50_000.0, "INR", "payroll suspense 1001", "today")),
                teller
        );
        // BRANCH_CASH + amount under limit — not a CXO verbal-wire policy breach.
        assertThat(assessment.policyViolation()).isFalse();
        assertThat(assessment.score()).isLessThan(0.98);
    }

    private static FeatureFrame frameWithAsk(Ask ask) {
        return new FeatureFrame(
                "sentinelvoice.FeatureFrame/1",
                "ctx-test",
                1,
                0L,
                500L,
                ChannelProfile.WEBRTC_WIDEBAND,
                true,
                10_000L,
                new FeatureFrame.VoiceFamily(true, 0.2, "test", 0.9),
                new FeatureFrame.ChannelFamily(true, 200.0, true, 0.2, 0.4, 0.0),
                new FeatureFrame.ProsodyFamily(
                        true, 120.0, 10.0, 0.8, 3.0, 18.0, 12.0, 0.05, 4.0, 0.2),
                new FeatureFrame.SpeakerFamily(false, null, null, null, null),
                new FeatureFrame.WatermarkFamily(false, null, null, null, null),
                new LinguisticFamily(
                        true, 0L, "en", 0.3, 0.2, 0.2, 0.1,
                        true, ask, "Rajesh Kumar", "CFO", ""),
                new FeatureFrame.LatencyMs(40, 180)
        );
    }
}

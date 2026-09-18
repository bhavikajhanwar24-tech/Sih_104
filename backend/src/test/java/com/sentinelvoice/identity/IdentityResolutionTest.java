package com.sentinelvoice.identity;

import com.sentinelvoice.fusion.FusionContext;
import com.sentinelvoice.fusion.ReasonCode;
import com.sentinelvoice.fusion.ReasonGenerator;
import com.sentinelvoice.identity.model.DirectoryRecord;
import com.sentinelvoice.identity.model.IdentityAssessment;
import com.sentinelvoice.model.Ask;
import com.sentinelvoice.model.CallSession;
import com.sentinelvoice.model.ChannelProfile;
import com.sentinelvoice.model.FeatureFrame;
import com.sentinelvoice.model.LinguisticFamily;
import com.sentinelvoice.repository.DirectoryRecordRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class IdentityResolutionTest {

    @Autowired
    private IdentityResolutionService identityResolutionService;

    @Autowired
    private DirectoryService directoryService;

    @Autowired
    private DirectoryRecordRepository directoryRecordRepository;

    @Autowired
    private TrunkClassifier trunkClassifier;

    @Autowired
    private ReasonGenerator reasonGenerator;

    @Test
    void seededDirectoryLoadsOnStartup() {
        assertThat(directoryRecordRepository.count()).isGreaterThanOrEqualTo(15);
        Optional<DirectoryRecord> cfo = directoryService.findByClaimedIdentity("Rajesh Kumar", "CFO");
        assertThat(cfo).isPresent();
        assertThat(cfo.get().getEmployeeId()).isEqualTo("EMP-10492");
        assertThat(cfo.get().getVerbalAuthorityLimitInr()).isZero();
        assertThat(cfo.get().getHierarchyLevel()).isEqualTo(2);

        Optional<DirectoryRecord> ceo = directoryService.findByClaimedIdentity("Arvind Mehta", "CEO");
        assertThat(ceo).isPresent();
        assertThat(ceo.get().getVerbalAuthorityLimitInr()).isZero();

        Optional<DirectoryRecord> teller = directoryService.findByClaimedIdentity("Sunita Rao", null);
        assertThat(teller).isPresent();
        assertThat(teller.get().getVerbalAuthorityLimitInr()).isEqualTo(100_000.0);
        assertThat(teller.get().getHierarchyLevel()).isEqualTo(6);
    }

    @Test
    void unregisteredSipClaimingCfo_setsMismatchAndCriticalReason() {
        CallSession session = new CallSession(
                "id-unreg",
                "+91-22-4000-1234",
                "EMP-30020",
                ChannelProfile.WEBRTC_WIDEBAND,
                "cfo-fraud"
        );
        assertThat(trunkClassifier.classify(session.getCallerId()))
                .isEqualTo(TrunkClassifier.TrunkProvenance.UNREGISTERED_SIP);

        FeatureFrame frame = claimFrame("Rajesh Kumar", "CFO", 0.41, 0.71);
        IdentityAssessment assessment = identityResolutionService.resolve(session, frame);

        assertThat(assessment.cliVsClaimMismatch()).isTrue();
        assertThat(assessment.cliTrunk()).isEqualTo("UNREGISTERED_SIP");
        assertThat(assessment.directoryMatch()).isNull();
        assertThat(assessment.directoryRecordForClaim()).isNotNull();
        assertThat(assessment.directoryRecordForClaim().get("employeeId")).isEqualTo("EMP-10492");
        assertThat(assessment.criticalReasons()).contains(ReasonCode.CLI_CLAIM_MISMATCH);
        assertThat(assessment.verbalAuthorityLimitInr()).isZero();
        assertThat(assessment.identityRiskScore()).isGreaterThanOrEqualTo(0.90);

        FusionContext ctx = FusionContext.withIdentity(frame, 0.9, true, 0.2, true, assessment);
        List<ReasonGenerator.GeneratedReason> reasons = reasonGenerator.generate(ctx);
        assertThat(reasons)
                .anyMatch(r -> r.code() == ReasonCode.CLI_CLAIM_MISMATCH
                        && r.severity() == ReasonCode.Severity.CRITICAL);
    }

    /**
     * Context §12 cosine × spoof matrix — all five verdicts.
     */
    @ParameterizedTest(name = "cosine={0}, spoof={1}, enrolled={2}, channelMismatch={3} → {4}")
    @CsvSource({
            "0.41, 0.20, true,  false, IMPERSONATION_HUMAN",
            "0.41, 0.75, true,  false, IMPERSONATION_SYNTHETIC",
            "0.88, 0.75, true,  false, IMPERSONATION_SYNTHETIC",
            "0.88, 0.15, true,  false, VERIFIED",
            "0.88, 0.15, true,  true,  INCONCLUSIVE",
            "0.60, 0.20, true,  false, INCONCLUSIVE",
            ",     0.20, true,  false, INCONCLUSIVE",
            "0.88, ,     true,  false, INCONCLUSIVE",
            "0.88, 0.15, false, false, INCONCLUSIVE"
    })
    void verdictTable_coversAllFiveCombinations(
            Double cosine,
            Double spoof,
            boolean enrolled,
            boolean channelMismatch,
            IdentityVerdict expected
    ) {
        assertThat(identityResolutionService.verdictOf(cosine, spoof, enrolled, channelMismatch))
                .isEqualTo(expected);
    }

    @Test
    void identityRiskFoldsIntoRelationshipFamilyViaMax() {
        CallSession session = new CallSession(
                "id-fold",
                "+91-22-4000-1234",
                "treasury-desk",
                ChannelProfile.WEBRTC_WIDEBAND,
                null
        );
        FeatureFrame frame = claimFrame("Rajesh Kumar", "CFO", 0.41, 0.80);
        IdentityAssessment identity = identityResolutionService.resolve(session, frame);

        FusionContext ctx = FusionContext.withIdentity(
                frame,
                0.5,
                true,
                0.11, // benign graph score
                true,
                identity
        );

        assertThat(ctx.relationshipAvailable()).isTrue();
        assertThat(ctx.relationshipScore()).isEqualTo(identity.identityRiskScore());
        assertThat(ctx.cliVsClaimMismatch()).isTrue();
        assertThat(ctx.verbalAuthorityLimit()).isZero();
    }

    private static FeatureFrame claimFrame(
            String claimedName,
            String claimedRole,
            double cosine,
            double spoof
    ) {
        return new FeatureFrame(
                "sentinelvoice.FeatureFrame/1",
                "call-test",
                1,
                0L,
                500L,
                ChannelProfile.WEBRTC_WIDEBAND,
                true,
                20_000L,
                new FeatureFrame.VoiceFamily(true, spoof, "test", 0.9),
                new FeatureFrame.ChannelFamily(true, 18.0, false, 0.66, 0.9, 0.0),
                new FeatureFrame.ProsodyFamily(
                        true, 120.0, 4.0, 0.08, 2.0, 25.0, 0.0, 0.0, 5.0, 0.7),
                new FeatureFrame.SpeakerFamily(true, "emb-1", "EMP-10492", cosine, 0.18),
                new FeatureFrame.WatermarkFamily(false, null, null, null, null),
                new LinguisticFamily(
                        true,
                        0L,
                        "en",
                        0.9,
                        0.9,
                        0.9,
                        0.5,
                        true,
                        new Ask("WIRE_TRANSFER", 5_000_000.0, "INR", "x", "now"),
                        claimedName,
                        claimedRole,
                        ""
                ),
                new FeatureFrame.LatencyMs(50, 200)
        );
    }
}

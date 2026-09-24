package com.sentinelvoice.scenario.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.sentinelvoice.model.InterventionLevel;

import java.util.List;

/**
 * F18 scenario fixture (JSON under classpath:/scenarios/).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Scenario(
        String id,
        String title,
        String description,
        List<TrajectoryPoint> expectedTrajectory,
        String channelProfile,
        Party caller,
        Party callee,
        List<DirectoryOverride> directoryOverrides,
        List<RelationshipEdgeSeed> relationshipEdges,
        List<CrossChannelSeed> crossChannelEvents,
        AudioSpec audio,
        TransactionSeed transactionSeed,
        InterventionLevel expectedFinalLevel,
        InterventionLevel mustNotExceed,
        Boolean seniorShield,
        ChallengeHint challenge,
        String teachingPoint,
        String attackerPersona,
        String spoofedCli,
        String claimedIdentity,
        String spokenScript,
        List<String> expectedPolicyRules
) {
    public Scenario {
        expectedTrajectory = expectedTrajectory == null ? List.of() : List.copyOf(expectedTrajectory);
        directoryOverrides = directoryOverrides == null ? List.of() : List.copyOf(directoryOverrides);
        relationshipEdges = relationshipEdges == null ? List.of() : List.copyOf(relationshipEdges);
        crossChannelEvents = crossChannelEvents == null ? List.of() : List.copyOf(crossChannelEvents);
        expectedPolicyRules = expectedPolicyRules == null ? List.of() : List.copyOf(expectedPolicyRules);
    }

    public record TrajectoryPoint(
            double tSec,
            double risk,
            Double acoustic,
            Double contextual,
            InterventionLevel level,
            boolean corroboration,
            Boolean confirmL5
    ) {
        public boolean isConfirmL5() {
            return Boolean.TRUE.equals(confirmL5);
        }
    }

    public record Party(
            String cli,
            String trunkClass,
            String claimedIdentity,
            String claimedRole,
            String employeeId,
            String name
    ) {
    }

    public record DirectoryOverride(
            String employeeId,
            String name,
            String role,
            String department,
            String primaryCli,
            String extension,
            Double verbalAuthorityLimitInr,
            String permittedChannels,
            String presenceStatus,
            String calendarLocation,
            String managerEmployeeId,
            Integer hierarchyLevel,
            Boolean passportEnrolled
    ) {
    }

    public record RelationshipEdgeSeed(
            String callerEmployeeId,
            String calleeEmployeeId,
            int interactionCount,
            int typicalHourOfDay,
            int typicalDurationSec
    ) {
    }

    public record CrossChannelSeed(
            String id,
            String channel,
            String targetEmployeeId,
            double occurredHoursAgo,
            String severity,
            String indicator,
            String campaignId,
            String description
    ) {
    }

    public record AudioSpec(
            String source,
            Boolean liveAllowed,
            String codecProfileHint
    ) {
        public boolean isLiveAllowed() {
            return liveAllowed == null || Boolean.TRUE.equals(liveAllowed);
        }
    }

    public record ChallengeHint(
            String phraseHint,
            Integer expectedLatencyMs
    ) {
    }

    public record TransactionSeed(
            Double amountInr,
            String currency,
            String beneficiaryHint,
            String askType,
            String type,
            String deadline,
            Double urgency,
            Double secrecy,
            Double authorityInvocation,
            String language
    ) {
        public String effectiveAskType() {
            if (askType != null && !askType.isBlank()) {
                return askType;
            }
            return type;
        }
    }
}

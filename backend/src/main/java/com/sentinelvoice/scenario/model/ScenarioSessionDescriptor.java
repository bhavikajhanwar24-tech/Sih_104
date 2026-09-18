package com.sentinelvoice.scenario.model;

import com.sentinelvoice.model.ChannelProfile;
import com.sentinelvoice.model.InterventionLevel;

import java.util.List;
import java.util.Map;

/**
 * Session descriptor returned by {@code POST /api/v1/scenario/{id}/load}.
 */
public record ScenarioSessionDescriptor(
        String scenarioId,
        String title,
        String sessionId,
        String mode,
        ChannelProfile channelProfile,
        String callerCli,
        String calleeCli,
        String claimedIdentity,
        String claimedRole,
        String audioSource,
        String ingestWsUrl,
        String replayCommand,
        InterventionLevel expectedFinalLevel,
        InterventionLevel mustNotExceed,
        boolean seniorShield,
        String teachingPoint,
        List<Map<String, Object>> expectedTrajectory
) {
}

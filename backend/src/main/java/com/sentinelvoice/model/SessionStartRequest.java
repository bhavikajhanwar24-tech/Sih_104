package com.sentinelvoice.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Frozen contract {@code sentinelvoice.SessionStartRequest/1}. See docs/contracts/SessionStartRequest.schema.json.
 */
public record SessionStartRequest(
        @NotBlank
        @Pattern(regexp = "sentinelvoice\\.SessionStartRequest/1")
        String schema,
        @Size(min = 1) String sessionId,
        @NotBlank String callerId,
        @NotBlank String calleeId,
        @NotNull ChannelProfile channelProfile,
        @Size(min = 1) String scenarioId
) {
}

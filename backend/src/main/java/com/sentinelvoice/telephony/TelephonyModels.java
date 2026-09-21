package com.sentinelvoice.telephony;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * F10 telephony DTOs. Decision documented in docs/v2/features/F10.md:
 * PJSIP REALTIME via schema {@code asterisk}; app tables RLS-isolated.
 */
public final class TelephonyModels {

    private TelephonyModels() {
    }

    public record SipEndpointView(
            UUID id,
            UUID tenantId,
            UUID employeeId,
            String extension,
            String username,
            String status,
            Instant lastRegisteredAt,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    /** Returned only on create / resetPassword — plaintext password once. */
    public record SipEndpointProvisionResult(
            SipEndpointView endpoint,
            String plaintextPassword
    ) {
    }

    public record TrunkView(
            UUID id,
            UUID tenantId,
            String name,
            String type,
            List<String> cliPrefixes,
            Map<String, Object> providerMetadata,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record CallSessionView(
            UUID id,
            UUID tenantId,
            Instant startedAt,
            Instant endedAt,
            String callerNumber,
            String calleeNumber,
            UUID callerEmployeeId,
            UUID calleeEmployeeId,
            String direction,
            UUID trunkId,
            Integer snapshotPolicyVersion,
            Integer snapshotFusionVersion,
            Integer snapshotResponsePlanVersion,
            Double peakScore,
            String peakLevel,
            String finalOutcome,
            String sipCallId,
            UUID svSessionUuid
    ) {
    }

    /** Live Calls row — call_sessions + directory names. */
    public record CallSessionListItem(
            UUID id,
            UUID tenantId,
            Instant startedAt,
            Instant endedAt,
            String callerNumber,
            String calleeNumber,
            UUID callerEmployeeId,
            UUID calleeEmployeeId,
            String callerName,
            String callerTitle,
            String calleeName,
            String calleeTitle,
            String direction,
            Double peakScore,
            String peakLevel,
            String finalOutcome,
            String sipCallId,
            UUID svSessionUuid
    ) {
        public boolean active() {
            return endedAt == null;
        }
    }

    public record ExtensionResolveResult(
            UUID tenantId,
            UUID employeeId,
            UUID endpointId,
            String username,
            String extension,
            String status
    ) {
    }

    public enum NumberClass {
        INTERNAL_EXT,
        KNOWN_MOBILE,
        EXTERNAL_UNKNOWN,
        SUSPECT_TRUNK
    }

    public record NumberClassifyResult(
            NumberClass classification,
            UUID tenantId,
            UUID employeeId,
            UUID endpointId,
            UUID trunkId,
            String matchedPrefix
    ) {
    }
}

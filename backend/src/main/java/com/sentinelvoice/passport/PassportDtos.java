package com.sentinelvoice.passport;

import com.sentinelvoice.identity.IdentityVerdict;
import com.sentinelvoice.model.ChannelProfile;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class PassportDtos {

    private PassportDtos() {
    }

    public record ConsentRequest(
            String employeeId,
            String purpose,
            String noticeVersion,
            String grantedBy,
            String method
    ) {
    }

    public record EnrolRequest(
            String employeeId,
            ChannelProfile channelProfile,
            String audioRef
    ) {
    }

    public record EnrolResponse(
            String employeeId,
            List<String> profileIds,
            List<String> channelProfiles,
            String embeddingModelId,
            int auditBlockIndex
    ) {
    }

    public record DeletionCertificate(
            String profileId,
            Instant erasedAt,
            String tombstoneHash,
            int auditBlockIndex
    ) {
    }

    public record VerifyResult(
            Double cosine,
            IdentityVerdict verdict,
            String reason,
            ChannelProfile channelProfile
    ) {
    }

    /** Metadata-only view — never includes the embedding vector (DPDP §11). */
    public record PassportMetadata(
            String profileId,
            String employeeId,
            ChannelProfile channelProfile,
            String embeddingModelId,
            Instant enrolledAt,
            Instant lastVerifiedAt,
            boolean active,
            List<Map<String, Object>> processingLog
    ) {
    }
}

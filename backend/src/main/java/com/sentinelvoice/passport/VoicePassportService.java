package com.sentinelvoice.passport;

import com.sentinelvoice.compliance.ComplianceService;
import com.sentinelvoice.identity.IdentityVerdict;
import com.sentinelvoice.model.ChannelProfile;
import com.sentinelvoice.passport.model.ConsentRecord;
import com.sentinelvoice.security.TenantContext;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * F15 — Voice Passport v2: tenant-scoped encrypted embeddings via {@link ComplianceService}.
 * Legacy v1 DTO methods remain for callers; new work uses {@code /api/v2/compliance/passports/**}.
 */
@Service
public class VoicePassportService {

    private final ComplianceService complianceService;

    public VoicePassportService(ComplianceService complianceService) {
        this.complianceService = complianceService;
    }

    public ConsentRecord grantConsent(PassportDtos.ConsentRequest request) {
        TenantContext ctx = TenantContext.require();
        UUID employeeId = UUID.fromString(request.employeeId());
        String purpose = mapPurpose(request.purpose());
        complianceService.upsertConsent(
                ctx.tenantId(), ctx.userId(), employeeId, purpose, "GRANTED",
                request.method() == null ? "ADMIN" : request.method()
        );
        ConsentRecord r = new ConsentRecord();
        r.setId(0L);
        r.setEmployeeId(request.employeeId());
        r.setPurpose(purpose);
        r.setNoticeVersion(request.noticeVersion());
        r.setGrantedAt(Instant.now());
        return r;
    }

    public PassportDtos.EnrolResponse enrol(PassportDtos.EnrolRequest request) {
        TenantContext ctx = TenantContext.require();
        UUID employeeId = UUID.fromString(request.employeeId());
        Map<String, Object> meta = complianceService.enrolPassport(
                ctx.tenantId(), ctx.userId(), employeeId, request.audioRef(), null);
        return new PassportDtos.EnrolResponse(
                employeeId.toString(),
                List.of(String.valueOf(meta.getOrDefault("id", employeeId))),
                List.of(request.channelProfile() == null ? "WEBRTC_WIDEBAND" : request.channelProfile().name()),
                String.valueOf(meta.getOrDefault("modelVersion", "unknown")),
                0
        );
    }

    public PassportDtos.DeletionCertificate erase(String profileId) {
        TenantContext ctx = TenantContext.require();
        UUID employeeId = UUID.fromString(profileId);
        Map<String, Object> out = complianceService.deletePassport(ctx.tenantId(), ctx.userId(), employeeId);
        return new PassportDtos.DeletionCertificate(
                profileId,
                Instant.now(),
                String.valueOf(out.getOrDefault("employeeId", profileId)),
                0
        );
    }

    public PassportDtos.PassportMetadata getMetadata(String profileId) {
        TenantContext ctx = TenantContext.require();
        UUID employeeId = UUID.fromString(profileId);
        Map<String, Object> meta = complianceService.passportMeta(ctx.tenantId(), employeeId);
        boolean enrolled = Boolean.TRUE.equals(meta.get("enrolled"));
        Instant enrolledAt = null;
        if (meta.get("createdAt") instanceof String s && !s.isBlank()) {
            enrolledAt = Instant.parse(s);
        }
        return new PassportDtos.PassportMetadata(
                profileId,
                employeeId.toString(),
                ChannelProfile.WEBRTC_WIDEBAND,
                meta.get("modelVersion") == null ? null : String.valueOf(meta.get("modelVersion")),
                enrolledAt,
                null,
                enrolled,
                List.of()
        );
    }

    public ConsentRecord withdrawConsent(long id) {
        throw new UnsupportedOperationException("Use POST /api/v2/compliance/consents with status=WITHDRAWN");
    }

    public List<ConsentRecord> listConsents() {
        throw new UnsupportedOperationException("Use GET /api/v2/compliance/consents");
    }

    public PassportDtos.VerifyResult verify(
            String employeeId,
            float[] embedding,
            ChannelProfile profile
    ) {
        TenantContext ctx = TenantContext.require();
        UUID eid = UUID.fromString(employeeId);
        float[] enrolled = complianceService.loadDecryptedEmbedding(ctx.tenantId(), eid);
        if (enrolled == null) {
            return new PassportDtos.VerifyResult(null, IdentityVerdict.INCONCLUSIVE, "NO_PASSPORT", profile);
        }
        double cos = EmbeddingCodec.cosine(embedding, enrolled);
        IdentityVerdict verdict = cos >= 0.75 ? IdentityVerdict.VERIFIED
                : cos >= 0.55 ? IdentityVerdict.INCONCLUSIVE
                : IdentityVerdict.IMPERSONATION_HUMAN;
        return new PassportDtos.VerifyResult(cos, verdict, null, profile);
    }

    private static String mapPurpose(String raw) {
        if (raw == null) return "VOICE_PASSPORT";
        String u = raw.trim().toUpperCase();
        if (u.contains("MONITOR")) return "MONITORING";
        return "VOICE_PASSPORT";
    }
}

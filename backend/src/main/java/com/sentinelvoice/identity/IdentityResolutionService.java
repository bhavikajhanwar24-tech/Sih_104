package com.sentinelvoice.identity;

import com.sentinelvoice.directory.DirectoryMatch;
import com.sentinelvoice.directory.DirectoryService;
import com.sentinelvoice.fusion.ReasonCode;
import com.sentinelvoice.identity.model.IdentityAssessment;
import com.sentinelvoice.model.CallSession;
import com.sentinelvoice.model.FeatureFrame;
import com.sentinelvoice.security.TenantContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Identity pipeline wired to F4 DirectoryService.resolve.
 * <p>
 * REMOVE IN F7: {@link IdentityAssessment} temporary adapter — telemetry {@code identity}
 * block will be rebuilt from {@link DirectoryMatch} directly.
 */
@Service
public class IdentityResolutionService {

    private final DirectoryService directoryService;

    public IdentityResolutionService(DirectoryService directoryService) {
        this.directoryService = directoryService;
    }

    public IdentityAssessment resolve(CallSession session, FeatureFrame featureFrame) {
        UUID tenantId = session.getTenantId();
        if (tenantId == null) {
            TenantContext ctx = TenantContext.get();
            tenantId = ctx == null ? null : ctx.tenantId();
        }
        String caller = session.getCallerId();
        String claimedName = null;
        String claimedRole = null;
        if (featureFrame != null && featureFrame.linguistic() != null) {
            claimedName = featureFrame.linguistic().claimedIdentity();
            claimedRole = featureFrame.linguistic().claimedRole();
        }

        DirectoryMatch match = tenantId == null
                ? DirectoryMatch.none(DirectoryMatch.NumberProvenance.EXTERNAL_UNKNOWN)
                : directoryService.resolve(tenantId, caller, claimedName, claimedRole);

        return toIdentityAssessment(caller, claimedName, claimedRole, match);
    }

    /** REMOVE IN F7 */
    static IdentityAssessment toIdentityAssessment(
            String cli, String claimedName, String claimedRole, DirectoryMatch match
    ) {
        Map<String, Object> directoryMatch = new LinkedHashMap<>();
        directoryMatch.put("matchType", match.matchType().name());
        directoryMatch.put("confidence", match.confidence());
        directoryMatch.put("numberProvenance", match.numberProvenance().name());
        if (match.matchedEmployeeId() != null) {
            directoryMatch.put("employeeId", match.matchedEmployeeId().toString());
            directoryMatch.put("fullName", match.fullName());
            directoryMatch.put("roleKey", match.roleKey());
            directoryMatch.put("status", match.status());
        }

        Map<String, Object> claimRecord = null;
        if (match.matchedEmployeeId() != null && match.matchType() != DirectoryMatch.MatchType.NONE) {
            claimRecord = new LinkedHashMap<>();
            claimRecord.put("employeeId", match.matchedEmployeeId().toString());
            claimRecord.put("name", match.fullName());
            claimRecord.put("role", match.roleKey());
            claimRecord.put("department", match.departmentName());
            claimRecord.put("status", match.status());
        }

        boolean mismatch = false;
        if (claimedRole != null && match.roleKey() != null
                && (match.matchType() == DirectoryMatch.MatchType.NUMBER_EXACT
                || match.matchType() == DirectoryMatch.MatchType.EXT_EXACT)) {
            String a = com.sentinelvoice.directory.NameSimilarity.normaliseRole(claimedRole);
            String b = com.sentinelvoice.directory.NameSimilarity.normaliseRole(match.roleKey());
            mismatch = !a.equals(b) && !a.isBlank() && !b.isBlank();
        }

        IdentityAssessment.PresenceConflict presence = null;
        if (match.status() != null && !"ACTIVE".equals(match.status())
                && match.matchType() != DirectoryMatch.MatchType.NONE) {
            presence = new IdentityAssessment.PresenceConflict(match.status(), "CLAIMED_PRESENT");
        }

        List<ReasonCode> critical = new ArrayList<>();
        double risk = 0.05;
        if (match.matchType() == DirectoryMatch.MatchType.NONE && claimedName != null) {
            risk = 0.55;
        } else if (match.matchType() == DirectoryMatch.MatchType.NAME_FUZZY) {
            risk = 0.35;
        } else if (mismatch) {
            risk = 0.7;
        } else if (presence != null) {
            risk = 0.65;
        } else if (match.numberProvenance() == DirectoryMatch.NumberProvenance.SUSPECT_TRUNK) {
            risk = 0.4;
        } else if (match.numberProvenance() == DirectoryMatch.NumberProvenance.EXTERNAL_UNKNOWN) {
            risk = 0.25;
        }

        double authorityMax = match.authority().stream()
                .map(a -> a.get("maxAmountInr"))
                .filter(v -> v instanceof Number)
                .mapToDouble(v -> ((Number) v).doubleValue())
                .max()
                .orElse(0.0);

        return new IdentityAssessment(
                cli,
                match.numberProvenance().name(),
                directoryMatch,
                claimedName,
                claimedRole,
                claimRecord,
                mismatch,
                new IdentityAssessment.VoicePassport(false, null, IdentityVerdict.INCONCLUSIVE),
                presence,
                risk,
                authorityMax > 0 ? authorityMax : null,
                critical
        );
    }
}

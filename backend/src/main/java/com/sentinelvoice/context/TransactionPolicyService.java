package com.sentinelvoice.context;

import com.sentinelvoice.context.model.TransactionAssessment;
import com.sentinelvoice.identity.model.DirectoryRecord;
import com.sentinelvoice.model.FeatureFrame;
import com.sentinelvoice.policy.engine.PolicyRuntimeService;
import com.sentinelvoice.policy.engine.RuleEvaluation;
import com.sentinelvoice.security.TenantContext;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * TRANSACTION family score from the F7 runtime rule engine (ACTIVE ACCEPTED/EDITED rules only).
 */
@Service
public class TransactionPolicyService {

    private final PolicyRuntimeService policyRuntimeService;

    public TransactionPolicyService(PolicyRuntimeService policyRuntimeService) {
        this.policyRuntimeService = policyRuntimeService;
    }

    public TransactionAssessment assess(FeatureFrame frame, DirectoryRecord claimedIdentity) {
        UUID tenantId = TenantContext.get() == null ? null : TenantContext.require().tenantId();
        if (tenantId == null) {
            return new TransactionAssessment(
                    0.0, List.of("NO_TENANT"), false, true, false, 0, null, null
            );
        }
        RuleEvaluation eval = policyRuntimeService.evaluateLive(
                tenantId, null, frame, null, null
        );
        TransactionAssessment base = policyRuntimeService.toTransactionAssessment(eval);
        Double authority = claimedIdentity != null ? claimedIdentity.getVerbalAuthorityLimitInr() : null;
        return new TransactionAssessment(
                base.score(),
                base.reasonCodes(),
                base.policyViolation(),
                base.channelPermitted(),
                base.beneficiaryNovel(),
                base.velocityCountToday(),
                base.amountInr(),
                authority
        );
    }
}

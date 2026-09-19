package com.sentinelvoice.context;

import com.sentinelvoice.context.model.TransactionAssessment;
import com.sentinelvoice.identity.model.DirectoryRecord;
import com.sentinelvoice.model.FeatureFrame;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * F6: v1 hardcoded TransactionPolicy removed. Transaction family score is neutral until F7
 * evaluates ACTIVE tenant policy sets. Callers keep compiling.
 */
@Service
public class TransactionPolicyService {

    public TransactionAssessment assess(FeatureFrame frame, DirectoryRecord claimedIdentity) {
        Double authority = claimedIdentity != null ? claimedIdentity.getVerbalAuthorityLimitInr() : null;
        return new TransactionAssessment(
                0.05,
                List.of("POLICY_ENGINE_PENDING_F7"),
                false,
                true,
                false,
                0,
                null,
                authority
        );
    }
}

package com.sentinelvoice.identity;

import com.sentinelvoice.identity.model.IdentityAssessment;
import com.sentinelvoice.model.CallSession;
import com.sentinelvoice.model.FeatureFrame;
import org.springframework.stereotype.Service;

/**
 * Identity pipeline — directory / passport persistence returns in F4 / F12.
 * Until then this service fails fast (no silent empty assessments).
 */
@Service
public class IdentityResolutionService {

    private static final String MSG = "re-implemented in F4";

    public IdentityResolutionService(DirectoryService directoryService, TrunkClassifier trunkClassifier) {
        // Dependencies retained for Spring wiring / future F4 constructor shape.
    }

    public IdentityAssessment resolve(CallSession session, FeatureFrame featureFrame) {
        throw new UnsupportedOperationException(MSG);
    }
}

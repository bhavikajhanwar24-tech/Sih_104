package com.sentinelvoice.passport;

import com.sentinelvoice.audit.AuditLedgerService;
import com.sentinelvoice.model.ChannelProfile;
import com.sentinelvoice.passport.model.ConsentRecord;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Voice Passport — persistence re-implemented in F12.
 */
@Service
public class VoicePassportService {

    private static final String MSG = "re-implemented in F12";

    private final AuditLedgerService auditLedgerService;

    public VoicePassportService(AuditLedgerService auditLedgerService) {
        this.auditLedgerService = auditLedgerService;
    }

    public ConsentRecord grantConsent(PassportDtos.ConsentRequest request) {
        throw new UnsupportedOperationException(MSG);
    }

    public PassportDtos.EnrolResponse enrol(PassportDtos.EnrolRequest request) {
        throw new UnsupportedOperationException(MSG);
    }

    public PassportDtos.DeletionCertificate erase(String profileId) {
        throw new UnsupportedOperationException(MSG);
    }

    public PassportDtos.PassportMetadata getMetadata(String profileId) {
        throw new UnsupportedOperationException(MSG);
    }

    public ConsentRecord withdrawConsent(long id) {
        throw new UnsupportedOperationException(MSG);
    }

    public List<ConsentRecord> listConsents() {
        throw new UnsupportedOperationException(MSG);
    }

    public PassportDtos.VerifyResult verify(
            String employeeId,
            float[] embedding,
            ChannelProfile profile
    ) {
        throw new UnsupportedOperationException(MSG);
    }

    AuditLedgerService auditLedger() {
        return auditLedgerService;
    }
}

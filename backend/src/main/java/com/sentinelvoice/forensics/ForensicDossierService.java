package com.sentinelvoice.forensics;

import com.sentinelvoice.forensics.model.ForensicDossier;
import org.springframework.stereotype.Service;

/**
 * Forensic dossier assembly — re-implemented in F15.
 */
@Service
public class ForensicDossierService {

    private static final String MSG = "re-implemented in F15";

    public ForensicDossier assembleJson(String sessionId, String generatedBy) {
        throw new UnsupportedOperationException(MSG);
    }

    public byte[] renderPdf(String sessionId, String generatedBy) {
        throw new UnsupportedOperationException(MSG);
    }

    public static String documentSha256(byte[] pdf) {
        throw new UnsupportedOperationException(MSG);
    }
}

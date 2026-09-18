package com.sentinelvoice.forensics.model;

/**
 * One explainability / evidence row for the forensic dossier evidence table.
 */
public record EvidenceItem(
        String reasonCode,
        String severity,
        String family,
        long observedAtEpochMs,
        String measuredValue,
        String humanBaseline,
        String narrative
) {
}

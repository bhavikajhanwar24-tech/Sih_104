package com.sentinelvoice.actuation;

/**
 * Logical intervention actions and {@link CallControlPort} capability markers (Context §11.6).
 */
public enum ActuationAction {

    // --- Level-mapped fired actions ---
    UI_BANNER,
    TXN_APPROVE_LOCKED,
    OOB_MFA_SENT,
    CALL_HELD,
    SUPERVISOR_BRIDGED,
    CALL_TERMINATED,
    BENEFICIARY_FROZEN,
    DOSSIER_GENERATED,

    // --- CallControlPort capabilities ---
    HOLD,
    UNHOLD,
    WHISPER,
    ANNOUNCE,
    BRIDGE_SUPERVISOR,
    TERMINATE
}

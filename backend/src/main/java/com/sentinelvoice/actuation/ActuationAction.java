package com.sentinelvoice.actuation;

/**
 * Intervention actuation outcomes / capability tokens (Context §9.5 / P7.3).
 * Ladder mapping:
 * <ul>
 *   <li>L2 → {@link #UI_BANNER}</li>
 *   <li>L3 → {@link #TXN_APPROVE_LOCKED}, {@link #OOB_MFA_SENT}</li>
 *   <li>L4 → {@link #CALL_HELD}, {@link #SUPERVISOR_BRIDGED}, {@link #TXN_APPROVE_LOCKED}
 *       (plus {@link #ANNOUNCE_HOLD}, {@link #WHISPER_WARNING} for audible SIP demo)</li>
 *   <li>L5 → {@link #CALL_TERMINATED}, {@link #BENEFICIARY_FROZEN}, {@link #DOSSIER_GENERATED}</li>
 * </ul>
 */
public enum ActuationAction {
    UI_BANNER,
    TXN_APPROVE_LOCKED,
    OOB_MFA_SENT,
    CALL_HELD,
    SUPERVISOR_BRIDGED,
    CALL_TERMINATED,
    BENEFICIARY_FROZEN,
    DOSSIER_GENERATED,
    WHISPER_WARNING,
    ANNOUNCE_HOLD,
    /** Capability: adapter can place a channel on hold. */
    HOLD,
    /** Capability: adapter can release hold. */
    UNHOLD,
    /** Capability: adapter can whisper to agent only. */
    WHISPER,
    /** Capability: adapter can play an announcement to the channel. */
    ANNOUNCE,
    /** Capability: adapter can bridge a supervisor endpoint. */
    BRIDGE_SUPERVISOR,
    /** Capability: adapter can hang up the channel. */
    TERMINATE
}

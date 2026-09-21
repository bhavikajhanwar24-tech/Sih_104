package com.sentinelvoice.response;

/**
 * Fixed action keys for the F9 response catalogue.
 */
public enum ResponseActionKey {
    LOG_ONLY,
    OPERATOR_ADVISORY,
    WHISPER_WARNING,
    REQUIRE_CALLBACK_VERIFICATION,
    REQUIRE_LIVENESS_CHALLENGE,
    LOCK_APPROVAL,
    SEND_OOB_MFA,
    NOTIFY_SUPERVISOR,
    BRIDGE_SUPERVISOR,
    HOLD_CALL,
    TERMINATE_CALL,
    FREEZE_BENEFICIARY,
    CREATE_INCIDENT,
    CUSTOM_WEBHOOK;

    public static ResponseActionKey parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("action key required");
        }
        return ResponseActionKey.valueOf(raw.trim().toUpperCase());
    }
}

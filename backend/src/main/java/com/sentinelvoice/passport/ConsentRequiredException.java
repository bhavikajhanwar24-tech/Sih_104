package com.sentinelvoice.passport;

/**
 * Thrown when enrolment is attempted without GRANTED VOICE_PASSPORT consent (F15).
 */
public class ConsentRequiredException extends RuntimeException {

    public ConsentRequiredException(String message) {
        super(message);
    }

    public static ConsentRequiredException forEmployee(String employeeId) {
        return new ConsentRequiredException(
                "Active VOICE_PASSPORT consent required to enrol employeeId=" + employeeId);
    }
}

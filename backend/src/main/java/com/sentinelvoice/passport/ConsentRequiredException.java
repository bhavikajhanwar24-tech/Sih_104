package com.sentinelvoice.passport;

/**
 * Thrown when enrolment is attempted without an active purpose-limited consent (DPDP §6).
 */
public class ConsentRequiredException extends RuntimeException {

    public ConsentRequiredException(String employeeId) {
        super("Active consent required to enrol Voice Passport for employeeId=" + employeeId);
    }
}

package com.sentinelvoice.email;

/**
 * Abstraction for verification / invite emails. Hackathon uses log-only impl.
 */
public interface EmailVerificationService {
    void sendVerificationLink(String toEmail, String organisationName, String link);
}

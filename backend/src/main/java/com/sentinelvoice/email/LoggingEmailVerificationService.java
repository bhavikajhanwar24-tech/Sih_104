package com.sentinelvoice.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class LoggingEmailVerificationService implements EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailVerificationService.class);

    @Override
    public void sendVerificationLink(String toEmail, String organisationName, String link) {
        log.info(
                "email_verification_link to={} org={} link={} (log-only; wire SMTP later)",
                toEmail,
                organisationName,
                link
        );
    }
}

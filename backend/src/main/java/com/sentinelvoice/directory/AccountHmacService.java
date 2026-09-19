package com.sentinelvoice.directory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * Keyed HMAC of account numbers — never persist raw account numbers.
 */
@Component
public class AccountHmacService {

    private final byte[] keyBytes;

    public AccountHmacService(
            @Value("${sentinelvoice.directory.hmac-secret:}") String directorySecret,
            @Value("${sentinelvoice.auth.jwt-secret:}") String jwtSecret
    ) {
        String secret = (directorySecret != null && !directorySecret.isBlank())
                ? directorySecret
                : (jwtSecret == null ? "" : jwtSecret);
        if (secret.isBlank()) {
            secret = "dev-only-directory-hmac-change-me";
        }
        this.keyBytes = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String hashAccount(String rawAccountNumber) {
        if (rawAccountNumber == null) {
            throw new IllegalArgumentException("account number required");
        }
        String normalised = rawAccountNumber.replaceAll("\\s+", "").trim();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(keyBytes, "HmacSHA256"));
            byte[] digest = mac.doFinal(normalised.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC failed", e);
        }
    }
}

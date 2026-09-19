package com.sentinelvoice.directory;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Normalises phones to E.164 via libphonenumber. Default region from tenant.region.
 */
@Component
public class PhoneNormaliser {

    private static final Pattern DIGITS = Pattern.compile("[^0-9+]");
    private final PhoneNumberUtil util = PhoneNumberUtil.getInstance();

    public Optional<String> toE164(String raw, String regionOrNull) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String cleaned = DIGITS.matcher(raw.trim()).replaceAll("");
        if (cleaned.isEmpty()) {
            return Optional.empty();
        }
        // Pure extension (3–6 digits, no +) — not E.164; caller handles separately
        if (!cleaned.startsWith("+") && cleaned.matches("\\d{3,6}")) {
            return Optional.empty();
        }
        String region = resolveRegion(regionOrNull);
        try {
            Phonenumber.PhoneNumber parsed = util.parse(cleaned, region);
            if (!util.isValidNumber(parsed)) {
                return Optional.empty();
            }
            return Optional.of(util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164));
        } catch (NumberParseException e) {
            return Optional.empty();
        }
    }

    public boolean looksLikeExtension(String raw) {
        if (raw == null) {
            return false;
        }
        String cleaned = DIGITS.matcher(raw.trim()).replaceAll("");
        return !cleaned.startsWith("+") && cleaned.matches("\\d{3,6}");
    }

    public String extensionDigits(String raw) {
        if (raw == null) {
            return "";
        }
        return DIGITS.matcher(raw.trim()).replaceAll("").replace("+", "");
    }

    static String resolveRegion(String regionOrNull) {
        if (regionOrNull == null || regionOrNull.isBlank()) {
            return "IN";
        }
        String r = regionOrNull.trim().toUpperCase(Locale.ROOT);
        // Map common tenant.region values to ISO country
        return switch (r) {
            case "INDIA", "AP-SOUTH-1", "AP_SOUTH_1" -> "IN";
            case "US", "USA", "US-EAST-1" -> "US";
            case "UK", "GB", "EU-WEST-2" -> "GB";
            default -> r.length() == 2 ? r : "IN";
        };
    }
}

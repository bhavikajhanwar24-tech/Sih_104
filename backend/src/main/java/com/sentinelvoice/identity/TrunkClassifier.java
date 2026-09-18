package com.sentinelvoice.identity;

import com.sentinelvoice.config.SentinelProperties;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Context §12 stage [1] — maps CLI / SIP signalling to trunk provenance.
 */
@Component
public class TrunkClassifier {

    public enum TrunkProvenance {
        INTERNAL_PBX,
        REGISTERED_EXTERNAL,
        UNREGISTERED_SIP,
        WITHHELD
    }

    private final Pattern internalExtensionPattern;
    private final Set<String> registeredExternalClis;

    public TrunkClassifier(SentinelProperties properties) {
        SentinelProperties.Identity identity = properties.identity();
        this.internalExtensionPattern = Pattern.compile(identity.internalExtensionPattern());
        this.registeredExternalClis = identity.registeredExternalClis().stream()
                .map(TrunkClassifier::normaliseCli)
                .collect(Collectors.toUnmodifiableSet());
    }

    public TrunkProvenance classify(String cli) {
        if (cli == null || cli.isBlank() || isWithheld(cli)) {
            return TrunkProvenance.WITHHELD;
        }
        String normalised = normaliseCli(cli);
        if (internalExtensionPattern.matcher(normalised).matches()
                || internalExtensionPattern.matcher(cli.trim()).matches()) {
            return TrunkProvenance.INTERNAL_PBX;
        }
        if (registeredExternalClis.contains(normalised)) {
            return TrunkProvenance.REGISTERED_EXTERNAL;
        }
        return TrunkProvenance.UNREGISTERED_SIP;
    }

    /** Human-readable observed location for presence conflict messages. */
    public String observedRegion(TrunkProvenance provenance) {
        return switch (provenance) {
            case INTERNAL_PBX -> "Internal PBX";
            case REGISTERED_EXTERNAL -> "Registered external trunk";
            case UNREGISTERED_SIP -> "SIP trunk / APAC";
            case WITHHELD -> "Withheld CLI";
        };
    }

    private static boolean isWithheld(String cli) {
        String t = cli.trim().toLowerCase(Locale.ROOT);
        return t.equals("withheld")
                || t.equals("anonymous")
                || t.equals("restricted")
                || t.equals("unavailable")
                || t.equals("private");
    }

    static String normaliseCli(String cli) {
        if (cli == null) {
            return "";
        }
        return cli.replaceAll("[^0-9+]", "").toLowerCase(Locale.ROOT);
    }
}

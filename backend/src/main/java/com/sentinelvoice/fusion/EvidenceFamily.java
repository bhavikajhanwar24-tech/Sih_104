package com.sentinelvoice.fusion;

import com.sentinelvoice.config.SentinelProperties;
import com.sentinelvoice.model.ChannelProfile;

import java.util.Locale;
import java.util.Map;

/**
 * Evidence families for Context §9.1–§9.3. Weights and corroboration thresholds are read from
 * {@link SentinelProperties} — never hardcoded here.
 */
public enum EvidenceFamily {

    VOICE(Group.ACOUSTIC, "voice"),
    CHANNEL(Group.ACOUSTIC, "channel"),
    PROSODY(Group.ACOUSTIC, "prosody"),
    LINGUISTIC(Group.CONTEXTUAL, "linguistic"),
    TRANSACTION(Group.CONTEXTUAL, "transaction"),
    RELATIONSHIP(Group.CONTEXTUAL, "relationship");

    public enum Group {
        ACOUSTIC,
        CONTEXTUAL
    }

    private final Group group;
    private final String configKey;

    EvidenceFamily(Group group, String configKey) {
        this.group = group;
        this.configKey = configKey;
    }

    public Group group() {
        return group;
    }

    public String configKey() {
        return configKey;
    }

    public double weight(SentinelProperties.Fusion fusion, ChannelProfile profile) {
        Map<String, Double> map = isNarrowband(profile)
                ? fusion.weights().narrowband()
                : fusion.weights().wideband();
        Double value = map.get(configKey);
        if (value == null) {
            throw new IllegalStateException(
                    "Missing fusion weight for family '" + configKey + "' in profile " + profile);
        }
        return value;
    }

    public double corroborationThreshold(SentinelProperties.Fusion fusion) {
        Double value = fusion.familyThresholds().get(configKey);
        if (value == null) {
            throw new IllegalStateException(
                    "Missing corroboration threshold for family '" + configKey + "'");
        }
        return value;
    }

    public static EvidenceFamily fromConfigKey(String key) {
        String normalised = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
        for (EvidenceFamily family : values()) {
            if (family.configKey.equals(normalised)) {
                return family;
            }
        }
        throw new IllegalArgumentException("Unknown evidence family: " + key);
    }

    private static boolean isNarrowband(ChannelProfile profile) {
        return profile == ChannelProfile.PSTN_NARROWBAND;
    }
}

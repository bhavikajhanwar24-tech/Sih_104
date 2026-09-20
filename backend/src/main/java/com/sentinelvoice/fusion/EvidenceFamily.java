package com.sentinelvoice.fusion;

import com.sentinelvoice.fusion.config.FusionConfigDocument;
import com.sentinelvoice.model.ChannelProfile;

import java.util.Locale;

/**
 * Evidence families for fusion. Weights and corroboration thresholds come from
 * {@link FusionConfigDocument} — never hardcoded.
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

    public double weight(FusionConfigDocument config, ChannelProfile profile) {
        return config.weight(configKey, isNarrowband(profile));
    }

    public double corroborationThreshold(FusionConfigDocument config) {
        return config.familyThreshold(configKey);
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

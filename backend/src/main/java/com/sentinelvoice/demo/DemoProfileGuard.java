package com.sentinelvoice.demo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * F18 — demo seed/reset only on non-prod profiles (or explicit demo/lab).
 */
@Component
public class DemoProfileGuard {

    private static final Set<String> PROD = Set.of("prod", "production");
    private static final Set<String> ALLOW = Set.of("demo", "dev", "local", "lab", "default");

    private final Environment environment;
    private final boolean labMode;

    public DemoProfileGuard(
            Environment environment,
            @Value("${LAB_MODE:false}") boolean labMode
    ) {
        this.environment = environment;
        this.labMode = labMode;
    }

    public void requireAllowed() {
        if (!isAllowed()) {
            throw new DemoSeedException(
                    "FORBIDDEN",
                    "Demo seed/reset is refused outside demo/dev/lab profiles (and never on prod)"
            );
        }
    }

    public boolean isAllowed() {
        String[] profiles = environment.getActiveProfiles();
        if (profiles.length == 0) {
            profiles = environment.getDefaultProfiles();
        }
        boolean anyProd = Arrays.stream(profiles)
                .map(p -> p.toLowerCase(Locale.ROOT))
                .anyMatch(PROD::contains);
        if (anyProd) {
            return false;
        }
        boolean allowProfile = Arrays.stream(profiles)
                .map(p -> p.toLowerCase(Locale.ROOT))
                .anyMatch(ALLOW::contains);
        return allowProfile || labMode;
    }
}

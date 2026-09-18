package com.sentinelvoice.service;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

@Service
public class ChallengeResponseService {

    private static final List<String> PREFIXES = Arrays.asList(
            "Silver", "Amber", "Cobalt", "Falcon", "Harbor", "Orbit", "Summit", "Vertex"
    );
    private static final List<String> SUFFIXES = Arrays.asList(
            "Alpha", "Beta", "Gamma", "Delta", "Echo", "Foxtrot", "Hotel", "Indigo"
    );

    private final SecureRandom random = new SecureRandom();

    public String issueChallenge() {
        String prefix = PREFIXES.get(random.nextInt(PREFIXES.size()));
        String suffix = SUFFIXES.get(random.nextInt(SUFFIXES.size()));
        int code = 10 + random.nextInt(90);
        return prefix + " " + suffix + " " + code;
    }

    public long measureResponseLatency(Instant start, Instant end) {
        return java.time.Duration.between(start, end).toMillis();
    }
}

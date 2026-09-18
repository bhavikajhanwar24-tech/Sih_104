package com.sentinelvoice.fusion;

import com.sentinelvoice.config.SentinelProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Asymmetric EMA per Context §9.2. Escalate fast ({@code lambdaUp}), de-escalate slow
 * ({@code lambdaDown}), freeze on silence, snap ({@code lambda=0}) on emergency.
 */
@Component
public class EmaSmoother {

    private final double lambdaUp;
    private final double lambdaDown;
    private final ConcurrentMap<String, Double> previousBySession = new ConcurrentHashMap<>();

    public EmaSmoother(SentinelProperties properties) {
        this.lambdaUp = properties.fusion().lambdaUp();
        this.lambdaDown = properties.fusion().lambdaDown();
    }

    /**
     * @param emergency when true, {@code lambda = 0} so the new instantaneous score is adopted
     */
    public double update(String sessionId, double instantaneous, boolean speechPresent, boolean emergency) {
        Double previous = previousBySession.get(sessionId);
        if (previous == null) {
            previousBySession.put(sessionId, instantaneous);
            return instantaneous;
        }
        if (!speechPresent) {
            // Silence must not decay an accumulated risk (Context §9.2).
            return previous;
        }
        double lambda = emergency
                ? 0.0
                : (instantaneous > previous ? lambdaUp : lambdaDown);
        double smoothed = lambda * previous + (1.0 - lambda) * instantaneous;
        previousBySession.put(sessionId, smoothed);
        return smoothed;
    }

    public Double peek(String sessionId) {
        return previousBySession.get(sessionId);
    }

    public void clear(String sessionId) {
        previousBySession.remove(sessionId);
    }

    public void clearAll() {
        previousBySession.clear();
    }
}

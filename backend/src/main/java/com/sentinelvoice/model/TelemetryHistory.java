package com.sentinelvoice.model;

import java.util.ArrayDeque;
import java.util.List;

/**
 * Bounded, thread-safe ring of telemetry samples. Cap is 600 entries (5 minutes at 500 ms).
 * Oldest entries are dropped. Contains NO audio.
 */
public final class TelemetryHistory {

    public static final int MAX_ENTRIES = 600;

    private final ArrayDeque<TelemetryEntry> entries = new ArrayDeque<>(MAX_ENTRIES);

    public synchronized void add(TelemetryEntry entry) {
        while (entries.size() >= MAX_ENTRIES) {
            entries.removeFirst();
        }
        entries.addLast(entry);
    }

    public synchronized List<TelemetryEntry> snapshot() {
        return List.copyOf(entries);
    }

    public synchronized int size() {
        return entries.size();
    }
}

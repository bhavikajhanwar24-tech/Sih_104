package com.sentinelvoice.transcript;

import com.sentinelvoice.audit.AuditEventType;
import com.sentinelvoice.audit.AuditWriteDispatcher;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Break-glass transcript access (Context §13.2). Analysts request; a different supervisor approves;
 * GET returns only a redacted ±5 s window. Emits {@link AuditEventType#TRANSCRIPT_BREAK_GLASS_ACCESS}.
 */
@Service
public class BreakGlassTranscriptService {

    private static final Pattern AADHAAR = Pattern.compile("\\b\\d{4}\\s?\\d{4}\\s?\\d{4}\\b");
    private static final Pattern CARD = Pattern.compile("\\b(?:\\d[ -]*?){13,19}\\b");
    private static final Pattern ACCOUNT = Pattern.compile("\\b\\d{9,18}\\b");

    public enum Status {
        NONE,
        PENDING,
        APPROVED
    }

    public record Window(
            Status status,
            String requester,
            String approver,
            String justification,
            String redactedText,
            long windowStartMs,
            long windowEndMs,
            long requestedAtEpochMs,
            long approvedAtEpochMs
    ) {
    }

    private final AuditWriteDispatcher auditWriteDispatcher;
    private final Clock clock;
    private final ConcurrentHashMap<String, String> rawBySession = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Window> stateBySession = new ConcurrentHashMap<>();

    public BreakGlassTranscriptService(AuditWriteDispatcher auditWriteDispatcher, Clock clock) {
        this.auditWriteDispatcher = auditWriteDispatcher;
        this.clock = clock;
    }

    /** Ingest path stores the latest redacted-capable snippet (never PCM). */
    public void rememberSnippet(String sessionId, String text, long callElapsedMs) {
        if (sessionId == null || sessionId.isBlank() || text == null || text.isBlank()) {
            return;
        }
        rawBySession.put(sessionId, text.strip());
        // Keep call-elapsed on the side via composite key in map value — store elapsed in window on approve.
        stateBySession.compute(sessionId, (id, prev) -> {
            if (prev != null && prev.status() == Status.APPROVED) {
                return prev;
            }
            if (prev != null && prev.status() == Status.PENDING) {
                return prev;
            }
            return new Window(Status.NONE, null, null, null, null, Math.max(0, callElapsedMs - 5000), callElapsedMs + 5000, 0, 0);
        });
    }

    public Window status(String sessionId) {
        return stateBySession.getOrDefault(
                sessionId,
                new Window(Status.NONE, null, null, null, null, 0, 0, 0, 0)
        );
    }

    public Window request(String sessionId, String requester, String justification) {
        if (justification == null || justification.trim().length() < 10) {
            throw new IllegalArgumentException("justification_min_10_chars");
        }
        long now = clock.millis();
        Window pending = new Window(
                Status.PENDING,
                requester,
                null,
                justification.trim(),
                null,
                Math.max(0, now - 5000),
                now + 5000,
                now,
                0
        );
        stateBySession.put(sessionId, pending);
        return pending;
    }

    public Window approve(String sessionId, String approver) {
        Window pending = stateBySession.get(sessionId);
        if (pending == null || pending.status() != Status.PENDING) {
            throw new IllegalStateException("no_pending_request");
        }
        if (approver == null || approver.isBlank()) {
            throw new IllegalArgumentException("approver_required");
        }
        if (approver.equalsIgnoreCase(pending.requester())) {
            throw new IllegalArgumentException("approver_must_differ_from_requester");
        }
        String raw = rawBySession.getOrDefault(sessionId, "");
        String redacted = redact(raw);
        long now = clock.millis();
        Window approved = new Window(
                Status.APPROVED,
                pending.requester(),
                approver,
                pending.justification(),
                redacted,
                pending.windowStartMs(),
                pending.windowEndMs(),
                pending.requestedAtEpochMs(),
                now
        );
        stateBySession.put(sessionId, approved);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requester", approved.requester());
        payload.put("approver", approved.approver());
        payload.put("justification", approved.justification());
        payload.put("windowStartMs", approved.windowStartMs());
        payload.put("windowEndMs", approved.windowEndMs());
        payload.put("redactedChars", redacted.length());
        auditWriteDispatcher.submit(sessionId, AuditEventType.TRANSCRIPT_BREAK_GLASS_ACCESS, payload);
        return approved;
    }

    public Window requireApproved(String sessionId) {
        Window w = status(sessionId);
        if (w.status() != Status.APPROVED) {
            throw new IllegalStateException("not_approved");
        }
        return w;
    }

    public void clear(String sessionId) {
        rawBySession.remove(sessionId);
        stateBySession.remove(sessionId);
    }

    static String redact(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String out = AADHAAR.matcher(text).replaceAll("[REDACTED-AADHAAR]");
        out = CARD.matcher(out).replaceAll("[REDACTED-CARD]");
        out = ACCOUNT.matcher(out).replaceAll("[REDACTED-ACCOUNT]");
        return out;
    }
}

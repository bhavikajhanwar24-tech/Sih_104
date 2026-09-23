import { useCallback, useEffect, useState } from 'react';
import PropTypes from 'prop-types';
import { Badge } from '@/components/ui/Badge.jsx';
import { useAuth } from '@/context/AuthContext.jsx';
import {
  BREAK_GLASS_STATUS,
  canApproveBreakGlass,
  canRequestBreakGlass,
  canViewRedactedWindow,
  nextBreakGlassStatus,
} from '@/lib/breakGlassFlow.js';
import { apiFetch } from '@/services/api.js';

/**
 * Live transcript + break-glass (±5 s redacted window after supervisor approve).
 *
 * @param {Object} props
 * @param {import('@/contracts').TelemetryFrame | null | undefined} props.frame
 * @param {string | null} [props.sessionId]
 */
export function TranscriptPanel({ frame, sessionId = null }) {
  const { hasRole, username } = useAuth();
  const isAnalyst = hasRole('ANALYST') || hasRole('ADMIN');
  const isSupervisor = hasRole('SUPERVISOR') || hasRole('ADMIN');

  const [status, setStatus] = useState(BREAK_GLASS_STATUS.NONE);
  const [justification, setJustification] = useState(
    'Analyst review of high-risk linguistic window',
  );
  const [windowBody, setWindowBody] = useState(/** @type {any | null} */ (null));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(/** @type {string | null} */ (null));

  const delta = frame?.transcriptDelta?.text;
  const snippet =
    frame?.linguistic?.redactedSnippet ||
    frame?.families?.linguistic?.redactedSnippet ||
    null;
  const liveText =
    (typeof delta === 'string' && delta.trim()) ||
    (typeof snippet === 'string' && snippet.trim()) ||
    null;
  const lang = frame?.linguistic?.language || frame?.transcriptDelta?.language || null;
  const refreshStatus = useCallback(async () => {
    if (!sessionId) return;
    try {
      const res = await apiFetch(
        `/api/v1/session/${encodeURIComponent(sessionId)}/transcript/status`,
      );
      if (!res.ok) return;
      const body = await res.json();
      if (body?.status && BREAK_GLASS_STATUS[body.status]) {
        setStatus(body.status);
        setWindowBody(body);
      }
    } catch {
      /* status poll is best-effort */
    }
  }, [sessionId]);

  useEffect(() => {
    setStatus(BREAK_GLASS_STATUS.NONE);
    setWindowBody(null);
    setError(null);
    void refreshStatus();
  }, [sessionId, refreshStatus]);

  async function requestAccess() {
    if (!sessionId || !canRequestBreakGlass(status, isAnalyst)) return;
    setBusy(true);
    setError(null);
    try {
      const res = await apiFetch(
        `/api/v1/session/${encodeURIComponent(sessionId)}/transcript/request`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ justification }),
        },
      );
      const body = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(body.error || `request HTTP ${res.status}`);
      setStatus(nextBreakGlassStatus(status, 'request'));
      setWindowBody(body);
      if (body.status) setStatus(body.status);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'request failed');
    } finally {
      setBusy(false);
    }
  }

  async function approveAccess() {
    if (!sessionId || !canApproveBreakGlass(status, isSupervisor)) return;
    setBusy(true);
    setError(null);
    try {
      const res = await apiFetch(
        `/api/v1/session/${encodeURIComponent(sessionId)}/transcript/approve`,
        { method: 'POST' },
      );
      const body = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(body.error || `approve HTTP ${res.status}`);
      setStatus(nextBreakGlassStatus(status, 'approve'));
      setWindowBody(body);
      if (body.status) setStatus(body.status);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'approve failed');
    } finally {
      setBusy(false);
    }
  }

  async function loadWindow() {
    if (!sessionId || !canViewRedactedWindow(status)) return;
    setBusy(true);
    setError(null);
    try {
      const res = await apiFetch(
        `/api/v1/session/${encodeURIComponent(sessionId)}/transcript`,
      );
      const body = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(body.error || `window HTTP ${res.status}`);
      setWindowBody(body);
      if (body.status) setStatus(body.status);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'window fetch failed');
    } finally {
      setBusy(false);
    }
  }

  const redacted =
    canViewRedactedWindow(status) && typeof windowBody?.redactedText === 'string'
      ? windowBody.redactedText
      : null;

  return (
    <div className="flex h-full min-h-0 flex-col gap-2 overflow-auto p-3" data-testid="transcript-panel">
      <div className="flex flex-wrap items-center gap-2">
        {lang ? (
          <span className="font-mono text-[10px] uppercase tracking-wider text-sv-muted">{lang}</span>
        ) : null}
        <span data-testid="break-glass-status">
          <Badge
            variant={
              status === BREAK_GLASS_STATUS.APPROVED
                ? 'accent'
                : status === BREAK_GLASS_STATUS.PENDING
                  ? 'live'
                  : 'neutral'
            }
          >
            {status}
          </Badge>
        </span>
        <span className="font-mono text-[10px] text-sv-muted">{username || '—'}</span>
      </div>

      {liveText ? (
        <p className="whitespace-pre-wrap font-mono text-[12px] leading-relaxed text-sv-fg">
          {liveText}
        </p>
      ) : (
        <p className="font-mono text-[11px] text-sv-muted">
          Live STOMP transcript text is withheld — use break-glass for a ±5 s redacted window.
        </p>
      )}

      {sessionId ? (
        <div className="mt-1 space-y-2 rounded border border-sv-border/80 bg-sv-bg/40 p-2">
          <p className="font-mono text-[10px] uppercase tracking-wide text-sv-muted">
            Break-glass · request (ANALYST) → approve (SUPERVISOR) → GET window
          </p>
          <textarea
            className="w-full rounded border border-sv-border bg-sv-bg px-2 py-1 font-mono text-[11px] text-sv-fg"
            rows={2}
            value={justification}
            onChange={(ev) => setJustification(ev.target.value)}
            data-testid="break-glass-justification"
          />
          <div className="flex flex-wrap gap-2">
            <button
              type="button"
              disabled={busy || !canRequestBreakGlass(status, isAnalyst)}
              onClick={() => void requestAccess()}
              className="rounded border border-sv-border px-2 py-1 text-[10px] uppercase tracking-wide text-sv-fg hover:border-sv-accent disabled:opacity-40"
              data-testid="break-glass-request"
            >
              Request
            </button>
            <button
              type="button"
              disabled={busy || !canApproveBreakGlass(status, isSupervisor)}
              onClick={() => void approveAccess()}
              className="rounded border border-sv-border px-2 py-1 text-[10px] uppercase tracking-wide text-sv-fg hover:border-sv-accent disabled:opacity-40"
              data-testid="break-glass-approve"
            >
              Approve
            </button>
            <button
              type="button"
              disabled={busy || !canViewRedactedWindow(status)}
              onClick={() => void loadWindow()}
              className="rounded border border-sv-border px-2 py-1 text-[10px] uppercase tracking-wide text-sv-fg hover:border-sv-accent disabled:opacity-40"
              data-testid="break-glass-fetch"
            >
              GET window
            </button>
          </div>
          {windowBody?.windowStartMs != null ? (
            <p className="font-mono text-[10px] text-sv-muted">
              window {windowBody.windowStartMs}–{windowBody.windowEndMs} ms
              {windowBody.requester ? ` · req ${windowBody.requester}` : ''}
              {windowBody.approver ? ` · apr ${windowBody.approver}` : ''}
            </p>
          ) : null}
          {redacted != null ? (
            <p
              className="whitespace-pre-wrap rounded border border-sv-accent/30 bg-sv-accent/5 p-2 font-mono text-[12px] text-sv-fg"
              data-testid="break-glass-redacted"
            >
              {redacted || '(empty redacted window)'}
            </p>
          ) : null}
        </div>
      ) : null}

      {error ? (
        <p className="text-[11px] text-red-400" role="alert">
          {error}
        </p>
      ) : null}
    </div>
  );
}

TranscriptPanel.propTypes = {
  frame: PropTypes.object,
  sessionId: PropTypes.string,
};

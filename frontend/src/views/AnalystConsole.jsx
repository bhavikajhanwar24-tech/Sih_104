import { useEffect, useState } from 'react';
import PropTypes from 'prop-types';
import { CHANNEL_PROFILES } from '@/contracts';
import { MicControl } from '@/components/MicControl.jsx';
import { RiskGauge } from '@/components/RiskGauge.jsx';
import {
  CONNECTION_STATES,
  useTelemetrySocket,
} from '@/hooks/useTelemetrySocket.js';

/**
 * Minimal analyst console — mic capture + live risk gauge + raw telemetry JSON.
 *
 * @param {Object} props
 * @param {string} [props.sessionId='browser-dev']
 */
export function AnalystConsole({ sessionId = 'browser-dev' }) {
  const {
    latest,
    history,
    connectionState,
    error,
    lastValidationError,
  } = useTelemetrySocket(sessionId);

  const [jsonOpen, setJsonOpen] = useState(true);
  const [sessionStatus, setSessionStatus] = useState(/** @type {string | null} */ (null));

  // Ensure the Decision Plane has a CallSession before FeatureFrames arrive.
  useEffect(() => {
    let cancelled = false;
    setSessionStatus('starting…');

    async function ensureSession() {
      try {
        const existing = await fetch(`/api/v1/session/${encodeURIComponent(sessionId)}`);
        if (cancelled) return;
        if (existing.ok) {
          setSessionStatus('session open');
          return;
        }
        const res = await fetch('/api/v1/session/start', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            schema: 'sentinelvoice.SessionStartRequest/1',
            sessionId,
            callerId: 'browser-agent',
            calleeId: 'desk-1',
            channelProfile: CHANNEL_PROFILES.WEBRTC_WIDEBAND,
          }),
        });
        if (cancelled) return;
        if (!res.ok) {
          const text = await res.text();
          // Concurrent StrictMode double-mount can race; treat conflict as open.
          if (res.status === 400 && /already exists/i.test(text)) {
            setSessionStatus('session open');
            return;
          }
          setSessionStatus(`session start failed (${res.status}): ${text.slice(0, 120)}`);
          return;
        }
        setSessionStatus('session open');
      } catch (err) {
        if (!cancelled) {
          setSessionStatus(err instanceof Error ? err.message : 'session start failed');
        }
      }
    }

    ensureSession();
    return () => {
      cancelled = true;
    };
  }, [sessionId]);

  return (
    <div className="mx-auto flex min-h-screen max-w-5xl flex-col gap-6 px-4 py-8">
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <p className="font-display text-xs uppercase tracking-[0.2em] text-sv-accent">
            SentinelVoice
          </p>
          <h1 className="mt-1 font-display text-2xl font-semibold text-sv-fg">
            Analyst console
          </h1>
          <p className="mt-1 font-mono text-xs text-sv-muted">session {sessionId}</p>
        </div>
        <ConnectionBadge state={connectionState} />
      </header>

      {/* Inline status strip — StatusBar (P6.1) will own this; keep drift visible now. */}
      <div className="flex flex-col gap-1 rounded border border-sv-border bg-sv-panel/60 px-3 py-2 text-xs">
        <div className="flex flex-wrap gap-x-4 gap-y-1 text-sv-muted">
          <span>Decision Plane: {connectionState}</span>
          <span>Session: {sessionStatus ?? '—'}</span>
          <span>Frames: {history.length}</span>
          <span>seq: {latest?.seq ?? '—'}</span>
        </div>
        {error ? (
          <p className="text-risk-elevated" role="status">
            {error}
          </p>
        ) : null}
        {lastValidationError ? (
          <p className="font-mono text-risk-critical" role="alert">
            Contract: {lastValidationError}
          </p>
        ) : null}
      </div>

      <div className="grid gap-6 md:grid-cols-2">
        <MicControl sessionId={sessionId} />
        <div className="flex flex-col items-center justify-center rounded-lg border border-sv-border bg-sv-panel/80 p-4">
          <RiskGauge frame={latest} />
        </div>
      </div>

      <section className="rounded-lg border border-sv-border bg-sv-panel/80">
        <button
          type="button"
          className="flex w-full items-center justify-between px-4 py-3 text-left text-sm font-medium text-sv-fg hover:bg-sv-bg/40"
          onClick={() => setJsonOpen((o) => !o)}
          aria-expanded={jsonOpen}
        >
          <span>Raw telemetry JSON</span>
          <span className="font-mono text-xs text-sv-muted">{jsonOpen ? '▾' : '▸'}</span>
        </button>
        {jsonOpen ? (
          <pre className="max-h-80 overflow-auto border-t border-sv-border bg-sv-bg/60 p-4 font-mono text-[11px] leading-relaxed text-sv-fg">
            {latest ? JSON.stringify(latest, null, 2) : '// waiting for TelemetryFrame…'}
          </pre>
        ) : null}
      </section>
    </div>
  );
}

AnalystConsole.propTypes = {
  sessionId: PropTypes.string,
};

/**
 * @param {Object} props
 * @param {string} props.state
 */
function ConnectionBadge({ state }) {
  const connected = state === CONNECTION_STATES.CONNECTED;
  const reconnecting = state === CONNECTION_STATES.RECONNECTING;
  const colour = connected
    ? 'bg-risk-clear'
    : reconnecting
      ? 'bg-risk-watch'
      : 'bg-risk-critical';
  const label = connected
    ? 'STOMP connected'
    : reconnecting
      ? 'STOMP reconnecting…'
      : state === CONNECTION_STATES.CONNECTING
        ? 'STOMP connecting…'
        : 'STOMP disconnected';

  return (
    <div
      className="flex items-center gap-2 rounded border border-sv-border bg-sv-panel px-3 py-2"
      role="status"
      aria-live="polite"
    >
      <span className={`inline-block h-2.5 w-2.5 rounded-full ${colour}`} aria-hidden />
      <span className="font-mono text-xs font-medium text-sv-fg">{label}</span>
    </div>
  );
}

ConnectionBadge.propTypes = {
  state: PropTypes.string.isRequired,
};

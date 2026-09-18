import { useEffect, useMemo, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import { Badge } from '@/components/ui/Badge.jsx';
import { useSession } from '@/context/SessionContext.jsx';
import {
  CONNECTION_STATES,
  useTelemetrySocket,
} from '@/hooks/useTelemetrySocket.js';

const HEALTH_POLL_MS = 4000;

/**
 * Bottom ops bar — STOMP, ml-engine health, sessions, latency p95, clock,
 * and contract-validation chip.
 *
 * @param {Object} props
 * @param {string} [props.className]
 */
export function StatusBar({ className = '' }) {
  const { sessionId } = useSession();
  const { latest, history, connectionState, lastValidationError } =
    useTelemetrySocket(sessionId);

  const [clock, setClock] = useState(() => formatClock(new Date()));
  const [mlHealth, setMlHealth] = useState(
    /** @type {{ ok: boolean, activeSessions: number | null, label: string }} */ ({
      ok: false,
      activeSessions: null,
      label: 'ml …',
    }),
  );

  const arrivalRef = useRef(/** @type {number[]} */ ([]));
  const lastSeqRef = useRef(/** @type {number | null} */ (null));
  const lastAtRef = useRef(/** @type {number | null} */ (null));
  const [latencyP95, setLatencyP95] = useState(/** @type {number | null} */ (null));

  useEffect(() => {
    const id = setInterval(() => setClock(formatClock(new Date())), 1000);
    return () => clearInterval(id);
  }, []);

  useEffect(() => {
    let cancelled = false;

    async function poll() {
      try {
        const res = await fetch('/engine/health', { cache: 'no-store' });
        if (cancelled) return;
        if (!res.ok) {
          setMlHealth({ ok: false, activeSessions: null, label: `ml ${res.status}` });
          return;
        }
        const body = await res.json();
        const active =
          typeof body.active_sessions === 'number' ? body.active_sessions : null;
        setMlHealth({
          ok: body.status === 'ok',
          activeSessions: active,
          label: body.status === 'ok' ? 'ml ok' : 'ml degraded',
        });
      } catch {
        if (!cancelled) {
          setMlHealth({ ok: false, activeSessions: null, label: 'ml down' });
        }
      }
    }

    poll();
    const id = setInterval(poll, HEALTH_POLL_MS);
    return () => {
      cancelled = true;
      clearInterval(id);
    };
  }, []);

  useEffect(() => {
    if (!latest || typeof latest.seq !== 'number') return;
    if (lastSeqRef.current === latest.seq) return;
    const now = Date.now();
    if (lastAtRef.current != null) {
      const delta = now - lastAtRef.current;
      const next = arrivalRef.current.concat(delta).slice(-40);
      arrivalRef.current = next;
      setLatencyP95(percentile(next, 0.95));
    }
    lastSeqRef.current = latest.seq;
    lastAtRef.current = now;
  }, [latest]);

  const stompBadge = useMemo(() => {
    if (connectionState === CONNECTION_STATES.CONNECTED) {
      return { variant: 'live', text: 'STOMP up' };
    }
    if (connectionState === CONNECTION_STATES.RECONNECTING) {
      return { variant: 'accent', text: 'STOMP retry' };
    }
    if (connectionState === CONNECTION_STATES.CONNECTING) {
      return { variant: 'accent', text: 'STOMP …' };
    }
    return { variant: 'fault', text: 'STOMP down' };
  }, [connectionState]);

  const validationChip =
    lastValidationError != null && lastValidationError.length > 0 ? (
      <Badge
        variant="risk"
        riskLevel="watch"
        title={lastValidationError}
        className="max-w-[28rem]"
      >
        contract: {truncate(lastValidationError, 72)}
      </Badge>
    ) : null;

  return (
    <footer
      className={`flex h-9 shrink-0 items-center gap-3 overflow-hidden border-t border-sv-border bg-sv-panel px-3 ${className}`}
      role="status"
      aria-live="polite"
    >
      <Badge variant={stompBadge.variant}>{stompBadge.text}</Badge>
      <Badge variant={mlHealth.ok ? 'live' : 'fault'}>{mlHealth.label}</Badge>
      <span className="font-mono text-[11px] tabular-nums text-sv-muted">
        sessions {mlHealth.activeSessions ?? '—'}
      </span>
      <span className="font-mono text-[11px] tabular-nums text-sv-muted">
        p95 {latencyP95 != null ? `${Math.round(latencyP95)} ms` : '—'}
      </span>
      <span className="font-mono text-[11px] tabular-nums text-sv-muted">
        frames {history.length}
      </span>
      <span className="font-mono text-[11px] tabular-nums text-sv-muted">
        seq {latest?.seq ?? '—'}
      </span>
      <div className="min-w-0 flex-1">{validationChip}</div>
      <span className="shrink-0 font-mono text-[11px] tabular-nums text-sv-fg">{clock}</span>
    </footer>
  );
}

StatusBar.propTypes = {
  className: PropTypes.string,
};

/**
 * @param {Date} d
 * @returns {string}
 */
function formatClock(d) {
  return d.toLocaleTimeString('en-GB', { hour12: false });
}

/**
 * @param {number[]} samples
 * @param {number} p 0..1
 * @returns {number | null}
 */
function percentile(samples, p) {
  if (!samples.length) return null;
  const sorted = samples.slice().sort((a, b) => a - b);
  const idx = Math.min(sorted.length - 1, Math.ceil(p * sorted.length) - 1);
  return sorted[Math.max(0, idx)];
}

/**
 * @param {string} text
 * @param {number} max
 * @returns {string}
 */
function truncate(text, max) {
  if (text.length <= max) return text;
  return `${text.slice(0, max - 1)}…`;
}

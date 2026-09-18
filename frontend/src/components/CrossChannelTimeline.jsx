import { useEffect, useMemo, useState } from 'react';
import PropTypes from 'prop-types';
import { riskRamp, palette } from '@/theme.js';

const SEVERITY_COLOUR = Object.freeze({
  CRITICAL: riskRamp.critical,
  HIGH: riskRamp.elevated,
  MEDIUM: riskRamp.watch,
  LOW: palette.muted,
});

const CHANNEL_LABEL = Object.freeze({
  EMAIL: 'Email',
  SMS: 'SMS',
  AUTH: 'Auth',
  WEB: 'Web',
  CALL: 'Call',
});

/**
 * Horizontal precursor timeline — email → SMS → live call.
 * Empty when the API returns no matched events (never fabricates).
 *
 * @param {Object} props
 * @param {string | null | undefined} props.sessionId
 * @param {number} [props.callStartedAtMs]
 * @param {string} [props.className]
 */
export function CrossChannelTimeline({ sessionId, callStartedAtMs, className = '' }) {
  const [payload, setPayload] = useState(/** @type {object | null} */ (null));
  const [error, setError] = useState(/** @type {string | null} */ (null));

  useEffect(() => {
    if (!sessionId) {
      setPayload(null);
      setError(null);
      return undefined;
    }
    let cancelled = false;
    const load = async () => {
      try {
        const res = await fetch(
          `/api/v1/cross-channel?sessionId=${encodeURIComponent(sessionId)}`,
        );
        if (!res.ok) {
          throw new Error(`cross-channel ${res.status}`);
        }
        const body = await res.json();
        if (!cancelled) {
          setPayload(body);
          setError(null);
        }
      } catch (err) {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : 'failed to load');
          setPayload(null);
        }
      }
    };
    load();
    const id = window.setInterval(load, 5000);
    return () => {
      cancelled = true;
      clearInterval(id);
    };
  }, [sessionId]);

  const nodes = useMemo(() => {
    const events = Array.isArray(payload?.events) ? payload.events : [];
    /** @type {{ key: string, channel: string, label: string, atMs: number, severity: string, description: string }[]} */
    const list = events.map((e, i) => ({
      key: e.id ?? `e-${i}`,
      channel: String(e.channel ?? 'EMAIL'),
      label: CHANNEL_LABEL[e.channel] ?? e.channel,
      atMs: Number(e.occurredAtEpochMs) || 0,
      severity: String(e.severity ?? 'MEDIUM'),
      description: String(e.description ?? e.indicator ?? ''),
    }));
    const callMs = callStartedAtMs ?? Date.now();
    list.push({
      key: 'live-call',
      channel: 'CALL',
      label: 'Call',
      atMs: callMs,
      severity: payload?.matchingCampaign ? 'CRITICAL' : 'MEDIUM',
      description: payload?.matchingCampaign
        ? 'Live call — matching BEC campaign precursor'
        : 'Live call',
    });
    list.sort((a, b) => a.atMs - b.atMs);
    return list;
  }, [payload, callStartedAtMs]);

  if (!sessionId) {
    return (
      <p className={`px-2 py-3 text-center font-mono text-[11px] text-sv-muted ${className}`}>
        Start a session to load cross-channel precursors
      </p>
    );
  }

  if (error) {
    return (
      <p className={`px-2 py-3 text-center font-mono text-[11px] text-risk-critical ${className}`}>
        {error}
      </p>
    );
  }

  const precursors = nodes.filter((n) => n.channel !== 'CALL');
  if (precursors.length === 0) {
    return (
      <div className={`flex h-full flex-col justify-center gap-1 px-2 ${className}`} data-testid="cross-channel-empty">
        <p className="font-mono text-[11px] text-sv-muted">
          No correlated precursors in the {payload?.windowHours ?? 48} h window
        </p>
        <p className="text-[10px] text-sv-muted">
          Empty is correct — we never invent SIEM events.
        </p>
      </div>
    );
  }

  const oldest = nodes[0]?.atMs ?? Date.now();
  const hoursAgo = Math.max(0, (Date.now() - oldest) / 3_600_000);
  const score = typeof payload?.correlationScore === 'number' ? payload.correlationScore : 0;

  return (
    <div className={`flex h-full min-h-0 flex-col gap-2 p-1 ${className}`} data-testid="cross-channel-timeline">
      <div className="flex flex-wrap items-baseline justify-between gap-2 px-1">
        <p className="font-mono text-[10px] uppercase tracking-wider text-sv-muted">
          This attack started ~{formatHours(hoursAgo)} ago
        </p>
        <p className="font-mono text-[10px] tabular-nums text-sv-muted">
          corr {score.toFixed(2)}
          {payload?.matchingCampaign ? (
            <span className="ml-2 text-risk-elevated">· campaign match</span>
          ) : null}
        </p>
      </div>

      <div className="relative flex min-h-[4.5rem] flex-1 items-center px-2">
        <div className="absolute left-4 right-4 top-1/2 h-px -translate-y-1/2 bg-sv-border" />
        <ol className="relative z-[1] flex w-full items-start justify-between gap-1">
          {nodes.map((node, idx) => {
            const colour = SEVERITY_COLOUR[node.severity] ?? palette.muted;
            const gap =
              idx > 0 ? formatGap(nodes[idx - 1].atMs, node.atMs) : null;
            return (
              <li key={node.key} className="flex min-w-0 flex-1 flex-col items-center text-center">
                {gap ? (
                  <span className="mb-1 font-mono text-[8px] text-sv-muted">{gap}</span>
                ) : (
                  <span className="mb-1 h-[12px]" />
                )}
                <span
                  className="flex h-3 w-3 rounded-full border-2 border-sv-panel"
                  style={{ background: colour, boxShadow: `0 0 0 2px ${colour}55` }}
                  title={node.description}
                />
                <span
                  className="mt-1 font-mono text-[10px] font-semibold uppercase tracking-wide"
                  style={{ color: colour }}
                >
                  {node.label}
                </span>
                <span className="mt-0.5 line-clamp-2 max-w-[7rem] text-[9px] leading-tight text-sv-muted">
                  {node.channel === 'CALL'
                    ? 'now'
                    : formatHours((Date.now() - node.atMs) / 3_600_000) + ' ago'}
                </span>
              </li>
            );
          })}
        </ol>
      </div>
    </div>
  );
}

CrossChannelTimeline.propTypes = {
  sessionId: PropTypes.string,
  callStartedAtMs: PropTypes.number,
  className: PropTypes.string,
};

/** @param {number} hours */
function formatHours(hours) {
  if (!Number.isFinite(hours) || hours < 0) return '—';
  if (hours < 1) return `${Math.max(1, Math.round(hours * 60))} min`;
  if (hours < 48) return `${hours.toFixed(hours < 10 ? 1 : 0)} h`;
  return `${(hours / 24).toFixed(1)} d`;
}

/** @param {number} fromMs @param {number} toMs */
function formatGap(fromMs, toMs) {
  const hours = (toMs - fromMs) / 3_600_000;
  if (!Number.isFinite(hours) || hours <= 0) return null;
  return `+${formatHours(hours)}`;
}

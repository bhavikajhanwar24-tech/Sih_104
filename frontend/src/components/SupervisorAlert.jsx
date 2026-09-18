import PropTypes from 'prop-types';
import { INTERVENTION_LEVELS } from '@/contracts';

/**
 * L4 supervisor slide-in / L5 full-screen terminal state.
 *
 * @param {Object} props
 * @param {import('@/contracts').TelemetryFrame | null | undefined} props.frame
 * @param {string | null | undefined} props.sessionId
 * @param {() => void} [props.onAccept]
 * @param {() => void} [props.onRelease]
 */
export function SupervisorAlert({ frame, sessionId, onAccept, onRelease }) {
  const level = frame?.intervention?.level;
  if (
    level !== INTERVENTION_LEVELS.LEVEL_4_AUTO_HOLD
    && level !== INTERVENTION_LEVELS.LEVEL_5_TERMINATE
  ) {
    return null;
  }

  const reasons = Array.isArray(frame?.topReasons) ? frame.topReasons : [];
  const smoothed = frame?.risk?.smoothed;
  const isL5 = level === INTERVENTION_LEVELS.LEVEL_5_TERMINATE;

  if (isL5) {
    return (
      <div
        className="sv-terminal-flash fixed inset-0 z-40 flex flex-col items-center justify-center bg-[#3f0a0a]/95 p-6 text-center"
        data-testid="terminal-overlay"
        role="alertdialog"
        aria-label="Call terminated"
      >
        <p className="font-display text-3xl font-bold tracking-wide text-risk-critical">
          LEVEL 5 — TERMINATE
        </p>
        <p className="mt-3 max-w-lg text-sm text-sv-fg">
          Call disconnected. Beneficiary account freeze requested. Fraud response team escalated.
        </p>
        <p className="mt-2 font-mono text-xs text-sv-muted">
          session {sessionId ?? '—'} · smoothed{' '}
          {Number.isFinite(smoothed) ? smoothed.toFixed(3) : '—'}
        </p>
        <ul className="mt-4 max-w-md space-y-1 text-left text-[11px] text-sv-fg">
          {reasons.slice(0, 5).map((r) => (
            <li key={r.code} className="rounded border border-risk-critical/30 bg-black/20 px-2 py-1">
              <span className="font-mono text-risk-critical">{r.code}</span>
              <span className="text-sv-muted"> — {r.text}</span>
            </li>
          ))}
        </ul>
      </div>
    );
  }

  return (
    <aside
      className="sv-slide-in fixed bottom-3 right-3 z-30 w-[min(100%-1.5rem,22rem)] rounded border border-risk-elevated bg-sv-panel p-3 shadow-2xl"
      data-testid="supervisor-alert"
      role="complementary"
      aria-label="Supervisor bridge"
    >
      <header className="flex items-center justify-between gap-2 border-b border-sv-border pb-2">
        <h3 className="text-xs font-semibold uppercase tracking-wider text-risk-elevated">
          Supervisor alert · L4
        </h3>
        <span className="font-mono text-[10px] text-sv-muted">
          risk {Number.isFinite(smoothed) ? smoothed.toFixed(2) : '—'}
        </span>
      </header>
      <p className="mt-2 text-[11px] text-sv-muted">
        Auto-hold active. Call bridged for supervisor review. Approve button remains
        server-locked.
      </p>
      <ul className="mt-2 max-h-28 space-y-1 overflow-auto text-[11px]">
        {reasons.length === 0 ? (
          <li className="text-sv-muted">No reason codes on latest frame</li>
        ) : (
          reasons.slice(0, 4).map((r) => (
            <li key={r.code} className="leading-snug text-sv-fg">
              <span className="font-mono text-risk-elevated">{r.severity}</span>{' '}
              {r.text}
            </li>
          ))
        )}
      </ul>
      <div className="mt-3 flex gap-2">
        <button
          type="button"
          onClick={onAccept}
          className="flex-1 rounded bg-risk-elevated px-2 py-1.5 text-[11px] font-semibold text-sv-bg"
        >
          Accept hold
        </button>
        <button
          type="button"
          onClick={onRelease}
          className="flex-1 rounded border border-sv-border px-2 py-1.5 text-[11px] text-sv-fg hover:bg-sv-elevated"
        >
          Release
        </button>
      </div>
    </aside>
  );
}

SupervisorAlert.propTypes = {
  frame: PropTypes.object,
  sessionId: PropTypes.string,
  onAccept: PropTypes.func,
  onRelease: PropTypes.func,
};

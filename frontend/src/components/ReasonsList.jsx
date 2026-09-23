import { useMemo, useState } from 'react';
import PropTypes from 'prop-types';
import { getTopReasons, REASON_SEVERITIES } from '@/contracts';
import { familyForReasonCode } from '@/lib/reasonMeta.js';
import { palette, riskRamp } from '@/theme.js';

const SEVERITY_RANK = Object.freeze({
  CRITICAL: 0,
  HIGH: 1,
  MEDIUM: 2,
  LOW: 3,
  INFO: 3,
});

/**
 * Top reasons ordered by severity. Click highlights a reason locally
 * (or via optional onSelectReason callback).
 *
 * @param {Object} props
 * @param {TelemetryFrame | null | undefined} props.frame
 * @param {(code: string | null, family: string | null) => void} [props.onSelectReason]
 * @param {string} [props.className]
 */
export function ReasonsList({ frame, onSelectReason, className = '' }) {
  const [localHighlight, setLocalHighlight] = useState(/** @type {string | null} */ (null));
  const highlighted = localHighlight;
  const select =
    onSelectReason ??
    ((code) => {
      setLocalHighlight(code);
    });

  const reasons = useMemo(() => {
    const raw = getTopReasons(frame);
    return [...raw].sort((a, b) => {
      const ra = SEVERITY_RANK[String(a?.severity || '').toUpperCase()] ?? 9;
      const rb = SEVERITY_RANK[String(b?.severity || '').toUpperCase()] ?? 9;
      return ra - rb;
    });
  }, [frame]);

  if (reasons.length === 0) {
    return (
      <p className={`px-1 py-2 text-center font-mono text-[11px] text-sv-muted ${className}`}>
        No reasons yet — fusion emits after speech evidence accumulates
      </p>
    );
  }

  return (
    <ul className={`flex flex-col gap-1.5 ${className}`} role="list">
      {reasons.map((reason) => {
        const code = reason?.code ?? 'UNKNOWN';
        const severity = String(reason?.severity ?? 'MEDIUM').toUpperCase();
        const family = familyForReasonCode(code);
        const active = highlighted === code;
        return (
          <li key={code}>
            <button
              type="button"
              onClick={() => select(active ? null : code, active ? null : family)}
              className={`flex w-full flex-col gap-1 rounded border px-2 py-1.5 text-left transition-colors duration-200 ${
                active
                  ? 'border-sv-accent/60 bg-sv-accent/10'
                  : 'border-sv-border/80 bg-sv-elevated/30 hover:border-sv-border hover:bg-sv-elevated/55'
              }`}
              aria-pressed={active}
              title={
                family
                  ? `Highlight ${family} evidence axis`
                  : 'Select reason'
              }
            >
              <div className="flex flex-wrap items-center gap-1.5">
                <SeverityChip severity={severity} />
                {family ? (
                  <span className="rounded border border-sv-border px-1 py-px font-mono text-[9px] uppercase tracking-wide text-sv-muted">
                    {family}
                  </span>
                ) : null}
                <span className="ml-auto truncate font-mono text-[9px] text-sv-muted">
                  {code}
                </span>
              </div>
              <p className="text-[11px] leading-snug text-sv-fg">
                {reason?.text ?? '—'}
              </p>
            </button>
          </li>
        );
      })}
    </ul>
  );
}

ReasonsList.propTypes = {
  frame: PropTypes.object,
  onSelectReason: PropTypes.func,
  className: PropTypes.string,
};

/**
 * @param {Object} props
 * @param {string} props.severity
 */
function SeverityChip({ severity }) {
  const meta = severityMeta(severity);
  return (
    <span
      className="inline-flex items-center rounded border px-1.5 py-px font-mono text-[9px] font-semibold uppercase tracking-wide"
      style={{
        color: meta.fg,
        borderColor: `${meta.fg}66`,
        background: `${meta.fg}18`,
      }}
    >
      {meta.label}
    </span>
  );
}

SeverityChip.propTypes = {
  severity: PropTypes.string.isRequired,
};

/** @param {string} severity */
function severityMeta(severity) {
  const s = severity.toUpperCase();
  if (s === REASON_SEVERITIES.CRITICAL || s === 'CRITICAL') {
    return { label: 'Critical', fg: riskRamp.critical };
  }
  if (s === REASON_SEVERITIES.HIGH || s === 'HIGH') {
    return { label: 'High', fg: riskRamp.elevated };
  }
  if (s === REASON_SEVERITIES.MEDIUM || s === 'MEDIUM') {
    return { label: 'Medium', fg: riskRamp.watch };
  }
  if (s === 'INFO') {
    return { label: 'Info', fg: palette.accent };
  }
  return { label: 'Low', fg: palette.muted };
}

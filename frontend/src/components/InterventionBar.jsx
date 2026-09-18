import { useEffect, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import { INTERVENTION_LEVELS } from '@/contracts';

const STAGES = [
  {
    level: INTERVENTION_LEVELS.LEVEL_1_SILENT,
    short: 'L1',
    label: 'Silent',
    actions: ['LOG_ONLY'],
  },
  {
    level: INTERVENTION_LEVELS.LEVEL_2_SOFT_NUDGE,
    short: 'L2',
    label: 'Soft nudge',
    actions: ['SOFT_WARNING', 'REQUEST_CALLBACK'],
  },
  {
    level: INTERVENTION_LEVELS.LEVEL_3_STEP_UP_MFA,
    short: 'L3',
    label: 'Step-up MFA',
    actions: ['STEP_UP_MFA', 'TXN_APPROVE_LOCKED'],
  },
  {
    level: INTERVENTION_LEVELS.LEVEL_4_AUTO_HOLD,
    short: 'L4',
    label: 'Auto-hold',
    actions: ['CALL_HELD', 'SUPERVISOR_BRIDGED'],
  },
  {
    level: INTERVENTION_LEVELS.LEVEL_5_TERMINATE,
    short: 'L5',
    label: 'Terminate',
    actions: ['CALL_TERMINATED', 'ACCOUNT_FREEZE'],
  },
];

function levelIndex(level) {
  const idx = STAGES.findIndex((s) => s.level === level);
  return idx < 0 ? 0 : idx;
}

/**
 * Five-stage intervention ladder driven by TelemetryFrame.intervention.
 *
 * @param {Object} props
 * @param {import('@/contracts').TelemetryFrame | null | undefined} props.frame
 * @param {() => void} [props.onOverrideClick]
 */
export function InterventionBar({ frame, onOverrideClick }) {
  const level = frame?.intervention?.level ?? INTERVENTION_LEVELS.LEVEL_1_SILENT;
  const previous = frame?.intervention?.previousLevel ?? level;
  const dwellMs = Number(frame?.intervention?.dwellRemainingMs ?? 0);
  const currentIdx = levelIndex(level);
  const prevIdx = levelIndex(previous);

  const [flash, setFlash] = useState(false);
  const prevLevelRef = useRef(level);

  useEffect(() => {
    if (prevLevelRef.current !== level) {
      prevLevelRef.current = level;
      setFlash(true);
      const t = window.setTimeout(() => setFlash(false), 400);
      return () => window.clearTimeout(t);
    }
    return undefined;
  }, [level]);

  const pendingEscalation = dwellMs > 0 && currentIdx < 4;

  return (
    <div
      className={`flex h-full min-h-0 flex-col gap-2 ${flash ? 'sv-ladder-flash' : ''}`}
      data-testid="intervention-bar"
    >
      <div className="flex items-center justify-between gap-2">
        <p className="text-[10px] uppercase tracking-wider text-sv-muted">
          Ladder
          {pendingEscalation ? (
            <span className="ml-2 font-mono text-risk-watch">
              dwell {(dwellMs / 1000).toFixed(1)}s
            </span>
          ) : null}
        </p>
        {typeof onOverrideClick === 'function' ? (
          <button
            type="button"
            onClick={onOverrideClick}
            className="rounded border border-sv-border px-2 py-0.5 text-[10px] uppercase tracking-wide text-sv-accent hover:bg-sv-elevated"
          >
            Override
          </button>
        ) : null}
      </div>

      <ol className="grid min-h-0 flex-1 grid-cols-5 gap-1">
        {STAGES.map((stage, idx) => {
          const passed = idx < currentIdx;
          const current = idx === currentIdx;
          const rising = current && prevIdx < currentIdx;
          return (
            <li
              key={stage.level}
              className={[
                'flex min-w-0 flex-col rounded border px-1.5 py-1 transition-all duration-[400ms] ease-out',
                current
                  ? 'border-sv-accent bg-sv-elevated shadow-[0_0_0_1px_rgba(56,189,248,0.35)] scale-[1.02]'
                  : passed
                    ? 'border-risk-clear/40 bg-risk-clear/10'
                    : 'border-sv-border/60 bg-sv-bg/40 opacity-70',
                rising ? 'sv-stage-rise' : '',
              ].join(' ')}
              aria-current={current ? 'step' : undefined}
            >
              <div className="flex items-baseline justify-between gap-1">
                <span
                  className={`font-mono text-[11px] font-semibold ${
                    current ? 'text-sv-accent' : passed ? 'text-risk-clear' : 'text-sv-muted'
                  }`}
                >
                  {stage.short}
                </span>
                {passed ? (
                  <span className="text-[9px] text-risk-clear" aria-hidden>
                    ✓
                  </span>
                ) : null}
              </div>
              <span className="truncate text-[10px] text-sv-fg">{stage.label}</span>
              <ul className="mt-auto space-y-0.5 pt-1">
                {stage.actions.map((a) => (
                  <li key={a} className="truncate font-mono text-[8px] text-sv-muted">
                    {a}
                  </li>
                ))}
              </ul>
            </li>
          );
        })}
      </ol>
    </div>
  );
}

InterventionBar.propTypes = {
  frame: PropTypes.object,
  onOverrideClick: PropTypes.func,
};

export { STAGES as INTERVENTION_STAGES };

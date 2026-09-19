import PropTypes from 'prop-types';
import { getLevel, getSmoothedRisk } from '@/contracts';

/**
 * §36 live-signal strip — green when each plane is producing usable evidence.
 *
 * @param {Object} props
 * @param {import('@/contracts').TelemetryFrame | null | undefined} props.frame
 * @param {boolean} [props.isRunning]
 * @param {string} [props.className]
 */
export function DemoStatusStrip({ frame, isRunning = false, className = '' }) {
  const families = frame?.families ?? {};
  const voiceOn = families.voice?.available === true;
  const asrOn =
    families.linguistic?.available === true ||
    Boolean(frame?.transcriptDelta?.text) ||
    Boolean(frame?.linguistic?.redactedSnippet);
  const contextOn =
    families.transaction?.available === true ||
    families.relationship?.available === true;
  const level = getLevel(frame);
  const levelOn = Boolean(level) && level !== 'LEVEL_1_SILENT';
  const risk = getSmoothedRisk(frame);
  const actuationOn =
    typeof level === 'string' &&
    (level.includes('LEVEL_3') || level.includes('LEVEL_4') || level.includes('LEVEL_5'));

  const chips = [
    { id: 'voice', label: 'Voice', on: voiceOn },
    { id: 'asr', label: 'ASR', on: asrOn },
    { id: 'context', label: 'Context', on: contextOn },
    {
      id: 'level',
      label: levelOn ? String(level).replace('LEVEL_', 'L').replace(/_/g, ' ') : 'Level',
      on: levelOn,
    },
    { id: 'actuation', label: 'Actuation', on: actuationOn },
  ];

  return (
    <div
      className={`flex flex-wrap items-center gap-2 rounded border border-sv-border bg-sv-panel px-2 py-1.5 ${className}`}
      data-testid="demo-status-strip"
      aria-label="Live signal status"
    >
      <span className="font-mono text-[10px] uppercase tracking-wider text-sv-muted">
        {isRunning ? 'Live signals' : 'Idle'}
      </span>
      {chips.map((c) => (
        <span
          key={c.id}
          className={`rounded px-2 py-0.5 font-mono text-[10px] font-semibold uppercase tracking-wide ${
            c.on ? 'bg-emerald-900/40 text-emerald-300' : 'bg-sv-bg text-sv-muted'
          }`}
        >
          {c.label}
        </span>
      ))}
      <span className="ml-auto font-mono text-[10px] tabular-nums text-sv-muted">
        risk {(risk * 100).toFixed(0)}%
      </span>
    </div>
  );
}

DemoStatusStrip.propTypes = {
  frame: PropTypes.object,
  isRunning: PropTypes.bool,
  className: PropTypes.string,
};

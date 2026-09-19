import PropTypes from 'prop-types';
import {
  SimulatedSignalBadge,
  isScenarioSeededText,
} from '@/components/SimulatedSignalBadge.jsx';

/**
 * Live transcript — prefers delta, falls back to linguistic redacted snippet.
 *
 * @param {Object} props
 * @param {import('@/contracts').TelemetryFrame | null | undefined} props.frame
 */
export function TranscriptPanel({ frame }) {
  const delta = frame?.transcriptDelta?.text;
  const snippet =
    frame?.linguistic?.redactedSnippet ||
    frame?.families?.linguistic?.redactedSnippet ||
    null;
  const text = (typeof delta === 'string' && delta.trim()) || (typeof snippet === 'string' && snippet.trim()) || null;
  const lang = frame?.linguistic?.language || frame?.transcriptDelta?.language || null;
  const seeded = isScenarioSeededText(snippet) || isScenarioSeededText(text);

  if (!text) {
    return (
      <p className="p-3 font-mono text-[11px] text-sv-muted">
        Waiting for ASR slow-path transcript…
      </p>
    );
  }

  return (
    <div className="flex h-full min-h-0 flex-col gap-1 overflow-auto p-3">
      <div className="flex flex-wrap items-center gap-2">
        {lang ? (
          <span className="font-mono text-[10px] uppercase tracking-wider text-sv-muted">{lang}</span>
        ) : null}
        {seeded ? <SimulatedSignalBadge label="simulated signal — scenario seed" /> : null}
      </div>
      <p className="whitespace-pre-wrap font-mono text-[12px] leading-relaxed text-sv-fg">{text}</p>
    </div>
  );
}

TranscriptPanel.propTypes = {
  frame: PropTypes.object,
};

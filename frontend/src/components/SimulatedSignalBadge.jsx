import PropTypes from 'prop-types';

/**
 * Honest label for scenario-seeded / fixture signals (not live ASR or carrier APIs).
 *
 * @param {Object} props
 * @param {string} [props.label]
 * @param {string} [props.className]
 */
export function SimulatedSignalBadge({ label = 'simulated signal', className = '' }) {
  return (
    <span
      className={`inline-flex items-center rounded border border-amber-700/60 bg-amber-950/50 px-1.5 py-0.5 font-mono text-[9px] font-semibold uppercase tracking-wide text-amber-200 ${className}`}
      title="Demo fixture — not derived from live ASR, carrier, or bank APIs"
      data-testid="simulated-signal-badge"
    >
      {label}
    </span>
  );
}

SimulatedSignalBadge.propTypes = {
  label: PropTypes.string,
  className: PropTypes.string,
};

/**
 * @param {string | null | undefined} text
 * @returns {boolean}
 */
export function isScenarioSeededText(text) {
  if (typeof text !== 'string') return false;
  const t = text.toLowerCase();
  return t.includes('scenario-seeded') || t.includes('[scenario-seeded');
}

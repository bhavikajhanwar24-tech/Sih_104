import PropTypes from 'prop-types';

const VARIANTS = ['neutral', 'accent', 'live', 'fault', 'risk'];

/**
 * Compact status / label chip.
 *
 * Risk variant uses the risk ramp only when {@code riskLevel} is set.
 * Infra states use live/fault (never risk red/green).
 *
 * @param {Object} props
 * @param {React.ReactNode} props.children
 * @param {'neutral'|'accent'|'live'|'fault'|'risk'} [props.variant='neutral']
 * @param {'clear'|'watch'|'elevated'|'critical'} [props.riskLevel] required when variant=risk
 * @param {string} [props.className]
 * @param {string} [props.title]
 */
export function Badge({
  children,
  variant = 'neutral',
  riskLevel,
  className = '',
  title,
}) {
  let styles = 'bg-sv-elevated text-sv-muted border-sv-border';
  if (variant === 'accent') styles = 'bg-sv-accent/15 text-sv-accent border-sv-accent/40';
  if (variant === 'live') styles = 'bg-sv-accent/15 text-sv-accent border-sv-accent/40';
  if (variant === 'fault') styles = 'bg-sv-fault/15 text-sv-fault border-sv-fault/40';
  if (variant === 'risk') {
    const map = {
      clear: 'bg-risk-clear/15 text-risk-clear border-risk-clear/40',
      watch: 'bg-risk-watch/15 text-risk-watch border-risk-watch/40',
      elevated: 'bg-risk-elevated/15 text-risk-elevated border-risk-elevated/40',
      critical: 'bg-risk-critical/15 text-risk-critical border-risk-critical/40',
    };
    styles = map[riskLevel] || map.watch;
  }

  return (
    <span
      title={title}
      className={`inline-flex max-w-full items-center rounded border px-1.5 py-0.5 font-mono text-[10px] font-medium uppercase tracking-wide ${styles} ${className}`}
    >
      {children}
    </span>
  );
}

Badge.propTypes = {
  children: PropTypes.node.isRequired,
  variant: PropTypes.oneOf(VARIANTS),
  riskLevel: PropTypes.oneOf(['clear', 'watch', 'elevated', 'critical']),
  className: PropTypes.string,
  title: PropTypes.string,
};

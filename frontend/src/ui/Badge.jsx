import PropTypes from 'prop-types';
import clsx from 'clsx';

const TONES = {
  neutral: 'bg-sv-elevated text-sv-muted border-sv-border',
  accent: 'bg-sv-accent/15 text-sv-accent border-sv-accent/30',
  success: 'bg-risk-clear/15 text-risk-clear border-risk-clear/30',
  warn: 'bg-risk-watch/15 text-risk-watch border-risk-watch/30',
  danger: 'bg-risk-critical/15 text-risk-critical border-risk-critical/30',
};

/**
 * @param {Object} props
 */
export function Badge({ children, tone = 'neutral', className }) {
  return (
    <span
      className={clsx(
        'inline-flex items-center rounded border px-2 py-0.5 text-xs font-medium',
        TONES[tone] || TONES.neutral,
        className,
      )}
    >
      {children}
    </span>
  );
}

Badge.propTypes = {
  children: PropTypes.node,
  tone: PropTypes.oneOf(['neutral', 'accent', 'success', 'warn', 'danger']),
  className: PropTypes.string,
};

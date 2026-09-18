import PropTypes from 'prop-types';

const VARIANTS = ['default', 'accent', 'mono'];

/**
 * Key/value statistic for the ops console.
 *
 * @param {Object} props
 * @param {string} props.label
 * @param {React.ReactNode} props.value
 * @param {'default'|'accent'|'mono'} [props.variant='mono']
 * @param {string} [props.className]
 * @param {string} [props.hint]
 */
export function Stat({ label, value, variant = 'mono', className = '', hint }) {
  const valueClass =
    variant === 'accent'
      ? 'font-mono text-sv-accent tabular-nums'
      : variant === 'default'
        ? 'font-display text-sv-fg'
        : 'font-mono text-sv-fg tabular-nums';

  return (
    <div className={`flex min-w-0 flex-col gap-0.5 ${className}`} title={hint}>
      <span className="text-[10px] uppercase tracking-wider text-sv-muted">{label}</span>
      <span className={`truncate text-sm font-medium ${valueClass}`}>{value}</span>
    </div>
  );
}

Stat.propTypes = {
  label: PropTypes.string.isRequired,
  value: PropTypes.node.isRequired,
  variant: PropTypes.oneOf(VARIANTS),
  className: PropTypes.string,
  hint: PropTypes.string,
};

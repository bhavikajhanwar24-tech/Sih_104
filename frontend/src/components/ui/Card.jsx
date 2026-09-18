import PropTypes from 'prop-types';

const VARIANTS = ['default', 'elevated', 'ghost'];

/**
 * Elevated surface container.
 *
 * @param {Object} props
 * @param {React.ReactNode} [props.children]
 * @param {'default'|'elevated'|'ghost'} [props.variant='default']
 * @param {string} [props.className]
 * @param {string} [props.title]
 */
export function Card({ children, variant = 'default', className = '', title }) {
  const surface =
    variant === 'elevated'
      ? 'bg-sv-elevated border-sv-border'
      : variant === 'ghost'
        ? 'bg-transparent border-transparent'
        : 'bg-sv-panel border-sv-border';

  return (
    <div className={`rounded border ${surface} ${className}`}>
      {title ? (
        <div className="border-b border-sv-border px-3 py-2">
          <h3 className="font-display text-xs font-semibold uppercase tracking-wider text-sv-muted">
            {title}
          </h3>
        </div>
      ) : null}
      <div className="p-3">{children}</div>
    </div>
  );
}

Card.propTypes = {
  children: PropTypes.node,
  variant: PropTypes.oneOf(VARIANTS),
  className: PropTypes.string,
  title: PropTypes.string,
};

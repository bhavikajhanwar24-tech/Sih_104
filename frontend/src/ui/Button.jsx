import PropTypes from 'prop-types';
import clsx from 'clsx';

const VARIANTS = {
  primary:
    'bg-sv-accent text-sv-bg hover:brightness-110 focus-visible:ring-sv-accent disabled:opacity-50',
  secondary:
    'border border-sv-border bg-sv-elevated text-sv-fg hover:border-sv-accent/60 focus-visible:ring-sv-accent disabled:opacity-50',
  ghost:
    'text-sv-muted hover:bg-sv-elevated hover:text-sv-fg focus-visible:ring-sv-accent disabled:opacity-50',
  danger:
    'border border-risk-critical/40 bg-risk-critical/10 text-risk-critical hover:bg-risk-critical/20 focus-visible:ring-risk-critical disabled:opacity-50',
};

/**
 * @param {Object} props
 */
export function Button({
  children,
  variant = 'primary',
  type = 'button',
  className,
  disabled,
  ...rest
}) {
  return (
    <button
      type={type}
      disabled={disabled}
      className={clsx(
        'inline-flex items-center justify-center gap-2 rounded-md px-3.5 py-2 text-sm font-medium transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-offset-2 focus-visible:ring-offset-sv-bg',
        VARIANTS[variant] || VARIANTS.primary,
        className,
      )}
      {...rest}
    >
      {children}
    </button>
  );
}

Button.propTypes = {
  children: PropTypes.node,
  variant: PropTypes.oneOf(['primary', 'secondary', 'ghost', 'danger']),
  type: PropTypes.oneOf(['button', 'submit', 'reset']),
  className: PropTypes.string,
  disabled: PropTypes.bool,
};

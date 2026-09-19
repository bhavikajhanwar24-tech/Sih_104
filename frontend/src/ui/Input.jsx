import PropTypes from 'prop-types';
import clsx from 'clsx';
import { useId } from 'react';

/**
 * @param {Object} props
 */
export function Input({
  label,
  type = 'text',
  error,
  className,
  id,
  hint,
  ...rest
}) {
  const autoId = useId();
  const inputId = id || autoId;
  return (
    <label className={clsx('flex flex-col gap-1.5', className)} htmlFor={inputId}>
      {label ? (
        <span className="text-sm font-medium text-sv-fg">{label}</span>
      ) : null}
      <input
        id={inputId}
        type={type}
        className={clsx(
          'w-full rounded-md border bg-sv-panel px-3 py-2 text-sm text-sv-fg placeholder:text-sv-muted/70',
          'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-sv-accent focus-visible:ring-offset-2 focus-visible:ring-offset-sv-bg',
          error ? 'border-risk-critical' : 'border-sv-border',
        )}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? `${inputId}-err` : hint ? `${inputId}-hint` : undefined}
        {...rest}
      />
      {hint && !error ? (
        <span id={`${inputId}-hint`} className="text-xs text-sv-muted">
          {hint}
        </span>
      ) : null}
      {error ? (
        <span id={`${inputId}-err`} className="text-xs text-risk-critical" role="alert">
          {error}
        </span>
      ) : null}
    </label>
  );
}

Input.propTypes = {
  label: PropTypes.string,
  type: PropTypes.string,
  error: PropTypes.string,
  className: PropTypes.string,
  id: PropTypes.string,
  hint: PropTypes.string,
};

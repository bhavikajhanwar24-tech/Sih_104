import PropTypes from 'prop-types';
import clsx from 'clsx';
import { useId } from 'react';

/**
 * @param {Object} props
 * @param {{ value: string, label: string }[]} [props.options]
 */
export function Select({ label, options, error, className, id, children, ...rest }) {
  const autoId = useId();
  const selectId = id || autoId;
  return (
    <label className={clsx('flex flex-col gap-1.5', className)} htmlFor={selectId}>
      {label ? (
        <span className="text-sm font-medium text-sv-fg">{label}</span>
      ) : null}
      <select
        id={selectId}
        className={clsx(
          'w-full rounded-md border bg-sv-panel px-3 py-2 text-sm text-sv-fg',
          'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-sv-accent focus-visible:ring-offset-2 focus-visible:ring-offset-sv-bg',
          error ? 'border-risk-critical' : 'border-sv-border',
        )}
        aria-invalid={error ? true : undefined}
        {...rest}
      >
        {children
          ? children
          : (options || []).map((opt) => (
              <option key={opt.value} value={opt.value}>
                {opt.label}
              </option>
            ))}
      </select>
      {error ? (
        <span className="text-xs text-risk-critical" role="alert">
          {error}
        </span>
      ) : null}
    </label>
  );
}

Select.propTypes = {
  label: PropTypes.string,
  options: PropTypes.arrayOf(
    PropTypes.shape({ value: PropTypes.string.isRequired, label: PropTypes.string.isRequired }),
  ),
  error: PropTypes.string,
  className: PropTypes.string,
  id: PropTypes.string,
  children: PropTypes.node,
};

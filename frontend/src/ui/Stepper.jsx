import PropTypes from 'prop-types';
import clsx from 'clsx';

/**
 * @param {Object} props
 * @param {string[]} props.steps
 * @param {number} props.current  0-based
 */
export function Stepper({ steps, current }) {
  return (
    <ol className="flex flex-wrap items-center gap-2" aria-label="Progress">
      {steps.map((label, i) => {
        const done = i < current;
        const active = i === current;
        return (
          <li key={label} className="flex items-center gap-2">
            <span
              className={clsx(
                'flex h-7 w-7 items-center justify-center rounded-full text-xs font-semibold',
                done && 'bg-sv-accent text-sv-bg',
                active && 'border-2 border-sv-accent text-sv-accent',
                !done && !active && 'border border-sv-border text-sv-muted',
              )}
              aria-current={active ? 'step' : undefined}
            >
              {i + 1}
            </span>
            <span
              className={clsx(
                'text-sm',
                active ? 'font-medium text-sv-fg' : 'text-sv-muted',
              )}
            >
              {label}
            </span>
            {i < steps.length - 1 ? (
              <span className="mx-1 hidden h-px w-6 bg-sv-border sm:inline-block" aria-hidden />
            ) : null}
          </li>
        );
      })}
    </ol>
  );
}

Stepper.propTypes = {
  steps: PropTypes.arrayOf(PropTypes.string).isRequired,
  current: PropTypes.number.isRequired,
};

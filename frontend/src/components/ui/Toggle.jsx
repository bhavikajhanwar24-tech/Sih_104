import PropTypes from 'prop-types';

const VARIANTS = ['default', 'accent'];

/**
 * Binary on/off control.
 *
 * @param {Object} props
 * @param {boolean} props.checked
 * @param {(next: boolean) => void} props.onChange
 * @param {string} [props.label]
 * @param {boolean} [props.disabled=false]
 * @param {'default'|'accent'} [props.variant='accent']
 * @param {string} [props.className]
 * @param {string} [props.id]
 */
export function Toggle({
  checked,
  onChange,
  label,
  disabled = false,
  variant = 'accent',
  className = '',
  id,
}) {
  const on = Boolean(checked);
  const trackOn = variant === 'accent' ? 'bg-sv-accent' : 'bg-sv-fault';

  return (
    <label
      className={`inline-flex cursor-pointer items-center gap-2 ${disabled ? 'opacity-50' : ''} ${className}`}
      htmlFor={id}
    >
      <button
        id={id}
        type="button"
        role="switch"
        aria-checked={on}
        disabled={disabled}
        onClick={() => onChange(!on)}
        className={`relative h-5 w-9 shrink-0 rounded-full border border-sv-border transition-colors ${
          on ? trackOn : 'bg-sv-elevated'
        }`}
      >
        <span
          className={`absolute top-0.5 h-3.5 w-3.5 rounded-full bg-sv-fg transition-transform ${
            on ? 'left-4' : 'left-0.5'
          }`}
        />
      </button>
      {label ? <span className="text-xs text-sv-fg">{label}</span> : null}
    </label>
  );
}

Toggle.propTypes = {
  checked: PropTypes.bool.isRequired,
  onChange: PropTypes.func.isRequired,
  label: PropTypes.string,
  disabled: PropTypes.bool,
  variant: PropTypes.oneOf(VARIANTS),
  className: PropTypes.string,
  id: PropTypes.string,
};

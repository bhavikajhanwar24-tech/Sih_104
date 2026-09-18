import PropTypes from 'prop-types';

const STYLES = {
  normal: {
    bg: 'bg-[#0a7a2f]',
    border: 'border-[#064d1d]',
    text: 'text-white',
  },
  caution: {
    bg: 'bg-[#ff9800]',
    border: 'border-[#9a4a00]',
    text: 'text-[#1a1a1a]',
  },
  danger: {
    bg: 'bg-[#c62828]',
    border: 'border-[#7f0000]',
    text: 'text-white',
  },
};

/**
 * Three-state status only — no numbers, no jargon.
 *
 * @param {Object} props
 * @param {'normal'|'caution'|'danger'} props.state
 * @param {string} props.title
 * @param {string} props.hint
 */
export function SimpleStatus({ state, title, hint }) {
  const s = STYLES[state] ?? STYLES.normal;
  return (
    <div
      className={`flex min-h-[160px] flex-col items-center justify-center rounded-2xl border-4 ${s.border} ${s.bg} ${s.text} px-4 py-6 text-center`}
      role="status"
      aria-live="polite"
      data-shield-state={state}
    >
      <p className="max-w-[20ch] text-[48px] font-black leading-tight tracking-tight sm:text-[52px]">
        {title}
      </p>
      <p className="mt-4 max-w-[28ch] text-[24px] font-semibold leading-snug opacity-95">
        {hint}
      </p>
    </div>
  );
}

SimpleStatus.propTypes = {
  state: PropTypes.oneOf(['normal', 'caution', 'danger']).isRequired,
  title: PropTypes.string.isRequired,
  hint: PropTypes.string.isRequired,
};

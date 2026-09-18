import PropTypes from 'prop-types';
import { SHIELD_LANGS } from '@/components/senior/shieldCopy.js';

/**
 * Large language chips — English / Hindi / Tamil / Telugu.
 *
 * @param {Object} props
 * @param {string} props.value
 * @param {(id: string) => void} props.onChange
 * @param {string} [props.label]
 */
export function LanguageToggle({ value, onChange, label = 'Language' }) {
  return (
    <div className="flex flex-col gap-2">
      <p className="text-[20px] font-semibold text-[#1a1a1a]">{label}</p>
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-4" role="group" aria-label={label}>
        {SHIELD_LANGS.map((lang) => {
          const selected = value === lang.id;
          return (
            <button
              key={lang.id}
              type="button"
              onClick={() => onChange(lang.id)}
              className={`min-h-[64px] rounded-xl border-4 px-2 py-2 text-[20px] font-bold leading-tight transition-colors ${
                selected
                  ? 'border-[#0b3d91] bg-[#0b3d91] text-white'
                  : 'border-[#1a1a1a] bg-white text-[#1a1a1a]'
              }`}
              aria-pressed={selected}
            >
              <span className="block">{lang.native}</span>
            </button>
          );
        })}
      </div>
    </div>
  );
}

LanguageToggle.propTypes = {
  value: PropTypes.string.isRequired,
  onChange: PropTypes.func.isRequired,
  label: PropTypes.string,
};

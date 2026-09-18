import { useState } from 'react';
import PropTypes from 'prop-types';

/**
 * Large "Alert My Family" control with an unmissable confirmation.
 *
 * @param {Object} props
 * @param {string} props.label
 * @param {string} props.confirmTitle
 * @param {string} props.confirmBody
 * @param {string} props.contactLine
 * @param {boolean} [props.disabled]
 */
export function SosButton({ label, confirmTitle, confirmBody, contactLine, disabled = false }) {
  const [sent, setSent] = useState(false);

  return (
    <div className="flex flex-col gap-3">
      <button
        type="button"
        disabled={disabled || sent}
        onClick={() => setSent(true)}
        className="min-h-[72px] w-full rounded-2xl border-4 border-[#0b3d91] bg-[#1565c0] px-4 py-4 text-[28px] font-black text-white shadow-md disabled:opacity-60"
        data-testid="senior-sos"
      >
        {label}
      </button>

      {sent ? (
        <div
          className="rounded-2xl border-4 border-[#0a7a2f] bg-[#e8f5e9] px-4 py-5 text-[#064d1d]"
          role="status"
          aria-live="assertive"
          data-testid="senior-sos-confirm"
        >
          <p className="text-[28px] font-black leading-tight">{confirmTitle}</p>
          <p className="mt-3 text-[22px] font-semibold leading-snug">{confirmBody}</p>
          <p className="mt-3 text-[20px] font-medium">{contactLine}</p>
        </div>
      ) : null}
    </div>
  );
}

SosButton.propTypes = {
  label: PropTypes.string.isRequired,
  confirmTitle: PropTypes.string.isRequired,
  confirmBody: PropTypes.string.isRequired,
  contactLine: PropTypes.string.isRequired,
  disabled: PropTypes.bool,
};

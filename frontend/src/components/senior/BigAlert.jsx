import { useEffect, useRef } from 'react';
import PropTypes from 'prop-types';

/**
 * Full-screen high-risk alert — pulse + optional Web Speech spoken warning.
 *
 * @param {Object} props
 * @param {boolean} props.open
 * @param {string} props.title
 * @param {string} props.body
 * @param {string} props.instruction
 * @param {string} props.spokenText
 * @param {string} [props.lang]
 * @param {boolean} [props.speak=true]
 */
export function BigAlert({
  open,
  title,
  body,
  instruction,
  spokenText,
  lang = 'en-IN',
  speak = true,
}) {
  const spokenForOpen = useRef(false);

  useEffect(() => {
    if (!open) {
      spokenForOpen.current = false;
      if (typeof window !== 'undefined' && window.speechSynthesis) {
        window.speechSynthesis.cancel();
      }
      return undefined;
    }
    if (!speak || spokenForOpen.current) return undefined;
    spokenForOpen.current = true;

    if (typeof window === 'undefined' || !window.speechSynthesis) return undefined;
    const utter = new SpeechSynthesisUtterance(spokenText);
    utter.lang = lang;
    utter.rate = 0.9;
    utter.volume = 1;
    const t = window.setTimeout(() => {
      try {
        window.speechSynthesis.speak(utter);
      } catch {
        /* speech optional */
      }
    }, 400);
    return () => {
      window.clearTimeout(t);
      window.speechSynthesis.cancel();
    };
  }, [open, speak, spokenText, lang]);

  if (!open) return null;

  return (
    <div
      className="fixed inset-0 z-50 flex flex-col items-center justify-center bg-[#b71c1c] px-5 py-8 text-center text-white"
      role="alertdialog"
      aria-modal="true"
      aria-label={title}
      data-testid="senior-big-alert"
    >
      <div className="senior-pulse flex max-w-md flex-col items-center gap-5">
        <p className="text-[56px] font-black leading-none tracking-wide sm:text-[64px]">{title}</p>
        <p className="text-[28px] font-bold leading-snug sm:text-[32px]">{body}</p>
        <p className="mt-2 rounded-2xl border-4 border-white bg-[#7f0000] px-4 py-5 text-[26px] font-extrabold leading-snug sm:text-[28px]">
          {instruction}
        </p>
      </div>
      <style>{`
        @keyframes senior-pulse {
          0%, 100% { transform: scale(1); opacity: 1; }
          50% { transform: scale(1.03); opacity: 0.92; }
        }
        .senior-pulse {
          animation: senior-pulse 1.6s ease-in-out infinite;
        }
      `}</style>
    </div>
  );
}

BigAlert.propTypes = {
  open: PropTypes.bool.isRequired,
  title: PropTypes.string.isRequired,
  body: PropTypes.string.isRequired,
  instruction: PropTypes.string.isRequired,
  spokenText: PropTypes.string.isRequired,
  lang: PropTypes.string,
  speak: PropTypes.bool,
};

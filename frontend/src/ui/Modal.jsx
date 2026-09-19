import PropTypes from 'prop-types';
import { useEffect, useRef } from 'react';
import { Button } from '@/ui/Button.jsx';

/**
 * @param {Object} props
 */
export function Modal({ open, title, children, onClose, footer, wide = false }) {
  const panelRef = useRef(/** @type {HTMLDivElement | null} */ (null));
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;
  const prevFocusRef = useRef(/** @type {Element | null} */ (null));

  // Only re-run when `open` flips. Do NOT list `onClose` — parents pass inline
  // lambdas that change every keystroke and would re-focus (steal caret) each letter.
  useEffect(() => {
    if (!open) return undefined;

    prevFocusRef.current = document.activeElement;
    const onKey = (e) => {
      if (e.key === 'Escape') onCloseRef.current?.();
    };
    window.addEventListener('keydown', onKey);

    const id = window.requestAnimationFrame(() => {
      const root = panelRef.current;
      if (!root) return;
      // Prefer a field; never the header ✕ (first button in the panel DOM).
      const field = root.querySelector(
        'input:not([type="hidden"]):not([disabled]), textarea:not([disabled]), select:not([disabled])',
      );
      const target = /** @type {HTMLElement | null} */ (
        field || root.querySelector('button:not([aria-label="Close"]), [href]')
      );
      target?.focus?.();
    });

    return () => {
      window.cancelAnimationFrame(id);
      window.removeEventListener('keydown', onKey);
      const prev = prevFocusRef.current;
      if (prev && 'focus' in prev) {
        try {
          /** @type {HTMLElement} */ (prev).focus();
        } catch {
          /* ignore */
        }
      }
    };
  }, [open]);

  if (!open) return null;

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center p-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby="sv-modal-title"
    >
      <button
        type="button"
        className="absolute inset-0 bg-black/55"
        aria-label="Close dialog"
        onClick={() => onCloseRef.current?.()}
      />
      <div
        ref={panelRef}
        className={`relative z-10 w-full rounded-lg border border-sv-border bg-sv-panel shadow-xl ${
          wide ? 'max-w-3xl' : 'max-w-lg'
        }`}
      >
        <div className="flex items-start justify-between gap-3 border-b border-sv-border px-5 py-4">
          <h2 id="sv-modal-title" className="font-display text-lg font-semibold text-sv-fg">
            {title}
          </h2>
          <Button
            variant="ghost"
            onClick={() => onCloseRef.current?.()}
            aria-label="Close"
          >
            ✕
          </Button>
        </div>
        <div className="px-5 py-4">{children}</div>
        {footer ? (
          <div className="flex justify-end gap-2 border-t border-sv-border px-5 py-3">{footer}</div>
        ) : null}
      </div>
    </div>
  );
}

Modal.propTypes = {
  open: PropTypes.bool.isRequired,
  title: PropTypes.string.isRequired,
  children: PropTypes.node,
  onClose: PropTypes.func,
  footer: PropTypes.node,
  wide: PropTypes.bool,
};

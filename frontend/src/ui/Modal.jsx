import PropTypes from 'prop-types';
import { useEffect, useRef } from 'react';
import { Button } from '@/ui/Button.jsx';

/**
 * @param {Object} props
 */
export function Modal({ open, title, children, onClose, footer, wide = false }) {
  const panelRef = useRef(/** @type {HTMLDivElement | null} */ (null));

  useEffect(() => {
    if (!open) return undefined;
    const onKey = (e) => {
      if (e.key === 'Escape') onClose?.();
    };
    window.addEventListener('keydown', onKey);
    const prev = document.activeElement;
    panelRef.current?.querySelector('button, [href], input, select, textarea')?.focus?.();
    return () => {
      window.removeEventListener('keydown', onKey);
      if (prev && 'focus' in prev) /** @type {HTMLElement} */ (prev).focus();
    };
  }, [open, onClose]);

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
        onClick={onClose}
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
          <Button variant="ghost" onClick={onClose} aria-label="Close">
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

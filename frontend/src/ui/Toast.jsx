import PropTypes from 'prop-types';
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from 'react';
import { setApiErrorHandler } from '@/services/api.js';

/**
 * @typedef {{ id: number, message: string, tone?: string }} ToastItem
 */

const ToastContext = createContext(/** @type {null | { push: (msg: string, tone?: string) => void }} */ (null));

let nextId = 1;

/**
 * @param {Object} props
 */
export function ToastProvider({ children }) {
  const [items, setItems] = useState(/** @type {ToastItem[]} */ ([]));

  const push = useCallback((message, tone = 'danger') => {
    const id = nextId++;
    setItems((prev) => [...prev, { id, message, tone }]);
    window.setTimeout(() => {
      setItems((prev) => prev.filter((t) => t.id !== id));
    }, 5000);
  }, []);

  useEffect(() => {
    setApiErrorHandler((err) => {
      const msg = String(err?.message || '');
      // Slow Supabase / hung poll — never spam the corner with this.
      if (err?.code === 'timeout' || /timed out/i.test(msg)) return;
      push(msg || 'Request failed');
    });
    return () => setApiErrorHandler(null);
  }, [push]);

  const value = useMemo(() => ({ push }), [push]);

  return (
    <ToastContext.Provider value={value}>
      {children}
      <div
        className="pointer-events-none fixed bottom-4 right-4 z-[60] flex w-full max-w-sm flex-col gap-2"
        aria-live="polite"
      >
        {items.map((t) => (
          <div
            key={t.id}
            className={`pointer-events-auto rounded-md border px-4 py-3 text-sm shadow-lg animate-[sv-slide-in_0.25s_ease] ${
              t.tone === 'danger'
                ? 'border-risk-critical/40 bg-sv-panel text-risk-critical'
                : 'border-sv-border bg-sv-panel text-sv-fg'
            }`}
            role="status"
          >
            {t.message}
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

ToastProvider.propTypes = {
  children: PropTypes.node.isRequired,
};

export function useToast() {
  const ctx = useContext(ToastContext);
  if (!ctx) throw new Error('useToast must be used within ToastProvider');
  return ctx;
}

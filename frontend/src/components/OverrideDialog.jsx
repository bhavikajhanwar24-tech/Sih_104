import { useEffect, useMemo, useState } from 'react';
import PropTypes from 'prop-types';
import { INTERVENTION_LEVELS } from '@/contracts';

const LEVEL_OPTIONS = Object.values(INTERVENTION_LEVELS);

/**
 * Analyst override dialog — reason ≥ 10 chars; permanently audited.
 *
 * @param {Object} props
 * @param {boolean} props.open
 * @param {() => void} props.onClose
 * @param {string} props.sessionId
 * @param {string} [props.currentLevel]
 * @param {(result: object) => void} [props.onSuccess]
 */
export function OverrideDialog({ open, onClose, sessionId, currentLevel, onSuccess }) {
  const [analystId, setAnalystId] = useState('analyst-demo');
  const [targetLevel, setTargetLevel] = useState(
    currentLevel ?? INTERVENTION_LEVELS.LEVEL_2_SOFT_NUDGE,
  );
  const [reason, setReason] = useState('');
  const [error, setError] = useState(/** @type {string | null} */ (null));
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!open) return;
    setTargetLevel(currentLevel ?? INTERVENTION_LEVELS.LEVEL_2_SOFT_NUDGE);
    setReason('');
    setError(null);
  }, [open, currentLevel]);

  const reasonOk = reason.trim().length >= 10;
  const canSubmit = useMemo(
    () => Boolean(sessionId) && analystId.trim().length > 0 && reasonOk && !busy,
    [sessionId, analystId, reasonOk, busy],
  );

  if (!open) return null;

  async function onSubmit(e) {
    e.preventDefault();
    setError(null);
    if (reason.trim().length < 10) {
      setError('Reason must be at least 10 characters (UI + API enforced).');
      return;
    }
    setBusy(true);
    try {
      const res = await fetch(
        `/api/v1/intervention/${encodeURIComponent(sessionId)}/override`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            analystId: analystId.trim(),
            reason: reason.trim(),
            targetLevel,
          }),
        },
      );
      const body = await res.json().catch(() => ({}));
      if (!res.ok) {
        const msg =
          body.message ||
          body.detail ||
          (Array.isArray(body.errors) ? body.errors.join('; ') : null) ||
          `Override rejected (${res.status})`;
        setError(String(msg));
        return;
      }
      onSuccess?.(body);
      onClose();
      setReason('');
    } catch (err) {
      setError(err instanceof Error ? err.message : 'network error');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby="override-title"
      data-testid="override-dialog"
    >
      <form
        onSubmit={onSubmit}
        className="w-full max-w-md rounded border border-sv-border bg-sv-panel p-4 shadow-xl"
      >
        <h2 id="override-title" className="font-display text-sm font-semibold text-sv-fg">
          Intervention override
        </h2>
        <p className="mt-2 rounded border border-risk-watch/40 bg-risk-watch/10 px-2 py-1.5 text-[11px] text-risk-watch">
          Warning: this override is permanently recorded in the audit ledger and cannot be
          erased.
        </p>

        <label className="mt-3 flex flex-col gap-0.5 text-[11px]">
          <span className="text-sv-muted">Analyst ID</span>
          <input
            className="rounded border border-sv-border bg-sv-bg px-2 py-1.5 text-sv-fg"
            value={analystId}
            onChange={(e) => setAnalystId(e.target.value)}
            required
          />
        </label>

        <label className="mt-2 flex flex-col gap-0.5 text-[11px]">
          <span className="text-sv-muted">Target level</span>
          <select
            className="rounded border border-sv-border bg-sv-bg px-2 py-1.5 font-mono text-sv-fg"
            value={targetLevel}
            onChange={(e) => setTargetLevel(e.target.value)}
          >
            {LEVEL_OPTIONS.map((l) => (
              <option key={l} value={l}>
                {l}
              </option>
            ))}
          </select>
        </label>

        <label className="mt-2 flex flex-col gap-0.5 text-[11px]">
          <span className="text-sv-muted">Reason (min 10 characters)</span>
          <textarea
            className="min-h-[4.5rem] rounded border border-sv-border bg-sv-bg px-2 py-1.5 text-sv-fg"
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            placeholder="Mandatory free-text justification…"
            required
            minLength={10}
          />
          <span
            className={`font-mono text-[10px] ${
              reasonOk ? 'text-risk-clear' : 'text-risk-critical'
            }`}
          >
            {reason.trim().length}/10
          </span>
        </label>

        {error ? (
          <p className="mt-2 text-[11px] text-risk-critical" role="alert">
            {error}
          </p>
        ) : null}

        <div className="mt-4 flex justify-end gap-2">
          <button
            type="button"
            onClick={onClose}
            className="rounded border border-sv-border px-3 py-1.5 text-[11px] text-sv-muted hover:text-sv-fg"
          >
            Cancel
          </button>
          <button
            type="submit"
            disabled={!canSubmit}
            className="rounded bg-sv-accent px-3 py-1.5 text-[11px] font-semibold text-sv-bg disabled:opacity-40"
          >
            {busy ? 'Recording…' : 'Commit override'}
          </button>
        </div>
      </form>
    </div>
  );
}

OverrideDialog.propTypes = {
  open: PropTypes.bool.isRequired,
  onClose: PropTypes.func.isRequired,
  sessionId: PropTypes.string.isRequired,
  currentLevel: PropTypes.string,
  onSuccess: PropTypes.func,
};

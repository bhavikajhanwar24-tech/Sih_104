import { useEffect, useMemo, useState } from 'react';
import PropTypes from 'prop-types';
import { INTERVENTION_LEVELS } from '@/contracts';
import { apiFetch } from '@/services/api.js';

const LOCK_LEVELS = new Set([
  INTERVENTION_LEVELS.LEVEL_3_STEP_UP_MFA,
  INTERVENTION_LEVELS.LEVEL_4_AUTO_HOLD,
  INTERVENTION_LEVELS.LEVEL_5_TERMINATE,
]);

/**
 * Operator transaction panel. Approve locks from telemetry level and/or plan lock action.
 *
 * @param {Object} props
 * @param {string | null | undefined} props.sessionId
 * @param {import('@/contracts').TelemetryFrame | null | undefined} props.frame
 * @param {boolean} [props.locked]
 * @param {string | null | undefined} [props.lockReason]
 */
export function TransactionPanel({ sessionId, frame, locked = false, lockReason = null }) {
  const level = frame?.intervention?.level ?? INTERVENTION_LEVELS.LEVEL_1_SILENT;
  /** Server-authoritative lock — never a local useState toggle. */
  const lockedByTelemetry = locked || LOCK_LEVELS.has(level);

  const askedAmount =
    frame?.linguistic?.amountAsked ??
    frame?.families?.transaction?.amountAsked ??
    frame?.transaction?.amountAsked ??
    null;

  const [beneficiary, setBeneficiary] = useState('');
  const [ifsc, setIfsc] = useState('');
  const [amount, setAmount] = useState('');
  const [purpose, setPurpose] = useState('');
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState(/** @type {string | null} */ (null));
  const [mfaState, setMfaState] = useState(/** @type {'pending'|'approved'|'denied'} */ ('pending'));
  const [mfaSeconds, setMfaSeconds] = useState(45);

  useEffect(() => {
    if (askedAmount != null && askedAmount !== '') {
      setAmount(String(askedAmount));
    }
  }, [askedAmount]);

  const showMfa = level === INTERVENTION_LEVELS.LEVEL_3_STEP_UP_MFA;

  useEffect(() => {
    if (!showMfa) {
      setMfaState('pending');
      setMfaSeconds(45);
      return undefined;
    }
    setMfaState('pending');
    setMfaSeconds(45);
    const id = window.setInterval(() => {
      setMfaSeconds((s) => (s <= 1 ? 0 : s - 1));
    }, 1000);
    return () => window.clearInterval(id);
  }, [showMfa, level]);

  const lockOverlay = useMemo(
    () =>
      lockedByTelemetry
        ? lockReason || 'Locked by SentinelVoice — confirm callback / step-up required'
        : null,
    [lockedByTelemetry, lockReason],
  );

  async function onApprove() {
    if (!sessionId || lockedByTelemetry) return;
    setBusy(true);
    setResult(null);
    try {
      // Always hit the server — UI disable is UX; 423 proves the lock is not CSS-only.
      const res = await apiFetch(
        `/api/v1/transaction/${encodeURIComponent(sessionId)}/approve`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ actorId: 'browser-teller' }),
        },
      );
      const body = await res.json().catch(() => ({}));
      if (res.status === 423) {
        setResult(`423 LOCKED — ${body.reason ?? 'server lock'}`);
      } else if (!res.ok) {
        setResult(`Approve failed (${res.status})`);
      } else {
        setResult('Approved');
      }
    } catch (err) {
      setResult(err instanceof Error ? err.message : 'network error');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="relative flex h-full min-h-0 flex-col gap-2" data-testid="transaction-panel">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-[10px] uppercase tracking-wider text-sv-muted">Sensitive action</p>
        <span
          className={`font-mono text-[10px] ${
            lockedByTelemetry ? 'text-risk-critical' : 'text-risk-clear'
          }`}
        >
          {lockedByTelemetry ? 'LOCKED' : 'OPEN'} · {level.replace('LEVEL_', 'L').split('_')[0]}
        </span>
      </div>

      <div className="grid grid-cols-2 gap-2 text-[11px]">
        <label className="col-span-2 flex flex-col gap-0.5">
          <span className="text-sv-muted">Counterparty</span>
          <input
            className="rounded border border-sv-border bg-sv-bg px-2 py-1 text-sv-fg"
            value={beneficiary}
            onChange={(e) => setBeneficiary(e.target.value)}
            disabled={lockedByTelemetry}
          />
        </label>
        <label className="flex flex-col gap-0.5">
          <span className="text-sv-muted">Reference</span>
          <input
            className="rounded border border-sv-border bg-sv-bg px-2 py-1 font-mono text-sv-fg"
            value={ifsc}
            onChange={(e) => setIfsc(e.target.value)}
            disabled={lockedByTelemetry}
          />
        </label>
        <label className="flex flex-col gap-0.5">
          <span className="text-sv-muted">Amount / value</span>
          <input
            className="rounded border border-sv-border bg-sv-bg px-2 py-1 font-mono text-sv-fg"
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
            disabled={lockedByTelemetry}
            placeholder="If spoken"
          />
        </label>
        <label className="col-span-2 flex flex-col gap-0.5">
          <span className="text-sv-muted">Notes</span>
          <input
            className="rounded border border-sv-border bg-sv-bg px-2 py-1 text-sv-fg"
            value={purpose}
            onChange={(e) => setPurpose(e.target.value)}
            disabled={lockedByTelemetry}
          />
        </label>
      </div>

      <div className="relative mt-auto">
        <button
          type="button"
          data-testid="approve-transfer"
          data-locked={lockedByTelemetry ? 'true' : 'false'}
          disabled={!sessionId || lockedByTelemetry || busy}
          onClick={onApprove}
          className={[
            'w-full rounded px-3 py-2 text-sm font-semibold transition-colors duration-300',
            lockedByTelemetry
              ? 'cursor-not-allowed bg-sv-border/40 text-sv-muted'
              : 'bg-risk-clear text-sv-bg hover:brightness-110',
          ].join(' ')}
        >
          {busy ? 'Submitting…' : 'Approve action'}
        </button>
        {lockOverlay ? (
          <div
            className="pointer-events-none absolute inset-0 flex items-center justify-center rounded bg-sv-bg/75 px-2 text-center text-[11px] font-medium text-risk-critical"
            role="status"
          >
            {lockOverlay}
          </div>
        ) : null}
      </div>

      {showMfa ? (
        <div
          className="rounded border border-risk-elevated/50 bg-risk-elevated/10 p-2 text-[11px]"
          data-testid="oob-mfa-card"
        >
          <p className="font-medium text-sv-fg">Step-up verification</p>
          <p className="mt-0.5 text-sv-muted">
            Extra confirmation required before approve can unlock.
          </p>
          <div className="mt-1 flex items-center justify-between gap-2 font-mono text-[10px]">
            <span
              className={
                mfaState === 'pending'
                  ? 'text-risk-watch'
                  : mfaState === 'approved'
                    ? 'text-risk-clear'
                    : 'text-risk-critical'
              }
            >
              {mfaState.toUpperCase()}
            </span>
            <span className="text-sv-muted">{mfaSeconds}s</span>
          </div>
        </div>
      ) : null}

      {result ? (
        <p className="font-mono text-[10px] text-sv-muted" role="status">
          {result}
        </p>
      ) : null}
    </div>
  );
}

TransactionPanel.propTypes = {
  sessionId: PropTypes.string,
  frame: PropTypes.object,
  locked: PropTypes.bool,
  lockReason: PropTypes.string,
};

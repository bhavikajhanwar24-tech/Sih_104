import { useCallback, useEffect, useMemo, useState } from 'react';
import PropTypes from 'prop-types';
import { apiFetch } from '@/services/api.js';
import { riskRamp } from '@/theme.js';

/**
 * Liveness challenge panel — latency bar is the visual centrepiece (1.8s human / 3.5s bot).
 *
 * @param {Object} props
 * @param {string | null | undefined} props.sessionId
 */
export function ChallengePanel({ sessionId }) {
  const [language, setLanguage] = useState('en');
  const [issued, setIssued] = useState(/** @type {any} */ (null));
  const [result, setResult] = useState(/** @type {any} */ (null));
  const [status, setStatus] = useState(/** @type {string | null} */ (null));
  const [latencyMs, setLatencyMs] = useState(/** @type {number | null} */ (null));
  const [remainingMs, setRemainingMs] = useState(/** @type {number | null} */ (null));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(/** @type {string | null} */ (null));
  const [displayAcked, setDisplayAcked] = useState(false);

  const humanMs = Number(issued?.humanLatencyMs ?? 1800);
  const botMs = Number(issued?.suspiciousLatencyMs ?? 3500);
  const scaleMs = Math.max(botMs * 1.15, 4000);

  const poll = useCallback(async () => {
    if (!sessionId) return;
    try {
      const res = await apiFetch(`/api/v1/challenge/session/${encodeURIComponent(sessionId)}`);
      if (res.status === 404) return;
      if (!res.ok) return;
      const body = await res.json();
      setStatus(body.status ?? null);
      if (typeof body.latencyMs === 'number') setLatencyMs(body.latencyMs);
      if (typeof body.remainingMs === 'number') setRemainingMs(body.remainingMs);
      if (body.verdict) {
        setResult(body);
      }
    } catch {
      /* poll best-effort */
    }
  }, [sessionId]);

  useEffect(() => {
    if (!sessionId) {
      setIssued(null);
      setResult(null);
      setStatus(null);
      setDisplayAcked(false);
      return undefined;
    }
    let cancelled = false;
    const syncAutoIssued = async () => {
      try {
        const res = await apiFetch(`/api/v1/challenge/session/${encodeURIComponent(sessionId)}`);
        if (cancelled || res.status === 404 || !res.ok) return;
        const body = await res.json();
        if (!body || body.status === 'EVALUATED' || body.status === 'TIMEOUT') return;
        // Auto-issued by Decision Plane on L3 — adopt into panel without clicking Issue.
        if (!issued && (body.phrase || body.nonce)) {
          setIssued({
            phrase: body.phrase,
            nonce: body.nonce,
            humanLatencyMs: body.humanLatencyMs,
            suspiciousLatencyMs: body.suspiciousLatencyMs,
            expiryMs: body.expiryMs ?? body.remainingMs,
          });
          setStatus(body.status ?? 'ISSUED');
          if (typeof body.remainingMs === 'number') setRemainingMs(body.remainingMs);
        }
      } catch {
        /* best-effort */
      }
    };
    void syncAutoIssued();
    const id = window.setInterval(() => {
      void syncAutoIssued();
    }, 1500);
    return () => {
      cancelled = true;
      window.clearInterval(id);
    };
  }, [sessionId, issued]);

  useEffect(() => {
    if (!sessionId || !issued) return undefined;
    const id = window.setInterval(() => {
      void poll();
    }, 400);
    return () => window.clearInterval(id);
  }, [sessionId, issued, poll]);

  useEffect(() => {
    if (!issued?.nonce || displayAcked) return undefined;
    let cancelled = false;
    (async () => {
      // Confirm render — starts the server stopwatch.
      try {
        await apiFetch(`/api/v1/challenge/${encodeURIComponent(issued.nonce)}/displayed`, {
          method: 'POST',
        });
        if (!cancelled) setDisplayAcked(true);
      } catch (err) {
        if (!cancelled) setError(String(err));
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [issued, displayAcked]);

  async function issueChallenge() {
    if (!sessionId || busy) return;
    setBusy(true);
    setError(null);
    setResult(null);
    setLatencyMs(null);
    setDisplayAcked(false);
    try {
      const res = await apiFetch(
        `/api/v1/challenge/issue?sessionId=${encodeURIComponent(sessionId)}&language=${encodeURIComponent(language)}`,
        { method: 'POST' },
      );
      const body = await res.json().catch(() => ({}));
      if (!res.ok) {
        throw new Error(body.message || body.error || `HTTP ${res.status}`);
      }
      setIssued(body);
      setStatus('ISSUED');
      setRemainingMs(Number(body.expiryMs ?? 15000));
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }

  const barPct = useMemo(() => {
    const ms = latencyMs ?? (status === 'DISPLAYED' || status === 'ISSUED' ? 0 : 0);
    return Math.min(100, (ms / scaleMs) * 100);
  }, [latencyMs, scaleMs, status]);

  const humanPct = (humanMs / scaleMs) * 100;
  const botPct = (botMs / scaleMs) * 100;

  const countdown = remainingMs == null ? '—' : `${Math.ceil(remainingMs / 1000)}s`;

  return (
    <div className="flex h-full min-h-0 flex-col gap-3 p-2">
      <div className="flex flex-wrap items-center gap-2">
        <button
          type="button"
          disabled={!sessionId || busy}
          onClick={() => void issueChallenge()}
          className="rounded bg-sv-accent px-3 py-1.5 font-display text-xs font-semibold uppercase tracking-wide text-sv-bg disabled:opacity-40"
        >
          Issue Challenge
        </button>
        <select
          className="rounded border border-sv-border bg-sv-elevated px-2 py-1 font-mono text-[11px] text-sv-fg"
          value={language}
          onChange={(e) => setLanguage(e.target.value)}
          aria-label="Challenge language"
        >
          <option value="en">English</option>
          <option value="hi">Hindi</option>
          <option value="ta">Tamil</option>
        </select>
        <span className="font-mono text-[11px] text-sv-muted">
          {status ?? 'idle'} · expires {countdown}
        </span>
      </div>

      {error ? (
        <p className="font-mono text-[11px] text-sv-fault">{error}</p>
      ) : null}

      <div className="flex flex-1 flex-col items-center justify-center gap-2 rounded border border-sv-border bg-sv-elevated px-3 py-6">
        <p className="font-display text-[10px] uppercase tracking-[0.2em] text-sv-muted">
          Repeat aloud
        </p>
        <p
          className="text-center font-display text-3xl font-bold tracking-wide text-sv-fg md:text-4xl"
          data-testid="challenge-phrase"
        >
          {issued?.phrase ?? '—'}
        </p>
        {issued?.nonce ? (
          <p className="font-mono text-[10px] text-sv-muted">nonce {issued.nonce.slice(0, 12)}…</p>
        ) : null}
      </div>

      {/* Latency bar — centrepiece */}
      <div className="rounded border border-sv-border bg-sv-bg p-3">
        <div className="mb-2 flex items-baseline justify-between">
          <p className="font-display text-[11px] font-semibold uppercase tracking-wider text-sv-muted">
            Response latency
          </p>
          <p className="font-mono text-lg tabular-nums text-sv-fg">
            {latencyMs == null ? '—' : `${(latencyMs / 1000).toFixed(2)}s`}
          </p>
        </div>
        <div className="relative h-10 w-full overflow-hidden rounded bg-sv-panel">
          <div
            className="absolute inset-y-0 left-0 transition-[width] duration-200"
            style={{
              width: `${barPct}%`,
              background:
                latencyMs != null && latencyMs > botMs
                  ? riskRamp.critical
                  : latencyMs != null && latencyMs > humanMs
                    ? riskRamp.watch
                    : riskRamp.clear,
            }}
          />
          {/* Human threshold 1.8s */}
          <div
            className="absolute inset-y-0 w-0.5 bg-emerald-400"
            style={{ left: `${humanPct}%` }}
            title={`Human ≤ ${humanMs}ms`}
          />
          {/* Bot threshold 3.5s */}
          <div
            className="absolute inset-y-0 w-0.5 bg-red-500"
            style={{ left: `${botPct}%` }}
            title={`Suspicious > ${botMs}ms`}
          />
          <div
            className="pointer-events-none absolute -top-0.5 -translate-x-1/2 font-mono text-[9px] text-emerald-400"
            style={{ left: `${humanPct}%` }}
          >
            1.8s
          </div>
          <div
            className="pointer-events-none absolute -top-0.5 -translate-x-1/2 font-mono text-[9px] text-red-400"
            style={{ left: `${botPct}%` }}
          >
            3.5s
          </div>
        </div>
        <p className="mt-2 font-mono text-[10px] text-sv-muted">
          Green zone ≤ 1.8s (human) · amber watch · red &gt; 3.5s (real-time TTS tell)
        </p>
      </div>

      <div className="grid grid-cols-3 gap-2 font-mono text-[11px]">
        <Signal
          label="Latency"
          pass={result ? Boolean(result.latencyPass) : null}
          detail={latencyMs == null ? '—' : `${latencyMs} ms`}
        />
        <Signal
          label="Content"
          pass={result ? Boolean(result.contentPass) : null}
          detail={
            result?.contentOverlap == null
              ? '—'
              : `overlap ${(Number(result.contentOverlap) * 100).toFixed(0)}%`
          }
        />
        <Signal
          label="Acoustic"
          pass={result ? Boolean(result.acousticPass) : null}
          detail={
            result?.acousticCosine == null
              ? '—'
              : `cos ${Number(result.acousticCosine).toFixed(2)}`
          }
        />
      </div>

      {result?.verdict ? (
        <p
          className={`rounded border px-3 py-2 text-center font-display text-sm font-bold uppercase tracking-wider ${
            result.verdict === 'PASS'
              ? 'border-emerald-500/40 text-emerald-400'
              : 'border-red-500/40 text-red-400'
          }`}
        >
          {result.verdict}
        </p>
      ) : null}
    </div>
  );
}

function Signal({ label, pass, detail }) {
  const tone =
    pass == null ? 'text-sv-muted' : pass ? 'text-emerald-400' : 'text-red-400';
  return (
    <div className="rounded border border-sv-border bg-sv-panel px-2 py-1.5">
      <p className="text-[10px] uppercase tracking-wide text-sv-muted">{label}</p>
      <p className={`font-semibold ${tone}`}>{pass == null ? '…' : pass ? 'PASS' : 'FAIL'}</p>
      <p className="truncate text-sv-muted">{detail}</p>
    </div>
  );
}

Signal.propTypes = {
  label: PropTypes.string.isRequired,
  pass: PropTypes.bool,
  detail: PropTypes.string,
};

ChallengePanel.propTypes = {
  sessionId: PropTypes.string,
};

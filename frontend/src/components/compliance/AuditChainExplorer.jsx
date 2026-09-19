import { Fragment, useCallback, useEffect, useState } from 'react';
import PropTypes from 'prop-types';
import { apiFetch } from '@/services/api.js';

/**
 * @param {string} hash
 * @param {number} [keep]
 */
function truncHash(hash, keep = 10) {
  if (!hash || typeof hash !== 'string') return '—';
  if (hash.length <= keep * 2 + 1) return hash;
  return `${hash.slice(0, keep)}…${hash.slice(-keep)}`;
}

/**
 * Live SHA-256 audit chain browser — the tamper demo screen (Context §13 / RBI).
 *
 * @param {Object} props
 * @param {string | null} [props.initialSessionId]
 */
export function AuditChainExplorer({ initialSessionId = null }) {
  const [sessionIds, setSessionIds] = useState(/** @type {string[]} */ ([]));
  const [sessionId, setSessionId] = useState(initialSessionId ?? '');
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [blocks, setBlocks] = useState(/** @type {any[]} */ ([]));
  const [expanded, setExpanded] = useState(/** @type {number | null} */ (null));
  const [verify, setVerify] = useState(
    /** @type {{ valid: boolean, blockCount: number, brokenAtIndex: number | null } | null} */ (
      null
    ),
  );
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(/** @type {string | null} */ (null));

  useEffect(() => {
    if (initialSessionId) setSessionId(initialSessionId);
  }, [initialSessionId]);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const res = await apiFetch('/api/v1/compliance/sessions');
        if (!res.ok) throw new Error(`sessions HTTP ${res.status}`);
        const data = await res.json();
        if (cancelled) return;
        const ids = Array.isArray(data.sessionIds) ? data.sessionIds : [];
        setSessionIds(ids);
        if (!sessionId && ids.length) setSessionId(ids[0]);
      } catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : 'failed to load sessions');
      }
    })();
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- seed once; sessionId updates from select
  }, []);

  const loadChain = useCallback(async (sid, p) => {
    if (!sid) return;
    setBusy(true);
    setError(null);
    try {
      const res = await apiFetch(
        `/api/v1/compliance/audit-chain/${encodeURIComponent(sid)}?page=${p}&size=25`,
      );
      if (!res.ok) throw new Error(`chain HTTP ${res.status}`);
      const data = await res.json();
      setBlocks(Array.isArray(data.blocks) ? data.blocks : []);
      setTotalPages(Number(data.totalPages) || 0);
      setPage(Number(data.page) || 0);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'chain load failed');
      setBlocks([]);
    } finally {
      setBusy(false);
    }
  }, []);

  useEffect(() => {
    if (sessionId) loadChain(sessionId, 0);
  }, [sessionId, loadChain]);

  const runVerify = async () => {
    if (!sessionId) return;
    setBusy(true);
    setError(null);
    try {
      const res = await apiFetch(`/api/v1/compliance/verify/${encodeURIComponent(sessionId)}`);
      if (!res.ok) throw new Error(`verify HTTP ${res.status}`);
      const data = await res.json();
      setVerify({
        valid: Boolean(data.valid),
        blockCount: Number(data.blockCount) || 0,
        brokenAtIndex: data.brokenAtIndex == null ? null : Number(data.brokenAtIndex),
      });
    } catch (e) {
      setError(e instanceof Error ? e.message : 'verify failed');
      setVerify(null);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-end gap-3">
        <label className="flex min-w-[14rem] flex-1 flex-col gap-1 text-sm">
          <span className="text-sv-muted">Session</span>
          <select
            className="rounded border border-sv-border bg-sv-bg px-2 py-1.5 font-mono text-xs text-sv-fg"
            value={sessionId}
            onChange={(e) => {
              setVerify(null);
              setSessionId(e.target.value);
            }}
          >
            <option value="">Select session…</option>
            {sessionIds.map((id) => (
              <option key={id} value={id}>
                {id}
              </option>
            ))}
          </select>
        </label>
        <button
          type="button"
          disabled={!sessionId || busy}
          onClick={runVerify}
          className="rounded border-2 border-sv-accent bg-sv-elevated px-4 py-2 text-sm font-semibold uppercase tracking-wide text-sv-fg hover:bg-sv-accent/20 disabled:opacity-40"
        >
          Verify Chain
        </button>
      </div>

      {verify ? (
        <div
          className={`rounded-lg border-4 px-6 py-8 text-center ${
            verify.valid
              ? 'border-emerald-500 bg-emerald-950/50'
              : 'border-red-500 bg-red-950/60'
          }`}
          role="status"
          aria-live="polite"
        >
          <p
            className={`font-display text-5xl font-bold tracking-wider md:text-6xl ${
              verify.valid ? 'text-emerald-400' : 'text-red-400'
            }`}
          >
            {verify.valid ? 'VALID' : 'INVALID'}
          </p>
          <p className="mt-3 font-mono text-sm text-sv-fg">
            {verify.valid
              ? `${verify.blockCount} blocks · hash chain intact`
              : `Broken at index ${verify.brokenAtIndex ?? '—'} · ${verify.blockCount} blocks scanned`}
          </p>
          {!verify.valid ? (
            <p className="mt-2 text-sm text-red-200/90">
              Tamper detected — expected hash ≠ stored hash at the reported index (H2 live demo).
            </p>
          ) : null}
        </div>
      ) : (
        <p className="text-sm text-sv-muted">
          Press <strong className="text-sv-fg">Verify Chain</strong> — the VALID / INVALID badge
          is sized for the room-back tamper demo.
        </p>
      )}

      {error ? <p className="text-sm text-red-400">{error}</p> : null}

      <div className="overflow-auto rounded border border-sv-border">
        <table className="w-full min-w-[640px] border-collapse text-left text-xs">
          <thead className="bg-sv-elevated text-sv-muted">
            <tr>
              <th className="px-2 py-1.5 font-mono">#</th>
              <th className="px-2 py-1.5">Timestamp</th>
              <th className="px-2 py-1.5">Event</th>
              <th className="px-2 py-1.5 font-mono">prev</th>
              <th className="px-2 py-1.5 font-mono">curr</th>
              <th className="px-2 py-1.5">Payload</th>
            </tr>
          </thead>
          <tbody>
            {blocks.length === 0 ? (
              <tr>
                <td colSpan={6} className="px-2 py-4 text-sv-muted">
                  {busy ? 'Loading…' : 'No blocks for this session.'}
                </td>
              </tr>
            ) : (
              blocks.map((b) => {
                const idx = b.blockIndex;
                const open = expanded === idx;
                return (
                  <Fragment key={idx}>
                    <tr className="border-t border-sv-border/80 hover:bg-sv-elevated/40">
                      <td className="px-2 py-1.5 font-mono text-sv-accent">{idx}</td>
                      <td className="px-2 py-1.5 text-sv-muted">
                        {b.tsEpochMs ? new Date(b.tsEpochMs).toISOString() : '—'}
                      </td>
                      <td className="px-2 py-1.5 text-sv-fg">{b.eventType}</td>
                      <td className="px-2 py-1.5 font-mono text-sv-muted">
                        {truncHash(b.previousHash)}
                      </td>
                      <td className="px-2 py-1.5 font-mono text-sv-muted">
                        {truncHash(b.currentHash)}
                      </td>
                      <td className="px-2 py-1.5">
                        <button
                          type="button"
                          className="text-sv-accent underline"
                          onClick={() => setExpanded(open ? null : idx)}
                        >
                          {open ? 'Hide' : 'Expand'}
                        </button>
                      </td>
                    </tr>
                    {open ? (
                      <tr className="border-t border-sv-border/40 bg-sv-bg">
                        <td colSpan={6} className="px-3 py-2">
                          <pre className="max-h-48 overflow-auto font-mono text-[10px] text-sv-muted">
                            {JSON.stringify(b.payload ?? {}, null, 2)}
                          </pre>
                        </td>
                      </tr>
                    ) : null}
                  </Fragment>
                );
              })
            )}
          </tbody>
        </table>
      </div>

      <div className="flex items-center gap-2 text-xs text-sv-muted">
        <button
          type="button"
          disabled={page <= 0 || busy || !sessionId}
          className="rounded border border-sv-border px-2 py-1 disabled:opacity-40"
          onClick={() => loadChain(sessionId, page - 1)}
        >
          Prev
        </button>
        <span className="font-mono">
          page {page + 1}/{Math.max(totalPages, 1)}
        </span>
        <button
          type="button"
          disabled={page + 1 >= totalPages || busy || !sessionId}
          className="rounded border border-sv-border px-2 py-1 disabled:opacity-40"
          onClick={() => loadChain(sessionId, page + 1)}
        >
          Next
        </button>
      </div>
    </div>
  );
}

AuditChainExplorer.propTypes = {
  initialSessionId: PropTypes.string,
};

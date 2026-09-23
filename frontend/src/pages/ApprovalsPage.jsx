/**
 * F14 — Approvals inbox (POLICY_APPROVER + TENANT_ADMIN).
 */
import { useCallback, useEffect, useState } from 'react';
import { useAuth } from '@/context/AuthContext.jsx';
import { apiJson } from '@/services/api.js';
import { Badge, Button, EmptyState } from '@/ui';
import { useToast } from '@/ui/Toast.jsx';

export function ApprovalsPage() {
  const { hasPermission } = useAuth();
  const { push } = useToast();
  const [items, setItems] = useState([]);
  const [selected, setSelected] = useState(null);
  const [detail, setDetail] = useState(null);
  const [comment, setComment] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  const canRead = hasPermission('approvals:read') || hasPermission('policies:approve');
  const canDecide = hasPermission('approvals:decide') || hasPermission('policies:approve');

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await apiJson('/api/v2/governance/approvals', {
        skipErrorToast: true,
        timeoutMs: 30_000,
      });
      setItems(data.items || []);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load approvals');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (canRead) void load();
  }, [canRead, load]);

  useEffect(() => {
    if (!selected) {
      setDetail(null);
      return;
    }
    let cancelled = false;
    (async () => {
      try {
        const d = await apiJson(
          `/api/v2/governance/approvals/${encodeURIComponent(selected.area)}/${encodeURIComponent(selected.id)}`,
          { skipErrorToast: true },
        );
        if (!cancelled) setDetail(d);
      } catch (err) {
        if (!cancelled) push(err instanceof Error ? err.message : 'Detail failed');
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [selected, push]);

  if (!canRead) {
    return (
      <div className="p-6">
        <EmptyState title="Permission denied" description="Approvals inbox requires approvals:read." />
      </div>
    );
  }

  async function decide(approve) {
    if (!selected || !canDecide) return;
    setBusy(true);
    try {
      await apiJson(
        `/api/v2/governance/approvals/${encodeURIComponent(selected.area)}/${encodeURIComponent(selected.id)}/decide`,
        {
          method: 'POST',
          body: JSON.stringify({ approve, comment }),
        },
      );
      push(approve ? 'Approved' : 'Rejected', 'ok');
      setSelected(null);
      setComment('');
      void load();
    } catch (err) {
      push(err instanceof Error ? err.message : 'Decision failed');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex h-full min-h-0 flex-col p-4 md:p-6">
      <header className="mb-4">
        <h1 className="font-display text-2xl text-sv-fg">Approvals inbox</h1>
        <p className="text-sm text-sv-muted">Policy sets, fusion configs, and response plans awaiting dual-control.</p>
      </header>

      {loading ? (
        <p className="text-sm text-sv-muted">Loading…</p>
      ) : error ? (
        <div className="text-sm text-red-200">
          {error}{' '}
          <Button variant="ghost" onClick={() => void load()}>
            Retry
          </Button>
        </div>
      ) : (
        <div className="grid min-h-0 flex-1 gap-4 lg:grid-cols-2">
          <div className="min-h-0 overflow-y-auto rounded border border-sv-border">
            {items.length === 0 ? (
              <div className="p-4">
                <EmptyState title="Inbox empty" description="No pending approvals." />
              </div>
            ) : (
              <ul className="divide-y divide-sv-border" role="list">
                {items.map((it) => (
                  <li key={`${it.area}-${it.id}`}>
                    <button
                      type="button"
                      className={`flex w-full flex-col gap-1 px-3 py-2.5 text-left hover:bg-sv-elevated/50 ${
                        selected?.id === it.id ? 'bg-sv-accent/10' : ''
                      }`}
                      onClick={() => setSelected(it)}
                    >
                      <div className="flex items-center gap-2">
                        <Badge tone="warn">{it.area}</Badge>
                        <span className="text-sm text-sv-fg">{it.title}</span>
                      </div>
                      <p className="font-mono text-[10px] text-sv-muted">
                        age {it.ageMinutes}m · sha {(it.contentSha256 || '').slice(0, 12)}…
                      </p>
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>
          <div className="min-h-0 overflow-y-auto rounded border border-sv-border p-3">
            {!selected ? (
              <p className="text-sm text-sv-muted">Select an item to review the diff.</p>
            ) : (
              <div className="space-y-3">
                <h2 className="text-base font-semibold">{detail?.title || selected.title}</h2>
                <pre className="max-h-64 overflow-auto rounded bg-sv-bg p-2 font-mono text-[11px] text-sv-fg/90">
                  {JSON.stringify(detail?.diff || { note: 'Loading diff…' }, null, 2)}
                </pre>
                <label className="block text-xs text-sv-muted">
                  Comment
                  <textarea
                    className="mt-1 w-full rounded border border-sv-border bg-sv-bg px-2 py-1 text-sm text-sv-fg"
                    rows={3}
                    value={comment}
                    onChange={(e) => setComment(e.target.value)}
                  />
                </label>
                {canDecide ? (
                  <div className="flex gap-2">
                    <Button disabled={busy} onClick={() => void decide(true)}>
                      Approve
                    </Button>
                    <Button variant="secondary" disabled={busy} onClick={() => void decide(false)}>
                      Reject
                    </Button>
                  </div>
                ) : (
                  <p className="text-xs text-sv-muted">View only — decide permission missing.</p>
                )}
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

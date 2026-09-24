/**
 * F14 — Configuration change history timeline + CSV export.
 */
import { useCallback, useEffect, useState } from 'react';
import { useAuth } from '@/context/AuthContext.jsx';
import { apiFetch, apiJson } from '@/services/api.js';
import { Badge, Button, EmptyState, Input } from '@/ui';
import { useToast } from '@/ui/Toast.jsx';

export function ChangeHistoryPage() {
  const { hasPermission } = useAuth();
  const { push } = useToast();
  const [items, setItems] = useState([]);
  const [area, setArea] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [expanded, setExpanded] = useState(null);

  const canRead = hasPermission('governance:read') || hasPermission('audit:read');

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const params = new URLSearchParams({ limit: '100' });
      if (area.trim()) params.set('area', area.trim());
      const data = await apiJson(`/api/v2/governance/changes?${params}`, {
        skipErrorToast: true,
      });
      setItems(data.items || []);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load changes');
    } finally {
      setLoading(false);
    }
  }, [area]);

  useEffect(() => {
    if (canRead) void load();
  }, [canRead, load]);

  if (!canRead) {
    return (
      <div className="p-6">
        <EmptyState title="Permission denied" description="governance:read or audit:read required." />
      </div>
    );
  }

  async function exportCsv() {
    try {
      const params = new URLSearchParams();
      if (area.trim()) params.set('area', area.trim());
      const res = await apiFetch(`/api/v2/governance/changes/export.csv?${params}`);
      if (!res.ok) throw new Error(`Export failed (${res.status})`);
      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = 'change-history.csv';
      a.click();
      URL.revokeObjectURL(url);
      push('CSV downloaded', 'ok');
    } catch (err) {
      push(err instanceof Error ? err.message : 'Export failed');
    }
  }

  return (
    <div className="p-4 md:p-6">
      <header className="mb-4 flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="font-display text-2xl text-sv-fg">Change history</h1>
          <p className="text-sm text-sv-muted">Configuration changes from the audit chain.</p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Input
            aria-label="Filter by area"
            placeholder="Filter area (policy, fusion…)"
            value={area}
            onChange={(e) => setArea(e.target.value)}
            className="!w-48"
          />
          <Button variant="secondary" onClick={() => void load()} disabled={loading}>
            Apply
          </Button>
          <Button onClick={() => void exportCsv()}>Export CSV</Button>
        </div>
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
      ) : items.length === 0 ? (
        <EmptyState title="No changes" description="Config change events will appear here." />
      ) : (
        <ol className="space-y-2" role="list">
          {items.map((row) => (
            <li key={row.seq} className="rounded border border-sv-border px-3 py-2">
              <button
                type="button"
                className="flex w-full flex-wrap items-center gap-2 text-left text-xs"
                onClick={() => setExpanded(expanded === row.seq ? null : row.seq)}
              >
                <span className="font-mono text-sv-muted">#{row.seq}</span>
                <Badge tone="neutral">{row.area}</Badge>
                <span className="font-medium text-sv-fg">{row.eventType}</span>
                <span className="ml-auto font-mono text-[10px] text-sv-muted">{row.createdAt}</span>
              </button>
              <p className="mt-1 text-xs text-sv-muted">
                {row.actorType} {row.actorId} · hash {(row.blockHash || '').slice(0, 12)}…
              </p>
              {expanded === row.seq ? (
                <pre className="mt-2 max-h-48 overflow-auto rounded bg-sv-bg p-2 font-mono text-[10px]">
                  {JSON.stringify({ before: row.before, after: row.after, change: row.change }, null, 2)}
                </pre>
              ) : null}
            </li>
          ))}
        </ol>
      )}
    </div>
  );
}

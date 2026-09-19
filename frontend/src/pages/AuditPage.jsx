import { useCallback, useEffect, useState } from 'react';
import { Navigate } from 'react-router-dom';
import { useAuth } from '@/context/AuthContext.jsx';
import { apiJson } from '@/services/api.js';
import { Badge, Button, EmptyState, Input, Table } from '@/ui';
import { useToast } from '@/ui/Toast.jsx';

export function AuditPage() {
  const { hasPermission } = useAuth();
  const { push } = useToast();
  const [items, setItems] = useState([]);
  const [cursor, setCursor] = useState(/** @type {number | null} */ (null));
  const [nextCursor, setNextCursor] = useState(/** @type {number | null} */ (null));
  const [eventType, setEventType] = useState('');
  const [verify, setVerify] = useState(/** @type {null | { valid: boolean, blocksChecked: number, firstBrokenSeq: number | null }} */ (null));
  const [loading, setLoading] = useState(false);

  const canRead = hasPermission('audit:read');

  const load = useCallback(
    async (reset) => {
      setLoading(true);
      try {
        const params = new URLSearchParams();
        params.set('limit', '40');
        if (!reset && cursor != null) params.set('cursor', String(cursor));
        if (eventType) params.set('eventType', eventType);
        const data = await apiJson(`/api/v2/audit?${params}`);
        setItems((prev) => (reset ? data.items || [] : [...prev, ...(data.items || [])]));
        setNextCursor(data.nextCursor ?? null);
        if (data.items?.length) {
          setCursor(data.items[data.items.length - 1].seq);
        }
      } catch (err) {
        push(err.message || 'Failed to load audit');
      } finally {
        setLoading(false);
      }
    },
    [cursor, eventType, push],
  );

  useEffect(() => {
    if (!canRead) return;
    setCursor(null);
    setItems([]);
    load(true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [canRead, eventType]);

  if (!canRead) {
    return <Navigate to="/app" replace />;
  }

  const columns = [
    { key: 'seq', header: 'Seq' },
    { key: 'eventType', header: 'Event' },
    { key: 'actorType', header: 'Actor' },
    {
      key: 'createdAt',
      header: 'When',
      render: (row) => (
        <span className="font-mono text-xs text-sv-muted">{row.createdAt}</span>
      ),
    },
    {
      key: 'payload',
      header: 'Detail',
      render: (row) => {
        const p = row.payload || {};
        const bits = [];
        if (p.policySetId) bits.push(`set ${String(p.policySetId).slice(0, 8)}…`);
        if (p.comment) bits.push(p.comment);
        if (p.contentSha256) bits.push(`sha ${String(p.contentSha256).slice(0, 8)}…`);
        if (!bits.length && Object.keys(p).length) {
          try {
            return (
              <span className="line-clamp-2 font-mono text-[11px] text-sv-muted">
                {JSON.stringify(p)}
              </span>
            );
          } catch {
            return '—';
          }
        }
        return bits.length ? (
          <span className="line-clamp-2 text-xs text-sv-fg">{bits.join(' · ')}</span>
        ) : (
          <span className="text-xs text-sv-muted">—</span>
        );
      },
    },
  ];

  const runVerify = async () => {
    try {
      const data = await apiJson('/api/v2/audit/verify');
      setVerify(data);
    } catch (err) {
      push(err.message || 'Verify failed');
    }
  };

  return (
    <div className="mx-auto max-w-6xl space-y-6 p-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="font-display text-2xl font-semibold text-sv-fg">Audit</h1>
          <p className="mt-1 text-sm text-sv-muted">
            Per-tenant hash chain — read-only. Seq starts at 1 for each organisation.
          </p>
        </div>
        <Button onClick={runVerify}>Verify chain</Button>
      </div>

      {verify ? (
        <div
          className={`rounded-md border px-4 py-3 text-sm ${
            verify.valid
              ? 'border-risk-clear/40 bg-risk-clear/10 text-risk-clear'
              : 'border-risk-critical/40 bg-risk-critical/10 text-risk-critical'
          }`}
          role="status"
        >
          {verify.valid
            ? `Chain intact — ${verify.blocksChecked} blocks`
            : `Broken at #${verify.firstBrokenSeq} (${verify.blocksChecked} checked)`}
        </div>
      ) : null}

      <div className="flex flex-wrap gap-3">
        <Input
          label="Event type filter"
          value={eventType}
          onChange={(e) => setEventType(e.target.value)}
          placeholder="e.g. POLICY_SET_APPROVED"
          className="max-w-xs"
        />
        <div className="flex items-end">
          <Button
            variant="secondary"
            onClick={() => {
              setCursor(null);
              setItems([]);
              load(true);
            }}
          >
            Apply
          </Button>
        </div>
      </div>

      {items.length === 0 && !loading ? (
        <EmptyState title="No audit events" description="Events appear as the tenant operates." />
      ) : (
        <Table columns={columns} rows={items} rowKey={(r) => r.id} />
      )}

      {nextCursor != null ? (
        <Button variant="secondary" disabled={loading} onClick={() => load(false)}>
          {loading ? 'Loading…' : 'Load more'}
        </Button>
      ) : null}
    </div>
  );
}

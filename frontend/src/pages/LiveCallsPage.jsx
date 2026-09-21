import { useCallback, useEffect, useState } from 'react';
import { Link, Navigate } from 'react-router-dom';
import { useAuth } from '@/context/AuthContext.jsx';
import { apiJson } from '@/services/api.js';
import { Badge, EmptyState, Table } from '@/ui';
import { useToast } from '@/ui/Toast.jsx';

/**
 * F10 — Live Calls: tenant call_sessions with directory-resolved names.
 * Polls while the page is open so softphone lab calls appear without refresh.
 */
export function LiveCallsPage() {
  const { hasPermission } = useAuth();
  const { push } = useToast();
  const [items, setItems] = useState([]);
  const [activeCount, setActiveCount] = useState(0);
  const [loading, setLoading] = useState(true);

  const canRead = hasPermission('calls:read');

  const load = useCallback(async () => {
    try {
      const data = await apiJson('/api/v2/calls?limit=50', { skipErrorToast: true });
      setItems(Array.isArray(data.items) ? data.items : []);
      setActiveCount(data.activeCount ?? 0);
    } catch (err) {
      push(err instanceof Error ? err.message : 'Failed to load live calls');
    } finally {
      setLoading(false);
    }
  }, [push]);

  useEffect(() => {
    if (!canRead) return undefined;
    load();
    const id = window.setInterval(load, 2500);
    return () => window.clearInterval(id);
  }, [canRead, load]);

  if (!canRead) {
    return <Navigate to="/app" replace />;
  }

  const columns = [
    {
      key: 'status',
      header: 'Status',
      render: (row) =>
        row.active ? (
          <Badge tone="success">Live</Badge>
        ) : (
          <Badge tone="neutral">Ended</Badge>
        ),
    },
    {
      key: 'callerName',
      header: 'Caller',
      render: (row) => (
        <div>
          <div className="text-sm text-sv-fg">{row.callerName || '—'}</div>
          <div className="font-mono text-[11px] text-sv-muted">{row.callerNumber || ''}</div>
        </div>
      ),
    },
    {
      key: 'calleeName',
      header: 'Callee',
      render: (row) => (
        <div>
          <div className="text-sm text-sv-fg">{row.calleeName || '—'}</div>
          <div className="font-mono text-[11px] text-sv-muted">{row.calleeNumber || ''}</div>
        </div>
      ),
    },
    {
      key: 'direction',
      header: 'Dir',
      render: (row) => <span className="text-xs text-sv-muted">{row.direction || '—'}</span>,
    },
    {
      key: 'level',
      header: 'Level',
      render: (row) => {
        const level = row.liveLevel || row.peakLevel;
        if (!level) return <span className="text-xs text-sv-muted">—</span>;
        return <Badge tone={row.active ? 'warning' : 'neutral'}>{level}</Badge>;
      },
    },
    {
      key: 'startedAt',
      header: 'Started',
      render: (row) => (
        <span className="font-mono text-[11px] text-sv-muted">
          {row.startedAt ? new Date(row.startedAt).toLocaleString() : '—'}
        </span>
      ),
    },
  ];

  return (
    <div className="space-y-6 p-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <p className="text-xs font-semibold uppercase tracking-wide text-sv-muted">Telephony</p>
          <h1 className="mt-1 text-xl font-semibold text-sv-fg">Live Calls</h1>
          <p className="mt-1 text-sm text-sv-muted">
            Tenant-scoped sessions from Asterisk. Names resolve from Directory when SIP accounts are
            linked to employees.
          </p>
        </div>
        <div className="flex items-center gap-3 text-sm text-sv-muted">
          <span>
            Active{' '}
            <span className="font-semibold text-sv-fg">{activeCount}</span>
          </span>
          <Link to="/app/directory" className="text-sv-accent underline-offset-2 hover:underline">
            Directory → provision SIP
          </Link>
        </div>
      </div>

      {loading && !items.length ? (
        <p className="text-sm text-sv-muted">Loading calls…</p>
      ) : !items.length ? (
        <EmptyState
          title="No calls yet"
          description="Create SIP accounts under Directory → employee → Telephony, register Zoiper, then dial. New calls appear here automatically."
        />
      ) : (
        <Table columns={columns} rows={items} rowKey={(r) => r.id} />
      )}
    </div>
  );
}

import { useCallback, useEffect, useState } from 'react';
import { Link, Navigate } from 'react-router-dom';
import { useAuth } from '@/context/AuthContext.jsx';
import { apiJson } from '@/services/api.js';
import { Badge, EmptyState, Input, Select, Table } from '@/ui';
import { useToast } from '@/ui/Toast.jsx';

/**
 * F12 — Call History: filtered GET /api/v2/sessions.
 */
export function CallHistoryPage() {
  const { hasPermission } = useAuth();
  const { push } = useToast();
  const [items, setItems] = useState([]);
  const [loading, setLoading] = useState(true);
  const [minLevel, setMinLevel] = useState('');
  const [outcome, setOutcome] = useState('');
  const [reviewed, setReviewed] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [employeeId, setEmployeeId] = useState('');
  const [employees, setEmployees] = useState([]);

  const canRead = hasPermission('calls:read');

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const params = new URLSearchParams();
      params.set('limit', '80');
      if (minLevel) params.set('minLevel', minLevel);
      if (outcome) params.set('outcome', outcome);
      if (reviewed === 'true' || reviewed === 'false') params.set('reviewed', reviewed);
      if (from) params.set('from', new Date(from).toISOString());
      if (to) params.set('to', new Date(to).toISOString());
      if (employeeId) params.set('employeeId', employeeId);
      const data = await apiJson(`/api/v2/sessions?${params}`, { skipErrorToast: true });
      setItems(Array.isArray(data.items) ? data.items : []);
    } catch (err) {
      push(err instanceof Error ? err.message : 'Failed to load sessions');
    } finally {
      setLoading(false);
    }
  }, [from, to, minLevel, outcome, reviewed, employeeId, push]);

  useEffect(() => {
    if (!canRead) return;
    void load();
  }, [canRead, load]);

  useEffect(() => {
    if (!canRead) return;
    void (async () => {
      try {
        const data = await apiJson('/api/v2/directory/employees?page=0&size=200', {
          skipErrorToast: true,
        });
        const rows = Array.isArray(data?.content)
          ? data.content
          : Array.isArray(data?.items)
            ? data.items
            : Array.isArray(data)
              ? data
              : [];
        setEmployees(rows);
      } catch {
        setEmployees([]);
      }
    })();
  }, [canRead]);

  if (!canRead) {
    return <Navigate to="/app" replace />;
  }

  const columns = [
    {
      key: 'when',
      header: 'Started',
      render: (row) => (
        <Link
          to={`/app/sessions/${encodeURIComponent(row.id)}`}
          className="font-mono text-[11px] text-sv-accent underline-offset-2 hover:underline"
        >
          {row.startedAt ? new Date(row.startedAt).toLocaleString() : '—'}
        </Link>
      ),
    },
    {
      key: 'parties',
      header: 'Parties',
      render: (row) => (
        <div className="space-y-0.5">
          <div className="text-sm text-sv-fg">{row.callerName || '—'}</div>
          <div className="text-xs text-sv-muted">→ {row.calleeName || '—'}</div>
        </div>
      ),
    },
    {
      key: 'peakLevel',
      header: 'Peak',
      render: (row) =>
        row.peakLevel ? (
          <Badge tone={row.peakLevel.includes('LEVEL_1') ? 'neutral' : 'warn'}>
            {row.peakLevel}
            {typeof row.peakScore === 'number' ? ` · ${row.peakScore.toFixed(2)}` : ''}
          </Badge>
        ) : (
          <span className="text-xs text-sv-muted">—</span>
        ),
    },
    {
      key: 'durationMs',
      header: 'Duration',
      render: (row) => (
        <span className="font-mono text-xs text-sv-muted">{formatDuration(row.durationMs)}</span>
      ),
    },
    {
      key: 'finalOutcome',
      header: 'Outcome',
      render: (row) => <span className="text-xs text-sv-muted">{row.finalOutcome || '—'}</span>,
    },
    {
      key: 'reviewStatus',
      header: 'Review',
      render: (row) => (
        <Badge tone={row.reviewStatus === 'CONFIRMED_FRAUD' ? 'danger' : 'neutral'}>
          {row.reviewStatus || 'UNREVIEWED'}
        </Badge>
      ),
    },
  ];

  return (
    <div className="space-y-6 p-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <p className="text-xs font-semibold uppercase tracking-wide text-sv-muted">Explainability</p>
          <h1 className="mt-1 text-xl font-semibold text-sv-fg">Call History</h1>
          <p className="mt-1 text-sm text-sv-muted">
            Retained scores, reasons, and actions — no audio or transcripts.
          </p>
        </div>
        <Link to="/app/calls" className="text-sm text-sv-accent underline-offset-2 hover:underline">
          Live Calls →
        </Link>
      </div>

      <div className="flex flex-wrap items-end gap-3 rounded-lg border border-sv-border bg-sv-panel p-3">
        <Input
          label="From"
          type="datetime-local"
          className="w-44"
          value={from}
          onChange={(e) => setFrom(e.target.value)}
        />
        <Input
          label="To"
          type="datetime-local"
          className="w-44"
          value={to}
          onChange={(e) => setTo(e.target.value)}
        />
        <Select
          label="Min level"
          className="w-40"
          value={minLevel}
          onChange={(e) => setMinLevel(e.target.value)}
        >
          <option value="">Any</option>
          <option value="LEVEL_1_SILENT">L1+</option>
          <option value="LEVEL_2_SOFT_NUDGE">L2+</option>
          <option value="LEVEL_3_STEP_UP_MFA">L3+</option>
          <option value="LEVEL_4_AUTO_HOLD">L4+</option>
          <option value="LEVEL_5_TERMINATE">L5</option>
        </Select>
        <Input
          label="Outcome"
          className="w-36"
          placeholder="ENDED…"
          value={outcome}
          onChange={(e) => setOutcome(e.target.value)}
        />
        <Select
          label="Employee"
          className="w-48"
          value={employeeId}
          onChange={(e) => setEmployeeId(e.target.value)}
        >
          <option value="">Any</option>
          {employees.map((e) => (
            <option key={e.id} value={e.id}>
              {e.fullName || e.full_name || e.name || e.id}
            </option>
          ))}
        </Select>
        <Select
          label="Reviewed"
          className="w-36"
          value={reviewed}
          onChange={(e) => setReviewed(e.target.value)}
        >
          <option value="">Any</option>
          <option value="false">Unreviewed</option>
          <option value="true">Reviewed</option>
        </Select>
      </div>

      {loading && !items.length ? (
        <p className="text-sm text-sv-muted">Loading sessions…</p>
      ) : !items.length ? (
        <EmptyState
          title="No sessions match"
          description="Place a lab call from Live Calls, then return here after hangup."
        />
      ) : (
        <Table columns={columns} rows={items} rowKey={(r) => r.id} />
      )}
    </div>
  );
}

/** @param {number | null | undefined} ms */
function formatDuration(ms) {
  if (ms == null || Number.isNaN(ms)) return '—';
  const s = Math.round(ms / 1000);
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  const rem = s % 60;
  return `${m}m ${rem}s`;
}

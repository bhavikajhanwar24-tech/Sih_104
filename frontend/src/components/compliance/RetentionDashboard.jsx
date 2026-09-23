import { useEffect, useState } from 'react';
import { apiFetch } from '@/services/api.js';

/**
 * Retention — F15 GET /api/v2/compliance/retention
 */
export function RetentionDashboard() {
  const [data, setData] = useState(/** @type {Record<string, any> | null} */ (null));
  const [error, setError] = useState(/** @type {string | null} */ (null));

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const res = await apiFetch('/api/v2/compliance/retention');
        if (!res.ok) throw new Error(`retention HTTP ${res.status}`);
        const json = await res.json();
        if (!cancelled) {
          setData(json);
          setError(null);
        }
      } catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : 'load failed');
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  if (error) return <p className="text-sm text-red-400">{error}</p>;
  if (!data) return <p className="text-sm text-sv-muted">Loading retention counters…</p>;

  const stats = data.lastPurgeStats || {};

  return (
    <div className="space-y-3 text-sm">
      <p className="text-[11px] text-sv-muted">
        Full controls: <code className="text-sv-accent">/app/compliance?tab=retention</code>
      </p>
      <dl className="grid grid-cols-2 gap-2">
        <dt className="text-sv-muted">Retention days</dt>
        <dd className="font-mono">{data.retentionDays}</dd>
        <dt className="text-sv-muted">Last purge</dt>
        <dd>{data.lastPurgeAt || 'Never'}</dd>
        <dt className="text-sv-muted">Ticks purged (last)</dt>
        <dd>{stats.sessionTicks ?? '—'}</dd>
        <dt className="text-sv-muted">Raw audio bytes persisted</dt>
        <dd className="font-mono">{data.rawAudioBytesPersisted ?? 0}</dd>
      </dl>
      <p className="text-xs text-sv-muted">{data.auditChainNote}</p>
    </div>
  );
}

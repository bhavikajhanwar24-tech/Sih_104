import { useEffect, useState } from 'react';
import PropTypes from 'prop-types';
import { apiFetch } from '@/services/api.js';

/**
 * Retention dashboard — every figure from GET /api/v1/compliance/retention (real queries).
 */
export function RetentionDashboard() {
  const [data, setData] = useState(/** @type {Record<string, any> | null} */ (null));
  const [error, setError] = useState(/** @type {string | null} */ (null));

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const res = await apiFetch('/api/v1/compliance/retention');
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

  const buckets = data.telemetry?.ageBuckets ?? {};
  const probe = data.rawAudioSchemaProbe ?? {};

  return (
    <div className="flex flex-col gap-4 text-sm">
      <div className="rounded border border-emerald-700/40 bg-emerald-950/20 px-3 py-3">
        <p className="font-mono text-[10px] uppercase tracking-wider text-emerald-400/90">
          Zero raw-audio retention
        </p>
        <p className="mt-1 font-display text-3xl font-semibold text-sv-fg">
          Raw audio bytes persisted:{' '}
          <span className="text-emerald-400">{data.rawAudioBytesPersisted}</span>
        </p>
        <p className="mt-2 text-[11px] text-sv-muted">
          Enforcing code path:{' '}
          <code className="font-mono text-sv-accent">{data.rawAudioEnforcingPath}</code>
          {' · '}schema pcm-like columns: {probe.pcmLikeColumns ?? 0}
          {' · '}guard:{' '}
          <code className="font-mono text-[10px]">{probe.decisionPlaneGuardTest}</code>
        </p>
      </div>

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <Stat label="Embeddings stored" value={data.embeddingsStored} hint="voice_passports active" />
        <Stat label="Erasures performed" value={data.erasuresPerformed} hint="PASSPORT_ERASED" />
        <Stat label="Tombstones recorded" value={data.tombstonesRecorded} hint="same erase events" />
        <Stat label="Audit blocks" value={data.auditBlocksTotal} hint="RBI immutable log" />
      </div>

      <div>
        <h4 className="mb-2 font-display text-[11px] font-semibold uppercase tracking-wider text-sv-muted">
          Telemetry age vs {data.telemetryTtlDays}-day TTL
        </h4>
        <p className="mb-2 text-[11px] text-sv-muted">
          Source event: <span className="font-mono">{data.telemetry?.eventType}</span> · rows:{' '}
          {data.telemetry?.totalRows ?? 0}
        </p>
        <div className="grid gap-2 sm:grid-cols-4">
          <Bucket label="0–7 d" count={buckets['0_7_days']} />
          <Bucket label="8–30 d" count={buckets['8_30_days']} />
          <Bucket label="31–TTL" count={buckets['31_ttl_days']} />
          <Bucket label="Past TTL" count={buckets.past_ttl} warn />
        </div>
      </div>

      <p className="text-[11px] text-sv-muted">
        Next scheduled purge:{' '}
        <span className="font-mono text-sv-fg">{data.nextScheduledPurge ?? '—'}</span>
        {data.lastPurgeAt ? (
          <>
            {' · '}last: <span className="font-mono">{data.lastPurgeAt}</span>
          </>
        ) : null}
        {' · '}interval {data.purgeIntervalHours}h · audit retention {data.auditRetentionYears}y
      </p>
    </div>
  );
}

RetentionDashboard.propTypes = {};

function Stat({ label, value, hint }) {
  return (
    <div className="rounded border border-sv-border bg-sv-elevated/40 px-3 py-2">
      <p className="text-[10px] uppercase tracking-wide text-sv-muted">{label}</p>
      <p className="font-mono text-2xl text-sv-fg">{value ?? '—'}</p>
      <p className="text-[10px] text-sv-muted">{hint}</p>
    </div>
  );
}

Stat.propTypes = {
  label: PropTypes.string.isRequired,
  value: PropTypes.oneOfType([PropTypes.number, PropTypes.string]),
  hint: PropTypes.string,
};

function Bucket({ label, count, warn = false }) {
  return (
    <div
      className={`rounded border px-2 py-2 ${
        warn ? 'border-amber-700/50 bg-amber-950/20' : 'border-sv-border'
      }`}
    >
      <p className="text-[10px] text-sv-muted">{label}</p>
      <p className="font-mono text-lg text-sv-fg">{count ?? 0}</p>
    </div>
  );
}

Bucket.propTypes = {
  label: PropTypes.string.isRequired,
  count: PropTypes.number,
  warn: PropTypes.bool,
};

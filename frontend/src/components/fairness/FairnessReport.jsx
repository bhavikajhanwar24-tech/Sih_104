import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { apiJson } from '@/services/api.js';
import { Badge, Button, EmptyState } from '@/ui';

/**
 * F16 — FP rate by optional directory fairness tags (never inferred from voice).
 * Renders CI whiskers and explicit "insufficient data" when n &lt; minGroupSize.
 */
export function FairnessReport({
  endpoint = '/api/v2/analytics/fairness',
  compact = false,
}) {
  const [report, setReport] = useState(null);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    const candidates = [
      endpoint,
      '/api/v2/analytics/fairness',
      '/api/v2/compliance/fairness',
    ].filter((v, i, a) => v && a.indexOf(v) === i);

    let lastErr = null;
    for (const url of candidates) {
      try {
        const body = await apiJson(url, { skipErrorToast: true });
        // Skip obsolete F15 placeholder payloads
        if (
          body?.status === 'EVALUATION_NOT_RUN' &&
          !Array.isArray(body?.dimensions)
        ) {
          lastErr = new Error(body.message || 'Stale fairness placeholder');
          continue;
        }
        setReport(body);
        setLoading(false);
        return;
      } catch (err) {
        lastErr = err;
      }
    }
    setReport(null);
    setError(lastErr instanceof Error ? lastErr.message : 'Failed to load fairness');
    setLoading(false);
  }, [endpoint]);

  useEffect(() => {
    void load();
  }, [load]);

  if (loading && !report) {
    return <p className="text-sm text-sv-muted">Loading fairness report…</p>;
  }

  if (error) {
    return (
      <div className="space-y-2">
        <p className="text-sm text-red-400">{error}</p>
        <p className="text-xs text-sv-muted">
          Restart the Decision Plane so F16 analytics is loaded, then retry.
        </p>
        <Button variant="secondary" onClick={() => void load()}>
          Retry
        </Button>
      </div>
    );
  }

  if (!report) {
    return (
      <EmptyState
        title="No fairness report"
        description="Could not load directory-tag fairness metrics."
      />
    );
  }

  const dims = Array.isArray(report.dimensions) ? report.dimensions : [];
  const minGroup = Number(report.minGroupSize) > 0 ? Number(report.minGroupSize) : 1;
  const totalGroups = dims.reduce((n, d) => n + (d.groups?.length || 0), 0);

  return (
    <div className={compact ? 'space-y-4' : 'space-y-6'}>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          {!compact ? (
            <>
              <h2 className="text-lg font-semibold text-sv-fg">Fairness reporting</h2>
              <p className="mt-1 text-sm text-sv-muted">
                {report.message ||
                  'FP rates by optional directory tags — never inferred from voice.'}
              </p>
            </>
          ) : (
            <p className="text-sm text-sv-muted">
              {report.message ||
                'FP rates by optional directory tags — never inferred from voice.'}
            </p>
          )}
          <div className="mt-2 flex flex-wrap gap-2">
            <Badge tone="neutral">{report.status || 'OK'}</Badge>
            <Badge tone="neutral">source: {report.source || 'directory_fairness_tags'}</Badge>
          </div>
        </div>
        <Button variant="secondary" onClick={() => void load()}>
          Refresh
        </Button>
      </div>

      <div className="rounded border border-sv-border bg-sv-elevated/20 px-3 py-2 text-xs text-sv-muted">
        Protected attributes are <strong className="text-sv-fg">never inferred from voice</strong>.
        Only optional tags you set on Directory employees (language, region, gender, age band) are
        used. Tag people under{' '}
        <Link to="/app/directory" className="text-sv-accent hover:underline">
          Directory → Profile
        </Link>
        .
      </div>

      {totalGroups === 0 ? (
        <p className="text-sm text-sv-muted">
          No tagged labelled alerts yet — set fairness tags on Directory employees, label L3+ calls
          as FP/confirmed, then Refresh. Rates show for every group with at least one label.
        </p>
      ) : null}

      {(dims.length > 0
        ? dims
        : [
            { key: 'language', groups: [] },
            { key: 'region', groups: [] },
            { key: 'gender', groups: [] },
            { key: 'ageBand', groups: [] },
          ]
      ).map((dim) => (
        <DimensionPanel key={dim.key} dim={dim} minGroup={minGroup} />
      ))}
    </div>
  );
}

function DimensionPanel({ dim, minGroup }) {
  const groups = Array.isArray(dim.groups) ? dim.groups : [];
  return (
    <section className="rounded border border-sv-border bg-sv-panel p-4">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <h3 className="font-mono text-xs font-semibold uppercase tracking-wide text-sv-fg">
          {dim.key}
        </h3>
        <span className="text-[11px] text-sv-muted">
          {dim.usableGroups ?? 0} usable / {groups.length} groups
        </span>
      </div>
      {groups.length === 0 ? (
        <p className="text-xs text-sv-muted">No values for this tag yet.</p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full min-w-[480px] text-left text-xs">
            <thead className="text-sv-muted">
              <tr>
                <th className="py-1 font-medium">Group</th>
                <th className="font-medium">n</th>
                <th className="font-medium">FP rate</th>
                <th className="font-medium">95% Wilson CI</th>
              </tr>
            </thead>
            <tbody>
              {groups.map((g) => (
                <tr key={`${dim.key}-${g.group}`} className="border-t border-sv-border/60">
                  <td className="py-2 font-medium text-sv-fg">{g.group}</td>
                  <td className="font-mono text-sv-muted">{g.n}</td>
                  <td>
                    {g.insufficientData ? (
                      <span className="text-sv-muted" title={`Need ≥${minGroup}`}>
                        insufficient data
                      </span>
                    ) : (
                      <span className="font-mono">{pct(g.fpRate)}</span>
                    )}
                  </td>
                  <td>
                    {g.insufficientData ? (
                      <span className="text-sv-muted">—</span>
                    ) : (
                      <div className="flex items-center gap-2">
                        <CiWhisker low={g.ciLow} high={g.ciHigh} point={g.fpRate} />
                        <span className="font-mono text-[10px] text-sv-muted">
                          {pct(g.ciLow)}–{pct(g.ciHigh)}
                        </span>
                      </div>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

function pct(v) {
  if (v == null || Number.isNaN(Number(v))) return '—';
  return `${(Number(v) * 100).toFixed(1)}%`;
}

function CiWhisker({ low, high, point }) {
  const l = Math.max(0, Math.min(1, Number(low) || 0));
  const h = Math.max(0, Math.min(1, Number(high) || 0));
  const p = Math.max(0, Math.min(1, Number(point) || 0));
  const left = Math.min(l, h) * 100;
  const width = Math.max(2, Math.abs(h - l) * 100);
  return (
    <div
      className="relative h-3 w-32 rounded bg-sv-bg"
      title={`CI ${pct(l)}–${pct(h)} · point ${pct(p)}`}
      role="img"
      aria-label={`Confidence interval ${pct(l)} to ${pct(h)}`}
    >
      <div
        className="absolute top-1 h-1 rounded bg-sv-accent/55"
        style={{ left: `${left}%`, width: `${width}%` }}
      />
      <div className="absolute top-0.5 h-2 w-0.5 bg-sv-fg" style={{ left: `${p * 100}%` }} />
    </div>
  );
}

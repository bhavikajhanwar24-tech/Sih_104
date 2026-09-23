import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, Navigate } from 'react-router-dom';
import { useAuth } from '@/context/AuthContext.jsx';
import { apiJson } from '@/services/api.js';
import { FairnessReport } from '@/components/fairness/FairnessReport.jsx';
import { Badge, Button, EmptyState } from '@/ui';
import { useToast } from '@/ui/Toast.jsx';

/**
 * F16 — /app/analytics: volume, FP/TP, rule quality, fairness, L3 threshold what-if.
 */
export function AnalyticsPage() {
  const { hasPermission } = useAuth();
  const { push } = useToast();
  const canRead = hasPermission('analytics:read') || hasPermission('dashboard:read');
  const canDraft = hasPermission('settings:write') || hasPermission('policies:write');

  const [overview, setOverview] = useState(null);
  const [rules, setRules] = useState([]);
  const [threshold, setThreshold] = useState(null);
  const [slider, setSlider] = useState(0.55);
  const [whatIfSessionId, setWhatIfSessionId] = useState('');
  const [whatIfBusy, setWhatIfBusy] = useState(false);
  const [whatIfTicks, setWhatIfTicks] = useState([]);
  const [draftBusy, setDraftBusy] = useState(false);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const settled = await Promise.allSettled([
        apiJson('/api/v2/analytics/overview?days=30', { skipErrorToast: true }),
        apiJson('/api/v2/analytics/rules', { skipErrorToast: true }),
        apiJson('/api/v2/analytics/threshold-suggestion', { skipErrorToast: true }),
      ]);
      const [ov, ru, th] = settled;
      if (ov.status === 'fulfilled') {
        setOverview(ov.value);
      } else {
        setOverview(null);
        push(ov.reason instanceof Error ? ov.reason.message : 'Overview failed to load');
      }
      if (ru.status === 'fulfilled') {
        setRules(Array.isArray(ru.value?.items) ? ru.value.items : []);
      } else {
        setRules([]);
      }
      if (th.status === 'fulfilled') {
        setThreshold(th.value);
        if (typeof th.value?.currentL3Enter === 'number') setSlider(th.value.currentL3Enter);
      } else {
        setThreshold(null);
      }
    } catch (err) {
      push(err instanceof Error ? err.message : 'Failed to load analytics');
    } finally {
      setLoading(false);
    }
  }, [push]);

  useEffect(() => {
    if (canRead) void load();
  }, [canRead, load]);

  const sliderPoint = useMemo(() => {
    if (!threshold?.curve) return null;
    let best = null;
    let bestDist = Infinity;
    for (const p of threshold.curve) {
      const d = Math.abs(Number(p.threshold) - slider);
      if (d < bestDist) {
        bestDist = d;
        best = p;
      }
    }
    return best;
  }, [threshold, slider]);

  async function createDraft() {
    setDraftBusy(true);
    try {
      const draft = await apiJson('/api/v2/analytics/threshold-suggestion/draft', {
        method: 'POST',
        body: JSON.stringify({ l3Enter: slider }),
      });
      push(`Draft fusion config v${draft.version ?? '?'} created — still needs approval`);
    } catch (err) {
      push(err instanceof Error ? err.message : 'Draft failed');
    } finally {
      setDraftBusy(false);
    }
  }

  async function runWhatIf() {
    const sid = whatIfSessionId.trim();
    if (!sid) {
      push('Enter a live session id for F8 what-if replay');
      return;
    }
    setWhatIfBusy(true);
    try {
      const active = await apiJson('/api/v2/fusion-configs/active', { skipErrorToast: true }).catch(() => null);
      const config = active?.config ? structuredClone(active.config) : null;
      if (!config?.levels?.L3) {
        push('No active fusion config to mutate');
        return;
      }
      config.levels.L3.enter = slider;
      if (config.levels.L3.exit >= slider) {
        config.levels.L3.exit = Math.max(0.05, slider - 0.09);
      }
      const res = await apiJson('/api/v2/fusion-configs/what-if', {
        method: 'POST',
        body: JSON.stringify({ config, sessionId: sid }),
      });
      setWhatIfTicks(Array.isArray(res?.ticks) ? res.ticks : []);
      push(`What-if replay: ${res?.tickCount ?? 0} ticks`);
    } catch (err) {
      push(err instanceof Error ? err.message : 'What-if failed');
      setWhatIfTicks([]);
    } finally {
      setWhatIfBusy(false);
    }
  }

  if (!canRead) {
    return <Navigate to="/app" replace />;
  }

  if (loading && !overview) {
    return <p className="p-6 text-sm text-sv-muted">Loading analytics…</p>;
  }

  const volume = overview?.alertVolumeDaily || [];
  const byLevel = overview?.fpRateByLevel || [];
  const maxAlerts = Math.max(1, ...volume.map((d) => Number(d.alertsL3Plus) || 0));

  return (
    <div className="space-y-6 p-6">
      <header className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold text-sv-fg">Analytics</h1>
          <p className="mt-1 text-sm text-sv-muted">
            Labelled-alert metrics, rule quality, fairness (directory tags only), L3 threshold trade-offs.
          </p>
        </div>
        <Button variant="secondary" onClick={() => void load()}>
          Refresh
        </Button>
      </header>

      {overview?.lowDataWarning ? (
        <div className="rounded border border-amber-500/40 bg-amber-500/10 px-3 py-2 text-sm text-amber-100">
          Low data: {overview.labelledSessions} labelled sessions (need ≥{overview.labelWarnBelow}).
          Figures are directional until more labels land.
        </div>
      ) : null}

      <section className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <Kpi
          label="Precision proxy (≥L3)"
          value={pct(overview?.precisionProxy?.precision)}
          hint={`${overview?.precisionProxy?.confirmedFraud ?? 0} confirmed / ${overview?.precisionProxy?.alertsL3Plus ?? 0} alerts`}
        />
        <Kpi
          label="FP rate (≥L3)"
          value={pct(overview?.precisionProxy?.falsePositiveRate)}
          hint={`${overview?.precisionProxy?.falsePositives ?? 0} false positives`}
        />
        <Kpi
          label="Labelled (window)"
          value={overview?.labelledSessions ?? 0}
          hint={`TTL avg ${overview?.timeToLabel?.avgSec ?? '—'}s`}
        />
        <Kpi
          label="Rules needing review"
          value={(overview?.rulesNeedingReview || []).length}
          hint="Fire rate or FP contribution above tenant limits"
        />
      </section>

      <section className="rounded border border-sv-border bg-sv-panel p-4">
        <h2 className="text-sm font-semibold text-sv-fg">Alert volume (L3+)</h2>
        {volume.length === 0 ? (
          <EmptyState title="No volume yet" description="Calls will appear as sessions accumulate." />
        ) : (
          <div className="mt-3 flex h-28 items-end gap-1">
            {volume.map((d) => (
              <div key={d.day} className="flex flex-1 flex-col items-center gap-1" title={`${d.day}: ${d.alertsL3Plus}`}>
                <div
                  className="w-full rounded-t bg-sv-accent/70"
                  style={{ height: `${Math.max(4, (Number(d.alertsL3Plus) / maxAlerts) * 100)}%` }}
                />
                <span className="font-mono text-[9px] text-sv-muted">{String(d.day).slice(5)}</span>
              </div>
            ))}
          </div>
        )}
      </section>

      <section className="grid gap-4 lg:grid-cols-2">
        <div className="rounded border border-sv-border bg-sv-panel p-4">
          <h2 className="text-sm font-semibold">FP / TP by peak level</h2>
          <table className="mt-3 w-full text-left text-xs">
            <thead className="text-sv-muted">
              <tr>
                <th className="py-1">Level</th>
                <th>Calls</th>
                <th>FP</th>
                <th>TP</th>
                <th>FP rate</th>
              </tr>
            </thead>
            <tbody>
              {byLevel.map((r) => (
                <tr key={r.level} className="border-t border-sv-border/60">
                  <td className="py-1.5 font-mono">{r.level}</td>
                  <td>{r.calls}</td>
                  <td>{r.falsePositives}</td>
                  <td>{r.confirmedFraud}</td>
                  <td>{pct(r.fpRate)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        <div className="rounded border border-sv-border bg-sv-panel p-4">
          <h2 className="text-sm font-semibold">Family contribution (labelled)</h2>
          <ul className="mt-3 space-y-2">
            {(overview?.familyContribution || []).map((f) => (
              <li key={f.family} className="flex items-center gap-2 text-xs">
                <span className="w-24 font-mono text-sv-muted">{f.family}</span>
                <div className="h-2 flex-1 rounded bg-sv-bg">
                  <div
                    className="h-2 rounded bg-sv-accent/60"
                    style={{ width: `${Math.min(100, (Number(f.avgScoreOnLabelled) || 0) * 100)}%` }}
                  />
                </div>
                <span className="w-12 text-right font-mono">{f.avgScoreOnLabelled ?? '—'}</span>
              </li>
            ))}
          </ul>
        </div>
      </section>

      <section className="rounded border border-sv-border bg-sv-panel p-4">
        <h2 className="text-sm font-semibold">Per-rule quality</h2>
        <p className="mt-0.5 text-xs text-sv-muted">Fire rate and FP contribution; flagged rows need review.</p>
        <div className="mt-3 overflow-x-auto">
          <table className="w-full min-w-[640px] text-left text-xs">
            <thead className="text-sv-muted">
              <tr>
                <th className="py-1">Rule</th>
                <th>Fire rate</th>
                <th>FP contrib</th>
                <th>14d spark</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {rules.length === 0 ? (
                <tr>
                  <td colSpan={5} className="py-4 text-sv-muted">
                    No fired rules yet.
                  </td>
                </tr>
              ) : (
                rules.slice(0, 40).map((r) => (
                  <tr key={r.ruleId} className="border-t border-sv-border/60">
                    <td className="max-w-[14rem] truncate py-1.5 font-mono" title={r.ruleId}>
                      {r.ruleId}
                    </td>
                    <td>{pct(r.fireRate)}</td>
                    <td>{r.fpContribution == null ? '—' : pct(r.fpContribution)}</td>
                    <td>
                      <Sparkline values={r.sparkline || []} />
                    </td>
                    <td>
                      {r.needsReview ? (
                        <Badge tone="warn">needs review</Badge>
                      ) : (
                        <Badge tone="neutral">ok</Badge>
                      )}
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </section>

      <section className="rounded border border-sv-border bg-sv-panel p-4">
        <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
          <h2 className="text-sm font-semibold">Fairness (directory tags)</h2>
          <Link to="/app/fairness" className="text-xs text-sv-accent hover:underline">
            Open Fairness page →
          </Link>
        </div>
        <FairnessReport endpoint="/api/v2/analytics/fairness" compact />
      </section>

      <section className="rounded border border-sv-border bg-sv-panel p-4">
        <h2 className="text-sm font-semibold">L3 threshold what-if</h2>
        <p className="mt-0.5 text-xs text-sv-muted">{threshold?.summary}</p>
        {threshold?.lowDataWarning ? (
          <p className="mt-2 text-xs text-amber-200">
            Sample size {threshold.sampleSize} &lt; {threshold.labelWarnBelow} — low-data warning.
          </p>
        ) : null}
        <div className="mt-4 flex flex-wrap items-end gap-4">
          <label className="block text-xs text-sv-muted">
            L3 enter = {slider.toFixed(2)}
            <input
              type="range"
              min={0.25}
              max={0.9}
              step={0.01}
              value={slider}
              onChange={(e) => setSlider(Number(e.target.value))}
              className="mt-1 block w-64"
            />
          </label>
          <div className="text-xs text-sv-fg">
            <p>
              At slider: FP {pct(sliderPoint?.fpRate)} · precision {pct(sliderPoint?.precision)} · alerts{' '}
              {sliderPoint?.alerts ?? '—'}
            </p>
            <p className="text-sv-muted">
              Current config enter={threshold?.currentL3Enter ?? '—'} · FP{' '}
              {pct(threshold?.atCurrent?.fpRate)}
            </p>
          </div>
        </div>
        <div className="mt-3 flex flex-wrap gap-2">
          <Button
            disabled={draftBusy || !canDraft}
            onClick={() => void createDraft()}
            title={!canDraft ? 'Requires TENANT_ADMIN draft permission' : undefined}
          >
            {draftBusy ? 'Creating…' : 'Create draft from suggestion'}
          </Button>
          <Link to="/app/settings/risk-tuning" className="text-xs text-sv-accent self-center hover:underline">
            Open Risk tuning →
          </Link>
        </div>
        <div className="mt-4 flex flex-wrap items-end gap-2 border-t border-sv-border pt-4">
          <label className="text-xs text-sv-muted">
            F8 replay session id
            <input
              className="mt-1 block w-72 rounded border border-sv-border bg-sv-bg px-2 py-1 font-mono text-xs text-sv-fg"
              value={whatIfSessionId}
              onChange={(e) => setWhatIfSessionId(e.target.value)}
              placeholder="live sv session uuid"
            />
          </label>
          <Button variant="secondary" disabled={whatIfBusy} onClick={() => void runWhatIf()}>
            {whatIfBusy ? 'Replaying…' : 'Run F8 what-if'}
          </Button>
        </div>
        {whatIfTicks.length > 0 ? (
          <p className="mt-2 font-mono text-[11px] text-sv-muted">
            Last tick level={String(whatIfTicks[whatIfTicks.length - 1]?.level ?? '—')} score=
            {Number(whatIfTicks[whatIfTicks.length - 1]?.score ?? 0).toFixed(3)} ({whatIfTicks.length} ticks)
          </p>
        ) : null}
      </section>
    </div>
  );
}

function Kpi({ label, value, hint }) {
  return (
    <div className="rounded border border-sv-border bg-sv-elevated/30 px-3 py-2">
      <p className="font-mono text-[10px] uppercase tracking-wide text-sv-muted">{label}</p>
      <p className="mt-1 font-display text-xl text-sv-fg">{value ?? '—'}</p>
      {hint ? <p className="mt-0.5 text-[11px] text-sv-muted">{hint}</p> : null}
    </div>
  );
}

function pct(v) {
  if (v == null || Number.isNaN(Number(v))) return '—';
  return `${(Number(v) * 100).toFixed(1)}%`;
}

function Sparkline({ values }) {
  const nums = (values || []).map(Number).filter((n) => Number.isFinite(n));
  if (!nums.length) return <span className="text-sv-muted">—</span>;
  const max = Math.max(1, ...nums);
  const w = 56;
  const h = 16;
  const step = nums.length === 1 ? 0 : w / (nums.length - 1);
  const pts = nums.map((n, i) => `${i * step},${h - (n / max) * (h - 2) - 1}`).join(' ');
  return (
    <svg width={w} height={h} className="inline-block align-middle text-sv-accent">
      <polyline fill="none" stroke="currentColor" strokeWidth="1.5" points={pts} />
    </svg>
  );
}

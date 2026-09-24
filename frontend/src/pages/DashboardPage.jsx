/**
 * F14 — Tenant admin dashboard (server aggregates).
 */
import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from '@/context/AuthContext.jsx';
import { apiJson } from '@/services/api.js';
import { Badge, Button, EmptyState } from '@/ui';
import { useToast } from '@/ui/Toast.jsx';
import {
  CoverageMeters,
  HorizontalBars,
  LevelBarChart,
  LevelDonut,
  VolumeGroupedChart,
} from '@/components/dashboard/DashboardCharts.jsx';

function Kpi({ label, value, hint }) {
  return (
    <div className="rounded border border-sv-border bg-sv-panel px-3 py-2 shadow-sm shadow-black/5">
      <p className="font-mono text-[10px] uppercase tracking-wide text-sv-muted">{label}</p>
      <p className="mt-1 font-display text-xl text-sv-fg">{value ?? '—'}</p>
      {hint ? <p className="mt-0.5 text-[11px] text-sv-muted">{hint}</p> : null}
    </div>
  );
}

function Panel({ title, children, className = '' }) {
  return (
    <div className={`rounded border border-sv-border bg-sv-panel p-3 shadow-sm shadow-black/5 ${className}`}>
      {title ? <h2 className="mb-2 text-sm font-semibold text-sv-fg">{title}</h2> : null}
      {children}
    </div>
  );
}

export function DashboardPage() {
  const { hasPermission, me } = useAuth();
  const { push } = useToast();
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [killMode, setKillMode] = useState('MONITOR_ONLY');
  const [killPw, setKillPw] = useState('');
  const [killBusy, setKillBusy] = useState(false);

  const canRead = hasPermission('dashboard:read');
  const canKill = hasPermission('governance:kill') || hasPermission('calls:kill');

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const snap = await apiJson('/api/v2/governance/dashboard', {
        skipErrorToast: true,
      });
      setData(snap);
    } catch (err) {
      setError(err?.message || err?.body?.message || 'Failed to load dashboard');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (canRead) void load();
  }, [canRead, load]);

  if (!canRead) {
    return (
      <div className="p-6">
        <EmptyState title="Permission denied" description="dashboard:read is required." />
      </div>
    );
  }

  async function enableEmergency() {
    if (!canKill || !killPw) return;
    setKillBusy(true);
    try {
      await apiJson('/api/v2/governance/emergency/enable', {
        method: 'POST',
        body: JSON.stringify({ mode: killMode, password: killPw, ttlMinutes: 60 }),
      });
      setKillPw('');
      push(`${killMode} enabled`, 'ok');
      void load();
    } catch (err) {
      push(err instanceof Error ? err.message : 'Kill switch failed');
    } finally {
      setKillBusy(false);
    }
  }

  async function disableEmergency() {
    if (!canKill || !killPw) return;
    setKillBusy(true);
    try {
      await apiJson('/api/v2/governance/emergency/disable', {
        method: 'POST',
        body: JSON.stringify({ password: killPw }),
      });
      setKillPw('');
      push('Emergency mode cleared', 'ok');
      void load();
    } catch (err) {
      push(err instanceof Error ? err.message : 'Disable failed');
    } finally {
      setKillBusy(false);
    }
  }

  const emergency = data?.emergency;
  const kpis = data?.kpis || {};
  const health = data?.configHealth || {};

  return (
    <div className="p-4 md:p-6">
      {emergency?.active ? (
        <div
          role="alert"
          className="mb-4 rounded border border-risk-critical/50 bg-risk-critical/10 px-4 py-3 text-sm text-sv-fg"
        >
          <strong className="font-semibold text-risk-critical">{emergency.mode}</strong>
          {emergency.mode === 'SUSPEND_MONITORING'
            ? ' — analysing is stopped; calls continue unmonitored.'
            : ' — automatic actions suppressed to advisory + notification.'}
          {emergency.expiresAt ? ` Expires ${emergency.expiresAt}.` : ''}
        </div>
      ) : null}

      <header className="mb-4 flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="font-display text-2xl text-sv-fg">Dashboard</h1>
          <p className="text-sm text-sv-muted">
            {me?.tenant?.name || 'Tenant'} · live + historical aggregates
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button variant="secondary" onClick={() => void load()} disabled={loading}>
            Refresh
          </Button>
          {hasPermission('approvals:read') ? (
            <Link to="/app/approvals" className="text-sm text-sv-accent underline-offset-2 hover:underline">
              Approvals inbox →
            </Link>
          ) : null}
          {hasPermission('governance:read') ? (
            <Link to="/app/changes" className="text-sm text-sv-accent underline-offset-2 hover:underline">
              Change history →
            </Link>
          ) : null}
        </div>
      </header>

      {loading && !data ? (
        <p className="text-sm text-sv-muted">Loading dashboard…</p>
      ) : error ? (
        <div className="rounded border border-risk-critical/40 bg-risk-critical/10 p-4 text-sm text-risk-critical">
          {error}
          <Button className="ml-3" variant="ghost" onClick={() => void load()}>
            Retry
          </Button>
        </div>
      ) : !data ? (
        <EmptyState title="No data" description="Dashboard aggregates will appear once calls are recorded." />
      ) : (
        <div className="space-y-6">
          <section>
            <h2 className="mb-2 text-sm font-semibold text-sv-fg">KPIs</h2>
            <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-4">
              <Kpi label="Calls today" value={kpis.today?.callsMonitored} />
              <Kpi label="Calls 7d" value={kpis.d7?.callsMonitored} />
              <Kpi label="Actions 7d" value={kpis.d7?.actionsExecuted} />
              <Kpi
                label="Avg TTI 7d"
                value={
                  kpis.d7?.avgTimeToInterventionSec != null
                    ? `${kpis.d7.avgTimeToInterventionSec}s`
                    : '—'
                }
              />
              <Kpi label="LLM availability" value={`${data.llmAvailabilityPct ?? '—'}%`} />
              <Kpi
                label="Pipeline p95"
                value={data.pipelineLatencyP95Ms != null ? `${Math.round(data.pipelineLatencyP95Ms)} ms` : '—'}
              />
              <Kpi
                label="False-positive rate"
                value={
                  data.falsePositiveRate?.available
                    ? data.falsePositiveRate.rate != null
                      ? `${(Number(data.falsePositiveRate.rate) * 100).toFixed(1)}%`
                      : '—'
                    : '—'
                }
                hint={
                  data.falsePositiveRate?.available
                    ? `30d · ${data.falsePositiveRate.labelledSessions ?? 0} labels`
                    : data.falsePositiveRate?.note || 'Label alerts on Call Detail'
                }
              />
              <Kpi label="Calls 30d" value={kpis.d30?.callsMonitored} />
            </div>
          </section>

          {(data.rulesNeedingReview || []).length > 0 ? (
            <section className="rounded border border-amber-500/40 bg-amber-500/5 p-3">
              <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
                <h2 className="text-sm font-semibold text-sv-fg">Rules needing review</h2>
                <Link to="/app/analytics" className="text-xs text-sv-accent hover:underline">
                  Open Analytics →
                </Link>
              </div>
              <ul className="space-y-1 text-xs">
                {(data.rulesNeedingReview || []).slice(0, 6).map((r) => (
                  <li key={r.ruleId} className="flex flex-wrap items-center gap-2 font-mono">
                    <Badge tone="warn">review</Badge>
                    <span className="truncate">{r.ruleId}</span>
                    <span className="text-sv-muted">
                      fire {(Number(r.fireRate) * 100).toFixed(0)}%
                      {r.fpContribution != null
                        ? ` · FP ${(Number(r.fpContribution) * 100).toFixed(0)}%`
                        : ''}
                    </span>
                  </li>
                ))}
              </ul>
            </section>
          ) : null}

          <section className="grid gap-4 lg:grid-cols-2">
            <Panel title="Calls by max level (7d)">
              <LevelBarChart rows={data.callsByMaxLevel || []} />
            </Panel>
            <Panel title="Volume — calls vs actions">
              <VolumeGroupedChart kpis={kpis} />
            </Panel>
          </section>

          <section className="grid gap-4 lg:grid-cols-2">
            <Panel title="Level mix (7d)">
              <LevelDonut rows={data.callsByMaxLevel || []} />
            </Panel>
            <Panel title="Pending approvals">
              {(data.pendingApprovals || []).length === 0 ? (
                <p className="text-xs text-sv-muted">None pending.</p>
              ) : (
                <ul className="space-y-1.5">
                  {(data.pendingApprovals || []).map((p) => (
                    <li key={`${p.area}-${p.id}`} className="flex items-center justify-between gap-2 text-xs text-sv-fg">
                      <span>
                        <Badge tone="warn">{p.area}</Badge> {p.title}
                      </span>
                      <span className="text-sv-muted">{p.ageMinutes}m</span>
                    </li>
                  ))}
                </ul>
              )}
            </Panel>
          </section>

          <section className="grid gap-4 lg:grid-cols-2">
            <Panel title="Configuration health">
              {(health.checklist || []).length === 0 ? (
                <ul className="space-y-1 text-xs text-sv-fg">
                  <li>Policy active: {health.policyActive ? 'yes' : 'no'}</li>
                  <li>Response plan floors: {health.responsePlanFloorsSatisfied ? 'ok' : 'missing'}</li>
                  <li>Phone coverage: {health.directoryPhoneCoveragePct}%</li>
                </ul>
              ) : (
                <ul className="mb-3 space-y-2" role="list">
                  {(health.checklist || []).map((item) => (
                    <li
                      key={item.id}
                      className={`flex flex-wrap items-start justify-between gap-2 rounded border px-2 py-1.5 text-xs ${
                        item.ok
                          ? 'border-risk-clear/40 bg-risk-clear/10 text-sv-fg'
                          : 'border-risk-watch/40 bg-risk-watch/10 text-sv-fg'
                      }`}
                    >
                      <div className="min-w-0">
                        <p className={`font-medium ${item.ok ? 'text-risk-clear' : 'text-risk-watch'}`}>
                          {item.ok ? '✓' : '○'} {item.label}
                        </p>
                        {!item.ok && item.hint ? (
                          <p className="mt-0.5 text-[11px] text-sv-muted">{item.hint}</p>
                        ) : null}
                      </div>
                      {!item.ok && item.href ? (
                        <Link
                          to={item.href}
                          className="shrink-0 font-medium text-sv-accent underline-offset-2 hover:underline"
                        >
                          Fix →
                        </Link>
                      ) : (
                        <span className="shrink-0 text-[10px] uppercase tracking-wide text-risk-clear">ok</span>
                      )}
                    </li>
                  ))}
                </ul>
              )}
              <CoverageMeters
                phonePct={health.directoryPhoneCoveragePct}
                authPct={health.directoryAuthorityCoveragePct}
              />
            </Panel>
            <div className="space-y-4">
              <Panel title="Top targeted departments (7d)">
                <HorizontalBars
                  rows={(data.topTargets || []).map((t) => ({
                    label: t.department || 'Unknown',
                    count: t.count,
                  }))}
                  color="#d97706"
                  empty="No elevated calls."
                />
              </Panel>
              <Panel title="Top risk reasons (7d)">
                <HorizontalBars
                  rows={(data.topRiskReasons || []).map((t) => ({
                    label: t.code || '—',
                    count: t.count,
                  }))}
                  color="#dc2626"
                  empty="No reason codes yet."
                />
              </Panel>
            </div>
          </section>

          <Panel title="Recent high-risk calls">
            {(data.highRiskCalls || []).length === 0 ? (
              <p className="text-xs text-sv-muted">No L3+ calls yet.</p>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-left text-xs text-sv-fg" role="table">
                  <thead>
                    <tr className="border-b border-sv-border text-sv-muted">
                      <th className="py-1 pr-2 font-medium">When</th>
                      <th className="py-1 pr-2 font-medium">Callee</th>
                      <th className="py-1 pr-2 font-medium">Level</th>
                      <th className="py-1 font-medium">Link</th>
                    </tr>
                  </thead>
                  <tbody>
                    {(data.highRiskCalls || []).map((c) => (
                      <tr key={c.id} className="border-b border-sv-border/60">
                        <td className="py-1.5 pr-2 font-mono text-[10px] text-sv-muted">{c.startedAt}</td>
                        <td className="py-1.5 pr-2">{c.calleeLabel}</td>
                        <td className="py-1.5 pr-2 font-mono">{c.peakLevel}</td>
                        <td className="py-1.5">
                          {c.svSessionUuid ? (
                            <Link
                              to={`/app/sessions/${encodeURIComponent(c.svSessionUuid)}`}
                              className="text-sv-accent hover:underline"
                            >
                              Open
                            </Link>
                          ) : (
                            '—'
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Panel>

          {canKill ? (
            <section className="rounded border border-risk-critical/40 bg-risk-critical/10 p-3">
              <h2 className="mb-2 text-sm font-semibold text-risk-critical">Kill switch / emergency modes</h2>
              <p className="mb-2 text-xs text-sv-muted">
                Requires password re-entry. Monitor-only auto-expires; suspend applies immediately to in-flight sessions.
              </p>
              <div className="flex flex-wrap items-end gap-2">
                <label className="text-xs text-sv-fg">
                  Mode
                  <select
                    className="ml-1 rounded border border-sv-border bg-sv-panel px-2 py-1 text-sv-fg"
                    value={killMode}
                    onChange={(e) => setKillMode(e.target.value)}
                  >
                    <option value="MONITOR_ONLY">Monitor-only</option>
                    <option value="SUSPEND_MONITORING">Suspend monitoring</option>
                  </select>
                </label>
                <label className="text-xs text-sv-fg">
                  Password
                  <input
                    type="password"
                    className="ml-1 rounded border border-sv-border bg-sv-panel px-2 py-1 text-sv-fg"
                    value={killPw}
                    onChange={(e) => setKillPw(e.target.value)}
                    autoComplete="current-password"
                  />
                </label>
                <Button disabled={killBusy || !killPw} onClick={() => void enableEmergency()}>
                  Enable
                </Button>
                <Button
                  variant="secondary"
                  disabled={killBusy || !killPw || !emergency?.active}
                  onClick={() => void disableEmergency()}
                >
                  Disable
                </Button>
              </div>
            </section>
          ) : null}
        </div>
      )}
    </div>
  );
}

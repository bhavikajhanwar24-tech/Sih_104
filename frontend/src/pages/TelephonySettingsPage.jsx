import { useCallback, useEffect, useState } from 'react';
import { Link, Navigate } from 'react-router-dom';
import { useAuth } from '@/context/AuthContext.jsx';
import { apiJson } from '@/services/api.js';
import { Badge, Button, Input, Select, Table } from '@/ui';
import { useToast } from '@/ui/Toast.jsx';

const TRUNK_TYPES = ['INTERNAL', 'CARRIER', 'CCAAS'];

/**
 * F10 — Settings → Telephony (trunks, numbering, Asterisk health, LAB_MODE).
 */
export function TelephonySettingsPage() {
  const { hasPermission } = useAuth();
  const { push } = useToast();
  const canWrite = hasPermission('settings:write') || hasPermission('directory:write');

  const [health, setHealth] = useState(null);
  const [trunks, setTrunks] = useState([]);
  const [endpoints, setEndpoints] = useState([]);
  const [defaults, setDefaults] = useState(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [trunkForm, setTrunkForm] = useState({
    name: '',
    type: 'CARRIER',
    cliPrefixes: '',
  });

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [h, t, e, d] = await Promise.all([
        apiJson('/api/v2/telephony/asterisk/health', {
          skipErrorToast: true,
          timeoutMs: 6_000,
        }).catch(() => ({
          realtimeReachable: false,
        })),
        apiJson('/api/v2/telephony/trunks', { timeoutMs: 8_000 }),
        apiJson('/api/v2/telephony/endpoints', { timeoutMs: 6_000 }),
        apiJson('/api/v2/telephony/softphone-defaults', {
          skipErrorToast: true,
          timeoutMs: 4_000,
        }).catch(() => ({})),
      ]);
      setHealth(h);
      setTrunks(Array.isArray(t) ? t : []);
      setEndpoints(Array.isArray(e) ? e : []);
      setDefaults(d);
    } catch (err) {
      push(err instanceof Error ? err.message : 'Failed to load telephony settings');
    } finally {
      setLoading(false);
    }
  }, [push]);

  useEffect(() => {
    if (hasPermission('settings:read') || hasPermission('directory:read')) load();
  }, [hasPermission, load]);

  if (!hasPermission('settings:read') && !hasPermission('directory:read')) {
    return <Navigate to="/app" replace />;
  }

  async function createTrunk() {
    if (!trunkForm.name.trim()) {
      push('Trunk name required');
      return;
    }
    setBusy(true);
    try {
      const prefixes = trunkForm.cliPrefixes
        .split(/[,\s]+/)
        .map((s) => s.trim())
        .filter(Boolean);
      await apiJson('/api/v2/telephony/trunks', {
        method: 'POST',
        body: JSON.stringify({
          name: trunkForm.name.trim(),
          type: trunkForm.type,
          cliPrefixes: prefixes,
        }),
      });
      push('Trunk created');
      setTrunkForm({ name: '', type: 'CARRIER', cliPrefixes: '' });
      await load();
    } catch (err) {
      push(err instanceof Error ? err.message : 'Create trunk failed');
    } finally {
      setBusy(false);
    }
  }

  async function createLabAttacker() {
    setBusy(true);
    try {
      const row = await apiJson('/api/v2/telephony/endpoints/lab-attacker', {
        method: 'POST',
        body: JSON.stringify({ label: 'attacker' }),
      });
      push(
        `Lab attacker ${row.username} / ${row.plaintextPassword} — copy now; password is shown once.`,
      );
      await load();
    } catch (err) {
      push(err instanceof Error ? err.message : 'Lab attacker create failed');
    } finally {
      setBusy(false);
    }
  }

  const registered = endpoints.filter((e) => e.registered).length;

  return (
    <div className="mx-auto flex max-w-4xl flex-col gap-8 p-6">
      <div>
        <p className="text-xs text-sv-muted">
          <Link to="/app/settings" className="text-sv-accent hover:underline">
            Settings
          </Link>
          {' / '}
          Telephony
        </p>
        <h1 className="mt-1 text-xl font-semibold text-sv-fg">Telephony</h1>
        <p className="mt-1 text-sm text-sv-muted">
          Trunks, SIP numbering, and Asterisk PJSIP REALTIME health. Softphones register to the
          LAN IP in <code className="font-mono text-xs">SIP_EXTERNAL_IP</code>.
        </p>
      </div>

      {loading ? (
        <p className="text-sm text-sv-muted">Loading…</p>
      ) : (
        <>
          {(defaults?.labMode || health?.labMode) && (
            <div
              role="status"
              className="rounded border border-amber-500/40 bg-amber-500/10 px-3 py-2 text-sm text-amber-100"
            >
              <strong>LAB_MODE</strong> is on — CLI-spoofable attack-simulator endpoints may be
              created. Refused when the Decision Plane runs with the <code>prod</code> profile.
            </div>
          )}

          <section className="rounded-lg border border-sv-border bg-sv-panel/40 p-4">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <h2 className="text-sm font-semibold text-sv-fg">Asterisk connection</h2>
              <div className="flex gap-2">
                <Button
                  className="px-2 py-1 text-xs"
                  variant="ghost"
                  disabled={!health}
                  onClick={async () => {
                    try {
                      const text =
                        health?.summary ||
                        JSON.stringify(health, null, 2);
                      await navigator.clipboard.writeText(String(text));
                      push('Diagnostics copied');
                    } catch {
                      push('Copy failed');
                    }
                  }}
                >
                  Copy diagnostics
                </Button>
                <Button className="px-2 py-1 text-xs" variant="ghost" onClick={load} disabled={busy}>
                  Refresh
                </Button>
              </div>
            </div>

            <div className="mt-3 flex flex-wrap items-center gap-2 text-sm">
              <Badge tone={health?.ok ? 'success' : 'danger'}>
                {health?.ok ? 'all checks ok' : 'issues found'}
              </Badge>
              <span className="font-mono text-xs text-sv-muted">{health?.mode || 'PJSIP_REALTIME'}</span>
            </div>

            <ul className="mt-4 space-y-3">
              {(Array.isArray(health?.checks) ? health.checks : []).map((chk) => (
                <li
                  key={chk.id}
                  className={`rounded border px-3 py-2 text-sm ${
                    chk.ok ? 'border-sv-border' : 'border-amber-500/40 bg-amber-500/5'
                  }`}
                >
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge tone={chk.ok ? 'success' : 'danger'}>{chk.ok ? 'ok' : 'fail'}</Badge>
                    <span className="font-medium text-sv-fg">{chk.label}</span>
                  </div>
                  {chk.detail ? (
                    <p className="mt-1 text-xs text-sv-muted">{chk.detail}</p>
                  ) : null}
                  {chk.error ? (
                    <p className="mt-1 font-mono text-[11px] text-amber-100">{chk.error}</p>
                  ) : null}
                  {chk.fixHint ? (
                    <p className="mt-1 text-xs text-sv-muted">
                      <span className="font-semibold text-sv-fg">Fix: </span>
                      {chk.fixHint}
                    </p>
                  ) : null}
                  {chk.tables ? (
                    <p className="mt-1 font-mono text-[10px] text-sv-muted">
                      tables: {(chk.tables || []).join(', ')}
                      {chk.probe ? ` · probe=${chk.probe}` : ''}
                    </p>
                  ) : null}
                  {chk.baseUrl ? (
                    <p className="mt-1 font-mono text-[10px] text-sv-muted">
                      {chk.baseUrl}
                      {chk.usernameConfigured != null
                        ? ` · user=${chk.usernameConfigured ? 'set' : 'missing'} · pass=${
                            chk.passwordConfigured ? 'set' : 'missing'
                          }`
                        : ''}
                    </p>
                  ) : null}
                  {chk.value ? (
                    <p className="mt-1 font-mono text-[11px] text-sv-fg">{chk.value}</p>
                  ) : null}
                </li>
              ))}
              {!health?.checks?.length ? (
                <li className="text-sm text-sv-muted">
                  Health payload missing checks — refresh after restarting the Decision Plane.
                  PJSIP:{' '}
                  <Badge tone={health?.realtimeReachable ? 'success' : 'danger'}>
                    {health?.realtimeReachable ? 'reachable' : 'unreachable'}
                  </Badge>
                  {' · '}
                  SIP_EXTERNAL_IP:{' '}
                  <span className="font-mono text-xs">
                    {health?.sipExternalIp || 'not set'}
                  </span>
                </li>
              ) : null}
            </ul>

            <dl className="mt-4 grid gap-3 border-t border-sv-border pt-3 text-sm sm:grid-cols-2">
              <div>
                <dt className="text-sv-muted">Endpoints (this tenant)</dt>
                <dd className="mt-0.5">
                  {endpoints.length} total · {registered} registered (last 2 min)
                </dd>
              </div>
              <div>
                <dt className="text-sv-muted">Active call sessions</dt>
                <dd className="mt-0.5">{health?.activeChannels ?? 0}</dd>
              </div>
            </dl>
            <p className="mt-3 text-[11px] text-sv-muted">
              Detect LAN IP: <code className="font-mono">.\scripts\detect-lan-ip.ps1</code> — set
              in <code className="font-mono">.env</code> then recreate Asterisk. Verify:{' '}
              <code className="font-mono">docker compose exec asterisk asterisk -rx &quot;pjsip show endpoints&quot;</code>
            </p>
          </section>

          <section className="space-y-3">
            <h2 className="text-sm font-semibold uppercase tracking-wide text-sv-muted">Trunks</h2>
            <p className="text-xs text-sv-muted">
              CLI prefixes drive number provenance (KNOWN / SUSPECT_TRUNK). INTERNAL is seeded per
              tenant.
            </p>
            {trunks.length === 0 ? (
              <p className="text-sm text-sv-muted">No trunks yet.</p>
            ) : (
              <Table
                columns={[
                  { key: 'name', header: 'Name' },
                  {
                    key: 'type',
                    header: 'Type',
                    render: (r) => <Badge tone="neutral">{r.type}</Badge>,
                  },
                  {
                    key: 'cliPrefixes',
                    header: 'CLI prefixes',
                    render: (r) => (
                      <span className="font-mono text-[11px]">
                        {(r.cliPrefixes || []).join(', ') || '—'}
                      </span>
                    ),
                  },
                ]}
                rows={trunks}
              />
            )}
            {canWrite && (
              <div className="flex flex-wrap items-end gap-2 rounded border border-sv-border p-3">
                <Input
                  label="Name"
                  className="w-40"
                  value={trunkForm.name}
                  onChange={(e) => setTrunkForm((f) => ({ ...f, name: e.target.value }))}
                />
                <Select
                  label="Type"
                  className="w-36"
                  value={trunkForm.type}
                  onChange={(e) => setTrunkForm((f) => ({ ...f, type: e.target.value }))}
                  options={TRUNK_TYPES.map((t) => ({ value: t, label: t }))}
                />
                <Input
                  label="CLI prefixes (comma-separated)"
                  className="min-w-[200px] flex-1"
                  value={trunkForm.cliPrefixes}
                  onChange={(e) => setTrunkForm((f) => ({ ...f, cliPrefixes: e.target.value }))}
                  placeholder="+9122, +9180"
                />
                <Button disabled={busy} onClick={createTrunk}>
                  Add trunk
                </Button>
              </div>
            )}
          </section>

          <section className="space-y-3">
            <h2 className="text-sm font-semibold uppercase tracking-wide text-sv-muted">
              Numbering plan
            </h2>
            <p className="text-xs text-sv-muted">
              Extensions are <code className="font-mono">telephony_ordinal × 1000 + nnn</code>{' '}
              (tenant ordinal 1 → 1001…). Create accounts from Directory → employee → Telephony.
            </p>
            <div className="overflow-x-auto">
              {endpoints.length === 0 ? (
                <p className="text-sm text-sv-muted">No SIP endpoints provisioned yet.</p>
              ) : (
                <Table
                  columns={[
                    { key: 'extension', header: 'Ext' },
                    { key: 'username', header: 'Username' },
                    {
                      key: 'status',
                      header: 'Status',
                      render: (r) => (
                        <Badge tone={r.status === 'ACTIVE' ? 'success' : 'neutral'}>
                          {r.status}
                        </Badge>
                      ),
                    },
                    {
                      key: 'registered',
                      header: 'Reg',
                      render: (r) => (
                        <span
                          className={`inline-block h-2.5 w-2.5 rounded-full ${
                            r.registered ? 'bg-emerald-400' : 'bg-sv-border'
                          }`}
                          title={r.registered ? 'Registered' : 'Not registered'}
                        />
                      ),
                    },
                    { key: 'employeeId', header: 'Employee', render: (r) => (
                      <span className="font-mono text-[10px]">
                        {r.employeeId ? String(r.employeeId).slice(0, 8) : '—'}
                      </span>
                    ) },
                  ]}
                  rows={endpoints}
                />
              )}
            </div>
            {canWrite && (defaults?.labMode || health?.labMode) && (
              <Button variant="secondary" disabled={busy} onClick={createLabAttacker}>
                Create lab attack-simulator endpoint
              </Button>
            )}
          </section>
        </>
      )}
    </div>
  );
}

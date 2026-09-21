import { useCallback, useEffect, useState } from 'react';
import { Link, Navigate } from 'react-router-dom';
import { useAuth } from '@/context/AuthContext.jsx';
import { apiJson } from '@/services/api.js';
import { Badge, Button } from '@/ui';
import { Toggle } from '@/components/ui/Toggle.jsx';
import { useToast } from '@/ui/Toast.jsx';

/**
 * F5 — Tenant settings with AI & Privacy panel.
 */
export function SettingsPage() {
  const { hasPermission } = useAuth();
  const { push } = useToast();
  const [settings, setSettings] = useState(null);
  const [health, setHealth] = useState(null);
  const [selftest, setSelftest] = useState(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);

  const canWrite = hasPermission('settings:write');

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [s, h] = await Promise.all([
        apiJson('/api/v2/settings'),
        apiJson('/api/v2/settings/llm-health', { skipErrorToast: true }).catch(() => ({
          ok: false,
          gateway: 'down',
          activeProvider: 'mock',
          degraded: true,
          ollama: { reachable: false, modelPresent: false, error: 'request_failed' },
          openaiCompat: { enabled: false, reachable: null },
        })),
      ]);
      setSettings(s);
      setHealth(h);
    } catch (err) {
      push(err.message || 'Failed to load settings');
    } finally {
      setLoading(false);
    }
  }, [push]);

  useEffect(() => {
    if (hasPermission('settings:read')) load();
  }, [hasPermission, load]);

  if (!hasPermission('settings:read')) {
    return <Navigate to="/app" replace />;
  }

  async function setAllowExternal(next) {
    if (!canWrite || !settings) return;
    setSaving(true);
    try {
      const updated = await apiJson('/api/v2/settings', {
        method: 'PATCH',
        body: JSON.stringify({ allowExternalLlm: next }),
      });
      setSettings(updated);
      push(next ? 'External LLM enabled for policy compile' : 'External LLM disabled');
    } catch (err) {
      push(err.message || 'Update failed');
    } finally {
      setSaving(false);
    }
  }

  async function runSelftest() {
    if (!canWrite) return;
    setTesting(true);
    setSelftest(null);
    try {
      const result = await apiJson('/api/v2/settings/llm-selftest', {
        method: 'POST',
        skipErrorToast: true,
      });
      setSelftest(result);
      if (result.schemaValid) {
        push(`Self-test OK · ${result.model} · ${Math.round(result.latencyMs || 0)} ms`);
      } else {
        push(result.error || 'Self-test failed schema validation');
      }
      await load();
    } catch (err) {
      setSelftest({ schemaValid: false, error: err.message || 'selftest failed' });
      push(err.message || 'Self-test failed');
    } finally {
      setTesting(false);
    }
  }

  const activeProvider = health?.activeProvider || health?.provider || '—';
  const gatewayStatus = health?.gateway || (health?.ok === false ? 'down' : '—');
  const degraded = Boolean(health?.degraded);
  const ollama = health?.ollama || {};
  const openaiCompat = health?.openaiCompat || {};
  const warmup = health?.warmup || {};
  const model =
    activeProvider === 'ollama'
      ? health?.ollamaModel || health?.model || '—'
      : activeProvider === 'openai_compat'
        ? health?.model || '—'
        : 'mock';
  const latencyParts = [];
  if (health?.p50LatencyMs != null) latencyParts.push(`p50 ${Math.round(health.p50LatencyMs)} ms`);
  if (health?.p95LatencyMs != null) latencyParts.push(`p95 ${Math.round(health.p95LatencyMs)} ms`);
  if (health?.tokensPerSecondP50 != null) {
    latencyParts.push(`~${Math.round(health.tokensPerSecondP50)} tok/s`);
  }
  const latency = latencyParts.length > 0 ? latencyParts.join(' · ') : 'no samples yet';

  let ollamaWarning = 'Using MOCK provider (Ollama unreachable)';
  if (degraded && ollama.reachable && !ollama.modelPresent) {
    ollamaWarning = 'Using MOCK provider (Ollama model not pulled)';
  }

  return (
    <div className="mx-auto flex max-w-2xl flex-col gap-8 p-6">
      <div>
        <h1 className="text-xl font-semibold text-sv-fg">Settings</h1>
        <p className="mt-1 text-sm text-sv-muted">Tenant configuration for privacy and AI usage.</p>
      </div>

      <Link
        to="/app/settings/risk-tuning"
        className="block rounded border border-sv-accent/40 bg-sv-accent/10 p-4 transition-colors hover:border-sv-accent hover:bg-sv-accent/15"
      >
        <p className="text-sm font-semibold text-sv-fg">Risk tuning</p>
        <p className="mt-1 text-xs text-sv-muted">
          Fusion weights, level hysteresis, corroboration, and what-if replay. Dual-control
          drafts require POLICY_APPROVER before going live.
        </p>
        <span className="mt-2 inline-block text-xs font-medium text-sv-accent">Open risk tuning →</span>
      </Link>

      <Link
        to="/app/settings/telephony"
        className="block rounded border border-sv-border bg-sv-elevated/40 p-4 transition-colors hover:border-sv-accent/50 hover:bg-sv-elevated/60"
      >
        <p className="text-sm font-semibold text-sv-fg">Telephony</p>
        <p className="mt-1 text-xs text-sv-muted">
          SIP trunks, numbering plan, Asterisk PJSIP REALTIME health, and LAB_MODE attack
          simulator. Softphones register to SIP_EXTERNAL_IP.
        </p>
        <span className="mt-2 inline-block text-xs font-medium text-sv-accent">Open telephony →</span>
      </Link>

      <section className="space-y-4">
        <h2 className="text-sm font-semibold uppercase tracking-wide text-sv-muted">AI &amp; Privacy</h2>

        {loading || !settings ? (
          <p className="text-sm text-sv-muted">Loading…</p>
        ) : (
          <>
            <div className="rounded border border-sv-border bg-sv-elevated/40 p-4">
              <div className="flex items-start justify-between gap-4">
                <div>
                  <p className="text-sm font-medium text-sv-fg">Allow external LLM for policy documents</p>
                  <p className="mt-1 text-xs text-sv-muted">
                    Off by default. When on, policy compile may call a hosted OpenAI-compatible endpoint
                    configured on the server. Runtime call-intent tasks never use an external LLM.
                    Document text is treated as untrusted data and wrapped so model instructions inside
                    uploads are ignored.
                  </p>
                </div>
                <Toggle
                  id="allow-external-llm"
                  checked={Boolean(settings.allowExternalLlm)}
                  disabled={!canWrite || saving}
                  onChange={setAllowExternal}
                  label={settings.allowExternalLlm ? 'On' : 'Off'}
                />
              </div>
              {settings.allowExternalLlm ? (
                <p role="alert" className="mt-3 text-xs text-risk-watch">
                  Warning: enabling this may send policy text to a third-party model host. Prefer local
                  Ollama unless your security team has approved the external provider.
                </p>
              ) : null}
            </div>

            <div className="rounded border border-sv-border bg-sv-elevated/40 p-4">
              <div className="flex items-center justify-between gap-3">
                <p className="text-sm font-medium text-sv-fg">LLM gateway health</p>
                <div className="flex gap-2">
                  {canWrite ? (
                    <Button
                      className="px-2 py-1 text-xs"
                      variant="ghost"
                      disabled={testing}
                      onClick={runSelftest}
                    >
                      {testing ? 'Testing…' : 'Run test'}
                    </Button>
                  ) : null}
                  <Button className="px-2 py-1 text-xs" variant="ghost" onClick={load}>
                    Refresh
                  </Button>
                </div>
              </div>
              {degraded ? (
                <p role="alert" className="mt-3 text-xs text-risk-watch">
                  {ollamaWarning}
                </p>
              ) : null}
              <dl className="mt-3 grid grid-cols-2 gap-2 text-sm">
                <dt className="text-sv-muted">Gateway</dt>
                <dd className="flex items-center gap-2 text-sv-fg">
                  {gatewayStatus}
                  {gatewayStatus === 'up' ? <Badge tone="success">up</Badge> : null}
                  {gatewayStatus === 'down' ? <Badge tone="danger">down</Badge> : null}
                </dd>
                <dt className="text-sv-muted">Active provider</dt>
                <dd className="flex items-center gap-2 text-sv-fg">
                  {activeProvider}
                  {activeProvider === 'mock' ? <Badge tone="warn">demo mock</Badge> : null}
                  {activeProvider === 'ollama' ? <Badge tone="success">ollama</Badge> : null}
                  {activeProvider === 'openai_compat' ? <Badge tone="success">openai</Badge> : null}
                </dd>
                <dt className="text-sv-muted">Ollama</dt>
                <dd className="text-sv-fg">
                  {ollama.reachable
                    ? ollama.modelPresent
                      ? 'reachable · model present'
                      : 'reachable · model missing'
                    : `unreachable${ollama.error ? ` (${ollama.error})` : ''}`}
                </dd>
                <dt className="text-sv-muted">OpenAI-compat</dt>
                <dd className="text-sv-fg">
                  {!openaiCompat.enabled
                    ? 'disabled'
                    : openaiCompat.reachable
                      ? 'enabled · reachable'
                      : 'enabled · unreachable'}
                </dd>
                <dt className="text-sv-muted">Model</dt>
                <dd className="text-sv-fg">{model}</dd>
                <dt className="text-sv-muted">Warm-up</dt>
                <dd className="text-sv-fg">
                  {warmup.status || '—'}
                  {warmup.latencyMs != null ? ` · ${Math.round(warmup.latencyMs)} ms` : ''}
                  {warmup.error ? ` (${warmup.error})` : ''}
                </dd>
                <dt className="text-sv-muted">Latency</dt>
                <dd className="text-sv-fg">{latency}</dd>
              </dl>
              {selftest ? (
                <div className="mt-3 rounded border border-sv-border bg-sv-bg/40 px-3 py-2 text-xs">
                  <p className="font-medium text-sv-fg">Self-test result</p>
                  <p className="mt-1 text-sv-muted">
                    {selftest.provider}/{selftest.model} ·{' '}
                    {selftest.schemaValid ? 'schema valid' : 'schema invalid'} ·{' '}
                    {selftest.latencyMs != null ? `${Math.round(selftest.latencyMs)} ms` : '—'}
                    {selftest.tokensPerSecond != null
                      ? ` · ${Math.round(selftest.tokensPerSecond)} tok/s`
                      : ''}
                    {selftest.error ? ` · ${selftest.error}` : ''}
                  </p>
                </div>
              ) : null}
            </div>
          </>
        )}
      </section>
    </div>
  );
}

import { useCallback, useEffect, useState } from 'react';
import { Navigate } from 'react-router-dom';
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
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  const canWrite = hasPermission('settings:write');

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [s, h] = await Promise.all([
        apiJson('/api/v2/settings'),
        apiJson('/api/v2/settings/llm-health', { skipErrorToast: true }).catch(() => ({
          ok: false,
          provider: 'unreachable',
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

  const provider = health?.provider || (health?.ok === false ? 'unreachable' : '—');
  const model = health?.model || health?.ollamaModel || '—';
  const latency =
    health?.p50LatencyMs != null
      ? `p50 ${Math.round(health.p50LatencyMs)} ms`
      : health?.reachable === false
        ? 'unreachable'
        : 'no samples yet';

  return (
    <div className="mx-auto flex max-w-2xl flex-col gap-8 p-6">
      <div>
        <h1 className="text-xl font-semibold text-sv-fg">Settings</h1>
        <p className="mt-1 text-sm text-sv-muted">Tenant configuration for privacy and AI usage.</p>
      </div>

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
                <Button className="px-2 py-1 text-xs" variant="ghost" onClick={load}>
                  Refresh
                </Button>
              </div>
              <dl className="mt-3 grid grid-cols-2 gap-2 text-sm">
                <dt className="text-sv-muted">Provider</dt>
                <dd className="flex items-center gap-2 text-sv-fg">
                  {provider}
                  {provider === 'mock' ? <Badge tone="warn">demo mock</Badge> : null}
                  {health?.ok === false ? <Badge tone="danger">down</Badge> : null}
                  {health?.ok && provider !== 'mock' && provider !== 'unreachable' ? (
                    <Badge tone="success">up</Badge>
                  ) : null}
                </dd>
                <dt className="text-sv-muted">Model</dt>
                <dd className="text-sv-fg">{model}</dd>
                <dt className="text-sv-muted">Latency</dt>
                <dd className="text-sv-fg">{latency}</dd>
              </dl>
            </div>
          </>
        )}
      </section>
    </div>
  );
}

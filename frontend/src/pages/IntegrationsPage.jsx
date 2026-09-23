/**
 * F17 — Integrations settings: API keys, webhooks, quickstart, try-it.
 */
import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, Navigate } from 'react-router-dom';
import { useAuth } from '@/context/AuthContext.jsx';
import { apiJson } from '@/services/api.js';
import { Badge, Button } from '@/ui';
import { useToast } from '@/ui/Toast.jsx';

const SCOPES = [
  'transactions:write',
  'events:write',
  'sessions:read',
  'risk:read',
  'directory:sync',
  'webhooks:manage',
];

const WEBHOOK_EVENTS = [
  'risk.level_changed',
  'action.executed',
  'action.failed',
  'session.ended',
  'config.approved',
];

function TabButton({ id, active, onClick, children }) {
  return (
    <button
      type="button"
      onClick={() => onClick(id)}
      className={`rounded px-3 py-1.5 text-sm ${
        active ? 'bg-sv-accent/20 font-medium text-sv-accent' : 'text-sv-muted hover:text-sv-fg'
      }`}
    >
      {children}
    </button>
  );
}

export function IntegrationsPage() {
  const { hasPermission } = useAuth();
  const { push } = useToast();
  const canWrite = hasPermission('integrations:write') || hasPermission('settings:write');
  const canRead = hasPermission('integrations:read') || hasPermission('settings:read');

  const [tab, setTab] = useState('keys');
  const [keys, setKeys] = useState([]);
  const [webhooks, setWebhooks] = useState([]);
  const [deliveries, setDeliveries] = useState([]);
  const [loading, setLoading] = useState(true);
  const [secretOnce, setSecretOnce] = useState(null);
  const [webhookSecretOnce, setWebhookSecretOnce] = useState(null);

  const [keyName, setKeyName] = useState('');
  const [keyScopes, setKeyScopes] = useState(() => new Set(['transactions:write', 'sessions:read', 'risk:read']));
  const [whUrl, setWhUrl] = useState('');
  const [whEvents, setWhEvents] = useState(() => new Set(['risk.level_changed']));

  const [tryBody, setTryBody] = useState(
    JSON.stringify(
      {
        actionType: 'SENSITIVE_ACTION',
        channel: 'CORE_SYSTEM',
        sessionId: '',
      },
      null,
      2,
    ),
  );
  const [tryResult, setTryResult] = useState(null);
  const [tryBusy, setTryBusy] = useState(false);

  const baseUrl = useMemo(() => {
    if (typeof window !== 'undefined') return window.location.origin.replace(':5173', ':8081');
    return 'http://127.0.0.1:8081';
  }, []);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [k, w, d] = await Promise.all([
        apiJson('/api/v2/integrations/api-keys'),
        apiJson('/api/v2/integrations/webhooks'),
        apiJson('/api/v2/integrations/webhooks/deliveries?limit=40', { skipErrorToast: true }).catch(() => ({
          items: [],
        })),
      ]);
      setKeys(k.items || []);
      setWebhooks(w.items || []);
      setDeliveries(d.items || []);
    } catch (err) {
      push(err.message || 'Failed to load integrations');
    } finally {
      setLoading(false);
    }
  }, [push]);

  useEffect(() => {
    if (canRead) void load();
  }, [canRead, load]);

  if (!canRead) {
    return <Navigate to="/app/settings" replace />;
  }

  async function createKey() {
    if (!canWrite || !keyName.trim()) return;
    try {
      const created = await apiJson('/api/v2/integrations/api-keys', {
        method: 'POST',
        body: JSON.stringify({ name: keyName.trim(), scopes: [...keyScopes] }),
      });
      setSecretOnce(created);
      setKeyName('');
      push('API key created — copy the secret now');
      void load();
    } catch (err) {
      push(err.message || 'Create failed');
    }
  }

  async function revokeKey(id) {
    if (!canWrite) return;
    try {
      await apiJson(`/api/v2/integrations/api-keys/${id}`, { method: 'DELETE' });
      push('Key revoked');
      void load();
    } catch (err) {
      push(err.message || 'Revoke failed');
    }
  }

  async function createWebhook() {
    if (!canWrite || !whUrl.trim()) return;
    try {
      const created = await apiJson('/api/v2/integrations/webhooks', {
        method: 'POST',
        body: JSON.stringify({ url: whUrl.trim(), events: [...whEvents] }),
      });
      setWebhookSecretOnce(created);
      setWhUrl('');
      push('Webhook created — copy signing secret');
      void load();
    } catch (err) {
      push(err.message || 'Create failed');
    }
  }

  async function testWebhook(id) {
    try {
      await apiJson(`/api/v2/integrations/webhooks/${id}/test`, { method: 'POST' });
      push('Test event enqueued');
      void load();
    } catch (err) {
      push(err.message || 'Test failed');
    }
  }

  async function disableWebhook(id) {
    if (!canWrite) return;
    try {
      await apiJson(`/api/v2/integrations/webhooks/${id}`, { method: 'DELETE' });
      push('Webhook disabled');
      void load();
    } catch (err) {
      push(err.message || 'Disable failed');
    }
  }

  async function runTryIt() {
    setTryBusy(true);
    setTryResult(null);
    try {
      const body = JSON.parse(tryBody);
      const result = await apiJson('/api/v2/integrations/transactions/request', {
        method: 'POST',
        body: JSON.stringify(body),
      });
      setTryResult(result);
    } catch (err) {
      setTryResult({ error: err.message || String(err) });
    } finally {
      setTryBusy(false);
    }
  }

  const curlSnippet = `curl -sS -X POST '${baseUrl}/api/v2/integrations/transactions/request' \\
  -H 'Authorization: Bearer sv_live_YOUR_SECRET' \\
  -H 'Content-Type: application/json' \\
  -d '{"actionType":"SENSITIVE_ACTION","channel":"CORE_SYSTEM"}'`;

  const pySnippet = `from sentinelvoice import SentinelVoiceClient

client = SentinelVoiceClient(base_url="${baseUrl}", api_key="sv_live_YOUR_SECRET")
result = client.create_transaction_request(
    action_type="SENSITIVE_ACTION",
    channel="CORE_SYSTEM",
)
print(result["decision"], result["level"])`;

  const jsSnippet = `import { SentinelVoiceClient } from '@sentinelvoice/sdk';

const client = new SentinelVoiceClient({
  baseUrl: '${baseUrl}',
  apiKey: 'sv_live_YOUR_SECRET',
});
const result = await client.createTransactionRequest({
  actionType: 'SENSITIVE_ACTION',
  channel: 'CORE_SYSTEM',
});
console.log(result.decision, result.level);`;

  return (
    <div className="mx-auto flex max-w-4xl flex-col gap-6 p-6">
      <div>
        <Link to="/app/settings" className="text-xs text-sv-accent hover:underline">
          ← Settings
        </Link>
        <h1 className="mt-1 font-display text-2xl text-sv-fg">Integrations</h1>
        <p className="text-sm text-sv-muted">
          API keys, webhooks, and quickstart for core-system callers (F17).{' '}
          <a className="text-sv-accent hover:underline" href="/api/v2/docs" target="_blank" rel="noreferrer">
            OpenAPI docs →
          </a>
        </p>
      </div>

      <div className="flex flex-wrap gap-1 border-b border-sv-border pb-2">
        <TabButton id="keys" active={tab === 'keys'} onClick={setTab}>
          API keys
        </TabButton>
        <TabButton id="webhooks" active={tab === 'webhooks'} onClick={setTab}>
          Webhooks
        </TabButton>
        <TabButton id="quickstart" active={tab === 'quickstart'} onClick={setTab}>
          Quickstart
        </TabButton>
        <TabButton id="try" active={tab === 'try'} onClick={setTab}>
          Try it
        </TabButton>
      </div>

      {loading ? <p className="text-sm text-sv-muted">Loading…</p> : null}

      {tab === 'keys' ? (
        <section className="space-y-4">
          {secretOnce?.secret ? (
            <div className="rounded border border-amber-600/50 bg-amber-950/30 p-3 text-sm">
              <p className="font-semibold text-amber-100">Copy secret now — shown once</p>
              <code className="mt-2 block break-all font-mono text-xs text-sv-fg">{secretOnce.secret}</code>
              <Button
                className="mt-2"
                variant="secondary"
                onClick={() => {
                  void navigator.clipboard.writeText(secretOnce.secret);
                  push('Copied');
                }}
              >
                Copy
              </Button>
              <Button className="ml-2 mt-2" variant="ghost" onClick={() => setSecretOnce(null)}>
                Dismiss
              </Button>
            </div>
          ) : null}

          {canWrite ? (
            <div className="rounded border border-sv-border p-3">
              <h2 className="mb-2 text-sm font-semibold">Create key</h2>
              <input
                className="mb-2 w-full rounded border border-sv-border bg-sv-bg px-2 py-1 text-sm"
                placeholder="Name (e.g. core-system-prod)"
                value={keyName}
                onChange={(e) => setKeyName(e.target.value)}
              />
              <div className="mb-3 flex flex-wrap gap-2">
                {SCOPES.map((s) => (
                  <label key={s} className="flex items-center gap-1 text-xs">
                    <input
                      type="checkbox"
                      checked={keyScopes.has(s)}
                      onChange={(e) => {
                        setKeyScopes((prev) => {
                          const next = new Set(prev);
                          if (e.target.checked) next.add(s);
                          else next.delete(s);
                          return next;
                        });
                      }}
                    />
                    {s}
                  </label>
                ))}
              </div>
              <Button disabled={!keyName.trim() || keyScopes.size === 0} onClick={() => void createKey()}>
                Create
              </Button>
            </div>
          ) : null}

          <ul className="space-y-2">
            {keys.map((k) => (
              <li key={k.id} className="flex flex-wrap items-center justify-between gap-2 rounded border border-sv-border px-3 py-2 text-sm">
                <div>
                  <p className="font-medium">{k.name}</p>
                  <p className="font-mono text-[11px] text-sv-muted">
                    {k.prefix}… · {(k.scopes || []).join(', ')} · uses {k.usageCount ?? 0}
                  </p>
                </div>
                <div className="flex items-center gap-2">
                  <Badge tone={k.status === 'ACTIVE' ? 'success' : 'warn'}>{k.status}</Badge>
                  {canWrite && k.status === 'ACTIVE' ? (
                    <Button variant="ghost" className="!text-xs" onClick={() => void revokeKey(k.id)}>
                      Revoke
                    </Button>
                  ) : null}
                </div>
              </li>
            ))}
            {!keys.length ? <p className="text-xs text-sv-muted">No API keys yet.</p> : null}
          </ul>
        </section>
      ) : null}

      {tab === 'webhooks' ? (
        <section className="space-y-4">
          {webhookSecretOnce?.secret ? (
            <div className="rounded border border-amber-600/50 bg-amber-950/30 p-3 text-sm">
              <p className="font-semibold text-amber-100">Signing secret (once)</p>
              <code className="mt-2 block break-all font-mono text-xs">{webhookSecretOnce.secret}</code>
              <Button
                className="mt-2"
                variant="secondary"
                onClick={() => {
                  void navigator.clipboard.writeText(webhookSecretOnce.secret);
                  push('Copied');
                }}
              >
                Copy
              </Button>
              <Button className="ml-2 mt-2" variant="ghost" onClick={() => setWebhookSecretOnce(null)}>
                Dismiss
              </Button>
            </div>
          ) : null}

          {canWrite ? (
            <div className="rounded border border-sv-border p-3">
              <h2 className="mb-2 text-sm font-semibold">Add endpoint</h2>
              <input
                className="mb-2 w-full rounded border border-sv-border bg-sv-bg px-2 py-1 text-sm"
                placeholder="https://example.com/hooks/sentinelvoice"
                value={whUrl}
                onChange={(e) => setWhUrl(e.target.value)}
              />
              <div className="mb-3 flex flex-wrap gap-2">
                {WEBHOOK_EVENTS.map((e) => (
                  <label key={e} className="flex items-center gap-1 text-xs">
                    <input
                      type="checkbox"
                      checked={whEvents.has(e)}
                      onChange={(ev) => {
                        setWhEvents((prev) => {
                          const next = new Set(prev);
                          if (ev.target.checked) next.add(e);
                          else next.delete(e);
                          return next;
                        });
                      }}
                    />
                    {e}
                  </label>
                ))}
              </div>
              <Button disabled={!whUrl.trim() || whEvents.size === 0} onClick={() => void createWebhook()}>
                Create
              </Button>
            </div>
          ) : null}

          <ul className="space-y-2">
            {webhooks.map((w) => (
              <li key={w.id} className="rounded border border-sv-border px-3 py-2 text-sm">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <div>
                    <p className="font-mono text-xs break-all">{w.url}</p>
                    <p className="text-[11px] text-sv-muted">{(w.events || []).join(', ')}</p>
                  </div>
                  <div className="flex gap-2">
                    <Badge>{w.status}</Badge>
                    <Button variant="secondary" className="!text-xs" onClick={() => void testWebhook(w.id)}>
                      Test
                    </Button>
                    {canWrite && w.status === 'ACTIVE' ? (
                      <Button variant="ghost" className="!text-xs" onClick={() => void disableWebhook(w.id)}>
                        Disable
                      </Button>
                    ) : null}
                  </div>
                </div>
              </li>
            ))}
          </ul>

          <div>
            <h2 className="mb-2 text-sm font-semibold">Delivery log</h2>
            <div className="overflow-x-auto rounded border border-sv-border">
              <table className="w-full text-left text-xs">
                <thead>
                  <tr className="border-b border-sv-border text-sv-muted">
                    <th className="px-2 py-1">When</th>
                    <th className="px-2 py-1">Event</th>
                    <th className="px-2 py-1">Status</th>
                    <th className="px-2 py-1">HTTP</th>
                    <th className="px-2 py-1">Attempts</th>
                  </tr>
                </thead>
                <tbody>
                  {deliveries.map((d) => (
                    <tr key={d.id} className="border-b border-sv-border/40">
                      <td className="px-2 py-1 font-mono text-[10px]">{d.createdAt}</td>
                      <td className="px-2 py-1">{d.eventType}</td>
                      <td className="px-2 py-1">{d.status}</td>
                      <td className="px-2 py-1">{d.lastStatusCode ?? '—'}</td>
                      <td className="px-2 py-1">{d.attemptCount}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {!deliveries.length ? <p className="p-3 text-xs text-sv-muted">No deliveries yet.</p> : null}
            </div>
          </div>
        </section>
      ) : null}

      {tab === 'quickstart' ? (
        <section className="space-y-4">
          <Snippet title="cURL" text={curlSnippet} onCopy={() => push('Copied curl')} />
          <Snippet title="Python" text={pySnippet} onCopy={() => push('Copied Python')} />
          <Snippet title="JavaScript / TypeScript" text={jsSnippet} onCopy={() => push('Copied JS')} />
          <p className="text-xs text-sv-muted">
            SDKs live in <code className="font-mono">/sdk/python</code> and <code className="font-mono">/sdk/js</code>.
            Replace <code className="font-mono">sv_live_YOUR_SECRET</code> with a key from the API keys tab.
          </p>
        </section>
      ) : null}

      {tab === 'try' ? (
        <section className="space-y-3">
          <p className="text-sm text-sv-muted">
            Calls <code className="font-mono">POST /transactions/request</code> with your session cookie (tenant admin).
          </p>
          <textarea
            className="min-h-[160px] w-full rounded border border-sv-border bg-sv-bg p-2 font-mono text-xs"
            value={tryBody}
            onChange={(e) => setTryBody(e.target.value)}
          />
          <Button disabled={tryBusy} onClick={() => void runTryIt()}>
            {tryBusy ? 'Running…' : 'Send request'}
          </Button>
          {tryResult ? (
            <pre className="overflow-auto rounded border border-sv-border bg-sv-bg p-3 font-mono text-xs">
              {JSON.stringify(tryResult, null, 2)}
            </pre>
          ) : null}
        </section>
      ) : null}
    </div>
  );
}

function Snippet({ title, text, onCopy }) {
  return (
    <div className="rounded border border-sv-border">
      <div className="flex items-center justify-between border-b border-sv-border px-3 py-1.5">
        <p className="text-xs font-semibold uppercase tracking-wide text-sv-muted">{title}</p>
        <Button
          variant="ghost"
          className="!text-xs"
          onClick={() => {
            void navigator.clipboard.writeText(text);
            onCopy?.();
          }}
        >
          Copy
        </Button>
      </div>
      <pre className="overflow-x-auto p-3 font-mono text-[11px] text-sv-fg/90">{text}</pre>
    </div>
  );
}

import { useCallback, useEffect, useState } from 'react';
import { Link, Navigate } from 'react-router-dom';
import { useAuth } from '@/context/AuthContext.jsx';
import { apiJson } from '@/services/api.js';
import { Badge, Button, EmptyState } from '@/ui';
import { useToast } from '@/ui/Toast.jsx';

/**
 * F18 — /app/lab scenario simulator. "Simulated" badge ONLY on this page.
 */
export function LabPage() {
  const { hasPermission } = useAuth();
  const { push } = useToast();
  const canRun = hasPermission('lab:robustness') || hasPermission('calls:act');
  const canRead = canRun || hasPermission('calls:read');

  const [items, setItems] = useState([]);
  const [selected, setSelected] = useState('');
  const [run, setRun] = useState(null);
  const [result, setResult] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  const load = useCallback(async () => {
    setError(null);
    try {
      const body = await apiJson('/api/v2/lab/scenarios', { skipErrorToast: true });
      setItems(Array.isArray(body?.items) ? body.items : []);
      if (!selected && body?.items?.[0]?.id) setSelected(body.items[0].id);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Lab unavailable (is LAB_MODE=true?)');
      setItems([]);
    }
  }, [selected]);

  useEffect(() => {
    if (canRead) void load();
  }, [canRead, load]);

  async function startRun() {
    if (!selected || !canRun) return;
    setBusy(true);
    setResult(null);
    try {
      const body = await apiJson(`/api/v2/lab/scenarios/${encodeURIComponent(selected)}/run`, {
        method: 'POST',
      });
      setRun(body);
      push(body.replayStarted ? 'Scenario replay started' : 'Run created (replay may need WAV)');
    } catch (err) {
      push(err instanceof Error ? err.message : 'Run failed');
    } finally {
      setBusy(false);
    }
  }

  async function refreshResult() {
    if (!run?.sessionId) return;
    try {
      const body = await apiJson(`/api/v2/lab/runs/${encodeURIComponent(run.sessionId)}`, {
        skipErrorToast: true,
      });
      setResult(body);
    } catch (err) {
      push(err instanceof Error ? err.message : 'Result fetch failed');
    }
  }

  if (!canRead) {
    return <Navigate to="/app" replace />;
  }

  const current = items.find((s) => s.id === selected);

  return (
    <div className="space-y-6 p-6">
      <header className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <div className="flex items-center gap-2">
            <h1 className="font-display text-2xl font-semibold text-sv-fg">Lab simulator</h1>
            <Badge tone="warn">SIMULATED</Badge>
          </div>
          <p className="mt-1 text-sm text-sv-muted">
            Scripted F18 scenarios drive real WAV audio into the pipeline. Expected-vs-actual when finished.
          </p>
        </div>
        <Button variant="secondary" onClick={() => void load()}>
          Refresh list
        </Button>
      </header>

      {error ? (
        <div className="rounded border border-amber-500/40 bg-amber-500/10 px-3 py-2 text-sm text-amber-100">
          {error}
        </div>
      ) : null}

      {items.length === 0 && !error ? (
        <EmptyState title="No scenarios" description="Seed classpath scenarios/*.json or restart backend." />
      ) : (
        <div className="grid gap-4 lg:grid-cols-2">
          <section className="rounded border border-sv-border bg-sv-panel p-4">
            <h2 className="text-sm font-semibold">Scenario</h2>
            <select
              className="mt-2 w-full rounded border border-sv-border bg-sv-bg px-2 py-2 text-sm"
              value={selected}
              onChange={(e) => setSelected(e.target.value)}
            >
              {items.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.title}
                </option>
              ))}
            </select>
            {current ? (
              <div className="mt-3 space-y-2 text-xs text-sv-muted">
                <p>{current.description}</p>
                {current.attackerPersona ? <p>Persona: {current.attackerPersona}</p> : null}
                {current.spoofedCli ? <p className="font-mono">Spoofed CLI: {current.spoofedCli}</p> : null}
                {current.spokenScript ? (
                  <p className="rounded border border-sv-border bg-sv-bg/40 p-2 text-sv-fg">
                    “{current.spokenScript}”
                  </p>
                ) : null}
                <p>
                  Expected final:{' '}
                  <span className="font-mono text-sv-fg">{current.expectedFinalLevel || '—'}</span>
                </p>
                <Button disabled={!canRun || busy || !selected} onClick={() => void startRun()}>
                  {busy ? 'Starting…' : 'Run'}
                </Button>
              </div>
            ) : null}
          </section>

          <section className="rounded border border-sv-border bg-sv-panel p-4">
            <h2 className="text-sm font-semibold">Run status</h2>
            {!run ? (
              <p className="mt-2 text-sm text-sv-muted">Pick a scenario and click Run.</p>
            ) : (
              <div className="mt-2 space-y-2 text-xs">
                <p className="font-mono text-sv-muted">session {run.sessionId}</p>
                <p>{run.note}</p>
                <div className="flex flex-wrap gap-2">
                  <Button variant="secondary" onClick={() => void refreshResult()}>
                    Compare expected vs actual
                  </Button>
                  <Link to="/app/live" className="text-sv-accent self-center hover:underline">
                    Open Live Calls →
                  </Link>
                  {run.sessionId ? (
                    <Link
                      to={`/app/sessions/${encodeURIComponent(run.sessionId)}`}
                      className="text-sv-accent self-center hover:underline"
                    >
                      Call Detail →
                    </Link>
                  ) : null}
                </div>
                {result ? (
                  <div className="mt-3 rounded border border-sv-border p-3">
                    <p>
                      Expected{' '}
                      <span className="font-mono">{result.expectedFinalLevel || '—'}</span> · Actual{' '}
                      <span className="font-mono">{result.actualPeakLevel || '—'}</span>{' '}
                      {result.match ? (
                        <Badge tone="success">match</Badge>
                      ) : (
                        <Badge tone="warn">differs</Badge>
                      )}
                    </p>
                    <p className="mt-2 text-sv-muted">
                      Actual ticks: {(result.actualTicks || []).length}
                    </p>
                  </div>
                ) : null}
              </div>
            )}
          </section>
        </div>
      )}
    </div>
  );
}

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  PerturbationControls,
  defaultPerturbationConfig,
} from '@/components/redteam/PerturbationControls.jsx';
import {
  RobustnessChart,
  exportChartPng,
} from '@/components/redteam/RobustnessChart.jsx';
import { RiskGauge } from '@/components/RiskGauge.jsx';
import { Card } from '@/components/ui/Card.jsx';
import { getFamilyScore } from '@/contracts';
import { useSession } from '@/context/SessionContext.jsx';
import { useTelemetrySocket } from '@/hooks/useTelemetrySocket.js';
import { EVIDENCE_FAMILIES } from '@/lib/evidenceMeta.js';

/**
 * Red Team Lab — defensive robustness playground (Context §5.1 A7 / §16.2).
 *
 * Left: live perturbations applied in-line to ml-engine ingest.
 * Right: analyst risk gauge + robustness chart (acoustic drop / context flat).
 *
 * No voice-clone generation. Perturbs existing audio only.
 */
export function RedTeamLab() {
  const { sessionId, isRunning } = useSession();
  const { latest } = useTelemetrySocket(sessionId);
  const [config, setConfig] = useState(defaultPerturbationConfig);
  const [livePoints, setLivePoints] = useState(
    /** @type {Array<{ intensity: number, families: Record<string, number> }>} */ ([]),
  );
  const [sweepPoints, setSweepPoints] = useState(
    /** @type {Array<{ intensity: number, families: Record<string, number> }> | null} */ (
      null
    ),
  );
  const [sweepBusy, setSweepBusy] = useState(false);
  const [syncError, setSyncError] = useState(/** @type {string | null} */ (null));
  const [serverPng, setServerPng] = useState(/** @type {string | null} */ (null));
  const chartRef = useRef(/** @type {HTMLCanvasElement | null} */ (null));
  const configKey = useMemo(() => JSON.stringify(config), [config]);

  // Push config to inference plane so the live ingest path perturbs in-line.
  useEffect(() => {
    if (!sessionId) return undefined;
    const t = window.setTimeout(async () => {
      try {
        const res = await fetch(`/engine/redteam/${encodeURIComponent(sessionId)}/config`, {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: configKey,
        });
        if (!res.ok) throw new Error(`config HTTP ${res.status}`);
        setSyncError(null);
      } catch (e) {
        setSyncError(e instanceof Error ? e.message : 'config sync failed');
      }
    }, 160);
    return () => window.clearTimeout(t);
  }, [sessionId, configKey]);

  // Probe acoustic scores as the noise slider moves; contextual from last telemetry (flat).
  useEffect(() => {
    if (!sessionId || !config.enabled) return undefined;
    const t = window.setTimeout(async () => {
      try {
        const res = await fetch(`/engine/redteam/${encodeURIComponent(sessionId)}/probe`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: configKey,
        });
        if (!res.ok) return;
        /** @type {{ acoustic?: Record<string, number> }} */
        const data = await res.json();
        const contextual = {};
        for (const fam of EVIDENCE_FAMILIES) {
          if (fam === 'voice' || fam === 'channel' || fam === 'prosody') continue;
          const s = getFamilyScore(latest, fam);
          contextual[fam] = typeof s === 'number' ? s : 0.55;
        }
        const families = {
          voice: data.acoustic?.voice ?? 0.5,
          channel: data.acoustic?.channel ?? 0.5,
          prosody: data.acoustic?.prosody ?? 0.5,
          ...contextual,
        };
        const intensity =
          typeof config.noise_snr_db === 'number' ? config.noise_snr_db : config.reverb_t60_ms;
        setLivePoints((prev) => {
          const without = prev.filter((p) => p.intensity !== intensity);
          return [...without, { intensity, families }].sort(
            (a, b) => a.intensity - b.intensity,
          );
        });
      } catch {
        /* probe is best-effort */
      }
    }, 220);
    return () => window.clearTimeout(t);
  }, [sessionId, configKey, config.enabled, config.noise_snr_db, config.reverb_t60_ms, latest]);

  const runSweep = useCallback(async () => {
    if (!sessionId) return;
    setSweepBusy(true);
    setServerPng(null);
    try {
      const contextual = {};
      for (const fam of ['linguistic', 'transaction', 'relationship']) {
        const s = getFamilyScore(latest, fam);
        contextual[fam] = typeof s === 'number' ? s : 0.58;
      }
      const res = await fetch(`/engine/redteam/${encodeURIComponent(sessionId)}/sweep`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          axis: 'noise_snr_db',
          values: [30, 25, 20, 15, 10, 5],
          base: { ...config, enabled: true, noise_snr_db: 30 },
          contextual,
          export_png: true,
        }),
      });
      if (!res.ok) throw new Error(`sweep HTTP ${res.status}`);
      const data = await res.json();
      setSweepPoints(data.points ?? []);
      if (typeof data.pngBase64 === 'string') setServerPng(data.pngBase64);
    } catch (e) {
      setSyncError(e instanceof Error ? e.message : 'sweep failed');
    } finally {
      setSweepBusy(false);
    }
  }, [sessionId, config, latest]);

  const chartPoints = sweepPoints ?? livePoints;
  const axisLabel = sweepPoints ? 'noise_snr_db (sweep)' : 'live intensity';

  const downloadPng = () => {
    if (serverPng) {
      const a = document.createElement('a');
      a.href = `data:image/png;base64,${serverPng}`;
      a.download = 'sentinelvoice-robustness-sweep.png';
      a.click();
      return;
    }
    exportChartPng(chartRef.current);
  };

  return (
    <div className="flex h-full min-h-0 flex-col gap-3 overflow-auto p-4">
      <header className="rounded border border-amber-700/50 bg-amber-950/30 px-4 py-3">
        <p className="font-mono text-[10px] uppercase tracking-[0.2em] text-amber-400/90">
          Defensive testing tool — not an attack toolkit
        </p>
        <h1 className="mt-1 font-display text-lg font-semibold text-sv-fg">
          Red Team Lab · Robustness evaluator
        </h1>
        <p className="mt-1 max-w-3xl text-sm text-sv-muted">
          Perturbs <em>existing</em> live or replayed audio (noise, pitch/time, codec, reverb,
          breaths, packet loss, band-limit) so you can watch acoustic detectors degrade while
          contextual families hold. There is <strong>no</strong> voice-clone generation, RVC, or
          TTS here — only robustness measurement (Context §16.2).
        </p>
      </header>

      <div className="grid min-h-0 flex-1 grid-cols-1 gap-3 lg:grid-cols-2">
        <Card title="Perturbations" variant="elevated" className="min-h-0">
          {!sessionId ? (
            <p className="text-sm text-sv-muted">
              Start a session from the Analyst Console (mic or SIP), then return here — perturbations
              apply in-line to that session&apos;s ingest stream.
            </p>
          ) : (
            <p className="mb-3 font-mono text-[11px] text-sv-accent">
              session {sessionId.slice(0, 8)}… {isRunning ? '· live' : '· idle'}
              {syncError ? ` · ${syncError}` : ''}
            </p>
          )}
          <PerturbationControls
            value={config}
            onChange={setConfig}
            disabled={!sessionId}
          />
          <div className="mt-4 flex flex-wrap gap-2">
            <button
              type="button"
              disabled={!sessionId || sweepBusy}
              onClick={runSweep}
              className="rounded border border-sv-border bg-sv-elevated px-3 py-1.5 text-sm text-sv-fg hover:border-sv-accent disabled:opacity-40"
            >
              {sweepBusy ? 'Sweeping…' : 'Run Sweep'}
            </button>
            <button
              type="button"
              disabled={!chartPoints.length && !serverPng}
              onClick={downloadPng}
              className="rounded border border-sv-border px-3 py-1.5 text-sm text-sv-muted hover:text-sv-fg disabled:opacity-40"
            >
              Export PNG
            </button>
            {sweepPoints ? (
              <button
                type="button"
                onClick={() => setSweepPoints(null)}
                className="rounded px-2 py-1.5 text-xs text-sv-muted hover:text-sv-fg"
              >
                Clear sweep · show live
              </button>
            ) : null}
          </div>
        </Card>

        <div className="flex min-h-0 flex-col gap-3">
          <Card title="Live risk (same session as Analyst Console)" variant="elevated">
            <div className="flex justify-center py-2">
              <RiskGauge frame={latest} />
            </div>
            <p className="text-center text-[11px] text-sv-muted">
              Lower the noise SNR on the left — voice family / gauge should react live.
            </p>
          </Card>

          <Card title="Robustness chart · acoustic vs contextual" variant="elevated">
            <RobustnessChart
              points={chartPoints}
              axisLabel={axisLabel}
              canvasRef={chartRef}
            />
          </Card>
        </div>
      </div>
    </div>
  );
}

RedTeamLab.propTypes = {};

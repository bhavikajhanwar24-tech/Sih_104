import { useEffect, useMemo, useRef, useState } from 'react';
import { SimulatedSignalBadge } from '@/components/SimulatedSignalBadge.jsx';
import { apiFetch } from '@/services/api.js';

/**
 * FPR parity chart from P12 {@code benchmarks/results.json} (Context §13.5).
 * Shows gaps honestly — flat charts are not the goal.
 * Synthetic harness output is badged; never presented as a field result.
 */
export function FairnessChart() {
  const [report, setReport] = useState(/** @type {Record<string, any> | null} */ (null));
  const [error, setError] = useState(/** @type {string | null} */ (null));
  const canvasRef = useRef(/** @type {HTMLCanvasElement | null} */ (null));

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const res = await apiFetch('/api/v1/compliance/fairness');
        if (!res.ok) throw new Error(`fairness HTTP ${res.status}`);
        const json = await res.json();
        if (!cancelled) {
          setReport(json);
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

  const series = useMemo(() => {
    const results = report?.results;
    if (!results) return [];
    /** @type {Array<{ label: string, fpr: number, facet: string }>} */
    const out = [];
    const push = (rows, facet) => {
      for (const row of rows ?? []) {
        if (row?.fpr == null || Number.isNaN(Number(row.fpr))) continue;
        out.push({ label: String(row.group ?? '?'), fpr: Number(row.fpr), facet });
      }
    };
    if (results.byLanguageGroup || results.byGender || results.byChannelProfile) {
      push(results.byLanguageGroup, 'language');
      push(results.byGender, 'gender');
      push(results.byAgeBand, 'age');
      push(results.byChannelProfile, 'channel');
    } else if (Array.isArray(results.fairness?.groups)) {
      for (const row of results.fairness.groups) {
        const name = String(row.group ?? '');
        const facet = name.includes('gender')
          ? 'gender'
          : name.includes('age')
            ? 'age'
            : 'language';
        if (row.fpr == null) continue;
        out.push({ label: name, fpr: Number(row.fpr), facet });
      }
    }
    return out;
  }, [report]);

  useEffect(() => {
    const el = canvasRef.current;
    if (!el || series.length === 0) return;
    const dpr = window.devicePixelRatio || 1;
    const w = el.clientWidth || 560;
    const h = 260;
    el.width = Math.floor(w * dpr);
    el.height = Math.floor(h * dpr);
    const ctx = el.getContext('2d');
    if (!ctx) return;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    drawBars(ctx, w, h, series);
  }, [series]);

  if (error) return <p className="text-sm text-red-400">{error}</p>;
  if (!report) return <p className="text-sm text-sv-muted">Loading fairness report…</p>;

  if (report.status === 'EVALUATION_NOT_RUN' || report.status === 'evaluation_not_yet_run' || !report.results) {
    return (
      <div className="rounded border border-amber-700/40 bg-amber-950/20 px-3 py-4 text-sm">
        <p className="font-semibold text-amber-300">Evaluation not yet run</p>
        <p className="mt-1 text-sv-muted">
          {report.message ?? 'P12 harness has not produced benchmarks/results.json.'}
        </p>
        <p className="mt-2 font-mono text-[10px] text-sv-muted">path: {report.path}</p>
      </div>
    );
  }

  const results = report.results;
  const synthetic = report.synthetic === true || results.synthetic === true;

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2">
        <p className="text-[11px] text-sv-muted">
          Metric: <span className="font-mono text-sv-fg">{results.metric}</span>
          {results.generatedAt ? (
            <>
              {' · '}generated <span className="font-mono">{results.generatedAt}</span>
            </>
          ) : null}
        </p>
        {synthetic ? (
          <SimulatedSignalBadge label="SYNTHETIC SMOKE DATA — NOT A FIELD RESULT" />
        ) : null}
      </div>
      <canvas
        ref={canvasRef}
        className="h-[260px] w-full rounded border border-sv-border bg-[#0c1118]"
        role="img"
        aria-label="False positive rate by language, gender, and channel"
      />
      <p className="text-sm leading-snug text-sv-fg">{results.disparityNotes}</p>
      <ul className="list-disc space-y-1 pl-4 text-[11px] text-sv-muted">
        {(results.mitigations ?? []).map((m) => (
          <li key={m}>{m}</li>
        ))}
      </ul>
    </div>
  );
}

FairnessChart.propTypes = {};

/**
 * @param {CanvasRenderingContext2D} ctx
 * @param {number} w
 * @param {number} h
 * @param {Array<{ label: string, fpr: number, facet: string }>} series
 */
function drawBars(ctx, w, h, series) {
  ctx.clearRect(0, 0, w, h);
  ctx.fillStyle = '#0c1118';
  ctx.fillRect(0, 0, w, h);
  const pad = { l: 48, r: 12, t: 16, b: 72 };
  const iw = w - pad.l - pad.r;
  const ih = h - pad.t - pad.b;
  const max = Math.max(0.25, ...series.map((s) => s.fpr));
  const barW = iw / series.length;

  const colours = {
    language: '#c45c26',
    gender: '#2a6f6f',
    age: '#7c6cf0',
    channel: '#d4a017',
  };

  ctx.strokeStyle = '#243044';
  for (let i = 0; i <= 4; i++) {
    const y = pad.t + (ih * i) / 4;
    ctx.beginPath();
    ctx.moveTo(pad.l, y);
    ctx.lineTo(pad.l + iw, y);
    ctx.stroke();
    ctx.fillStyle = '#8b9bb4';
    ctx.font = '10px JetBrains Mono, monospace';
    ctx.textAlign = 'right';
    ctx.fillText((max * (1 - i / 4)).toFixed(2), pad.l - 6, y + 3);
  }

  series.forEach((s, i) => {
    const x = pad.l + i * barW + barW * 0.15;
    const bh = (s.fpr / max) * ih;
    const y = pad.t + ih - bh;
    ctx.fillStyle = colours[s.facet] ?? '#38bdf8';
    ctx.fillRect(x, y, barW * 0.7, bh);
    ctx.save();
    ctx.translate(pad.l + i * barW + barW / 2, pad.t + ih + 8);
    ctx.rotate(-Math.PI / 4);
    ctx.fillStyle = '#8b9bb4';
    ctx.font = '9px IBM Plex Sans, sans-serif';
    ctx.textAlign = 'right';
    ctx.fillText(s.label, 0, 0);
    ctx.restore();
  });

  ctx.fillStyle = '#8b9bb4';
  ctx.font = '11px IBM Plex Sans, sans-serif';
  ctx.textAlign = 'left';
  ctx.fillText('FPR @ fixed TPR — gaps are intentional (honest reporting)', pad.l, h - 8);
}

/** Pure helper for vitest — whether the portal must show the synthetic badge. */
export function fairnessIsSynthetic(report) {
  if (!report || typeof report !== 'object') return false;
  if (report.synthetic === true) return true;
  if (report.results?.synthetic === true) return true;
  return false;
}

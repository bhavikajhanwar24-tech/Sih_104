import { useEffect, useMemo, useRef } from 'react';
import PropTypes from 'prop-types';
import { EVIDENCE_FAMILIES } from '@/lib/evidenceMeta.js';
import { palette } from '@/theme.js';

const ACOUSTIC = new Set(['voice', 'channel', 'prosody']);

const FAMILY_COLOUR = Object.freeze({
  voice: '#c45c26',
  channel: '#d4a017',
  prosody: '#e07a3d',
  linguistic: '#2a6f6f',
  transaction: '#3d8b8b',
  relationship: '#1f5c5c',
});

/**
 * Live plot: acoustic families degrade under evasion; contextual hold flat.
 * That split is the corroboration argument (Context §9.3 / §16.2).
 *
 * @param {Object} props
 * @param {Array<{ intensity: number, families: Record<string, number> }>} props.points
 * @param {string} [props.axisLabel]
 * @param {string} [props.className]
 * @param {React.RefObject<HTMLCanvasElement | null>} [props.canvasRef]  for PNG export
 */
export function RobustnessChart({
  points,
  axisLabel = 'intensity',
  className = '',
  canvasRef,
}) {
  const localRef = useRef(/** @type {HTMLCanvasElement | null} */ (null));
  const canvas = canvasRef ?? localRef;

  const series = useMemo(() => {
    return EVIDENCE_FAMILIES.map((fam) => ({
      fam,
      colour: FAMILY_COLOUR[fam] ?? palette.accent,
      acoustic: ACOUSTIC.has(fam),
      ys: points.map((p) => Number(p.families?.[fam] ?? 0)),
    }));
  }, [points]);

  useEffect(() => {
    const el = canvas.current;
    if (!el) return;
    const dpr = window.devicePixelRatio || 1;
    const cssW = el.clientWidth || 560;
    const cssH = el.clientHeight || 280;
    el.width = Math.floor(cssW * dpr);
    el.height = Math.floor(cssH * dpr);
    const ctx = el.getContext('2d');
    if (!ctx) return;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    drawChart(ctx, cssW, cssH, points, series, axisLabel);
  }, [points, series, axisLabel, canvas]);

  return (
    <div className={`flex flex-col gap-2 ${className}`}>
      <canvas
        ref={canvas}
        className="h-[280px] w-full rounded border border-sv-border bg-[#0c1118]"
        role="img"
        aria-label="Robustness chart: acoustic vs contextual family scores"
      />
      <div className="flex flex-wrap gap-x-3 gap-y-1 text-[10px] text-sv-muted">
        {EVIDENCE_FAMILIES.map((fam) => (
          <span key={fam} className="inline-flex items-center gap-1 font-mono">
            <span
              className="inline-block h-2 w-3 rounded-sm"
              style={{
                background: FAMILY_COLOUR[fam],
                opacity: ACOUSTIC.has(fam) ? 1 : 0.85,
                borderBottom: ACOUSTIC.has(fam) ? undefined : '1px dashed currentColor',
              }}
            />
            {fam}
            <span className="text-sv-muted/70">
              {ACOUSTIC.has(fam) ? '(acoustic)' : '(context)'}
            </span>
          </span>
        ))}
      </div>
      <p className="text-[11px] leading-snug text-sv-muted">
        Acoustic scores fall under evasion; contextual families stay flat — corroboration still
        holds the decision.
      </p>
    </div>
  );
}

RobustnessChart.propTypes = {
  points: PropTypes.arrayOf(
    PropTypes.shape({
      intensity: PropTypes.number,
      families: PropTypes.object,
    }),
  ).isRequired,
  axisLabel: PropTypes.string,
  className: PropTypes.string,
  canvasRef: PropTypes.shape({ current: PropTypes.any }),
};

/**
 * Download the chart canvas as a PNG (deck-ready).
 * @param {HTMLCanvasElement | null} el
 * @param {string} [filename]
 */
export function exportChartPng(el, filename = 'sentinelvoice-robustness-sweep.png') {
  if (!el) return;
  const url = el.toDataURL('image/png');
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.click();
}

/**
 * @param {CanvasRenderingContext2D} ctx
 * @param {number} w
 * @param {number} h
 * @param {Array<{ intensity: number }>} points
 * @param {Array<{ fam: string, colour: string, acoustic: boolean, ys: number[] }>} series
 * @param {string} axisLabel
 */
function drawChart(ctx, w, h, points, series, axisLabel) {
  ctx.clearRect(0, 0, w, h);
  const pad = { l: 44, r: 16, t: 16, b: 36 };
  const iw = w - pad.l - pad.r;
  const ih = h - pad.t - pad.b;

  ctx.fillStyle = '#0c1118';
  ctx.fillRect(0, 0, w, h);

  // Grid
  ctx.strokeStyle = '#243044';
  ctx.lineWidth = 1;
  for (let i = 0; i <= 4; i++) {
    const y = pad.t + (ih * i) / 4;
    ctx.beginPath();
    ctx.moveTo(pad.l, y);
    ctx.lineTo(pad.l + iw, y);
    ctx.stroke();
    ctx.fillStyle = '#8b9bb4';
    ctx.font = '10px JetBrains Mono, monospace';
    ctx.textAlign = 'right';
    ctx.fillText((1 - i / 4).toFixed(1), pad.l - 6, y + 3);
  }

  if (!points.length) {
    ctx.fillStyle = '#8b9bb4';
    ctx.textAlign = 'center';
    ctx.font = '12px IBM Plex Sans, sans-serif';
    ctx.fillText('Move a slider or run a sweep', pad.l + iw / 2, pad.t + ih / 2);
    return;
  }

  const xs = points.map((p) => p.intensity);
  const xmin = Math.min(...xs);
  const xmax = Math.max(...xs);
  const xspan = xmax - xmin || 1;

  const xAt = (v) => pad.l + ((v - xmin) / xspan) * iw;
  const yAt = (v) => pad.t + (1 - Math.min(1, Math.max(0, v))) * ih;

  for (const s of series) {
    if (s.ys.length === 0) continue;
    ctx.beginPath();
    ctx.strokeStyle = s.colour;
    ctx.lineWidth = s.acoustic ? 2.4 : 1.6;
    if (!s.acoustic) ctx.setLineDash([5, 4]);
    else ctx.setLineDash([]);
    s.ys.forEach((y, i) => {
      const x = xAt(xs[i]);
      const yy = yAt(y);
      if (i === 0) ctx.moveTo(x, yy);
      else ctx.lineTo(x, yy);
    });
    ctx.stroke();
    ctx.setLineDash([]);
  }

  ctx.fillStyle = '#8b9bb4';
  ctx.font = '11px IBM Plex Sans, sans-serif';
  ctx.textAlign = 'center';
  ctx.fillText(axisLabel, pad.l + iw / 2, h - 10);
}

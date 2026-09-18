import { useMemo } from 'react';
import PropTypes from 'prop-types';
import { getSmoothedRisk } from '@/contracts';
import { LEVEL_BANDS } from '@/lib/reasonMeta.js';
import { palette, riskColour, riskRamp } from '@/theme.js';

const WINDOW_MS = 120_000;
const W = 280;
const H = 56;
const PAD = { top: 4, right: 4, bottom: 4, left: 4 };

/**
 * Sparkline of smoothed risk over the last 120 s with intervention level bands
 * and markers where the level changed.
 *
 * @param {Object} props
 * @param {TelemetryFrame[]} [props.history]
 * @param {string} [props.className]
 */
export function RiskTimeline({ history = [], className = '' }) {
  const { points, markers, pathD, bandRects } = useMemo(
    () => buildSparkline(history),
    [history],
  );

  const empty = points.length < 2;

  return (
    <div className={`w-full ${className}`}>
      <div className="mb-0.5 flex items-center justify-between px-0.5">
        <span className="font-mono text-[9px] uppercase tracking-wider text-sv-muted">
          120 s trajectory
        </span>
        <span className="font-mono text-[9px] text-sv-muted">
          L1–L5 bands
        </span>
      </div>
      <svg
        width="100%"
        height={H}
        viewBox={`0 0 ${W} ${H}`}
        preserveAspectRatio="none"
        role="img"
        aria-label="Risk score over the last 120 seconds"
        className="block overflow-visible"
      >
        {/* Level bands (background) */}
        {bandRects.map((b) => (
          <rect
            key={b.level}
            x={PAD.left}
            y={b.y}
            width={W - PAD.left - PAD.right}
            height={b.h}
            fill={b.fill}
            opacity={0.22}
          />
        ))}
        {/* Band separators */}
        {bandRects.slice(1).map((b) => (
          <line
            key={`sep-${b.level}`}
            x1={PAD.left}
            x2={W - PAD.right}
            y1={b.y}
            y2={b.y}
            stroke={palette.border}
            strokeWidth={0.5}
            opacity={0.6}
          />
        ))}

        {empty ? (
          <text
            x={W / 2}
            y={H / 2 + 3}
            textAnchor="middle"
            fill={palette.muted}
            fontFamily="IBM Plex Mono, monospace"
            fontSize="9"
          >
            awaiting frames…
          </text>
        ) : (
          <>
            <path
              d={pathD}
              fill="none"
              stroke={riskColour(points[points.length - 1]?.y ?? 0)}
              strokeWidth={1.75}
              strokeLinejoin="round"
              strokeLinecap="round"
              vectorEffect="non-scaling-stroke"
            />
            {markers.map((m) => (
              <g key={`${m.x}-${m.level}`}>
                <line
                  x1={m.x}
                  x2={m.x}
                  y1={PAD.top}
                  y2={H - PAD.bottom}
                  stroke={riskRamp.elevated}
                  strokeWidth={1}
                  strokeDasharray="2 2"
                  opacity={0.85}
                />
                <circle
                  cx={m.x}
                  cy={m.cy}
                  r={2.5}
                  fill={riskRamp.elevated}
                  stroke={palette.panel}
                  strokeWidth={1}
                />
              </g>
            ))}
          </>
        )}
      </svg>
    </div>
  );
}

RiskTimeline.propTypes = {
  history: PropTypes.arrayOf(PropTypes.object),
  className: PropTypes.string,
};

/**
 * @param {TelemetryFrame[]} history
 */
function buildSparkline(history) {
  const plotW = W - PAD.left - PAD.right;
  const plotH = H - PAD.top - PAD.bottom;

  const bandRects = [...LEVEL_BANDS].reverse().map((band) => {
    // y=0 is score 1.0 at top
    const y0 = PAD.top + (1 - band.to) * plotH;
    const y1 = PAD.top + (1 - band.from) * plotH;
    return {
      level: band.level,
      y: y0,
      h: Math.max(1, y1 - y0),
      fill: riskColour((band.from + band.to) / 2),
    };
  });

  if (!Array.isArray(history) || history.length === 0) {
    return { points: [], markers: [], pathD: '', bandRects };
  }

  const last = history[history.length - 1];
  const endMs =
    typeof last?.tsEpochMs === 'number'
      ? last.tsEpochMs
      : typeof last?.callElapsedMs === 'number'
        ? last.callElapsedMs
        : 0;
  const startMs = endMs - WINDOW_MS;

  const windowed = history.filter((f) => {
    const t = frameTime(f);
    return t >= startMs && t <= endMs;
  });

  const span = Math.max(1, endMs - startMs);
  /** @type {{ x: number, y: number, level: string }[]} */
  const points = windowed.map((f) => {
    const t = frameTime(f);
    const score = getSmoothedRisk(f);
    return {
      x: PAD.left + ((t - startMs) / span) * plotW,
      y: score,
      level: typeof f?.intervention?.level === 'string' ? f.intervention.level : '',
      cy: PAD.top + (1 - clamp01(score)) * plotH,
    };
  });

  /** @type {{ x: number, cy: number, level: string }[]} */
  const markers = [];
  for (let i = 1; i < points.length; i += 1) {
    if (points[i].level && points[i].level !== points[i - 1].level) {
      markers.push({
        x: points[i].x,
        cy: points[i].cy,
        level: points[i].level,
      });
    }
  }

  let pathD = '';
  if (points.length > 0) {
    pathD = points
      .map((p, i) => {
        const py = PAD.top + (1 - clamp01(p.y)) * plotH;
        return `${i === 0 ? 'M' : 'L'} ${p.x.toFixed(2)} ${py.toFixed(2)}`;
      })
      .join(' ');
  }

  return { points, markers, pathD, bandRects };
}

/** @param {TelemetryFrame} f */
function frameTime(f) {
  if (typeof f?.tsEpochMs === 'number') return f.tsEpochMs;
  if (typeof f?.callElapsedMs === 'number') return f.callElapsedMs;
  return 0;
}

/** @param {number} v */
function clamp01(v) {
  if (!Number.isFinite(v)) return 0;
  return Math.max(0, Math.min(1, v));
}

import { useEffect, useId, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import { getLevel, getSmoothedRisk, INTERVENTION_LEVELS, RISK_STATES } from '@/contracts';
import { palette, riskColour, riskRamp } from '@/theme.js';

/** @deprecated Use {@link riskColour} from `@/theme.js`. */
export const riskColor = riskColour;

const INSUFFICIENT_STROKE = '#64748b';

/**
 * Animated SVG risk gauge — smoothed (EMA) arc + instantaneous tick.
 *
 * {@code INSUFFICIENT_EVIDENCE} renders grey hatch — never clear-green —
 * so "we don't know" is not conflated with "it's safe".
 *
 * @param {Object} props
 * @param {TelemetryFrame | null | undefined} props.frame
 * @param {string} [props.className]
 */
export function RiskGauge({ frame, className = '' }) {
  const patternId = useId().replace(/:/g, '');
  const smoothedTarget = getSmoothedRisk(frame);
  const instantaneousTarget =
    typeof frame?.risk?.instantaneous === 'number' ? frame.risk.instantaneous : 0;
  const level = getLevel(frame);
  const riskState = frame?.risk?.state ?? RISK_STATES.INSUFFICIENT_EVIDENCE;
  const insufficient = riskState === RISK_STATES.INSUFFICIENT_EVIDENCE;
  const trend = frame?.risk?.trend ?? 'STABLE';

  const [smoothed, setSmoothed] = useState(smoothedTarget);
  const [instantaneous, setInstantaneous] = useState(instantaneousTarget);
  const animRef = useRef(/** @type {number | null} */ (null));
  const fromRef = useRef({ s: smoothedTarget, i: instantaneousTarget });

  useEffect(() => {
    const fromS = fromRef.current.s;
    const fromI = fromRef.current.i;
    const toS = smoothedTarget;
    const toI = instantaneousTarget;
    const started = performance.now();
    const duration = 300;

    if (animRef.current != null) cancelAnimationFrame(animRef.current);

    const tick = (now) => {
      const t = Math.min(1, (now - started) / duration);
      const ease = 1 - (1 - t) ** 3;
      const s = fromS + (toS - fromS) * ease;
      const i = fromI + (toI - fromI) * ease;
      setSmoothed(s);
      setInstantaneous(i);
      fromRef.current = { s, i };
      if (t < 1) {
        animRef.current = requestAnimationFrame(tick);
      } else {
        animRef.current = null;
      }
    };
    animRef.current = requestAnimationFrame(tick);
    return () => {
      if (animRef.current != null) cancelAnimationFrame(animRef.current);
    };
  }, [smoothedTarget, instantaneousTarget]);

  const size = 220;
  const cx = size / 2;
  const cy = size / 2 + 10;
  const radius = 84;
  const startAngle = Math.PI;
  const sweep = Math.PI;

  const fillColour = insufficient ? INSUFFICIENT_STROKE : riskColour(smoothed);
  const pct = Math.round(smoothed * 100);
  const fillFrac = insufficient ? Math.max(0.08, clamp01(smoothed)) : clamp01(smoothed);

  return (
    <div className={`flex flex-col items-center gap-1 ${className}`}>
      <svg
        width={size}
        height={size * 0.68}
        viewBox={`0 0 ${size} ${size * 0.68}`}
        role="img"
        aria-label={
          insufficient
            ? 'Insufficient evidence — risk not yet scored'
            : `Risk ${pct} percent, ${formatLevel(level)}`
        }
      >
        <defs>
          <pattern
            id={`hatch-${patternId}`}
            patternUnits="userSpaceOnUse"
            width="6"
            height="6"
            patternTransform="rotate(45)"
          >
            <rect width="6" height="6" fill="#1e293b" />
            <line
              x1="0"
              y1="0"
              x2="0"
              y2="6"
              stroke={INSUFFICIENT_STROKE}
              strokeWidth="2.5"
              opacity="0.85"
            />
          </pattern>
        </defs>

        {/* Track */}
        <path
          d={arcPath(cx, cy, radius, startAngle, startAngle + sweep)}
          fill="none"
          stroke={insufficient ? '#334155' : 'var(--sv-border)'}
          strokeWidth={14}
          strokeLinecap="round"
        />

        {/* Smoothed EMA fill — hatch when evidence window not met */}
        {insufficient ? (
          <path
            d={arcPath(cx, cy, radius, startAngle, startAngle + sweep * fillFrac)}
            fill="none"
            stroke={`url(#hatch-${patternId})`}
            strokeWidth={14}
            strokeLinecap="round"
          />
        ) : (
          <path
            d={arcPath(cx, cy, radius, startAngle, startAngle + sweep * fillFrac)}
            fill="none"
            stroke={fillColour}
            strokeWidth={14}
            strokeLinecap="round"
          />
        )}

        {/* Thin EMA outline so judges can see the smoother vs the tick */}
        {!insufficient ? (
          <path
            d={arcPath(cx, cy, radius - 11, startAngle, startAngle + sweep * fillFrac)}
            fill="none"
            stroke={fillColour}
            strokeWidth={2}
            strokeLinecap="round"
            opacity={0.45}
          />
        ) : null}

        {/* Instantaneous tick (raw score before asymmetric EMA) */}
        <g transform={`rotate(${needleDeg(instantaneous)} ${cx} ${cy})`}>
          <line
            x1={cx}
            y1={cy - radius + 18}
            x2={cx}
            y2={cy - radius - 2}
            stroke={insufficient ? INSUFFICIENT_STROKE : palette.fg}
            strokeWidth={2.5}
            strokeLinecap="round"
            opacity={0.9}
          />
        </g>
        <circle
          cx={cx}
          cy={cy}
          r={4}
          fill={insufficient ? INSUFFICIENT_STROKE : palette.fg}
        />

        <text
          x={cx}
          y={cy - 22}
          textAnchor="middle"
          fill={insufficient ? INSUFFICIENT_STROKE : palette.fg}
          fontFamily="IBM Plex Sans, sans-serif"
          fontSize="34"
          fontWeight="600"
        >
          {insufficient ? '—' : pct}
        </text>
        <text
          x={cx}
          y={cy - 2}
          textAnchor="middle"
          fill={palette.muted}
          fontFamily="IBM Plex Mono, monospace"
          fontSize="10"
        >
          {insufficient ? 'not scored' : 'EMA %'}
        </text>
      </svg>

      <div className="text-center">
        {insufficient ? (
          <p
            className="rounded border border-slate-500/50 bg-slate-800/80 px-2 py-1 font-mono text-[10px] font-semibold uppercase tracking-wider text-slate-300"
            title="Below the ~3 s speech evidence window — not a clear/safe score"
          >
            Insufficient evidence
          </p>
        ) : (
          <>
            <p className="font-mono text-[10px] uppercase tracking-wider text-sv-muted">
              Intervention
            </p>
            <p
              className="font-display text-sm font-semibold"
              style={{ color: fillColour }}
            >
              {formatLevel(level)}
            </p>
          </>
        )}
        <p className="mt-1 flex items-center justify-center gap-1.5 font-mono text-[10px] text-sv-muted">
          <span title="Instantaneous (pre-EMA)">
            tick {(instantaneous * 100).toFixed(0)}%
          </span>
          <span aria-hidden>·</span>
          <TrendArrow trend={trend} />
        </p>
      </div>
    </div>
  );
}

RiskGauge.propTypes = {
  frame: PropTypes.object,
  className: PropTypes.string,
};

/**
 * @param {Object} props
 * @param {string} props.trend
 */
function TrendArrow({ trend }) {
  const t = String(trend || 'STABLE').toUpperCase();
  if (t === 'RISING') {
    return (
      <span className="inline-flex items-center gap-0.5" style={{ color: riskRamp.elevated }} title="Rising">
        <span aria-hidden>↑</span> rising
      </span>
    );
  }
  if (t === 'FALLING') {
    return (
      <span className="inline-flex items-center gap-0.5" style={{ color: riskRamp.clear }} title="Falling">
        <span aria-hidden>↓</span> falling
      </span>
    );
  }
  return (
    <span className="inline-flex items-center gap-0.5 text-sv-muted" title="Stable">
      <span aria-hidden>→</span> stable
    </span>
  );
}

TrendArrow.propTypes = {
  trend: PropTypes.string,
};

/**
 * @param {number} x
 * @param {number} y
 * @param {number} r
 * @param {number} a0
 * @param {number} a1
 * @returns {string}
 */
function arcPath(x, y, r, a0, a1) {
  if (a1 <= a0 + 1e-6) {
    const x0 = x + r * Math.cos(a0);
    const y0 = y + r * Math.sin(a0);
    return `M ${x0} ${y0} L ${x0} ${y0}`;
  }
  const x0 = x + r * Math.cos(a0);
  const y0 = y + r * Math.sin(a0);
  const x1 = x + r * Math.cos(a1);
  const y1 = y + r * Math.sin(a1);
  const large = a1 - a0 > Math.PI ? 1 : 0;
  return `M ${x0} ${y0} A ${r} ${r} 0 ${large} 1 ${x1} ${y1}`;
}

/** @param {number} score */
function needleDeg(score) {
  return -90 + clamp01(score) * 180;
}

/** @param {number} v */
function clamp01(v) {
  if (Number.isNaN(v)) return 0;
  return Math.max(0, Math.min(1, v));
}

/** @param {string} level */
function formatLevel(level) {
  const map = {
    [INTERVENTION_LEVELS.LEVEL_1_SILENT]: 'L1 Silent',
    [INTERVENTION_LEVELS.LEVEL_2_SOFT_NUDGE]: 'L2 Soft nudge',
    [INTERVENTION_LEVELS.LEVEL_3_STEP_UP_MFA]: 'L3 Step-up MFA',
    [INTERVENTION_LEVELS.LEVEL_4_AUTO_HOLD]: 'L4 Auto-hold',
    [INTERVENTION_LEVELS.LEVEL_5_TERMINATE]: 'L5 Terminate',
  };
  return map[level] || level;
}

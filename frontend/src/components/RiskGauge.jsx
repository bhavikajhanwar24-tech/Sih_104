import { useEffect, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import { getLevel, getSmoothedRisk, INTERVENTION_LEVELS } from '@/contracts';

/**
 * Map a unit-interval risk score to the Tailwind risk colour ramp CSS variable.
 * @param {number} score 0..1
 * @returns {string}
 */
export function riskColor(score) {
  if (score < 0.35) return 'var(--risk-clear)';
  if (score < 0.55) return 'var(--risk-watch)';
  if (score < 0.75) return 'var(--risk-elevated)';
  return 'var(--risk-critical)';
}

/**
 * Animated SVG risk gauge — smoothed arc + instantaneous needle.
 *
 * @param {Object} props
 * @param {TelemetryFrame | null | undefined} props.frame
 * @param {string} [props.className]
 */
export function RiskGauge({ frame, className = '' }) {
  const smoothedTarget = getSmoothedRisk(frame);
  const instantaneousTarget =
    typeof frame?.risk?.instantaneous === 'number' ? frame.risk.instantaneous : 0;
  const level = getLevel(frame);

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
  const startAngle = Math.PI; // left
  const sweep = Math.PI; // semicircle

  const color = riskColor(smoothed);
  const pct = Math.round(smoothed * 100);

  return (
    <div className={`flex flex-col items-center gap-2 ${className}`}>
      <svg
        width={size}
        height={size * 0.72}
        viewBox={`0 0 ${size} ${size * 0.72}`}
        role="img"
        aria-label={`Risk ${pct} percent`}
      >
        {/* Track */}
        <path
          d={arcPath(cx, cy, radius, startAngle, startAngle + sweep)}
          fill="none"
          stroke="var(--sv-border)"
          strokeWidth={14}
          strokeLinecap="round"
        />
        {/* Smoothed primary arc */}
        <path
          d={arcPath(cx, cy, radius, startAngle, startAngle + sweep * clamp01(smoothed))}
          fill="none"
          stroke={color}
          strokeWidth={14}
          strokeLinecap="round"
        />
        {/* Instantaneous needle */}
        <g transform={`rotate(${needleDeg(instantaneous)} ${cx} ${cy})`}>
          <line
            x1={cx}
            y1={cy}
            x2={cx}
            y2={cy - radius + 4}
            stroke="var(--sv-fg)"
            strokeWidth={2}
            strokeLinecap="round"
            opacity={0.85}
          />
          <circle cx={cx} cy={cy} r={5} fill="var(--sv-fg)" />
        </g>
        <text
          x={cx}
          y={cy - 18}
          textAnchor="middle"
          fill="var(--sv-fg)"
          fontFamily="IBM Plex Sans, sans-serif"
          fontSize="36"
          fontWeight="600"
        >
          {pct}
        </text>
        <text
          x={cx}
          y={cy + 6}
          textAnchor="middle"
          fill="var(--sv-muted)"
          fontFamily="IBM Plex Mono, monospace"
          fontSize="11"
        >
          smoothed %
        </text>
      </svg>
      <div className="text-center">
        <p className="font-mono text-xs uppercase tracking-wider text-sv-muted">
          Intervention
        </p>
        <p className="font-display text-sm font-semibold text-sv-fg">
          {formatLevel(level)}
        </p>
        <p className="mt-1 font-mono text-[10px] text-sv-muted">
          instant {(instantaneous * 100).toFixed(0)}% · trend{' '}
          {frame?.risk?.trend ?? '—'}
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
 * @param {number} x
 * @param {number} y
 * @param {number} r
 * @param {number} a0
 * @param {number} a1
 * @returns {string}
 */
function arcPath(x, y, r, a0, a1) {
  if (a1 <= a0 + 1e-6) {
    // Degenerate — draw a tiny stub so SVG stays valid.
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

/**
 * Needle rotation: 0% → -90° (left), 100% → +90° (right) from vertical-up.
 * Our arc is a bottom semicircle from π to 2π in standard math coords…
 * Actually startAngle=π is left, π+π=2π is right along the upper... wait.
 * In SVG, y increases downward. cos(π)=-1 (left), cos(2π)=1 (right),
 * sin(π)=0, sin(1.5π)=-1 (up in SVG? sin(3π/2)=-1 → y = cy - r → UP). Good upper semicircle.
 *
 * Needle at 0 → point left (-90 from up), at 1 → point right (+90 from up).
 * CSS rotate is clockwise from up in SVG? rotate(0) is up. We want 0% = left = -90 or 270.
 * @param {number} score
 * @returns {number} degrees
 */
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

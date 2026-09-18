import { useEffect, useMemo, useRef } from 'react';
import PropTypes from 'prop-types';
import {
  BENIGN_BASELINE,
  EVIDENCE_FAMILIES,
  readFamily,
} from '@/lib/evidenceMeta.js';
import { palette, riskColour, riskRamp } from '@/theme.js';

const LABELS = Object.freeze({
  voice: 'Voice',
  channel: 'Channel',
  prosody: 'Prosody',
  linguistic: 'Linguistic',
  transaction: 'Txn',
  relationship: 'Rel',
});

/**
 * 6-axis radar — live scores overlaid on a benign baseline.
 * Unavailable families: dashed axis + slash (never plotted as zero).
 *
 * @param {Object} props
 * @param {TelemetryFrame | null | undefined} props.frame
 * @param {string | null} [props.highlightedFamily]
 * @param {string} [props.className]
 */
export function FamilyRadar({ frame, highlightedFamily = null, className = '' }) {
  const size = 200;
  const cx = size / 2;
  const cy = size / 2;
  const maxR = 72;
  const n = EVIDENCE_FAMILIES.length;

  const axes = useMemo(() => {
    return EVIDENCE_FAMILIES.map((family, i) => {
      const angle = -Math.PI / 2 + (i * 2 * Math.PI) / n;
      const fam = readFamily(frame, family);
      return {
        family,
        angle,
        ...fam,
        label: LABELS[family] ?? family,
        tipX: cx + maxR * Math.cos(angle),
        tipY: cy + maxR * Math.sin(angle),
      };
    });
  }, [frame, cx, cy, maxR, n]);

  const livePoints = axes
    .filter((a) => a.available)
    .map((a) => polar(cx, cy, maxR * clamp01(a.score), a.angle));

  const baselinePoints = axes.map((a) =>
    polar(cx, cy, maxR * clamp01(BENIGN_BASELINE[a.family] ?? 0.1), a.angle),
  );

  return (
    <svg
      width="100%"
      height="100%"
      viewBox={`0 0 ${size} ${size}`}
      role="img"
      aria-label="Evidence family radar"
      className={className}
    >
      {/* Rings */}
      {[0.25, 0.5, 0.75, 1].map((f) => (
        <circle
          key={f}
          cx={cx}
          cy={cy}
          r={maxR * f}
          fill="none"
          stroke={palette.border}
          strokeWidth={0.75}
          opacity={0.7}
        />
      ))}

      {/* Axes */}
      {axes.map((a) => {
        const hi = highlightedFamily === a.family;
        return (
          <g key={a.family}>
            <line
              x1={cx}
              y1={cy}
              x2={a.tipX}
              y2={a.tipY}
              stroke={hi ? palette.accent : palette.border}
              strokeWidth={hi ? 1.75 : 1}
              strokeDasharray={a.available ? undefined : '4 3'}
              opacity={a.available ? 1 : 0.7}
            />
            {!a.available ? (
              <UnavailableMark
                x={(cx + a.tipX) / 2}
                y={(cy + a.tipY) / 2}
              />
            ) : null}
            <text
              x={cx + (maxR + 14) * Math.cos(a.angle)}
              y={cy + (maxR + 14) * Math.sin(a.angle)}
              textAnchor="middle"
              dominantBaseline="middle"
              fill={hi ? palette.accent : a.available ? palette.fg : palette.muted}
              fontFamily="IBM Plex Mono, monospace"
              fontSize="8"
              fontWeight={hi ? 600 : 400}
            >
              {a.label}
            </text>
          </g>
        );
      })}

      {/* Benign baseline */}
      <polygon
        points={toPoints(baselinePoints)}
        fill={`${palette.muted}22`}
        stroke={palette.muted}
        strokeWidth={1}
        strokeDasharray="3 2"
      />

      {/* Live polygon — only available vertices */}
      {livePoints.length >= 3 ? (
        <LivePolygon points={livePoints} scores={axes.filter((a) => a.available)} />
      ) : null}

      <text
        x={8}
        y={size - 8}
        fill={palette.muted}
        fontFamily="IBM Plex Mono, monospace"
        fontSize="7"
      >
        —— benign · — live
      </text>
    </svg>
  );
}

FamilyRadar.propTypes = {
  frame: PropTypes.object,
  highlightedFamily: PropTypes.string,
  className: PropTypes.string,
};

/**
 * @param {Object} props
 * @param {{ x: number, y: number }[]} props.points
 * @param {{ score: number }[]} props.scores
 */
function LivePolygon({ points, scores }) {
  const avg =
    scores.reduce((s, a) => s + a.score, 0) / Math.max(1, scores.length);
  const stroke = riskColour(avg);
  const animRef = useRef(/** @type {SVGPolygonElement | null} */ (null));

  useEffect(() => {
    const el = animRef.current;
    if (!el) return;
    el.style.transition = 'all 300ms ease-out';
  }, [points]);

  return (
    <polygon
      ref={animRef}
      points={toPoints(points)}
      fill={`${stroke}33`}
      stroke={stroke}
      strokeWidth={2}
    />
  );
}

LivePolygon.propTypes = {
  points: PropTypes.arrayOf(
    PropTypes.shape({ x: PropTypes.number, y: PropTypes.number }),
  ).isRequired,
  scores: PropTypes.arrayOf(PropTypes.object).isRequired,
};

/** @param {number} x @param {number} y */
function UnavailableMark({ x, y }) {
  return (
    <g opacity={0.85}>
      <line
        x1={x - 5}
        y1={y - 5}
        x2={x + 5}
        y2={y + 5}
        stroke={riskRamp.watch}
        strokeWidth={1.5}
      />
      <line
        x1={x - 5}
        y1={y + 5}
        x2={x + 5}
        y2={y - 5}
        stroke={riskRamp.watch}
        strokeWidth={1.5}
      />
    </g>
  );
}

UnavailableMark.propTypes = {
  x: PropTypes.number.isRequired,
  y: PropTypes.number.isRequired,
};

/**
 * @param {number} cx
 * @param {number} cy
 * @param {number} r
 * @param {number} angle
 */
function polar(cx, cy, r, angle) {
  return { x: cx + r * Math.cos(angle), y: cy + r * Math.sin(angle) };
}

/** @param {{ x: number, y: number }[]} pts */
function toPoints(pts) {
  return pts.map((p) => `${p.x.toFixed(1)},${p.y.toFixed(1)}`).join(' ');
}

/** @param {number} v */
function clamp01(v) {
  if (!Number.isFinite(v)) return 0;
  return Math.max(0, Math.min(1, v));
}

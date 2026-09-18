import { useMemo } from 'react';
import PropTypes from 'prop-types';
import { useAnimatedNumber } from '@/hooks/useAnimatedNumber.js';
import {
  EVIDENCE_FAMILIES,
  profileKind,
  profileLabel,
  readFamily,
  WEIGHT_PROFILES,
} from '@/lib/evidenceMeta.js';
import { getSmoothedRisk } from '@/contracts';
import { palette, riskColour, riskRamp } from '@/theme.js';

const FAMILY_LABEL = Object.freeze({
  voice: 'VOICE',
  channel: 'CHANNEL',
  prosody: 'PROSODY',
  linguistic: 'LINGUISTIC',
  transaction: 'TRANSACTION',
  relationship: 'RELATIONSHIP',
});

/**
 * Contribution waterfall — weight × score bars ordered by contribution.
 * Legible from the back of the room; sums toward the gauge value.
 *
 * @param {Object} props
 * @param {TelemetryFrame | null | undefined} props.frame
 * @param {string} [props.channelProfile]
 * @param {string | null} [props.highlightedFamily]
 * @param {string} [props.className]
 */
export function ContributionWaterfall({
  frame,
  channelProfile = 'WEBRTC_WIDEBAND',
  highlightedFamily = null,
  className = '',
}) {
  const kind = profileKind(channelProfile);
  const baseWeights = WEIGHT_PROFILES[kind];
  const label = profileLabel(kind);

  const rows = useMemo(() => {
    const list = EVIDENCE_FAMILIES.map((family) => {
      const f = readFamily(frame, family);
      return {
        family,
        ...f,
        baseWeight: baseWeights[family] ?? 0,
      };
    });
    list.sort((a, b) => b.contribution - a.contribution);
    return list;
  }, [frame, baseWeights]);

  const sumContribution = rows
    .filter((r) => r.available)
    .reduce((s, r) => s + r.contribution, 0);
  const gauge = getSmoothedRisk(frame);
  const animSum = useAnimatedNumber(sumContribution);
  const animGauge = useAnimatedNumber(gauge);
  const maxContrib = Math.max(0.01, ...rows.map((r) => r.contribution));

  return (
    <div className={`flex h-full min-h-0 flex-col gap-2 ${className}`}>
      <div className="flex flex-wrap items-center justify-between gap-1">
        <ProfileBadge kind={kind} label={label} weights={baseWeights} />
        <p className="font-mono text-[10px] tabular-nums text-sv-muted">
          Σ contrib{' '}
          <span className="text-sv-fg">{animSum.toFixed(2)}</span>
          <span className="mx-1 opacity-50">→</span>
          gauge{' '}
          <span style={{ color: riskColour(animGauge) }}>
            {(animGauge * 100).toFixed(0)}%
          </span>
        </p>
      </div>

      <ul className="flex min-h-0 flex-1 flex-col justify-center gap-1.5">
        {rows.map((row) => (
          <WaterfallRow
            key={row.family}
            row={row}
            maxContrib={maxContrib}
            highlighted={highlightedFamily === row.family}
          />
        ))}
      </ul>
    </div>
  );
}

ContributionWaterfall.propTypes = {
  frame: PropTypes.object,
  channelProfile: PropTypes.string,
  highlightedFamily: PropTypes.string,
  className: PropTypes.string,
};

/**
 * @param {Object} props
 * @param {'wideband'|'narrowband'} props.kind
 * @param {string} props.label
 * @param {Record<string, number>} props.weights
 */
function ProfileBadge({ kind, label, weights }) {
  const voiceW = useAnimatedNumber(weights.voice ?? 0);
  const lingW = useAnimatedNumber(weights.linguistic ?? 0);
  return (
    <div
      className="max-w-[14rem] rounded border px-2 py-1"
      style={{
        borderColor: kind === 'narrowband' ? `${riskRamp.watch}88` : `${palette.accent}66`,
        background:
          kind === 'narrowband' ? `${riskRamp.watch}14` : `${palette.accent}12`,
      }}
      title={`voice w=${voiceW.toFixed(2)} · linguistic w=${lingW.toFixed(2)}`}
    >
      <p
        className="font-mono text-[9px] font-semibold uppercase tracking-wide"
        style={{ color: kind === 'narrowband' ? riskRamp.watch : palette.accent }}
      >
        {kind === 'narrowband' ? 'Narrowband' : 'Wideband'} weights
      </p>
      <p className="text-[10px] leading-snug text-sv-fg">{label}</p>
      <p className="mt-0.5 font-mono text-[9px] text-sv-muted">
        voice {(voiceW * 100).toFixed(0)}% · ling {(lingW * 100).toFixed(0)}%
      </p>
    </div>
  );
}

ProfileBadge.propTypes = {
  kind: PropTypes.oneOf(['wideband', 'narrowband']).isRequired,
  label: PropTypes.string.isRequired,
  weights: PropTypes.object.isRequired,
};

/**
 * @param {Object} props
 * @param {{ family: string, score: number, weight: number, contribution: number, available: boolean, baseWeight: number }} props.row
 * @param {number} props.maxContrib
 * @param {boolean} props.highlighted
 */
function WaterfallRow({ row, maxContrib, highlighted }) {
  const animContrib = useAnimatedNumber(row.available ? row.contribution : 0);
  const animWeight = useAnimatedNumber(row.available ? row.weight : row.baseWeight);
  const pct = Math.max(2, (animContrib / maxContrib) * 100);
  const barColor = row.available ? riskColour(row.score) : palette.border;

  return (
    <li
      className={`flex items-center gap-2 rounded px-1 py-0.5 ${
        highlighted ? 'bg-sv-accent/10 ring-1 ring-sv-accent/40' : ''
      }`}
    >
      <span
        className="w-[5.5rem] shrink-0 font-mono text-[10px] font-semibold tracking-wide"
        style={{ color: row.available ? palette.fg : palette.muted }}
      >
        {FAMILY_LABEL[row.family] ?? row.family}
        {!row.available ? (
          <span className="ml-1 text-[8px] text-sv-muted">n/a</span>
        ) : null}
      </span>
      <div className="relative h-5 min-w-0 flex-1 overflow-hidden rounded bg-sv-bg">
        {row.available ? (
          <div
            className="absolute inset-y-0 left-0 rounded transition-[width] duration-300 ease-out"
            style={{
              width: `${pct}%`,
              background: barColor,
              opacity: 0.85,
            }}
          />
        ) : (
          <div
            className="absolute inset-0 opacity-40"
            style={{
              backgroundImage: `repeating-linear-gradient(-45deg, transparent, transparent 3px, ${palette.border} 3px, ${palette.border} 5px)`,
            }}
          />
        )}
        <span className="relative z-[1] flex h-full items-center px-1.5 font-mono text-[9px] tabular-nums text-sv-fg mix-blend-difference">
          {row.available
            ? `w ${animWeight.toFixed(2)} × s ${row.score.toFixed(2)} = ${animContrib.toFixed(3)}`
            : 'unavailable — not scored as zero'}
        </span>
      </div>
    </li>
  );
}

WaterfallRow.propTypes = {
  row: PropTypes.object.isRequired,
  maxContrib: PropTypes.number.isRequired,
  highlighted: PropTypes.bool,
};

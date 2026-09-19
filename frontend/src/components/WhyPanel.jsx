import { useMemo } from 'react';
import PropTypes from 'prop-types';
import { ContributionWaterfall } from '@/components/ContributionWaterfall.jsx';
import { ReasonsList } from '@/components/ReasonsList.jsx';
import { readFamily } from '@/lib/evidenceMeta.js';

/** §30 judge-readable buckets (maps fusion families → four lines). */
const WHY_BUCKETS = Object.freeze([
  { id: 'voice', label: 'Voice', families: ['voice'] },
  { id: 'conversation', label: 'Conversation', families: ['linguistic', 'prosody'] },
  { id: 'context', label: 'Context', families: ['transaction', 'relationship'] },
  { id: 'channel', label: 'Channel', families: ['channel'] },
]);

/**
 * Explainable WHY panel — contribution waterfall (§30) + reason codes.
 *
 * @param {Object} props
 * @param {import('@/contracts').TelemetryFrame | null | undefined} props.frame
 * @param {string} [props.channelProfile]
 * @param {string | null} [props.highlightedFamily]
 */
export function WhyPanel({ frame, channelProfile, highlightedFamily = null }) {
  const buckets = useMemo(() => {
    return WHY_BUCKETS.map((b) => {
      let contribution = 0;
      let any = false;
      for (const fam of b.families) {
        const f = readFamily(frame, fam);
        if (f.available) {
          any = true;
          contribution += f.contribution;
        }
      }
      return { ...b, contribution, available: any };
    });
  }, [frame]);

  const maxC = Math.max(0.01, ...buckets.map((b) => b.contribution));

  return (
    <div className="flex h-full min-h-0 flex-col gap-2 p-2">
      <p className="font-mono text-[10px] uppercase tracking-wider text-sv-muted">
        Why this risk — family contributions
      </p>
      <ul className="flex shrink-0 flex-col gap-1" aria-label="Contribution summary">
        {buckets.map((b) => (
          <li key={b.id} className="flex items-center gap-2 font-mono text-[10px]">
            <span className={`w-24 shrink-0 uppercase tracking-wide ${b.available ? 'text-sv-fg' : 'text-sv-muted'}`}>
              {b.label}
            </span>
            <div className="h-1.5 min-w-0 flex-1 overflow-hidden rounded bg-sv-bg">
              <div
                className={`h-full rounded ${b.available ? 'bg-sv-accent' : 'bg-sv-border'}`}
                style={{ width: `${Math.round((b.contribution / maxC) * 100)}%` }}
              />
            </div>
            <span className="w-10 shrink-0 tabular-nums text-right text-sv-muted">
              {b.available ? b.contribution.toFixed(2) : '—'}
            </span>
          </li>
        ))}
      </ul>
      <div className="min-h-0 flex-1 overflow-auto">
        <ContributionWaterfall
          frame={frame}
          channelProfile={channelProfile}
          highlightedFamily={highlightedFamily}
        />
      </div>
      <div className="shrink-0 border-t border-sv-border pt-2">
        <ReasonsList frame={frame} />
      </div>
    </div>
  );
}

WhyPanel.propTypes = {
  frame: PropTypes.object,
  channelProfile: PropTypes.string,
  highlightedFamily: PropTypes.string,
};

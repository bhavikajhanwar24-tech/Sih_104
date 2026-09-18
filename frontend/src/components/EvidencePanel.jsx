import PropTypes from 'prop-types';
import { ContributionWaterfall } from '@/components/ContributionWaterfall.jsx';
import { FamilyRadar } from '@/components/FamilyRadar.jsx';
import { useSession } from '@/context/SessionContext.jsx';

/**
 * Explainability panel — radar + contribution waterfall (Context §8.2 / §9.1).
 *
 * @param {Object} props
 * @param {TelemetryFrame | null | undefined} props.frame
 * @param {string} [props.channelProfile]
 * @param {string} [props.className]
 */
export function EvidencePanel({ frame, channelProfile, className = '' }) {
  const { highlightedFamily, channelProfile: sessionProfile } = useSession();
  const profile = channelProfile ?? sessionProfile;

  return (
    <div
      className={`grid h-full min-h-0 grid-cols-1 gap-2 lg:grid-cols-[minmax(0,11rem)_minmax(0,1fr)] ${className}`}
    >
      <div className="flex min-h-[10rem] items-center justify-center lg:min-h-0">
        <FamilyRadar
          frame={frame}
          highlightedFamily={highlightedFamily}
          className="max-h-full max-w-full"
        />
      </div>
      <ContributionWaterfall
        frame={frame}
        channelProfile={profile}
        highlightedFamily={highlightedFamily}
        className="min-h-0"
      />
    </div>
  );
}

EvidencePanel.propTypes = {
  frame: PropTypes.object,
  channelProfile: PropTypes.string,
  className: PropTypes.string,
};

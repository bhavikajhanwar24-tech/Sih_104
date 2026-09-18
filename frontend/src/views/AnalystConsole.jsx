import PropTypes from 'prop-types';
import { IdentityCard } from '@/components/IdentityCard.jsx';
import { MicControl } from '@/components/MicControl.jsx';
import { ReasonsList } from '@/components/ReasonsList.jsx';
import { RiskGauge } from '@/components/RiskGauge.jsx';
import { RiskTimeline } from '@/components/RiskTimeline.jsx';
import { SessionControl } from '@/components/SessionControl.jsx';
import { Panel } from '@/components/ui/Panel.jsx';
import { useSession } from '@/context/SessionContext.jsx';
import { useTelemetrySocket } from '@/hooks/useTelemetrySocket.js';

/**
 * Analyst operations view — named grid slots for later widgets.
 *
 * Layout (1280×720 safe):
 *   [ identity ][ risk gauge ][ intervention ]
 *   [ spectrogram     ][ evidence panel      ]
 *   [ live transcript ][ reasons list        ]
 */
export function AnalystConsole() {
  const { sessionId, isRunning, sessionError, highlightedFamily } = useSession();
  const { latest, history, error: telemetryError } = useTelemetrySocket(sessionId);

  const hasFrame = latest != null;
  const bootStatus = !isRunning
    ? 'empty'
    : sessionError || telemetryError
      ? 'error'
      : hasFrame
        ? 'ready'
        : 'empty';

  const emptyMsg = !isRunning ? 'Start a session to begin' : 'waiting for audio';
  const errMsg = sessionError || telemetryError || 'Telemetry error';

  return (
    <div className="flex h-full min-h-0 min-w-0 flex-col gap-2 p-2 md:gap-3 md:p-3">
      <div className="flex shrink-0 flex-col gap-2 lg:flex-row">
        <SessionControl className="min-w-0 flex-1" />
        {sessionId ? (
          <div className="w-full shrink-0 lg:w-72">
            <MicControl sessionId={sessionId} />
          </div>
        ) : null}
      </div>

      <div className="grid min-h-0 flex-1 auto-rows-min grid-cols-12 gap-2 md:gap-3">
        <Panel
          title="Identity"
          slot="identity"
          status={bootStatus}
          emptyMessage={emptyMsg}
          errorMessage={errMsg}
          className="col-span-12 h-[16rem] sm:col-span-4 sm:h-[18rem]"
        >
          <IdentityCard frame={latest} />
        </Panel>

        <Panel
          title="Risk"
          slot="risk"
          status={bootStatus === 'error' ? 'error' : hasFrame ? 'ready' : bootStatus}
          emptyMessage={emptyMsg}
          errorMessage={errMsg}
          className="col-span-12 h-[18rem] sm:col-span-4"
          variant="flush"
        >
          <div className="flex h-full flex-col items-center justify-between gap-1 p-2">
            <RiskGauge frame={latest} />
            <RiskTimeline history={history} className="w-full shrink-0 px-1" />
          </div>
        </Panel>

        <Panel
          title="Intervention"
          slot="intervention"
          status={bootStatus}
          emptyMessage={emptyMsg}
          errorMessage={errMsg}
          className="col-span-12 h-[16rem] sm:col-span-4 sm:h-[18rem]"
        >
          <PlaceholderBody
            lines={[
              latest?.intervention?.level ?? 'LEVEL_1_SILENT',
              `dwell ${latest?.intervention?.dwellRemainingMs ?? 0} ms`,
              `prev ${latest?.intervention?.previousLevel ?? '—'}`,
            ]}
          />
        </Panel>

        <Panel
          title="Spectrogram"
          slot="spectrogram"
          status={bootStatus}
          emptyMessage={emptyMsg}
          errorMessage={errMsg}
          className="col-span-12 h-[12rem] md:col-span-7"
        >
          <PlaceholderBody lines={['Spectrogram canvas — P6.x']} />
        </Panel>

        <Panel
          title="Evidence"
          slot="evidence"
          status={bootStatus}
          emptyMessage={emptyMsg}
          errorMessage={errMsg}
          className="col-span-12 h-[12rem] md:col-span-5"
        >
          <PlaceholderBody
            lines={[
              highlightedFamily
                ? `Axis highlight → ${highlightedFamily} (P6.3)`
                : 'Evidence radar / waterfall — P6.3',
              'Click a reason to highlight its family',
            ]}
          />
        </Panel>

        <Panel
          title="Live transcript"
          slot="transcript"
          status={bootStatus}
          emptyMessage={emptyMsg}
          errorMessage={errMsg}
          className="col-span-12 h-[10rem] md:col-span-7"
        >
          <PlaceholderBody
            lines={[
              latest?.transcriptDelta?.text
                ? latest.transcriptDelta.text
                : 'No transcript delta yet',
            ]}
          />
        </Panel>

        <Panel
          title="Reasons"
          slot="reasons"
          status={bootStatus}
          emptyMessage={emptyMsg}
          errorMessage={errMsg}
          className="col-span-12 h-[10rem] md:col-span-5"
          variant="flush"
        >
          <div className="p-2">
            <ReasonsList frame={latest} />
          </div>
        </Panel>
      </div>
    </div>
  );
}

/**
 * Temporary body until dedicated widgets land.
 *
 * @param {Object} props
 * @param {string[]} props.lines
 */
function PlaceholderBody({ lines }) {
  return (
    <ul className="space-y-1 font-mono text-[11px] tabular-nums text-sv-fg">
      {lines.map((line) => (
        <li key={line} className="truncate text-sv-muted">
          {line}
        </li>
      ))}
    </ul>
  );
}

PlaceholderBody.propTypes = {
  lines: PropTypes.arrayOf(PropTypes.string).isRequired,
};

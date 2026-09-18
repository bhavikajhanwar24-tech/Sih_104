import { useState } from 'react';
import PropTypes from 'prop-types';
import { EvidencePanel } from '@/components/EvidencePanel.jsx';
import { IdentityCard } from '@/components/IdentityCard.jsx';
import { InterventionBar } from '@/components/InterventionBar.jsx';
import { MicControl } from '@/components/MicControl.jsx';
import { OverrideDialog } from '@/components/OverrideDialog.jsx';
import { ReasonsList } from '@/components/ReasonsList.jsx';
import { RiskGauge } from '@/components/RiskGauge.jsx';
import { RiskTimeline } from '@/components/RiskTimeline.jsx';
import { SessionControl } from '@/components/SessionControl.jsx';
import { SpectrogramCanvas } from '@/components/SpectrogramCanvas.jsx';
import { SupervisorAlert } from '@/components/SupervisorAlert.jsx';
import { TransactionPanel } from '@/components/TransactionPanel.jsx';
import { Panel } from '@/components/ui/Panel.jsx';
import { useSession } from '@/context/SessionContext.jsx';
import { useTelemetrySocket } from '@/hooks/useTelemetrySocket.js';
import { INTERVENTION_LEVELS } from '@/contracts';

/**
 * Analyst operations view — named grid slots for later widgets.
 *
 * Layout (1280×720 safe):
 *   [ identity ][ risk gauge ][ intervention ]
 *   [ spectrogram     ][ evidence panel      ]
 *   [ live transcript ][ reasons | txn       ]
 */
export function AnalystConsole() {
  const {
    sessionId,
    isRunning,
    sessionError,
    scenarioId,
    awaitingSip,
    channelProfile,
  } = useSession();
  const { latest, history, error: telemetryError } = useTelemetrySocket(sessionId);
  const [overrideOpen, setOverrideOpen] = useState(false);

  const hasFrame = latest != null;
  const bootStatus = !isRunning
    ? 'empty'
    : sessionError || telemetryError
      ? 'error'
      : hasFrame
        ? 'ready'
        : 'empty';

  const emptyMsg = !isRunning
    ? 'Start a session to begin'
    : awaitingSip
      ? 'Waiting for SIP call… dial 1002 from softphone'
      : 'waiting for audio';
  const errMsg = sessionError || telemetryError || 'Telemetry error';
  const showMic = Boolean(sessionId) && scenarioId === 'live-browser';

  async function postRelease() {
    if (!sessionId) return;
    const reason = window.prompt(
      'Release hold — mandatory audit reason (≥10 chars):',
      'Supervisor released auto-hold after review',
    );
    if (!reason || reason.trim().length < 10) return;
    await fetch(`/api/v1/intervention/${encodeURIComponent(sessionId)}/release`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ analystId: 'supervisor-demo', reason: reason.trim() }),
    });
  }

  return (
    <div className="relative flex h-full min-h-0 min-w-0 flex-col gap-2 p-2 md:gap-3 md:p-3">
      <div className="flex shrink-0 flex-col gap-2 lg:flex-row">
        <SessionControl className="min-w-0 flex-1" />
        {showMic ? (
          <div className="w-full shrink-0 lg:w-72">
            <MicControl sessionId={sessionId} autoStart />
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
          status={bootStatus === 'error' ? 'error' : isRunning ? 'ready' : 'empty'}
          emptyMessage={emptyMsg}
          errorMessage={errMsg}
          className="col-span-12 h-[11rem] sm:col-span-4 sm:h-[18rem]"
          variant="flush"
        >
          <div className="h-full p-2">
            <InterventionBar
              frame={latest}
              onOverrideClick={sessionId ? () => setOverrideOpen(true) : undefined}
            />
          </div>
        </Panel>

        <Panel
          title="Spectrogram"
          slot="spectrogram"
          status={isRunning ? 'ready' : 'empty'}
          emptyMessage={emptyMsg}
          errorMessage={errMsg}
          className="col-span-12 h-[14rem] md:col-span-6"
          variant="flush"
        >
          <SpectrogramCanvas channelProfile={channelProfile} className="h-full" />
        </Panel>

        <Panel
          title="Evidence"
          slot="evidence"
          status={bootStatus === 'error' ? 'error' : hasFrame ? 'ready' : bootStatus}
          emptyMessage={emptyMsg}
          errorMessage={errMsg}
          className="col-span-12 h-[16rem] md:col-span-6"
          variant="flush"
        >
          <div className="h-full overflow-auto p-2">
            <EvidencePanel frame={latest} channelProfile={channelProfile} />
          </div>
        </Panel>

        <Panel
          title="Live transcript"
          slot="transcript"
          status={bootStatus}
          emptyMessage={emptyMsg}
          errorMessage={errMsg}
          className="col-span-12 h-[10rem] md:col-span-5"
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
          className="col-span-12 h-[10rem] md:col-span-3"
          variant="flush"
        >
          <div className="p-2">
            <ReasonsList frame={latest} />
          </div>
        </Panel>

        <Panel
          title="Transaction"
          slot="transaction"
          status={isRunning ? 'ready' : 'empty'}
          emptyMessage={emptyMsg}
          errorMessage={errMsg}
          className="col-span-12 h-[10rem] md:col-span-4"
          variant="flush"
        >
          <div className="h-full overflow-auto p-2">
            <TransactionPanel sessionId={sessionId} frame={latest} />
          </div>
        </Panel>
      </div>

      <SupervisorAlert
        frame={latest}
        sessionId={sessionId}
        onAccept={() => {
          /* hold acknowledged — lock remains server-side */
        }}
        onRelease={() => {
          void postRelease();
        }}
      />

      {sessionId ? (
        <OverrideDialog
          open={overrideOpen}
          onClose={() => setOverrideOpen(false)}
          sessionId={sessionId}
          currentLevel={latest?.intervention?.level ?? INTERVENTION_LEVELS.LEVEL_1_SILENT}
        />
      ) : null}
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

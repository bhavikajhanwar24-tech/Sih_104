import { useEffect, useState } from 'react';
import { ChallengePanel } from '@/components/ChallengePanel.jsx';
import { CrossChannelTimeline } from '@/components/CrossChannelTimeline.jsx';
import { DemoStatusStrip } from '@/components/DemoStatusStrip.jsx';
import { EvidencePanel } from '@/components/EvidencePanel.jsx';
import { IdentityCard } from '@/components/IdentityCard.jsx';
import { InterventionBar } from '@/components/InterventionBar.jsx';
import { MicControl } from '@/components/MicControl.jsx';
import { OverrideDialog } from '@/components/OverrideDialog.jsx';
import { RiskGauge } from '@/components/RiskGauge.jsx';
import { RiskTimeline } from '@/components/RiskTimeline.jsx';
import { SessionControl } from '@/components/SessionControl.jsx';
import { SpectrogramCanvas } from '@/components/SpectrogramCanvas.jsx';
import { SupervisorAlert } from '@/components/SupervisorAlert.jsx';
import { ForensicDossierViewer } from '@/components/compliance/ForensicDossierViewer.jsx';
import { TranscriptPanel } from '@/components/TranscriptPanel.jsx';
import { TransactionPanel } from '@/components/TransactionPanel.jsx';
import { WhyPanel } from '@/components/WhyPanel.jsx';
import { Card } from '@/components/ui/Card.jsx';
import { Panel } from '@/components/ui/Panel.jsx';
import { useSession } from '@/context/SessionContext.jsx';
import { useTelemetrySocket } from '@/hooks/useTelemetrySocket.js';
import { INTERVENTION_LEVELS } from '@/contracts';
import { apiFetch } from '@/services/api.js';

/**
 * Analyst operations view — named grid slots for later widgets.
 */
export function AnalystConsole() {
  const {
    sessionId,
    isRunning,
    sessionError,
    scenarioId,
    awaitingSip,
    channelProfile,
    startedAtMs,
    highlightedFamily,
  } = useSession();
  const { latest, history, error: telemetryError } = useTelemetrySocket(sessionId);
  const [overrideOpen, setOverrideOpen] = useState(false);
  const [holdAccepted, setHoldAccepted] = useState(false);

  useEffect(() => {
    setHoldAccepted(false);
  }, [sessionId, latest?.intervention?.level]);

  const hasFrame = latest != null;
  const bootStatus = !isRunning
    ? 'empty'
    : sessionError || telemetryError
      ? 'error'
      : hasFrame
        ? 'ready'
        : 'empty';

  const emptyMsg = !isRunning
    ? 'Pick Live SIP or a fixture scenario, then Start'
    : awaitingSip
      ? 'Waiting for SIP call… dial 1002 from softphone (caller → agent)'
      : 'waiting for FeatureFrames from ml-engine';
  const errMsg = sessionError || telemetryError || 'Telemetry error';
  const showMic = Boolean(sessionId) && scenarioId === 'live-browser';
  const spectroHint =
    scenarioId === 'pstn-narrowband'
      ? 'Spectrogram needs browser mic — Live SIP drives the gauge from Asterisk audio'
      : scenarioId !== 'live-browser'
        ? 'Server WAV replay drives the gauge; switch to Live browser mic for spectrogram'
        : null;

  async function postAcceptHold() {
    if (!sessionId) return;
    setHoldAccepted(true);
    try {
      const res = await apiFetch(`/api/v1/actuation/${encodeURIComponent(sessionId)}/hold`, {
        method: 'POST',
      });
      if (!res.ok) {
        console.warn('force hold failed', res.status, await res.text());
      }
    } catch (err) {
      console.warn('force hold error', err);
    }
  }

  async function postRelease() {
    if (!sessionId) return;
    const reason = window.prompt(
      'Release hold — mandatory audit reason (≥10 chars):',
      'Supervisor released auto-hold after review',
    );
    if (!reason || reason.trim().length < 10) return;
    await apiFetch(`/api/v1/intervention/${encodeURIComponent(sessionId)}/release`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ analystId: 'supervisor-demo', reason: reason.trim() }),
    });
  }

  return (
    <div className="relative flex h-full min-h-0 min-w-0 flex-col gap-2 p-2 md:gap-3 md:p-3">
      <div className="flex shrink-0 flex-col gap-2 lg:flex-row lg:items-stretch">
        <SessionControl className="min-w-0 flex-1" />
        {showMic ? (
          <div className="w-full shrink-0 lg:w-72">
            <MicControl sessionId={sessionId} autoStart />
          </div>
        ) : null}
      </div>

      <DemoStatusStrip frame={latest} isRunning={isRunning} className="shrink-0" />

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
          className="relative col-span-12 h-[14rem] md:col-span-6"
          variant="flush"
        >
          {spectroHint ? (
            <div className="absolute z-10 m-2 max-w-[90%] rounded bg-sv-bg/80 px-2 py-1 font-mono text-[10px] text-sv-muted">
              {spectroHint}
            </div>
          ) : null}
          <SpectrogramCanvas
            channelProfile={channelProfile}
            frame={latest}
            className="h-full"
          />
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
          title="Cross-channel"
          slot="cross-channel"
          status={isRunning ? 'ready' : 'empty'}
          emptyMessage={emptyMsg}
          errorMessage={errMsg}
          className="col-span-12 h-[7.5rem]"
          variant="flush"
        >
          <CrossChannelTimeline
            sessionId={sessionId}
            callStartedAtMs={startedAtMs ?? undefined}
            fixtureSeeded={Boolean(scenarioId) && scenarioId !== 'live-browser' && scenarioId !== 'pstn-narrowband'}
            className="h-full"
          />
        </Panel>

        <Panel
          title="Liveness challenge"
          slot="challenge"
          status={isRunning ? 'ready' : 'empty'}
          emptyMessage={emptyMsg}
          errorMessage={errMsg}
          className="col-span-12 h-[22rem] md:col-span-5"
          variant="flush"
        >
          <ChallengePanel sessionId={sessionId} />
        </Panel>

        <Panel
          title="Live transcript"
          slot="transcript"
          status={bootStatus}
          emptyMessage={emptyMsg}
          errorMessage={errMsg}
          className="col-span-12 h-[10rem] md:col-span-5"
          variant="flush"
        >
          <TranscriptPanel frame={latest} sessionId={sessionId} />
        </Panel>

        <Panel
          title="Why / reasons"
          slot="reasons"
          status={bootStatus}
          emptyMessage={emptyMsg}
          errorMessage={errMsg}
          className="col-span-12 h-[16rem] md:col-span-3"
          variant="flush"
        >
          <WhyPanel
            frame={latest}
            channelProfile={channelProfile}
            highlightedFamily={highlightedFamily}
          />
        </Panel>

        <Panel
          title="Transaction"
          slot="transaction"
          status={isRunning ? 'ready' : 'empty'}
          emptyMessage={emptyMsg}
          errorMessage={errMsg}
          className="col-span-12 h-[16rem] md:col-span-4"
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
        accepted={holdAccepted}
        onAccept={() => {
          void postAcceptHold();
        }}
        onRelease={() => {
          setHoldAccepted(false);
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

      <div className="shrink-0 px-1 pb-2">
        <Card title="Forensic dossier" variant="elevated">
          <ForensicDossierViewer initialSessionId={sessionId} />
        </Card>
      </div>
    </div>
  );
}

AnalystConsole.propTypes = {};

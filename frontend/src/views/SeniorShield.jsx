import { useEffect, useMemo, useState } from 'react';
import { BigAlert } from '@/components/senior/BigAlert.jsx';
import { LanguageToggle } from '@/components/senior/LanguageToggle.jsx';
import { SimpleStatus } from '@/components/senior/SimpleStatus.jsx';
import { SosButton } from '@/components/senior/SosButton.jsx';
import {
  shieldStateFromFrame,
  t,
} from '@/components/senior/shieldCopy.js';
import { useSession } from '@/context/SessionContext.jsx';
import { useTelemetrySocket } from '@/hooks/useTelemetrySocket.js';

const SPEECH_LANG = Object.freeze({
  en: 'en-IN',
  hi: 'hi-IN',
  ta: 'ta-IN',
  te: 'te-IN',
});

const SEVERITY = Object.freeze({ normal: 0, caution: 1, danger: 2 });

/**
 * @param {'normal'|'caution'|'danger'} a
 * @param {'normal'|'caution'|'danger'} b
 */
function worseState(a, b) {
  return SEVERITY[a] >= SEVERITY[b] ? a : b;
}

/** Friendly clock for SOS copy — not a risk metric. */
function friendlyClock() {
  try {
    return new Intl.DateTimeFormat(undefined, {
      hour: 'numeric',
      minute: '2-digit',
    }).format(new Date());
  } catch {
    return 'this afternoon';
  }
}

/**
 * Senior Shield — high-contrast protective UI for elderly / vulnerable users
 * (Context §7.4 / Scenario 4). Completely different visual language from the analyst console.
 */
export function SeniorShield() {
  const { sessionId, isRunning, startSession, setScenarioId, scenarioId } = useSession();
  const { latest } = useTelemetrySocket(sessionId);

  const [lang, setLang] = useState(scenarioId === 'grandparent-scam' ? 'hi' : 'en');
  /** Scenario 4 fixture trajectory when live fusion has not yet escalated. */
  const [demoPhase, setDemoPhase] = useState(/** @type {0|1|2} */ (0));

  useEffect(() => {
    if (scenarioId === 'grandparent-scam') setLang('hi');
  }, [scenarioId]);

  useEffect(() => {
    if (scenarioId !== 'grandparent-scam' || !isRunning) {
      setDemoPhase(0);
      return undefined;
    }
    const cautionAt = window.setTimeout(() => setDemoPhase(1), 3500);
    const dangerAt = window.setTimeout(() => setDemoPhase(2), 9000);
    return () => {
      window.clearTimeout(cautionAt);
      window.clearTimeout(dangerAt);
    };
  }, [scenarioId, isRunning]);

  const liveState = useMemo(() => shieldStateFromFrame(latest), [latest]);
  const demoState = demoPhase === 2 ? 'danger' : demoPhase === 1 ? 'caution' : 'normal';
  const state = worseState(liveState, demoState);
  const danger = state === 'danger';

  const statusTitle =
    state === 'danger'
      ? t(lang, 'statusDanger')
      : state === 'caution'
        ? t(lang, 'statusCaution')
        : t(lang, 'statusNormal');
  const statusHint =
    state === 'danger'
      ? t(lang, 'statusHintDanger')
      : state === 'caution'
        ? t(lang, 'statusHintCaution')
        : t(lang, 'statusHintNormal');

  const sosBody = t(lang, 'sosConfirmBody', { time: friendlyClock() });

  async function onStart() {
    setScenarioId('grandparent-scam');
    setLang('hi');
    if (!isRunning) {
      await startSession();
    }
  }

  return (
    <div
      className="senior-shield-root relative min-h-full w-full bg-[#f5f5f0] text-[#1a1a1a]"
      data-testid="senior-shield"
    >
      <div className="mx-auto flex min-h-full w-full max-w-[390px] flex-col gap-4 px-4 py-5">
        <header className="text-center">
          <h1 className="text-[32px] font-black leading-tight text-[#0b3d91]">
            {t(lang, 'appTitle')}
          </h1>
          <p className="mt-1 text-[20px] font-medium text-[#333]">
            {isRunning ? t(lang, 'listening') : t(lang, 'waiting')}
          </p>
        </header>

        <aside
          className="rounded-2xl border-4 border-[#0b3d91] bg-[#e3f2fd] px-4 py-4 text-center"
          role="note"
        >
          <p className="text-[22px] font-bold leading-snug text-[#0b3d91]">
            {t(lang, 'safeWordBanner')}
          </p>
        </aside>

        <LanguageToggle value={lang} onChange={setLang} label={t(lang, 'language')} />

        <SimpleStatus state={state} title={statusTitle} hint={statusHint} />

        {!isRunning ? (
          <button
            type="button"
            onClick={onStart}
            className="min-h-[72px] w-full rounded-2xl border-4 border-[#064d1d] bg-[#0a7a2f] px-4 text-[26px] font-black text-white"
          >
            {t(lang, 'startCall')}
          </button>
        ) : null}

        <SosButton
          label={t(lang, 'sosLabel')}
          confirmTitle={t(lang, 'sosConfirmTitle')}
          confirmBody={sosBody}
          contactLine={t(lang, 'sosContact')}
        />
      </div>

      <BigAlert
        open={danger}
        title={t(lang, 'alertTitle')}
        body={t(lang, 'alertBody')}
        instruction={t(lang, 'alertInstruction')}
        spokenText={t(lang, 'alertSpoken')}
        lang={SPEECH_LANG[lang] ?? 'en-IN'}
      />
    </div>
  );
}

SeniorShield.propTypes = {};

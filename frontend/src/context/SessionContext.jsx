import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import PropTypes from 'prop-types';
import { CHANNEL_PROFILES } from '@/contracts';
import { SEED_SCENARIOS } from '@/theme.js';

/** Softphone / AudioSocket path — attach to Decision Plane session opened by the bridge. */
export const SIP_SCENARIO_ID = 'pstn-narrowband';

/**
 * @typedef {Object} SessionContextValue
 * @property {string | null} sessionId
 * @property {boolean} isRunning
 * @property {boolean} awaitingSip  true while listening for a SIP/AudioSocket session
 * @property {string} channelProfile
 * @property {string} scenarioId
 * @property {number | null} startedAtMs
 * @property {string | null} highlightedReasonCode
 * @property {string | null} highlightedFamily   evidence radar axis (P6.3)
 * @property {string | null} sessionError
 * @property {(profile: string) => void} setChannelProfile
 * @property {(scenarioId: string) => void} setScenarioId
 * @property {(code: string | null, family?: string | null) => void} setHighlightedReason
 * @property {() => Promise<void>} startSession
 * @property {() => Promise<void>} stopSession
 */

const SessionContext = createContext(/** @type {SessionContextValue | null} */ (null));

/**
 * Shared session + highlight state — avoid prop-drilling more than two levels.
 *
 * @param {Object} props
 * @param {React.ReactNode} props.children
 */
export function SessionProvider({ children }) {
  const [sessionId, setSessionId] = useState(/** @type {string | null} */ (null));
  const [isRunning, setIsRunning] = useState(false);
  const [awaitingSip, setAwaitingSip] = useState(false);
  const [channelProfile, setChannelProfile] = useState(CHANNEL_PROFILES.WEBRTC_WIDEBAND);
  const [scenarioId, setScenarioIdState] = useState(SEED_SCENARIOS[0].id);
  const [startedAtMs, setStartedAtMs] = useState(/** @type {number | null} */ (null));
  const [highlightedReasonCode, setHighlightedReasonCode] = useState(
    /** @type {string | null} */ (null),
  );
  const [highlightedFamily, setHighlightedFamily] = useState(
    /** @type {string | null} */ (null),
  );
  const [sessionError, setSessionError] = useState(/** @type {string | null} */ (null));
  const sipOwnedRef = useRef(false);
  const knownAtListenRef = useRef(/** @type {Set<string>} */ (new Set()));

  const setScenarioId = useCallback((id) => {
    setScenarioIdState(id);
    if (id === SIP_SCENARIO_ID) {
      setChannelProfile(CHANNEL_PROFILES.PSTN_NARROWBAND);
    } else if (id === 'live-browser') {
      setChannelProfile(CHANNEL_PROFILES.WEBRTC_WIDEBAND);
    }
  }, []);

  const setHighlightedReason = useCallback((code, family = null) => {
    setHighlightedReasonCode(code);
    setHighlightedFamily(family ?? null);
  }, []);

  const stopSession = useCallback(async () => {
    const id = sessionId;
    const sipOwned = sipOwnedRef.current;
    setIsRunning(false);
    setAwaitingSip(false);
    setStartedAtMs(null);
    setHighlightedReason(null, null);
    setSessionError(null);
    setSessionId(null);
    sipOwnedRef.current = false;
    knownAtListenRef.current = new Set();
    // SIP sessions are owned by the AudioSocket bridge — UI only detaches.
    if (!id || sipOwned) return;
    try {
      await fetch(`/api/v1/session/${encodeURIComponent(id)}/close`, { method: 'POST' });
    } catch {
      /* UI already stopped; backend close is best-effort */
    }
  }, [sessionId, setHighlightedReason]);

  const startSession = useCallback(async () => {
    setSessionError(null);
    setHighlightedReason(null, null);

    if (scenarioId === SIP_SCENARIO_ID) {
      // Listen for a Decision Plane session created by gateway/asterisk_bridge.py
      try {
        const snap = await fetch('/api/v1/session');
        const body = snap.ok ? await snap.json().catch(() => ({})) : {};
        const existing = Array.isArray(body.sessions) ? body.sessions : [];
        knownAtListenRef.current = new Set(
          existing.map((s) => (s && typeof s.sessionId === 'string' ? s.sessionId : '')).filter(Boolean),
        );
      } catch {
        knownAtListenRef.current = new Set();
      }
      sipOwnedRef.current = true;
      setSessionId(null);
      setAwaitingSip(true);
      setIsRunning(true);
      setStartedAtMs(Date.now());
      return;
    }

    const id = `sv-${scenarioId}-${Date.now().toString(36)}`;
    sipOwnedRef.current = false;
    setAwaitingSip(false);
    try {
      const res = await fetch('/api/v1/session/start', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          schema: 'sentinelvoice.SessionStartRequest/1',
          sessionId: id,
          callerId: scenarioId === 'cfo-wire-inr' ? '+91-unreg-sip-unknown' : 'browser-agent',
          // Scenario 2: callee is Sunita Rao (EMP-50040) — cross-channel precursors target her.
          calleeId: scenarioId === 'cfo-wire-inr' ? '+91-22-6655-5040' : 'desk-1',
          channelProfile,
          scenarioId,
        }),
      });
      if (!res.ok) {
        const text = await res.text();
        throw new Error(`session start failed (${res.status}): ${text.slice(0, 160)}`);
      }
      const body = await res.json().catch(() => ({}));
      const resolvedId = typeof body.sessionId === 'string' ? body.sessionId : id;
      setSessionId(resolvedId);
      setStartedAtMs(Date.now());
      setIsRunning(true);
    } catch (err) {
      setSessionError(err instanceof Error ? err.message : 'session start failed');
      setIsRunning(false);
      setSessionId(null);
      setStartedAtMs(null);
    }
  }, [channelProfile, scenarioId, setHighlightedReason]);

  // Poll Decision Plane for a new SIP/AudioSocket session while awaiting.
  useEffect(() => {
    if (!awaitingSip || !isRunning) return undefined;

    let cancelled = false;
    const tick = async () => {
      try {
        const res = await fetch('/api/v1/session');
        if (!res.ok || cancelled) return;
        const body = await res.json().catch(() => ({}));
        const sessions = Array.isArray(body.sessions) ? body.sessions : [];
        const candidate = sessions.find((s) => {
          if (!s || typeof s.sessionId !== 'string') return false;
          if (knownAtListenRef.current.has(s.sessionId)) return false;
          return s.channelProfile === CHANNEL_PROFILES.PSTN_NARROWBAND;
        });
        if (!candidate || cancelled) return;
        setSessionId(candidate.sessionId);
        setAwaitingSip(false);
        setSessionError(null);
      } catch {
        /* keep polling */
      }
    };

    tick();
    const id = setInterval(tick, 1000);
    return () => {
      cancelled = true;
      clearInterval(id);
    };
  }, [awaitingSip, isRunning]);

  const value = useMemo(
    () => ({
      sessionId,
      isRunning,
      awaitingSip,
      channelProfile,
      scenarioId,
      startedAtMs,
      highlightedReasonCode,
      highlightedFamily,
      sessionError,
      setChannelProfile,
      setScenarioId,
      setHighlightedReason,
      startSession,
      stopSession,
    }),
    [
      sessionId,
      isRunning,
      awaitingSip,
      channelProfile,
      scenarioId,
      startedAtMs,
      highlightedReasonCode,
      highlightedFamily,
      sessionError,
      setScenarioId,
      setHighlightedReason,
      startSession,
      stopSession,
    ],
  );

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

SessionProvider.propTypes = {
  children: PropTypes.node.isRequired,
};

/**
 * @returns {SessionContextValue}
 */
export function useSession() {
  const ctx = useContext(SessionContext);
  if (!ctx) {
    throw new Error('useSession must be used within SessionProvider');
  }
  return ctx;
}

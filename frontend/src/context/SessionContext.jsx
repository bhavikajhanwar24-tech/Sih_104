import { createContext, useCallback, useContext, useMemo, useState } from 'react';
import PropTypes from 'prop-types';
import { CHANNEL_PROFILES } from '@/contracts';
import { SEED_SCENARIOS } from '@/theme.js';

/**
 * @typedef {Object} SessionContextValue
 * @property {string | null} sessionId
 * @property {boolean} isRunning
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
  const [channelProfile, setChannelProfile] = useState(CHANNEL_PROFILES.WEBRTC_WIDEBAND);
  const [scenarioId, setScenarioId] = useState(SEED_SCENARIOS[0].id);
  const [startedAtMs, setStartedAtMs] = useState(/** @type {number | null} */ (null));
  const [highlightedReasonCode, setHighlightedReasonCode] = useState(
    /** @type {string | null} */ (null),
  );
  const [highlightedFamily, setHighlightedFamily] = useState(
    /** @type {string | null} */ (null),
  );
  const [sessionError, setSessionError] = useState(/** @type {string | null} */ (null));

  const setHighlightedReason = useCallback((code, family = null) => {
    setHighlightedReasonCode(code);
    setHighlightedFamily(family ?? null);
  }, []);

  const startSession = useCallback(async () => {
    setSessionError(null);
    const id = `sv-${scenarioId}-${Date.now().toString(36)}`;
    try {
      const res = await fetch('/api/v1/session/start', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          schema: 'sentinelvoice.SessionStartRequest/1',
          sessionId: id,
          callerId: scenarioId === 'cfo-wire-inr' ? '+91-unreg-sip-unknown' : 'browser-agent',
          calleeId: 'desk-1',
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
      setHighlightedReason(null, null);
    } catch (err) {
      setSessionError(err instanceof Error ? err.message : 'session start failed');
      setIsRunning(false);
      setSessionId(null);
      setStartedAtMs(null);
    }
  }, [channelProfile, scenarioId, setHighlightedReason]);

  const stopSession = useCallback(async () => {
    const id = sessionId;
    setIsRunning(false);
    setStartedAtMs(null);
    setHighlightedReason(null, null);
    setSessionError(null);
    setSessionId(null);
    if (!id) return;
    try {
      await fetch(`/api/v1/session/${encodeURIComponent(id)}/close`, { method: 'POST' });
    } catch {
      /* UI already stopped; backend close is best-effort */
    }
  }, [sessionId, setHighlightedReason]);

  const value = useMemo(
    () => ({
      sessionId,
      isRunning,
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
      channelProfile,
      scenarioId,
      startedAtMs,
      highlightedReasonCode,
      highlightedFamily,
      sessionError,
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

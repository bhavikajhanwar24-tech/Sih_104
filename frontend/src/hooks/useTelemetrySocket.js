import { useEffect, useRef, useState } from 'react';
import {
  assertTelemetryFrame,
  getTelemetryValidationError,
} from '@/contracts';
import {
  CONNECTION_STATES,
  ensureConnected,
  getConnectionState,
  getLastError,
  onConnectionState,
  subscribe,
} from '@/services/stompClient.js';

const HISTORY_LIMIT = 300;

/**
 * @typedef {Object} TelemetrySocketApi
 * @property {TelemetryFrame | null} latest
 * @property {TelemetryFrame[]} history
 * @property {string} connectionState
 * @property {string | null} error
 * @property {string | null} lastValidationError
 */

/**
 * Sole boundary where Decision Plane telemetry enters React.
 * Topic: `/topic/tenant/{tenantId}/telemetry/{sessionId}` (F3).
 *
 * @param {string | null | undefined} sessionId
 * @param {string | null | undefined} [tenantId]
 * @returns {TelemetrySocketApi}
 */
export function useTelemetrySocket(sessionId, tenantId) {
  const [latest, setLatest] = useState(/** @type {TelemetryFrame | null} */ (null));
  const [history, setHistory] = useState(/** @type {TelemetryFrame[]} */ ([]));
  const [connectionState, setConnectionState] = useState(getConnectionState);
  const [error, setError] = useState(/** @type {string | null} */ (null));
  const [lastValidationError, setLastValidationError] = useState(
    /** @type {string | null} */ (null),
  );

  const sessionRef = useRef(sessionId);
  sessionRef.current = sessionId;

  useEffect(() => {
    const unlisten = onConnectionState((state) => {
      setConnectionState(state);
      if (state === CONNECTION_STATES.CONNECTED) {
        setError(null);
      } else if (state === CONNECTION_STATES.RECONNECTING || state === CONNECTION_STATES.DISCONNECTED) {
        const transportErr = getLastError();
        if (transportErr) setError(transportErr);
      }
    });
    ensureConnected();
    return unlisten;
  }, []);

  useEffect(() => {
    if (!sessionId || !tenantId) {
      setLatest(null);
      setHistory([]);
      setLastValidationError(null);
      return undefined;
    }

    setLatest(null);
    setHistory([]);
    setLastValidationError(null);
    setError(null);

    const destination = `/topic/tenant/${tenantId}/telemetry/${sessionId}`;
    const unsubscribe = subscribe(destination, (body) => {
      if (sessionRef.current !== sessionId) return;
      let parsed;
      try {
        parsed = JSON.parse(body);
      } catch (err) {
        setError(err instanceof Error ? err.message : 'Invalid telemetry JSON');
        return;
      }

      const validationError = getTelemetryValidationError(parsed);
      setLastValidationError(validationError);

      const frame = assertTelemetryFrame(/** @type {TelemetryFrame} */ (parsed));
      setLatest(frame);
      setHistory((prev) => {
        const next = prev.length >= HISTORY_LIMIT ? prev.slice(prev.length - HISTORY_LIMIT + 1) : prev.slice();
        next.push(frame);
        return next;
      });
    });

    return unsubscribe;
  }, [sessionId, tenantId]);

  return {
    latest,
    history,
    connectionState,
    error,
    lastValidationError,
  };
}

export { CONNECTION_STATES };

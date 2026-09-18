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
 * Return shape of {@link useTelemetrySocket} — documented for editor autocomplete.
 *
 * @typedef {Object} TelemetrySocketApi
 * @property {TelemetryFrame | null} latest
 * @property {TelemetryFrame[]} history          last 300 frames (oldest → newest)
 * @property {string} connectionState            from {@link CONNECTION_STATES}
 * @property {string | null} error               transport / parse errors
 * @property {string | null} lastValidationError contract drift (dev); surface in StatusBar
 */

/**
 * Sole boundary where Decision Plane telemetry enters React.
 *
 * Every inbound STOMP message is piped through {@link assertTelemetryFrame}
 * before state updates. In dev, a Java field rename then produces a console
 * error naming the exact JSON path instead of a silently frozen gauge.
 *
 * @param {string | null | undefined} sessionId
 * @returns {TelemetrySocketApi}
 */
export function useTelemetrySocket(sessionId) {
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
    if (!sessionId) {
      setLatest(null);
      setHistory([]);
      setLastValidationError(null);
      return undefined;
    }

    setLatest(null);
    setHistory([]);
    setLastValidationError(null);
    setError(null);

    const destination = `/topic/telemetry/${sessionId}`;
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
  }, [sessionId]);

  return {
    latest,
    history,
    connectionState,
    error,
    lastValidationError,
  };
}

export { CONNECTION_STATES };

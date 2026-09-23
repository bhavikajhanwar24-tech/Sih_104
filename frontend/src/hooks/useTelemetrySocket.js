import { useCallback, useEffect, useRef, useState } from 'react';
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

const HISTORY_LIMIT = 240;
const STALE_AFTER_MS = 3000;

/**
 * V2 telemetry + live-calls STOMP boundary (F13).
 *
 * - `/topic/tenant/{tid}/calls` — list deltas
 * - `/topic/tenant/{tid}/telemetry/{sessionId}` — selected call frames
 *
 * Accepts either an options object or a legacy sessionId string.
 *
 * @param {Object | string | null | undefined} [sessionIdOrOpts]
 * @param {string | null | undefined} [tenantIdMaybe]
 */
export function useTelemetrySocket(sessionIdOrOpts = null, tenantIdMaybe = undefined) {
  const opts =
    sessionIdOrOpts != null && typeof sessionIdOrOpts === 'object' && !Array.isArray(sessionIdOrOpts)
      ? sessionIdOrOpts
      : { sessionId: sessionIdOrOpts, tenantId: tenantIdMaybe, subscribeCalls: false };
  return useTelemetrySocketImpl(opts);
}

/**
 * @param {Object} opts
 * @param {string | null | undefined} [opts.sessionId]
 * @param {string | null | undefined} [opts.tenantId]
 * @param {boolean} [opts.subscribeCalls=false]
 */
function useTelemetrySocketImpl({ sessionId = null, tenantId = null, subscribeCalls = false } = {}) {
  const [latest, setLatest] = useState(/** @type {import('@/contracts').TelemetryFrame | null} */ (null));
  const [history, setHistory] = useState(/** @type {import('@/contracts').TelemetryFrame[]} */ ([]));
  const [callDeltas, setCallDeltas] = useState(/** @type {Record<string, object>} */ ({}));
  const [connectionState, setConnectionState] = useState(getConnectionState);
  const [error, setError] = useState(/** @type {string | null} */ (null));
  const [lastValidationError, setLastValidationError] = useState(
    /** @type {string | null} */ (null),
  );
  const [lastMessageAt, setLastMessageAt] = useState(/** @type {number | null} */ (null));
  const [stale, setStale] = useState(false);

  const sessionRef = useRef(sessionId);
  sessionRef.current = sessionId;

  useEffect(() => {
    const unlisten = onConnectionState((state) => {
      setConnectionState(state);
      if (state === CONNECTION_STATES.CONNECTED) {
        setError(null);
        setStale(false);
      } else if (state === CONNECTION_STATES.RECONNECTING || state === CONNECTION_STATES.DISCONNECTED) {
        const transportErr = getLastError();
        if (transportErr) setError(transportErr);
      }
    });
    ensureConnected();
    return unlisten;
  }, []);

  useEffect(() => {
    if (connectionState === CONNECTION_STATES.CONNECTED) {
      setStale(false);
      return undefined;
    }
    const started = Date.now();
    const id = window.setInterval(() => {
      if (Date.now() - started >= STALE_AFTER_MS) {
        setStale(true);
      }
    }, 500);
    return () => window.clearInterval(id);
  }, [connectionState]);

  // Selected-call telemetry
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
      const frame = assertTelemetryFrame(/** @type {import('@/contracts').TelemetryFrame} */ (parsed));
      setLatest(frame);
      setLastMessageAt(Date.now());
      setHistory((prev) => {
        const next =
          prev.length >= HISTORY_LIMIT
            ? prev.slice(prev.length - HISTORY_LIMIT + 1)
            : prev.slice();
        next.push(frame);
        return next;
      });
    });

    return unsubscribe;
  }, [sessionId, tenantId]);

  // Live calls list deltas
  useEffect(() => {
    if (!subscribeCalls || !tenantId) {
      return undefined;
    }
    const destination = `/topic/tenant/${tenantId}/calls`;
    const unsubscribe = subscribe(destination, (body) => {
      let parsed;
      try {
        parsed = JSON.parse(body);
      } catch {
        return;
      }
      const key = String(parsed.svSessionUuid || parsed.id || '');
      if (!key) return;
      setLastMessageAt(Date.now());
      setCallDeltas((prev) => ({ ...prev, [key]: { ...prev[key], ...parsed, _at: Date.now() } }));
    });
    return unsubscribe;
  }, [subscribeCalls, tenantId]);

  const clearCallDelta = useCallback((key) => {
    setCallDeltas((prev) => {
      const next = { ...prev };
      delete next[key];
      return next;
    });
  }, []);

  return {
    latest,
    history,
    callDeltas,
    clearCallDelta,
    connectionState,
    error,
    lastValidationError,
    lastMessageAt,
    stale: stale || (connectionState !== CONNECTION_STATES.CONNECTED && Date.now() - (lastMessageAt || 0) > STALE_AFTER_MS),
  };
}

export { CONNECTION_STATES };

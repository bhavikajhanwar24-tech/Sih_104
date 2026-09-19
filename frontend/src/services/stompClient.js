import { Client } from '@stomp/stompjs';

/**
 * Shared STOMP client for Decision Plane telemetry.
 *
 * Uses the native WebSocket STOMP endpoint (Spring registers `/ws-sentinel`
 * both raw and with SockJS). Native WS is far more reliable through Vite's
 * proxy than SockJS's XHR/websocket dance.
 *
 * Components must NOT open their own sockets — use {@link useTelemetrySocket}.
 */

export const CONNECTION_STATES = Object.freeze({
  DISCONNECTED: 'DISCONNECTED',
  CONNECTING: 'CONNECTING',
  CONNECTED: 'CONNECTED',
  RECONNECTING: 'RECONNECTING',
});

const RECONNECT_BASE_MS = 500;
const RECONNECT_MAX_MS = 8000;

/** @typedef {(state: string) => void} ConnectionListener */

/** @type {Client | null} */
let client = null;
/** @type {string} */
let connectionState = CONNECTION_STATES.DISCONNECTED;
/** @type {Set<ConnectionListener>} */
const listeners = new Set();
/** @type {string | null} */
let lastError = null;
let reconnectAttempt = 0;
/** @type {ReturnType<typeof setTimeout> | null} */
let reconnectTimer = null;
let wantConnected = false;
/** @type {string | null} legacy Basic header — unused with cookie JWT (F2/F3) */
let stompAuthHeader = null;

/**
 * @deprecated Cookie JWT is sent automatically on same-origin WS; kept for API compat.
 * @param {string | null} header
 */
export function setStompAuthHeader(header) {
  stompAuthHeader = header && header.length > 0 ? header : null;
  if (!wantConnected) return;
  if (client) {
    try {
      client.deactivate();
    } catch {
      /* ignore */
    }
    client = null;
  }
  connect();
}

/**
 * STOMP broker URL (ws / wss). Override with VITE_STOMP_URL
 * (e.g. ws://localhost:8080/ws-sentinel to bypass the Vite proxy).
 * @returns {string}
 */
export function brokerUrl() {
  if (import.meta.env.VITE_STOMP_URL) {
    return String(import.meta.env.VITE_STOMP_URL);
  }
  const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${proto}//${window.location.host}/ws-sentinel`;
}

/**
 * @returns {string}
 */
export function getConnectionState() {
  return connectionState;
}

/**
 * @returns {string | null}
 */
export function getLastError() {
  return lastError;
}

/**
 * @param {ConnectionListener} listener
 * @returns {() => void} unsubscribe
 */
export function onConnectionState(listener) {
  listeners.add(listener);
  listener(connectionState);
  return () => listeners.delete(listener);
}

/**
 * @param {string} next
 * @param {string | null} [error]
 */
function setState(next, error = null) {
  connectionState = next;
  if (error !== undefined) lastError = error;
  listeners.forEach((l) => {
    try {
      l(next);
    } catch {
      /* ignore listener errors */
    }
  });
}

/**
 * Ensure the shared client is connecting / connected.
 * @returns {Client}
 */
export function ensureConnected() {
  wantConnected = true;
  if (client && client.active) {
    return client;
  }
  return connect();
}

/**
 * Soft disconnect — stops reconnect until {@link ensureConnected} again.
 */
export function disconnect() {
  wantConnected = false;
  if (reconnectTimer != null) {
    clearTimeout(reconnectTimer);
    reconnectTimer = null;
  }
  if (client) {
    try {
      client.deactivate();
    } catch {
      /* ignore */
    }
    client = null;
  }
  setState(CONNECTION_STATES.DISCONNECTED, null);
}

/**
 * @returns {Client}
 */
function connect() {
  if (reconnectTimer != null) {
    clearTimeout(reconnectTimer);
    reconnectTimer = null;
  }

  const isReconnect = connectionState === CONNECTION_STATES.RECONNECTING
    || reconnectAttempt > 0;
  setState(
    isReconnect ? CONNECTION_STATES.RECONNECTING : CONNECTION_STATES.CONNECTING,
    null,
  );

  // Tear down any half-open client before creating a new one.
  if (client) {
    try {
      client.deactivate();
    } catch {
      /* ignore */
    }
    client = null;
  }

  const stomp = new Client({
    brokerURL: brokerUrl(),
    reconnectDelay: 0,
    connectionTimeout: 8000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    connectHeaders: stompAuthHeader ? { Authorization: stompAuthHeader } : {},
    debug: () => {},
    onConnect: () => {
      reconnectAttempt = 0;
      lastError = null;
      setState(CONNECTION_STATES.CONNECTED, null);
    },
    onStompError: (frame) => {
      const msg = frame?.headers?.message || frame?.body || 'STOMP error';
      lastError = String(msg);
      setState(connectionState, lastError);
    },
    onWebSocketClose: () => {
      client = null;
      if (!wantConnected) {
        setState(CONNECTION_STATES.DISCONNECTED, lastError);
        return;
      }
      scheduleReconnect();
    },
    onWebSocketError: () => {
      lastError = 'WebSocket error talking to Decision Plane';
    },
  });

  client = stomp;
  stomp.activate();
  return stomp;
}

function scheduleReconnect() {
  if (!wantConnected) return;
  if (reconnectTimer != null) return;
  const delay = Math.min(RECONNECT_MAX_MS, RECONNECT_BASE_MS * 2 ** reconnectAttempt);
  reconnectAttempt += 1;
  setState(CONNECTION_STATES.RECONNECTING, lastError || 'Connection lost — reconnecting');
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    if (wantConnected) connect();
  }, delay);
}

/**
 * Subscribe to a STOMP destination once the client is connected.
 * Retries subscription across reconnects.
 *
 * @param {string} destination
 * @param {(body: string) => void} onMessage
 * @returns {() => void} unsubscribe
 */
export function subscribe(destination, onMessage) {
  ensureConnected();

  /** @type {{ unsubscribe: () => void } | null} */
  let sub = null;
  let cancelled = false;

  const clearSub = () => {
    if (sub) {
      try {
        sub.unsubscribe();
      } catch {
        /* ignore — connection may already be dead */
      }
      sub = null;
    }
  };

  const trySubscribe = () => {
    if (cancelled) return;
    const c = client;
    if (!c || !c.connected) return;
    if (sub) return;
    sub = c.subscribe(destination, (message) => {
      onMessage(message.body);
    });
  };

  const unlisten = onConnectionState((state) => {
    if (state === CONNECTION_STATES.CONNECTED) {
      clearSub();
      trySubscribe();
    } else {
      // Connection lost — drop local handle; server-side sub is already gone.
      sub = null;
    }
  });

  trySubscribe();

  return () => {
    cancelled = true;
    unlisten();
    clearSub();
  };
}

/**
 * @returns {Client | null}
 */
export function getClient() {
  return client;
}

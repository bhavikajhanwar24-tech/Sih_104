import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';

/**
 * Shared STOMP client for Decision Plane telemetry.
 *
 * Connects to the Java SockJS endpoint `/ws-sentinel` (proxied by Vite to
 * http://localhost:8080). Components must NOT open their own sockets — use
 * {@link useTelemetrySocket} which rides this singleton.
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

/**
 * Broker URL. Prefer same-origin `/ws-sentinel` so Vite's proxy handles it in
 * dev; absolute override via VITE_STOMP_URL.
 * @returns {string}
 */
export function brokerUrl() {
  if (import.meta.env.VITE_STOMP_URL) {
    return String(import.meta.env.VITE_STOMP_URL);
  }
  return `${window.location.protocol}//${window.location.host}/ws-sentinel`;
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

  const stomp = new Client({
    webSocketFactory: () => new SockJS(brokerUrl()),
    reconnectDelay: 0, // we own backoff
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
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
      sub = null; // force re-subscribe after reconnect
      trySubscribe();
    } else {
      sub = null;
    }
  });

  trySubscribe();

  return () => {
    cancelled = true;
    unlisten();
    try {
      sub?.unsubscribe();
    } catch {
      /* ignore */
    }
    sub = null;
  };
}

/**
 * @returns {Client | null}
 */
export function getClient() {
  return client;
}

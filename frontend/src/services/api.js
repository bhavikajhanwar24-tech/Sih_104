/**
 * Cookie-session API client (F2).
 * credentials:include, CSRF double-submit, single-flight refresh on 401.
 */

/** @typedef {{ code: string, message: string, status: number, body?: unknown }} ApiError */

let csrfHeaderName = 'X-XSRF-TOKEN';
/** @type {string | null} */
let csrfToken = null;
/** @type {Promise<boolean> | null} */
let refreshFlight = null;
/** @type {((err: ApiError) => void) | null} */
let onApiError = null;

/** Single request limit for every API call (PDF compile + remote Supabase can be slow). */
export const REQUEST_TIMEOUT_MS = 300_000;
const DEFAULT_TIMEOUT_MS = REQUEST_TIMEOUT_MS;

/**
 * @param {(err: ApiError) => void | null} handler
 */
export function setApiErrorHandler(handler) {
  onApiError = handler;
}

/**
 * Read XSRF-TOKEN cookie (Spring CookieCsrfTokenRepository).
 * @returns {string | null}
 */
function readXsrfCookie() {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/);
  if (!match) return null;
  try {
    return decodeURIComponent(match[1]);
  } catch {
    return match[1];
  }
}

/**
 * Ensure CSRF token is available (GET /api/v2/auth/csrf).
 * @returns {Promise<void>}
 */
export async function ensureCsrf() {
  const fromCookie = readXsrfCookie();
  if (fromCookie) {
    csrfToken = fromCookie;
    return;
  }
  const res = await fetch('/api/v2/auth/csrf', {
    method: 'GET',
    credentials: 'include',
    signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
  });
  if (!res.ok) return;
  const data = await res.json().catch(() => ({}));
  if (data.headerName) csrfHeaderName = data.headerName;
  csrfToken = data.token || readXsrfCookie();
}

/**
 * @param {Response} res
 * @returns {Promise<ApiError>}
 */
async function toApiError(res) {
  let body = null;
  let message = res.statusText || `HTTP ${res.status}`;
  let code = `http_${res.status}`;
  try {
    body = await res.json();
    if (body && typeof body === 'object') {
      if (body.message) message = String(body.message);
      if (body.error) code = String(body.error);
    }
  } catch {
    /* ignore */
  }
  return { code, message, status: res.status, body };
}

/**
 * @returns {Promise<boolean>} true if refresh succeeded
 */
async function refreshSession() {
  if (refreshFlight) return refreshFlight;
  refreshFlight = (async () => {
    try {
      await ensureCsrf();
      const headers = new Headers();
      const token = csrfToken || readXsrfCookie();
      if (token) headers.set(csrfHeaderName, token);
      const res = await fetch('/api/v2/auth/refresh', {
        method: 'POST',
        credentials: 'include',
        headers,
        signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
      });
      if (res.ok) {
        csrfToken = readXsrfCookie() || csrfToken;
        return true;
      }
      return false;
    } catch {
      return false;
    } finally {
      refreshFlight = null;
    }
  })();
  return refreshFlight;
}

/**
 * Merge caller AbortSignal with a timeout so hung APIs fail fast.
 * @param {AbortSignal | null | undefined} userSignal
 * @param {number} timeoutMs
 * @returns {{ signal: AbortSignal | undefined, cleanup: () => void }}
 */
function withTimeout(userSignal, timeoutMs) {
  if (timeoutMs <= 0) {
    return { signal: userSignal || undefined, cleanup: () => {} };
  }
  const ctrl = new AbortController();
  const onUserAbort = () => ctrl.abort(userSignal?.reason);
  if (userSignal) {
    if (userSignal.aborted) {
      ctrl.abort(userSignal.reason);
    } else {
      userSignal.addEventListener('abort', onUserAbort, { once: true });
    }
  }
  const timer = setTimeout(
    () => ctrl.abort(new DOMException('Request timed out', 'TimeoutError')),
    timeoutMs,
  );
  return {
    signal: ctrl.signal,
    cleanup: () => {
      clearTimeout(timer);
      if (userSignal) userSignal.removeEventListener('abort', onUserAbort);
    },
  };
}

/**
 * @param {string} input
 * @param {RequestInit & { skipAuthRefresh?: boolean, skipErrorToast?: boolean, timeoutMs?: number }} [init]
 * @returns {Promise<Response>}
 */
export async function apiFetch(input, init = {}) {
  const {
    skipAuthRefresh = false,
    skipErrorToast = false,
    timeoutMs = DEFAULT_TIMEOUT_MS,
    signal: userSignal,
    ...rest
  } = init;
  await ensureCsrf();
  const headers = new Headers(rest.headers || {});
  if (!headers.has('Content-Type') && rest.body && typeof rest.body === 'string') {
    headers.set('Content-Type', 'application/json');
  }
  const token = csrfToken || readXsrfCookie();
  if (token && !headers.has(csrfHeaderName)) {
    headers.set(csrfHeaderName, token);
  }

  const { signal, cleanup } = withTimeout(userSignal, timeoutMs);
  let res;
  try {
    res = await fetch(input, {
      ...rest,
      headers,
      credentials: 'include',
      signal,
    });
  } catch (err) {
    cleanup();
    if (err instanceof DOMException && (err.name === 'TimeoutError' || err.name === 'AbortError')) {
      const timedOut =
        err.name === 'TimeoutError' || String(err.message || '').includes('timed out');
      const out = Object.assign(new Error(timedOut ? 'Request timed out' : 'Request aborted'), {
        code: timedOut ? 'timeout' : 'aborted',
        status: 0,
      });
      // Never toast client-side aborts — callers that care will handle.
      throw out;
    }
    throw err;
  }

  if (res.status === 401 && !skipAuthRefresh) {
    const ok = await refreshSession();
    if (ok) {
      const retryHeaders = new Headers(rest.headers || {});
      if (!retryHeaders.has('Content-Type') && rest.body && typeof rest.body === 'string') {
        retryHeaders.set('Content-Type', 'application/json');
      }
      const retryToken = csrfToken || readXsrfCookie();
      if (retryToken) retryHeaders.set(csrfHeaderName, retryToken);
      const retry = withTimeout(userSignal, timeoutMs);
      try {
        res = await fetch(input, {
          ...rest,
          headers: retryHeaders,
          credentials: 'include',
          signal: retry.signal,
        });
      } finally {
        retry.cleanup();
      }
    }
  }

  cleanup();

  if (!res.ok && !skipErrorToast && onApiError && res.status !== 401) {
    const err = await res.clone().json().catch(() => null);
    onApiError({
      code: err?.error || `http_${res.status}`,
      message: err?.message || res.statusText || `Request failed (${res.status})`,
      status: res.status,
      body: err,
    });
  }

  return res;
}

/**
 * @param {string} input
 * @param {RequestInit & { skipAuthRefresh?: boolean, skipErrorToast?: boolean, timeoutMs?: number }} [init]
 * @returns {Promise<any>}
 */
export async function apiJson(input, init = {}) {
  const res = await apiFetch(input, init);
  if (!res.ok) {
    throw await toApiError(res);
  }
  if (res.status === 204) return null;
  return res.json();
}

/** @deprecated v1 Basic auth — no-op retained for legacy imports */
export function setAuthorizationHeader() {}
/** @deprecated */
export function getAuthorizationHeader() {
  return null;
}
/** @deprecated */
export function basicAuthHeader() {
  return '';
}

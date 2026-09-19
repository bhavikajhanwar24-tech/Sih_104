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
 * @param {string} input
 * @param {RequestInit & { skipAuthRefresh?: boolean, skipErrorToast?: boolean }} [init]
 * @returns {Promise<Response>}
 */
export async function apiFetch(input, init = {}) {
  const { skipAuthRefresh = false, skipErrorToast = false, ...rest } = init;
  await ensureCsrf();
  const headers = new Headers(rest.headers || {});
  if (!headers.has('Content-Type') && rest.body && typeof rest.body === 'string') {
    headers.set('Content-Type', 'application/json');
  }
  const token = csrfToken || readXsrfCookie();
  if (token && !headers.has(csrfHeaderName)) {
    headers.set(csrfHeaderName, token);
  }

  let res = await fetch(input, {
    ...rest,
    headers,
    credentials: 'include',
  });

  if (res.status === 401 && !skipAuthRefresh) {
    const ok = await refreshSession();
    if (ok) {
      const retryHeaders = new Headers(rest.headers || {});
      if (!retryHeaders.has('Content-Type') && rest.body && typeof rest.body === 'string') {
        retryHeaders.set('Content-Type', 'application/json');
      }
      const retryToken = csrfToken || readXsrfCookie();
      if (retryToken) retryHeaders.set(csrfHeaderName, retryToken);
      res = await fetch(input, {
        ...rest,
        headers: retryHeaders,
        credentials: 'include',
      });
    }
  }

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
 * @param {RequestInit & { skipAuthRefresh?: boolean, skipErrorToast?: boolean }} [init]
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

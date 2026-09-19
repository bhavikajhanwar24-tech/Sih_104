/**
 * Authenticated fetch for Decision Plane REST.
 * Credentials live only in memory (AuthContext) — never localStorage.
 */

/** @type {string | null} */
let authorizationHeader = null;

/**
 * @param {string | null} header  full `Basic …` value, or null to clear
 */
export function setAuthorizationHeader(header) {
  authorizationHeader = header && header.length > 0 ? header : null;
}

/**
 * @returns {string | null}
 */
export function getAuthorizationHeader() {
  return authorizationHeader;
}

/**
 * Build `Authorization: Basic …` from username/password.
 * @param {string} username
 * @param {string} password
 * @returns {string}
 */
export function basicAuthHeader(username, password) {
  const token = btoa(`${username}:${password}`);
  return `Basic ${token}`;
}

/**
 * fetch() with Authorization when a session is active.
 * @param {string} input
 * @param {RequestInit} [init]
 * @returns {Promise<Response>}
 */
export async function apiFetch(input, init = {}) {
  const headers = new Headers(init.headers || {});
  if (authorizationHeader && !headers.has('Authorization')) {
    headers.set('Authorization', authorizationHeader);
  }
  return fetch(input, { ...init, headers });
}

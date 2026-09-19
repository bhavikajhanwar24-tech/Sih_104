import { createContext, useCallback, useContext, useMemo, useState } from 'react';
import PropTypes from 'prop-types';
import {
  apiFetch,
  basicAuthHeader,
  setAuthorizationHeader,
} from '@/services/api.js';
import { disconnect, ensureConnected, setStompAuthHeader } from '@/services/stompClient.js';

/**
 * Demo lab users (P13) — password for all is: password
 *   analyst    → ROLE_ANALYST
 *   supervisor → ROLE_SUPERVISOR
 *   compliance → ROLE_COMPLIANCE
 *   admin      → ROLE_ADMIN (+ analyst/supervisor/compliance)
 */
export const DEMO_USERS = Object.freeze({
  analyst: Object.freeze(['ANALYST']),
  supervisor: Object.freeze(['SUPERVISOR']),
  compliance: Object.freeze(['COMPLIANCE']),
  admin: Object.freeze(['ADMIN', 'ANALYST', 'SUPERVISOR', 'COMPLIANCE']),
});

/**
 * @typedef {Object} AuthContextValue
 * @property {boolean} isAuthenticated
 * @property {string | null} username
 * @property {string[]} roles
 * @property {(username: string, password: string) => Promise<void>} login
 * @property {() => void} logout
 * @property {(role: string) => boolean} hasRole
 */

const AuthContext = createContext(/** @type {AuthContextValue | null} */ (null));

/**
 * @param {Object} props
 * @param {React.ReactNode} props.children
 */
export function AuthProvider({ children }) {
  const [username, setUsername] = useState(/** @type {string | null} */ (null));
  const [roles, setRoles] = useState(/** @type {string[]} */ ([]));

  const logout = useCallback(() => {
    setAuthorizationHeader(null);
    setStompAuthHeader(null);
    disconnect();
    setUsername(null);
    setRoles([]);
  }, []);

  const login = useCallback(async (user, password) => {
    const trimmed = String(user || '').trim().toLowerCase();
    if (!trimmed || !password) {
      throw new Error('Username and password required');
    }
    const header = basicAuthHeader(trimmed, password);
    setAuthorizationHeader(header);
    try {
      const res = await apiFetch('/api/v1/session', { method: 'GET' });
      if (res.status === 401 || res.status === 403) {
        setAuthorizationHeader(null);
        throw new Error('Invalid credentials or insufficient role');
      }
      if (!res.ok) {
        // Backend may be down — still accept known demo users so UI is usable offline.
        if (!DEMO_USERS[trimmed]) {
          setAuthorizationHeader(null);
          throw new Error(`Login probe failed (${res.status})`);
        }
      }
    } catch (err) {
      if (err instanceof Error && err.message.startsWith('Invalid')) throw err;
      if (!DEMO_USERS[trimmed]) {
        setAuthorizationHeader(null);
        throw err instanceof Error ? err : new Error('Login failed');
      }
    }

    const nextRoles = DEMO_USERS[trimmed] ? [...DEMO_USERS[trimmed]] : ['ANALYST'];
    setUsername(trimmed);
    setRoles(nextRoles);
    setStompAuthHeader(header);
    ensureConnected();
  }, []);

  const hasRole = useCallback(
    (role) => roles.includes(String(role || '').toUpperCase()),
    [roles],
  );

  const value = useMemo(
    () => ({
      isAuthenticated: Boolean(username),
      username,
      roles,
      login,
      logout,
      hasRole,
    }),
    [username, roles, login, logout, hasRole],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

AuthProvider.propTypes = {
  children: PropTypes.node.isRequired,
};

/**
 * @returns {AuthContextValue}
 */
export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error('useAuth must be used within AuthProvider');
  }
  return ctx;
}

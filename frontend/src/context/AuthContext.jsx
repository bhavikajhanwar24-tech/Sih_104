import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from 'react';
import PropTypes from 'prop-types';
import { apiFetch, apiJson, ensureCsrf } from '@/services/api.js';

/**
 * @typedef {Object} MePayload
 * @property {{ id: string, email: string, displayName: string, status: string }} user
 * @property {{ id: string, name: string, slug: string, industry?: string, region?: string, status: string }} tenant
 * @property {string} role
 * @property {boolean} mfaEnabled
 * @property {string[]} permissions
 * @property {boolean} [promptMfa]
 */

/**
 * @typedef {Object} AuthContextValue
 * @property {boolean} loading
 * @property {boolean} isAuthenticated
 * @property {MePayload | null} me
 * @property {string[]} permissions
 * @property {(perm: string) => boolean} hasPermission
 * @property {() => Promise<MePayload | null>} refreshMe
 * @property {(creds: { tenantSlug: string, email: string, password: string, mfaCode?: string }) => Promise<{ mfaRequired: boolean }>} login
 * @property {() => Promise<void>} logout
 */

const AuthContext = createContext(/** @type {AuthContextValue | null} */ (null));

/**
 * @param {Object} props
 */
export function AuthProvider({ children }) {
  const [loading, setLoading] = useState(true);
  const [me, setMe] = useState(/** @type {MePayload | null} */ (null));

  const refreshMe = useCallback(async () => {
    try {
      await ensureCsrf();
      const data = await apiJson('/api/v2/me', { skipErrorToast: true, skipAuthRefresh: false });
      setMe(data);
      return data;
    } catch {
      setMe(null);
      return null;
    }
  }, []);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      await ensureCsrf();
      if (cancelled) return;
      await refreshMe();
      if (!cancelled) setLoading(false);
    })();
    return () => {
      cancelled = true;
    };
  }, [refreshMe]);

  const login = useCallback(
    async ({ tenantSlug, email, password, mfaCode }) => {
      await ensureCsrf();
      const res = await apiFetch('/api/v2/auth/login', {
        method: 'POST',
        skipAuthRefresh: true,
        skipErrorToast: true,
        body: JSON.stringify({
          tenantSlug,
          email,
          password,
          mfaCode: mfaCode || null,
        }),
      });
      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw new Error(err.message || 'Login failed');
      }
      const data = await res.json();
      if (data.mfaRequired) {
        return { mfaRequired: true };
      }
      await refreshMe();
      return { mfaRequired: false };
    },
    [refreshMe],
  );

  const logout = useCallback(async () => {
    try {
      await apiFetch('/api/v2/auth/logout', {
        method: 'POST',
        skipErrorToast: true,
      });
    } finally {
      setMe(null);
    }
  }, []);

  const permissions = me?.permissions || [];

  const hasPermission = useCallback(
    (perm) => permissions.includes(perm),
    [permissions],
  );

  const value = useMemo(
    () => ({
      loading,
      isAuthenticated: Boolean(me),
      me,
      permissions,
      hasPermission,
      refreshMe,
      login,
      logout,
      /** @deprecated use me.user — kept for v1 view remounts */
      username: me?.user?.email ?? null,
      /** @deprecated use hasPermission — role name checks are discouraged */
      hasRole: (role) =>
        Boolean(me?.role && String(role || '').toUpperCase() === me.role),
    }),
    [loading, me, permissions, hasPermission, refreshMe, login, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

AuthProvider.propTypes = {
  children: PropTypes.node.isRequired,
};

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}

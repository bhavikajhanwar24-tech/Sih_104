import { useState } from 'react';
import PropTypes from 'prop-types';
import { DEMO_USERS, useAuth } from '@/context/AuthContext.jsx';

/**
 * HTTP Basic login gate for demo lab users.
 *
 * Demo users (password: password): analyst, supervisor, compliance, admin
 *
 * @param {Object} [props]
 * @param {string} [props.className]
 */
export function LoginPage({ className = '' }) {
  const { login } = useAuth();
  const [username, setUsername] = useState('analyst');
  const [password, setPassword] = useState('password');
  const [error, setError] = useState(/** @type {string | null} */ (null));
  const [busy, setBusy] = useState(false);

  async function onSubmit(e) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await login(username, password);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Login failed');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div
      className={`flex min-h-full items-center justify-center bg-sv-bg p-6 text-sv-fg ${className}`}
    >
      <form
        onSubmit={(ev) => void onSubmit(ev)}
        className="w-full max-w-sm rounded border border-sv-border bg-sv-panel p-6 shadow-lg"
      >
        <p className="font-mono text-[10px] uppercase tracking-[0.2em] text-sv-accent">
          SentinelVoice · AuthZ
        </p>
        <h1 className="mt-2 font-display text-xl font-semibold">Sign in</h1>
        <p className="mt-2 text-sm text-sv-muted">
          HTTP Basic for the Decision Plane. Demo users:{' '}
          {Object.keys(DEMO_USERS).join(', ')} — password{' '}
          <span className="font-mono text-sv-fg">password</span>.
        </p>

        <label className="mt-4 block text-[11px] uppercase tracking-wide text-sv-muted">
          Username
          <input
            className="mt-1 w-full rounded border border-sv-border bg-sv-bg px-3 py-2 font-mono text-sm text-sv-fg outline-none focus:border-sv-accent"
            value={username}
            onChange={(ev) => setUsername(ev.target.value)}
            autoComplete="username"
            data-testid="login-username"
          />
        </label>

        <label className="mt-3 block text-[11px] uppercase tracking-wide text-sv-muted">
          Password
          <input
            type="password"
            className="mt-1 w-full rounded border border-sv-border bg-sv-bg px-3 py-2 font-mono text-sm text-sv-fg outline-none focus:border-sv-accent"
            value={password}
            onChange={(ev) => setPassword(ev.target.value)}
            autoComplete="current-password"
            data-testid="login-password"
          />
        </label>

        {error ? (
          <p className="mt-3 text-sm text-red-400" role="alert">
            {error}
          </p>
        ) : null}

        <button
          type="submit"
          disabled={busy}
          data-testid="login-submit"
          className="mt-5 w-full rounded bg-sv-accent px-3 py-2 text-sm font-medium text-sv-bg hover:opacity-90 disabled:opacity-50"
        >
          {busy ? 'Signing in…' : 'Sign in'}
        </button>
      </form>
    </div>
  );
}

LoginPage.propTypes = {
  className: PropTypes.string,
};

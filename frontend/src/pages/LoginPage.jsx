import { useState } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '@/context/AuthContext.jsx';
import { Button, Input } from '@/ui';
import { useToast } from '@/ui/Toast.jsx';

export function LoginPage() {
  const { login, isAuthenticated, loading } = useAuth();
  const { push } = useToast();
  const navigate = useNavigate();
  const location = useLocation();
  const presetSlug = location.state?.tenantSlug || '';

  const [tenantSlug, setTenantSlug] = useState(presetSlug);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [mfaCode, setMfaCode] = useState('');
  const [needMfa, setNeedMfa] = useState(false);
  const [busy, setBusy] = useState(false);

  if (!loading && isAuthenticated) {
    return <Navigate to="/app" replace />;
  }

  const onSubmit = async (e) => {
    e.preventDefault();
    setBusy(true);
    try {
      const result = await login({
        tenantSlug: tenantSlug.trim(),
        email: email.trim(),
        password,
        mfaCode: needMfa ? mfaCode : undefined,
      });
      if (result.mfaRequired) {
        setNeedMfa(true);
        return;
      }
      const dest = location.state?.from || '/app';
      navigate(dest, { replace: true });
    } catch (err) {
      push(err.message || 'Login failed');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="flex min-h-full items-center justify-center bg-[radial-gradient(ellipse_at_top,_var(--sv-elevated)_0%,_var(--sv-bg)_55%)] px-4 py-10">
      <form
        onSubmit={onSubmit}
        className="w-full max-w-md rounded-xl border border-sv-border bg-sv-panel/95 p-6 shadow-xl backdrop-blur sm:p-8"
      >
        <header className="mb-6">
          <p className="font-display text-2xl font-semibold tracking-tight text-sv-fg">
            SentinelVoice
          </p>
          <p className="mt-1 text-sm text-sv-muted">
            {needMfa ? 'Enter your authenticator code' : 'Sign in to your workspace'}
          </p>
        </header>
        <div className="space-y-4">
          {!needMfa ? (
            <>
              <Input
                label="Tenant slug"
                value={tenantSlug}
                onChange={(e) => setTenantSlug(e.target.value)}
                autoComplete="organization"
                required
              />
              <Input
                label="Email"
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                autoComplete="username"
                required
              />
              <Input
                label="Password"
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                autoComplete="current-password"
                required
              />
            </>
          ) : (
            <Input
              label="MFA code"
              value={mfaCode}
              onChange={(e) => setMfaCode(e.target.value)}
              inputMode="numeric"
              autoComplete="one-time-code"
              required
            />
          )}
          <Button type="submit" className="w-full" disabled={busy}>
            {busy ? 'Signing in…' : needMfa ? 'Verify' : 'Sign in'}
          </Button>
        </div>
        <p className="mt-6 text-center text-xs text-sv-muted">
          New organisation?{' '}
          <Link className="text-sv-accent underline-offset-2 hover:underline" to="/register">
            Register
          </Link>
        </p>
      </form>
    </div>
  );
}

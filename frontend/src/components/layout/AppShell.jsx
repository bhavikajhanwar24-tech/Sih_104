import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useCallback, useEffect, useState } from 'react';
import { useAuth } from '@/context/AuthContext.jsx';
import { Button } from '@/ui/Button.jsx';

const NAV = [
  { to: '/app', end: true, label: 'Dashboard', permission: 'dashboard:read' },
  { to: '/app/live', label: 'Live Calls', permission: 'calls:read', alertKey: 'live' },
  { to: '/app/history', label: 'Call History', permission: 'calls:read' },
  { to: '/app/directory', label: 'Directory', permission: 'directory:read' },
  { to: '/app/policies', label: 'Policies', permission: 'policies:read' },
  { to: '/app/approvals', label: 'Approvals', permission: 'approvals:read' },
  { to: '/app/response', label: 'Response Plans', permission: 'response:read' },
  { to: '/app/changes', label: 'Change history', permission: 'governance:read' },
  { to: '/app/audit', label: 'Audit', permission: 'audit:read' },
  { to: '/app/compliance', label: 'Compliance', permission: 'compliance:read' },
  { to: '/app/settings', label: 'Settings', permission: 'settings:read' },
  { to: '/app/users', label: 'Users', permission: 'users:read' },
];

/**
 * Authenticated application chrome — sidebar driven by permissions[], not role names.
 */
export function AppShell() {
  const { me, hasPermission, logout } = useAuth();
  const navigate = useNavigate();
  const [theme, setTheme] = useState(() => localStorage.getItem('sv-theme') || 'dark');
  const [liveAlerts, setLiveAlerts] = useState(0);
  const [emergency, setEmergency] = useState(null);

  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme);
    localStorage.setItem('sv-theme', theme);
  }, [theme]);

  useEffect(() => {
    const onAlerts = (e) => {
      const n = Number(e?.detail?.count);
      if (Number.isFinite(n)) setLiveAlerts(Math.max(0, n));
    };
    window.addEventListener('sv-live-alerts', onAlerts);
    return () => window.removeEventListener('sv-live-alerts', onAlerts);
  }, []);

  useEffect(() => {
    let cancelled = false;
    async function pollEmergency() {
      if (!hasPermission('dashboard:read')) return;
      try {
        const { apiJson } = await import('@/services/api.js');
        const st = await apiJson('/api/v2/governance/emergency', {
          skipErrorToast: true,
        });
        if (!cancelled) setEmergency(st);
      } catch {
        /* ignore — never toast poll failures */
      }
    }
    void pollEmergency();
    const id = window.setInterval(() => void pollEmergency(), 45_000);
    return () => {
      cancelled = true;
      window.clearInterval(id);
    };
  }, [hasPermission]);

  const onLogout = useCallback(async () => {
    await logout();
    navigate('/login', { replace: true });
  }, [logout, navigate]);

  const visible = NAV.filter((item) => hasPermission(item.permission));

  return (
    <div className="flex h-full min-h-0 w-full overflow-hidden bg-sv-bg text-sv-fg">
      <aside
        className="flex w-56 shrink-0 flex-col border-r border-sv-border bg-sv-panel"
        aria-label="Application"
      >
        <div className="border-b border-sv-border px-4 py-4">
          <p className="font-display text-sm font-semibold tracking-wide text-sv-fg">
            SentinelVoice
          </p>
          <p className="mt-0.5 truncate font-mono text-[11px] text-sv-muted" title={me?.tenant?.slug}>
            {me?.tenant?.name || me?.tenant?.slug}
          </p>
        </div>
        <nav className="flex-1 overflow-y-auto p-2" aria-label="Primary">
          <ul className="flex flex-col gap-0.5">
            {visible.map((item) => (
              <li key={item.to}>
                <NavLink
                  to={item.to}
                  end={item.end}
                  className={({ isActive }) =>
                    `flex items-center justify-between gap-2 rounded-md px-3 py-2 text-sm transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-sv-accent ${
                      isActive
                        ? 'bg-sv-accent/15 font-medium text-sv-accent'
                        : 'text-sv-muted hover:bg-sv-elevated hover:text-sv-fg'
                    }`
                  }
                >
                  <span>{item.label}</span>
                  {item.alertKey === 'live' && liveAlerts > 0 ? (
                    <span className="rounded-full bg-red-600 px-1.5 py-0.5 font-mono text-[10px] font-semibold text-white">
                      {liveAlerts > 99 ? '99+' : liveAlerts}
                    </span>
                  ) : null}
                </NavLink>
              </li>
            ))}
          </ul>
        </nav>
        <div className="space-y-2 border-t border-sv-border p-3">
          <p className="truncate text-xs text-sv-fg" title={me?.user?.email}>
            {me?.user?.displayName || me?.user?.email}
          </p>
          <div className="flex gap-2">
            <Button
              variant="ghost"
              className="flex-1 !px-2 !py-1 text-xs"
              onClick={() => setTheme((t) => (t === 'dark' ? 'light' : 'dark'))}
              aria-label="Toggle colour theme"
            >
              {theme === 'dark' ? 'Light' : 'Dark'}
            </Button>
            <Button variant="secondary" className="flex-1 !px-2 !py-1 text-xs" onClick={onLogout}>
              Log out
            </Button>
          </div>
        </div>
      </aside>
      <main className="flex min-h-0 min-w-0 flex-1 flex-col overflow-hidden">
        {emergency?.active ? (
          <div
            role="alert"
            className="shrink-0 border-b border-red-500/50 bg-red-950/60 px-4 py-2 text-center text-xs font-medium text-red-100"
          >
            Emergency: {emergency.mode}
            {emergency.mode === 'SUSPEND_MONITORING'
              ? ' — monitoring suspended for this tenant'
              : ' — automatic actions suppressed (monitor-only)'}
          </div>
        ) : null}
        <div className="min-h-0 flex-1 overflow-y-auto overflow-x-hidden">
          <Outlet />
        </div>
      </main>
    </div>
  );
}

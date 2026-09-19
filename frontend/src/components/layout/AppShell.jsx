import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useCallback, useEffect, useState } from 'react';
import { useAuth } from '@/context/AuthContext.jsx';
import { Button } from '@/ui/Button.jsx';

const NAV = [
  { to: '/app', end: true, label: 'Dashboard', permission: 'dashboard:read' },
  { to: '/app/calls', label: 'Live Calls', permission: 'calls:read' },
  { to: '/app/directory', label: 'Directory', permission: 'directory:read' },
  { to: '/app/policies', label: 'Policies', permission: 'policies:read' },
  { to: '/app/response', label: 'Response Plans', permission: 'response:read' },
  { to: '/app/audit', label: 'Audit', permission: 'audit:read' },
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

  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme);
    localStorage.setItem('sv-theme', theme);
  }, [theme]);

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
                    `block rounded-md px-3 py-2 text-sm transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-sv-accent ${
                      isActive
                        ? 'bg-sv-accent/15 font-medium text-sv-accent'
                        : 'text-sv-muted hover:bg-sv-elevated hover:text-sv-fg'
                    }`
                  }
                >
                  {item.label}
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
      <main className="min-h-0 min-w-0 flex-1 overflow-y-auto">
        <Outlet />
      </main>
    </div>
  );
}

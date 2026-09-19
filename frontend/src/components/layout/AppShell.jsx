import { useState } from 'react';
import PropTypes from 'prop-types';
import { StatusBar } from '@/components/layout/StatusBar.jsx';
import { useAuth } from '@/context/AuthContext.jsx';
import { VIEWS } from '@/theme.js';

const NAV = [
  { id: VIEWS.ANALYST, label: 'Analyst', short: 'AN' },
  { id: VIEWS.SENIOR_SHIELD, label: 'Senior Shield', short: 'SS' },
  { id: VIEWS.COMPLIANCE, label: 'Compliance', short: 'CP' },
  { id: VIEWS.RED_TEAM, label: 'Red Team', short: 'RT' },
];

/**
 * Ops-centre chrome: left rail + main stage + StatusBar.
 *
 * @param {Object} props
 * @param {Record<string, React.ComponentType>} props.views map of view id → component
 * @param {string} [props.defaultView]
 */
export function AppShell({ views, defaultView = VIEWS.ANALYST }) {
  const [active, setActive] = useState(defaultView);
  const ActiveView = views[active] || views[VIEWS.ANALYST];
  const { username, roles, logout } = useAuth();

  return (
    <div className="flex h-full min-h-0 w-full min-w-0 overflow-hidden bg-sv-bg text-sv-fg">
      <nav
        className="flex w-14 shrink-0 flex-col border-r border-sv-border bg-sv-panel md:w-44"
        aria-label="Primary"
      >
        <div className="border-b border-sv-border px-2 py-3 md:px-3">
          <p className="font-display text-[10px] font-semibold uppercase tracking-[0.18em] text-sv-accent md:text-xs">
            Sentinel
          </p>
          <p className="hidden font-mono text-[10px] text-sv-muted md:block">Voice</p>
        </div>
        <ul className="flex flex-1 flex-col gap-1 p-1.5 md:p-2">
          {NAV.map((item) => {
            const selected = active === item.id;
            return (
              <li key={item.id}>
                <button
                  type="button"
                  onClick={() => setActive(item.id)}
                  className={`flex w-full items-center gap-2 rounded px-2 py-2 text-left text-xs transition-colors ${
                    selected
                      ? 'bg-sv-accent/15 text-sv-accent'
                      : 'text-sv-muted hover:bg-sv-elevated hover:text-sv-fg'
                  }`}
                  aria-current={selected ? 'page' : undefined}
                >
                  <span className="font-mono text-[10px] tabular-nums md:hidden">
                    {item.short}
                  </span>
                  <span className="hidden font-display font-medium md:inline">{item.label}</span>
                </button>
              </li>
            );
          })}
        </ul>
        <div className="border-t border-sv-border p-2">
          <p
            className="hidden truncate font-mono text-[10px] text-sv-muted md:block"
            title={roles.join(',')}
          >
            {username}
          </p>
          <button
            type="button"
            onClick={logout}
            className="mt-1 w-full rounded border border-sv-border px-2 py-1 font-mono text-[10px] text-sv-muted hover:border-sv-accent hover:text-sv-fg"
            data-testid="logout"
          >
            Log out
          </button>
        </div>
      </nav>

      <div className="flex min-h-0 min-w-0 flex-1 flex-col">
        <main className="min-h-0 min-w-0 flex-1 overflow-auto">
          {ActiveView ? <ActiveView /> : null}
        </main>
        <StatusBar />
      </div>
    </div>
  );
}

AppShell.propTypes = {
  views: PropTypes.objectOf(PropTypes.elementType).isRequired,
  defaultView: PropTypes.string,
};

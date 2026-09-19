import { AppShell } from '@/components/layout/AppShell.jsx';
import { AuthProvider, useAuth } from '@/context/AuthContext.jsx';
import { SessionProvider } from '@/context/SessionContext.jsx';
import { AnalystConsole } from '@/views/AnalystConsole.jsx';
import { CompliancePortal } from '@/views/CompliancePortal.jsx';
import { LoginPage } from '@/views/LoginPage.jsx';
import { RedTeamLab } from '@/views/RedTeamLab.jsx';
import { SeniorShield } from '@/views/SeniorShield.jsx';
import { VIEWS } from '@/theme.js';

/** Stable view → component map (avoid remounting from fresh element trees). */
const VIEW_COMPONENTS = {
  [VIEWS.ANALYST]: AnalystConsole,
  [VIEWS.SENIOR_SHIELD]: SeniorShield,
  [VIEWS.COMPLIANCE]: CompliancePortal,
  [VIEWS.RED_TEAM]: RedTeamLab,
};

function AuthenticatedApp() {
  const { isAuthenticated } = useAuth();
  if (!isAuthenticated) {
    return <LoginPage />;
  }
  return (
    <SessionProvider>
      <AppShell views={VIEW_COMPONENTS} />
    </SessionProvider>
  );
}

/**
 * Root — AuthProvider gates the ops shell; SessionProvider mounts after login.
 */
export default function App() {
  return (
    <AuthProvider>
      <AuthenticatedApp />
    </AuthProvider>
  );
}

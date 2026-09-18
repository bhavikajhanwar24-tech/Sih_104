import { AppShell } from '@/components/layout/AppShell.jsx';
import { SessionProvider } from '@/context/SessionContext.jsx';
import { AnalystConsole } from '@/views/AnalystConsole.jsx';
import { CompliancePortal } from '@/views/CompliancePortal.jsx';
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

/**
 * Root — SessionProvider + AppShell with the four primary views.
 */
export default function App() {
  return (
    <SessionProvider>
      <AppShell views={VIEW_COMPONENTS} />
    </SessionProvider>
  );
}

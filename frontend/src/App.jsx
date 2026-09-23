import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { lazy, Suspense } from 'react';
import { AuthProvider } from '@/context/AuthContext.jsx';
import { RequireAuth } from '@/components/auth/RequireAuth.jsx';
import { AppShell } from '@/components/layout/AppShell.jsx';
import { ToastProvider } from '@/ui/Toast.jsx';
import { LoginPage } from '@/pages/LoginPage.jsx';
import { RegisterPage } from '@/pages/RegisterPage.jsx';

const UsersPage = lazy(() =>
  import('@/pages/UsersPage.jsx').then((m) => ({ default: m.UsersPage })),
);
const AuditPage = lazy(() =>
  import('@/pages/AuditPage.jsx').then((m) => ({ default: m.AuditPage })),
);
const DirectoryPage = lazy(() =>
  import('@/pages/DirectoryPage.jsx').then((m) => ({ default: m.DirectoryPage })),
);
const DashboardPage = lazy(() =>
  import('@/pages/DashboardPage.jsx').then((m) => ({ default: m.DashboardPage })),
);
const ApprovalsPage = lazy(() =>
  import('@/pages/ApprovalsPage.jsx').then((m) => ({ default: m.ApprovalsPage })),
);
const ChangeHistoryPage = lazy(() =>
  import('@/pages/ChangeHistoryPage.jsx').then((m) => ({ default: m.ChangeHistoryPage })),
);
const LiveCallsPage = lazy(() =>
  import('@/pages/LiveCallsPage.jsx').then((m) => ({ default: m.LiveCallsPage })),
);
const CallHistoryPage = lazy(() =>
  import('@/pages/CallHistoryPage.jsx').then((m) => ({ default: m.CallHistoryPage })),
);
const CallDetailPage = lazy(() =>
  import('@/pages/CallDetailPage.jsx').then((m) => ({ default: m.CallDetailPage })),
);
const ResponsePlansPage = lazy(() =>
  import('@/pages/ResponsePlansPage.jsx').then((m) => ({ default: m.ResponsePlansPage })),
);
const PoliciesPage = lazy(() =>
  import('@/pages/PoliciesPage.jsx').then((m) => ({ default: m.PoliciesPage })),
);
const PolicyReviewPage = lazy(() =>
  import('@/pages/PolicyReviewPage.jsx').then((m) => ({ default: m.PolicyReviewPage })),
);
const SettingsPage = lazy(() =>
  import('@/pages/SettingsPage.jsx').then((m) => ({ default: m.SettingsPage })),
);
const RiskTuningPage = lazy(() =>
  import('@/pages/RiskTuningPage.jsx').then((m) => ({ default: m.RiskTuningPage })),
);
const TelephonySettingsPage = lazy(() =>
  import('@/pages/TelephonySettingsPage.jsx').then((m) => ({ default: m.TelephonySettingsPage })),
);
const IntegrationsPage = lazy(() =>
  import('@/pages/IntegrationsPage.jsx').then((m) => ({ default: m.IntegrationsPage })),
);

function RouteFallback() {
  return (
    <div className="flex h-full min-h-[12rem] items-center justify-center p-6 text-sm text-sv-muted">
      Loading…
    </div>
  );
}

/**
 * V2 application root — router + cookie auth. Replaces v1 four-view AppShell switcher.
 */
export default function App() {
  return (
    <BrowserRouter
      future={{
        v7_startTransition: true,
        v7_relativeSplatPath: true,
      }}
    >
      <ToastProvider>
        <AuthProvider>
          <Suspense fallback={<RouteFallback />}>
            <Routes>
              <Route path="/" element={<Navigate to="/app" replace />} />
              <Route path="/login" element={<LoginPage />} />
              <Route path="/register" element={<RegisterPage />} />
              <Route element={<RequireAuth />}>
                <Route path="/app" element={<AppShell />}>
                  <Route index element={<DashboardPage />} />
                  <Route path="dashboard" element={<Navigate to="/app" replace />} />
                  <Route path="approvals" element={<ApprovalsPage />} />
                  <Route path="changes" element={<ChangeHistoryPage />} />
                  <Route path="live" element={<LiveCallsPage />} />
                  <Route path="calls" element={<Navigate to="/app/live" replace />} />
                  <Route path="history" element={<CallHistoryPage />} />
                  <Route path="sessions" element={<Navigate to="/app/history" replace />} />
                  <Route path="sessions/:id" element={<CallDetailPage />} />
                  <Route path="directory" element={<DirectoryPage />} />
                  <Route path="policies" element={<PoliciesPage />} />
                  <Route path="policies/review" element={<PolicyReviewPage />} />
                  <Route path="response" element={<ResponsePlansPage />} />
                  <Route path="response-plans" element={<ResponsePlansPage />} />
                  <Route path="audit" element={<AuditPage />} />
                  <Route path="settings" element={<SettingsPage />} />
                  <Route path="settings/risk-tuning" element={<RiskTuningPage />} />
                  <Route path="settings/telephony" element={<TelephonySettingsPage />} />
                  <Route path="settings/integrations" element={<IntegrationsPage />} />
                  <Route path="users" element={<UsersPage />} />
                </Route>
              </Route>
              <Route path="*" element={<Navigate to="/app" replace />} />
            </Routes>
          </Suspense>
        </AuthProvider>
      </ToastProvider>
    </BrowserRouter>
  );
}

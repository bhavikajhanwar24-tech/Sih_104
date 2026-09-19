import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { AuthProvider } from '@/context/AuthContext.jsx';
import { RequireAuth } from '@/components/auth/RequireAuth.jsx';
import { AppShell } from '@/components/layout/AppShell.jsx';
import { ToastProvider } from '@/ui/Toast.jsx';
import { LoginPage } from '@/pages/LoginPage.jsx';
import { RegisterPage } from '@/pages/RegisterPage.jsx';
import { UsersPage } from '@/pages/UsersPage.jsx';
import { AuditPage } from '@/pages/AuditPage.jsx';
import { DirectoryPage } from '@/pages/DirectoryPage.jsx';
import {
  DashboardPage,
  LiveCallsPage,
  PoliciesPage,
  ResponsePlansPage,
  SettingsPage,
} from '@/pages/ComingSoonPages.jsx';

/**
 * V2 application root — router + cookie auth. Replaces v1 four-view AppShell switcher.
 */
export default function App() {
  return (
    <BrowserRouter>
      <ToastProvider>
        <AuthProvider>
          <Routes>
            <Route path="/" element={<Navigate to="/app" replace />} />
            <Route path="/login" element={<LoginPage />} />
            <Route path="/register" element={<RegisterPage />} />
            <Route element={<RequireAuth />}>
              <Route path="/app" element={<AppShell />}>
                <Route index element={<DashboardPage />} />
                <Route path="calls" element={<LiveCallsPage />} />
                <Route path="directory" element={<DirectoryPage />} />
                <Route path="policies" element={<PoliciesPage />} />
                <Route path="response" element={<ResponsePlansPage />} />
                <Route path="audit" element={<AuditPage />} />
                <Route path="settings" element={<SettingsPage />} />
                <Route path="users" element={<UsersPage />} />
              </Route>
            </Route>
            <Route path="*" element={<Navigate to="/app" replace />} />
          </Routes>
        </AuthProvider>
      </ToastProvider>
    </BrowserRouter>
  );
}

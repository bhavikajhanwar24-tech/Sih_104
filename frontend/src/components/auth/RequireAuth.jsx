import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { useAuth } from '@/context/AuthContext.jsx';

/**
 * Requires a valid session (/me). Redirects to /login on 401/unauthenticated.
 */
export function RequireAuth() {
  const { loading, isAuthenticated } = useAuth();
  const location = useLocation();

  if (loading) {
    return (
      <div className="flex h-full items-center justify-center bg-sv-bg text-sm text-sv-muted">
        Checking session…
      </div>
    );
  }

  if (!isAuthenticated) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }

  return <Outlet />;
}

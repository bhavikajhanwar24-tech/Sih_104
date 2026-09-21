import { EmptyState } from '@/ui/EmptyState.jsx';

export function DashboardPage() {
  return (
    <div className="p-6">
      <EmptyState
        title="Dashboard"
        feature="F6"
        description="Live risk overview and tenant KPIs will appear here once scoring and session planes are wired for multi-tenant."
      />
    </div>
  );
}

export function LiveCallsPage() {
  return (
    <div className="p-6">
      <EmptyState
        title="Live Calls"
        feature="F13"
        description="Analyst live-call console relocates here once sessions are tenant-scoped and the presentation plane is remounted."
      />
    </div>
  );
}

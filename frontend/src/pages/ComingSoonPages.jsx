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
        feature="F6"
        description="Analyst live-call console relocates here. The v1 Analyst Console remains available for remount once sessions are tenant-scoped."
      />
    </div>
  );
}

export function ResponsePlansPage() {
  return (
    <div className="p-6">
      <EmptyState
        title="Response Plans"
        feature="F8"
        description="Response-matrix drafts and approvals land in F8."
      />
    </div>
  );
}


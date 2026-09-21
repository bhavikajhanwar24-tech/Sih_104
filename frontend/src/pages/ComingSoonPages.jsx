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

import { EmptyState } from '@/ui/EmptyState.jsx';

/** Placeholder pages kept for any residual imports. Dashboard moved to DashboardPage.jsx */
export function DashboardPage() {
  return (
    <div className="p-6">
      <EmptyState title="Dashboard moved" description="See /app (DashboardPage)." />
    </div>
  );
}

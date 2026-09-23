import { useCallback, useEffect, useMemo, useState } from 'react';
import { Navigate } from 'react-router-dom';
import { useAuth } from '@/context/AuthContext.jsx';
import { apiJson } from '@/services/api.js';
import { Badge, Button, Input, Modal, Select, Table } from '@/ui';
import { useToast } from '@/ui/Toast.jsx';

const ROLES = [
  { value: 'TENANT_ADMIN', label: 'Tenant admin' },
  { value: 'POLICY_APPROVER', label: 'Policy approver' },
  { value: 'ANALYST', label: 'Analyst' },
  { value: 'SUPERVISOR', label: 'Supervisor' },
  { value: 'AUDITOR', label: 'Auditor' },
];

export function UsersPage() {
  const { hasPermission, me } = useAuth();
  const { push } = useToast();
  const [users, setUsers] = useState([]);
  const [loading, setLoading] = useState(true);
  const [inviteOpen, setInviteOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [invite, setInvite] = useState({
    email: '',
    displayName: '',
    role: 'ANALYST',
    temporaryPassword: '',
  });

  const canWrite = hasPermission('users:write');

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = await apiJson('/api/v2/users');
      setUsers(data.users || []);
    } catch (err) {
      push(err.message || 'Failed to load users');
    } finally {
      setLoading(false);
    }
  }, [push]);

  useEffect(() => {
    if (hasPermission('users:read')) load();
  }, [hasPermission, load]);

  if (!hasPermission('users:read')) {
    return <Navigate to="/app" replace />;
  }

  const columns = useMemo(
    () => [
      { key: 'displayName', header: 'Name' },
      { key: 'email', header: 'Email' },
      {
        key: 'role',
        header: 'Role',
        render: (row) =>
          canWrite && row.id !== me?.user?.id && row.status === 'ACTIVE' ? (
            <select
              className="rounded border border-sv-border bg-sv-elevated px-2 py-1 text-xs"
              value={row.role}
              aria-label={`Role for ${row.email}`}
              onChange={async (e) => {
                try {
                  await apiJson(`/api/v2/users/${row.id}/role`, {
                    method: 'POST',
                    body: JSON.stringify({ role: e.target.value }),
                  });
                  await load();
                } catch (err) {
                  push(err.message || 'Role change failed');
                }
              }}
            >
              {ROLES.map((r) => (
                <option key={r.value} value={r.value}>
                  {r.label}
                </option>
              ))}
            </select>
          ) : (
            <span className="font-mono text-xs">{row.role}</span>
          ),
      },
      {
        key: 'status',
        header: 'Status',
        render: (row) => (
          <Badge tone={row.status === 'ACTIVE' ? 'success' : 'neutral'}>{row.status}</Badge>
        ),
      },
      {
        key: 'mfaEnabled',
        header: 'MFA',
        render: (row) => (
          <Badge tone={row.mfaEnabled ? 'accent' : 'warn'}>
            {row.mfaEnabled ? 'Enabled' : 'Off'}
          </Badge>
        ),
      },
      {
        key: 'actions',
        header: '',
        render: (row) =>
          canWrite && row.status === 'ACTIVE' && row.id !== me?.user?.id ? (
            <Button
              variant="danger"
              className="!px-2 !py-1 text-xs"
              onClick={async () => {
                try {
                  await apiJson(`/api/v2/users/${row.id}/disable`, { method: 'POST' });
                  await load();
                } catch (err) {
                  push(err.message || 'Disable failed');
                }
              }}
            >
              Disable
            </Button>
          ) : null,
      },
    ],
    [canWrite, load, me?.user?.id, push],
  );

  const submitInvite = async () => {
    setBusy(true);
    try {
      await apiJson('/api/v2/users', {
        method: 'POST',
        skipErrorToast: true,
        body: JSON.stringify(invite),
      });
      setInviteOpen(false);
      setInvite({ email: '', displayName: '', role: 'ANALYST', temporaryPassword: '' });
      await load();
    } catch (err) {
      push(err.message || 'Invite failed');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="mx-auto max-w-6xl space-y-6 p-6">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="font-display text-2xl font-semibold text-sv-fg">Users</h1>
          <p className="mt-1 text-sm text-sv-muted">
            Tenant-scoped accounts and roles. Changes are written to the audit chain.
          </p>
        </div>
        {canWrite ? (
          <Button onClick={() => setInviteOpen(true)}>Invite user</Button>
        ) : null}
      </div>
      {loading ? (
        <p className="text-sm text-sv-muted">Loading…</p>
      ) : (
        <Table columns={columns} rows={users} rowKey={(r) => r.id} />
      )}

      <Modal
        open={inviteOpen}
        title="Invite user"
        onClose={() => setInviteOpen(false)}
        footer={
          <>
            <Button variant="ghost" onClick={() => setInviteOpen(false)}>
              Cancel
            </Button>
            <Button disabled={busy} onClick={submitInvite}>
              {busy ? 'Creating…' : 'Create'}
            </Button>
          </>
        }
      >
        <div className="space-y-3">
          <Input
            label="Display name"
            value={invite.displayName}
            onChange={(e) => setInvite((i) => ({ ...i, displayName: e.target.value }))}
          />
          <Input
            label="Email"
            type="email"
            value={invite.email}
            onChange={(e) => setInvite((i) => ({ ...i, email: e.target.value }))}
          />
          <Select
            label="Role"
            options={ROLES}
            value={invite.role}
            onChange={(e) => setInvite((i) => ({ ...i, role: e.target.value }))}
          />
          <Input
            label="Temporary password"
            type="password"
            value={invite.temporaryPassword}
            onChange={(e) => setInvite((i) => ({ ...i, temporaryPassword: e.target.value }))}
            hint="Min 12 characters"
          />
        </div>
      </Modal>
    </div>
  );
}

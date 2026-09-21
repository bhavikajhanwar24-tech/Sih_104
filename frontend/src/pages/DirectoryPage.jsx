import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Navigate } from 'react-router-dom';
import { useAuth } from '@/context/AuthContext.jsx';
import { apiFetch, apiJson, ensureCsrf } from '@/services/api.js';
import { Badge, Button, EmptyState, Input, Modal, Select, Stepper, Table, Tabs } from '@/ui';
import { useToast } from '@/ui/Toast.jsx';

const TABS = [
  { id: 'employees', label: 'Employees' },
  { id: 'departments', label: 'Departments' },
  { id: 'externals', label: 'External Parties' },
  { id: 'beneficiaries', label: 'Beneficiaries' },
  { id: 'imports', label: 'Imports' },
];

const STATUS_TONE = {
  ACTIVE: 'success',
  ON_LEAVE: 'warn',
  TRAVELLING: 'accent',
  SUSPENDED: 'danger',
  TERMINATED: 'neutral',
};

function initials(name) {
  if (!name) return '?';
  return name
    .split(/\s+/)
    .slice(0, 2)
    .map((p) => p[0]?.toUpperCase() || '')
    .join('');
}

function SkeletonRows({ cols = 5 }) {
  return (
    <div className="space-y-2 p-4" aria-busy="true">
      {Array.from({ length: 6 }).map((_, i) => (
        <div key={i} className="h-10 animate-pulse rounded bg-sv-elevated" style={{ opacity: 1 - i * 0.08 }} />
      ))}
      <span className="sr-only">Loading {cols} columns</span>
    </div>
  );
}

export function DirectoryPage() {
  const { hasPermission } = useAuth();
  const { push } = useToast();
  const canWrite = hasPermission('directory:write');
  const canRead = hasPermission('directory:read');
  const [tab, setTab] = useState('employees');

  if (!canRead) {
    return <Navigate to="/app" replace />;
  }

  return (
    <div className="flex h-full min-h-0 flex-col gap-4 p-6">
      <header className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="font-display text-2xl font-semibold text-sv-fg">Directory</h1>
          <p className="mt-1 text-sm text-sv-muted">
            People, org structure, authority limits, and trusted external parties.
          </p>
        </div>
      </header>
      <Tabs tabs={TABS} value={tab} onChange={setTab} />
      <div className="min-h-0 flex-1 overflow-auto">
        {tab === 'employees' ? <EmployeesTab canWrite={canWrite} push={push} /> : null}
        {tab === 'departments' ? <DepartmentsTab canWrite={canWrite} push={push} /> : null}
        {tab === 'externals' ? <ExternalsTab canWrite={canWrite} push={push} /> : null}
        {tab === 'beneficiaries' ? <BeneficiariesTab canWrite={canWrite} push={push} /> : null}
        {tab === 'imports' ? <ImportsTab canWrite={canWrite} push={push} /> : null}
      </div>
    </div>
  );
}

function EmployeesTab({ canWrite, push }) {
  const [q, setQ] = useState('');
  const [qDebounced, setQDebounced] = useState('');
  const [status, setStatus] = useState('');
  const [items, setItems] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [selected, setSelected] = useState(null);
  const [addOpen, setAddOpen] = useState(false);
  const [form, setForm] = useState({
    employeeCode: '',
    fullName: '',
    email: '',
    roleKey: '',
    jobTitle: '',
  });
  const [departments, setDepartments] = useState([]);
  const [sipByEmployee, setSipByEmployee] = useState(/** @type {Record<string, any>} */ ({}));
  const skipFirstSearchEffect = useRef(true);

  useEffect(() => {
    const t = window.setTimeout(() => setQDebounced(q), 300);
    return () => window.clearTimeout(t);
  }, [q]);

  const load = useCallback(async (opts = {}) => {
    const showSpinner = opts.showSpinner !== false;
    const query = opts.q !== undefined ? opts.q : qDebounced;
    const statusFilter = opts.status !== undefined ? opts.status : status;
    if (showSpinner) setLoading(true);
    setError(null);
    try {
      const params = new URLSearchParams({ page: '0', size: '50' });
      if (query) params.set('q', query);
      if (statusFilter) params.set('status', statusFilter);
      const data = await apiJson(`/api/v2/directory/employees?${params}`);
      setItems(data.items || []);
      const deps = await apiJson('/api/v2/directory/departments');
      setDepartments(deps.items || []);
      try {
        const eps = await apiJson('/api/v2/telephony/endpoints', { skipErrorToast: true });
        /** @type {Record<string, any>} */
        const map = {};
        for (const ep of Array.isArray(eps) ? eps : []) {
          if (ep.employeeId) map[ep.employeeId] = ep;
        }
        setSipByEmployee(map);
      } catch {
        setSipByEmployee({});
      }
    } catch (err) {
      setError(err.message || 'Failed to load employees');
      push(err.message || 'Failed to load employees');
    } finally {
      if (showSpinner) setLoading(false);
    }
  }, [qDebounced, status, push]);

  // Status / first paint — may show skeleton.
  useEffect(() => {
    load({ showSpinner: true, q: qDebounced, status });
    // eslint-disable-next-line react-hooks/exhaustive-deps -- status-driven only
  }, [status]);

  // Debounced search — silent so the input keeps focus while typing.
  useEffect(() => {
    if (skipFirstSearchEffect.current) {
      skipFirstSearchEffect.current = false;
      return undefined;
    }
    load({ showSpinner: false, q: qDebounced, status });
    return undefined;
    // eslint-disable-next-line react-hooks/exhaustive-deps -- search debounce only
  }, [qDebounced]);

  const columns = useMemo(
    () => [
      {
        key: 'fullName',
        header: 'Employee',
        render: (row) => (
          <button
            type="button"
            className="flex items-center gap-3 text-left hover:text-sv-accent"
            onClick={async () => {
              try {
                const detail = await apiJson(`/api/v2/directory/employees/${row.id}`);
                setSelected(detail);
              } catch (err) {
                push(err.message || 'Failed to open employee');
              }
            }}
          >
            <span className="flex h-8 w-8 items-center justify-center rounded-full bg-sv-elevated text-xs font-semibold">
              {initials(row.fullName)}
            </span>
            <span>
              <span className="block font-medium">{row.fullName}</span>
              <span className="block text-xs text-sv-muted">{row.employeeCode}</span>
            </span>
          </button>
        ),
      },
      { key: 'roleKey', header: 'Role', render: (r) => r.roleKey || '—' },
      { key: 'departmentName', header: 'Department', render: (r) => r.departmentName || '—' },
      {
        key: 'status',
        header: 'Status',
        render: (r) => <Badge tone={STATUS_TONE[r.status] || 'neutral'}>{r.status}</Badge>,
      },
      {
        key: 'sip',
        header: 'SIP',
        render: (r) => {
          const ep = sipByEmployee[r.id];
          if (!ep) {
            return <span className="text-xs text-sv-muted">—</span>;
          }
          const title = ep.onCall
            ? 'On call'
            : ep.registered
              ? 'Registered'
              : 'Not registered';
          return (
            <span className="inline-flex items-center gap-1.5 text-xs" title={title}>
              <span
                className={`inline-block h-2 w-2 rounded-full ${
                  ep.onCall ? 'bg-amber-400' : ep.registered ? 'bg-emerald-400' : 'bg-sv-border'
                }`}
              />
              <span className="font-mono">{ep.extension}</span>
              {ep.onCall ? <span className="text-amber-200">live</span> : null}
            </span>
          );
        },
      },
      { key: 'primaryPhone', header: 'Phone', render: (r) => r.primaryPhone || '—' },
    ],
    [push, sipByEmployee],
  );

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2">
        <Input
          placeholder="Search name, code, role…"
          value={q}
          onChange={(e) => setQ(e.target.value)}
          className="max-w-xs"
        />
        <Select value={status} onChange={(e) => setStatus(e.target.value)} aria-label="Status filter">
          <option value="">All statuses</option>
          {Object.keys(STATUS_TONE).map((s) => (
            <option key={s} value={s}>
              {s}
            </option>
          ))}
        </Select>
        <Button variant="ghost" onClick={() => load({ showSpinner: true })}>
          Refresh
        </Button>
        {canWrite ? (
          <Button onClick={() => setAddOpen(true)} className="ml-auto">
            Add employee
          </Button>
        ) : null}
      </div>

      {loading ? <SkeletonRows /> : null}
      {error && !loading ? (
        <EmptyState title="Could not load employees" description={error} />
      ) : null}
      {!loading && !error && items.length === 0 ? (
        <EmptyState
          title="No employees yet"
          description="Add your first employee or import a CSV from the Imports tab."
        />
      ) : null}
      {!loading && !error && items.length > 0 ? (
        <Table columns={columns} rows={items} rowKey={(r) => r.id} />
      ) : null}

      <EmployeeDrawer
        employee={selected}
        onClose={() => setSelected(null)}
        canWrite={canWrite}
        push={push}
        onChanged={async () => {
          await load();
          if (selected?.id) {
            const detail = await apiJson(`/api/v2/directory/employees/${selected.id}`);
            setSelected(detail);
          }
        }}
      />

      <Modal
        open={addOpen}
        title="Add employee"
        onClose={() => setAddOpen(false)}
        footer={
          <>
            <Button variant="ghost" onClick={() => setAddOpen(false)}>
              Cancel
            </Button>
            <Button
              onClick={async () => {
                if (!form.employeeCode.trim() || !form.fullName.trim()) {
                  push('employeeCode and fullName are required');
                  return;
                }
                try {
                  await apiJson('/api/v2/directory/employees', {
                    method: 'POST',
                    body: JSON.stringify(form),
                  });
                  setAddOpen(false);
                  setForm({ employeeCode: '', fullName: '', email: '', roleKey: '', jobTitle: '' });
                  await load();
                  push('Employee created');
                } catch (err) {
                  push(err.message || 'Create failed');
                }
              }}
            >
              Create
            </Button>
          </>
        }
      >
        <div className="grid gap-3">
          <Input
            label="Employee code"
            value={form.employeeCode}
            onChange={(e) => setForm((f) => ({ ...f, employeeCode: e.target.value }))}
          />
          <Input
            label="Full name"
            value={form.fullName}
            onChange={(e) => setForm((f) => ({ ...f, fullName: e.target.value }))}
          />
          <Input
            label="Email"
            value={form.email}
            onChange={(e) => setForm((f) => ({ ...f, email: e.target.value }))}
          />
          <Input
            label="Role key"
            value={form.roleKey}
            onChange={(e) => setForm((f) => ({ ...f, roleKey: e.target.value }))}
            placeholder="CFO"
          />
          <Input
            label="Job title"
            value={form.jobTitle}
            onChange={(e) => setForm((f) => ({ ...f, jobTitle: e.target.value }))}
          />
          {departments.length ? (
            <Select
              label="Department"
              value={form.departmentId || ''}
              onChange={(e) => setForm((f) => ({ ...f, departmentId: e.target.value || undefined }))}
            >
              <option value="">None</option>
              {departments.map((d) => (
                <option key={d.id} value={d.id}>
                  {d.name}
                </option>
              ))}
            </Select>
          ) : null}
        </div>
      </Modal>
    </div>
  );
}

function CopyField({ label, value }) {
  const { push } = useToast();
  return (
    <div className="flex items-end gap-2">
      <Input label={label} value={value || ''} readOnly className="flex-1 font-mono text-xs" />
      <Button
        type="button"
        variant="ghost"
        className="shrink-0 px-2 py-2 text-xs"
        disabled={!value}
        onClick={async () => {
          try {
            await navigator.clipboard.writeText(String(value));
            push(`Copied ${label}`);
          } catch {
            push('Copy failed');
          }
        }}
      >
        Copy
      </Button>
    </div>
  );
}

/**
 * F10 — Directory employee drawer → Telephony (SIP account + Zoiper setup).
 */
function EmployeeTelephonyTab({ employee, canWrite, push, onChanged }) {
  const [endpoint, setEndpoint] = useState(null);
  const [defaults, setDefaults] = useState(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  /** @type {[null | { username: string, plaintextPassword: string, domain: string, port: number, transport: string, extension: string }, Function]} */
  const [reveal, setReveal] = useState(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [eps, soft] = await Promise.all([
        apiJson(`/api/v2/telephony/endpoints?employeeId=${employee.id}`, { skipErrorToast: true }),
        apiJson('/api/v2/telephony/softphone-defaults', { skipErrorToast: true }).catch(() => ({})),
      ]);
      setEndpoint(Array.isArray(eps) && eps.length ? eps[0] : null);
      setDefaults(soft || {});
    } catch {
      setEndpoint(null);
    } finally {
      setLoading(false);
    }
  }, [employee.id]);

  useEffect(() => {
    load();
  }, [load]);

  async function createAccount() {
    setBusy(true);
    try {
      const row = await apiJson(`/api/v2/telephony/endpoints/for-employee/${employee.id}`, {
        method: 'POST',
      });
      setReveal({
        username: row.username,
        plaintextPassword: row.plaintextPassword,
        domain: row.domain,
        port: row.port,
        transport: row.transport,
        extension: row.extension,
      });
      push('SIP account created — copy the password now; it is shown once.');
      await load();
      if (onChanged) await onChanged();
    } catch (err) {
      push(err.message || 'Create SIP account failed');
    } finally {
      setBusy(false);
    }
  }

  async function resetPassword() {
    if (!endpoint?.id) return;
    setBusy(true);
    try {
      const row = await apiJson(`/api/v2/telephony/endpoints/${endpoint.id}/reset-password`, {
        method: 'POST',
      });
      setReveal({
        username: row.username,
        plaintextPassword: row.plaintextPassword,
        domain: row.domain,
        port: row.port,
        transport: row.transport,
        extension: row.extension,
      });
      push('Password reset — copy the new password now.');
      await load();
    } catch (err) {
      push(err.message || 'Reset password failed');
    } finally {
      setBusy(false);
    }
  }

  if (loading) {
    return <p className="text-sm text-sv-muted">Loading telephony…</p>;
  }

  const domain = reveal?.domain || defaults?.domain || '—';
  const port = reveal?.port ?? defaults?.port ?? 5060;
  const transport = reveal?.transport || defaults?.transport || 'UDP';

  return (
    <div className="space-y-4">
      {!endpoint ? (
        <div className="space-y-3">
          <p className="text-sm text-sv-muted">
            No SIP softphone account yet. Create one to register Zoiper / MicroSIP against Asterisk.
          </p>
          {canWrite ? (
            <Button disabled={busy} onClick={createAccount}>
              Create SIP account
            </Button>
          ) : null}
        </div>
      ) : (
        <div className="space-y-3">
          <div className="flex flex-wrap items-center gap-3 rounded border border-sv-border px-3 py-2">
            <span
              className={`inline-block h-2.5 w-2.5 rounded-full ${
                endpoint.registered ? 'bg-emerald-400' : 'bg-sv-border'
              }`}
              title={endpoint.registered ? 'Registered' : 'Not registered'}
            />
            <div className="text-sm">
              <div className="font-medium">
                Ext <span className="font-mono">{endpoint.extension}</span>
                <Badge tone={endpoint.status === 'ACTIVE' ? 'success' : 'neutral'} className="ml-2">
                  {endpoint.status}
                </Badge>
              </div>
              <div className="text-xs text-sv-muted">
                {endpoint.registered ? 'Registered' : 'Not registered'}
                {endpoint.lastRegisteredAt
                  ? ` · last seen ${new Date(endpoint.lastRegisteredAt).toLocaleString()}`
                  : ''}
              </div>
            </div>
          </div>
          <CopyField label="Username" value={endpoint.username} />
          <CopyField label="Extension" value={endpoint.extension} />
          {canWrite ? (
            <div className="flex flex-wrap gap-2">
              <Button variant="secondary" disabled={busy} onClick={resetPassword}>
                Reset password
              </Button>
            </div>
          ) : null}
        </div>
      )}

      {reveal ? (
        <div className="space-y-3 rounded border border-amber-500/40 bg-amber-500/10 p-3">
          <p className="text-xs font-medium text-amber-100">
            Password shown once — save it now. It cannot be retrieved later.
          </p>
          <CopyField label="Password" value={reveal.plaintextPassword} />
          <div className="rounded border border-sv-border bg-sv-panel/60 p-3">
            <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-sv-muted">
              Zoiper / softphone
            </p>
            <div className="space-y-2">
              <CopyField label="Username" value={reveal.username} />
              <CopyField label="Password" value={reveal.plaintextPassword} />
              <CopyField label="Domain / Host" value={domain} />
              <CopyField label="Port" value={String(port)} />
              <CopyField label="Transport" value={transport} />
            </div>
            <p className="mt-3 text-[11px] text-sv-muted">
              Account type: SIP · Authentication name = username · Outbound proxy blank.
              QR: encode{' '}
              <code className="font-mono">
                sip:{reveal.username}@{domain}:{port}
              </code>{' '}
              with the password above.
            </p>
          </div>
          <Button variant="ghost" className="text-xs" onClick={() => setReveal(null)}>
            Dismiss password
          </Button>
        </div>
      ) : endpoint ? (
        <div className="rounded border border-sv-border bg-sv-panel/40 p-3 text-xs text-sv-muted">
          <p className="font-semibold text-sv-fg">Softphone defaults</p>
          <p className="mt-1">
            Domain <span className="font-mono text-sv-fg">{domain}</span> · port{' '}
            <span className="font-mono text-sv-fg">{port}</span> · {transport}. Reset password to
            get a new one-time secret for Zoiper.
          </p>
        </div>
      ) : null}
    </div>
  );
}

function EmployeeDrawer({ employee, onClose, canWrite, push, onChanged }) {
  const [drawerTab, setDrawerTab] = useState('profile');
  const [statusForm, setStatusForm] = useState({ status: 'ACTIVE', statusUntil: '', statusNote: '' });
  const [phoneForm, setPhoneForm] = useState({ e164: '', label: 'MOBILE', primary: true, sipExtension: '' });
  const [authForm, setAuthForm] = useState({
    actionType: 'WIRE_TRANSFER',
    maxAmountInr: '',
    requiresDualApproval: false,
    allowedChannels: ['VOICE'],
  });
  const [rels, setRels] = useState([]);

  useEffect(() => {
    if (!employee) return;
    setStatusForm({
      status: employee.status || 'ACTIVE',
      statusUntil: employee.statusUntil ? String(employee.statusUntil).slice(0, 16) : '',
      statusNote: employee.statusNote || '',
    });
    setDrawerTab('profile');
    apiJson(`/api/v2/directory/employees/${employee.id}/relationships`)
      .then((d) => setRels(d.items || []))
      .catch(() => setRels([]));
  }, [employee]);

  if (!employee) return null;

  const drawerTabs = [
    { id: 'profile', label: 'Profile' },
    { id: 'phones', label: 'Phones' },
    { id: 'telephony', label: 'Telephony' },
    { id: 'authority', label: 'Authority' },
    { id: 'relationships', label: 'Relationships' },
  ];

  return (
    <div className="fixed inset-y-0 right-0 z-40 flex w-full max-w-md flex-col border-l border-sv-border bg-sv-panel shadow-2xl">
      <div className="flex items-start justify-between gap-3 border-b border-sv-border px-5 py-4">
        <div className="flex items-center gap-3">
          <span className="flex h-10 w-10 items-center justify-center rounded-full bg-sv-elevated font-semibold">
            {initials(employee.fullName)}
          </span>
          <div>
            <h2 className="font-display text-lg font-semibold">{employee.fullName}</h2>
            <p className="text-xs text-sv-muted">
              {employee.roleKey || '—'} · {employee.employeeCode}
            </p>
          </div>
        </div>
        <Button variant="ghost" onClick={onClose} aria-label="Close">
          ✕
        </Button>
      </div>
      <div className="px-5 pt-3">
        <Tabs tabs={drawerTabs} value={drawerTab} onChange={setDrawerTab} />
      </div>
      <div className="flex-1 overflow-auto px-5 py-4">
        {drawerTab === 'profile' ? (
          <div className="space-y-4">
            <dl className="grid grid-cols-2 gap-3 text-sm">
              <div>
                <dt className="text-sv-muted">Email</dt>
                <dd>{employee.email || '—'}</dd>
              </div>
              <div>
                <dt className="text-sv-muted">Department</dt>
                <dd>{employee.departmentName || '—'}</dd>
              </div>
              <div>
                <dt className="text-sv-muted">Job title</dt>
                <dd>{employee.jobTitle || '—'}</dd>
              </div>
              <div>
                <dt className="text-sv-muted">Status</dt>
                <dd>
                  <Badge tone={STATUS_TONE[employee.status] || 'neutral'}>{employee.status}</Badge>
                </dd>
              </div>
            </dl>
            {canWrite ? (
              <div className="space-y-2 rounded-lg border border-sv-border p-3">
                <p className="text-sm font-medium">Quick status</p>
                <Select
                  value={statusForm.status}
                  onChange={(e) => setStatusForm((s) => ({ ...s, status: e.target.value }))}
                >
                  {Object.keys(STATUS_TONE).map((s) => (
                    <option key={s} value={s}>
                      {s}
                    </option>
                  ))}
                </Select>
                <Input
                  type="datetime-local"
                  label="Until"
                  value={statusForm.statusUntil}
                  onChange={(e) => setStatusForm((s) => ({ ...s, statusUntil: e.target.value }))}
                />
                <Input
                  label="Note"
                  value={statusForm.statusNote}
                  onChange={(e) => setStatusForm((s) => ({ ...s, statusNote: e.target.value }))}
                />
                <Button
                  onClick={async () => {
                    try {
                      const until = statusForm.statusUntil
                        ? new Date(statusForm.statusUntil).toISOString()
                        : null;
                      await apiJson(`/api/v2/directory/employees/${employee.id}/status`, {
                        method: 'PATCH',
                        body: JSON.stringify({
                          status: statusForm.status,
                          statusUntil: until,
                          statusNote: statusForm.statusNote || null,
                        }),
                      });
                      push('Status updated');
                      await onChanged();
                    } catch (err) {
                      push(err.message || 'Status update failed');
                    }
                  }}
                >
                  Set status
                </Button>
              </div>
            ) : null}
          </div>
        ) : null}

        {drawerTab === 'phones' ? (
          <div className="space-y-3">
            {(employee.phones || []).map((p) => (
              <div key={p.id} className="flex items-center justify-between rounded border border-sv-border px-3 py-2 text-sm">
                <div>
                  <div className="font-mono">{p.e164}</div>
                  <div className="text-xs text-sv-muted">
                    {p.label}
                    {p.primary ? ' · primary' : ''}
                    {p.sipExtension ? ` · ext ${p.sipExtension}` : ''}
                  </div>
                </div>
                {canWrite ? (
                  <Button
                    variant="ghost"
                    onClick={async () => {
                      try {
                        await apiJson(`/api/v2/directory/employees/${employee.id}/phones/${p.id}`, {
                          method: 'DELETE',
                        });
                        await onChanged();
                      } catch (err) {
                        push(err.message || 'Delete failed');
                      }
                    }}
                  >
                    Remove
                  </Button>
                ) : null}
              </div>
            ))}
            {!(employee.phones || []).length ? (
              <p className="text-sm text-sv-muted">No phones yet. Add a mobile or extension.</p>
            ) : null}
            {canWrite ? (
              <div className="space-y-2 border-t border-sv-border pt-3">
                <Input
                  label="Phone (E.164 or local)"
                  value={phoneForm.e164}
                  onChange={(e) => setPhoneForm((f) => ({ ...f, e164: e.target.value }))}
                />
                <Select
                  value={phoneForm.label}
                  onChange={(e) => setPhoneForm((f) => ({ ...f, label: e.target.value }))}
                >
                  <option value="MOBILE">MOBILE</option>
                  <option value="OFFICE">OFFICE</option>
                  <option value="HOME">HOME</option>
                </Select>
                <Input
                  label="SIP extension (directory hint)"
                  value={phoneForm.sipExtension}
                  onChange={(e) => setPhoneForm((f) => ({ ...f, sipExtension: e.target.value }))}
                />
                <Button
                  onClick={async () => {
                    try {
                      await apiJson(`/api/v2/directory/employees/${employee.id}/phones`, {
                        method: 'POST',
                        body: JSON.stringify(phoneForm),
                      });
                      setPhoneForm({ e164: '', label: 'MOBILE', primary: true, sipExtension: '' });
                      await onChanged();
                    } catch (err) {
                      push(err.message || 'Add phone failed');
                    }
                  }}
                >
                  Add phone
                </Button>
              </div>
            ) : null}
          </div>
        ) : null}

        {drawerTab === 'telephony' ? (
          <EmployeeTelephonyTab
            employee={employee}
            canWrite={canWrite}
            push={push}
            onChanged={onChanged}
          />
        ) : null}

        {drawerTab === 'authority' ? (
          <div className="space-y-3">
            {(employee.authority || []).map((a) => (
              <div key={a.id} className="rounded border border-sv-border px-3 py-2 text-sm">
                <div className="font-medium">{a.actionType}</div>
                <div className="text-xs text-sv-muted">
                  Max ₹{a.maxAmountInr ?? '—'} · dual={String(a.requiresDualApproval)} ·{' '}
                  {(a.allowedChannels || []).join(', ')}
                </div>
              </div>
            ))}
            {canWrite ? (
              <div className="space-y-2 border-t border-sv-border pt-3">
                <Input
                  label="Action type"
                  value={authForm.actionType}
                  onChange={(e) => setAuthForm((f) => ({ ...f, actionType: e.target.value }))}
                />
                <Input
                  label="Max amount INR"
                  value={authForm.maxAmountInr}
                  onChange={(e) => setAuthForm((f) => ({ ...f, maxAmountInr: e.target.value }))}
                />
                <Button
                  onClick={async () => {
                    try {
                      await apiJson(`/api/v2/directory/employees/${employee.id}/authority`, {
                        method: 'POST',
                        body: JSON.stringify({
                          ...authForm,
                          maxAmountInr: authForm.maxAmountInr ? Number(authForm.maxAmountInr) : null,
                        }),
                      });
                      await onChanged();
                    } catch (err) {
                      push(err.message || 'Add authority failed');
                    }
                  }}
                >
                  Add limit
                </Button>
              </div>
            ) : null}
          </div>
        ) : null}

        {drawerTab === 'relationships' ? (
          <div className="space-y-2">
            {rels.length === 0 ? (
              <p className="text-sm text-sv-muted">No known relationships yet. Contacts update after finalised calls (F10).</p>
            ) : (
              rels.map((r) => (
                <div key={r.id} className="rounded border border-sv-border px-3 py-2 text-sm">
                  <div>{r.relationshipType}</div>
                  <div className="text-xs text-sv-muted">
                    contacts={r.contactCount} · last={r.lastContactAt || '—'}
                  </div>
                </div>
              ))
            )}
          </div>
        ) : null}
      </div>
    </div>
  );
}

function DepartmentsTab({ canWrite, push }) {
  const [items, setItems] = useState([]);
  const [loading, setLoading] = useState(true);
  const [name, setName] = useState('');
  const [org, setOrg] = useState(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = await apiJson('/api/v2/directory/departments');
      setItems(data.items || []);
      const chart = await apiJson('/api/v2/directory/org-chart');
      setOrg(chart);
    } catch (err) {
      push(err.message || 'Failed to load departments');
    } finally {
      setLoading(false);
    }
  }, [push]);

  useEffect(() => {
    load();
  }, [load]);

  return (
    <div className="space-y-4">
      {canWrite ? (
        <div className="flex gap-2">
          <Input placeholder="New department name" value={name} onChange={(e) => setName(e.target.value)} />
          <Button
            onClick={async () => {
              if (!name.trim()) return;
              try {
                await apiJson('/api/v2/directory/departments', {
                  method: 'POST',
                  body: JSON.stringify({ name }),
                });
                setName('');
                await load();
              } catch (err) {
                push(err.message || 'Create failed');
              }
            }}
          >
            Add
          </Button>
        </div>
      ) : null}
      {loading ? <SkeletonRows cols={2} /> : null}
      {!loading && items.length === 0 ? (
        <EmptyState title="No departments" description="Create Finance, Treasury, or Branch Ops to organise employees." />
      ) : null}
      {!loading && items.length > 0 ? (
        <Table
          columns={[
            { key: 'name', header: 'Name' },
            { key: 'parentId', header: 'Parent', render: (r) => r.parentId || '—' },
          ]}
          rows={items}
        />
      ) : null}
      {org?.departments?.length ? (
        <div className="rounded-lg border border-sv-border p-4">
          <h3 className="mb-3 font-display text-sm font-semibold">Org chart</h3>
          <ul className="space-y-2 text-sm">
            {org.departments.map((d) => {
              const people = (org.employees || []).filter((e) => e.departmentId === d.id);
              return (
                <li key={d.id}>
                  <span className="font-medium text-sv-accent">{d.name}</span>
                  <ul className="ml-4 mt-1 text-sv-muted">
                    {people.map((p) => (
                      <li key={p.id}>
                        {p.fullName}
                        {p.managerId ? ' ← reports up' : ''}
                        {p.roleKey ? ` (${p.roleKey})` : ''}
                      </li>
                    ))}
                    {!people.length ? <li className="italic">No people assigned</li> : null}
                  </ul>
                </li>
              );
            })}
          </ul>
        </div>
      ) : null}
    </div>
  );
}

function ExternalsTab({ canWrite, push }) {
  const [items, setItems] = useState([]);
  const [loading, setLoading] = useState(true);
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState({ name: '', type: 'VENDOR', notes: '' });

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = await apiJson('/api/v2/directory/external-parties?page=0&size=50');
      setItems(data.items || []);
    } catch (err) {
      push(err.message || 'Failed to load');
    } finally {
      setLoading(false);
    }
  }, [push]);

  useEffect(() => {
    load();
  }, [load]);

  return (
    <div className="space-y-4">
      {canWrite ? (
        <Button className="ml-auto" onClick={() => setOpen(true)}>
          Add external party
        </Button>
      ) : null}
      {loading ? <SkeletonRows /> : null}
      {!loading && items.length === 0 ? (
        <EmptyState title="No external parties" description="Add vendors, customers, or regulators your analysts may hear about." />
      ) : null}
      {!loading && items.length > 0 ? (
        <Table
          columns={[
            { key: 'name', header: 'Name' },
            { key: 'type', header: 'Type' },
            {
              key: 'verified',
              header: 'Verified',
              render: (r) => (r.verified ? <Badge tone="success">Yes</Badge> : <Badge>No</Badge>),
            },
            { key: 'notes', header: 'Notes', render: (r) => r.notes || '—' },
          ]}
          rows={items}
        />
      ) : null}
      <Modal
        open={open}
        title="Add external party"
        onClose={() => setOpen(false)}
        footer={
          <Button
            onClick={async () => {
              try {
                await apiJson('/api/v2/directory/external-parties', {
                  method: 'POST',
                  body: JSON.stringify(form),
                });
                setOpen(false);
                await load();
              } catch (err) {
                push(err.message || 'Create failed');
              }
            }}
          >
            Create
          </Button>
        }
      >
        <div className="grid gap-3">
          <Input label="Name" value={form.name} onChange={(e) => setForm((f) => ({ ...f, name: e.target.value }))} />
          <Select value={form.type} onChange={(e) => setForm((f) => ({ ...f, type: e.target.value }))}>
            {['VENDOR', 'CUSTOMER', 'REGULATOR', 'PARTNER', 'OTHER'].map((t) => (
              <option key={t} value={t}>
                {t}
              </option>
            ))}
          </Select>
          <Input label="Notes" value={form.notes} onChange={(e) => setForm((f) => ({ ...f, notes: e.target.value }))} />
        </div>
      </Modal>
    </div>
  );
}

function BeneficiariesTab({ canWrite, push }) {
  const [items, setItems] = useState([]);
  const [loading, setLoading] = useState(true);
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState({ accountNumber: '', label: '' });

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = await apiJson('/api/v2/directory/beneficiaries?page=0&size=50');
      setItems(data.items || []);
    } catch (err) {
      push(err.message || 'Failed to load');
    } finally {
      setLoading(false);
    }
  }, [push]);

  useEffect(() => {
    load();
  }, [load]);

  return (
    <div className="space-y-4">
      {canWrite ? <Button onClick={() => setOpen(true)}>Add beneficiary</Button> : null}
      {loading ? <SkeletonRows /> : null}
      {!loading && items.length === 0 ? (
        <EmptyState
          title="No beneficiaries"
          description="Account numbers are stored as keyed HMAC only — paste the full number once when adding."
        />
      ) : null}
      {!loading && items.length > 0 ? (
        <Table
          columns={[
            { key: 'label', header: 'Label', render: (r) => r.label || '—' },
            {
              key: 'accountRefHash',
              header: 'Account hash',
              render: (r) => <span className="font-mono text-xs">{String(r.accountRefHash).slice(0, 16)}…</span>,
            },
            {
              key: 'verified',
              header: 'Verified',
              render: (r) => (r.verified ? <Badge tone="success">Yes</Badge> : <Badge>No</Badge>),
            },
          ]}
          rows={items}
        />
      ) : null}
      <Modal
        open={open}
        title="Add beneficiary"
        onClose={() => setOpen(false)}
        footer={
          <Button
            onClick={async () => {
              try {
                await apiJson('/api/v2/directory/beneficiaries', {
                  method: 'POST',
                  body: JSON.stringify(form),
                });
                setOpen(false);
                setForm({ accountNumber: '', label: '' });
                await load();
              } catch (err) {
                push(err.message || 'Create failed');
              }
            }}
          >
            Create
          </Button>
        }
      >
        <div className="grid gap-3">
          <Input
            label="Account number"
            value={form.accountNumber}
            onChange={(e) => setForm((f) => ({ ...f, accountNumber: e.target.value }))}
          />
          <Input
            label="Label"
            value={form.label}
            onChange={(e) => setForm((f) => ({ ...f, label: e.target.value }))}
          />
          <p className="text-xs text-sv-muted">Raw account number is never stored — only HMAC.</p>
        </div>
      </Modal>
    </div>
  );
}

function ImportsTab({ canWrite, push }) {
  const steps = ['Choose type', 'Template', 'Upload', 'Dry-run', 'Commit'];
  const [step, setStep] = useState(0);
  const [kind, setKind] = useState('EMPLOYEES');
  const [dry, setDry] = useState(null);
  const [history, setHistory] = useState([]);
  const [busy, setBusy] = useState(false);

  const loadHistory = useCallback(async () => {
    try {
      const data = await apiJson('/api/v2/directory/imports?page=0&size=20');
      setHistory(data.items || []);
    } catch {
      /* ignore */
    }
  }, []);

  useEffect(() => {
    loadHistory();
  }, [loadHistory]);

  if (!canWrite) {
    return (
      <EmptyState title="Imports require admin" description="Ask a tenant admin to run CSV/XLSX bulk imports." />
    );
  }

  return (
    <div className="space-y-6">
      <Stepper steps={steps} current={step} />
      {step === 0 ? (
        <div className="space-y-3">
          <Select value={kind} onChange={(e) => setKind(e.target.value)} label="Import type">
            {['EMPLOYEES', 'PHONES', 'AUTHORITY', 'EXTERNAL_PARTIES'].map((k) => (
              <option key={k} value={k}>
                {k}
              </option>
            ))}
          </Select>
          <Button onClick={() => setStep(1)}>Continue</Button>
        </div>
      ) : null}
      {step === 1 ? (
        <div className="space-y-3">
          <p className="text-sm text-sv-muted">Download the CSV template, fill rows, then upload.</p>
          <Button
            onClick={async () => {
              await ensureCsrf();
              const res = await apiFetch(`/api/v2/directory/imports/template?kind=${kind}`);
              const blob = await res.blob();
              const url = URL.createObjectURL(blob);
              const a = document.createElement('a');
              a.href = url;
              a.download = `${kind.toLowerCase()}_template.csv`;
              a.click();
              URL.revokeObjectURL(url);
              setStep(2);
            }}
          >
            Download template
          </Button>
        </div>
      ) : null}
      {step === 2 || step === 3 ? (
        <div className="space-y-3">
          <input
            type="file"
            accept=".csv,.xlsx,.xls"
            onChange={async (e) => {
              const file = e.target.files?.[0];
              if (!file) return;
              setBusy(true);
              try {
                await ensureCsrf();
                const fd = new FormData();
                fd.append('file', file);
                const res = await apiFetch(`/api/v2/directory/imports/dry-run?kind=${kind}`, {
                  method: 'POST',
                  body: fd,
                  headers: {}, // let browser set multipart boundary
                });
                // apiFetch sets Content-Type json by default only for string body — good
                if (!res.ok) {
                  const err = await res.json().catch(() => ({}));
                  throw new Error(err.message || 'Dry-run failed');
                }
                const data = await res.json();
                setDry(data);
                setStep(3);
              } catch (err) {
                push(err.message || 'Upload failed');
              } finally {
                setBusy(false);
              }
            }}
          />
          {busy ? <p className="text-sm text-sv-muted">Validating…</p> : null}
          {dry ? (
            <div className="space-y-3">
              <p className="text-sm">
                Valid: <strong>{dry.validCount}</strong> · Errors: <strong>{dry.errorCount}</strong>
              </p>
              {(dry.rowErrors || []).length > 0 ? (
                <Table
                  columns={[
                    { key: 'row', header: 'Row' },
                    {
                      key: 'errors',
                      header: 'Errors',
                      render: (r) => (
                        <span className="text-risk-critical">{(r.errors || []).join('; ')}</span>
                      ),
                    },
                  ]}
                  rows={dry.rowErrors}
                />
              ) : (
                <p className="text-sm text-risk-clear">All rows valid. Ready to commit.</p>
              )}
              <div className="flex gap-2">
                <Button variant="ghost" onClick={() => { setDry(null); setStep(2); }}>
                  Re-upload
                </Button>
                <Button
                  disabled={!dry.canCommit}
                  onClick={async () => {
                    try {
                      const result = await apiJson(`/api/v2/directory/imports/${dry.importId}/commit`, {
                        method: 'POST',
                      });
                      push(`Imported ${result.committedCount} rows`);
                      setStep(4);
                      await loadHistory();
                    } catch (err) {
                      push(err.message || 'Commit failed');
                    }
                  }}
                >
                  Confirm import
                </Button>
              </div>
            </div>
          ) : null}
        </div>
      ) : null}
      {step === 4 ? (
        <EmptyState
          title="Import committed"
          description="Rows were written in a single transaction. Start another import or open Employees."
        />
      ) : null}

      <div>
        <h3 className="mb-2 font-display text-sm font-semibold">Recent imports</h3>
        <Table
          columns={[
            { key: 'filename', header: 'File' },
            { key: 'kind', header: 'Kind' },
            { key: 'status', header: 'Status' },
            { key: 'rowCount', header: 'Rows' },
            { key: 'createdAt', header: 'When', render: (r) => String(r.createdAt || '').slice(0, 19) },
          ]}
          rows={history}
        />
      </div>
    </div>
  );
}

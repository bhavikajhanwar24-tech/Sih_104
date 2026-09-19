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
      { key: 'primaryPhone', header: 'Phone', render: (r) => r.primaryPhone || '—' },
    ],
    [push],
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
                  label="SIP extension"
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

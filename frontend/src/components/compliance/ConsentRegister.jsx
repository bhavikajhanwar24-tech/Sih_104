import { useCallback, useEffect, useState } from 'react';
import { apiFetch } from '@/services/api.js';

/**
 * Consent register — F15 GET /api/v2/compliance/consents
 */
export function ConsentRegister() {
  const [rows, setRows] = useState(/** @type {any[]} */ ([]));
  const [error, setError] = useState(/** @type {string | null} */ (null));
  const [busyId, setBusyId] = useState(/** @type {string | null} */ (null));

  const load = useCallback(async () => {
    try {
      const res = await apiFetch('/api/v2/compliance/consents');
      if (!res.ok) throw new Error(`consent HTTP ${res.status}`);
      const data = await res.json();
      setRows(Array.isArray(data.items) ? data.items : []);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'load failed');
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const withdraw = async (row) => {
    setBusyId(row.id);
    try {
      const res = await apiFetch('/api/v2/compliance/consents', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          employeeId: row.employeeId,
          purpose: row.purpose,
          status: 'WITHDRAWN',
          method: 'ADMIN',
        }),
      });
      if (!res.ok) throw new Error(`withdraw HTTP ${res.status}`);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'withdraw failed');
    } finally {
      setBusyId(null);
    }
  };

  return (
    <div className="flex flex-col gap-2">
      <p className="text-[11px] text-sv-muted">
        Prefer the full UI at <code className="text-sv-accent">/app/compliance?tab=consent</code>.
      </p>
      {error ? <p className="text-sm text-red-400">{error}</p> : null}
      <div className="overflow-auto rounded border border-sv-border">
        <table className="w-full min-w-[560px] border-collapse text-left text-xs">
          <thead className="bg-sv-elevated text-sv-muted">
            <tr>
              <th className="px-2 py-1.5">Employee</th>
              <th className="px-2 py-1.5">Purpose</th>
              <th className="px-2 py-1.5">Notice</th>
              <th className="px-2 py-1.5">Status</th>
              <th className="px-2 py-1.5">Action</th>
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 ? (
              <tr>
                <td colSpan={5} className="px-2 py-3 text-sv-muted">
                  No consent records.
                </td>
              </tr>
            ) : (
              rows.map((r) => (
                <tr key={r.id} className="border-t border-sv-border/80">
                  <td className="px-2 py-1.5 text-sv-fg">{r.employeeName || r.employeeId}</td>
                  <td className="px-2 py-1.5 text-sv-muted">{r.purpose}</td>
                  <td className="px-2 py-1.5 font-mono text-[10px]">v{r.noticeVersion}</td>
                  <td className="px-2 py-1.5">{r.status}</td>
                  <td className="px-2 py-1.5">
                    {r.status === 'GRANTED' ? (
                      <button
                        type="button"
                        className="text-sv-accent underline-offset-2 hover:underline disabled:opacity-40"
                        disabled={busyId === r.id}
                        onClick={() => withdraw(r)}
                      >
                        Withdraw
                      </button>
                    ) : (
                      '—'
                    )}
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}

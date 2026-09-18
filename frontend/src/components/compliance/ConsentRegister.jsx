import { useCallback, useEffect, useState } from 'react';
import PropTypes from 'prop-types';

/**
 * Consent register — every ConsentRecord with DPDP §4/§5/§6 refs + withdraw (Context §13.3).
 */
export function ConsentRegister() {
  const [rows, setRows] = useState(/** @type {any[]} */ ([]));
  const [error, setError] = useState(/** @type {string | null} */ (null));
  const [busyId, setBusyId] = useState(/** @type {number | null} */ (null));

  const load = useCallback(async () => {
    try {
      const res = await fetch('/api/v1/compliance/consent');
      if (!res.ok) throw new Error(`consent HTTP ${res.status}`);
      const data = await res.json();
      setRows(Array.isArray(data.consents) ? data.consents : []);
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'load failed');
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const withdraw = async (id) => {
    setBusyId(id);
    try {
      const res = await fetch(`/api/v1/compliance/consent/${id}/withdraw`, { method: 'POST' });
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
        Lawful basis · DPDP <span className="text-sv-accent">§4</span>, notice{' '}
        <span className="text-sv-accent">§5</span>, consent{' '}
        <span className="text-sv-accent">§6</span>. Withdrawal triggers erasure path under{' '}
        <span className="text-sv-accent">§12</span>.
      </p>
      {error ? <p className="text-sm text-red-400">{error}</p> : null}
      <div className="overflow-auto rounded border border-sv-border">
        <table className="w-full min-w-[560px] border-collapse text-left text-xs">
          <thead className="bg-sv-elevated text-sv-muted">
            <tr>
              <th className="px-2 py-1.5">Employee</th>
              <th className="px-2 py-1.5">Purpose</th>
              <th className="px-2 py-1.5">Notice</th>
              <th className="px-2 py-1.5">Granted</th>
              <th className="px-2 py-1.5">Status</th>
              <th className="px-2 py-1.5">Action</th>
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 ? (
              <tr>
                <td colSpan={6} className="px-2 py-3 text-sv-muted">
                  No consent records.
                </td>
              </tr>
            ) : (
              rows.map((r) => (
                <tr key={r.id} className="border-t border-sv-border/80">
                  <td className="px-2 py-1.5 font-mono text-sv-fg">{r.employeeId}</td>
                  <td className="px-2 py-1.5 text-sv-muted">{r.purpose}</td>
                  <td className="px-2 py-1.5 font-mono text-[10px]">{r.noticeVersion}</td>
                  <td className="px-2 py-1.5 text-sv-muted">
                    {r.grantedAt ? String(r.grantedAt).slice(0, 19) : '—'}
                  </td>
                  <td className="px-2 py-1.5">
                    <span
                      className={
                        r.status === 'ACTIVE' ? 'text-emerald-400' : 'text-sv-muted line-through'
                      }
                    >
                      {r.status}
                    </span>
                  </td>
                  <td className="px-2 py-1.5">
                    {r.status === 'ACTIVE' ? (
                      <button
                        type="button"
                        disabled={busyId === r.id}
                        onClick={() => withdraw(r.id)}
                        className="rounded border border-sv-border px-2 py-0.5 text-[10px] uppercase tracking-wide text-sv-fg hover:border-red-400 hover:text-red-300 disabled:opacity-40"
                      >
                        Withdraw
                      </button>
                    ) : (
                      <span className="text-[10px] text-sv-muted">
                        {r.withdrawnAt ? String(r.withdrawnAt).slice(0, 19) : '—'}
                      </span>
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

ConsentRegister.propTypes = {};

import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { apiFetch } from '@/services/api.js';

/**
 * DPDP mapping — wording uses "supports", never "guarantees".
 */
export function DpdpMappingTable() {
  const [items, setItems] = useState([]);
  const [disclaimer, setDisclaimer] = useState('');
  const [error, setError] = useState(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const res = await apiFetch('/api/v2/compliance/dpdp-mapping');
        if (!res.ok) throw new Error(`dpdp HTTP ${res.status}`);
        const json = await res.json();
        if (!cancelled) {
          setItems(json.items || []);
          setDisclaimer(json.disclaimer || '');
        }
      } catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : 'load failed');
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  if (error) return <p className="text-sm text-red-400">{error}</p>;

  return (
    <div className="space-y-2">
      <p className="text-xs text-sv-muted">{disclaimer}</p>
      <table className="w-full border-collapse text-left text-xs">
        <thead className="text-sv-muted">
          <tr>
            <th className="border-b border-sv-border px-2 py-1.5">Obligation</th>
            <th className="border-b border-sv-border px-2 py-1.5">Supports</th>
            <th className="border-b border-sv-border px-2 py-1.5">Evidence</th>
          </tr>
        </thead>
        <tbody>
          {items.map((row) => (
            <tr key={row.obligation} className="border-b border-sv-border/50">
              <td className="px-2 py-1.5 text-sv-fg">{row.obligation}</td>
              <td className="px-2 py-1.5 text-sv-muted">{row.feature}</td>
              <td className="px-2 py-1.5">
                {String(row.evidence || '').startsWith('/app') ? (
                  <Link className="text-sv-accent" to={row.evidence}>
                    {row.evidence}
                  </Link>
                ) : (
                  <span className="font-mono text-[10px]">{row.evidence}</span>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

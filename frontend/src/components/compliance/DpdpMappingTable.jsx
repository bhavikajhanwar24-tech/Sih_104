import PropTypes from 'prop-types';

/**
 * Context §13.3 DPDP mapping — each row links to the implementing endpoint/class.
 * Answers "how exactly are you compliant?" in ten seconds.
 */
const ROWS = Object.freeze([
  {
    provision: '§4, §6',
    requirement: 'Lawful basis; free, specific, informed, unambiguous consent',
    implementation:
      'Consent record per enrolled principal: purpose, timestamp, notice version, withdrawal',
    link: '/api/v1/compliance/consent',
    linkLabel: 'GET /api/v1/compliance/consent',
  },
  {
    provision: '§5',
    requirement: 'Notice at/before consent',
    implementation: 'Enrolment UI notice (English + regional); noticeVersion on ConsentRecord',
    link: '/api/v1/passport/consent',
    linkLabel: 'POST /api/v1/passport/consent',
  },
  {
    provision: '§8(4), §8(7)',
    requirement: 'Security safeguards; erase on withdrawal or purpose expiry',
    implementation: 'Ring-buffer zero-audio, TTL purge, consent-gated embeddings',
    link: 'ml-engine/app/ring_buffer.py',
    linkLabel: 'RingBuffer + RetentionPurgeScheduler',
  },
  {
    provision: '§8(5)',
    requirement: 'Notify on breach',
    implementation: 'Documented incident procedure + anomalous access volume alerting (roadmap)',
    link: '/api/v1/compliance/retention',
    linkLabel: 'Retention metrics (access volume surface)',
  },
  {
    provision: '§11',
    requirement: 'Right to access information about processing',
    implementation: 'Passport GET returns metadata + processing log — never the raw vector',
    link: '/api/v1/passport/{profileId}',
    linkLabel: 'GET /api/v1/passport/{id}',
  },
  {
    provision: '§12',
    requirement: 'Right to correction and erasure',
    implementation: 'DELETE passport → cryptographic tombstone in audit chain',
    link: '/api/v1/passport/{profileId}',
    linkLabel: 'DELETE /api/v1/passport/{id}',
  },
  {
    provision: '§13',
    requirement: 'Right of grievance redressal',
    implementation: 'Grievance contact + SLA surfaced in this compliance portal',
    link: '#grievance',
    linkLabel: 'Compliance portal · grievance',
  },
  {
    provision: 'RBI IT / CSF',
    requirement: 'Immutable audit of automated decisions and human overrides',
    implementation: 'SHA-256 hash-chained AuditBlock ledger + live verify',
    link: '/api/v1/compliance/verify/{sessionId}',
    linkLabel: 'GET /api/v1/compliance/verify/{id}',
  },
]);

export function DpdpMappingTable() {
  return (
    <div className="overflow-auto rounded border border-sv-border">
      <table className="w-full min-w-[720px] border-collapse text-left text-xs">
        <thead className="bg-sv-elevated text-sv-muted">
          <tr>
            <th className="px-2 py-1.5">Provision</th>
            <th className="px-2 py-1.5">Requirement</th>
            <th className="px-2 py-1.5">Implementation</th>
            <th className="px-2 py-1.5">Where in code</th>
          </tr>
        </thead>
        <tbody>
          {ROWS.map((row) => (
            <tr key={row.provision} className="border-t border-sv-border/80 align-top">
              <td className="px-2 py-2 font-mono text-sv-accent">{row.provision}</td>
              <td className="px-2 py-2 text-sv-fg">{row.requirement}</td>
              <td className="px-2 py-2 text-sv-muted">{row.implementation}</td>
              <td className="px-2 py-2">
                <a
                  href={row.link.startsWith('/') || row.link.startsWith('#') ? row.link : undefined}
                  className="font-mono text-[10px] text-sv-accent underline"
                  title={row.link}
                >
                  {row.linkLabel}
                </a>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <p id="grievance" className="border-t border-sv-border px-3 py-2 text-[11px] text-sv-muted">
        Grievance: compliance@sentinelvoice.demo · SLA acknowledgement within 48 business hours
        (lab contact — replace with bank DPO for production).
      </p>
    </div>
  );
}

DpdpMappingTable.propTypes = {};

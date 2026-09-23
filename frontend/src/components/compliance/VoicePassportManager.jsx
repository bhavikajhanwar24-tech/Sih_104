import { Link } from 'react-router-dom';

/**
 * Legacy Voice Passport wizard removed — F15 flows live under Compliance DSR tab.
 */
export function VoicePassportManager({ className = '' }) {
  return (
    <div className={`rounded border border-sv-border p-4 text-sm text-sv-muted ${className}`}>
      Voice Passport enrolment and erasure moved to{' '}
      <Link className="text-sv-accent underline-offset-2 hover:underline" to="/app/compliance?tab=dsr">
        /app/compliance → Data Subject Requests
      </Link>
      . Enrolment requires GRANTED VOICE_PASSPORT consent.
    </div>
  );
}

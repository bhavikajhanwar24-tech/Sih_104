import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { apiJson } from '@/services/api.js';
import { Button, Input, Select, Stepper } from '@/ui';
import { useToast } from '@/ui/Toast.jsx';

const STEPS = ['Organisation', 'Admin account', 'Terms', 'Done'];

const INDUSTRIES = [
  { value: 'BANKING', label: 'Banking' },
  { value: 'INSURANCE', label: 'Insurance' },
  { value: 'GOVERNMENT', label: 'Government' },
  { value: 'TELECOM', label: 'Telecom' },
  { value: 'OTHER', label: 'Other' },
];

export function RegisterPage() {
  const navigate = useNavigate();
  const { push } = useToast();
  const [step, setStep] = useState(0);
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState(/** @type {{ tenantId: string, slug: string } | null} */ (null));
  const [form, setForm] = useState({
    organisationName: '',
    industry: 'BANKING',
    region: '',
    adminEmail: '',
    adminDisplayName: '',
    password: '',
    acceptedTerms: false,
  });

  const set = (key, value) => setForm((f) => ({ ...f, [key]: value }));

  const submit = async () => {
    setBusy(true);
    try {
      const data = await apiJson('/api/v2/public/tenants/register', {
        method: 'POST',
        skipErrorToast: true,
        body: JSON.stringify({
          organisationName: form.organisationName,
          industry: form.industry,
          region: form.region || null,
          adminEmail: form.adminEmail,
          adminDisplayName: form.adminDisplayName,
          password: form.password,
          acceptedTerms: form.acceptedTerms,
        }),
      });
      setResult(data);
      setStep(3);
    } catch (err) {
      push(err.message || 'Registration failed');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="flex min-h-full items-center justify-center bg-[radial-gradient(ellipse_at_top,_var(--sv-elevated)_0%,_var(--sv-bg)_55%)] px-4 py-10">
      <div className="w-full max-w-xl rounded-xl border border-sv-border bg-sv-panel/95 p-6 shadow-xl backdrop-blur sm:p-8">
        <header className="mb-6">
          <p className="font-display text-2xl font-semibold tracking-tight text-sv-fg">
            SentinelVoice
          </p>
          <p className="mt-1 text-sm text-sv-muted">Create your organisation workspace</p>
        </header>
        <Stepper steps={STEPS} current={step} />
        <div className="mt-8 space-y-4">
          {step === 0 ? (
            <>
              <Input
                label="Organisation name"
                value={form.organisationName}
                onChange={(e) => set('organisationName', e.target.value)}
                autoComplete="organization"
                required
              />
              <Select
                label="Industry"
                options={INDUSTRIES}
                value={form.industry}
                onChange={(e) => set('industry', e.target.value)}
              />
              <Input
                label="Region / country"
                value={form.region}
                onChange={(e) => set('region', e.target.value)}
                placeholder="e.g. IN"
              />
              <div className="flex justify-end pt-2">
                <Button
                  disabled={!form.organisationName.trim()}
                  onClick={() => setStep(1)}
                >
                  Continue
                </Button>
              </div>
            </>
          ) : null}
          {step === 1 ? (
            <>
              <Input
                label="Admin display name"
                value={form.adminDisplayName}
                onChange={(e) => set('adminDisplayName', e.target.value)}
                autoComplete="name"
              />
              <Input
                label="Admin email"
                type="email"
                value={form.adminEmail}
                onChange={(e) => set('adminEmail', e.target.value)}
                autoComplete="email"
              />
              <Input
                label="Password"
                type="password"
                value={form.password}
                onChange={(e) => set('password', e.target.value)}
                autoComplete="new-password"
                hint="At least 12 characters"
              />
              <div className="flex justify-between pt-2">
                <Button variant="ghost" onClick={() => setStep(0)}>
                  Back
                </Button>
                <Button
                  disabled={
                    !form.adminDisplayName.trim() ||
                    !form.adminEmail.trim() ||
                    form.password.length < 12
                  }
                  onClick={() => setStep(2)}
                >
                  Continue
                </Button>
              </div>
            </>
          ) : null}
          {step === 2 ? (
            <>
              <label className="flex items-start gap-3 text-sm text-sv-fg">
                <input
                  type="checkbox"
                  className="mt-1 h-4 w-4 rounded border-sv-border"
                  checked={form.acceptedTerms}
                  onChange={(e) => set('acceptedTerms', e.target.checked)}
                />
                <span>
                  I accept the SentinelVoice terms of service and acknowledge that audit events
                  for this tenant will be recorded in an immutable ledger.
                </span>
              </label>
              <div className="flex justify-between pt-2">
                <Button variant="ghost" onClick={() => setStep(1)}>
                  Back
                </Button>
                <Button disabled={!form.acceptedTerms || busy} onClick={submit}>
                  {busy ? 'Creating…' : 'Create organisation'}
                </Button>
              </div>
            </>
          ) : null}
          {step === 3 && result ? (
            <div className="space-y-4 text-sm">
              <p className="text-sv-fg">Your organisation is ready.</p>
              <dl className="space-y-2 rounded-md border border-sv-border bg-sv-elevated p-4 font-mono text-xs">
                <div className="flex justify-between gap-4">
                  <dt className="text-sv-muted">Tenant ID</dt>
                  <dd className="text-sv-fg">{result.tenantId}</dd>
                </div>
                <div className="flex justify-between gap-4">
                  <dt className="text-sv-muted">Slug</dt>
                  <dd className="text-sv-accent">{result.slug}</dd>
                </div>
              </dl>
              <p className="text-sv-muted">
                Use the slug with your admin email to sign in. No session tokens are issued at
                registration.
              </p>
              <Button onClick={() => navigate('/login', { state: { tenantSlug: result.slug } })}>
                Go to login
              </Button>
            </div>
          ) : null}
        </div>
        <p className="mt-8 text-center text-xs text-sv-muted">
          Already registered?{' '}
          <Link className="text-sv-accent underline-offset-2 hover:underline" to="/login">
            Sign in
          </Link>
        </p>
      </div>
    </div>
  );
}

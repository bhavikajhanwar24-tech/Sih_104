-- F13: SUPERVISOR role for bridge + co-approval
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_role_check;
ALTER TABLE users ADD CONSTRAINT users_role_check CHECK (role IN (
    'TENANT_ADMIN', 'POLICY_APPROVER', 'ANALYST', 'SUPERVISOR', 'AUDITOR'
));

-- Seeded bank directory for Context §12 / P8.1. CXO verbal authority = 0 (policy).
-- H2 MERGE so re-initialisation across SpringBootTest contexts is idempotent.

MERGE INTO directory_records (
  employee_id, name, role, department, primary_cli, extension,
  verbal_authority_limit_inr, permitted_channels, presence_status, calendar_location,
  manager_employee_id, hierarchy_level, passport_enrolled
) KEY (employee_id) VALUES
('EMP-10001', 'Arvind Mehta', 'Chief Executive Officer', 'Executive Office', '+91-22-6655-0001', '1001',
 0, 'BOARD,CBS', 'AVAILABLE', 'Mumbai HQ', NULL, 1, TRUE),

('EMP-10492', 'Rajesh Kumar', 'Chief Financial Officer', 'Finance', '+91-22-6655-0100', '1100',
 0, 'CBS', 'AVAILABLE', 'London', 'EMP-10001', 2, TRUE),

('EMP-10003', 'Priya Nair', 'Chief Operating Officer', 'Operations', '+91-22-6655-0003', '1003',
 0, 'BOARD,CBS', 'AVAILABLE', 'Mumbai HQ', 'EMP-10001', 2, TRUE),

('EMP-10004', 'Vikram Shah', 'Chief Technology Officer', 'Technology', '+91-22-6655-0004', '1004',
 0, 'BOARD,CBS', 'BUSY', 'Bengaluru', 'EMP-10001', 2, TRUE),

('EMP-20010', 'Anita Desai', 'Treasury Head', 'Treasury', '+91-22-6655-2010', '2010',
 500000, 'CBS,SWIFT', 'AVAILABLE', 'Mumbai HQ', 'EMP-10492', 3, TRUE),

('EMP-20011', 'Karan Malhotra', 'Corporate Banking Head', 'Corporate Banking', '+91-22-6655-2011', '2011',
 250000, 'CBS', 'AVAILABLE', 'Delhi', 'EMP-10003', 3, FALSE),

('EMP-30020', 'Meera Iyer', 'Payments Supervisor', 'Payments', '+91-22-6655-3020', '3020',
 200000, 'CBS,BRANCH', 'AVAILABLE', 'Mumbai HQ', 'EMP-20010', 4, TRUE),

('EMP-30021', 'Rohit Banerjee', 'Fraud Ops Supervisor', 'Fraud Operations', '+91-22-6655-3021', '3021',
 150000, 'CBS,BRANCH', 'AVAILABLE', 'Kolkata', 'EMP-10003', 4, FALSE),

('EMP-40030', 'Neha Kapoor', 'Senior Relationship Manager', 'Retail', '+91-22-6655-4030', '4030',
 100000, 'BRANCH,CBS', 'AVAILABLE', 'Pune', 'EMP-30020', 5, FALSE),

('EMP-40031', 'Suresh Patil', 'Relationship Manager', 'Retail', '+91-22-6655-4031', '4031',
 75000, 'BRANCH', 'AVAILABLE', 'Pune', 'EMP-30020', 5, FALSE),

('EMP-50040', 'Sunita Rao', 'Branch Teller', 'Retail Branch', '+91-22-6655-5040', '5040',
 100000, 'BRANCH', 'AVAILABLE', 'Andheri Branch', 'EMP-40030', 6, FALSE),

('EMP-50041', 'Aamir Khan', 'Branch Teller', 'Retail Branch', '+91-22-6655-5041', '5041',
 50000, 'BRANCH', 'AVAILABLE', 'Andheri Branch', 'EMP-40030', 6, FALSE),

('EMP-50042', 'Lakshmi Venkatesh', 'Cash Officer', 'Retail Branch', '+91-22-6655-5042', '5042',
 25000, 'BRANCH', 'AVAILABLE', 'Bandra Branch', 'EMP-40031', 6, FALSE),

('EMP-60050', 'Deepak Joshi', 'IT Helpdesk Analyst', 'Technology', '+91-22-6655-6050', '6050',
 0, 'INTERNAL', 'AVAILABLE', 'Bengaluru', 'EMP-10004', 5, FALSE),

('EMP-60051', 'Fatima Sheikh', 'Compliance Analyst', 'Compliance', '+91-22-6655-6051', '6051',
 0, 'INTERNAL,CBS', 'AVAILABLE', 'Mumbai HQ', 'EMP-10003', 5, FALSE);

-- Cross-channel precursors for Scenario 2 (CFO wire → Sunita Rao). Relative to NOW so the
-- 48 h correlation window always covers them on demo day.
DELETE FROM cross_channel_events WHERE campaign_id = 'BEC-CFO-2026-09';

INSERT INTO cross_channel_events (
  id, channel, target_employee_id, occurred_at, severity, indicator, campaign_id, description
) VALUES
('cc-bec-email-sunitarao', 'EMAIL', 'EMP-50040',
 DATEADD('HOUR', -36, CURRENT_TIMESTAMP), 'HIGH',
 'Rajesh Kumar <rajesh.kumar@secure-finance-mail.com>', 'BEC-CFO-2026-09',
 'BEC email to Sunita Rao purporting to be CFO Rajesh Kumar — urgent wire instruction, secrecy demand'),
('cc-smish-sms-sunitarao', 'SMS', 'EMP-50040',
 DATEADD('HOUR', -4, CURRENT_TIMESTAMP), 'HIGH',
 'Rajesh Kumar via +91-98XXX-44120', 'BEC-CFO-2026-09',
 'Smishing SMS to Sunita Rao claiming CFO needs ₹50L vendor settlement before market close');

-- Interaction graph (P8.3). CFO↔Treasury Head is routine; CFO→Sunita Rao has NO edge (demo anomaly).
DELETE FROM interaction_edges;

INSERT INTO interaction_edges (
  caller_employee_id, callee_employee_id, interaction_count,
  first_seen_at, last_seen_at, typical_hour_of_day, typical_duration_sec
) VALUES
('EMP-10492', 'EMP-20010', 52,
 TIMESTAMP '2025-01-10 11:00:00', TIMESTAMP '2026-09-10 11:15:00', 11, 420),
('EMP-20010', 'EMP-10492', 48,
 TIMESTAMP '2025-01-12 10:30:00', TIMESTAMP '2026-09-08 10:45:00', 10, 360),
('EMP-10492', 'EMP-30020', 18,
 TIMESTAMP '2025-03-01 14:00:00', TIMESTAMP '2026-08-20 14:20:00', 14, 300),
('EMP-10001', 'EMP-10492', 24,
 TIMESTAMP '2025-02-01 09:30:00', TIMESTAMP '2026-09-01 09:40:00', 9, 600),
('EMP-20010', 'EMP-30020', 40,
 TIMESTAMP '2025-01-05 12:00:00', TIMESTAMP '2026-09-12 12:10:00', 12, 240),
('EMP-30020', 'EMP-50040', 35,
 TIMESTAMP '2025-04-01 11:00:00', TIMESTAMP '2026-09-05 11:05:00', 11, 180),
('EMP-40030', 'EMP-50040', 60,
 TIMESTAMP '2025-01-20 10:00:00', TIMESTAMP '2026-09-11 10:30:00', 10, 120),
('EMP-10003', 'EMP-30021', 22,
 TIMESTAMP '2025-05-01 15:00:00', TIMESTAMP '2026-08-28 15:10:00', 15, 280);

-- DPDP §4/§6 consent register seeds for the compliance portal.
INSERT INTO consent_records (
  employee_id, purpose, notice_version, granted_at, granted_by, withdrawn_at, method
)
SELECT 'EMP-10492', 'VOICE_PASSPORT_ENROLMENT', 'notice-v1-2026',
       TIMESTAMP '2026-08-01 10:00:00', 'EMP-10492', NULL, 'AFFIRMATIVE_UI'
WHERE NOT EXISTS (
  SELECT 1 FROM consent_records
  WHERE employee_id = 'EMP-10492' AND purpose = 'VOICE_PASSPORT_ENROLMENT' AND withdrawn_at IS NULL
);

INSERT INTO consent_records (
  employee_id, purpose, notice_version, granted_at, granted_by, withdrawn_at, method
)
SELECT 'EMP-20010', 'VOICE_PASSPORT_ENROLMENT', 'notice-v1-2026',
       TIMESTAMP '2026-08-05 11:30:00', 'EMP-20010', NULL, 'AFFIRMATIVE_UI'
WHERE NOT EXISTS (
  SELECT 1 FROM consent_records
  WHERE employee_id = 'EMP-20010' AND purpose = 'VOICE_PASSPORT_ENROLMENT' AND withdrawn_at IS NULL
);

INSERT INTO consent_records (
  employee_id, purpose, notice_version, granted_at, granted_by, withdrawn_at, method
)
SELECT 'EMP-30020', 'VOICE_PASSPORT_ENROLMENT', 'notice-v1-2026',
       TIMESTAMP '2026-07-20 09:15:00', 'compliance-officer',
       TIMESTAMP '2026-09-01 16:00:00', 'AFFIRMATIVE_UI'
WHERE NOT EXISTS (
  SELECT 1 FROM consent_records
  WHERE employee_id = 'EMP-30020' AND purpose = 'VOICE_PASSPORT_ENROLMENT'
);

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

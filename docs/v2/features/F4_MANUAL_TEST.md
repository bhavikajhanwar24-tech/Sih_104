# F4 Manual test — Directory import, status, HMAC, tenant isolation

Preconditions: Postgres on **15432**, backend **8081**, frontend **5173**.

```powershell
# Terminal — backend (from Sih_104/backend)
$env:JWT_SECRET = "dev-only-change-me-sentinelvoice-jwt-32b"
$env:ML_SERVICE_TOKEN = "dev-ml-service-token-change-me"
$env:DIRECTORY_HMAC_SECRET = "dev-directory-hmac-change-me"
& C:\sih\Sih_104\tools\apache-maven-3.9.9\bin\mvn.cmd spring-boot:run "-Dspring-boot.run.arguments=--server.port=8081"

# Terminal — frontend
cd C:\sih\Sih_104\frontend
npm run dev -- --host 127.0.0.1 --port 5173 --strictPort
```

Flyway should apply `V005` on startup. Confirm:

```powershell
docker compose -f C:\sih\Sih_104\infra\postgres\docker-compose.yml exec postgres `
  psql -U sv_bootstrap -d sentinelvoice -c "\dt employees"
```

---

## 0. Two banks

1. Open http://127.0.0.1:5173/register → **Bank A** (`bank-a`), admin `admin@bank-a.test` / strong password.
2. Log out → register **Bank B** (`bank-b`), admin `admin@bank-b.test`.
3. Log back in as **Bank A**.

---

## 1. Seed departments (needed for good rows)

**Directory → Departments** → add:

- `Finance`
- `Treasury`
- `Branch Ops`

(Do **not** create `DoesNotExist` — that name is used as the intentional failure.)

---

## 2. Employees dry-run — unknown department

Phones are a **separate** import kind (`PHONES`). Employee template errors here = unknown dept / unknown manager / duplicate code.

**Directory → Imports** → kind `EMPLOYEES` → download template → save as `employees_bad.csv`:

```csv
employee_code,full_name,email,department_name,job_title,role_key,manager_code,high_authority,status
E001,Priya Sharma,priya@bank-a.test,Finance,Chief Financial Officer,CFO,,true,ACTIVE
E002,Arjun Mehta,arjun@bank-a.test,Treasury,Treasury Officer,TREASURY_OFFICER,,false,ACTIVE
E003,Neha Kapoor,neha@bank-a.test,Branch Ops,Branch Manager,BRANCH_MANAGER,,false,ACTIVE
E004,Rohan Das,rohan@bank-a.test,Finance,Analyst,ANALYST,,false,ACTIVE
E005,Kavita Iyer,kavita@bank-a.test,Treasury,Ops Lead,OPS_LEAD,,false,ACTIVE
E006,Samir Khan,samir@bank-a.test,Branch Ops,Teller,TELLER,,false,ACTIVE
E007,Meera Joshi,meera@bank-a.test,Finance,Controller,CONTROLLER,,false,ACTIVE
E008,Vikram Rao,vikram@bank-a.test,DoesNotExist,Ghost Role,GHOST,,false,ACTIVE
E009,Ananya Sen,ananya@bank-a.test,Treasury,Clerk,CLERK,E999,false,ACTIVE
E001,Dup Code,dup@bank-a.test,Finance,Dup,DUP,,false,ACTIVE
```

Upload → dry-run. Expect **3 bad rows**:

| Row | Reason |
|-----|--------|
| E008 / DoesNotExist | `unknown department_name` |
| E009 / manager E999 | `unknown manager_code` |
| second E001 | `duplicate employee_code` (after first E001 would be valid in-file… if validator only checks DB, the 2nd E001 may still pass dry-run until first commit — see note below) |

**Note:** Dry-run checks DB, not in-file duplicates. For a guaranteed 3rd employee-row error, use unknown manager + unknown dept + empty `full_name` on one row instead of a second E001:

```csv
E010,,empty@bank-a.test,Finance,Blank,BLANK,,false,ACTIVE
```

→ `full_name required`.

### Prove nothing inserted

```powershell
docker compose -f C:\sih\Sih_104\infra\postgres\docker-compose.yml exec postgres `
  psql -U sv_bootstrap -d sentinelvoice -c "SELECT count(*) FROM employees;"
```

Expect **0** (or unchanged from before this dry-run).

Do **not** click Confirm yet.

---

## 3. Phones dry-run — bad + duplicate E.164

First commit a **clean** 10-row employees file (fix the 3 bad rows; keep CFO `high_authority=true` on E001). Confirm import → Employees table shows 10.

Then **Imports** → kind `PHONES` → template → `phones_bad.csv`:

```csv
employee_code,e164,label,is_primary,sip_extension
E001,+919876543210,MOBILE,true,
E002,+919876543211,MOBILE,true,
E003,not-a-phone,MOBILE,true,
E004,+919876543210,MOBILE,true,
```

Dry-run expect:

- `E003` → `invalid E.164`
- `E004` → `duplicate phone +919876543210` (same as E001 in this file / DB after… actually duplicate check is against DB; within-file both may show as valid until commit of E001. Safer: dry-run phones **after** committing E001’s phone alone, then dry-run a file that reuses that number.)

**Reliable sequence for phone errors:**

1. Commit phones for E001 only (`+919876543210`).
2. Dry-run file with `not-a-phone` + `+919876543210` for E002 → both fail; nothing new inserted for those rows.

---

## 4. Happy path — commit employees

Fixed `employees_ok.csv` (10 good rows, CFO with `high_authority=true`):

```csv
employee_code,full_name,email,department_name,job_title,role_key,manager_code,high_authority,status
E001,Priya Sharma,priya@bank-a.test,Finance,Chief Financial Officer,CFO,,true,ACTIVE
E002,Arjun Mehta,arjun@bank-a.test,Treasury,Treasury Officer,TREASURY_OFFICER,E001,false,ACTIVE
E003,Neha Kapoor,neha@bank-a.test,Branch Ops,Branch Manager,BRANCH_MANAGER,,false,ACTIVE
E004,Rohan Das,rohan@bank-a.test,Finance,Analyst,ANALYST,E001,false,ACTIVE
E005,Kavita Iyer,kavita@bank-a.test,Treasury,Ops Lead,OPS_LEAD,E002,false,ACTIVE
E006,Samir Khan,samir@bank-a.test,Branch Ops,Teller,TELLER,E003,false,ACTIVE
E007,Meera Joshi,meera@bank-a.test,Finance,Controller,CONTROLLER,E001,false,ACTIVE
E008,Vikram Rao,vikram@bank-a.test,Treasury,Dealer,DEALER,E002,false,ACTIVE
E009,Ananya Sen,ananya@bank-a.test,Branch Ops,RM,RELATIONSHIP_MGR,E003,false,ACTIVE
E010,Dev Patel,dev@bank-a.test,Finance,Intern,INTERN,E004,false,ACTIVE
```

Dry-run → **0 errors** → **Confirm import**.

```powershell
docker compose -f C:\sih\Sih_104\infra\postgres\docker-compose.yml exec postgres `
  psql -U sv_bootstrap -d sentinelvoice -c "SELECT count(*) FROM employees; SELECT kind,status,row_count FROM directory_imports ORDER BY created_at DESC LIMIT 3;"
```

Expect `employees` count **10**, latest import `COMMITTED`.

Audit (UI **Audit** page or SQL):

```sql
SELECT event_type, payload
FROM audit_blocks
WHERE event_type = 'DIRECTORY_IMPORTED'
ORDER BY created_at DESC
LIMIT 3;
```

---

## 5. CFO status → amber badge

**Directory → Employees** → open **Priya Sharma (CFO)** → Quick status:

- Status: `ON_LEAVE`
- Until: tomorrow (datetime-local)
- Set status

Badge should use warn/amber tone. Detail shows `statusUntil`.

```sql
SELECT full_name, status, status_until
FROM employees
WHERE employee_code = 'E001';
```

---

## 6. Beneficiary HMAC

**Directory → Beneficiaries** → Add:

- Account number: `123456789012`
- Label: `Vendor payout A`

```sql
SELECT label, account_ref_hash FROM beneficiary_accounts;
```

Expect a long hex hash — **not** `123456789012`.

---

## 7. Bank B isolation

Log out → log in as **Bank B** → **Directory → Employees**.

Expect empty (or only Bank B data). As `sv_bootstrap`:

```sql
-- totals across tenants (owner bypasses RLS)
SELECT t.slug, count(e.*)
FROM tenants t
LEFT JOIN employees e ON e.tenant_id = t.id
GROUP BY t.slug;
```

As app role **without** `app.tenant_id`, RLS returns 0 rows (fail-closed). With Bank B session, API lists only Bank B.

---

## Quick API check (optional)

```powershell
# after browser login, copy Cookie header — or use curl login from F2.md
curl -s -b cookies-a.txt "http://127.0.0.1:8081/api/v2/directory/employees?page=0&size=50"
```

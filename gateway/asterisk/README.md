# SentinelVoice — local Asterisk PBX (F10 multi-tenant)

**Lab only.** Do **not** expose UDP/5060 or the ARI port to the public internet.

## Architecture (F10)

- **PJSIP REALTIME** — softphone identities live in PostgreSQL schema `asterisk`
  (`ps_endpoints` / `ps_auths` / `ps_aors`), provisioned by Java from tenant
  `sip_endpoints`. Static caller/agent stanzas were removed from `pjsip.conf`.
- **Dialplan** — generic `_X.` → `scripts/sv_call_agi.py` (CURL Java
  `/internal/v2/telephony/resolve` + `/internal/v2/sessions/start`) → ConfBridge
  + Originate. `[sentinel-snoop]` AudioSocket unchanged.
- **Env** — set `SIP_EXTERNAL_IP` to your LAN IPv4 (`scripts/detect-lan-ip.ps1` /
  `.sh`). `ASTERISK_DB_*` points at Postgres as role `sv_asterisk`.

## Quick start

```bash
# From the repo root — set SIP_EXTERNAL_IP in .env first
docker compose up -d --build asterisk

docker compose logs -f asterisk
docker compose exec asterisk asterisk -rvvv
```

Provision endpoints via backend (TENANT_ADMIN):

`POST /api/v2/telephony/endpoints/for-employee/{employeeId}` → extension + one-time password.

ARI (hold/terminate):

| Field    | Value            |
|----------|------------------|
| URL      | `http://127.0.0.1:8088/ari/` |
| User     | `sentinel`       |
| Password | `sentineldemo`   |

See `docs/v2/features/F10.md` for the full verification script.

# SentinelVoice — local Asterisk PBX (softphone lab)

**Lab only.** Demo SIP/ARI passwords are intentional and obvious. Do **not** expose
UDP/5060 or the ARI port to the public internet.

This is Path 2 from Context §11.3: two softphones register to Asterisk, place a real
SIP call offline, and (in P7.2) fork media into the inference plane via AudioSocket.

## Quick start

```bash
# From the repo root
docker compose up -d --build asterisk

# Watch verbose console
docker compose logs -f asterisk

# CLI
docker compose exec asterisk asterisk -rvvv
```

`network_mode: host` is required so SIP + RTP do not fight Docker NAT.

> **Windows / macOS Docker Desktop:** classic `host` networking is a Linux feature.
> Prefer a Linux VM or WSL2 backend for the venue demo laptop. On Docker Desktop,
> if registration fails from the host softphone, see Troubleshooting → NAT.

## Demo accounts (obvious on purpose)

| Role   | Auth username | Extension | Password         | Softphone identity |
|--------|---------------|-----------|------------------|--------------------|
| Caller | `caller`      | **1001**  | `callerdemo1001` | attacker / CEO clone feed |
| Agent  | `agent`       | **1002**  | `agentdemo1002`  | bank agent         |

ARI (P7.3 hold/terminate):

| Field    | Value            |
|----------|------------------|
| URL      | `http://127.0.0.1:8088/ari/` |
| User     | `sentinel`       |
| Password | `sentineldemo`   |

```bash
curl -u sentinel:sentineldemo http://127.0.0.1:8088/ari/asterisk/info
```

## Softphone setup

Server address = the machine running Asterisk (`127.0.0.1` if softphones are on the
same laptop; otherwise the LAN IP of that machine). Transport = **UDP**. Port = **5060**.

### Zoiper 5 (desktop)

1. Add account → **SIP**.
2. **Caller** account:
   - Domain / Registrar: `127.0.0.1` (or LAN IP)
   - Username: `caller`
   - Password: `callerdemo1001`
   - Outbound proxy: leave empty (or same as domain)
   - Network → Protocol: **UDP**, Port **5060**
3. Repeat for **Agent**: username `agent`, password `agentdemo1002`.
4. Optional: Authentication username = same as username (`caller` / `agent`).
5. Disable STUN if you are on the same LAN as Asterisk (avoids one-way audio confusion).
6. From the caller account, dial **1002**. Agent should ring; answer for two-way audio.

### Linphone (desktop / mobile)

1. Assistant → **Use a SIP account**.
2. Username: `caller` (or `agent`)
3. SIP domain: `127.0.0.1` (or LAN IP) — account becomes `caller@127.0.0.1`
4. Password: `callerdemo1001` / `agentdemo1002`
5. Transport: **UDP**
6. Settings → Network: disable ICE/STUN for same-LAN lab, or keep defaults if registration works.
7. Dial `1002` (or `sip:1002@127.0.0.1`) from the caller account.

## Useful Asterisk CLI

Inside the container (`docker compose exec asterisk asterisk -rvvv`):

```text
pjsip show endpoints          ; both should show Avail when softphones are registered
pjsip show registrations      ; (inbound AoR contacts)
core show channels            ; live call legs
pjsip set logger on           ; SIP message dump to console
core set verbose 5
quit
```

One-shot from the host:

```bash
docker compose exec asterisk asterisk -rx "pjsip show endpoints"
docker compose exec asterisk asterisk -rx "core show channels"
```

## Automated smoke test

```bash
bash scripts/test-sip-call.sh
```

Confirms the container is up, ARI returns JSON, and a Local/Echo originate works
**before** you blame the AudioSocket bridge (P7.2).

## Dialplan seam for P7.2

`extensions.conf` `[sentinel]` dials `PJSIP/agent` today. A clearly marked
`TODO(P7.2)` sits above that Dial() — swap in `Dial(...,U(tap))` + `AudioSocket`
when `gateway/asterisk_bridge.py` listens on `127.0.0.1:9092`.

## Troubleshooting (the three failures you WILL hit)

### 1) Registration fails

- Confirm Asterisk is listening: `docker compose logs asterisk | head`
- Softphone Domain must be reachable (same laptop → `127.0.0.1`; another device → LAN IP).
- Username must be `caller` / `agent` (not `1001` / `1002`). Extensions are dialplan
  destinations; AoR auth usernames are the words in `pjsip.conf`.
- Password typos: `callerdemo1001` / `agentdemo1002`.
- Windows host networking: if UDP/5060 never reaches the VM, run Asterisk on Linux/WSL2
  or publish `5060:5060/udp` + RTP range (last resort; prefer host mode on Linux).
- CLI: `pjsip set logger on` then retry register; watch 401/403.

### 2) One-way audio

- Usually RTP blocked or wrong address in SDP. We set `rtp_symmetric=yes`,
  `force_rport=yes`, `rewrite_contact=yes`, `direct_media=no` for the lab.
- Disable softphone STUN/ICE on a pure LAN lab.
- Allow UDP **10000–10100** on the host firewall (see `rtp.conf`).
- Confirm both endpoints Avail and a channel exists: `core show channels`.

### 3) NAT / “works on laptop A, dies on LAN”

- Softphones on another device must use the **LAN IP** of the Asterisk host, not
  `127.0.0.1`.
- Host firewall must allow UDP 5060 and UDP 10000–10100.
- Docker Desktop (Mac/Win) without real host-network: RTP hairpinning fails often —
  venue demo should use Linux + `network_mode: host`.
- Avoid double-NAT; keep both softphones and Asterisk on the same L2 segment when possible.

## Acceptance checklist

- [ ] `docker compose up asterisk` starts cleanly
- [ ] Softphones register; `pjsip show endpoints` shows **caller** and **agent** as **Avail**
- [ ] Caller dials **1002** → agent rings → two-way audio
- [ ] `curl -u sentinel:sentineldemo http://127.0.0.1:8088/ari/asterisk/info` returns JSON

#!/usr/bin/env python3
"""
SentinelVoice AGI — resolve dialled extension via Java internal APIs, set
CONF / DEST_ENDPOINT / SV_SESSION channel vars, and finalise on hangup.

Decision (F10): PJSIP REALTIME endpoints; dialplan stays generic (_X.).
Env:
  SV_BACKEND_URL   default http://host.docker.internal:8081
  ML_SERVICE_TOKEN shared secret for X-ML-Service-Token
"""
from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid


def agi_read_env() -> dict[str, str]:
    env: dict[str, str] = {}
    while True:
        line = sys.stdin.readline()
        if not line:
            break
        line = line.strip()
        if line == "":
            break
        if ":" in line:
            k, v = line.split(":", 1)
            env[k.strip()] = v.strip()
    return env


def agi_cmd(cmd: str) -> str:
    sys.stdout.write(cmd + "\n")
    sys.stdout.flush()
    return sys.stdin.readline().strip()


def agi_set(name: str, value: str) -> None:
    agi_cmd(f'SET VARIABLE {name} "{value}"')


def agi_verbose(msg: str, level: int = 1) -> None:
    safe = msg.replace('"', "'")
    agi_cmd(f'VERBOSE "{safe}" {level}')


def http_json(method: str, url: str, body: dict | None = None, timeout: float = 20) -> dict:
    token = os.environ.get("ML_SERVICE_TOKEN") or os.environ.get("SENTINELVOICE_ML_SERVICE_TOKEN") or ""
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=data,
        method=method,
        headers={
            "Content-Type": "application/json",
            "X-ML-Service-Token": token,
            "Accept": "application/json",
        },
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        raw = resp.read().decode("utf-8")
        return json.loads(raw) if raw else {}


def backend_base() -> str:
    return (
        os.environ.get("SV_BACKEND_URL")
        or os.environ.get("SENTINELVOICE_BACKEND_URL")
        or "http://host.docker.internal:8081"
    ).rstrip("/")


def setup(extension: str, caller_endpoint: str, caller_num: str, sip_call_id: str) -> None:
    base = backend_base()
    try:
        q = {"extension": extension}
        if caller_endpoint:
            q["callerUsername"] = caller_endpoint
        resolved = http_json(
            "GET",
            f"{base}/internal/v2/telephony/resolve?{urllib.parse.urlencode(q)}",
            timeout=10,
        )
    except Exception as exc:  # noqa: BLE001
        agi_verbose(f"resolve failed: {exc}", 2)
        agi_set("AGI_STATUS", "FAILED")
        agi_set("DEST_ENDPOINT", "")
        return

    dest = resolved.get("destEndpoint") or resolved.get("username") or ""
    if not dest:
        agi_verbose("resolve returned empty destEndpoint", 2)
        agi_set("AGI_STATUS", "FAILED")
        agi_set("DEST_ENDPOINT", "")
        return

    # Do NOT call SIP_HEADER — chan_sip only; PJSIP lab has no SIP_HEADER and it
    # can abort the AGI channel (return 4) before Originate.

    # Ringing audit is best-effort and must not delay the dial.
    try:
        http_json(
            "POST",
            f"{base}/internal/v2/sessions/ringing",
            {
                "calleeExtension": extension,
                "callerUsername": caller_endpoint or None,
                "callerNumber": caller_num or None,
                "sipCallId": sip_call_id or None,
                "direction": "INTERNAL",
            },
            timeout=3,
        )
    except Exception as exc:  # noqa: BLE001
        agi_verbose(f"sessions/ringing failed (non-fatal): {exc}", 2)

    sv_session = str(uuid.uuid4())
    conf = "sv" + sv_session.replace("-", "")
    try:
        started = http_json(
            "POST",
            f"{base}/internal/v2/sessions/start",
            {
                "svSessionUuid": sv_session,
                "calleeExtension": extension,
                "callerUsername": caller_endpoint or None,
                "callerNumber": caller_num or None,
                "sipCallId": sip_call_id or None,
                "direction": "INTERNAL",
            },
            timeout=25,
        )
        sv_session = started.get("svSessionUuid") or sv_session
        conf = started.get("conf") or conf
        dest = started.get("destEndpoint") or dest
    except Exception as exc:  # noqa: BLE001
        # Still ring the callee — Live Calls metadata can catch up later.
        agi_verbose(f"sessions/start failed (dial continues): {exc}", 2)

    agi_set("SV_SESSION", sv_session)
    agi_set("CONF", conf)
    agi_set("DEST_ENDPOINT", dest)
    agi_set("AGI_STATUS", "OK")
    agi_verbose(f"setup ok dest={dest} conf={conf} sid={sv_session}", 1)


def end_call(sv_session: str) -> None:
    if not sv_session:
        return
    base = backend_base()
    try:
        http_json(
            "POST",
            f"{base}/internal/v2/sessions/end",
            {"svSessionUuid": sv_session, "outcome": "ENDED"},
            timeout=10,
        )
        agi_verbose(f"session ended {sv_session}", 1)
    except Exception as exc:  # noqa: BLE001
        agi_verbose(f"sessions/end failed: {exc}", 2)


def main() -> int:
    agi_read_env()
    args = sys.argv[1:]
    if args and args[0] == "--end":
        end_call(args[1] if len(args) > 1 else "")
        return 0
    extension = args[0] if len(args) > 0 else ""
    caller_endpoint = args[1] if len(args) > 1 else ""
    caller_num = args[2] if len(args) > 2 else ""
    sip_call_id = args[3] if len(args) > 3 else ""
    if not extension:
        agi_set("AGI_STATUS", "FAILED")
        return 1
    setup(extension, caller_endpoint, caller_num, sip_call_id)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:  # noqa: BLE001
        try:
            agi_verbose(f"agi crash: {exc}", 2)
            agi_set("AGI_STATUS", "FAILED")
            agi_set("DEST_ENDPOINT", "")
        except Exception:
            pass
        raise SystemExit(1)

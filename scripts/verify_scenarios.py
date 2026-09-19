#!/usr/bin/env python3
"""Replay each scenario WAV through the live pipeline and score acceptance.

For every ``scenarios/*.yaml`` fixture:

  1. POST /api/v1/scenario/{id}/load?mode=replay&autoReplay=false
  2. Real-time ``gateway/replay_audio.py`` into ml-engine ingest WS
  3. Poll ``/api/v1/session/{sid}/telemetry/latest``
  4. Print a table: expected vs observed level, time-to-L4, pass/fail

On failure, dump family scores — do **not** edit fusion thresholds/weights.

ARI physical hold soft-skips when there is no live Asterisk conference
(WAV replay). That is reported honestly; Decision Plane L4 is still required.

Usage (repo root, stack up, ml-engine venv)::

  python scripts/verify_scenarios.py
  python scripts/verify_scenarios.py --only deepfake-ceo-wire,hinglish-grandparent
  python scripts/verify_scenarios.py --api http://127.0.0.1:8080 --user analyst --password password
"""

from __future__ import annotations

import argparse
import base64
import json
import logging
import subprocess
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Optional

LOG = logging.getLogger("sentinelvoice.verify_scenarios")

LEVEL_ORDER = [
    "LEVEL_1_SILENT",
    "LEVEL_2_SOFT_NUDGE",
    "LEVEL_3_STEP_UP_MFA",
    "LEVEL_4_AUTO_HOLD",
    "LEVEL_5_TERMINATE",
]

FAMILY_KEYS = (
    "voice",
    "channel",
    "prosody",
    "linguistic",
    "transaction",
    "relationship",
)


def repo_root() -> Path:
    return Path(__file__).resolve().parents[1]


def level_rank(level: Optional[str]) -> int:
    if not level:
        return -1
    try:
        return LEVEL_ORDER.index(level)
    except ValueError:
        return -1


def load_yaml(path: Path) -> dict[str, Any]:
    try:
        import yaml
    except ImportError as exc:
        raise SystemExit(
            "PyYAML required. Activate ml-engine venv or: pip install PyYAML"
        ) from exc
    data = yaml.safe_load(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError(f"scenario YAML root must be a mapping: {path}")
    return data


@dataclass
class ScenarioResult:
    scenario_id: str
    title: str
    expected: str
    observed: str
    time_to_l4_s: Optional[float]
    passed: bool
    must_not_exceed: Optional[str] = None
    peak_level: Optional[str] = None
    hold_status: str = "n/a"
    notes: list[str] = field(default_factory=list)
    families: dict[str, Any] = field(default_factory=dict)
    session_id: Optional[str] = None


class ApiClient:
    def __init__(self, base: str, user: str, password: str, timeout_s: float = 30.0):
        self.base = base.rstrip("/")
        self.timeout_s = timeout_s
        token = base64.b64encode(f"{user}:{password}".encode("utf-8")).decode("ascii")
        self._auth = f"Basic {token}"

    def request(self, method: str, path: str, body: Any = None) -> tuple[int, Any]:
        url = f"{self.base}{path}"
        data = None
        headers = {
            "Authorization": self._auth,
            "Accept": "application/json",
        }
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=self.timeout_s) as resp:
                raw = resp.read()
                code = resp.getcode()
        except urllib.error.HTTPError as exc:
            raw = exc.read()
            code = exc.code
        except urllib.error.URLError as exc:
            raise ConnectionError(f"API unreachable at {url}: {exc}") from exc
        if not raw:
            return code, None
        try:
            return code, json.loads(raw.decode("utf-8"))
        except json.JSONDecodeError:
            return code, raw.decode("utf-8", errors="replace")

    def get(self, path: str) -> tuple[int, Any]:
        return self.request("GET", path)

    def post(self, path: str, body: Any = None) -> tuple[int, Any]:
        return self.request("POST", path, body=body)


def ari_reachable(ari_url: str, user: str, password: str, timeout_s: float = 2.0) -> bool:
    url = ari_url.rstrip("/") + "/asterisk/info"
    token = base64.b64encode(f"{user}:{password}".encode("utf-8")).decode("ascii")
    req = urllib.request.Request(
        url,
        headers={"Authorization": f"Basic {token}", "Accept": "application/json"},
        method="GET",
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout_s) as resp:
            return 200 <= resp.getcode() < 300
    except Exception:
        return False


def resolve_python(root: Path) -> Path:
    win = root / "ml-engine" / ".venv" / "Scripts" / "python.exe"
    if win.is_file():
        return win
    unix = root / "ml-engine" / ".venv" / "bin" / "python"
    if unix.is_file():
        return unix
    return Path(sys.executable)


def list_scenario_files(root: Path) -> list[Path]:
    return sorted((root / "scenarios").glob("*.yaml"))


def extract_families(frame: dict[str, Any]) -> dict[str, Any]:
    fam = frame.get("families") or {}
    out: dict[str, Any] = {}
    for key in FAMILY_KEYS:
        cell = fam.get(key) or {}
        if isinstance(cell, dict):
            out[key] = {
                "score": cell.get("score"),
                "available": cell.get("available"),
                "contribution": cell.get("contribution"),
            }
        else:
            out[key] = cell
    corr = frame.get("corroboration") or {}
    out["_corroboration"] = {
        "satisfied": corr.get("satisfied"),
        "familiesAboveThreshold": corr.get("familiesAboveThreshold"),
    }
    risk = frame.get("risk") or {}
    out["_risk"] = {
        "smoothed": risk.get("smoothed"),
        "instantaneous": risk.get("instantaneous"),
    }
    return out


def format_table(rows: list[ScenarioResult]) -> str:
    headers = ("scenario", "expected", "observed", "t→L4_s", "hold", "result")
    cells = [headers]
    for r in rows:
        t_l4 = "—" if r.time_to_l4_s is None else f"{r.time_to_l4_s:.1f}"
        cells.append(
            (
                r.scenario_id,
                r.expected,
                r.observed or "NONE",
                t_l4,
                r.hold_status,
                "PASS" if r.passed else "FAIL",
            )
        )
    widths = [max(len(str(row[i])) for row in cells) for i in range(len(headers))]
    lines = []
    for i, row in enumerate(cells):
        line = "  ".join(str(col).ljust(widths[j]) for j, col in enumerate(row))
        lines.append(line)
        if i == 0:
            lines.append("  ".join("-" * w for w in widths))
    return "\n".join(lines)


def diagnose_fail(result: ScenarioResult) -> None:
    print(f"\n--- FAIL diagnosis: {result.scenario_id} ---", file=sys.stderr)
    for note in result.notes:
        print(f"  note: {note}", file=sys.stderr)
    fam = result.families
    if not fam:
        print("  (no telemetry families captured)", file=sys.stderr)
        return
    risk = fam.get("_risk") or {}
    corr = fam.get("_corroboration") or {}
    print(
        f"  risk.smoothed={risk.get('smoothed')}  "
        f"corroboration.satisfied={corr.get('satisfied')}  "
        f"above={corr.get('familiesAboveThreshold')}",
        file=sys.stderr,
    )
    print("  families (score / available / contribution):", file=sys.stderr)
    for key in FAMILY_KEYS:
        cell = fam.get(key) or {}
        print(
            f"    {key:13s}  score={cell.get('score')!s:>8}  "
            f"avail={cell.get('available')!s:>5}  contrib={cell.get('contribution')!s}",
            file=sys.stderr,
        )
    print(
        "  Do NOT tweak fusion thresholds/weights to force a pass — "
        "fix audio/ASR/context seeding first.",
        file=sys.stderr,
    )


def evaluate(
    scenario: dict[str, Any],
    observed: str,
    peak: str,
    time_to_l4: Optional[float],
) -> tuple[bool, list[str]]:
    expected = scenario.get("expectedFinalLevel") or ""
    must_not = scenario.get("mustNotExceed")
    notes: list[str] = []
    ok = True
    if observed != expected:
        ok = False
        notes.append(f"final level {observed!r} != expected {expected!r}")
    if must_not:
        if level_rank(peak) > level_rank(must_not):
            ok = False
            notes.append(f"peak {peak!r} exceeded mustNotExceed {must_not!r}")
        elif observed == expected:
            notes.append(f"mustNotExceed {must_not} held (peak={peak})")
    return ok, notes


def run_one(
    client: ApiClient,
    scenario_path: Path,
    root: Path,
    *,
    ari_url: str,
    ari_user: str,
    ari_pass: str,
    poll_hz: float,
    settle_s: float,
    python: Path,
) -> ScenarioResult:
    scenario = load_yaml(scenario_path)
    sid_key = scenario["id"]
    title = scenario.get("title") or sid_key
    expected = scenario.get("expectedFinalLevel") or ""
    must_not = scenario.get("mustNotExceed")
    audio = (scenario.get("audio") or {}).get("source")
    channel = scenario.get("channelProfile") or "PSTN_NARROWBAND"
    codec_hint = (scenario.get("audio") or {}).get("codecProfileHint")

    wav = root / audio if audio else None
    if wav is None or not wav.is_file():
        return ScenarioResult(
            scenario_id=sid_key,
            title=title,
            expected=expected,
            observed="NONE",
            time_to_l4_s=None,
            passed=False,
            must_not_exceed=must_not,
            hold_status="n/a",
            notes=[
                f"WAV missing: {audio}. Run scripts/build_scenario_audio.py first "
                "(no placeholder tones)."
            ],
        )

    code, body = client.post(
        f"/api/v1/scenario/{sid_key}/load?mode=replay&autoReplay=false"
    )
    if code != 200 or not isinstance(body, dict):
        return ScenarioResult(
            scenario_id=sid_key,
            title=title,
            expected=expected,
            observed="NONE",
            time_to_l4_s=None,
            passed=False,
            must_not_exceed=must_not,
            hold_status="n/a",
            notes=[f"scenario load failed HTTP {code}: {body!r}"],
        )

    session_id = body["sessionId"]
    ingest = body.get("ingestWsUrl") or f"ws://127.0.0.1:8000/ingest/{session_id}"
    LOG.info("loaded %s sessionId=%s wav=%s", sid_key, session_id, wav.name)

    live_ari = ari_reachable(ari_url, ari_user, ari_pass)
    hold_status = "SOFT_SKIP" if not live_ari else "ARI_UP_NO_CONF_EXPECTED"

    cmd = [
        str(python),
        str(root / "gateway" / "replay_audio.py"),
        "--wav",
        str(wav),
        "--session",
        session_id,
        "--ws",
        ingest,
        "--channel",
        channel,
    ]
    if codec_hint:
        cmd.extend(["--profile", str(codec_hint)])

    # Duration of WAV ≈ wall time under real-time pacing
    try:
        import wave

        with wave.open(str(wav), "rb") as w:
            audio_s = w.getnframes() / float(w.getframerate())
    except Exception:
        audio_s = 30.0

    proc = subprocess.Popen(
        cmd,
        cwd=str(root),
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )

    t0 = time.perf_counter()
    deadline = t0 + audio_s + settle_s + 5.0
    interval = max(0.1, 1.0 / poll_hz)
    peak_level = "LEVEL_1_SILENT"
    time_to_l4: Optional[float] = None
    last_frame: Optional[dict[str, Any]] = None
    observed = "LEVEL_1_SILENT"

    try:
        while time.perf_counter() < deadline:
            if proc.poll() is not None and time.perf_counter() > t0 + audio_s:
                # Replay finished — keep polling briefly for late FSM ticks
                if time.perf_counter() > t0 + audio_s + settle_s:
                    break
            code_t, frame = client.get(f"/api/v1/session/{session_id}/telemetry/latest")
            if code_t == 200 and isinstance(frame, dict):
                last_frame = frame
                level = ((frame.get("intervention") or {}).get("level")) or observed
                observed = level
                if level_rank(level) > level_rank(peak_level):
                    peak_level = level
                if time_to_l4 is None and level_rank(level) >= level_rank("LEVEL_4_AUTO_HOLD"):
                    elapsed_ms = frame.get("callElapsedMs")
                    if isinstance(elapsed_ms, (int, float)):
                        time_to_l4 = float(elapsed_ms) / 1000.0
                    else:
                        time_to_l4 = time.perf_counter() - t0
                    # Physical hold check (honest soft-skip when no live call)
                    if live_ari:
                        h_code, h_body = client.post(f"/api/v1/actuation/{session_id}/hold")
                        if h_code == 200 and isinstance(h_body, dict):
                            hold_status = f"ARI_{h_body.get('status', 'OK')}"
                        else:
                            hold_status = "SOFT_SKIP"
                            LOG.info(
                                "ARI hold soft-skip sessionId=%s http=%s body=%s",
                                session_id,
                                h_code,
                                h_body,
                            )
                    else:
                        hold_status = "SOFT_SKIP"
                        LOG.info(
                            "ARI unreachable — soft-skipping physical hold "
                            "(Decision Plane L4 still scored). sessionId=%s",
                            session_id,
                        )
            time.sleep(interval)
    finally:
        if proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                proc.kill()
        client.post(f"/api/v1/session/{session_id}/close")

    families = extract_families(last_frame) if last_frame else {}
    actions = ((last_frame or {}).get("intervention") or {}).get("actionsFired") or []
    notes: list[str] = []
    if hold_status.startswith("SOFT_SKIP"):
        notes.append(
            "Physical ARI/ConfBridge hold soft-skipped — no live Asterisk call "
            "(WAV replay). Decision Plane level is still authoritative."
        )
    if actions:
        notes.append(f"actionsFired={actions}")

    passed, eval_notes = evaluate(scenario, observed, peak_level, time_to_l4)
    notes.extend(eval_notes)

    # Scenario 5 teaching check: acoustic may be high, contextual should stay cold
    if sid_key == "false-positive-stress" and families:
        acoustic_keys = ("voice", "channel", "prosody")
        contextual_keys = ("linguistic", "transaction", "relationship")
        ac = [
            float((families.get(k) or {}).get("score") or 0.0)
            for k in acoustic_keys
            if (families.get(k) or {}).get("available")
        ]
        cx = [
            float((families.get(k) or {}).get("score") or 0.0)
            for k in contextual_keys
            if (families.get(k) or {}).get("available")
        ]
        if ac:
            notes.append(f"acoustic max≈{max(ac):.2f}")
        if cx:
            notes.append(f"contextual max≈{max(cx):.2f}")

    return ScenarioResult(
        scenario_id=sid_key,
        title=title,
        expected=expected,
        observed=observed,
        time_to_l4_s=time_to_l4,
        passed=passed,
        must_not_exceed=must_not,
        peak_level=peak_level,
        hold_status=hold_status,
        notes=notes,
        families=families,
        session_id=session_id,
    )


def main(argv: Optional[list[str]] = None) -> int:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )
    root = repo_root()
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--api", default="http://127.0.0.1:8080", help="Decision Plane base URL")
    p.add_argument("--user", default="analyst", help="HTTP Basic user")
    p.add_argument("--password", default="password", help="HTTP Basic password")
    p.add_argument("--ari-url", default="http://127.0.0.1:8088/ari", help="Asterisk ARI base")
    p.add_argument("--ari-user", default="sentinel")
    p.add_argument("--ari-pass", default="sentineldemo")
    p.add_argument("--only", default=None, help="Comma scenario ids, e.g. deepfake-ceo-wire")
    p.add_argument("--poll-hz", type=float, default=2.0, help="Telemetry poll rate")
    p.add_argument("--settle-s", type=float, default=3.0, help="Extra seconds after WAV ends")
    p.add_argument(
        "--json-out",
        type=Path,
        default=None,
        help="Optional path to write machine-readable results",
    )
    args = p.parse_args(argv)

    only: Optional[set[str]] = None
    if args.only:
        only = {x.strip() for x in args.only.split(",") if x.strip()}

    client = ApiClient(args.api, args.user, args.password)
    try:
        code, health = client.get("/actuator/health")
    except ConnectionError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        print("Start the stack (backend + ml-engine) before verifying.", file=sys.stderr)
        return 2
    if code != 200:
        print(f"ERROR: backend health HTTP {code}: {health}", file=sys.stderr)
        return 2

    python = resolve_python(root)
    files = list_scenario_files(root)
    if only:
        files = [f for f in files if load_yaml(f).get("id") in only]
    if not files:
        print("No scenario YAML files matched.", file=sys.stderr)
        return 2

    live_ari = ari_reachable(args.ari_url, args.ari_user, args.ari_pass)
    ari_msg = (
        "reachable"
        if live_ari
        else "unreachable — physical hold will SOFT_SKIP (honest)"
    )
    print(f"Backend OK. Asterisk ARI: {ari_msg}")
    print(f"Replaying {len(files)} scenario(s) with {python} …\n")

    results: list[ScenarioResult] = []
    for path in files:
        try:
            result = run_one(
                client,
                path,
                root,
                ari_url=args.ari_url,
                ari_user=args.ari_user,
                ari_pass=args.ari_pass,
                poll_hz=args.poll_hz,
                settle_s=args.settle_s,
                python=python,
            )
        except Exception as exc:
            LOG.exception("scenario crashed: %s", path.name)
            result = ScenarioResult(
                scenario_id=path.stem,
                title=path.stem,
                expected="?",
                observed="ERROR",
                time_to_l4_s=None,
                passed=False,
                hold_status="n/a",
                notes=[str(exc)],
            )
        results.append(result)
        status = "PASS" if result.passed else "FAIL"
        print(
            f"[{status}] {result.scenario_id}: expected={result.expected} "
            f"observed={result.observed} hold={result.hold_status}"
        )

    print("\n" + format_table(results) + "\n")

    for r in results:
        if not r.passed:
            diagnose_fail(r)

    if args.json_out:
        payload = [
            {
                "scenarioId": r.scenario_id,
                "title": r.title,
                "expected": r.expected,
                "observed": r.observed,
                "peak": r.peak_level,
                "mustNotExceed": r.must_not_exceed,
                "timeToL4Sec": r.time_to_l4_s,
                "holdStatus": r.hold_status,
                "passed": r.passed,
                "notes": r.notes,
                "families": r.families,
                "sessionId": r.session_id,
            }
            for r in results
        ]
        args.json_out.parent.mkdir(parents=True, exist_ok=True)
        args.json_out.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
        print(f"Wrote {args.json_out}")

    failed = sum(1 for r in results if not r.passed)
    if failed:
        print(
            f"{failed}/{len(results)} scenario(s) FAILED. "
            "No threshold/weight edits applied — fix audio or context seeding.",
            file=sys.stderr,
        )
        return 1
    print(f"All {len(results)} scenario(s) PASSED.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

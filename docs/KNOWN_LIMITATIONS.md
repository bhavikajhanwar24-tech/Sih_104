# Known limitations (honest status)

This document lists gaps that demos and reports must not paper over.

## Evaluation

- Until ASVspoof / In-the-Wild / Common Voice corpora are present on disk and
  `python -m benchmarks.run_eval` is run **without** `--synthetic`, every EER / FPR /
  ECE cell is **synthetic smoke data**, not a field result. Without corpora the
  harness **stops** rather than inventing `source=disk` numbers (see
  `scripts/fetch_datasets.md`).
- Local `ml-engine/models/antispoof/*.pt` may exist from synthetic training; they are
  gitignored and must not be cited as production checkpoints.
- Fairness groups require Common Voice TSV language / gender / age tags
  (`python -m benchmarks.fairness`). Without CV, the portal shows empty groups or a
  **SYNTHETIC SMOKE DATA** badge when `meta.synthetic` is true — never fabricated FPR.
- If `ml-engine/benchmarks/results.json` is missing, the portal returns
  `status: EVALUATION_NOT_RUN` and invents **no** numbers.

## Telephony / actuation

- Softphone ConfBridge mute (L4 hold) requires a live Asterisk call. WAV replay soft-skips
  hold when there is no conference channel.
- Real CBS freeze is mocked (`/mock-cbs`); carrier CNAP / email / SMS gateways are
  scenario-seeded fixtures only (UI: **simulated signal** badge).

## Watermark / threats

- SynthID detection is not implemented; AudioSeal path only (attribution, not fusion).
- Out-of-scope threats from Context §5.2 (insider human fraud without synthetic speech,
  etc.) remain out of scope — acoustic families will not fire on a genuine insider.

## Privacy / compliance

- Live STOMP `transcriptDelta.text` is stripped by default; analysts use break-glass
  (request → different supervisor approve → GET ±5 s redacted window). Audit event:
  `TRANSCRIPT_BREAK_GLASS_ACCESS`.
- Demo HTTP Basic roles (password for all: `password`): `analyst`, `supervisor`,
  `compliance`, `admin` — registered in `SecurityConfig` (bcrypt at boot). Optional
  `sv.security.enabled` / extra users stay off the `sentinelvoice.*` tree so constructor
  binding is not wiped by `$` placeholders. Not an IdP — lab only.
- Default persistence is file H2 at `./data/sentinelvoice`; profile `demo-mem` for tests;
  optional `postgres` Spring profile + compose `--profile postgres`.

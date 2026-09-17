# SentinelVoice data contracts

Frozen JSON Schema (draft 2020-12) for the interfaces between the four planes.
TypeScript types in `frontend/src/types/contracts.ts` are **generated** from these schemas —
do not hand-edit that file.

## Freeze policy

These schemas are **frozen**. Changing a schema requires:

1. Bumping the `schema` version string (e.g. `sentinelvoice.FeatureFrame/1` → `/2`)
2. Updating **all four planes** (gateway / ml-engine / backend / frontend) in the **same commit**
3. Regenerating types via `bash scripts/gen-types.sh`

Never change a contract just to make code compile. If a field is missing, say so and stop —
do not invent fields.

## Schemas

| Schema | Direction | Source |
|--------|-----------|--------|
| `FeatureFrame` | Inference → Decision | Context §8.1 |
| `TelemetryFrame` | Decision → Presentation | Context §8.2 |
| `AuditBlock` | Decision ledger | Context §8.3 / §9.7 |
| `SessionStartRequest` | Presentation/Media → Decision | Context §8.3 |
| `ChallengeIssued` | Decision → Presentation | Context §8.3 |
| `ChallengeResult` | Decision (audit + fusion) | Context §8.3 |
| `InterventionOverride` | Presentation → Decision | Context §8.3 |
| `CrossChannelEvent` | Ingest → Decision | Context §8.3 |

Shared enums are defined as `$defs` and `$ref`'d inside each schema
(`ChannelProfile`, `InterventionLevel`, `RiskState`, `IdentityVerdict`, `Trend`,
`ReasonSeverity`). `common.defs.json` is the human-readable catalogue of the same enums.

## Regenerate TypeScript

```bash
bash scripts/gen-types.sh
```

## Validate

`ajv-cli` defaults to JSON Schema draft-07. These contracts declare draft **2020-12**,
so `--spec=draft2020` is required:

```bash
bash scripts/validate-contracts.sh

# or one schema:
npx --yes ajv-cli@5 compile --spec=draft2020 \
  -s docs/contracts/FeatureFrame.schema.json
```

## Fixture notes

- `FeatureFrame.example.json` adds `watermark.available` (required for every evidence family per
  Context §8.1 design notes; omitted in the illustrative JSONC block).
- `TelemetryFrame.example.json` uses `IdentityVerdict` `IMPERSONATION_SYNTHETIC` where the
  illustrative block wrote shorthand `"FAILED"` — formal enum is Context §12.

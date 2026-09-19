# SentinelVoice contracts — V2

**Source of truth for new work.** These JSON Schemas (draft 2020-12) define the wire formats
between the Inference, Decision, and Presentation planes for **SentinelVoice V2**.

V2 schemas **may evolve with features**. Historical v1 schemas remain in
[`../v1/`](../v1/) for reference and must not be treated as authoritative for V2.

## Required envelope fields (every message schema)

| Field | Constraint |
|-------|------------|
| `tenantId` | `string`, `format: uuid` |
| `schemaVersion` | `const: "2"` |

Legacy `schema` const strings (e.g. `sentinelvoice.FeatureFrame/1`) may still appear until
producers are migrated in later features; **`schemaVersion: "2"`** is the V2 discriminator.

## Schemas

| Schema | Direction |
|--------|-----------|
| `FeatureFrame` | Inference → Decision |
| `TelemetryFrame` | Decision → Presentation |
| `AuditBlock` | Decision ledger |
| `SessionStartRequest` | Presentation/Media → Decision |
| `ChallengeIssued` | Decision → Presentation |
| `ChallengeResult` | Decision (audit + fusion) |
| `InterventionOverride` | Presentation → Decision |
| `CrossChannelEvent` | Ingest → Decision |

Shared enums: `common.defs.json`.

## Regenerate frontend artefacts

```bash
bash scripts/gen-contracts.sh
```

Reads **this** `docs/contracts/v2` tree. Generators may fail or produce validators that reject
current v1 runtime payloads until later features update producers/consumers — noted in
`docs/v2/CHANGELOG.md`.

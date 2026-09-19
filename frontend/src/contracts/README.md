# Frontend contract artefacts

Generated from **`docs/contracts/v2/`** (V2 source of truth):

- `contracts.d.ts` — ambient editor hints
- `validators.js` — precompiled Ajv validators

Do not hand-edit. Regenerate with:

```bash
bash scripts/gen-contracts.sh
```

Note (F0): v2 schemas require `tenantId` + `schemaVersion: "2"`. Runtime payloads and
frontend tests may still follow v1 until later features / F20 reconcile them.

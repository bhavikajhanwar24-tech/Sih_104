#!/usr/bin/env bash
# Regenerate frontend contract artifacts from docs/contracts/*.schema.json
# Emits:
#   frontend/src/contracts/contracts.d.ts  (ambient JSDoc/editor hints)
#   frontend/src/contracts/validators.js   (precompiled Ajv ESM validators)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CONTRACTS="$ROOT/docs/contracts"
OUT_DIR="$ROOT/frontend/src/contracts"
DTS="$OUT_DIR/contracts.d.ts"

mkdir -p "$OUT_DIR"

SCHEMAS=(
  FeatureFrame
  TelemetryFrame
  AuditBlock
  SessionStartRequest
  ChallengeIssued
  ChallengeResult
  InterventionOverride
  CrossChannelEvent
)

# Prefer local frontend toolchain when present (after npm install).
export PATH="$ROOT/frontend/node_modules/.bin:$PATH"

# json-schema-to-typescript CLI binary is `json2ts`
JSON2TS=(json2ts)
if ! command -v json2ts >/dev/null 2>&1; then
  JSON2TS=(npx --no-install json2ts)
fi

# ---------------------------------------------------------------------------
# a) Ambient declaration file (editor hints only — not compiled)
# ---------------------------------------------------------------------------
{
  echo "/* GENERATED - DO NOT EDIT. Editor hints only; not compiled. Run scripts/gen-contracts.sh */"
  echo ""
  for name in "${SCHEMAS[@]}"; do
    "${JSON2TS[@]}" \
      "$CONTRACTS/${name}.schema.json" \
      --bannerComment '' \
      --unreachableDefinitions true \
      --additionalProperties false
    echo ""
  done
} | sed -E \
  -e 's/^export (interface|type|enum|declare|const|class) /\1 /g' \
  -e '/^export \{\};?$/d' \
  -e '/^export \{/d' \
  > "$DTS"

echo "Wrote $DTS"

# ---------------------------------------------------------------------------
# b) Precompiled Ajv ESM validators (one export per schema)
# ---------------------------------------------------------------------------
node "$ROOT/scripts/gen-validators.mjs"

echo "Contracts regenerated."

#!/usr/bin/env bash
# Validate frozen contracts (ajv-cli defaults to draft-07; we need draft 2020-12).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CONTRACTS="$ROOT/docs/contracts"

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

for name in "${SCHEMAS[@]}"; do
  echo "compile $name"
  npx --yes ajv-cli@5 compile --spec=draft2020 \
    -s "$CONTRACTS/${name}.schema.json"
done

echo "validate FeatureFrame fixture"
npx --yes ajv-cli@5 validate --spec=draft2020 \
  -s "$CONTRACTS/FeatureFrame.schema.json" \
  -d "$CONTRACTS/fixtures/FeatureFrame.example.json"

echo "validate TelemetryFrame fixture"
npx --yes ajv-cli@5 validate --spec=draft2020 \
  -s "$CONTRACTS/TelemetryFrame.schema.json" \
  -d "$CONTRACTS/fixtures/TelemetryFrame.example.json"

echo "All contracts OK"

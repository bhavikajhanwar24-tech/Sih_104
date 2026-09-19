#!/usr/bin/env bash
# Load fixture scenarios into the Decision Plane and print UI entry URLs.
set -euo pipefail

API="${SENTINELVOICE_API_BASE:-http://127.0.0.1:8080}"
UI="${SENTINELVOICE_UI_BASE:-http://127.0.0.1:5173}"

wait_api() {
  local deadline=$((SECONDS + 120))
  while (( SECONDS < deadline )); do
    if curl -sf "$API/actuator/health" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  echo "ERROR: backend not healthy at $API" >&2
  exit 1
}

load() {
  local id="$1"
  # autoReplay=false — JRE backend image has no Python replay launcher.
  curl -sf -X POST \
    "$API/api/v1/scenario/${id}/load?mode=replay&autoReplay=false" \
    -H 'Content-Type: application/json'
}

echo "==> waiting for backend $API"
wait_api

echo "==> loading deepfake-ceo-wire"
CEO_JSON="$(load deepfake-ceo-wire)"
PY=python3
command -v python3 >/dev/null 2>&1 || PY=python
CEO_SID="$(echo "$CEO_JSON" | "$PY" -c 'import json,sys; print(json.load(sys.stdin)["sessionId"])')"

echo "==> loading hinglish-grandparent"
HI_JSON="$(load hinglish-grandparent)"
HI_SID="$(echo "$HI_JSON" | "$PY" -c 'import json,sys; print(json.load(sys.stdin)["sessionId"])')"

cat <<EOF

Demo sessions seeded (select scenario + Start in the UI, or use session IDs below):

  Analyst console (Deepfake CFO wire):
    $UI/
    scenario: deepfake-ceo-wire
    sessionId: $CEO_SID

  Senior Shield (Hinglish grandparent):
    $UI/
    scenario: hinglish-grandparent
    sessionId: $HI_SID

  Scenario API:
    POST $API/api/v1/scenario/{id}/load

EOF

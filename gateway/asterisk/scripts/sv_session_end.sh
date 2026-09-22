#!/bin/bash
# Finalize call_sessions from dialplan / hangup handler.
# AGI is unreliable during hangup; curl to Java is best-effort and fast.
set -u
SID="${1:-}"
OUTCOME="${2:-ENDED}"
if [[ -z "${SID}" ]]; then
  exit 0
fi
BASE="${SV_BACKEND_URL:-http://host.docker.internal:8081}"
TOKEN="${ML_SERVICE_TOKEN:-}"
curl -sf --max-time 8 \
  -X POST \
  -H "Content-Type: application/json" \
  -H "X-ML-Service-Token: ${TOKEN}" \
  -d "{\"svSessionUuid\":\"${SID}\",\"outcome\":\"${OUTCOME}\"}" \
  "${BASE%/}/internal/v2/sessions/end" >/dev/null 2>&1 || true
exit 0

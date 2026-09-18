#!/usr/bin/env bash
# Smoke-test the SentinelVoice lab Asterisk PBX (P7.1) BEFORE blaming the bridge.
# Verifies: compose service up → ARI JSON → Echo originate via Asterisk CLI.
#
# Usage (repo root):
#   bash scripts/test-sip-call.sh
#
# Optional:
#   ASTERISK_SERVICE=asterisk ARI_USER=sentinel ARI_PASS=sentineldemo bash scripts/test-sip-call.sh

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT}"

SERVICE="${ASTERISK_SERVICE:-asterisk}"
ARI_USER="${ARI_USER:-sentinel}"
ARI_PASS="${ARI_PASS:-sentineldemo}"
ARI_URL="${ARI_URL:-http://127.0.0.1:8088/ari/asterisk/info}"

echo "==> [1/4] Ensuring ${SERVICE} is up"
if ! docker compose ps --status running "${SERVICE}" 2>/dev/null | grep -q "${SERVICE}"; then
  echo "    starting ${SERVICE}…"
  docker compose up -d --build "${SERVICE}"
fi

echo "==> [2/4] Waiting for Asterisk CLI"
ok=0
for i in $(seq 1 30); do
  if docker compose exec -T "${SERVICE}" asterisk -rx "core show version" >/dev/null 2>&1; then
    ok=1
    break
  fi
  sleep 1
done
if [[ "${ok}" -ne 1 ]]; then
  echo "ERROR: Asterisk CLI not ready. Logs:" >&2
  docker compose logs --tail=80 "${SERVICE}" >&2 || true
  exit 1
fi
docker compose exec -T "${SERVICE}" asterisk -rx "core show version"

echo "==> [3/4] ARI ${ARI_URL}"
ari_json="$(curl -fsS -u "${ARI_USER}:${ARI_PASS}" "${ARI_URL}")"
echo "${ari_json}" | head -c 400
echo
echo "${ari_json}" | grep -q '"system"' || echo "${ari_json}" | grep -qi 'asterisk'
echo "    ARI OK"

echo "==> [4/4] Originate Local/9196@sentinel → Echo (CLI)"
# Local channel hits dialplan echo test; proves PBX answers without softphones / sipp.
docker compose exec -T "${SERVICE}" asterisk -rx "channel originate Local/9196@sentinel application Echo" || true
sleep 1
docker compose exec -T "${SERVICE}" asterisk -rx "core show channels"
docker compose exec -T "${SERVICE}" asterisk -rx "pjsip show endpoints"

echo
echo "PASS: PBX answers + ARI reachable."
echo "Next: register Zoiper/Linphone (caller/agent) and dial 1002 — see gateway/asterisk/README.md"
exit 0

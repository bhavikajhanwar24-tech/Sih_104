#!/usr/bin/env bash
# Host fallback: start all four planes without Docker (Inference / Decision / Presentation on host).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

bash scripts/preflight.sh
bash scripts/fetch_models.sh

echo "==> Media (Asterisk)"
docker compose up -d asterisk

ML_PID=""
BE_PID=""
FE_PID=""
cleanup() {
  [[ -n "$ML_PID" ]] && kill "$ML_PID" 2>/dev/null || true
  [[ -n "$BE_PID" ]] && kill "$BE_PID" 2>/dev/null || true
  [[ -n "$FE_PID" ]] && kill "$FE_PID" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

wait_http() {
  local url="$1" label="$2" sec="${3:-120}"
  local deadline=$((SECONDS + sec))
  while (( SECONDS < deadline )); do
    if curl -sf "$url" >/dev/null 2>&1; then
      echo "$label ready: $url"
      return 0
    fi
    sleep 2
  done
  echo "ERROR: $label not healthy: $url" >&2
  exit 1
}

PY="${ROOT}/ml-engine/.venv/bin/python"
UV="${ROOT}/ml-engine/.venv/bin/uvicorn"
if [[ ! -x "$PY" ]]; then PY=python3; UV=""; fi

echo "==> Inference (ml-engine :8000)"
(
  cd ml-engine
  export SENTINELVOICE_ML_JAVA_INGEST_WS="${SENTINELVOICE_ML_JAVA_INGEST_WS:-ws://127.0.0.1:8080/ws/features}"
  if [[ -x "${ROOT}/ml-engine/.venv/bin/uvicorn" ]]; then
    exec "${ROOT}/ml-engine/.venv/bin/uvicorn" app.main:app --host 127.0.0.1 --port 8000
  else
    exec "$PY" -m uvicorn app.main:app --host 127.0.0.1 --port 8000
  fi
) &
ML_PID=$!
wait_http "http://127.0.0.1:8000/health" "ml-engine"

echo "==> Decision (backend :8080)"
(
  cd backend
  if [[ -x "${ROOT}/tools/apache-maven-3.9.9/bin/mvn" ]]; then
    exec "${ROOT}/tools/apache-maven-3.9.9/bin/mvn" -q spring-boot:run
  else
    exec mvn -q spring-boot:run
  fi
) &
BE_PID=$!
wait_http "http://127.0.0.1:8080/actuator/health" "backend" 180

echo "==> Presentation (frontend :5173)"
(
  cd frontend
  exec npm run dev -- --host 127.0.0.1 --port 5173 --strictPort
) &
FE_PID=$!
wait_http "http://127.0.0.1:5173/" "frontend" 90

echo ""
echo "Stack ready — open http://127.0.0.1:5173/ (Ctrl+C stops host planes; Asterisk keeps running)"
wait

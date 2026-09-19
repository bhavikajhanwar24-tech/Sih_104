#!/usr/bin/env bash
# Pre-flight checks before demo / docker compose up.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FAIL=0

warn() { echo "WARN: $*" >&2; }
die() { echo "ERROR: $*" >&2; FAIL=1; }

port_free() {
  local port="$1"
  if command -v ss >/dev/null 2>&1; then
    ss -H -ltn "sport = :$port" 2>/dev/null | grep -q . && return 1
  elif command -v netstat >/dev/null 2>&1; then
    netstat -an 2>/dev/null | grep -E "[.:]$port[[:space:]]" | grep -q LISTEN && return 1
  fi
  return 0
}

echo "==> SentinelVoice preflight"

# Toolchain
if command -v java >/dev/null 2>&1; then
  ver="$(java -version 2>&1 | head -1)"
  echo "java: $ver"
  java -version 2>&1 | grep -q 'version "21' || warn "Java 21 recommended (found: $ver)"
else
  die "java not on PATH (Java 21 required for host fallback)"
fi

if command -v node >/dev/null 2>&1; then
  nv="$(node -p 'process.versions.node')"
  echo "node: v$nv"
  major="${nv%%.*}"
  if (( major < 20 )); then die "Node 20+ required (found v$nv)"; fi
else
  die "node not on PATH (Node 20+ required for host fallback)"
fi

if command -v python3 >/dev/null 2>&1; then
  pv="$(python3 -c 'import sys; print(".".join(map(str, sys.version_info[:3])))')"
  echo "python: $pv"
  python3 -c 'import sys; assert sys.version_info[:2] >= (3, 11)' || die "Python 3.11+ required"
elif command -v python >/dev/null 2>&1; then
  pv="$(python -c 'import sys; print(".".join(map(str, sys.version_info[:3])))')"
  echo "python: $pv"
  python -c 'import sys; assert sys.version_info[:2] >= (3, 11)' || die "Python 3.11+ required"
else
  die "python3 not on PATH"
fi

if command -v docker >/dev/null 2>&1; then
  echo "docker: $(docker --version)"
else
  die "docker not on PATH"
fi

if command -v ffmpeg >/dev/null 2>&1; then
  echo "ffmpeg: $(ffmpeg -version 2>&1 | head -1)"
else
  warn "ffmpeg not found (replay / codec fixtures may fail on host)"
fi

# Ports — 8080 must be free for compose backend publish
PORTS=(8080 8000 5173 5060 8088 9092)
for p in "${PORTS[@]}"; do
  if port_free "$p"; then
    echo "port $p: free"
  else
    if [[ "$p" == "8080" ]]; then
      die "port 8080 is in use (required for Decision Plane / compose publish)"
    else
      warn "port $p appears in use"
    fi
  fi
done

CKPT="$ROOT/ml-engine/models/antispoof/codec_aug.pt"
if [[ -f "$CKPT" ]]; then
  echo "models: $CKPT present"
else
  die "missing $CKPT — run: bash scripts/fetch_models.sh"
fi

if (( FAIL != 0 )); then
  exit 1
fi
echo "preflight: OK"

#!/usr/bin/env bash
# Print the primary LAN IPv4 (for SIP_EXTERNAL_IP). Excludes docker/loopback.
set -euo pipefail
if command -v ip >/dev/null 2>&1; then
  ip -4 route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i=="src"){print $(i+1); exit}}'
  exit 0
fi
ifconfig 2>/dev/null | awk '/inet / && $2 != "127.0.0.1" {print $2; exit}'

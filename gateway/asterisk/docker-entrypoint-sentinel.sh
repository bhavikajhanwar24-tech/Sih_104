#!/bin/bash
# Apply SentinelVoice lab configs, then start Asterisk.
# Avoid the stock image entrypoint restoring default http.conf (binds 127.0.0.1),
# which breaks published-port ARI on Docker Desktop.
set -euo pipefail

SRC="${SENTINEL_ASTERISK_CONFIG:-/opt/sentinelvoice/asterisk-config}"
if [[ -d "${SRC}" ]]; then
  for f in "${SRC}"/*.conf; do
    [[ -f "${f}" ]] || continue
    base="$(basename "${f}")"
    # Do not clobber a read-only bind mount.
    if cp -f "${f}" "/etc/asterisk/${base}" 2>/dev/null; then
      :
    fi
  done
fi

# Match common andrius flags when running as root.
if [[ "$(id -u)" = "0" ]] && id asterisk >/dev/null 2>&1; then
  exec asterisk -f -vvv -U asterisk -p
fi
exec asterisk -f -vvv

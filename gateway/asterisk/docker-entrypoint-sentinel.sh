#!/bin/bash
# Apply SentinelVoice configs with envsubst, write PJSIP realtime DB config, then start Asterisk.
# F10: endpoints come from PostgreSQL schema asterisk (res_config_pgsql) — not static pjsip.conf.
set -euo pipefail

SRC="${SENTINEL_ASTERISK_CONFIG:-/opt/sentinelvoice/asterisk-config}"
SCRIPTS="${SENTINEL_ASTERISK_SCRIPTS:-/opt/sentinelvoice/scripts}"

export SIP_EXTERNAL_IP="${SIP_EXTERNAL_IP:-127.0.0.1}"
export SIP_LOCAL_NET="${SIP_LOCAL_NET:-127.0.0.1/32}"
export ASTERISK_DB_HOST="${ASTERISK_DB_HOST:-host.docker.internal}"
export ASTERISK_DB_PORT="${ASTERISK_DB_PORT:-5432}"
export ASTERISK_DB_NAME="${ASTERISK_DB_NAME:-sentinelvoice}"
export ASTERISK_DB_USER="${ASTERISK_DB_USER:-sv_asterisk}"
export ASTERISK_DB_PASSWORD="${ASTERISK_DB_PASSWORD:-changeme_asterisk}"
export SV_BACKEND_URL="${SV_BACKEND_URL:-http://host.docker.internal:8081}"
export ML_SERVICE_TOKEN="${ML_SERVICE_TOKEN:-}"

subst_file() {
  local src="$1"
  local dest="$2"
  if command -v envsubst >/dev/null 2>&1; then
    # Support both ${VAR} and __VAR__ placeholders
    sed -e "s/__SIP_EXTERNAL_IP__/${SIP_EXTERNAL_IP}/g" \
        -e "s/__SIP_LOCAL_NET__/${SIP_LOCAL_NET//\//\\/}/g" \
        "${src}" | envsubst '${SIP_EXTERNAL_IP} ${SIP_LOCAL_NET}' > "${dest}"
  else
    sed -e "s/__SIP_EXTERNAL_IP__/${SIP_EXTERNAL_IP}/g" \
        -e "s/__SIP_LOCAL_NET__/${SIP_LOCAL_NET//\//\\/}/g" \
        "${src}" > "${dest}"
  fi
}

if [[ -d "${SRC}" ]]; then
  for f in "${SRC}"/*.conf; do
    [[ -f "${f}" ]] || continue
    base="$(basename "${f}")"
    case "${base}" in
      pjsip.conf|rtp.conf)
        subst_file "${f}" "/etc/asterisk/${base}" || true
        ;;
      *)
        if cp -f "${f}" "/etc/asterisk/${base}" 2>/dev/null; then
          :
        fi
        ;;
    esac
  done
fi

# Ensure AGI script is executable
if [[ -d "${SCRIPTS}" ]]; then
  chmod +x "${SCRIPTS}"/*.py 2>/dev/null || true
fi

# --- PJSIP REALTIME (res_config_pgsql) ---------------------------------------
# Asterisk 20 reads res_pgsql.conf (NOT res_config_pgsql.conf).
# Accepts dbhost/dbuser/dbpass and hostname/user/password aliases.
cat > /etc/asterisk/res_pgsql.conf <<EOF
[general]
dbhost=${ASTERISK_DB_HOST}
hostname=${ASTERISK_DB_HOST}
dbport=${ASTERISK_DB_PORT}
port=${ASTERISK_DB_PORT}
dbname=${ASTERISK_DB_NAME}
dbuser=${ASTERISK_DB_USER}
user=${ASTERISK_DB_USER}
dbpass=${ASTERISK_DB_PASSWORD}
password=${ASTERISK_DB_PASSWORD}
dbappname=asterisk
appname=asterisk
encoding=UTF-8
requirements=warn
EOF
rm -f /etc/asterisk/res_config_pgsql.conf
if id asterisk >/dev/null 2>&1; then
  chown asterisk:asterisk /etc/asterisk/res_pgsql.conf 2>/dev/null || true
fi
chmod 644 /etc/asterisk/res_pgsql.conf

cat > /etc/asterisk/extconfig.conf <<'EOF'
[settings]
ps_endpoints => pgsql,asterisk,ps_endpoints
ps_auths => pgsql,asterisk,ps_auths
ps_aors => pgsql,asterisk,ps_aors
ps_contacts => pgsql,asterisk,ps_contacts
EOF

# Sorcery: prefer realtime for endpoints/auths/aors; keep transport/global in conf
if [[ -f /etc/asterisk/sorcery.conf ]]; then
  if ! grep -q '\[res_pjsip\]' /etc/asterisk/sorcery.conf 2>/dev/null; then
    cat >> /etc/asterisk/sorcery.conf <<'EOF'

[res_pjsip]
endpoint=realtime,ps_endpoints
auth=realtime,ps_auths
aor=realtime,ps_aors
EOF
  fi
else
  cat > /etc/asterisk/sorcery.conf <<'EOF'
[res_pjsip]
endpoint=realtime,ps_endpoints
auth=realtime,ps_auths
aor=realtime,ps_aors
EOF
fi

# Match common andrius flags when running as root.
if [[ "$(id -u)" = "0" ]] && id asterisk >/dev/null 2>&1; then
  exec asterisk -f -vvv -U asterisk -p
fi
exec asterisk -f -vvv

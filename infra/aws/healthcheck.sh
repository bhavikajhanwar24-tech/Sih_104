#!/bin/bash
set -euo pipefail
cd /opt/sentinelvoice

i=0
while [ "$i" -lt 60 ]; do
  if docker compose -f docker-compose.aws.yml exec -T backend curl -sf http://127.0.0.1:8080/actuator/health >/dev/null \
    && docker compose -f docker-compose.aws.yml exec -T ml-engine curl -sf http://127.0.0.1:8000/health >/dev/null \
    && docker compose -f docker-compose.aws.yml exec -T frontend wget -qO- http://127.0.0.1/ >/dev/null; then
    echo "app healthy"
    exit 0
  fi
  i=$((i + 1))
  sleep 10
done

docker compose -f docker-compose.aws.yml ps
exit 1

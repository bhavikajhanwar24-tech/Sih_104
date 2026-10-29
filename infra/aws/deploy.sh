#!/bin/bash
# Pull main and rebuild the app containers. Safe to run from Jenkins or an SSH shell.
set -euo pipefail

if [ ! -f /opt/sentinelvoice-secrets/app.env ]; then
  echo "missing /opt/sentinelvoice-secrets/app.env" >&2
  exit 1
fi

cd /opt/sentinelvoice
if [ "${SV_DEPLOY_SKIP_GIT:-}" != "1" ]; then
  git config --global --add safe.directory /opt/sentinelvoice || true
  git fetch --depth 1 origin main
  git reset --hard origin/main
  export SV_DEPLOY_SKIP_GIT=1
  exec bash /opt/sentinelvoice/infra/aws/deploy.sh
fi

export COMPOSE_PARALLEL_LIMIT=1
export DOCKER_BUILDKIT=1

docker compose -f docker-compose.aws.yml build backend
docker compose -f docker-compose.aws.yml build frontend
docker compose -f docker-compose.aws.yml build ml-engine
docker compose -f docker-compose.aws.yml up -d --remove-orphans

bash /opt/sentinelvoice/infra/aws/healthcheck.sh

docker compose -f docker-compose.aws.yml exec -T postgres \
  psql -U sv_bootstrap -d sentinelvoice -v ON_ERROR_STOP=1 \
  -c "REVOKE sv_asterisk FROM sv_owner;" \
  -c "ALTER ROLE sv_owner NOCREATEROLE;"

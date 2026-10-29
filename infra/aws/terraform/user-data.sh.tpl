#!/bin/bash
# First boot: Docker, swap, clone, Jenkins. The app image build is the Jenkins job.
set -euxo pipefail
exec > /var/log/sentinelvoice-bootstrap.log 2>&1

dnf install -y docker git
systemctl enable --now docker

mkdir -p /usr/libexec/docker/cli-plugins
curl -fsSL -o /usr/libexec/docker/cli-plugins/docker-compose \
  https://github.com/docker/compose/releases/download/v2.29.7/docker-compose-linux-x86_64
chmod +x /usr/libexec/docker/cli-plugins/docker-compose

if [ ! -f /swapfile ]; then
  fallocate -l 4G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi
sysctl -w vm.swappiness=40

mkdir -p /opt/sentinelvoice-secrets
if [ ! -d /opt/sentinelvoice/.git ]; then
  git clone --branch "${repo_branch}" --depth 1 "${repo_url}" /opt/sentinelvoice
fi

TOKEN=$(curl -fsS -X PUT "http://169.254.169.254/latest/api/token" -H "X-aws-ec2-metadata-token-ttl-seconds: 21600")
PUBLIC_IP=$(curl -fsS -H "X-aws-ec2-metadata-token: $TOKEN" http://169.254.169.254/latest/meta-data/public-ipv4 || echo localhost)

if [ ! -f /opt/sentinelvoice-secrets/app.env ]; then
  cp /opt/sentinelvoice/infra/aws/env.aws.example /opt/sentinelvoice-secrets/app.env
  sed -i "s/__PUBLIC_HOST__/$PUBLIC_IP/g" /opt/sentinelvoice-secrets/app.env
  chmod 600 /opt/sentinelvoice-secrets/app.env
fi

if [ ! -f /opt/sentinelvoice-secrets/jenkins.env ]; then
  echo "JENKINS_ADMIN_PASSWORD=$(openssl rand -hex 16)" > /opt/sentinelvoice-secrets/jenkins.env
  chmod 600 /opt/sentinelvoice-secrets/jenkins.env
fi

chmod +x /opt/sentinelvoice/infra/aws/deploy.sh /opt/sentinelvoice/infra/aws/healthcheck.sh /opt/sentinelvoice/infra/aws/jenkins/entrypoint.sh

cd /opt/sentinelvoice
docker compose -f infra/aws/docker-compose.jenkins.yml up -d

cat > /etc/motd <<EOF
SentinelVoice
  App (after the Jenkins job is green): http://$PUBLIC_IP/
  Jenkins: http://$PUBLIC_IP:8080/   user admin
  Password: sudo cat /opt/sentinelvoice-secrets/jenkins.env
  Bootstrap log: /var/log/sentinelvoice-bootstrap.log
  Stop this instance when you are finished so AWS credits are not burned.
EOF

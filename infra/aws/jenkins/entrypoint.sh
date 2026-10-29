#!/bin/bash
# Install a static Docker client, the Compose plugin, and Jenkins plugins, then start Jenkins.
set -euo pipefail

install_docker_cli() {
  mkdir -p /var/jenkins_home/bin /usr/local/lib/docker/cli-plugins /usr/libexec/docker/cli-plugins
  if [ ! -x /usr/local/bin/docker ]; then
    if [ -x /var/jenkins_home/bin/docker ]; then
      cp /var/jenkins_home/bin/docker /usr/local/bin/docker
    else
      curl -fsSL -o /tmp/docker.tgz https://download.docker.com/linux/static/stable/x86_64/docker-27.3.1.tgz
      tar -xzf /tmp/docker.tgz -C /tmp
      mv /tmp/docker/docker /usr/local/bin/docker
      chmod +x /usr/local/bin/docker
      cp /usr/local/bin/docker /var/jenkins_home/bin/docker
      rm -rf /tmp/docker /tmp/docker.tgz
    fi
  fi
  if [ ! -x /usr/local/lib/docker/cli-plugins/docker-compose ]; then
    if [ -x /var/jenkins_home/bin/docker-compose ]; then
      cp /var/jenkins_home/bin/docker-compose /usr/local/lib/docker/cli-plugins/docker-compose
    else
      curl -fsSL -o /usr/local/lib/docker/cli-plugins/docker-compose \
        https://github.com/docker/compose/releases/download/v2.29.7/docker-compose-linux-x86_64
      chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
      cp /usr/local/lib/docker/cli-plugins/docker-compose /var/jenkins_home/bin/docker-compose
    fi
  fi
  ln -sfn /usr/local/lib/docker/cli-plugins/docker-compose /usr/libexec/docker/cli-plugins/docker-compose
}

install_docker_cli
git config --global --add safe.directory /opt/sentinelvoice || true

if [ ! -f /var/jenkins_home/.sv-plugins-ok ]; then
  jenkins-plugin-cli --plugin-file /opt/sentinelvoice/infra/aws/jenkins/plugins.txt
  touch /var/jenkins_home/.sv-plugins-ok
fi

exec /usr/bin/tini -- /usr/local/bin/jenkins.sh "$@"

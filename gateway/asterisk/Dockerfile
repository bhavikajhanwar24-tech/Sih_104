# SentinelVoice lab Asterisk PBX (F10 multi-tenant PJSIP REALTIME)
FROM andrius/asterisk:20

LABEL org.opencontainers.image.title="sentinelvoice-asterisk"
LABEL org.opencontainers.image.description="Local SIP PBX with PJSIP realtime + ARI"

USER root
RUN (apt-get update && apt-get install -y --no-install-recommends python3 gettext-base curl \
    && rm -rf /var/lib/apt/lists/*) \
 || (apk add --no-cache python3 gettext curl) \
 || true

COPY config/ /opt/sentinelvoice/asterisk-config/
COPY scripts/ /opt/sentinelvoice/scripts/
COPY sounds/ /var/lib/asterisk/sounds/
COPY docker-entrypoint-sentinel.sh /docker-entrypoint-sentinel.sh
RUN sed -i 's/\r$//' /docker-entrypoint-sentinel.sh \
 && sed -i 's/\r$//' /opt/sentinelvoice/scripts/*.py \
 && chmod +x /docker-entrypoint-sentinel.sh \
 && chmod +x /opt/sentinelvoice/scripts/*.py \
 && cp -f /opt/sentinelvoice/asterisk-config/*.conf /etc/asterisk/ \
 && ls -la /var/lib/asterisk/sounds/sentinel-*.wav

HEALTHCHECK --interval=15s --timeout=5s --start-period=25s --retries=5 \
  CMD asterisk -rx "core show version" || exit 1

ENTRYPOINT ["bash", "/docker-entrypoint-sentinel.sh"]
CMD ["asterisk", "-f", "-vvv"]

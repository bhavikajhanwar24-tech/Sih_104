# SentinelVoice lab Asterisk PBX (Context §11.3 / P7.1)
# Asterisk 20 LTS — host networking avoids SIP/RTP Docker-NAT pain.
# If :20 is missing on your registry mirror, try andrius/asterisk:latest.
FROM andrius/asterisk:20

LABEL org.opencontainers.image.title="sentinelvoice-asterisk"
LABEL org.opencontainers.image.description="Local SIP PBX with caller/agent endpoints + ARI"

# Keep lab configs out of the path the image may reset, then copy in via our wrapper.
COPY config/ /opt/sentinelvoice/asterisk-config/
COPY sounds/ /var/lib/asterisk/sounds/
COPY docker-entrypoint-sentinel.sh /docker-entrypoint-sentinel.sh
RUN sed -i 's/\r$//' /docker-entrypoint-sentinel.sh \
 && chmod +x /docker-entrypoint-sentinel.sh \
 && cp -f /opt/sentinelvoice/asterisk-config/*.conf /etc/asterisk/ \
 && ls -la /var/lib/asterisk/sounds/sentinel-*.wav

# Health: Asterisk CLI responds (ARI checked by scripts/test-sip-call.sh).
HEALTHCHECK --interval=15s --timeout=5s --start-period=25s --retries=5 \
  CMD asterisk -rx "core show version" || exit 1

ENTRYPOINT ["bash", "/docker-entrypoint-sentinel.sh"]
# Foreground + verbose console (logger.conf also enables verbose).
CMD ["asterisk", "-f", "-vvv"]

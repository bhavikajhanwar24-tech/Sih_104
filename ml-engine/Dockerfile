# SentinelVoice Inference Plane — production image (models mounted at runtime).
FROM python:3.11-slim AS builder

WORKDIR /build

RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    libsndfile1 \
    && rm -rf /var/lib/apt/lists/*

COPY pyproject.toml README.md ./
COPY app ./app
COPY training ./training
COPY scripts ./scripts

RUN pip install --no-cache-dir .

FROM python:3.11-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    ffmpeg \
    libsndfile1 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=builder /usr/local/lib/python3.11/site-packages /usr/local/lib/python3.11/site-packages
COPY --from=builder /usr/local/bin /usr/local/bin
COPY app ./app
COPY training ./training
COPY scripts ./scripts
COPY pyproject.toml README.md ./

# Tier-1 checkpoint and optional HF weights — bind-mount from host (see docker-compose.yml).
# Expected layout: /app/models/antispoof/codec_aug.pt
ENV SENTINELVOICE_ML_JAVA_DECISION_HTTP=http://backend:8080

ENV PORT=8000
EXPOSE 8000 10000

HEALTHCHECK --interval=15s --timeout=5s --retries=5 --start-period=60s \
  CMD curl -sf http://127.0.0.1:${PORT:-8000}/health || exit 1

CMD ["sh", "-c", "uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8000}"]

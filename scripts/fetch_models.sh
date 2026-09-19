#!/usr/bin/env bash
# Ensure local ML artifacts exist before docker compose / live inference.
# Does NOT download ECAPA or Whisper weights — those populate HF caches on first use.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ML="$ROOT/ml-engine"
CKPT="$ML/models/antispoof/codec_aug.pt"

echo "==> Tier-1 antispoof (codec_aug.pt)"
if command -v python3 >/dev/null 2>&1; then
  PY=python3
elif command -v python >/dev/null 2>&1; then
  PY=python
else
  echo "ERROR: python3.11+ required for ensure_antispoof_checkpoint.py" >&2
  exit 1
fi

if [[ -x "$ML/.venv/bin/python" ]]; then
  PY="$ML/.venv/bin/python"
fi

"$PY" "$ROOT/scripts/ensure_antispoof_checkpoint.py"

if [[ ! -f "$CKPT" ]]; then
  echo "ERROR: missing $CKPT after ensure step" >&2
  exit 2
fi

cat <<'NOTE'

Optional Hugging Face / SpeechBrain caches (not bundled; first slow-path run may download):

  ECAPA speaker (speechbrain/spkrec-ecapa-voxceleb):
    ~/.cache/huggingface/   or  HF_HOME / TRANSFORMERS_CACHE

  faster-whisper ASR (tiny/small):
    ~/.cache/huggingface/hub/   (CTranslate2 model files)

Mount a host cache into ml-engine for offline demos, e.g. in docker-compose:
  - ${HF_HOME:-~/.cache/huggingface}:/root/.cache/huggingface:ro

NOTE

echo "models ok: $CKPT"

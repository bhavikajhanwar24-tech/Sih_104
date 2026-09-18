#!/usr/bin/env bash
# Build codec-degraded fixtures for channel forensics tests and P12 evaluation.
# Produces 7 round-tripped 16 kHz WAVs from each source WAV in fixtures/codec/source/.
#
# Variants:
#   pcm_mulaw_8k, pcm_alaw_8k, amr_nb_12k2, amr_nb_4k75, libopus_24k, libopus_12k, mp3_64k
#
# Usage:
#   bash scripts/build_codec_fixtures.sh
#   bash scripts/build_codec_fixtures.sh /path/to/source_dir /path/to/out_dir
#
# Requires ffmpeg on PATH (or set FFMPEG=...).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
SRC_DIR="${1:-${ROOT}/tests/fixtures/codec/source}"
OUT_DIR="${2:-${ROOT}/tests/fixtures/codec/degraded}"

FFMPEG="${FFMPEG:-ffmpeg}"
if ! command -v "${FFMPEG}" >/dev/null 2>&1; then
  # Fallback for Windows / CI without system ffmpeg: imageio-ffmpeg binary.
  for py in python3 python py; do
    if command -v "${py}" >/dev/null 2>&1; then
      FFMPEG="$("${py}" -c 'import imageio_ffmpeg; print(imageio_ffmpeg.get_ffmpeg_exe())' 2>/dev/null || true)"
      [[ -n "${FFMPEG}" ]] && break
    fi
  done
fi
if [[ -z "${FFMPEG}" ]]; then
  echo "ERROR: ffmpeg not found. Install ffmpeg or: pip install imageio-ffmpeg" >&2
  exit 1
fi
# Windows Git-Bash: imageio path may not be flagged executable; still usable.
if ! command -v "${FFMPEG}" >/dev/null 2>&1 && [[ ! -f "${FFMPEG}" ]]; then
  echo "ERROR: ffmpeg binary missing: ${FFMPEG}" >&2
  exit 1
fi

mkdir -p "${SRC_DIR}" "${OUT_DIR}"

# Seed a source tone+noise WAV if none provided (keeps the script self-bootstrapping).
shopt -s nullglob
sources=("${SRC_DIR}"/*.wav)
if [[ ${#sources[@]} -eq 0 ]]; then
  echo "No source WAVs in ${SRC_DIR}; generating seed_speech.wav"
  export SRC_DIR
  python - <<'PY'
import math, wave, struct, pathlib, os
sr = 16000
n = sr * 3
path = pathlib.Path(os.environ["SRC_DIR"]) / "seed_speech.wav"
path.parent.mkdir(parents=True, exist_ok=True)
with wave.open(str(path), "w") as w:
    w.setnchannels(1)
    w.setsampwidth(2)
    w.setframerate(sr)
    frames = bytearray()
    for i in range(n):
        t = i / sr
        # Harmonic stack + pauses to look vaguely speech-like.
        if int(t * 2) % 2 == 0:
            x = 0.25 * math.sin(2 * math.pi * 140 * t)
            x += 0.12 * math.sin(2 * math.pi * 280 * t)
            x += 0.06 * math.sin(2 * math.pi * 420 * t)
        else:
            x = 0.01 * math.sin(2 * math.pi * 50 * t)
        frames += struct.pack("<h", int(max(-1, min(1, x)) * 32767))
    w.writeframes(frames)
print(path)
PY
  sources=("${SRC_DIR}"/*.wav)
fi

encode_one() {
  local src="$1"
  local base
  base="$(basename "${src}" .wav)"
  local tmp
  tmp="$(mktemp -d)"

  # 1) µ-law 8 kHz
  "${FFMPEG}" -y -hide_banner -loglevel error -i "${src}" \
    -ar 8000 -ac 1 -c:a pcm_mulaw "${tmp}/mulaw.wav"
  "${FFMPEG}" -y -hide_banner -loglevel error -i "${tmp}/mulaw.wav" \
    -ar 16000 -ac 1 -c:a pcm_s16le "${OUT_DIR}/${base}__pcm_mulaw_8k.wav"

  # 2) A-law 8 kHz
  "${FFMPEG}" -y -hide_banner -loglevel error -i "${src}" \
    -ar 8000 -ac 1 -c:a pcm_alaw "${tmp}/alaw.wav"
  "${FFMPEG}" -y -hide_banner -loglevel error -i "${tmp}/alaw.wav" \
    -ar 16000 -ac 1 -c:a pcm_s16le "${OUT_DIR}/${base}__pcm_alaw_8k.wav"

  # 3) AMR-NB 12.2 kbps
  "${FFMPEG}" -y -hide_banner -loglevel error -i "${src}" \
    -ar 8000 -ac 1 -c:a libopencore_amrnb -b:a 12200 "${tmp}/amr122.amr" \
    || "${FFMPEG}" -y -hide_banner -loglevel error -i "${src}" \
         -ar 8000 -ac 1 -c:a amr_nb -b:a 12200 "${tmp}/amr122.amr"
  "${FFMPEG}" -y -hide_banner -loglevel error -i "${tmp}/amr122.amr" \
    -ar 16000 -ac 1 -c:a pcm_s16le "${OUT_DIR}/${base}__amr_nb_12k2.wav"

  # 4) AMR-NB 4.75 kbps
  "${FFMPEG}" -y -hide_banner -loglevel error -i "${src}" \
    -ar 8000 -ac 1 -c:a libopencore_amrnb -b:a 4750 "${tmp}/amr475.amr" \
    || "${FFMPEG}" -y -hide_banner -loglevel error -i "${src}" \
         -ar 8000 -ac 1 -c:a amr_nb -b:a 4750 "${tmp}/amr475.amr"
  "${FFMPEG}" -y -hide_banner -loglevel error -i "${tmp}/amr475.amr" \
    -ar 16000 -ac 1 -c:a pcm_s16le "${OUT_DIR}/${base}__amr_nb_4k75.wav"

  # 5) Opus 24 kbps
  "${FFMPEG}" -y -hide_banner -loglevel error -i "${src}" \
    -ar 16000 -ac 1 -c:a libopus -b:a 24000 "${tmp}/opus24.opus"
  "${FFMPEG}" -y -hide_banner -loglevel error -i "${tmp}/opus24.opus" \
    -ar 16000 -ac 1 -c:a pcm_s16le "${OUT_DIR}/${base}__libopus_24k.wav"

  # 6) Opus 12 kbps
  "${FFMPEG}" -y -hide_banner -loglevel error -i "${src}" \
    -ar 16000 -ac 1 -c:a libopus -b:a 12000 "${tmp}/opus12.opus"
  "${FFMPEG}" -y -hide_banner -loglevel error -i "${tmp}/opus12.opus" \
    -ar 16000 -ac 1 -c:a pcm_s16le "${OUT_DIR}/${base}__libopus_12k.wav"

  # 7) MP3 64 kbps
  "${FFMPEG}" -y -hide_banner -loglevel error -i "${src}" \
    -ar 16000 -ac 1 -c:a libmp3lame -b:a 64k "${tmp}/mp3_64.mp3"
  "${FFMPEG}" -y -hide_banner -loglevel error -i "${tmp}/mp3_64.mp3" \
    -ar 16000 -ac 1 -c:a pcm_s16le "${OUT_DIR}/${base}__mp3_64k.wav"

  rm -rf "${tmp}"
  echo "OK ${base} -> 7 variants in ${OUT_DIR}"
}

export SRC_DIR
for src in "${sources[@]}"; do
  encode_one "${src}"
done

echo "Done. Variants:"
ls -1 "${OUT_DIR}" | sed 's/^/  /'

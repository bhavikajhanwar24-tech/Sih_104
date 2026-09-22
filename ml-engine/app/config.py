from pathlib import Path
import os

from dotenv import load_dotenv
from pydantic_settings import BaseSettings, SettingsConfigDict

# Repo-root .env uses ML_SERVICE_TOKEN; Settings prefix expects SENTINELVOICE_ML_*.
_REPO_ROOT = Path(__file__).resolve().parents[2]
load_dotenv(_REPO_ROOT / ".env", override=False)
load_dotenv(Path(__file__).resolve().parents[1] / ".env", override=False)
if not os.environ.get("SENTINELVOICE_ML_SERVICE_TOKEN") and os.environ.get("ML_SERVICE_TOKEN"):
    os.environ["SENTINELVOICE_ML_SERVICE_TOKEN"] = os.environ["ML_SERVICE_TOKEN"]


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="SENTINELVOICE_ML_")

    sample_rate: int = 16000
    ring_capacity_seconds: float = 8.0
    vad_frame_ms: int = 30
    vad_aggressiveness: int = 2
    ack_every_frames: int = 10
    version: str = "0.1.0"
    emit_enabled: bool = True
    java_ingest_ws: str = "ws://127.0.0.1:8081/ws/features"
    java_decision_http: str = "http://127.0.0.1:8081"
    service_token: str = ""
    default_max_concurrent_calls: int = 20
    emit_interval_ms: int = 500
    window_seconds: float = 2.0
    emit_queue_max: int = 64
    emit_backoff_max_s: float = 5.0
    validate_frames: bool = False
    debug: bool = False

    # Prefer Praat (parselmouth) for jitter/shimmer/HNR; False forces librosa fallback.
    use_parselmouth: bool = True

    # Human ranges for unnaturalness_score (Context §10.3 / Teixeira et al. / Baken & Orlikoff).
    # Jitter local: typical conversational ~0.5–1.5% (0.005–0.015 as fraction).
    jitter_local_min: float = 0.005
    jitter_local_max: float = 0.015
    # Shimmer local: typical ~3–8% (0.03–0.08 as fraction).
    shimmer_local_min: float = 0.03
    shimmer_local_max: float = 0.08
    # HNR: human conversational often ~15–25 dB; >28 dB is suspiciously clean (synthetic tell).
    hnr_too_clean_db: float = 28.0
    # F0 relative std below this fraction of mean → over-smooth contour.
    f0_rel_std_min: float = 0.02

    min_voiced_seconds: float = 0.5
    breath_min_cumulative_speech_s: float = 15.0

    # Speaker / Voice Passport (Context §10.5) — recalibrate on your data.
    # Channel mismatch shifts these sharply; enrol per channel profile.
    speaker_match_threshold: float = 0.70
    speaker_mismatch_threshold: float = 0.50
    speaker_model_id: str = "speechbrain/spkrec-ecapa-voxceleb"
    speaker_min_speech_s: float = 1.5
    speaker_latency_budget_ms: float = 40.0
    java_passport_url: str = "http://127.0.0.1:8081/api/v1/passport/register"

    # Slow-path ASR (Context §7.2 / §10.6) — faster-whisper / CTranslate2.
    asr_enabled: bool = True
    # Lab CPU: tiny keeps the fast-path/gauge alive; small starves the 500ms tick.
    asr_model_size: str = "tiny"
    asr_device: str = "auto"  # auto | cpu | cuda
    asr_compute_type: str = "default"  # default → int8 (cpu) / float16 (cuda)
    asr_window_seconds: float = 6.0
    asr_interval_ms: int = 2500
    asr_vad_speech_ratio_min: float = 0.3
    asr_snippet_chars: int = 160
    asr_latency_budget_ms: float = 900.0
    # F11 — in-memory rolling transcript horizon (seconds); zeroised on session close.
    asr_rolling_seconds: float = 20.0
    # Tenant-allowed ASR languages (Whisper codes). Hinglish = code-switch hi+en.
    asr_languages: str = "en,hi"


settings = Settings()

# Host .env uses ML_SERVICE_TOKEN; Settings prefix expects SENTINELVOICE_ML_SERVICE_TOKEN.
if not settings.service_token:
    alt = os.environ.get("ML_SERVICE_TOKEN") or os.environ.get("SENTINELVOICE_ML_SERVICE_TOKEN") or ""
    if alt:
        object.__setattr__(settings, "service_token", alt)

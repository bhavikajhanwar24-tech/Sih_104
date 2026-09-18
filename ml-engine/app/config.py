from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="SENTINELVOICE_ML_")

    sample_rate: int = 16000
    ring_capacity_seconds: float = 8.0
    vad_frame_ms: int = 30
    vad_aggressiveness: int = 2
    ack_every_frames: int = 10
    version: str = "0.1.0"
    emit_enabled: bool = True
    java_ingest_ws: str = "ws://127.0.0.1:8080/ws/features"
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


settings = Settings()

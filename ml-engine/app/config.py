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


settings = Settings()

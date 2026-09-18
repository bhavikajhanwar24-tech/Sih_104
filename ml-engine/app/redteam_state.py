"""Per-session red-team perturbation state (defensive testing only)."""

from __future__ import annotations

import threading
from typing import Optional

from app.modules.adversarial import PerturbationConfig

_lock = threading.Lock()
_configs: dict[str, PerturbationConfig] = {}


def get_config(session_id: str) -> PerturbationConfig:
    with _lock:
        return _configs.get(session_id, PerturbationConfig()).clamp()


def set_config(session_id: str, config: PerturbationConfig | dict) -> PerturbationConfig:
    cfg = (
        config
        if isinstance(config, PerturbationConfig)
        else PerturbationConfig.from_dict(config)
    ).clamp()
    with _lock:
        _configs[session_id] = cfg
    return cfg


def clear_config(session_id: str) -> None:
    with _lock:
        _configs.pop(session_id, None)


def has_active(session_id: str) -> bool:
    cfg = get_config(session_id)
    return bool(cfg.enabled)

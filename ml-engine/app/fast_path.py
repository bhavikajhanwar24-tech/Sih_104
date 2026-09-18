from __future__ import annotations

import numpy as np

from app.types import ChannelProfile
from app.vad import is_speech


def extract(audio: np.ndarray, sr: int, profile: ChannelProfile) -> dict:
    """Return stub FeatureFrame families. Real DSP arrives in P4.x."""
    samples = np.asarray(audio, dtype=np.float32).reshape(-1)
    if samples.size == 0:
        rms = 0.0
    else:
        rms = float(np.sqrt(np.mean(np.square(samples, dtype=np.float64))))
    # STUB - replaced in P4.x
    spoof = float(np.clip(rms * 4.0, 0.0, 1.0))
    # STUB - replaced in P4.x
    confidence = float(np.clip(0.35 + rms, 0.0, 1.0))
    return {
        "speechPresent": bool(is_speech(samples)),
        "voice": {
            "available": True,
            # STUB - replaced in P4.x
            "spoofProbability": spoof,
            # STUB - replaced in P4.x
            "modelId": "stub-rms-p2.2",
            # STUB - replaced in P4.x
            "confidence": confidence,
        },
        # STUB - replaced in P4.x
        "channel": {"available": False},
        # STUB - replaced in P4.x
        "prosody": {"available": False},
        # STUB - replaced in P4.x
        "speaker": {"available": False},
        # STUB - replaced in P4.x
        "watermark": {"available": False},
        # STUB - replaced in P4.x
        "linguistic": {"available": False},
        "profile": profile,
    }

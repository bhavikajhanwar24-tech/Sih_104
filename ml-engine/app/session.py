from __future__ import annotations

import threading
import time
from dataclasses import dataclass, field
from typing import Optional

from app.config import settings
from app.fast_path import FastPathState
from app.ring_buffer import RingBuffer
from app.types import ChannelProfile


@dataclass
class PipelineSession:
    session_id: str
    ring_buffer: RingBuffer
    profile: ChannelProfile
    seq: int = 0
    emit_seq: int = 0
    cumulative_speech_ms: int = 0
    last_emitted_samples: int = -1
    created_at: float = field(default_factory=time.time)
    fast_path: FastPathState = field(init=False)

    def __post_init__(self) -> None:
        self.fast_path = FastPathState(session_id=self.session_id)


class SessionRegistry:
    def __init__(self) -> None:
        self._sessions: dict[str, PipelineSession] = {}
        self._lock = threading.Lock()

    def create(
        self,
        session_id: str,
        profile: ChannelProfile = ChannelProfile.WEBRTC_WIDEBAND,
    ) -> PipelineSession:
        with self._lock:
            existing = self._sessions.get(session_id)
            if existing is not None:
                return existing
            session = PipelineSession(
                session_id=session_id,
                ring_buffer=RingBuffer(
                    capacity_seconds=settings.ring_capacity_seconds,
                    sample_rate=settings.sample_rate,
                ),
                profile=profile,
            )
            self._sessions[session_id] = session
            return session

    def get(self, session_id: str) -> Optional[PipelineSession]:
        with self._lock:
            return self._sessions.get(session_id)

    def close(self, session_id: str) -> bool:
        with self._lock:
            session = self._sessions.pop(session_id, None)
        if session is None:
            return False
        session.ring_buffer.zeroise()
        return True

    def active_count(self) -> int:
        with self._lock:
            return len(self._sessions)


registry = SessionRegistry()

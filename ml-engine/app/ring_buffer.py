"""Raw PCM exists ONLY here. Fixed capacity, overwritten in place,
never persisted. This class is the enforcement point for Context §6.3."""

from __future__ import annotations

import numpy as np


class RingBuffer:
    """Bounded float32 circular buffer. Capacity never changes after construction."""

    def __init__(self, capacity_seconds: float = 8.0, sample_rate: int = 16000) -> None:
        if capacity_seconds <= 0:
            raise ValueError("capacity_seconds must be positive")
        if sample_rate <= 0:
            raise ValueError("sample_rate must be positive")
        self.sample_rate = int(sample_rate)
        self.capacity = int(capacity_seconds * self.sample_rate)
        self._buf = np.zeros(self.capacity, dtype=np.float32)
        self._write_pos = 0
        self.total_samples_written = 0

    @property
    def nbytes(self) -> int:
        return int(self._buf.nbytes)

    def write(self, samples: np.ndarray) -> None:
        chunk = np.asarray(samples, dtype=np.float32).reshape(-1)
        n = int(chunk.size)
        if n == 0:
            return
        cap = self.capacity
        if n >= cap:
            np.copyto(self._buf, chunk[-cap:], casting="unsafe")
            self._write_pos = 0
            self.total_samples_written += n
            return
        pos = self._write_pos
        first = min(n, cap - pos)
        self._buf[pos : pos + first] = chunk[:first]
        rest = n - first
        if rest:
            self._buf[0:rest] = chunk[first:]
            self._write_pos = rest
        else:
            self._write_pos = pos + first
            if self._write_pos == cap:
                self._write_pos = 0
        self.total_samples_written += n

    def read_window(self, duration_s: float, hop_offset_s: float = 0.0) -> np.ndarray:
        length = int(round(duration_s * self.sample_rate))
        hop = int(round(hop_offset_s * self.sample_rate))
        if length < 0 or hop < 0:
            raise ValueError("duration_s and hop_offset_s must be non-negative")
        cap = self.capacity
        length = min(length, cap)
        out = np.zeros(length, dtype=np.float32)
        filled = min(self.total_samples_written, cap)
        if length == 0 or filled <= hop:
            return out
        n_take = min(length, filled - hop)
        end = (self._write_pos - hop) % cap
        start = (end - n_take) % cap
        dest = length - n_take
        if start < end:
            out[dest:] = self._buf[start:end]
        elif n_take == cap:
            out[dest:] = np.concatenate((self._buf[start:], self._buf[:end]))
        else:
            head = cap - start
            out[dest : dest + head] = self._buf[start:]
            out[dest + head :] = self._buf[:end]
        return out

    def zeroise(self) -> None:
        self._buf[:] = 0.0
        self._write_pos = 0
        self.total_samples_written = 0

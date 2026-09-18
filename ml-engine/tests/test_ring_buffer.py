import numpy as np

from app.ring_buffer import RingBuffer
from app.session import SessionRegistry
from app.types import ChannelProfile


def test_nbytes_constant_after_10000_writes() -> None:
    buf = RingBuffer(capacity_seconds=8.0, sample_rate=16000)
    before = buf.nbytes
    chunk = np.ones(317, dtype=np.float32)
    for _ in range(10_000):
        buf.write(chunk)
    assert buf.nbytes == before
    assert buf._buf.shape == (8 * 16000,)
    assert buf._buf.dtype == np.float32


def test_wraparound_preserves_most_recent_n() -> None:
    sr = 100
    buf = RingBuffer(capacity_seconds=1.0, sample_rate=sr)
    for start in range(0, 150, 25):
        buf.write(np.arange(start, start + 25, dtype=np.float32))
    window = buf.read_window(1.0)
    np.testing.assert_array_equal(window, np.arange(50, 150, dtype=np.float32))
    assert buf.total_samples_written == 150


def test_read_window_is_a_copy() -> None:
    buf = RingBuffer(capacity_seconds=1.0, sample_rate=8)
    buf.write(np.arange(8, dtype=np.float32))
    window = buf.read_window(1.0)
    window[:] = 99.0
    np.testing.assert_array_equal(buf.read_window(1.0), np.arange(8, dtype=np.float32))


def test_hop_offset_skips_most_recent() -> None:
    buf = RingBuffer(capacity_seconds=1.0, sample_rate=10)
    buf.write(np.arange(10, dtype=np.float32))
    window = buf.read_window(0.5, hop_offset_s=0.3)
    np.testing.assert_array_equal(window, np.array([2, 3, 4, 5, 6], dtype=np.float32))


def test_zeroise_leaves_no_nonzero_sample() -> None:
    buf = RingBuffer(capacity_seconds=0.5, sample_rate=16000)
    buf.write(np.linspace(-1.0, 1.0, 4000, dtype=np.float32))
    assert np.count_nonzero(buf._buf) > 0
    buf.zeroise()
    assert np.count_nonzero(buf._buf) == 0
    assert np.all(buf._buf == 0.0)
    assert buf.total_samples_written == 0
    assert not np.any(buf.read_window(0.5))


def test_session_close_zeroises_ring_buffer() -> None:
    registry = SessionRegistry()
    session = registry.create("close-me", ChannelProfile.VOIP_WIDEBAND)
    session.ring_buffer.write(np.ones(512, dtype=np.float32))
    held = session.ring_buffer
    assert np.any(held._buf)
    assert registry.close("close-me") is True
    assert np.count_nonzero(held._buf) == 0
    assert registry.get("close-me") is None

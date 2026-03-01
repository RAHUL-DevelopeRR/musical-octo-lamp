"""Tests for the audio_capture module."""

import array
import struct
import threading
import time

import pytest

from vlc_ai_subtitles.core.audio_capture import (
    CHUNK_SAMPLES,
    SAMPLE_RATE,
    AudioBuffer,
    BytesCapture,
)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _make_silence(num_samples: int) -> bytes:
    """Return *num_samples* silent (zero-valued) 16-bit PCM samples as bytes."""
    return (b"\x00\x00") * num_samples


def _make_pcm_bytes(values) -> bytes:
    """Pack a list of int16 *values* into little-endian bytes."""
    return struct.pack(f"<{len(values)}h", *values)


# ---------------------------------------------------------------------------
# AudioBuffer
# ---------------------------------------------------------------------------


class TestAudioBuffer:
    def test_no_chunk_before_threshold(self):
        buf = AudioBuffer(chunk_samples=4)
        buf.push(_make_silence(3))
        assert buf.get_chunk(timeout=0.05) is None

    def test_chunk_available_at_threshold(self):
        buf = AudioBuffer(chunk_samples=4)
        buf.push(_make_silence(4))
        chunk = buf.get_chunk(timeout=0.1)
        assert chunk is not None
        assert len(chunk) == 4 * 2  # 4 samples × 2 bytes each

    def test_multiple_chunks(self):
        buf = AudioBuffer(chunk_samples=4)
        buf.push(_make_silence(12))
        chunks = []
        for _ in range(3):
            c = buf.get_chunk(timeout=0.1)
            assert c is not None
            chunks.append(c)
        # No fourth chunk should be present
        assert buf.get_chunk(timeout=0.05) is None

    def test_leftover_not_emitted_before_flush(self):
        buf = AudioBuffer(chunk_samples=4)
        buf.push(_make_silence(5))  # 1 full chunk + 1 leftover
        chunk = buf.get_chunk(timeout=0.1)
        assert chunk is not None
        assert buf.get_chunk(timeout=0.05) is None  # leftover not yet emitted

    def test_flush_emits_partial_chunk(self):
        buf = AudioBuffer(chunk_samples=4)
        buf.push(_make_silence(5))
        buf.get_chunk(timeout=0.1)  # consume the full chunk
        buf.flush()
        partial = buf.get_chunk(timeout=0.1)
        assert partial is not None
        assert len(partial) == 1 * 2

    def test_flush_on_empty_buffer_is_safe(self):
        buf = AudioBuffer(chunk_samples=4)
        buf.flush()  # should not raise

    def test_sample_values_preserved(self):
        values = [1, -1, 32767, -32768]
        buf = AudioBuffer(chunk_samples=len(values))
        buf.push(_make_pcm_bytes(values))
        chunk = buf.get_chunk(timeout=0.1)
        assert chunk is not None
        recovered = list(array.array("h", chunk))
        assert recovered == values

    def test_thread_safety(self):
        """Multiple producers can push concurrently without data corruption."""
        chunk_samples = 100
        buf = AudioBuffer(chunk_samples=chunk_samples)
        num_producers = 5
        samples_per_producer = 400  # 4 chunks worth each

        def producer():
            for _ in range(samples_per_producer // 10):
                buf.push(_make_silence(10))
                time.sleep(0.001)

        threads = [threading.Thread(target=producer) for _ in range(num_producers)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        buf.flush()

        total_samples_consumed = 0
        while True:
            chunk = buf.get_chunk(timeout=0.05)
            if chunk is None:
                break
            total_samples_consumed += len(chunk) // 2

        total_pushed = num_producers * samples_per_producer
        assert total_samples_consumed == total_pushed


# ---------------------------------------------------------------------------
# BytesCapture
# ---------------------------------------------------------------------------


class TestBytesCapture:
    def test_feed_and_get_chunk(self):
        buf = AudioBuffer(chunk_samples=4)
        cap = BytesCapture(buf)
        cap.feed(_make_silence(4))
        chunk = buf.get_chunk(timeout=0.1)
        assert chunk is not None

    def test_flush_delegates_to_buffer(self):
        buf = AudioBuffer(chunk_samples=4)
        cap = BytesCapture(buf)
        cap.feed(_make_silence(2))
        cap.flush()
        chunk = buf.get_chunk(timeout=0.1)
        assert chunk is not None
        assert len(chunk) == 2 * 2

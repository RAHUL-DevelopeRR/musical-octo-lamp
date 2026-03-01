"""
Audio capture module.

Captures audio from the system (loopback) or from a media file's audio stream
and provides fixed-length chunks for transcription.
"""

import array
import io
import queue
import threading
import wave

SAMPLE_RATE = 16000  # Whisper expects 16 kHz mono audio
CHUNK_DURATION_S = 30  # seconds per transcription chunk
CHUNK_SAMPLES = SAMPLE_RATE * CHUNK_DURATION_S


class AudioBuffer:
    """Thread-safe ring buffer that accumulates PCM samples and yields chunks."""

    def __init__(self, chunk_samples: int = CHUNK_SAMPLES) -> None:
        self._chunk_samples = chunk_samples
        self._buffer: array.array = array.array("h")  # signed 16-bit PCM
        self._lock = threading.Lock()
        self._chunk_queue: queue.Queue = queue.Queue()

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def push(self, samples: bytes) -> None:
        """Append raw PCM bytes (signed 16-bit little-endian) to the buffer.

        When enough samples have accumulated a full chunk is pushed to the
        internal queue for consumption by :py:meth:`get_chunk`.
        """
        new_samples = array.array("h", samples)
        with self._lock:
            self._buffer.extend(new_samples)
            while len(self._buffer) >= self._chunk_samples:
                chunk = self._buffer[: self._chunk_samples]
                self._buffer = self._buffer[self._chunk_samples :]
                self._chunk_queue.put(bytes(chunk))

    def get_chunk(self, timeout: float = 1.0):
        """Return the next complete chunk as bytes, or *None* on timeout."""
        try:
            return self._chunk_queue.get(timeout=timeout)
        except queue.Empty:
            return None

    def flush(self) -> None:
        """Flush any remaining samples as a (possibly partial) chunk."""
        with self._lock:
            if self._buffer:
                self._chunk_queue.put(bytes(self._buffer))
                self._buffer = array.array("h")


class WavFileCapture:
    """Read a WAV file and feed it into an :py:class:`AudioBuffer`."""

    def __init__(self, path: str, audio_buffer: AudioBuffer) -> None:
        self._path = path
        self._audio_buffer = audio_buffer

    def capture(self) -> None:
        """Read the entire file synchronously, pushing chunks to the buffer."""
        with wave.open(self._path, "rb") as wf:
            if wf.getnchannels() != 1 or wf.getsampwidth() != 2:
                raise ValueError(
                    "WavFileCapture requires mono 16-bit PCM audio. "
                    f"Got channels={wf.getnchannels()}, sampwidth={wf.getsampwidth()}."
                )
            read_size = SAMPLE_RATE // 10  # 100 ms at a time
            while True:
                raw = wf.readframes(read_size)
                if not raw:
                    break
                self._audio_buffer.push(raw)
        self._audio_buffer.flush()


class BytesCapture:
    """Feed raw PCM bytes (already 16 kHz mono s16le) into an AudioBuffer."""

    def __init__(self, audio_buffer: AudioBuffer) -> None:
        self._audio_buffer = audio_buffer

    def feed(self, data: bytes) -> None:
        """Push *data* into the underlying buffer."""
        self._audio_buffer.push(data)

    def flush(self) -> None:
        """Flush remaining samples."""
        self._audio_buffer.flush()

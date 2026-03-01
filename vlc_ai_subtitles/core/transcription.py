"""
Transcription module.

Wraps OpenAI Whisper to transcribe raw audio chunks into timed text segments.
All inference runs locally — no network access is required.
"""

from __future__ import annotations

import array
import logging
from dataclasses import dataclass, field
from typing import List, Optional

try:
    import numpy as np  # type: ignore[import]
except ImportError:  # pragma: no cover
    np = None  # type: ignore[assignment]

logger = logging.getLogger(__name__)


@dataclass
class TranscriptSegment:
    """A single subtitle segment produced by the transcription engine."""

    start: float  # seconds from the beginning of the chunk
    end: float  # seconds from the beginning of the chunk
    text: str
    language: Optional[str] = None


@dataclass
class TranscriptResult:
    """Collection of segments returned for one audio chunk."""

    segments: List[TranscriptSegment] = field(default_factory=list)
    language: Optional[str] = None


class WhisperTranscriber:
    """Offline transcriber backed by OpenAI Whisper.

    Parameters
    ----------
    model_size:
        Whisper model variant.  Smaller models are faster; larger ones are
        more accurate.  Valid values: ``"tiny"``, ``"base"``, ``"small"``,
        ``"medium"``, ``"large"``.
    language:
        BCP-47 language code (e.g. ``"en"``, ``"es"``).  When *None* Whisper
        auto-detects the language.
    device:
        PyTorch device string such as ``"cpu"`` or ``"cuda"``.
    """

    VALID_MODELS = ("tiny", "base", "small", "medium", "large")

    def __init__(
        self,
        model_size: str = "base",
        language: Optional[str] = None,
        device: str = "cpu",
    ) -> None:
        if model_size not in self.VALID_MODELS:
            raise ValueError(
                f"model_size must be one of {self.VALID_MODELS}, got {model_size!r}."
            )
        self._model_size = model_size
        self._language = language
        self._device = device
        self._model = None  # lazy-loaded on first call

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    def _load_model(self):
        """Load the Whisper model (called once on first transcription)."""
        try:
            import whisper  # type: ignore[import]
        except ImportError as exc:
            raise ImportError(
                "openai-whisper is required for transcription. "
                "Install it with: pip install openai-whisper"
            ) from exc
        logger.info("Loading Whisper model %r on device %r …", self._model_size, self._device)
        self._model = whisper.load_model(self._model_size, device=self._device)
        logger.info("Whisper model loaded.")

    @staticmethod
    def _pcm_bytes_to_float32(raw: bytes):
        """Convert signed 16-bit PCM bytes to a float32 numpy array."""
        if np is None:  # pragma: no cover
            raise ImportError("numpy is required. Install it with: pip install numpy")
        samples = array.array("h", raw)
        audio = np.array(samples, dtype=np.float32) / 32768.0
        return audio

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def transcribe(self, pcm_bytes: bytes, time_offset: float = 0.0) -> TranscriptResult:
        """Transcribe a chunk of raw PCM audio.

        Parameters
        ----------
        pcm_bytes:
            Raw audio as signed 16-bit little-endian PCM at 16 000 Hz mono.
        time_offset:
            Offset (in seconds) of the beginning of this chunk within the
            overall media timeline.  Segment timestamps are adjusted by this
            amount.

        Returns
        -------
        TranscriptResult
        """
        if self._model is None:
            self._load_model()

        import whisper  # type: ignore[import]

        audio = self._pcm_bytes_to_float32(pcm_bytes)
        audio = whisper.pad_or_trim(audio)

        options = whisper.DecodingOptions(
            language=self._language,
            fp16=(self._device != "cpu"),
        )

        result = self._model.transcribe(
            audio,
            language=self._language,
            word_timestamps=False,
        )

        segments = [
            TranscriptSegment(
                start=seg["start"] + time_offset,
                end=seg["end"] + time_offset,
                text=seg["text"].strip(),
                language=result.get("language"),
            )
            for seg in result.get("segments", [])
            if seg["text"].strip()
        ]

        return TranscriptResult(segments=segments, language=result.get("language"))

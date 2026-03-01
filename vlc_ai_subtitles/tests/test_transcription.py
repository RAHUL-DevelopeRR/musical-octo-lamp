"""Tests for the transcription module (WhisperTranscriber)."""

import array
import struct
from unittest.mock import MagicMock, patch

import pytest

from vlc_ai_subtitles.core.transcription import (
    TranscriptResult,
    TranscriptSegment,
    WhisperTranscriber,
)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _make_silence_bytes(num_samples: int = 16000) -> bytes:
    """Return *num_samples* silent signed 16-bit PCM bytes."""
    return b"\x00\x00" * num_samples


# ---------------------------------------------------------------------------
# WhisperTranscriber construction
# ---------------------------------------------------------------------------


class TestWhisperTranscriberInit:
    def test_default_model_size(self):
        t = WhisperTranscriber()
        assert t._model_size == "base"

    def test_custom_model_size(self):
        t = WhisperTranscriber(model_size="small")
        assert t._model_size == "small"

    def test_invalid_model_raises(self):
        with pytest.raises(ValueError, match="model_size"):
            WhisperTranscriber(model_size="giant")

    def test_language_stored(self):
        t = WhisperTranscriber(language="en")
        assert t._language == "en"

    def test_device_stored(self):
        t = WhisperTranscriber(device="cuda")
        assert t._device == "cuda"

    def test_model_lazy_loaded(self):
        t = WhisperTranscriber()
        assert t._model is None


# ---------------------------------------------------------------------------
# WhisperTranscriber.transcribe — mocked Whisper
# ---------------------------------------------------------------------------


def _build_mock_whisper_module(segments=None, language="en"):
    """Return a mock that mimics the ``whisper`` package interface."""
    if segments is None:
        segments = [
            {"start": 0.0, "end": 1.0, "text": "Hello world."},
        ]

    mock_whisper = MagicMock()
    mock_model = MagicMock()
    mock_model.transcribe.return_value = {"segments": segments, "language": language}
    mock_whisper.load_model.return_value = mock_model
    mock_whisper.DecodingOptions.return_value = MagicMock()
    mock_whisper.pad_or_trim.side_effect = lambda audio: audio
    return mock_whisper


def _fake_pcm_to_float32(raw: bytes):
    """Stand-in for WhisperTranscriber._pcm_bytes_to_float32 that avoids numpy."""
    return raw  # whisper is mocked so the exact value doesn't matter


class TestWhisperTranscriberTranscribe:
    def test_returns_transcript_result(self):
        transcriber = WhisperTranscriber()
        mock_whisper = _build_mock_whisper_module()

        with patch.dict("sys.modules", {"whisper": mock_whisper}), \
             patch.object(WhisperTranscriber, "_pcm_bytes_to_float32", staticmethod(_fake_pcm_to_float32)):
            result = transcriber.transcribe(b"\x00\x00" * 16000)

        assert isinstance(result, TranscriptResult)

    def test_segments_text(self):
        transcriber = WhisperTranscriber()
        segments_data = [
            {"start": 0.0, "end": 1.0, "text": "Hello world."},
            {"start": 1.5, "end": 3.0, "text": "  Foo bar.  "},
        ]
        mock_whisper = _build_mock_whisper_module(segments=segments_data)

        with patch.dict("sys.modules", {"whisper": mock_whisper}), \
             patch.object(WhisperTranscriber, "_pcm_bytes_to_float32", staticmethod(_fake_pcm_to_float32)):
            result = transcriber.transcribe(b"\x00\x00" * 16000)

        assert len(result.segments) == 2
        assert result.segments[0].text == "Hello world."
        assert result.segments[1].text == "Foo bar."  # stripped

    def test_time_offset_applied(self):
        transcriber = WhisperTranscriber()
        segments_data = [{"start": 1.0, "end": 2.0, "text": "Hi."}]
        mock_whisper = _build_mock_whisper_module(segments=segments_data)

        with patch.dict("sys.modules", {"whisper": mock_whisper}), \
             patch.object(WhisperTranscriber, "_pcm_bytes_to_float32", staticmethod(_fake_pcm_to_float32)):
            result = transcriber.transcribe(b"\x00\x00" * 16000, time_offset=10.0)

        assert result.segments[0].start == pytest.approx(11.0)
        assert result.segments[0].end == pytest.approx(12.0)

    def test_empty_text_segments_skipped(self):
        transcriber = WhisperTranscriber()
        segments_data = [
            {"start": 0.0, "end": 1.0, "text": "   "},  # whitespace-only
            {"start": 1.0, "end": 2.0, "text": "Real text."},
        ]
        mock_whisper = _build_mock_whisper_module(segments=segments_data)

        with patch.dict("sys.modules", {"whisper": mock_whisper}), \
             patch.object(WhisperTranscriber, "_pcm_bytes_to_float32", staticmethod(_fake_pcm_to_float32)):
            result = transcriber.transcribe(b"\x00\x00" * 16000)

        assert len(result.segments) == 1
        assert result.segments[0].text == "Real text."

    def test_language_in_result(self):
        transcriber = WhisperTranscriber()
        mock_whisper = _build_mock_whisper_module(language="fr")

        with patch.dict("sys.modules", {"whisper": mock_whisper}), \
             patch.object(WhisperTranscriber, "_pcm_bytes_to_float32", staticmethod(_fake_pcm_to_float32)):
            result = transcriber.transcribe(b"\x00\x00" * 16000)

        assert result.language == "fr"

    def test_missing_whisper_raises_import_error(self):
        transcriber = WhisperTranscriber()

        with patch.dict("sys.modules", {"whisper": None}):
            with pytest.raises((ImportError, TypeError)):
                transcriber.transcribe(b"\x00\x00" * 16000)

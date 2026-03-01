"""Tests for the SubtitlePipeline."""

import os
import struct
import threading
import time
from unittest.mock import MagicMock, patch

import pytest

from vlc_ai_subtitles.core.pipeline import SubtitlePipeline
from vlc_ai_subtitles.core.transcription import TranscriptResult, TranscriptSegment


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _make_silence(num_samples: int) -> bytes:
    return b"\x00\x00" * num_samples


def _make_mock_transcriber(segments=None):
    mock = MagicMock()
    mock.transcribe.return_value = TranscriptResult(
        segments=segments or [TranscriptSegment(start=0.0, end=1.0, text="Hello.")],
        language="en",
    )
    return mock


# ---------------------------------------------------------------------------
# SubtitlePipeline
# ---------------------------------------------------------------------------


class TestSubtitlePipeline:
    def test_srt_file_created(self, tmp_path):
        output = str(tmp_path / "out.srt")
        pipeline = SubtitlePipeline(output_path=output)
        pipeline._transcriber = _make_mock_transcriber()

        pipeline.start()
        # Feed enough samples to trigger at least one chunk
        from vlc_ai_subtitles.core.audio_capture import CHUNK_SAMPLES

        pipeline.feed(_make_silence(CHUNK_SAMPLES))
        pipeline.stop()

        assert os.path.isfile(output)
        content = open(output).read()
        assert "Hello." in content

    def test_vtt_file_created(self, tmp_path):
        output = str(tmp_path / "out.vtt")
        pipeline = SubtitlePipeline(output_path=output)
        pipeline._transcriber = _make_mock_transcriber()

        pipeline.start()
        from vlc_ai_subtitles.core.audio_capture import CHUNK_SAMPLES

        pipeline.feed(_make_silence(CHUNK_SAMPLES))
        pipeline.stop()

        assert os.path.isfile(output)
        content = open(output).read()
        assert content.startswith("WEBVTT")
        assert "Hello." in content

    def test_on_segment_callback_called(self, tmp_path):
        output = str(tmp_path / "out.srt")
        received = []
        pipeline = SubtitlePipeline(
            output_path=output,
            on_segment=lambda segs: received.extend(segs),
        )
        pipeline._transcriber = _make_mock_transcriber(
            segments=[TranscriptSegment(start=0.0, end=1.0, text="Callback test.")]
        )

        pipeline.start()
        from vlc_ai_subtitles.core.audio_capture import CHUNK_SAMPLES

        pipeline.feed(_make_silence(CHUNK_SAMPLES))
        pipeline.stop()

        assert any(s.text == "Callback test." for s in received)

    def test_no_segments_no_file_content_issue(self, tmp_path):
        """Pipeline should not crash when transcription yields no segments."""
        output = str(tmp_path / "out.srt")
        pipeline = SubtitlePipeline(output_path=output)
        pipeline._transcriber = _make_mock_transcriber(segments=[])

        pipeline.start()
        from vlc_ai_subtitles.core.audio_capture import CHUNK_SAMPLES

        pipeline.feed(_make_silence(CHUNK_SAMPLES))
        pipeline.stop()  # should not raise

    def test_start_idempotent(self, tmp_path):
        output = str(tmp_path / "out.srt")
        pipeline = SubtitlePipeline(output_path=output)
        pipeline._transcriber = _make_mock_transcriber()

        pipeline.start()
        pipeline.start()  # second call should be a no-op
        pipeline.stop()

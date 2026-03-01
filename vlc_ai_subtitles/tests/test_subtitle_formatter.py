"""Tests for the subtitle_formatter module."""

import pytest

from vlc_ai_subtitles.core.subtitle_formatter import (
    _seconds_to_srt_timestamp,
    _seconds_to_vtt_timestamp,
    segments_to_srt,
    segments_to_vtt,
)
from vlc_ai_subtitles.core.transcription import TranscriptSegment


# ---------------------------------------------------------------------------
# Timestamp helpers
# ---------------------------------------------------------------------------


class TestSrtTimestamp:
    def test_zero(self):
        assert _seconds_to_srt_timestamp(0.0) == "00:00:00,000"

    def test_one_second(self):
        assert _seconds_to_srt_timestamp(1.0) == "00:00:01,000"

    def test_one_minute(self):
        assert _seconds_to_srt_timestamp(60.0) == "00:01:00,000"

    def test_one_hour(self):
        assert _seconds_to_srt_timestamp(3600.0) == "01:00:00,000"

    def test_milliseconds(self):
        assert _seconds_to_srt_timestamp(1.5) == "00:00:01,500"

    def test_rounding(self):
        # 1.9994 rounds to 1.999; 1.9995 rounds to 2.000
        assert _seconds_to_srt_timestamp(1.9994) == "00:00:01,999"
        assert _seconds_to_srt_timestamp(1.9995) == "00:00:02,000"

    def test_complex_value(self):
        # 3723.456 → 1h 2m 3.456s
        assert _seconds_to_srt_timestamp(3723.456) == "01:02:03,456"


class TestVttTimestamp:
    def test_dot_separator(self):
        ts = _seconds_to_vtt_timestamp(1.5)
        assert "." in ts
        assert "," not in ts
        assert ts == "00:00:01.500"


# ---------------------------------------------------------------------------
# segments_to_srt
# ---------------------------------------------------------------------------


class TestSegmentsToSrt:
    def _make_segments(self):
        return [
            TranscriptSegment(start=0.0, end=1.5, text="Hello world."),
            TranscriptSegment(start=2.0, end=4.0, text="This is a test."),
        ]

    def test_sequence_numbers(self):
        srt = segments_to_srt(self._make_segments())
        lines = srt.strip().split("\n")
        assert lines[0] == "1"
        # Find second block
        idx = lines.index("2")
        assert idx > 0

    def test_arrow_format(self):
        srt = segments_to_srt(self._make_segments())
        assert "00:00:00,000 --> 00:00:01,500" in srt
        assert "00:00:02,000 --> 00:00:04,000" in srt

    def test_text_present(self):
        srt = segments_to_srt(self._make_segments())
        assert "Hello world." in srt
        assert "This is a test." in srt

    def test_empty_segments(self):
        srt = segments_to_srt([])
        assert srt == ""

    def test_single_segment(self):
        segs = [TranscriptSegment(start=0.0, end=2.0, text="One.")]
        srt = segments_to_srt(segs)
        assert "1\n" in srt
        assert "One." in srt


# ---------------------------------------------------------------------------
# segments_to_vtt
# ---------------------------------------------------------------------------


class TestSegmentsToVtt:
    def _make_segments(self):
        return [
            TranscriptSegment(start=0.0, end=1.5, text="Hello world."),
            TranscriptSegment(start=2.0, end=4.0, text="This is a test."),
        ]

    def test_webvtt_header(self):
        vtt = segments_to_vtt(self._make_segments())
        assert vtt.startswith("WEBVTT")

    def test_dot_timestamp(self):
        vtt = segments_to_vtt(self._make_segments())
        assert "00:00:00.000 --> 00:00:01.500" in vtt
        assert "00:00:02.000 --> 00:00:04.000" in vtt

    def test_text_present(self):
        vtt = segments_to_vtt(self._make_segments())
        assert "Hello world." in vtt
        assert "This is a test." in vtt

    def test_no_sequence_numbers(self):
        vtt = segments_to_vtt(self._make_segments())
        lines = vtt.split("\n")
        # No bare "1" or "2" lines (unlike SRT)
        numbered = [ln for ln in lines if ln.strip() in ("1", "2")]
        assert numbered == []

    def test_empty_segments(self):
        vtt = segments_to_vtt([])
        assert vtt.startswith("WEBVTT")

"""
Subtitle formatting module.

Converts :py:class:`~vlc_ai_subtitles.core.transcription.TranscriptSegment`
objects into standard subtitle formats (SRT and WebVTT).
"""

from __future__ import annotations

from typing import Iterable, List

from vlc_ai_subtitles.core.transcription import TranscriptSegment


# ---------------------------------------------------------------------------
# Time helpers
# ---------------------------------------------------------------------------


def _seconds_to_srt_timestamp(seconds: float) -> str:
    """Return an SRT timestamp string ``HH:MM:SS,mmm``."""
    total_ms = int(round(seconds * 1000))
    ms = total_ms % 1000
    total_s = total_ms // 1000
    s = total_s % 60
    total_m = total_s // 60
    m = total_m % 60
    h = total_m // 60
    return f"{h:02d}:{m:02d}:{s:02d},{ms:03d}"


def _seconds_to_vtt_timestamp(seconds: float) -> str:
    """Return a WebVTT timestamp string ``HH:MM:SS.mmm``."""
    return _seconds_to_srt_timestamp(seconds).replace(",", ".")


# ---------------------------------------------------------------------------
# Formatters
# ---------------------------------------------------------------------------


def segments_to_srt(segments: Iterable[TranscriptSegment]) -> str:
    """Render *segments* as an SRT subtitle file string.

    Parameters
    ----------
    segments:
        Iterable of :py:class:`TranscriptSegment` objects.

    Returns
    -------
    str
        Full SRT file content.
    """
    lines: List[str] = []
    for index, seg in enumerate(segments, start=1):
        start_ts = _seconds_to_srt_timestamp(seg.start)
        end_ts = _seconds_to_srt_timestamp(seg.end)
        lines.append(str(index))
        lines.append(f"{start_ts} --> {end_ts}")
        lines.append(seg.text)
        lines.append("")
    return "\n".join(lines)


def segments_to_vtt(segments: Iterable[TranscriptSegment]) -> str:
    """Render *segments* as a WebVTT subtitle file string.

    Parameters
    ----------
    segments:
        Iterable of :py:class:`TranscriptSegment` objects.

    Returns
    -------
    str
        Full WebVTT file content (including the mandatory ``WEBVTT`` header).
    """
    lines: List[str] = ["WEBVTT", ""]
    for seg in segments:
        start_ts = _seconds_to_vtt_timestamp(seg.start)
        end_ts = _seconds_to_vtt_timestamp(seg.end)
        lines.append(f"{start_ts} --> {end_ts}")
        lines.append(seg.text)
        lines.append("")
    return "\n".join(lines)

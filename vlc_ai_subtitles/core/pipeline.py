"""
Subtitle pipeline module.

Orchestrates audio capture → transcription → subtitle writing in a
background thread, mirroring the streaming-engine design described in the
project README.
"""

from __future__ import annotations

import logging
import os
import threading
from typing import Callable, List, Optional

from vlc_ai_subtitles.core.audio_capture import AudioBuffer, BytesCapture, CHUNK_SAMPLES
from vlc_ai_subtitles.core.subtitle_formatter import segments_to_srt, segments_to_vtt
from vlc_ai_subtitles.core.transcription import TranscriptSegment, WhisperTranscriber

logger = logging.getLogger(__name__)

SegmentCallback = Callable[[List[TranscriptSegment]], None]


class SubtitlePipeline:
    """End-to-end pipeline that produces subtitle files from a live audio feed.

    Usage::

        pipeline = SubtitlePipeline(output_path="movie.srt")
        pipeline.start()
        # … feed audio data via pipeline.feed(pcm_bytes) …
        pipeline.stop()

    Parameters
    ----------
    output_path:
        Path of the output subtitle file.  The format is inferred from the
        file extension: ``.srt`` → SubRip, ``.vtt`` → WebVTT.
    model_size:
        Whisper model size (see :py:class:`WhisperTranscriber`).
    language:
        BCP-47 language code or *None* for auto-detection.
    device:
        PyTorch device string.
    on_segment:
        Optional callback invoked with each batch of new segments.
    """

    def __init__(
        self,
        output_path: str,
        model_size: str = "base",
        language: Optional[str] = None,
        device: str = "cpu",
        on_segment: Optional[SegmentCallback] = None,
    ) -> None:
        self._output_path = output_path
        self._on_segment = on_segment
        self._transcriber = WhisperTranscriber(
            model_size=model_size, language=language, device=device
        )
        self._audio_buffer = AudioBuffer(chunk_samples=CHUNK_SAMPLES)
        self._capture = BytesCapture(self._audio_buffer)
        self._all_segments: List[TranscriptSegment] = []
        self._time_offset: float = 0.0
        self._stop_event = threading.Event()
        self._worker: Optional[threading.Thread] = None

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def start(self) -> None:
        """Start the background transcription worker."""
        if self._worker is not None and self._worker.is_alive():
            return
        self._stop_event.clear()
        self._worker = threading.Thread(
            target=self._run_worker, name="subtitle-pipeline-worker", daemon=True
        )
        self._worker.start()
        logger.info("SubtitlePipeline started, writing to %r", self._output_path)

    def feed(self, pcm_bytes: bytes) -> None:
        """Push raw PCM audio into the pipeline.

        Parameters
        ----------
        pcm_bytes:
            Signed 16-bit little-endian PCM at 16 000 Hz mono.
        """
        self._capture.feed(pcm_bytes)

    def stop(self) -> None:
        """Flush remaining audio, finalize the subtitle file and stop the worker."""
        self._capture.flush()
        self._stop_event.set()
        if self._worker is not None:
            self._worker.join(timeout=60)
        self._write_subtitle_file()
        logger.info("SubtitlePipeline stopped.")

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    def _run_worker(self) -> None:
        """Worker loop: consume chunks and transcribe them."""
        while not self._stop_event.is_set():
            chunk = self._audio_buffer.get_chunk(timeout=0.5)
            if chunk is None:
                continue
            self._process_chunk(chunk)
        # Drain any remaining complete chunks after stop is requested.
        while True:
            chunk = self._audio_buffer.get_chunk(timeout=0.1)
            if chunk is None:
                break
            self._process_chunk(chunk)

    def _process_chunk(self, pcm_bytes: bytes) -> None:
        """Transcribe one chunk and update the subtitle file."""
        try:
            result = self._transcriber.transcribe(pcm_bytes, time_offset=self._time_offset)
        except Exception:
            logger.exception("Transcription failed for chunk at offset %.1fs", self._time_offset)
            return
        finally:
            chunk_duration = len(pcm_bytes) / 2 / 16000  # s16le @ 16 kHz
            self._time_offset += chunk_duration

        if result.segments:
            self._all_segments.extend(result.segments)
            if self._on_segment is not None:
                self._on_segment(result.segments)
            self._write_subtitle_file()

    def _write_subtitle_file(self) -> None:
        """Serialise all collected segments to the output file."""
        ext = os.path.splitext(self._output_path)[1].lower()
        if ext == ".vtt":
            content = segments_to_vtt(self._all_segments)
        else:
            content = segments_to_srt(self._all_segments)
        tmp_path = self._output_path + ".tmp"
        with open(tmp_path, "w", encoding="utf-8") as fh:
            fh.write(content)
        os.replace(tmp_path, self._output_path)

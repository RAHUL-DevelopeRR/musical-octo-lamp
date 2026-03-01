"""
Command-line interface for vlc-ai-subtitles.

Usage
-----
    vlc-ai-subtitles transcribe <media_file> [options]
    python -m vlc_ai_subtitles transcribe <media_file> [options]
"""

from __future__ import annotations

import argparse
import logging
import os
import sys
import tempfile


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="vlc-ai-subtitles",
        description="Generate offline AI subtitles for a media file using Whisper.",
    )
    sub = parser.add_subparsers(dest="command")

    # ---- transcribe -------------------------------------------------------
    t = sub.add_parser(
        "transcribe",
        help="Transcribe audio from a media file and write an SRT/VTT subtitle file.",
    )
    t.add_argument("media", metavar="MEDIA_FILE", help="Path to the input media file.")
    t.add_argument(
        "--output",
        "-o",
        default=None,
        metavar="OUTPUT_FILE",
        help=(
            "Path for the output subtitle file.  "
            "Format is inferred from the extension (.srt or .vtt).  "
            "Defaults to <media_basename>.srt in the same directory."
        ),
    )
    t.add_argument(
        "--model",
        "-m",
        default="base",
        choices=("tiny", "base", "small", "medium", "large"),
        help="Whisper model size (default: base).",
    )
    t.add_argument(
        "--language",
        "-l",
        default=None,
        metavar="LANG",
        help="BCP-47 language code (e.g. 'en', 'es').  Auto-detected when omitted.",
    )
    t.add_argument(
        "--device",
        default="cpu",
        metavar="DEVICE",
        help="PyTorch device string, e.g. 'cpu' or 'cuda' (default: cpu).",
    )
    t.add_argument(
        "--verbose",
        "-v",
        action="store_true",
        help="Enable verbose logging.",
    )

    return parser


def _extract_audio_to_wav(media_path: str, wav_path: str) -> None:
    """Use ffmpeg to extract and resample audio to 16 kHz mono WAV."""
    import subprocess

    cmd = [
        "ffmpeg",
        "-y",
        "-i", media_path,
        "-ar", "16000",
        "-ac", "1",
        "-f", "wav",
        wav_path,
    ]
    result = subprocess.run(cmd, capture_output=True)
    if result.returncode != 0:
        raise RuntimeError(
            f"ffmpeg failed (exit {result.returncode}):\n"
            + result.stderr.decode(errors="replace")
        )


def cmd_transcribe(args: argparse.Namespace) -> int:
    """Execute the ``transcribe`` sub-command."""
    from vlc_ai_subtitles.core.audio_capture import WavFileCapture, AudioBuffer
    from vlc_ai_subtitles.core.transcription import WhisperTranscriber
    from vlc_ai_subtitles.core.subtitle_formatter import segments_to_srt, segments_to_vtt

    media_path = os.path.abspath(args.media)
    if not os.path.isfile(media_path):
        print(f"Error: media file not found: {media_path}", file=sys.stderr)
        return 1

    # Determine output path
    if args.output:
        output_path = os.path.abspath(args.output)
    else:
        base = os.path.splitext(media_path)[0]
        output_path = base + ".srt"

    ext = os.path.splitext(output_path)[1].lower()
    if ext not in (".srt", ".vtt"):
        print(f"Error: unsupported output format {ext!r}. Use .srt or .vtt.", file=sys.stderr)
        return 1

    print(f"Input  : {media_path}")
    print(f"Output : {output_path}")
    print(f"Model  : {args.model}  |  Language: {args.language or 'auto'}  |  Device: {args.device}")

    transcriber = WhisperTranscriber(
        model_size=args.model, language=args.language, device=args.device
    )
    all_segments = []
    chunk_number = 0
    time_offset = 0.0

    # Extract audio to a temporary WAV file then process chunk by chunk.
    with tempfile.TemporaryDirectory() as tmp_dir:
        wav_path = os.path.join(tmp_dir, "audio.wav")
        print("Extracting audio with ffmpeg …")
        _extract_audio_to_wav(media_path, wav_path)

        buf = AudioBuffer()
        capture = WavFileCapture(wav_path, buf)

        print("Feeding audio into buffer …")
        capture.capture()

        print("Transcribing …")
        while True:
            chunk = buf.get_chunk(timeout=2.0)
            if chunk is None:
                break
            chunk_number += 1
            print(f"  Chunk {chunk_number} @ {time_offset:.1f}s …", end=" ", flush=True)
            result = transcriber.transcribe(chunk, time_offset=time_offset)
            time_offset += len(chunk) / 2 / 16000
            all_segments.extend(result.segments)
            print(f"{len(result.segments)} segment(s)")

    if not all_segments:
        print("No speech detected in the audio.", file=sys.stderr)
        return 0

    if ext == ".vtt":
        content = segments_to_vtt(all_segments)
    else:
        content = segments_to_srt(all_segments)

    with open(output_path, "w", encoding="utf-8") as fh:
        fh.write(content)

    print(f"Done. {len(all_segments)} subtitle segment(s) written to: {output_path}")
    return 0


def main(argv=None) -> int:
    parser = _build_parser()
    args = parser.parse_args(argv)

    if args.command is None:
        parser.print_help()
        return 0

    log_level = logging.DEBUG if getattr(args, "verbose", False) else logging.WARNING
    logging.basicConfig(level=log_level, format="%(levelname)s: %(message)s")

    if args.command == "transcribe":
        return cmd_transcribe(args)

    parser.print_help()
    return 1


if __name__ == "__main__":  # pragma: no cover
    sys.exit(main())

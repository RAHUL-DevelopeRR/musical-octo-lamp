# VLC AI Subtitles

> **Offline AI subtitle generation engine for VLC — powered by [OpenAI Whisper](https://github.com/openai/whisper)**

This project is a fork/extension of the VLC media player ecosystem that adds a
real-time, **fully offline** speech-to-text subtitle engine.  Audio from any
media file is captured, split into chunks, transcribed locally using the
Whisper model, and written to a standard SRT or WebVTT subtitle file — no
internet connection or cloud API key required.

---

## Features

| Feature | Details |
|---------|---------|
| 🔒 100 % offline | All inference runs on your machine via Whisper |
| 🎙 Auto language detection | Whisper detects the spoken language when not specified |
| 📝 SRT & WebVTT output | Standard subtitle formats compatible with VLC and browsers |
| 🔌 VLC Lua extension | One-click generation and loading inside VLC |
| ⚙️  CLI | Scriptable command-line interface for batch processing |
| 🐍 Python API | Import `vlc_ai_subtitles` directly in your own projects |

---

## Architecture

```
Media file
    │
    ▼
ffmpeg (audio extraction)
    │  16 kHz mono PCM
    ▼
AudioBuffer  ──chunk (30 s)──►  WhisperTranscriber
                                        │
                                        ▼
                               TranscriptSegment list
                                        │
                                        ▼
                              SubtitleFormatter  ──►  .srt / .vtt file
```

The `SubtitlePipeline` class orchestrates all of the above in a background
thread so that the caller can continue feeding audio in real time.

---

## Installation

### Prerequisites

* Python 3.9 or later
* [ffmpeg](https://ffmpeg.org/) on your `PATH` (required by the CLI for audio extraction)

### Install from source

```bash
git clone https://github.com/RAHUL-DevelopeRR/musical-octo-lamp.git
cd musical-octo-lamp
pip install .
```

For development (includes test dependencies):

```bash
pip install ".[dev]"
```

---

## Usage

### Command-line interface

```bash
# Basic usage — writes movie.srt next to the input file
vlc-ai-subtitles transcribe movie.mkv

# Specify output path and language
vlc-ai-subtitles transcribe movie.mkv --output movie.vtt --language en

# Use a larger model for better accuracy
vlc-ai-subtitles transcribe movie.mkv --model medium

# GPU acceleration
vlc-ai-subtitles transcribe movie.mkv --device cuda
```

Available options:

```
usage: vlc-ai-subtitles transcribe [-h] [--output OUTPUT_FILE]
                                    [--model {tiny,base,small,medium,large}]
                                    [--language LANG] [--device DEVICE]
                                    [--verbose]
                                    MEDIA_FILE
```

### VLC Lua extension

1. Copy `vlc_ai_subtitles/vlc_ext/vlc_ai_subtitles.lua` to the VLC extensions directory:
   - **Linux / macOS**: `~/.local/share/vlc/lua/extensions/`
   - **Windows**: `%APPDATA%\vlc\lua\extensions\`
2. Restart VLC.
3. Open **View → VLC AI Subtitles**.
4. Click **Generate Subtitles** — the extension invokes the CLI in the background.
5. Once transcription is complete, click **Load Subtitles**.

### Python API

```python
from vlc_ai_subtitles.core.pipeline import SubtitlePipeline

pipeline = SubtitlePipeline(
    output_path="output.srt",
    model_size="base",
    language="en",          # or None for auto-detect
    device="cpu",
    on_segment=lambda segs: print(f"New segments: {segs}"),
)

pipeline.start()

# Feed raw 16 kHz mono signed-16-bit PCM audio bytes
pipeline.feed(pcm_bytes)

# Flush and finalize the subtitle file
pipeline.stop()
```

---

## Model sizes

| Model  | Parameters | English-only | Multilingual | VRAM  | Speed (relative) |
|--------|-----------|--------------|--------------|-------|-----------------|
| tiny   | 39 M      | ✓            | ✓            | ~1 GB | ~32×             |
| base   | 74 M      | ✓            | ✓            | ~1 GB | ~16×             |
| small  | 244 M     | ✓            | ✓            | ~2 GB | ~6×              |
| medium | 769 M     | ✓            | ✓            | ~5 GB | ~2×              |
| large  | 1550 M    | ✗            | ✓            | ~10 GB| 1×               |

---

## Development

### Running tests

```bash
pytest
```

### Project layout

```
musical-octo-lamp/
├── vlc_ai_subtitles/
│   ├── __init__.py
│   ├── __main__.py          # CLI entry-point
│   ├── core/
│   │   ├── audio_capture.py     # AudioBuffer, WavFileCapture, BytesCapture
│   │   ├── transcription.py     # WhisperTranscriber
│   │   ├── subtitle_formatter.py # SRT / VTT formatters
│   │   └── pipeline.py          # SubtitlePipeline (end-to-end orchestration)
│   ├── vlc_ext/
│   │   └── vlc_ai_subtitles.lua # VLC Lua extension
│   └── tests/
│       ├── test_audio_capture.py
│       ├── test_subtitle_formatter.py
│       ├── test_transcription.py
│       └── test_pipeline.py
├── pyproject.toml
├── requirements.txt
└── README.md
```

---

## License

GPL-2.0-or-later — same as VLC itself.

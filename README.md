# musical-octo-lamp — LuminaPlayer

A **JavaFX desktop media player** with VLC codec support and **offline AI subtitle generation** powered by a precision 9-stage Python sidecar (Faster-Whisper + Silero VAD + YAMNet).

---

## ✨ Features

| Feature | Status |
|---|---|
| Full media playback (video/audio/subtitles) via libVLC | ✅ |
| AI-powered offline subtitle generation | ✅ |
| Speech transcription (Faster-Whisper, INT8) | ✅ |
| VAD — silence skipped, zero hallucinations (Silero) | ✅ |
| Sound event captions — 30+ events (YAMNet SED) | ✅ optional |
| End-time waveform correction | ✅ |
| Drift detection & correction | ✅ |
| Forced alignment refinement (±50ms, 2ms precision) | ✅ |
| Confidence gate + auto-retry (beam=5) | ✅ |
| Result caching — instant re-runs | ✅ |
| CPU throttle (`max_cpu_percent`) | ✅ optional |
| Network streaming (HTTP, RTSP, RTMP, HLS) | ✅ |
| Playlist management (shuffle, repeat) | ✅ |
| Dark theme UI + keyboard shortcuts | ✅ |
| 100% offline — no internet required | ✅ |

---

## 🏗️ Architecture

```
┌─────────────────────────────────────────────┐
│          LuminaPlayer  (JavaFX + libVLC)     │
│                                             │
│  ChunkedSubtitleGenerator.java              │
│    Phase 1: Instant 10s micro-chunk         │
│    Phase 2: Parallel full chunks            │
│    Phase 3: Confidence-based model upgrade  │
│                                             │
│  WhisperEngine.java  ──► whisper_server.py  │
│          ▲  JSON-lines stdin/stdout         │
└──────────┼──────────────────────────────────┘
           │
┌──────────▼──────────────────────────────────┐
│       whisper_server.py  (Python sidecar)   │
│  Stage 1  Audio I/O       soundfile/numpy   │
│  Stage 2  Silero VAD      torch             │
│  Stage 3  Faster-Whisper  INT8, per-region  │
│  Stage 4  End-time fix    waveform RMS      │
│  Stage 5  Drift correct   linear regression │
│  Stage 6  Alignment refine ±50ms energy     │
│  Stage 7  YAMNet SED      TFLite (optional) │
│  Stage 8  Validation      overlap/gap fix   │
│  Cache    LRU 256 entries  <1s re-runs      │
└─────────────────────────────────────────────┘
```

---

## ⚡ Performance

| Scenario | Before | After |
|---|---|---|
| 60s chunk — first run | ~4 min | **~25–40s** |
| 60s chunk — re-run (cache) | ~4 min | **< 1s** |
| Silence hallucinations | Frequent | **Zero** (VAD-gated) |
| Subtitle accuracy | Low | **High** (drift+alignment corrected) |

---

## 🚀 Quick Start

### Prerequisites
- Java 17+
- Maven 3.x
- Python 3.9+
- FFmpeg (add to PATH)
- VLC 3.0+ installed (or bundled native libs)

### 1. Clone
```bash
git clone --recurse-submodules https://github.com/RAHUL-DevelopeRR/musical-octo-lamp.git
cd musical-octo-lamp
```

### 2. Install Python sidecar

**Minimal** (speech only, fastest install ~500 MB):
```bash
pip install faster-whisper soundfile numpy
```

**Recommended** (speech + Silero VAD — eliminates silence hallucinations):
```bash
pip install -r lumina-player/scripts/requirements.txt
```

**Full** (speech + VAD + YAMNet sound events):
```bash
# Uncomment tensorflow lines in requirements.txt first, then:
pip install -r lumina-player/scripts/requirements.txt
```

### 3. Build & run LuminaPlayer
```bash
cd lumina-player
mvn clean package
mvn javafx:run
```

### 4. Generate subtitles
Open a media file → **Subtitles ▶ Generate Subtitles (AI)** or press `Ctrl+Shift+G`.

---

## 🐍 Python Sidecar — whisper_server.py

The sidecar runs as a persistent process communicating via **JSON-lines on stdin/stdout**.
Java launches it once and reuses it across all chunks — the model stays in RAM.

### Manual test
```bash
python lumina-player/scripts/whisper_server.py
# Paste a request:
{"wav_path": "/tmp/chunk.wav", "model": "small", "quality": "BALANCED", "use_vad": true}
```

### Request format
```json
{
  "wav_path":         "/tmp/lumina/chunk.wav",
  "model":            "small",
  "language":         "en",
  "quality":          "BALANCED",
  "translate":        false,
  "use_vad":          true,
  "sound_events":     false,
  "max_cpu_percent":  75,
  "cache_key":        "sha256hex"
}
```

### Response format
```json
{
  "segments": [
    {"start": 1.24, "end": 3.80, "text": "Hello world.", "type": "speech", "confidence": -0.21},
    {"start": 3.90, "end": 4.50, "text": "(Applause)",   "type": "sound_event", "confidence": 0.87}
  ],
  "language":       "en",
  "from_cache":     false,
  "alignment_info": {
    "overall_confidence": 94.2,
    "drift_ms":           1.2,
    "refined":            12,
    "overlaps_fixed":     0,
    "passed":             true
  },
  "error": null
}
```

### Quality presets
| Preset | Beam | Use case |
|---|---|---|
| `INSTANT` | 1 | Live preview, fastest |
| `FAST` | 1 | Quick generation |
| `BALANCED` | 3 | Default — speed/accuracy balance |
| `BEST` | 5 | Maximum accuracy |

---

## 🐳 Docker

### Whisper sidecar only
```bash
# Build
docker build --target whisper-sidecar -t lumina-whisper-sidecar .

# Run (interactive stdin/stdout JSON-lines protocol)
docker run -it --rm -v "$(pwd)/.models:/models" lumina-whisper-sidecar
```

### Full VLC streaming engine
```bash
docker build -t vlc-streaming-engine .
docker run --rm vlc-streaming-engine --version
docker run --rm -p 8080:8080 -v /path/to/media:/media vlc-streaming-engine \
  /media/input.mp4 --sout '#std{access=http,mux=ts,dst=:8080}'
```

---

## ⌨️ Keyboard Shortcuts

| Key | Action |
|---|---|
| `Space` | Play / Pause |
| `F11` / `F` | Toggle fullscreen |
| `M` | Toggle mute |
| `Right` / `Left` | Skip ±10 seconds |
| `Up` / `Down` | Volume ±5% |
| `N` / `P` | Next / Previous track |
| `E` | Next frame |
| `Ctrl+O` | Open file |
| `Ctrl+N` | Open network stream |
| `Ctrl+Shift+G` | Generate subtitles (AI) |
| `Ctrl+I` | Media information |
| `Ctrl+Q` | Exit |

---

## 🔧 Native Library Bundling (Windows)

```powershell
.\lumina-player\scripts\download-vlc-libs.ps1
```
Downloads VLC 3.0.x Windows 64-bit libraries to `native/win-x64/`.

---

## 🏗️ VLC Streaming Engine (Linux/Docker)

### Prerequisites
- Ubuntu 24.04
- GCC 13+ or Clang 15+
- `autoconf automake libtool pkg-config flex bison gettext`
- FFmpeg dev libs: `libavcodec libavutil libavformat libswscale`

### Build
```bash
./build.sh          # recommended (handles all deps)
./build.sh --deps-only   # install deps only
./build.sh --no-deps     # skip dep install
MAKE_JOBS=4 ./build.sh  # control parallelism
```

### Run
```bash
./run.sh -I dummy input.mp4
./run.sh -I dummy http://example.com/stream.ts
./run.sh -I dummy input.mp4 --sout '#std{access=http,mux=ts,dst=:8080}'
```

---

## 📦 Dependencies Summary

| Layer | Technology |
|---|---|
| Media playback | libVLC 3.0+ |
| UI | JavaFX 21 |
| ASR | Faster-Whisper (CTranslate2, INT8) |
| VAD | Silero VAD (PyTorch) |
| Sound events | YAMNet (TensorFlow, optional) |
| Audio I/O | soundfile + numpy |
| CPU throttle | psutil (optional) |
| Container | Docker (multi-stage) |

---

## CI

GitHub Actions (`.github/workflows/build.yml`) builds VLC automatically on every push and pull request to `main`.

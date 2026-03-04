# LuminaPlayer — Project Summary

> **An Offline-First, AI-Powered Desktop Media Player with Real-Time Chunked Subtitle Generation, Multi-Model Translation Orchestration, and a Premium Glassmorphism UI**

---

## Table of Contents

1. [Overview](#1-overview)
2. [Key Features at a Glance](#2-key-features-at-a-glance)
3. [Architecture — The 30,000-Foot View](#3-architecture--the-30000-foot-view)
4. [Parallel & Multi-Threaded Design](#4-parallel--multi-threaded-design)
5. [The Chunked Subtitle Pipeline — Engineering Deep-Dive](#5-the-chunked-subtitle-pipeline--engineering-deep-dive)
6. [Multi-Model Translation Orchestration](#6-multi-model-translation-orchestration)
7. [The `.dsrt` Format — A New Standard for Dynamic Subtitles](#7-the-dsrt-format--a-new-standard-for-dynamic-subtitles)
8. [Premium Glassmorphism UI](#8-premium-glassmorphism-ui)
9. [VLC Engine Integration — The Facade Layer](#9-vlc-engine-integration--the-facade-layer)
10. [Design Patterns Employed](#10-design-patterns-employed)
11. [Technology Stack](#11-technology-stack)
12. [Package Structure](#12-package-structure)
13. [Build & Run](#13-build--run)

---

## 1. Overview

**LuminaPlayer** is a desktop media player built with **JavaFX 21** and **libVLC** (via vlcj 4.8.3) that goes far beyond basic playback. Its headline capability is **offline AI-powered subtitle generation** — it can take any video in any of 40+ languages, transcribe it using **whisper.cpp**, translate it via an offline Neural Machine Translation engine, and deliver the first 30 seconds of subtitles to the user in approximately **3 seconds** — while the rest of the video continues processing in the background.

The project lives inside the `musical-octo-lamp` monorepo, which also houses a headless VLC streaming engine for Linux/Docker deployments.

| Property | Value |
|---|---|
| **Language** | Java 17 |
| **UI Framework** | JavaFX 21.0.5 |
| **Media Engine** | libVLC via vlcj 4.8.3 |
| **STT Engine** | whisper.cpp (offline, local) |
| **Translation** | Argos Translate (offline NMT), LibreTranslate, Ollama |
| **Source Files** | 59 Java classes |
| **CSS** | 611 lines — premium glassmorphism theme |

---

## 2. Key Features at a Glance

### Playback
- Full video and audio playback powered by VLC (supports virtually every codec)
- Variable playback speed (0.25x – 4.0x)
- Frame-by-frame stepping
- Loop and shuffle modes (Fisher-Yates shuffle algorithm)
- Network stream support (HTTP, HTTPS, RTSP, RTMP, MMS, UDP)
- Playlist management with drag-and-drop
- Fullscreen with auto-hiding controls (3-second inactivity timer)
- Keyboard-driven: 20+ shortcuts covering every operation

### AI Subtitle Generation
- **Offline-first**: No internet required — whisper.cpp + Argos Translate run entirely on-device
- **Instant first results**: Priority-chunk processing delivers subtitles in ~3 seconds
- **Chunked parallel pipeline**: Splits media into configurable chunks (15–120s), processes in parallel across 2–4 worker threads
- **40+ languages** with native-script decoder prompts for accuracy
- **5 Whisper model sizes**: Tiny (75 MB) → Large (2.9 GB), auto-downloaded from HuggingFace
- **3 quality presets**: Fast, Balanced, Best — controlling beam search width and best-of count
- **Custom `.dsrt` format**: JSON-based dynamic subtitle file with progressive loading
- **Export to `.srt`**: Standard subtitle format export for compatibility

### Multi-Model Translation Orchestration
- **3 translation backends**: Argos Translate (offline), LibreTranslate (API), Ollama (local LLM)
- **3 orchestration modes**: Single-Pass, Multi-Model, Multi-Model + Verification
- **Batch translation** with configurable batch sizes and character-limit-aware splitting
- **Optional parallel translation** across up to 3 concurrent batch workers
- **Verification pass**: A secondary model reviews and corrects translations

### UI/UX
- Premium dark glassmorphism theme with cyan (#00d4ff) accent glow
- Real-time chunk-progress grid with color-coded status indicators
- Subtitle overlay with O(log n) cue lookup, styled with drop shadow and rounded corners
- 100ms subtitle synchronization polling

---

## 3. Architecture — The 30,000-Foot View

```
┌──────────────────────────────────────────────────────────────────┐
│                       JavaFX Application Thread                  │
│  ┌────────────┐  ┌────────────┐  ┌────────────┐  ┌───────────┐  │
│  │ MainWindow │  │ ControlBar │  │  SeekBar   │  │ Playlist  │  │
│  │ (Composite)│  │  (Command) │  │ (Drag-jit) │  │  Panel    │  │
│  └─────┬──────┘  └─────┬──────┘  └─────┬──────┘  └─────┬─────┘  │
│        │               │               │               │         │
│  ┌─────▼───────────────▼───────────────▼───────────────▼─────┐   │
│  │              PlayerController (MEDIATOR)                   │   │
│  │   Observable Properties: state, time, volume, rate, ...   │   │
│  └────────────────────────┬──────────────────────────────────┘   │
│                           │                                      │
│  ┌────────────────────────▼──────────────────────────────────┐   │
│  │           PlayerEventBridge (ADAPTER / BRIDGE)            │   │
│  │      vlcj native thread ──► Platform.runLater() ──► FX   │   │
│  └────────────────────────┬──────────────────────────────────┘   │
└───────────────────────────┼──────────────────────────────────────┘
                            │
┌───────────────────────────▼──────────────────────────────────────┐
│                     VlcEngine (FACADE)                           │
│  ┌──────────────┐  ┌────────────────┐  ┌──────────────────┐     │
│  │ AudioManager │  │ SubtitleManager│  │  VideoManager    │     │
│  └──────────────┘  └────────────────┘  └──────────────────┘     │
│                     EmbeddedMediaPlayer                          │
│                     MediaPlayerFactory                           │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│                AI Subtitle Generation Pipeline                   │
│                                                                  │
│  ┌────────┐    ┌──────────────┐    ┌───────────────┐            │
│  │ FFmpeg │───►│ whisper.cpp  │───►│  DsrtFile     │            │
│  │ (Audio │    │ (STT Engine) │    │ (ConcurrentMap│            │
│  │  Extract)   │              │    │  + Atomic I/O)│            │
│  └────────┘    └──────────────┘    └───────┬───────┘            │
│                                            │                     │
│  ┌─────────────────────────────────────────▼───────────────────┐ │
│  │         ChunkedSubtitleGenerator (PRODUCER-CONSUMER)        │ │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐      │ │
│  │  │ Worker-1 │ │ Worker-2 │ │ Worker-3 │ │ Worker-4 │      │ │
│  │  │ (Chunk 0)│ │ (Chunk 2)│ │ (Chunk 3)│ │ (Chunk 4)│      │ │
│  │  └──────────┘ └──────────┘ └──────────┘ └──────────┘      │ │
│  │                CompletableFuture.allOf()                    │ │
│  └─────────────────────────────────────────────────────────────┘ │
│                                                                  │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │      MultiModelOrchestrator (STRATEGY + PIPELINE)           │ │
│  │  ┌────────────────┐  ┌──────────────────┐  ┌────────────┐  │ │
│  │  │ ArgosTranslate │  │ LibreTranslate   │  │   Ollama   │  │ │
│  │  │  (Offline NMT) │  │    (REST API)    │  │ (Local LLM)│  │ │
│  │  └────────────────┘  └──────────────────┘  └────────────┘  │ │
│  └─────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────┘
```

---

## 4. Parallel & Multi-Threaded Design

LuminaPlayer's concurrency architecture is one of its most sophisticated aspects. The application coordinates **five distinct threading domains** that work in concert.

### 4.1 Thread Model Overview

| Thread Domain | Mechanism | Purpose |
|---|---|---|
| **JavaFX Application Thread** | Single UI thread | All UI rendering, property bindings, scene graph mutations |
| **vlcj Native Thread** | JNA callback thread | Media events from libVLC (playing, paused, time changed, etc.) |
| **Chunk Worker Pool** | `ExecutorService` (2–4 daemon threads) | Parallel audio extraction + transcription per chunk |
| **Translation Workers** | `ExecutorService` (up to 3 threads) | Parallel batch translation across providers |
| **External Processes** | `ProcessBuilder` | FFmpeg, whisper.cpp CLI, Python (Argos Translate) |

### 4.2 Thread Safety Mechanisms

```
ConcurrentSkipListMap<Long, DsrtCue>     — O(log n) thread-safe cue storage
CopyOnWriteArrayList<DsrtChunk>          — Lock-free read-heavy chunk list
CopyOnWriteArrayList<Future<?>>          — Safe future tracking across workers
AtomicInteger                            — Lock-free cue ID generation & completion counting
volatile boolean                         — Cancel flags, chunk status fields
synchronized                             — DsrtFile.saveTo() for atomic file writes
Platform.runLater()                      — Native → FX thread marshalling
CompletableFuture.allOf()                — Non-blocking join on all background chunks
```

### 4.3 The Bridge Pattern — vlcj → JavaFX

The `PlayerEventBridge` solves a fundamental challenge: vlcj fires events on a **native JNA callback thread**, but JavaFX properties can only be modified on the **FX Application Thread**. The bridge extends `MediaPlayerEventAdapter` and wraps every callback in `Platform.runLater()`:

```
vlcj native thread                    JavaFX FX thread
    │                                      │
    │  timeChanged(ms)                     │
    │────────────────────►                 │
    │  Platform.runLater(() -> {           │
    │     currentTime.set(ms);  ◄──────────│
    │     position.set(pos);               │
    │  })                                  │
```

Time updates are throttled to **50ms intervals** to avoid flooding the FX event queue — a detail that prevents UI stutter during playback.

### 4.4 Worker Thread Pool Design

The chunk generation pool is sized adaptively:

```java
int threads = Math.min(4, Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
ExecutorService pool = Executors.newFixedThreadPool(threads, daemonThreadFactory("chunk-worker"));
```

All worker threads are **daemon threads**, ensuring the JVM can exit cleanly even if workers are still running. Futures are tracked in a `CopyOnWriteArrayList` for safe cancellation from the FX thread.

### 4.5 Resource Cleanup — LIFO Order

`ResourceCleaner` (Singleton) tracks all `AutoCloseable` resources in a LIFO `Deque`, ensuring that the vlcj `MediaPlayerFactory` is released **after** the `EmbeddedMediaPlayer` it created — preventing native crashes on shutdown.

---

## 5. The Chunked Subtitle Pipeline — Engineering Deep-Dive

This is the crown jewel of LuminaPlayer's engineering. The pipeline takes a video file and produces subtitles in a **two-phase approach** that delivers near-instant results while maintaining quality.

### 5.1 Two-Phase Priority Architecture

```
Phase 1 (SYNCHRONOUS — ~3 seconds)
══════════════════════════════════
  ┌──────────┐     ┌──────────────┐     ┌──────────┐
  │  FFmpeg   │────►│ whisper.cpp  │────►│ DsrtFile │──► firstChunkReadyCallback()
  │ extract   │     │ TINY model   │     │ addCues  │    ──► Subtitles appear!
  │ 30s chunk │     │ FAST quality │     │          │
  └──────────┘     └──────────────┘     └──────────┘
       │
       │  Priority chunk = chunk containing current playback position
       │  Uses TINY model (75MB) + FAST quality = ~3 second processing time
       │

Phase 2 (ASYNCHRONOUS — background)
════════════════════════════════════
  ┌──────────────────────────────────────────────────────────┐
  │               CompletableFuture.allOf()                  │
  │                                                          │
  │  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐      │
  │  │ Chunk 1 │ │ Chunk 2 │ │ Chunk 3 │ │ Chunk N │      │
  │  │ (fwd)   │ │ (fwd)   │ │ (fwd)   │ │ (bkwd)  │      │
  │  └─────────┘ └─────────┘ └─────────┘ └─────────┘      │
  │                                                          │
  │  Uses USER-SELECTED model + quality                      │
  │  Priority chunk re-queued last for quality upgrade       │
  │                                                          │
  │  onAllComplete() ──► Final .dsrt file saved              │
  └──────────────────────────────────────────────────────────┘
```

### 5.2 Chunk Ordering Strategy

Chunks are not processed sequentially. They follow a **playback-aware priority order**:

1. **Priority chunk** (containing current playback position) — processed first, synchronously
2. **Forward chunks** (from priority + 1 to end) — most likely to be needed next
3. **Backward chunks** (from priority - 1 to 0) — least likely but still processed
4. **Priority chunk re-queued** — upgraded from TINY/FAST to user's chosen model/quality

This ensures the user sees subtitles for what they're *currently watching* almost instantly, while background processing handles the rest in the order most likely to be needed.

### 5.3 FFmpeg Integration — Smart Seeking

Audio extraction uses FFmpeg's **input seeking** (`-ss` before `-i`) for fast keyframe-based seeking, rather than the slower output seeking (`-ss` after `-i`):

```
ffmpeg -ss <start> -i <input> -t <duration> -vn -acodec pcm_s16le -ar 16000 -ac 1 -y <output.wav>
```

This avoids decoding the entire file up to the seek point, making chunk extraction nearly instantaneous regardless of chunk position.

### 5.4 Whisper Configuration Intelligence

The engine auto-configures based on context:
- **Non-English source + translation requested** → auto-upgrades to MEDIUM+ model, BEST quality, 60s chunks
- **Priority chunk** → always uses TINY + FAST for speed
- **Native-script prompts** → primes the Whisper decoder with target-language text (e.g., Japanese: `「こんにちは、世界。」`), dramatically improving non-Latin script accuracy
- **Thread allocation** → `availableProcessors() - 1` threads for Whisper, leaving one core for the UI

---

## 6. Multi-Model Translation Orchestration

The orchestration system implements a pluggable **Strategy pattern** with three provider implementations and three pipeline modes.

### 6.1 Translation Providers

| Provider | Type | Latency | Offline | Max Chars |
|---|---|---|---|---|
| **ArgosTranslateProvider** | CTranslate2 + OpenNMT | ~50-200ms/sentence | Yes | 10,000 |
| **LibreTranslateProvider** | REST API (Docker/hosted) | ~100-500ms/sentence | Self-hosted | 5,000 |
| **OllamaTranslationProvider** | Local LLM (llama3, etc.) | ~500ms-2s/sentence | Yes | 3,000 |

### 6.2 Argos Translate — The Offline Champion

Argos Translate is the default and recommended provider. It runs **entirely offline** using CTranslate2 inference with OpenNMT-trained models (~50MB per language pair).

Implementation shells out to Python:
- **Single translation**: `python -c "from argostranslate.translate import ..."`
- **Batch translation**: Generates a temp Python script, executes it, reads results — avoiding per-sentence process overhead
- **Language pack management**: `installLanguagePack()` downloads and installs language models via `argospm`

### 6.3 Pipeline Modes

```
SINGLE_PASS:       Whisper ──► .dsrt
                   (No translation — transcription only)

MULTI_MODEL:       Whisper ──► BatchTranslate ──► .dsrt
                   (Transcribe in source language, then translate to target)

MULTI_MODEL_VERIFIED: Whisper ──► BatchTranslate ──► Verify ──► .dsrt
                      (Same as above, plus a second model reviews each translation)
```

### 6.4 Intelligent Batching

The orchestrator builds translation batches respecting both **count limits** (`translationBatchSize`, default 10) and **character limits** (`maxCharsPerRequest` per provider):

```
Batch Building Algorithm:
  for each subtitle entry:
    if batch.size >= batchSize OR batch.chars + entry.chars > maxChars:
      flush current batch
      start new batch
    add entry to batch
```

When parallel translation is enabled, batches are distributed across `min(3, batchCount)` worker threads using an `ExecutorService` with 120-second timeout per batch.

---

## 7. The `.dsrt` Format — A New Standard for Dynamic Subtitles

Standard `.srt` files are static — they're generated once and loaded in full. LuminaPlayer introduces **`.dsrt` (Dynamic SRT)**, a JSON-based format designed for **progressive subtitle generation**.

### 7.1 Format Structure

```json
{
  "version": "1.0",
  "mediaFile": "video.mp4",
  "totalDuration": 180000,
  "chunkDuration": 30000,
  "sourceLanguage": "ja",
  "targetLanguage": "en",
  "translated": true,
  "chunks": [
    { "index": 0, "startMs": 0, "endMs": 30000, "status": "COMPLETED" },
    { "index": 1, "startMs": 30000, "endMs": 60000, "status": "TRANSCRIBING" },
    { "index": 2, "startMs": 60000, "endMs": 90000, "status": "PENDING" }
  ],
  "cues": [
    { "id": 1, "startTimeMs": 1200, "endTimeMs": 3400, "text": "Hello, world.", "chunkIndex": 0 },
    { "id": 2, "startTimeMs": 3800, "endTimeMs": 6100, "text": "How are you?", "chunkIndex": 0 }
  ]
}
```

### 7.2 Thread-Safe Data Structure

`DsrtFile` is the in-memory representation, engineered for concurrent access:

| Field | Type | Why |
|---|---|---|
| Cue storage | `ConcurrentSkipListMap<Long, DsrtCue>` | O(log n) lookup by timestamp, lock-free reads |
| Chunk list | `CopyOnWriteArrayList<DsrtChunk>` | Lock-free iteration during UI updates |
| Cue ID counter | `AtomicInteger` | Lock-free unique ID generation |
| File persistence | `synchronized saveTo()` | Atomic temp-file + rename strategy |

### 7.3 Real-Time Subtitle Display

The `SubtitleOverlay` polls `DsrtFile.getActiveCue(currentMs)` every **100 milliseconds** using a JavaFX `Timeline`. The lookup uses `ConcurrentSkipListMap.floorEntry(timestamp)` — an **O(log n)** binary search — to find the currently active cue without iterating the full cue list.

---

## 8. Premium Glassmorphism UI

The UI theme is implemented in **611 lines of CSS**, achieving a modern, premium aesthetic:

### 8.1 Design Language

| Element | Style |
|---|---|
| **Base palette** | Deep navy (`#1a1a2e`) background, near-black (`#0f0f1a`) base |
| **Accent color** | Cyan (`#00d4ff`) — used for hover states, active indicators, glow effects |
| **Glass effect** | Semi-transparent backgrounds (`rgba(255,255,255,0.06)`) with subtle borders |
| **Typography** | "Segoe UI" / "Inter" / "Roboto" for UI; "Consolas" / "JetBrains Mono" for timestamps |
| **Animations** | Scale transforms on press (`0.95`), glow transitions on hover |
| **Shadows** | Gaussian drop shadows with cyan tint on interactive elements |

### 8.2 Notable UI Elements

- **Play/Pause button**: 48×48px circular with gradient background, cyan glow halo on hover
- **Seek slider**: Expandable track (4px → 8px on hover), glowing circular thumb
- **Volume slider**: Transparent background, matching glow effects
- **Chunk progress grid**: 16×16px colored squares (gray/orange/cyan/green/red) with tooltips
- **Subtitle overlay**: 24px bold white text on 75%-opacity black background, rounded corners, drop shadow
- **Orchestration badge**: Gradient background with cyan-to-purple accent
- **Scrollbars**: Thin (8px), rounded, semi-transparent — only visible on hover

---

## 9. VLC Engine Integration — The Facade Layer

LuminaPlayer wraps the complex vlcj API behind a clean **Facade pattern**:

```
                    ┌──────────────────────┐
                    │      VlcEngine       │
                    │   (Master Facade)    │
                    │                      │
                    │  factory()           │
                    │  mediaPlayer()       │
                    │  audio() ──────────────────► AudioManager
                    │  subtitles() ──────────────► SubtitleManager
                    │  video() ──────────────────► VideoManager
                    │  close()             │
                    └──────────────────────┘
```

Each sub-manager provides a **focused, simplified API**:

- **AudioManager**: Volume (0–200), mute toggle, track selection, audio delay
- **SubtitleManager**: External subtitle loading, track selection, delay, disable
- **VideoManager**: Aspect ratio, scale, snapshot, dimension queries

VLC is configured with `--no-video-title-show`, `--quiet`, `--no-metadata-network-access` for a clean, private playback experience.

---

## 10. Design Patterns Employed

| Pattern | Implementation | Purpose |
|---|---|---|
| **Mediator** | `PlayerController` — central hub between 10+ UI components and VLC engine | Decouples UI from media engine; single source of truth for playback state |
| **Facade** | `VlcEngine` + `AudioManager` / `SubtitleManager` / `VideoManager` | Simplifies 100+ vlcj API methods into focused sub-APIs |
| **Observer** | JavaFX observable properties + bidirectional bindings throughout UI | Reactive UI updates without explicit refresh calls |
| **Adapter / Bridge** | `PlayerEventBridge` — adapts vlcj native callbacks to FX properties | Cross-thread event translation |
| **Strategy** | `TranslationProvider` interface with 3 implementations | Swappable translation backends at runtime |
| **Factory Method** | `DsrtFile.create()`, `DsrtFile.loadFrom()` | Encapsulated object creation with validation |
| **Singleton** | `ResourceCleaner` — LIFO resource tracker | Single point of lifecycle management |
| **Command** | Keyboard shortcuts, menu actions | Encapsulated user actions |
| **Template Method** | JavaFX `Task` subclasses (`call()`, `succeeded()`, `failed()`) | Structured background task lifecycle |
| **Producer-Consumer** | `ChunkedSubtitleGenerator` — thread pool processes prioritized chunk queue | Parallel workload distribution |
| **Composite** | `MainWindow` — assembles sub-components into scene graph | Hierarchical UI construction |

---

## 11. Technology Stack

| Layer | Technology | Version |
|---|---|---|
| **Language** | Java | 17 |
| **UI Framework** | JavaFX | 21.0.5 |
| **Media Engine** | vlcj (libVLC bindings) | 4.8.3 |
| **Video Surface** | vlcj-javafx (ImageView bridge) | 1.2.0 |
| **Speech-to-Text** | whisper.cpp (CLI) | Local binary |
| **Audio Processing** | FFmpeg (CLI) | System-installed |
| **Offline Translation** | Argos Translate (CTranslate2 + OpenNMT) | Python package |
| **API Translation** | LibreTranslate | Docker/hosted |
| **LLM Translation** | Ollama (llama3, mistral, qwen2) | Local server |
| **Logging** | SLF4J + Logback | 2.0.16 / 1.5.15 |
| **Build Tool** | Maven | 3.9.12 |
| **Testing** | JUnit Jupiter | 5.11.4 |

---

## 12. Package Structure

```
com.luminaplayer/
│
├── app/                           # Application bootstrap & configuration
│   ├── LuminaPlayerApp.java       # JavaFX Application entry point
│   ├── AppConfig.java             # Application-wide constants
│   └── NativeLibrarySetup.java    # JNA native library path configuration
│
├── engine/                        # VLC engine facade layer
│   ├── VlcEngine.java             # Master facade — owns native VLC lifecycle
│   ├── AudioManager.java          # Audio operations facade
│   ├── SubtitleManager.java       # Subtitle operations facade
│   └── VideoManager.java          # Video operations facade
│
├── player/                        # Playback control & event bridging
│   ├── PlayerController.java      # Central mediator — UI ↔ engine
│   ├── PlayerEventBridge.java     # vlcj native thread → FX thread adapter
│   ├── PlaybackState.java         # State enum (IDLE, PLAYING, PAUSED, ...)
│   └── MediaInfo.java             # Media metadata POJO
│
├── playlist/                      # Playlist management
│   ├── Playlist.java              # ObservableList-backed playlist
│   ├── PlaylistController.java    # Navigation, shuffle (Fisher-Yates), repeat
│   ├── PlaylistItem.java          # Single playlist entry
│   └── RepeatMode.java            # NONE / ONE / ALL
│
├── subtitle/                      # Dynamic subtitle format (.dsrt)
│   ├── DsrtFile.java              # Thread-safe in-memory .dsrt representation
│   ├── DsrtChunk.java             # Chunk metadata with volatile status
│   ├── DsrtCue.java               # Immutable subtitle cue record
│   ├── SubtitleEntry.java         # Parsed SRT entry POJO
│   ├── SrtParser.java             # Standard .srt file parser
│   └── ChunkStatus.java           # PENDING / EXTRACTING / TRANSCRIBING / COMPLETED / FAILED
│
├── ai/                            # AI subtitle generation pipeline
│   ├── ChunkedSubtitleGenerator.java   # Parallel chunked pipeline engine
│   ├── ChunkedTranscriptionTask.java   # JavaFX Task wrapper for chunked gen
│   ├── WhisperEngine.java              # whisper.cpp CLI wrapper
│   ├── AudioExtractor.java             # FFmpeg audio extraction
│   ├── SubtitleGenerator.java          # Legacy single-pass generator
│   ├── TranscriptionTask.java          # JavaFX Task for single-pass gen
│   ├── ModelDownloadTask.java          # HuggingFace model downloader
│   ├── ChunkProgressEvent.java         # Immutable progress event record
│   ├── WhisperModel.java               # TINY / BASE / SMALL / MEDIUM / LARGE
│   ├── WhisperLanguage.java            # 40+ languages with native prompts
│   ├── WhisperQuality.java             # FAST / BALANCED / BEST presets
│   │
│   └── orchestration/                  # Multi-model translation pipeline
│       ├── TranslationProvider.java    # Strategy interface
│       ├── ArgosTranslateProvider.java # Offline NMT (CTranslate2 + OpenNMT)
│       ├── LibreTranslateProvider.java # REST API translation
│       ├── OllamaTranslationProvider.java # Local LLM translation
│       ├── MultiModelOrchestrator.java # Pipeline engine with batching
│       ├── OrchestrationConfig.java    # Mode & provider configuration
│       └── TranslationException.java   # Checked translation exception
│
├── ui/                            # User interface components
│   ├── MainWindow.java            # Main window assembly (Composite)
│   ├── DsrtGenerationDialog.java  # Chunked generation dialog (990 lines)
│   ├── MenuBarBuilder.java        # 7-menu builder with accelerators
│   ├── ControlBar.java            # Transport controls
│   ├── SeekBar.java               # Seek slider with time tooltip
│   ├── SubtitleOverlay.java       # O(log n) real-time subtitle display
│   ├── VideoPane.java             # Video surface + overlay container
│   ├── VolumeControl.java         # Volume slider with scroll wheel
│   ├── FullScreenHandler.java     # Auto-hiding fullscreen controls
│   ├── PlaylistPanel.java         # Sidebar playlist with drag-and-drop
│   ├── StatusBar.java             # File name + status text
│   ├── NetworkStreamDialog.java   # Network stream URL input
│   ├── TrackSelectionDialog.java  # Audio/subtitle track picker
│   ├── SubtitleGenerationDialog.java # Legacy single-pass dialog
│   │
│   └── controls/                  # Reusable custom controls
│       ├── SpeedSelector.java     # Playback speed ComboBox
│       └── TimeLabel.java         # "current / total" time display
│
└── util/                          # Utility classes
    ├── FileUtils.java             # File extension/type checking
    ├── PlatformUtils.java         # OS detection
    ├── ResourceCleaner.java       # LIFO AutoCloseable tracker (Singleton)
    └── TimeFormatter.java         # Millisecond → HH:MM:SS formatting
```

---

## 13. Build & Run

### Prerequisites
- Java 17+
- Maven 3.9+
- VLC 3.0+ installed (or bundled in `native/`)
- FFmpeg on PATH
- whisper.cpp binary on PATH or in `whisper/Release/`
- (Optional) Python 3 + `argostranslate` for offline translation

### Build
```bash
cd lumina-player
mvn clean package -DskipTests
```

### Run
```bash
java --add-opens java.base/java.lang=ALL-UNNAMED -jar target/lumina-player-1.0.0-SNAPSHOT.jar
```

### Keyboard Shortcuts

| Shortcut | Action |
|---|---|
| `Space` | Play / Pause |
| `F11` / `F` | Toggle fullscreen |
| `M` | Toggle mute |
| `←` / `→` | Skip backward / forward 10s |
| `↑` / `↓` | Volume up / down |
| `N` / `P` | Next / Previous in playlist |
| `E` | Next frame (step) |
| `V` | Toggle subtitle overlay |
| `Ctrl+O` | Open file |
| `Ctrl+Shift+O` | Open multiple files |
| `Ctrl+N` | Open network stream |
| `Ctrl+L` | Toggle playlist panel |
| `Ctrl+Shift+G` | Generate .dsrt subtitles (AI) |
| `Ctrl+Shift+S` | Load .dsrt file |
| `Ctrl+S` | Load static subtitles |
| `Ctrl+I` | Media info |
| `Ctrl+Q` | Quit |

---

> *LuminaPlayer — Where AI meets media playback, entirely offline, entirely on your machine.*

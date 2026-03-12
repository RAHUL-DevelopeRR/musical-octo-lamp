# =============================================================================
# LuminaPlayer — Multi-stage Dockerfile
# =============================================================================
# Stage 1: whisper-sidecar  — Python sidecar (whisper_server.py)
#           Build target: whisper-sidecar
#           Base: python:3.10-slim
#
# Stage 2: builder          — VLC build from source
#           Base: ubuntu:24.04
#
# Stage 3: vlc-runtime      — Final VLC streaming engine
#           Base: ubuntu:24.04
# =============================================================================

# -----------------------------------------------------------------------------
# Stage 1 — Whisper Sidecar (Python)
# Run: docker build --target whisper-sidecar -t lumina-whisper-sidecar .
# -----------------------------------------------------------------------------
FROM python:3.10-slim AS whisper-sidecar

# Build args to toggle optional features
ARG WITH_VAD=1
ARG WITH_SED=0
ARG WITH_THROTTLE=1

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    HF_HOME=/models/hf \
    XDG_CACHE_HOME=/models/cache \
    TORCH_HOME=/models/torch

# System deps:
#   ffmpeg      — audio extraction pipeline
#   libgomp1    — OpenMP, required by ctranslate2 (faster-whisper)
#   git         — torch.hub fetches silero-vad
#   libsndfile1 — required by soundfile (audio I/O)
RUN apt-get update && apt-get install -y --no-install-recommends \
      ffmpeg \
      git \
      libgomp1 \
      libsndfile1 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy sidecar + requirements
COPY lumina-player/scripts/whisper_server.py /app/whisper_server.py
COPY lumina-player/scripts/requirements.txt  /app/requirements.txt

# Core deps (always installed)
RUN pip install --upgrade pip \
 && pip install faster-whisper soundfile numpy

# Stage 2 — Silero VAD (CPU-only torch, avoids 3 GB CUDA download)
RUN if [ "$WITH_VAD" = "1" ]; then \
      pip install \
        --index-url https://download.pytorch.org/whl/cpu \
        torch torchaudio ; \
    fi

# Stage 7 — YAMNet SED
RUN if [ "$WITH_SED" = "1" ]; then \
      pip install tensorflow tensorflow-hub ; \
    fi

# CPU throttle
RUN if [ "$WITH_THROTTLE" = "1" ]; then pip install psutil; fi

# Non-root user for security
RUN useradd -m -u 10001 appuser
USER appuser

# Mount point for HuggingFace model cache — avoids re-downloading on every run
VOLUME ["/models"]

ENTRYPOINT ["python", "-u", "/app/whisper_server.py"]


# -----------------------------------------------------------------------------
# Stage 2 — VLC Builder
# -----------------------------------------------------------------------------
FROM ubuntu:24.04 AS builder

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update -qq && apt-get install -y -qq \
    build-essential \
    autoconf \
    automake \
    libtool \
    libtool-bin \
    pkg-config \
    flex \
    bison \
    gettext \
    git \
    libgcrypt20-dev \
    libavcodec-dev \
    libavutil-dev \
    libavformat-dev \
    libswscale-dev \
    libswresample-dev \
    libxml2-dev \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /src

COPY vlc/ vlc/

RUN cd vlc && ./bootstrap

RUN mkdir vlc/build && cd vlc/build && ../configure \
    --disable-lua \
    --disable-vlm \
    --disable-nls \
    --disable-dbus \
    --disable-qt \
    --disable-skins2 \
    --disable-xcb \
    --disable-wayland \
    --disable-gles2 \
    --disable-egl \
    --disable-alsa \
    --disable-pulse \
    --disable-jack \
    --enable-avcodec \
    --enable-avformat \
    --enable-swscale \
    --enable-optimizations

RUN cd vlc/build && make -j"$(nproc)"


# -----------------------------------------------------------------------------
# Stage 3 — VLC Runtime
# Run: docker build -t vlc-streaming-engine .
# -----------------------------------------------------------------------------
FROM ubuntu:24.04 AS vlc-runtime

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update -qq && apt-get install -y -qq \
    libgcrypt20 \
    libavcodec60 \
    libavutil58 \
    libavformat60 \
    libswscale7 \
    libswresample4 \
    libxml2 \
    && rm -rf /var/lib/apt/lists/*

COPY --from=builder /src/vlc/build/lib/.libs/libvlc*.so*      /usr/local/lib/
COPY --from=builder /src/vlc/build/src/.libs/libvlccore*.so*  /usr/local/lib/
COPY --from=builder /src/vlc/build/modules/.libs/*.so          /usr/local/lib/vlc/plugins/
COPY --from=builder /src/vlc/build/bin/vlc-cache-gen           /usr/local/bin/
COPY --from=builder /src/vlc/build/bin/.libs/vlc               /usr/local/bin/vlc

RUN ldconfig && vlc-cache-gen /usr/local/lib/vlc/plugins/

ENTRYPOINT ["vlc", "-I", "dummy"]
CMD ["--help"]

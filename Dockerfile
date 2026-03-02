# Build VLC from source
# Multi-stage build for caching dependencies

FROM ubuntu:24.04 AS builder

ENV DEBIAN_FRONTEND=noninteractive

# Install build dependencies
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

# Copy source
COPY vlc/ vlc/

# Bootstrap
RUN cd vlc && ./bootstrap

# Configure (headless/server build)
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

# Build
RUN cd vlc/build && make -j"$(nproc)"

# Runtime stage
FROM ubuntu:24.04

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

# Copy built libraries, plugins, and executables
COPY --from=builder /src/vlc/build/lib/.libs/libvlc*.so* /usr/local/lib/
COPY --from=builder /src/vlc/build/src/.libs/libvlccore*.so* /usr/local/lib/
COPY --from=builder /src/vlc/build/modules/.libs/*.so /usr/local/lib/vlc/plugins/
COPY --from=builder /src/vlc/build/bin/vlc-cache-gen /usr/local/bin/
COPY --from=builder /src/vlc/build/bin/.libs/vlc /usr/local/bin/vlc

RUN ldconfig && vlc-cache-gen /usr/local/lib/vlc/plugins/

ENTRYPOINT ["vlc", "-I", "dummy"]
CMD ["--help"]

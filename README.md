# musical-octo-lamp

This project is the Realtime Streaming Engine of the VLC Player, with Offline AI Subtitle Generation.

## Prerequisites

- Ubuntu 24.04 (or compatible Linux distribution)
- Git with submodule support
- GCC 13+ or Clang 15+
- autoconf, automake, libtool, pkg-config, flex, bison, gettext
- FFmpeg development libraries (libavcodec, libavutil, libavformat, libswscale)
- libgcrypt, libxml2

## Getting Started

Clone the repository with the VLC submodule:

```bash
git clone --recurse-submodules https://github.com/RAHUL-DevelopeRR/musical-octo-lamp.git
cd musical-octo-lamp
```

If you already cloned without submodules:

```bash
git submodule update --init --recursive
```

## Building

### Quick Build (recommended)

The included build script handles dependencies, bootstrapping, configuration, and compilation:

```bash
./build.sh
```

Options:
- `./build.sh --deps-only` — Install dependencies only
- `./build.sh --no-deps` — Skip dependency installation
- `MAKE_JOBS=4 ./build.sh` — Control parallel build jobs

### Manual Build

```bash
# 1. Install dependencies
sudo apt-get install -y build-essential autoconf automake libtool libtool-bin \
  pkg-config flex bison gettext libgcrypt20-dev libavcodec-dev libavutil-dev \
  libavformat-dev libswscale-dev libswresample-dev libxml2-dev

# 2. Bootstrap
cd vlc && ./bootstrap

# 3. Configure
mkdir build && cd build
../configure --disable-lua --disable-qt --disable-skins2 --disable-xcb \
  --disable-wayland --disable-nls --disable-dbus --enable-optimizations

# 4. Build
make -j$(nproc)
```

### Docker Build

```bash
docker build -t vlc-streaming-engine .
```

## Running VLC

### Quick Run (recommended)

After building, use the included `run.sh` script which sets up library and plugin paths automatically:

```bash
# Show version
./run.sh --version

# Play a media file (headless, no GUI)
./run.sh -I dummy input.mp4

# Play an HTTP stream
./run.sh -I dummy http://example.com/stream.ts
```

### Stream Media over HTTP

Start a local HTTP streaming server:

```bash
./run.sh -I dummy input.mp4 \
  --sout '#std{access=http,mux=ts,dst=:8080}'
```

Then connect from another VLC instance or player to `http://localhost:8080`.

### Transcode and Save

Convert a media file to a different format:

```bash
./run.sh -I dummy input.mp4 \
  --sout '#transcode{vcodec=h264,acodec=mpga}:std{access=file,mux=ts,dst=output.ts}' \
  vlc://quit
```

### Running from the Build Directory (manual)

If you prefer not to use `run.sh`, set the environment variables yourself:

```bash
export LD_LIBRARY_PATH=vlc/build/lib/.libs:vlc/build/src/.libs
export VLC_PLUGIN_PATH=vlc/build/modules
vlc/build/bin/vlc-static [options] [media]
```

### Running with Docker

```bash
# Build the image
docker build -t vlc-streaming-engine .

# Run VLC inside a container
docker run --rm vlc-streaming-engine --version

# Stream media (expose port 8080)
docker run --rm -p 8080:8080 -v /path/to/media:/media vlc-streaming-engine \
  /media/input.mp4 --sout '#std{access=http,mux=ts,dst=:8080}'
```

### Common Options

| Option | Description |
|---|---|
| `-I dummy` | Use the dummy interface (no GUI, headless) |
| `--no-video` | Disable video output |
| `--no-audio` | Disable audio output |
| `--sout '<chain>'` | Stream output chain (for streaming/transcoding) |
| `--repeat` | Loop the playlist |
| `vlc://quit` | Auto-quit after playback finishes |
| `--version` | Show VLC version |
| `--help` | Show all available options |

## Build Artifacts

After a successful build, the following artifacts are produced in `vlc/build/`:

| Artifact | Path | Description |
|---|---|---|
| `vlc-static` | `bin/` | VLC standalone executable |
| `libvlc.so` | `lib/.libs/` | VLC public API library |
| `libvlccore.so` | `src/.libs/` | VLC core engine |
| Plugins (`.so`) | `modules/.libs/` | Codec, demuxer, and stream modules |
| `vlc-cache-gen` | `bin/` | Plugin cache generator |

## CI

The project includes a GitHub Actions workflow (`.github/workflows/build.yml`) that automatically builds VLC on every push and pull request to `main`.

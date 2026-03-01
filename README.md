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

## Build Artifacts

After a successful build, the following artifacts are produced in `vlc/build/`:

| Artifact | Path | Description |
|---|---|---|
| `libvlc.so` | `lib/.libs/` | VLC public API library |
| `libvlccore.so` | `src/.libs/` | VLC core engine |
| Plugins (`.so`) | `modules/.libs/` | Codec, demuxer, and stream modules |
| `vlc-cache-gen` | `bin/` | Plugin cache generator |

## CI

The project includes a GitHub Actions workflow (`.github/workflows/build.yml`) that automatically builds VLC on every push and pull request to `main`.

# musical-octo-lamp

This project is the Realtime Streaming Engine of the VLC Player, with Offline AI Subtitle Generation.

## Prerequisites

### Linux

- Ubuntu 24.04 (or compatible Linux distribution)
- Git with submodule support
- GCC 13+ or Clang 15+
- autoconf, automake, libtool, pkg-config, flex, bison, gettext
- FFmpeg development libraries (libavcodec, libavutil, libavformat, libswscale)
- libgcrypt, libxml2

### Windows (x86_64)

- Windows 10/11
- [MSYS2](https://www.msys2.org/) with MinGW-w64 toolchain **or** Visual Studio 2022 (Desktop C++ workload)
- Git, Python 3, CMake, Ninja
- See [Windows Build](#windows-build-x64--qt-gui) for full details

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

### Quick Build — Linux (recommended)

The included build script handles dependencies, bootstrapping, configuration, and compilation:

```bash
./build.sh
```

Options:
- `./build.sh --deps-only` — Install dependencies only
- `./build.sh --no-deps` — Skip dependency installation
- `MAKE_JOBS=4 ./build.sh` — Control parallel build jobs

### Manual Build — Linux

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

### Windows Build (x64 + Qt GUI)

Build VLC as a native Windows executable with the full Qt GUI. Produces `vlc.exe`,
`libvlc.dll`, and all required runtime DLLs.

#### Option A: MSYS2 + MinGW-w64 (recommended)

1. Install [MSYS2](https://www.msys2.org/) and open the **MSYS2 MinGW x64** shell.

2. Clone this repository with submodules:

   ```bash
   git clone --recurse-submodules https://github.com/RAHUL-DevelopeRR/musical-octo-lamp.git
   cd musical-octo-lamp
   ```

3. Run the build script:

   ```bash
   ./build-windows-mingw.sh
   ```

   Options:
   - `./build-windows-mingw.sh --deps-only` — Install MSYS2 packages only
   - `./build-windows-mingw.sh --no-deps` — Skip package installation
   - `./build-windows-mingw.sh --skip-contribs` — Skip contrib builds (use system packages)
   - `MAKE_JOBS=4 ./build-windows-mingw.sh` — Control parallel jobs

4. After a successful build, run VLC:

   ```
   run-windows.bat
   ```

#### Option B: Visual Studio 2022 + PowerShell

1. Install prerequisites:
   - [Visual Studio 2022](https://visualstudio.microsoft.com/) with **Desktop development with C++** workload and **Windows SDK**
   - [Python 3](https://www.python.org/downloads/)
   - [CMake](https://cmake.org/download/)
   - [Ninja](https://ninja-build.org/)
   - Meson: `pip install meson`

2. Open a **PowerShell** window and run:

   ```powershell
   .\build-windows.ps1
   ```

   Options:
   - `.\build-windows.ps1 -BuildType Debug` — Debug build
   - `.\build-windows.ps1 -SkipContribs` — Skip contrib builds
   - `.\build-windows.ps1 -ContribOnly` — Only build contribs

3. After a successful build, run VLC:

   ```
   .\run-windows.bat
   ```

#### Windows Build Configuration

The Windows build is configured with:

| Feature | Status |
|---|---|
| Qt GUI | ✅ Enabled |
| Lua scripting | ✅ Enabled |
| Direct3D 11 video output | ✅ Enabled |
| WASAPI audio output | ✅ Enabled |
| FFmpeg codecs (avcodec, avformat, swscale) | ✅ Enabled |
| X11 / XCB | ❌ Disabled (not applicable) |
| Wayland | ❌ Disabled (not applicable) |
| D-Bus | ❌ Disabled (not applicable) |
| ALSA / PulseAudio / JACK | ❌ Disabled (not applicable) |

### Docker Build

```bash
docker build -t vlc-streaming-engine .
```

## Running VLC

### Quick Run — Linux (recommended)

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

### Quick Run — Windows

After building with `build-windows-mingw.sh` or `build-windows.ps1`, use the batch script:

```
run-windows.bat
run-windows.bat --version
run-windows.bat C:\Videos\movie.mp4
```

Or run `vlc.exe` directly from the output directory:

```
cd output\vlc-win64\bin
vlc.exe
```

#### Required Windows Runtime DLLs

When distributing `vlc.exe`, ensure these DLLs are alongside the executable:

| DLL | Description |
|---|---|
| `libvlc.dll` | VLC public API |
| `libvlccore.dll` | VLC core engine |
| `libgcc_s_seh-1.dll` | GCC runtime (MinGW builds) |
| `libstdc++-6.dll` | C++ standard library (MinGW builds) |
| `libwinpthread-1.dll` | POSIX threads (MinGW builds) |
| `Qt6Core.dll` | Qt core library |
| `Qt6Gui.dll` | Qt GUI library |
| `Qt6Widgets.dll` | Qt widgets library |
| `Qt6Svg.dll` | Qt SVG support |
| `plugins/platforms/qwindows.dll` | Qt Windows platform plugin |

All VLC plugin DLLs must be in a `plugins/` subdirectory or the path set via `VLC_PLUGIN_PATH`.

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

### Linux

After a successful build, the following artifacts are produced in `vlc/build/`:

| Artifact | Path | Description |
|---|---|---|
| `vlc-static` | `bin/` | VLC standalone executable |
| `libvlc.so` | `lib/.libs/` | VLC public API library |
| `libvlccore.so` | `src/.libs/` | VLC core engine |
| Plugins (`.so`) | `modules/.libs/` | Codec, demuxer, and stream modules |
| `vlc-cache-gen` | `bin/` | Plugin cache generator |

### Windows

After a successful Windows build, artifacts are collected in `output/vlc-win64/`:

| Artifact | Path | Description |
|---|---|---|
| `vlc.exe` | `bin/` | VLC Windows executable (with Qt GUI) |
| `libvlc.dll` | `bin/` or `lib/` | VLC public API library |
| `libvlccore.dll` | `bin/` or `lib/` | VLC core engine |
| Plugins (`.dll`) | `lib/vlc/plugins/` | Codec, demuxer, and stream modules |
| Qt DLLs | `bin/` | Qt6 runtime dependencies |
| Qt platform plugins | `bin/plugins/platforms/` | Qt Windows platform integration |

## CI

The project includes a GitHub Actions workflow (`.github/workflows/build.yml`) that automatically builds VLC on every push and pull request to `main`.

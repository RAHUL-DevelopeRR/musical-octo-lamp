#!/usr/bin/env bash
# build-windows-mingw.sh - Build VLC for Windows using MSYS2 + MinGW-w64
#
# This script must be run inside an MSYS2 MinGW 64-bit shell.
# It produces a native Windows VLC binary (vlc.exe) with Qt GUI support.
#
# Prerequisites:
#   1. Install MSYS2 from https://www.msys2.org/
#   2. Open "MSYS2 MinGW x64" shell
#   3. Run this script from the repository root
#
# Usage:
#   ./build-windows-mingw.sh              # Full build
#   ./build-windows-mingw.sh --deps-only  # Install dependencies only
#   ./build-windows-mingw.sh --no-deps    # Skip dependency installation
#   ./build-windows-mingw.sh --skip-contribs  # Skip contrib build
#
# Environment variables:
#   MAKE_JOBS  - Number of parallel make jobs (default: nproc)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
VLC_DIR="${SCRIPT_DIR}/vlc"
BUILD_DIR="${VLC_DIR}/build-win64"
CONTRIB_DIR="${VLC_DIR}/contrib/win64-build"
OUTPUT_DIR="${SCRIPT_DIR}/output/vlc-win64"
MAKE_JOBS="${MAKE_JOBS:-$(nproc)}"

# Parse arguments
INSTALL_DEPS=true
SKIP_BUILD=false
SKIP_CONTRIBS=false
for arg in "$@"; do
    case "$arg" in
        --deps-only)       SKIP_BUILD=true ;;
        --no-deps)         INSTALL_DEPS=false ;;
        --skip-contribs)   SKIP_CONTRIBS=true ;;
        --help|-h)
            head -16 "$0" | tail -15 | sed 's/^# \?//'
            exit 0
            ;;
        *)
            echo "Unknown option: $arg" >&2
            exit 1
            ;;
    esac
done

###############################################################################
# Verify we're in MSYS2 MinGW environment
###############################################################################
verify_environment() {
    echo "==> Verifying MSYS2 MinGW environment..."

    if [[ "${MSYSTEM:-}" != "MINGW64" && "${MSYSTEM:-}" != "UCRT64" ]]; then
        echo "ERROR: This script must be run in an MSYS2 MinGW 64-bit shell." >&2
        echo "" >&2
        echo "Open one of:" >&2
        echo "  - MSYS2 MinGW x64" >&2
        echo "  - MSYS2 UCRT64" >&2
        echo "" >&2
        echo "Then run this script again." >&2
        exit 1
    fi

    echo "  Environment: ${MSYSTEM}"
}

###############################################################################
# Step 1: Install MSYS2/MinGW dependencies
###############################################################################
install_deps() {
    echo "==> Installing build dependencies via pacman..."

    # Update package database
    pacman -Syu --noconfirm

    # Install MinGW-w64 toolchain and build tools
    pacman -S --needed --noconfirm \
        mingw-w64-x86_64-toolchain \
        mingw-w64-x86_64-cmake \
        mingw-w64-x86_64-ninja \
        mingw-w64-x86_64-meson \
        mingw-w64-x86_64-python \
        mingw-w64-x86_64-python-pip \
        base-devel \
        git \
        autoconf \
        automake \
        libtool \
        pkg-config \
        flex \
        bison \
        gettext-devel \
        nasm \
        yasm

    # Install VLC runtime dependencies
    pacman -S --needed --noconfirm \
        mingw-w64-x86_64-qt6-base \
        mingw-w64-x86_64-qt6-svg \
        mingw-w64-x86_64-qt6-shadertools \
        mingw-w64-x86_64-ffmpeg \
        mingw-w64-x86_64-libgcrypt \
        mingw-w64-x86_64-libxml2 \
        mingw-w64-x86_64-lua \
        mingw-w64-x86_64-protobuf \
        mingw-w64-x86_64-libplacebo \
        mingw-w64-x86_64-freetype \
        mingw-w64-x86_64-fribidi \
        mingw-w64-x86_64-harfbuzz \
        mingw-w64-x86_64-fontconfig \
        mingw-w64-x86_64-dav1d \
        mingw-w64-x86_64-opus \
        mingw-w64-x86_64-flac \
        mingw-w64-x86_64-libvorbis \
        mingw-w64-x86_64-x264 \
        mingw-w64-x86_64-x265 \
        mingw-w64-x86_64-libpng \
        mingw-w64-x86_64-libjpeg-turbo

    echo "==> Dependencies installed successfully."
}

###############################################################################
# Step 2: Initialize VLC submodule
###############################################################################
init_submodule() {
    echo "==> Initializing VLC submodule..."
    cd "$SCRIPT_DIR"
    if [ ! -f "${VLC_DIR}/configure.ac" ]; then
        git submodule update --init --recursive
    fi
    echo "==> VLC submodule ready."
}

###############################################################################
# Step 3: Build contrib packages
###############################################################################
build_contribs() {
    echo "==> Building contrib packages..."
    local contrib_src="${VLC_DIR}/contrib"

    if [ ! -d "$contrib_src" ]; then
        echo "ERROR: VLC contrib directory not found at $contrib_src" >&2
        exit 1
    fi

    mkdir -p "$CONTRIB_DIR"
    cd "$CONTRIB_DIR"

    # Bootstrap contribs for Windows target
    if [ ! -f Makefile ]; then
        "$contrib_src/bootstrap" \
            --host=x86_64-w64-mingw32
    fi

    # Build contrib libraries
    make -j"${MAKE_JOBS}" .qt || true  # Qt is critical, build it first
    make -j"${MAKE_JOBS}" || true

    echo "==> Contrib build complete."
}

###############################################################################
# Step 4: Bootstrap VLC
###############################################################################
bootstrap_vlc() {
    echo "==> Bootstrapping VLC..."
    cd "$VLC_DIR"
    if [ ! -f configure ]; then
        ./bootstrap
    else
        echo "    configure already exists, skipping bootstrap."
    fi
    echo "==> Bootstrap complete."
}

###############################################################################
# Step 5: Configure VLC for Windows with Qt GUI
###############################################################################
configure_vlc() {
    echo "==> Configuring VLC for Windows x64 with Qt GUI..."
    mkdir -p "$BUILD_DIR"
    cd "$BUILD_DIR"

    local CONFIGURE_OPTS=(
        # Target Windows x86_64
        --host=x86_64-w64-mingw32

        # Enable Qt GUI (this is the key requirement)
        --enable-qt

        # Enable Lua scripting
        --enable-lua

        # Windows video output (Direct3D)
        --enable-d3d11va
        --enable-directx-va2

        # Windows audio output (WASAPI)
        --enable-wasapi

        # Codecs and streaming
        --enable-avcodec
        --enable-avformat
        --enable-swscale

        # Disable Linux/Unix display systems
        --disable-xcb
        --disable-xvideo
        --disable-wayland
        --disable-dbus

        # Disable Linux audio systems
        --disable-alsa
        --disable-pulse
        --disable-jack
        --disable-oss

        # Optimizations
        --enable-optimizations

        # Install prefix
        --prefix="$OUTPUT_DIR"
    )

    ../configure "${CONFIGURE_OPTS[@]}"

    echo "==> Configuration complete."
}

###############################################################################
# Step 6: Build VLC
###############################################################################
build_vlc() {
    echo "==> Building VLC with ${MAKE_JOBS} parallel jobs..."
    cd "$BUILD_DIR"
    make -j"${MAKE_JOBS}"
    echo "==> Build complete."
}

###############################################################################
# Step 7: Install and collect artifacts
###############################################################################
install_vlc() {
    echo "==> Installing VLC to output directory..."
    cd "$BUILD_DIR"
    make install

    # Also copy required MinGW/Qt runtime DLLs
    echo "==> Copying runtime DLLs..."
    local bin_dir="${OUTPUT_DIR}/bin"
    local mingw_bin="/mingw64/bin"

    # Core MinGW runtime DLLs
    local runtime_dlls=(
        "libgcc_s_seh-1.dll"
        "libstdc++-6.dll"
        "libwinpthread-1.dll"
    )

    for dll in "${runtime_dlls[@]}"; do
        if [ -f "${mingw_bin}/${dll}" ]; then
            cp "${mingw_bin}/${dll}" "$bin_dir/"
            echo "    Copied ${dll}"
        fi
    done

    # Qt6 DLLs
    for dll in "${mingw_bin}"/Qt6*.dll; do
        if [ -f "$dll" ]; then
            cp "$dll" "$bin_dir/"
            echo "    Copied $(basename "$dll")"
        fi
    done

    # Qt6 plugins (platforms, imageformats, etc.)
    local qt_plugin_dir="/mingw64/share/qt6/plugins"
    if [ -d "$qt_plugin_dir" ]; then
        mkdir -p "${bin_dir}/plugins/platforms"
        mkdir -p "${bin_dir}/plugins/imageformats"
        mkdir -p "${bin_dir}/plugins/styles"
        cp "$qt_plugin_dir/platforms/qwindows.dll" "${bin_dir}/plugins/platforms/" 2>/dev/null || true
        cp "$qt_plugin_dir/imageformats/"*.dll "${bin_dir}/plugins/imageformats/" 2>/dev/null || true
        cp "$qt_plugin_dir/styles/"*.dll "${bin_dir}/plugins/styles/" 2>/dev/null || true
        echo "    Copied Qt6 platform plugins"
    fi

    echo "==> Installation complete."
}

###############################################################################
# Step 8: Verify build
###############################################################################
verify_build() {
    echo "==> Verifying build artifacts..."
    local success=true
    local bin_dir="${OUTPUT_DIR}/bin"

    if [ -f "${bin_dir}/vlc.exe" ]; then
        echo "    ✓ vlc.exe"
    else
        echo "    ✗ vlc.exe not found"
        success=false
    fi

    if [ -f "${bin_dir}/libvlc.dll" ] || [ -f "${OUTPUT_DIR}/lib/libvlc.dll" ]; then
        echo "    ✓ libvlc.dll"
    else
        echo "    ✗ libvlc.dll not found"
        success=false
    fi

    if [ -f "${bin_dir}/libvlccore.dll" ] || [ -f "${OUTPUT_DIR}/lib/libvlccore.dll" ]; then
        echo "    ✓ libvlccore.dll"
    else
        echo "    ✗ libvlccore.dll not found"
        success=false
    fi

    local plugin_count
    plugin_count=$(find "${OUTPUT_DIR}" -name "*.dll" -path "*/vlc/plugins/*" 2>/dev/null | wc -l)
    echo "    ✓ ${plugin_count} VLC plugin DLLs"

    if [ "$success" = true ]; then
        echo ""
        echo "=== VLC Windows build completed successfully! ==="
        echo ""
        echo "Output directory: ${OUTPUT_DIR}"
        echo ""
        echo "To run VLC:"
        echo "  cd ${bin_dir}"
        echo "  ./vlc.exe"
        echo ""
        echo "Or use: ./run-windows.bat"
    else
        echo ""
        echo "=== VLC build had issues. Check the output above. ==="
        exit 1
    fi
}

###############################################################################
# Main
###############################################################################
echo "=========================================="
echo "  VLC Windows Build (MSYS2 + MinGW-w64)"
echo "=========================================="
echo ""
echo "  Target  : Windows x86_64"
echo "  GUI     : Qt (enabled)"
echo "  Build   : ${BUILD_DIR}"
echo "  Output  : ${OUTPUT_DIR}"
echo ""

verify_environment

if [ "$INSTALL_DEPS" = true ]; then
    install_deps
fi

if [ "$SKIP_BUILD" = true ]; then
    echo "==> Dependency installation complete (--deps-only)."
    exit 0
fi

init_submodule

if [ "$SKIP_CONTRIBS" = false ]; then
    build_contribs
fi

bootstrap_vlc
configure_vlc
build_vlc
install_vlc
verify_build

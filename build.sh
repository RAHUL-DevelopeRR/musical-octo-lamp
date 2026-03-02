#!/usr/bin/env bash
# build.sh - Build script for VLC Media Player
#
# Usage:
#   ./build.sh              # Install deps, bootstrap, configure, and build
#   ./build.sh --deps-only  # Only install dependencies
#   ./build.sh --no-deps    # Skip dependency installation
#
# Environment variables:
#   VLC_CONFIGURE_ARGS  - Extra arguments to pass to ./configure
#   MAKE_JOBS           - Number of parallel make jobs (default: nproc)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
VLC_DIR="${SCRIPT_DIR}/vlc"
BUILD_DIR="${VLC_DIR}/build"
MAKE_JOBS="${MAKE_JOBS:-$(nproc)}"

# Parse arguments
INSTALL_DEPS=true
SKIP_BUILD=false
for arg in "$@"; do
    case "$arg" in
        --deps-only)  SKIP_BUILD=true ;;
        --no-deps)    INSTALL_DEPS=false ;;
        --help|-h)
            head -8 "$0" | tail -7 | sed 's/^# \?//'
            exit 0
            ;;
        *)
            echo "Unknown option: $arg" >&2
            exit 1
            ;;
    esac
done

###############################################################################
# Step 1: Install system dependencies
###############################################################################
install_deps() {
    echo "==> Installing build dependencies..."
    sudo apt-get update -qq

    sudo apt-get install -y -qq \
        build-essential \
        autoconf \
        automake \
        libtool \
        libtool-bin \
        pkg-config \
        flex \
        bison \
        gettext \
        libgcrypt20-dev \
        libavcodec-dev \
        libavutil-dev \
        libavformat-dev \
        libswscale-dev \
        libswresample-dev \
        libxml2-dev

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
# Step 3: Bootstrap VLC (generate configure script)
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
# Step 4: Configure VLC
###############################################################################
configure_vlc() {
    echo "==> Configuring VLC..."
    mkdir -p "$BUILD_DIR"
    cd "$BUILD_DIR"

    # Minimal headless/server configuration
    # Disable GUI and display modules (no X11/Wayland/Qt needed)
    # Keep codec and streaming support for the realtime streaming engine
    local CONFIGURE_OPTS=(
        --disable-lua
        --disable-vlm
        --disable-nls
        --disable-dbus
        --disable-qt
        --disable-skins2
        --disable-xcb
        --disable-wayland
        --disable-gles2
        --disable-egl
        --disable-alsa
        --disable-pulse
        --disable-jack
        --enable-avcodec
        --enable-avformat
        --enable-swscale
        --enable-optimizations
    )

    ../configure "${CONFIGURE_OPTS[@]}" ${VLC_CONFIGURE_ARGS:-}

    echo "==> Configuration complete."
}

###############################################################################
# Step 5: Build VLC
###############################################################################
build_vlc() {
    echo "==> Building VLC with ${MAKE_JOBS} parallel jobs..."
    cd "$BUILD_DIR"
    make -j"${MAKE_JOBS}"
    echo "==> Build complete."
}

###############################################################################
# Step 6: Verify build
###############################################################################
verify_build() {
    echo "==> Verifying build artifacts..."
    local success=true

    if [ -f "${BUILD_DIR}/lib/.libs/libvlc.so" ]; then
        echo "    ✓ libvlc.so built successfully"
    else
        echo "    ✗ libvlc.so not found"
        success=false
    fi

    if [ -f "${BUILD_DIR}/src/.libs/libvlccore.so" ]; then
        echo "    ✓ libvlccore.so built successfully"
    else
        echo "    ✗ libvlccore.so not found"
        success=false
    fi

    local module_count
    module_count=$(find "${BUILD_DIR}/modules/.libs/" -name "*.so" 2>/dev/null | wc -l)
    echo "    ✓ ${module_count} VLC modules built"

    if [ "$success" = true ]; then
        echo ""
        echo "=== VLC build completed successfully! ==="
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
echo "  VLC Media Player Build Script"
echo "=========================================="
echo ""

if [ "$INSTALL_DEPS" = true ]; then
    install_deps
fi

if [ "$SKIP_BUILD" = true ]; then
    echo "==> Dependency installation complete (--deps-only)."
    exit 0
fi

init_submodule
bootstrap_vlc
configure_vlc
build_vlc
verify_build

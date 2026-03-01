#!/usr/bin/env bash
# run.sh - Run VLC from the build directory
#
# Usage:
#   ./run.sh [vlc-options] [media]    Run VLC (interactive)
#   ./run.sh --hierarchical           Stream media over HTTP
#   ./run.sh --version                Show VLC version
#   ./run.sh --help                   Show VLC help
#
# This script automatically sets the correct library and plugin paths
# so VLC can be run directly from the build tree without installing.
#
# Examples:
#   ./run.sh --version
#   ./run.sh -I dummy --no-video --no-audio file.mp4
#   ./run.sh --sout '#std{access=http,mux=ts,dst=:8080}' input.mp4

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
VLC_DIR="${SCRIPT_DIR}/vlc"
BUILD_DIR="${VLC_DIR}/build"
VLC_BIN="${BUILD_DIR}/bin/vlc-static"

###############################################################################
# Pre-flight checks
###############################################################################
if [ ! -f "$VLC_BIN" ]; then
    echo "Error: VLC has not been built yet." >&2
    echo "" >&2
    echo "Run the build first:" >&2
    echo "  ./build.sh" >&2
    exit 1
fi

###############################################################################
# Set up environment for running from the build tree
###############################################################################
# Library paths so the dynamic linker can find libvlc and libvlccore
export LD_LIBRARY_PATH="${BUILD_DIR}/lib/.libs:${BUILD_DIR}/src/.libs${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"

# Tell VLC where to find its plugins
export VLC_PLUGIN_PATH="${BUILD_DIR}/modules"

###############################################################################
# Run VLC
###############################################################################
exec "$VLC_BIN" "$@"

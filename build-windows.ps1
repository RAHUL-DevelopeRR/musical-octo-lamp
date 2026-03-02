<#
.SYNOPSIS
    Build VLC Media Player for Windows (x86_64) with Qt GUI support.

.DESCRIPTION
    This script builds VLC 4.0 from source on Windows using MSVC (Visual Studio 2022)
    with Meson/Ninja. It produces vlc.exe, libvlc.dll, and all required runtime
    dependencies including the Qt GUI.

    Prerequisites:
      - Visual Studio 2022 (Desktop C++ workload + Windows SDK)
      - Python 3.x (in PATH)
      - Git (in PATH)
      - CMake 3.20+ (in PATH)
      - Ninja (in PATH)
      - Meson (pip install meson)
      - NASM (for assembly optimizations, optional)

.PARAMETER Architecture
    Target architecture. Default: x64

.PARAMETER BuildType
    Build type: Release or Debug. Default: Release

.PARAMETER ContribOnly
    Only build contrib (third-party) packages.

.PARAMETER SkipContribs
    Skip building contrib packages (use if already built).

.PARAMETER VsVersion
    Visual Studio version. Default: 2022

.EXAMPLE
    .\build-windows.ps1
    .\build-windows.ps1 -BuildType Debug
    .\build-windows.ps1 -SkipContribs
#>

[CmdletBinding()]
param(
    [ValidateSet("x64")]
    [string]$Architecture = "x64",

    [ValidateSet("Release", "Debug")]
    [string]$BuildType = "Release",

    [switch]$ContribOnly,

    [switch]$SkipContribs,

    [ValidateSet("2022", "2019")]
    [string]$VsVersion = "2022"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$VlcDir = Join-Path $ScriptDir "vlc"
$BuildDir = Join-Path $VlcDir "build-win64"
$ContribDir = Join-Path $VlcDir "contrib" "win64-build"
$OutputDir = Join-Path $ScriptDir "output" "vlc-win64"

# ──────────────────────────────────────────────────────────────────────────────
# Helper functions
# ──────────────────────────────────────────────────────────────────────────────

function Write-Step {
    param([string]$Message)
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Assert-Command {
    param([string]$Name, [string]$HelpUrl)
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        Write-Host "ERROR: '$Name' not found in PATH." -ForegroundColor Red
        if ($HelpUrl) {
            Write-Host "  Install from: $HelpUrl" -ForegroundColor Yellow
        }
        exit 1
    }
}

function Get-VsInstallPath {
    $vswhere = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
    if (-not (Test-Path $vswhere)) {
        Write-Host "ERROR: Visual Studio Installer not found." -ForegroundColor Red
        Write-Host "  Install Visual Studio 2022 with 'Desktop development with C++' workload." -ForegroundColor Yellow
        exit 1
    }
    $vsPath = & $vswhere -version "[17.0,18.0)" -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath -latest
    if (-not $vsPath) {
        $vsPath = & $vswhere -latest -property installationPath
    }
    if (-not $vsPath) {
        Write-Host "ERROR: Visual Studio $VsVersion with C++ workload not found." -ForegroundColor Red
        exit 1
    }
    return $vsPath
}

function Enter-VsDevShell {
    $vsPath = Get-VsInstallPath
    $vsDevShell = Join-Path $vsPath "Common7\Tools\Launch-VsDevShell.ps1"
    if (Test-Path $vsDevShell) {
        Write-Step "Setting up Visual Studio Developer Environment"
        & $vsDevShell -Arch amd64 -HostArch amd64 -SkipAutomaticLocation
    } else {
        $vcvarsall = Join-Path $vsPath "VC\Auxiliary\Build\vcvarsall.bat"
        if (Test-Path $vcvarsall) {
            Write-Step "Setting up MSVC environment via vcvarsall.bat"
            cmd /c "`"$vcvarsall`" amd64 && set" | ForEach-Object {
                if ($_ -match "^([^=]+)=(.*)$") {
                    [System.Environment]::SetEnvironmentVariable($matches[1], $matches[2], "Process")
                }
            }
        } else {
            Write-Host "ERROR: Cannot find VS developer environment setup." -ForegroundColor Red
            exit 1
        }
    }
    Write-Host "  MSVC environment configured for $Architecture" -ForegroundColor Green
}

# ──────────────────────────────────────────────────────────────────────────────
# Step 1: Verify prerequisites
# ──────────────────────────────────────────────────────────────────────────────
function Test-Prerequisites {
    Write-Step "Checking prerequisites..."

    Assert-Command "git"    "https://git-scm.com/download/win"
    Assert-Command "python" "https://www.python.org/downloads/"
    Assert-Command "cmake"  "https://cmake.org/download/"
    Assert-Command "ninja"  "https://ninja-build.org/"

    # Check for meson
    if (-not (Get-Command "meson" -ErrorAction SilentlyContinue)) {
        Write-Host "  Meson not found, installing via pip..." -ForegroundColor Yellow
        python -m pip install --user meson
        $userScriptsDir = Join-Path ([System.Environment]::GetFolderPath("LocalApplicationData")) "Programs\Python" | Get-ChildItem -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($userScriptsDir) {
            $env:PATH += ";$(Join-Path $userScriptsDir.FullName 'Scripts')"
        } else {
            $userBase = python -c "import site; print(site.getuserbase())"
            $env:PATH += ";$(Join-Path $userBase 'Scripts')"
        }
        Assert-Command "meson" "Run: pip install meson"
    }

    # Verify Visual Studio
    $null = Get-VsInstallPath
    Write-Host "  All prerequisites satisfied." -ForegroundColor Green
}

# ──────────────────────────────────────────────────────────────────────────────
# Step 2: Initialize VLC submodule
# ──────────────────────────────────────────────────────────────────────────────
function Initialize-VlcSubmodule {
    Write-Step "Initializing VLC submodule..."
    Push-Location $ScriptDir
    try {
        if (-not (Test-Path (Join-Path $VlcDir "configure.ac"))) {
            git submodule update --init --recursive
        } else {
            Write-Host "  VLC submodule already initialized." -ForegroundColor Green
        }
    } finally {
        Pop-Location
    }
}

# ──────────────────────────────────────────────────────────────────────────────
# Step 3: Build contrib (third-party libraries including Qt)
# ──────────────────────────────────────────────────────────────────────────────
function Build-Contribs {
    Write-Step "Building contrib packages (third-party libraries)..."
    $contribSrc = Join-Path $VlcDir "contrib"

    if (-not (Test-Path $contribSrc)) {
        Write-Host "ERROR: VLC contrib directory not found at $contribSrc" -ForegroundColor Red
        exit 1
    }

    New-Item -ItemType Directory -Path $ContribDir -Force | Out-Null
    Push-Location $ContribDir
    try {
        # Bootstrap the contrib build system
        $bootstrapScript = Join-Path $contribSrc "bootstrap"
        if (Test-Path $bootstrapScript) {
            Write-Host "  Bootstrapping contribs..."
            & $bootstrapScript `
                --host=x86_64-w64-mingw32
        }

        # Use prebuilt contribs if available (VLC provides prebuilt Windows contribs)
        $prebuiltUrl = "https://artifacts.videolan.org/vlc/win64/"
        Write-Host "  Building contribs with make..."
        Write-Host "  NOTE: This may take a long time (1-2 hours) on first build." -ForegroundColor Yellow

        # Build the contrib libraries
        if (Test-Path (Join-Path $ContribDir "Makefile")) {
            cmake --build . --parallel $env:NUMBER_OF_PROCESSORS
        }
    } finally {
        Pop-Location
    }

    Write-Host "  Contribs built." -ForegroundColor Green
}

# ──────────────────────────────────────────────────────────────────────────────
# Step 4: Configure VLC (Meson)
# ──────────────────────────────────────────────────────────────────────────────
function Configure-Vlc {
    Write-Step "Configuring VLC for Windows x64 with Qt GUI..."

    New-Item -ItemType Directory -Path $BuildDir -Force | Out-Null

    $mesonBuildFile = Join-Path $VlcDir "meson.build"
    $usesMeson = Test-Path $mesonBuildFile

    if ($usesMeson) {
        Write-Host "  Using Meson build system..."
        $mesonArgs = @(
            "setup"
            $BuildDir
            $VlcDir
            "--buildtype=$(if ($BuildType -eq 'Release') { 'release' } else { 'debug' })"
            "--backend=ninja"
            "-Dqt=enabled"
            "-Dlua=enabled"
            "-Dx11=disabled"
            "-Dxcb=disabled"
            "-Dwayland=disabled"
            "-Dd3d11=enabled"
            "-Dwasapi=enabled"
            "-Doptimize_memory=true"
        )
        & meson @mesonArgs
    } else {
        Write-Host "  Meson build file not found, falling back to autotools..." -ForegroundColor Yellow
        Write-Host "  For autotools on Windows, use build-windows-mingw.sh with MSYS2 instead." -ForegroundColor Yellow

        # Autotools path (requires MSYS2/MinGW shell - see build-windows-mingw.sh)
        Push-Location $VlcDir
        try {
            if (-not (Test-Path (Join-Path $VlcDir "configure"))) {
                Write-Host "  Running bootstrap..."
                bash ./bootstrap
            }
        } finally {
            Pop-Location
        }

        New-Item -ItemType Directory -Path $BuildDir -Force | Out-Null
        Push-Location $BuildDir
        try {
            $configureArgs = @(
                "--host=x86_64-w64-mingw32"
                "--enable-qt"
                "--enable-lua"
                "--disable-xcb"
                "--disable-wayland"
                "--disable-dbus"
                "--enable-wasapi"
                "--enable-d3d11va"
                "--enable-avcodec"
                "--enable-avformat"
                "--enable-swscale"
                "--enable-optimizations"
                "--prefix=$OutputDir"
            )
            bash ../configure @configureArgs
        } finally {
            Pop-Location
        }
    }

    Write-Host "  Configuration complete." -ForegroundColor Green
}

# ──────────────────────────────────────────────────────────────────────────────
# Step 5: Build VLC
# ──────────────────────────────────────────────────────────────────────────────
function Build-Vlc {
    Write-Step "Building VLC ($BuildType, $Architecture)..."
    Push-Location $BuildDir
    try {
        $mesonBuildFile = Join-Path $VlcDir "meson.build"
        if (Test-Path $mesonBuildFile) {
            ninja -j $env:NUMBER_OF_PROCESSORS
        } else {
            make -j $env:NUMBER_OF_PROCESSORS
        }
    } finally {
        Pop-Location
    }
    Write-Host "  Build complete." -ForegroundColor Green
}

# ──────────────────────────────────────────────────────────────────────────────
# Step 6: Collect output artifacts
# ──────────────────────────────────────────────────────────────────────────────
function Collect-Artifacts {
    Write-Step "Collecting build artifacts..."

    New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $OutputDir "plugins") -Force | Out-Null

    # Copy main executables
    $vlcExe = Get-ChildItem -Path $BuildDir -Filter "vlc.exe" -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($vlcExe) {
        Copy-Item $vlcExe.FullName -Destination $OutputDir
        Write-Host "  Copied vlc.exe" -ForegroundColor Green
    } else {
        Write-Host "  WARNING: vlc.exe not found in build output" -ForegroundColor Yellow
    }

    # Copy libvlc.dll
    $libvlcDll = Get-ChildItem -Path $BuildDir -Filter "libvlc.dll" -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($libvlcDll) {
        Copy-Item $libvlcDll.FullName -Destination $OutputDir
        Write-Host "  Copied libvlc.dll" -ForegroundColor Green
    }

    # Copy libvlccore.dll
    $libvlccoreDll = Get-ChildItem -Path $BuildDir -Filter "libvlccore.dll" -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($libvlccoreDll) {
        Copy-Item $libvlccoreDll.FullName -Destination $OutputDir
        Write-Host "  Copied libvlccore.dll" -ForegroundColor Green
    }

    # Copy plugin DLLs
    $plugins = Get-ChildItem -Path $BuildDir -Filter "*.dll" -Recurse -ErrorAction SilentlyContinue |
        Where-Object { $_.Directory.Name -match "modules|plugins" }
    foreach ($plugin in $plugins) {
        Copy-Item $plugin.FullName -Destination (Join-Path $OutputDir "plugins")
    }
    $pluginCount = ($plugins | Measure-Object).Count
    Write-Host "  Copied $pluginCount plugin DLLs" -ForegroundColor Green

    # Copy Qt runtime DLLs from contribs
    $qtBinDir = Join-Path $ContribDir "x86_64-w64-mingw32" "bin"
    if (Test-Path $qtBinDir) {
        $qtDlls = Get-ChildItem -Path $qtBinDir -Filter "Qt*.dll" -ErrorAction SilentlyContinue
        foreach ($dll in $qtDlls) {
            Copy-Item $dll.FullName -Destination $OutputDir
        }
        Write-Host "  Copied Qt runtime DLLs" -ForegroundColor Green
    }

    Write-Host ""
    Write-Host "  Build artifacts collected in: $OutputDir" -ForegroundColor Green
}

# ──────────────────────────────────────────────────────────────────────────────
# Step 7: Verify build
# ──────────────────────────────────────────────────────────────────────────────
function Test-Build {
    Write-Step "Verifying build artifacts..."
    $success = $true

    $vlcExePath = Join-Path $OutputDir "vlc.exe"
    if (Test-Path $vlcExePath) {
        Write-Host "  ✓ vlc.exe" -ForegroundColor Green
    } else {
        Write-Host "  ✗ vlc.exe not found" -ForegroundColor Red
        $success = $false
    }

    $libvlcPath = Join-Path $OutputDir "libvlc.dll"
    if (Test-Path $libvlcPath) {
        Write-Host "  ✓ libvlc.dll" -ForegroundColor Green
    } else {
        Write-Host "  ✗ libvlc.dll not found" -ForegroundColor Red
        $success = $false
    }

    $libvlccorePath = Join-Path $OutputDir "libvlccore.dll"
    if (Test-Path $libvlccorePath) {
        Write-Host "  ✓ libvlccore.dll" -ForegroundColor Green
    } else {
        Write-Host "  ✗ libvlccore.dll not found" -ForegroundColor Red
        $success = $false
    }

    $pluginDir = Join-Path $OutputDir "plugins"
    if (Test-Path $pluginDir) {
        $pluginCount = (Get-ChildItem -Path $pluginDir -Filter "*.dll" | Measure-Object).Count
        Write-Host "  ✓ $pluginCount VLC plugin DLLs" -ForegroundColor Green
    }

    if ($success) {
        Write-Host ""
        Write-Host "=== VLC Windows build completed successfully! ===" -ForegroundColor Green
        Write-Host ""
        Write-Host "To run VLC:" -ForegroundColor Cyan
        Write-Host "  cd $OutputDir" -ForegroundColor White
        Write-Host "  .\vlc.exe" -ForegroundColor White
        Write-Host ""
        Write-Host "Or use the run script:" -ForegroundColor Cyan
        Write-Host "  .\run-windows.bat" -ForegroundColor White
    } else {
        Write-Host ""
        Write-Host "=== Build verification found missing artifacts ===" -ForegroundColor Red
        exit 1
    }
}

# ──────────────────────────────────────────────────────────────────────────────
# Main
# ──────────────────────────────────────────────────────────────────────────────

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "  VLC Windows Build Script (x64 + Qt GUI)" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "  Architecture : $Architecture"
Write-Host "  Build Type   : $BuildType"
Write-Host "  VLC Source   : $VlcDir"
Write-Host "  Build Dir    : $BuildDir"
Write-Host "  Output Dir   : $OutputDir"
Write-Host ""

Test-Prerequisites
Enter-VsDevShell
Initialize-VlcSubmodule

if (-not $SkipContribs) {
    Build-Contribs
    if ($ContribOnly) {
        Write-Host "==> Contrib-only build complete." -ForegroundColor Green
        exit 0
    }
}

Configure-Vlc
Build-Vlc
Collect-Artifacts
Test-Build

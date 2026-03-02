@echo off
REM run-windows.bat - Run VLC from the build output directory on Windows
REM
REM Usage:
REM   run-windows.bat [vlc-options] [media]
REM   run-windows.bat --version
REM   run-windows.bat --help
REM   run-windows.bat C:\path\to\video.mp4
REM
REM This script sets up the correct paths for DLLs and plugins so VLC
REM can run directly from the build output without a system-wide install.

setlocal enabledelayedexpansion

set "SCRIPT_DIR=%~dp0"
set "OUTPUT_DIR=%SCRIPT_DIR%output\vlc-win64"

REM Look for vlc.exe in the output directory
if exist "%OUTPUT_DIR%\bin\vlc.exe" (
    set "VLC_BIN=%OUTPUT_DIR%\bin"
) else if exist "%OUTPUT_DIR%\vlc.exe" (
    set "VLC_BIN=%OUTPUT_DIR%"
) else (
    echo ERROR: VLC has not been built yet.
    echo.
    echo Build VLC first using one of:
    echo   PowerShell:  .\build-windows.ps1
    echo   MSYS2:       ./build-windows-mingw.sh
    echo.
    exit /b 1
)

REM Add VLC binary directory to PATH for DLL resolution
set "PATH=%VLC_BIN%;%PATH%"

REM Set VLC plugin path
if exist "%VLC_BIN%\plugins" (
    set "VLC_PLUGIN_PATH=%VLC_BIN%\plugins"
) else if exist "%OUTPUT_DIR%\lib\vlc\plugins" (
    set "VLC_PLUGIN_PATH=%OUTPUT_DIR%\lib\vlc\plugins"
)

REM Set Qt plugin path if Qt plugins exist alongside VLC
if exist "%VLC_BIN%\plugins\platforms\qwindows.dll" (
    set "QT_PLUGIN_PATH=%VLC_BIN%\plugins"
)

REM Launch VLC with all passed arguments
echo Starting VLC from %VLC_BIN%...
"%VLC_BIN%\vlc.exe" %*

# download-vlc-libs.ps1
# Downloads and extracts VLC 3.0.x Windows 64-bit libraries for bundling with LuminaPlayer.
# These files are NOT committed to git.

param(
    [string]$VlcVersion = "3.0.21",
    [string]$OutputDir = "$PSScriptRoot\..\native\win-x64"
)

$ErrorActionPreference = "Stop"

$zipUrl = "https://get.videolan.org/vlc/$VlcVersion/win64/vlc-$VlcVersion-win64.zip"
$tempDir = "$env:TEMP\vlc-download"
$zipFile = "$tempDir\vlc-$VlcVersion-win64.zip"

function Write-Step($msg) {
    Write-Host "[LuminaPlayer] $msg" -ForegroundColor Cyan
}

# Check if already downloaded
if (Test-Path "$OutputDir\libvlc.dll") {
    Write-Step "VLC libraries already present at $OutputDir"
    Write-Step "Delete the directory to force re-download."
    exit 0
}

Write-Step "Downloading VLC $VlcVersion Windows 64-bit..."

# Create temp directory
if (-not (Test-Path $tempDir)) {
    New-Item -ItemType Directory -Path $tempDir -Force | Out-Null
}

# Download
if (-not (Test-Path $zipFile)) {
    try {
        Invoke-WebRequest -Uri $zipUrl -OutFile $zipFile -UseBasicParsing
    } catch {
        Write-Host "Primary URL failed, trying mirror..." -ForegroundColor Yellow
        $mirrorUrl = "https://mirror.csclub.uwaterloo.ca/vlc/vlc/$VlcVersion/win64/vlc-$VlcVersion-win64.zip"
        Invoke-WebRequest -Uri $mirrorUrl -OutFile $zipFile -UseBasicParsing
    }
}

Write-Step "Extracting VLC libraries..."

# Extract
$extractDir = "$tempDir\vlc-$VlcVersion"
if (-not (Test-Path $extractDir)) {
    Expand-Archive -Path $zipFile -DestinationPath $tempDir -Force
}

# Find the VLC directory (may have different naming)
$vlcDir = Get-ChildItem -Path $tempDir -Directory -Filter "vlc-*" | Select-Object -First 1
if (-not $vlcDir) {
    Write-Error "Could not find extracted VLC directory"
    exit 1
}

# Create output directory
if (-not (Test-Path $OutputDir)) {
    New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
}

Write-Step "Copying libraries to $OutputDir..."

# Copy required files
Copy-Item "$($vlcDir.FullName)\libvlc.dll" "$OutputDir\" -Force
Copy-Item "$($vlcDir.FullName)\libvlccore.dll" "$OutputDir\" -Force

# Copy plugins directory
if (Test-Path "$OutputDir\plugins") {
    Remove-Item "$OutputDir\plugins" -Recurse -Force
}
Copy-Item "$($vlcDir.FullName)\plugins" "$OutputDir\plugins" -Recurse -Force

# Copy additional DLLs that VLC depends on
$additionalDlls = @("axvlc.dll", "npvlc.dll")
foreach ($dll in $additionalDlls) {
    $dllPath = "$($vlcDir.FullName)\$dll"
    if (Test-Path $dllPath) {
        Copy-Item $dllPath "$OutputDir\" -Force
    }
}

Write-Step "Cleaning up temp files..."
Remove-Item $tempDir -Recurse -Force -ErrorAction SilentlyContinue

Write-Step "VLC $VlcVersion libraries installed to: $OutputDir"
Write-Step "Files:"
Get-ChildItem $OutputDir -File | ForEach-Object { Write-Host "  $($_.Name)" }
$pluginCount = (Get-ChildItem "$OutputDir\plugins" -Recurse -File).Count
Write-Step "Plugins: $pluginCount files"

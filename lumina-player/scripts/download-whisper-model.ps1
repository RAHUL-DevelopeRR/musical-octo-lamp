# download-whisper-model.ps1
# Downloads a Whisper GGML model for offline AI subtitle generation.
# Models are stored in the 'models' directory next to the application.

param(
    [ValidateSet("tiny", "base", "small", "medium", "large-v3")]
    [string]$Model = "base",
    [string]$OutputDir = "$PSScriptRoot\..\..\models"
)

$ErrorActionPreference = "Stop"

$modelFiles = @{
    "tiny"     = "ggml-tiny.bin"
    "base"     = "ggml-base.bin"
    "small"    = "ggml-small.bin"
    "medium"   = "ggml-medium.bin"
    "large-v3" = "ggml-large-v3.bin"
}

$baseUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"

function Write-Step($msg) {
    Write-Host "[LuminaPlayer] $msg" -ForegroundColor Cyan
}

$fileName = $modelFiles[$Model]
if (-not $fileName) {
    Write-Error "Unknown model: $Model"
    exit 1
}

$outputFile = Join-Path $OutputDir $fileName

# Create output directory
if (-not (Test-Path $OutputDir)) {
    New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
}

# Check if already downloaded
if (Test-Path $outputFile) {
    Write-Step "Model '$fileName' already exists at $outputFile"
    Write-Step "Delete the file to force re-download."
    exit 0
}

$downloadUrl = "$baseUrl/$fileName"
Write-Step "Downloading Whisper model: $Model ($fileName)"
Write-Step "URL: $downloadUrl"
Write-Step "Destination: $outputFile"
Write-Step ""
Write-Step "This may take a while depending on model size..."

try {
    $ProgressPreference = 'SilentlyContinue'
    Invoke-WebRequest -Uri $downloadUrl -OutFile $outputFile -UseBasicParsing
    $ProgressPreference = 'Continue'
} catch {
    Write-Error "Failed to download model: $_"
    if (Test-Path $outputFile) {
        Remove-Item $outputFile -Force
    }
    exit 1
}

$fileSize = (Get-Item $outputFile).Length
$fileSizeMB = [math]::Round($fileSize / 1MB, 1)
Write-Step "Download complete: $fileName ($fileSizeMB MB)"
Write-Step "Model stored at: $outputFile"

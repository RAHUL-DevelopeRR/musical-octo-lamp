param(
    [string]$AppVersion = "1.0.0",
    [string]$Vendor = "LuminaPlayer",
    [string]$Dest = "target/dist"
)

$ErrorActionPreference = "Stop"

$projectRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $projectRoot

Write-Host "[1/4] Building jar + runtime dependencies..."
mvn clean package -DskipTests

$targetDir = Join-Path $projectRoot "target"
$inputDir = Join-Path $targetDir "jpackage-input"
if (Test-Path $inputDir) {
    Remove-Item -Recurse -Force $inputDir
}
New-Item -ItemType Directory -Path $inputDir | Out-Null

Write-Host "[2/4] Preparing jpackage input..."
Copy-Item (Join-Path $targetDir "lumina-player-1.0.0-SNAPSHOT.jar") $inputDir
Copy-Item (Join-Path $targetDir "lib") (Join-Path $inputDir "lib") -Recurse

$javaHome = $env:JAVA_HOME
if (-not $javaHome) {
    $javacCmd = Get-Command javac -ErrorAction SilentlyContinue
    if ($javacCmd -and $javacCmd.Source) {
        $binDir = Split-Path -Parent $javacCmd.Source
        $javaHome = Split-Path -Parent $binDir
    }
}

if (-not $javaHome) {
    throw "Could not detect JAVA_HOME. Set JAVA_HOME to a JDK 17+ installation."
}

$jpackage = Join-Path $javaHome "bin\jpackage.exe"
if (-not (Test-Path $jpackage)) {
    throw "jpackage.exe not found in JAVA_HOME\bin. Install JDK 17+ with jpackage."
}

$destDir = Join-Path $projectRoot $Dest
if (-not (Test-Path $destDir)) {
    New-Item -ItemType Directory -Path $destDir | Out-Null
}

Write-Host "[3/4] Packaging Windows EXE installer..."
& $jpackage `
    --type exe `
    --name "LuminaPlayer" `
    --app-version $AppVersion `
    --vendor $Vendor `
    --dest $destDir `
    --input $inputDir `
    --main-jar "lumina-player-1.0.0-SNAPSHOT.jar" `
    --main-class "com.luminaplayer.app.LuminaPlayerApp" `
    --java-options "--add-opens=java.base/java.lang=ALL-UNNAMED" `
    --win-shortcut `
    --win-menu

Write-Host "[4/4] Done. EXE installer created in: $destDir"

$ErrorActionPreference = "Stop"

$projectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$workspaceDir = Split-Path -Parent $projectDir
$toolchainCandidates = @(
    (Join-Path $workspaceDir ".toolchain"),
    (Join-Path $env:USERPROFILE "Desktop\Codex\pixel4xl\.toolchain")
)
$toolchainDir = $toolchainCandidates |
    Where-Object { Test-Path -LiteralPath $_ } |
    Select-Object -First 1

$wrapperBat = Join-Path $projectDir "gradlew.bat"
$bundledGradleBat = if ($toolchainDir) {
    Join-Path $toolchainDir "gradle\gradle-8.10.2\bin\gradle.bat"
}

if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
    $javaHome = $env:JAVA_HOME
} elseif ($toolchainDir) {
    $javaExe = Get-ChildItem (Join-Path $toolchainDir "jdk17") -Recurse -Filter java.exe `
        -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($javaExe) {
        $javaHome = Split-Path -Parent (Split-Path -Parent $javaExe.FullName)
    }
}

if (-not $javaHome) {
    throw "JDK 17 was not found. Set JAVA_HOME before running this script."
}
$env:JAVA_HOME = $javaHome

if (-not $env:ANDROID_HOME -and -not $env:ANDROID_SDK_ROOT) {
    if (-not $toolchainDir) {
        throw "Android SDK was not found. Set ANDROID_HOME or ANDROID_SDK_ROOT."
    }
    $env:ANDROID_HOME = Join-Path $toolchainDir "android-sdk"
}

if ($bundledGradleBat -and (Test-Path -LiteralPath $bundledGradleBat)) {
    $gradleCommand = $bundledGradleBat
} elseif (Test-Path -LiteralPath $wrapperBat) {
    $gradleCommand = $wrapperBat
} else {
    throw "Neither the bundled Gradle runtime nor Gradle Wrapper was found."
}

Push-Location $projectDir
try {
    & $gradleCommand :app:assembleDebug :app:lintDebug --no-daemon
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed with exit code $LASTEXITCODE."
    }
} finally {
    Pop-Location
}

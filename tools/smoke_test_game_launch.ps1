param(
    [string]$GameDir = $env:SSOPTIMIZER_GAME_DIR,
    [int]$TimeoutSec = 15,
    [ValidateSet("launcher", "game")]
    [string]$Mode = "launcher",
    [switch]$SkipInstall
)

$ErrorActionPreference = "Stop"
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)

function Require-GameDir {
    param([string]$Path)

    if ([string]::IsNullOrWhiteSpace($Path)) {
        throw "Missing game directory. Pass -GameDir or set SSOPTIMIZER_GAME_DIR."
    }

    $resolved = (Resolve-Path $Path).Path
    if (-not (Test-Path (Join-Path $resolved "starsector-core"))) {
        throw "Expected Windows Starsector root with starsector-core under: $resolved"
    }
    return $resolved
}

function Invoke-GradleUtf8 {
    param([string[]]$Arguments)

    $repoRoot = Split-Path -Parent $PSScriptRoot
    $argLine = ($Arguments | ForEach-Object {
        if ($_ -match '[\s"]') {
            '"' + ($_ -replace '"', '\"') + '"'
        } else {
            $_
        }
    }) -join ' '

    $command = "chcp 65001>nul & gradlew.bat --console=plain $argLine"
    Push-Location $repoRoot
    try {
        cmd.exe /d /c $command
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle command failed with exit code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }
}

function Resolve-BundledJavaExecutable {
    param([string]$ResolvedGameDir)

    foreach ($candidate in @(
        (Join-Path $ResolvedGameDir "zulu25\bin\java.exe"),
        (Join-Path $ResolvedGameDir "jre\bin\java.exe")
    )) {
        if (Test-Path $candidate) {
            return $candidate
        }
    }

    throw "Missing bundled Java runtime under $ResolvedGameDir"
}

function Rewrite-RuntimePathArgs {
    param([string[]]$InputArgs)

    return $InputArgs | ForEach-Object {
        if ($_.StartsWith("-javaagent:./mods/")) {
            return $_.Replace("-javaagent:./mods/", "-javaagent:../mods/")
        }
        if ($_.StartsWith("-Dcom.fs.starfarer.settings.paths.saves=./")) {
            return $_.Replace("=./", "=../")
        }
        if ($_.StartsWith("-Dcom.fs.starfarer.settings.paths.screenshots=./")) {
            return $_.Replace("=./", "=../")
        }
        if ($_.StartsWith("-Dcom.fs.starfarer.settings.paths.mods=./")) {
            return $_.Replace("=./", "=../")
        }
        return $_
    }
}

function Test-LogPattern {
    param(
        [string[]]$Paths,
        [string]$Pattern
    )

    foreach ($path in $Paths) {
        if (Test-Path $path) {
            if (Select-String -Path $path -Pattern $Pattern -Quiet -Encoding UTF8) {
                return $true
            }
        }
    }
    return $false
}

$gameRoot = Require-GameDir $GameDir
$runtimeDir = Join-Path $gameRoot "starsector-core"
$repoRoot = Split-Path -Parent $PSScriptRoot
$launchConfigPath = Join-Path $repoRoot "launch-config.json"
$javaExe = Resolve-BundledJavaExecutable $gameRoot
$logFile = Join-Path $runtimeDir "starsector.log"
$processLog = Join-Path $gameRoot "ssoptimizer-smoke-process.log"
$processErrLog = Join-Path $gameRoot "ssoptimizer-smoke-process.err.log"
$fatalPattern = 'ClassFormatError|VerifyError|LinkageError|NoSuchMethodError|NoSuchFieldError|A fatal error has been detected by the Java Runtime Environment|EXCEPTION_ACCESS_VIOLATION|FATAL'

Write-Host "=== SSOptimizer Windows Smoke Test ==="
Write-Host "Game dir: $gameRoot"
Write-Host "Timeout:  ${TimeoutSec}s"
Write-Host "Mode:     $Mode"

if (-not $SkipInstall) {
    $gradleArgs = @("-Pstarsector.gameDir=$gameRoot", "-Pstarsector.platform=windows")
    $bundledJdk = Join-Path $gameRoot "zulu25"
    if (Test-Path $bundledJdk) {
        $gradleArgs = @("-Dorg.gradle.java.installations.paths=$bundledJdk") + $gradleArgs
    }
    $gradleArgs += "installDevMod"
    Invoke-GradleUtf8 -Arguments $gradleArgs
}

if (Test-Path $logFile) {
    Clear-Content -Path $logFile -ErrorAction SilentlyContinue
}
if (Test-Path $processLog) {
    Remove-Item $processLog -Force
}
if (Test-Path $processErrLog) {
    Remove-Item $processErrLog -Force
}

$launchConfig = Get-Content $launchConfigPath -Raw -Encoding UTF8 | ConvertFrom-Json
$commonArgs = Rewrite-RuntimePathArgs ([string[]]$launchConfig.jvmArgs.common)
$platformArgs = Rewrite-RuntimePathArgs ([string[]]$launchConfig.jvmArgs.windows)
$classPath = (([string[]]$launchConfig.classpath) | ForEach-Object { Join-Path $runtimeDir $_ }) -join ';'
$javaArgs = @()
$javaArgs += $commonArgs
$javaArgs += $platformArgs
if ($Mode -eq "game") {
    $javaArgs += @(
        "-Dssoptimizer.launcher.autostart=true",
        "-Dssoptimizer.launcher.autostart.res=1920x1080",
        "-Dssoptimizer.launcher.autostart.fullscreen=false",
        "-Dssoptimizer.launcher.autostart.sound=true",
        "-DstartRes=1920x1080",
        "-DstartFS=false",
        "-DstartSound=true"
    )
}
$javaArgs += @("-classpath", $classPath, "com.fs.starfarer.StarfarerLauncher")

$process = Start-Process -FilePath $javaExe -ArgumentList $javaArgs -WorkingDirectory $runtimeDir -PassThru -RedirectStandardOutput $processLog -RedirectStandardError $processErrLog

try {
    for ($elapsed = 1; $elapsed -le $TimeoutSec; $elapsed++) {
        Start-Sleep -Seconds 1

        $logBytes = if (Test-Path $logFile) { (Get-Item $logFile).Length } else { 0 }
        Write-Host "[smoke] elapsed=${elapsed}s/${TimeoutSec}s pid=$($process.Id) log_bytes=$logBytes"

        if ($process.HasExited) {
            Write-Host "Process exited before timeout"
            break
        }

        if (Test-LogPattern -Paths @($logFile, $processLog, $processErrLog) -Pattern $fatalPattern) {
            Write-Host "Fatal marker detected in logs, stopping early"
            break
        }
    }
} finally {
    if (-not $process.HasExited) {
        Stop-Process -Id $process.Id -Force
    }
}

Write-Host ""
Write-Host "=== Log Analysis ==="

$failed = $false
foreach ($pattern in @(
    "ClassFormatError",
    "VerifyError",
    "LinkageError",
    "NoSuchMethodError",
    "NoSuchFieldError",
    "A fatal error has been detected by the Java Runtime Environment",
    "EXCEPTION_ACCESS_VIOLATION",
    "FATAL"
)) {
    if (Test-LogPattern -Paths @($logFile, $processLog, $processErrLog) -Pattern $pattern) {
        Write-Host "FAIL: $pattern found in logs"
        $failed = $true
    }
}

if (Test-LogPattern -Paths @($logFile) -Pattern "\[SSOptimizer\] Agent loaded") {
    Write-Host "OK: Agent loaded successfully"
} else {
    Write-Host "WARN: Agent loaded marker not found in starsector.log"
}

if ($failed) {
    exit 1
}

Write-Host "PASS: smoke test finished without fatal markers"
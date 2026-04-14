param(
    [string]$GameDir = $env:SSOPTIMIZER_GAME_DIR,
    [string]$Task = ':native:compileDebugCpp',
    [string]$Platform = 'windows',
    [string]$JavaInstallationsPath,
    [string[]]$ExtraGradleArgs = @()
)

$ErrorActionPreference = 'Stop'
[Console]::InputEncoding = [System.Text.ASCIIEncoding]::new()
[Console]::OutputEncoding = [System.Text.ASCIIEncoding]::new()

function Resolve-RepoRoot {
    Split-Path -Parent $PSScriptRoot
}

function Resolve-DevCmdPath {
    $vswhereCandidates = @(
        'C:\Program Files (x86)\Microsoft Visual Studio\Installer\vswhere.exe',
        'C:\Program Files\Microsoft Visual Studio\Installer\vswhere.exe'
    )

    foreach ($candidate in $vswhereCandidates) {
        if (-not (Test-Path $candidate)) {
            continue
        }

        $json = & $candidate -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -format json
        if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($json)) {
            continue
        }

        $instances = $json | ConvertFrom-Json
        foreach ($instance in @($instances)) {
            if (-not $instance.installationPath) {
                continue
            }

            $vsDevCmd = Join-Path $instance.installationPath 'Common7\Tools\VsDevCmd.bat'
            if (Test-Path $vsDevCmd) {
                return $vsDevCmd
            }

            $launchDevCmd = Join-Path $instance.installationPath 'Common7\Tools\LaunchDevCmd.bat'
            if (Test-Path $launchDevCmd) {
                return $launchDevCmd
            }
        }
    }

    $fallbacks = @(
        'C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\Common7\Tools\VsDevCmd.bat',
        'C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\Common7\Tools\LaunchDevCmd.bat'
    )
    foreach ($fallback in $fallbacks) {
        if (Test-Path $fallback) {
            return $fallback
        }
    }

    throw 'Unable to locate a Visual Studio developer command script.'
}

function Resolve-GameDir([string]$ConfiguredGameDir) {
    if (-not [string]::IsNullOrWhiteSpace($ConfiguredGameDir)) {
        return (Resolve-Path $ConfiguredGameDir).Path
    }

    $defaultGameDir = 'C:\Data\Games\Starsector098'
    if (Test-Path $defaultGameDir) {
        return $defaultGameDir
    }

    throw 'Missing Starsector game directory. Pass -GameDir or set SSOPTIMIZER_GAME_DIR.'
}

function Resolve-JavaInstallationsPath([string]$ResolvedGameDir, [string]$ConfiguredJavaPath) {
    if (-not [string]::IsNullOrWhiteSpace($ConfiguredJavaPath)) {
        return (Resolve-Path $ConfiguredJavaPath).Path
    }

    $bundledJdk = Join-Path $ResolvedGameDir 'zulu25'
    if (Test-Path $bundledJdk) {
        return $bundledJdk
    }

    return $null
}

function Quote-CmdArgument([string]$Value) {
    if ($null -eq $Value) {
        return '""'
    }
    '"' + ($Value -replace '"', '""') + '"'
}

$repoRoot = Resolve-RepoRoot
$resolvedGameDir = Resolve-GameDir $GameDir
$resolvedJavaPath = Resolve-JavaInstallationsPath $resolvedGameDir $JavaInstallationsPath
$devCmdPath = Resolve-DevCmdPath

$gradleArgs = New-Object System.Collections.Generic.List[string]
$gradleArgs.Add('gradlew.bat')
$gradleArgs.Add('--no-daemon')
$gradleArgs.Add('--console=plain')
if (-not [string]::IsNullOrWhiteSpace($resolvedJavaPath)) {
    $gradleArgs.Add("-Dorg.gradle.java.installations.paths=$resolvedJavaPath")
}
$gradleArgs.Add("-Pstarsector.gameDir=$resolvedGameDir")
$gradleArgs.Add("-Pstarsector.platform=$Platform")
$gradleArgs.Add($Task)
foreach ($arg in $ExtraGradleArgs) {
    $gradleArgs.Add($arg)
}

$tempCmd = Join-Path $env:TEMP 'ssoptimizer-native-build.cmd'
$cmdLines = @(
    '@echo off',
    ('call ' + (Quote-CmdArgument $devCmdPath) + ' -arch=x64 -host_arch=x64 || exit /b 1'),
    'where cl || exit /b 1',
    ('cd /d ' + (Quote-CmdArgument $repoRoot) + ' || exit /b 1'),
    (($gradleArgs | ForEach-Object { Quote-CmdArgument $_ }) -join ' ')
)
[System.IO.File]::WriteAllLines($tempCmd, $cmdLines, [System.Text.ASCIIEncoding]::new())

try {
    Write-Host "[windows_native_build] repoRoot=$repoRoot"
    Write-Host "[windows_native_build] gameDir=$resolvedGameDir"
    Write-Host "[windows_native_build] devCmd=$devCmdPath"
    if ($resolvedJavaPath) {
        Write-Host "[windows_native_build] javaInstallationsPath=$resolvedJavaPath"
    }

    & cmd.exe /d /c $tempCmd
    exit $LASTEXITCODE
} finally {
    Remove-Item $tempCmd -Force -ErrorAction SilentlyContinue
}
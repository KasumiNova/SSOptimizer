param(
    [string]$GameDir,
    [switch]$Restore
)

$ErrorActionPreference = "Stop"
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$agentArg = "-javaagent:..\\mods\\ssoptimizer\\jars\\SSOptimizer.jar"
$runtimeBackupDirName = "jre.ssoptimizer.bak"

function Resolve-GameDir {
    param([string]$Path)

    if (-not [string]::IsNullOrWhiteSpace($Path)) {
        $resolved = (Resolve-Path $Path).Path
        if (-not (Test-Path (Join-Path $resolved "starsector-core"))) {
            throw "Expected Starsector root with starsector-core under: $resolved"
        }
        return $resolved
    }

    $scriptDir = Split-Path -Parent $PSCommandPath
    $candidateDirs = @(
        $scriptDir,
        (Resolve-Path (Join-Path $scriptDir "..\..") -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Path -ErrorAction SilentlyContinue),
        (Resolve-Path (Join-Path $scriptDir "..\..\..") -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Path -ErrorAction SilentlyContinue)
    ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }

    foreach ($candidate in $candidateDirs) {
        if ((Test-Path (Join-Path $candidate "starsector-core")) -and (Test-Path (Join-Path $candidate "vmparams"))) {
            return $candidate
        }
    }

    throw "Unable to locate Starsector root automatically. Pass -GameDir explicitly."
}

function Read-Text {
    param([string]$Path)

    return [System.IO.File]::ReadAllText($Path, [System.Text.UTF8Encoding]::new($false))
}

function Write-Text {
    param(
        [string]$Path,
        [string]$Content
    )

    [System.IO.File]::WriteAllText($Path, $Content, [System.Text.UTF8Encoding]::new($false))
}

function Ensure-ExeRuntimeRedirect {
    param(
        [string]$ResolvedGameDir
    )

    $zuluDir = Join-Path $ResolvedGameDir "zulu25"
    $jreDir = Join-Path $ResolvedGameDir "jre"
    $runtimeBackupDir = Join-Path $ResolvedGameDir $runtimeBackupDirName

    if (-not (Test-Path (Join-Path $zuluDir "bin\java.exe"))) {
        throw "Missing Zulu 25 runtime under $zuluDir"
    }

    if (Test-Path $jreDir) {
        $item = Get-Item $jreDir -Force
        if ($item.LinkType -eq "Junction") {
            $target = [string]$item.Target
            if ($target -eq $zuluDir) {
                return "jre already redirected to zulu25"
            }
            throw "Existing jre junction points to unexpected target: $target"
        }

        if (-not (Test-Path $runtimeBackupDir)) {
            Move-Item $jreDir $runtimeBackupDir
        } else {
            Remove-Item $jreDir -Recurse -Force
        }
    }

    New-Item -ItemType Junction -Path $jreDir -Target $zuluDir | Out-Null
    return "jre redirected to zulu25"
}

function Restore-ExeRuntimeRedirect {
    param(
        [string]$ResolvedGameDir
    )

    $zuluDir = Join-Path $ResolvedGameDir "zulu25"
    $jreDir = Join-Path $ResolvedGameDir "jre"
    $runtimeBackupDir = Join-Path $ResolvedGameDir $runtimeBackupDirName

    if (Test-Path $jreDir) {
        $item = Get-Item $jreDir -Force
        if ($item.LinkType -eq "Junction") {
            $target = [string]$item.Target
            if ($target -eq $zuluDir) {
                Remove-Item $jreDir -Force
            }
        }
    }

    if (Test-Path $runtimeBackupDir) {
        Move-Item $runtimeBackupDir $jreDir -Force
        return "restored original jre directory"
    }

    return "no jre runtime backup found"
}

function Ensure-AgentArg {
    param(
        [string]$Content
    )

    if ($Content.Contains($agentArg)) {
        return $Content
    }

    $classpathToken = " -classpath "
    if ($Content.Contains($classpathToken)) {
        return $Content.Replace($classpathToken, " $agentArg -classpath ")
    }

    return ($Content.TrimEnd() + " " + $agentArg).Trim()
}

function Remove-AgentArg {
    param(
        [string]$Content
    )

    if (-not $Content.Contains($agentArg)) {
        return $Content
    }

    $updated = $Content.Replace(" $agentArg", "").Replace("$agentArg ", "").Replace($agentArg, "")
    return $updated.Trim()
}

function Normalize-JavaCommand {
    param(
        [string]$Content
    )

    $updated = [System.Text.RegularExpressions.Regex]::Replace(
        $Content,
        '^(?:\.\\)?(?:zulu25|jre)\\bin\\java\.exe\b|^\.\.\\jre\\bin\\java\.exe\b',
        'java.exe',
        [System.Text.RegularExpressions.RegexOptions]::IgnoreCase
    )

    if ($updated -eq $Content) {
        if ($Content.StartsWith('java.exe', [System.StringComparison]::OrdinalIgnoreCase)) {
            return $Content
        }
        $firstSpace = $Content.IndexOf(' ')
        if ($firstSpace -lt 0) {
            return 'java.exe'
        }
        return 'java.exe' + $Content.Substring($firstSpace)
    }

    return $updated
}

$gameRoot = Resolve-GameDir $GameDir
$vmparamsPath = Join-Path $gameRoot "vmparams"
$backupPath = Join-Path $gameRoot "vmparams.ssoptimizer.bak"
$modJarPath = Join-Path $gameRoot "mods\ssoptimizer\jars\SSOptimizer.jar"

if (-not (Test-Path $vmparamsPath)) {
    throw "Missing vmparams at $vmparamsPath"
}

if ($Restore) {
    $runtimeRestoreMessage = Restore-ExeRuntimeRedirect -ResolvedGameDir $gameRoot

    if (Test-Path $backupPath) {
        Copy-Item $backupPath $vmparamsPath -Force
        Write-Host "Restored vmparams from $backupPath"
        Write-Host $runtimeRestoreMessage
        exit 0
    }

    $current = Read-Text $vmparamsPath
    $updated = Normalize-JavaCommand -Content (Remove-AgentArg -Content $current)
    if ($updated -ne $current) {
        Write-Text $vmparamsPath $updated
        Write-Host "Removed SSOptimizer javaagent from vmparams"
        Write-Host $runtimeRestoreMessage
        exit 0
    }

    Write-Host "No SSOptimizer vmparams backup found, and no agent arg present."
    Write-Host $runtimeRestoreMessage
    exit 0
}

if (-not (Test-Path $modJarPath)) {
    throw "Missing mod jar: $modJarPath. Extract SSOptimizer into mods/ssoptimizer first."
}

$content = Read-Text $vmparamsPath
$updated = Ensure-AgentArg -Content (Normalize-JavaCommand -Content $content)
$runtimeMessage = Ensure-ExeRuntimeRedirect -ResolvedGameDir $gameRoot

if ($updated -eq $content) {
    Write-Host "vmparams already contains the SSOptimizer javaagent."
    Write-Host $runtimeMessage
    Write-Host "You can now launch the game with starsector.exe"
    exit 0
}

if (-not (Test-Path $backupPath)) {
    Copy-Item $vmparamsPath $backupPath -Force
    Write-Host "Backed up vmparams to $backupPath"
}

Write-Text $vmparamsPath $updated
Write-Host "Patched vmparams for SSOptimizer."
Write-Host $runtimeMessage
Write-Host "Launch path: starsector.exe"
Write-Host "Restore command: powershell -ExecutionPolicy Bypass -File .\\mods\\ssoptimizer\\enable_starsector_exe_launch.ps1 -GameDir `"$gameRoot`" -Restore"
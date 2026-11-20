#requires -Version 5.1
[CmdletBinding()]
param([ValidateSet('Install','Launch','Prepare','Config','Menu')][string]$Action = 'Menu', [string]$Game, [string[]]$GameArgs = @())
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$flavor = '@FLAVOR@'

function Assert-DirectoryPath([string]$path) {
    for ($dir = [IO.DirectoryInfo]::new($path); $dir; $dir = $dir.Parent) {
        if ($dir.Exists -and ($dir.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw "目录经过链接或重解析点：$($dir.FullName)" }
    }
}

function Read-SignedRelease([string]$source) {
    $bytes = [IO.File]::ReadAllBytes((Join-Path $source 'release.manifest'))
    $sig = [IO.File]::ReadAllBytes((Join-Path $source 'release.sig'))
    $rsa = [Security.Cryptography.RSA]::Create()
    try {
        $key = New-Object Security.Cryptography.RSAParameters
        $key.Modulus = [Convert]::FromBase64String('@MODULUS@')
        $key.Exponent = [Convert]::FromBase64String('@EXPONENT@')
        $rsa.ImportParameters($key)
        if (!$rsa.VerifyData($bytes, $sig, [Security.Cryptography.HashAlgorithmName]::SHA256, [Security.Cryptography.RSASignaturePadding]::Pkcs1)) { throw '发行签名无效。' }
    } finally { $rsa.Dispose() }
    $lines = [Text.UTF8Encoding]::new($false, $true).GetString($bytes).Split("`n")
    if ($lines[0] -cne 'SSOptimizer-Windows-x64-v2' -or $lines[2] -cne $flavor) { throw '发行清单格式或发行类型不匹配。' }
    $id = $lines[1]
    if ($id -cnotmatch '\A[A-Za-z0-9][A-Za-z0-9._+-]{0,63}\z' -or $id.EndsWith('.')) { throw "发行版本无效：$id" }
    return @{ Id = $id; Signature = $sig; Entries = $lines[3..($lines.Count - 1)] }
}

function Copy-SignedFiles([string]$source, [string]$stage, $entries) {
    foreach ($entry in $entries) {
        if (!$entry) { continue }
        if ($entry -cnotmatch '\A([0-9a-f]{64})  ([A-Za-z0-9_.+/-]+)\z') { throw "无效的签名条目：$entry" }
        $digest = $Matches[1]; $name = $Matches[2]
        foreach ($part in $name.Split('/')) {
            if (!$part -or $part.EndsWith('.') -or $part -match '^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\.|$)') { throw "无效的安装路径：$name" }
        }
        $dest = Join-Path $stage $name
        $null = [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($dest))
        [IO.File]::Copy((Join-Path $source $name), $dest, $false)
        if ((Get-FileHash -LiteralPath $dest -Algorithm SHA256).Hash -ine $digest) { throw "文件与发行签名不符：$name" }
    }
}

function Install-Bootstrap([string]$gameDir) {
    $mods = Join-Path $gameDir 'mods'
    $root = Join-Path $gameDir 'ssoptimizer-bootstrap'
    Assert-DirectoryPath $mods; Assert-DirectoryPath $root
    if (![IO.Directory]::Exists($mods)) { throw "缺少游戏模组目录：$mods" }
    $null = [IO.Directory]::CreateDirectory($root)
    $lock = [IO.File]::Open((Join-Path $root 'install.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
    try {
        $link = Join-Path $mods 'ssoptimizer'
        $existing = Get-Item -LiteralPath $link -Force -ErrorAction SilentlyContinue
        if ($existing) {
            if ($existing.LinkType -ne 'Junction') { throw "请先将现有 SSOptimizer 模组移出：$link" }
            $old = [IO.Path]::GetFullPath($existing.Target[0]); $parent = [IO.Directory]::GetParent($old).Parent
            if ($parent.FullName -ine $root -or [IO.Path]::GetFileName($old) -ine 'mod') { throw "请先移出现有模组链接：$link" }
        }
        $source = Split-Path -Parent $PSScriptRoot
        $manifest = Read-SignedRelease $source
        $id = $manifest.Id
        $release = Join-Path $root "$flavor-$id"
        $target = Join-Path $release 'mod'
        $work = Join-Path $root "work-$flavor"
        $workLink = Join-Path $release 'work'
        $receipt = 'release.sig'
        $token = [Guid]::NewGuid().ToString('N')
        $stage = Join-Path $root ".install-$token"
        $next = Join-Path $root ".next-$token"
        $previous = Join-Path $root ".previous-$token"
        $newRelease = $false
        $activated = $false
        $oldMoved = $false
        try {
            if (Test-Path -LiteralPath $release) {
                Assert-DirectoryPath $target
                $installed = [IO.File]::ReadAllBytes((Join-Path $release $receipt))
                if ([Convert]::ToBase64String($installed) -cne [Convert]::ToBase64String($manifest.Signature)) { throw "已安装版本与所选发行包不同：$release" }
                if ($existing -and $old -ieq $target) { Write-Host "版本已启用：$release"; return }
            } else {
                $null = [IO.Directory]::CreateDirectory($stage)
                Copy-SignedFiles $source $stage $manifest.Entries
                [IO.File]::WriteAllBytes((Join-Path $stage $receipt), $manifest.Signature)
                [IO.Directory]::Move($stage, $release)
                $newRelease = $true
            }
            if (!(Test-Path -LiteralPath $workLink)) {
                Assert-DirectoryPath $work
                $null = [IO.Directory]::CreateDirectory($work)
                $null = New-Item -ItemType Junction -Path $workLink -Target $work
            }
            $null = New-Item -ItemType Junction -Path $next -Target $target
            if ($existing) { [IO.Directory]::Move($link, $previous); $oldMoved = $true }
            [IO.Directory]::Move($next, $link)
            $activated = $true
        } finally {
            if ($oldMoved) {
                if ($activated) { [IO.Directory]::Delete($previous) }
                else { [IO.Directory]::Move($previous, $link) }
            }
            if ([IO.Directory]::Exists($next)) { [IO.Directory]::Delete($next) }
            if ($newRelease -and !$activated) {
                if ([IO.Directory]::Exists($workLink)) { [IO.Directory]::Delete($workLink) }
                [IO.Directory]::Delete($release, $true)
            }
            if ([IO.Directory]::Exists($stage)) { [IO.Directory]::Delete($stage, $true) }
        }
        Write-Host "已安装 $flavor $id。启动入口：$link\Launch.cmd"
    } finally { $lock.Dispose() }
}

try {
    if ($Action -eq 'Install') {
        if (!$Game) {
            Add-Type -AssemblyName System.Windows.Forms
            $dialog = New-Object Windows.Forms.FolderBrowserDialog
            try {
                $dialog.Description = '选择已有游戏根目录'; $dialog.ShowNewFolderButton = $false
                if ($dialog.ShowDialog() -ne [Windows.Forms.DialogResult]::OK) { exit 0 }
                $Game = $dialog.SelectedPath
            } finally { $dialog.Dispose() }
        }
        Install-Bootstrap ([IO.Path]::GetFullPath($Game)); exit 0
    }
    $mod = Get-Item -LiteralPath $PSScriptRoot
    if ($mod.LinkType -eq 'Junction') { $mod = Get-Item -LiteralPath $mod.Target[0] }
    $release = $mod.Parent.FullName
    if (!$Game) { $Game = $mod.Parent.Parent.Parent.FullName }
    if ($Action -eq 'Menu') {
        $choice = Read-Host '1 启动   2 准备缓存   3 编辑启动参数'
        $Action = @{'1'='Launch'; '2'='Prepare'; '3'='Config'}[$choice]
        if (!$Action) { exit 0 }
    }
    $cliArgs = @('-cp', "$release\agent.jar;$release\lib\*", 'ssoptimizer.standalone.launch.Main', $Action.ToLowerInvariant(), '--game', $Game, '--home', $release)
    if ($GameArgs.Count) { $cliArgs += '--'; $cliArgs += $GameArgs }
    & (Join-Path $release 'runtime/bin/java.exe') @cliArgs
    $code = $LASTEXITCODE
    if ($Action -eq 'Config' -and $code -eq 0) { & notepad.exe (Join-Path $release 'work/jvm.args') }
    exit $code
} catch { [Console]::Error.WriteLine("SSOptimizer：$_"); exit 1 }

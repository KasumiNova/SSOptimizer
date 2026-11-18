#requires -Version 5.1
[CmdletBinding()]
param(
    [ValidateSet('Install','Update','Select','Launch','Doctor','Prepare','Import-Save','Uninstall','Menu')][string]$Action = 'Menu',
    [string]$Game, [string]$Bundle, [string]$Version, [string[]]$GameArgs = @(), [switch]$NoShortcut,
    [string]$Source, [string]$ConfirmSource, [string]$Slot
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$utf8 = New-Object Text.UTF8Encoding($false, $true)
$toolNames = @('Launch.cmd', 'Manage.cmd', 'Manage.ps1')

function Pick-Folder([string]$prompt) {
    Add-Type -AssemblyName System.Windows.Forms
    $dialog = New-Object Windows.Forms.FolderBrowserDialog
    try {
        $dialog.Description = $prompt; $dialog.ShowNewFolderButton = $false
        if ($dialog.ShowDialog() -ne [Windows.Forms.DialogResult]::OK) { throw '已取消。' }
        return $dialog.SelectedPath
    } finally { $dialog.Dispose() }
}
function Assert-Plain([string]$path) {
    if (([IO.File]::GetAttributes($path) -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw "拒绝访问链接或重解析点：$path" }
}
function Assert-Chain([string]$path) {
    for ($p = [IO.Path]::GetFullPath($path); $p; $p = [IO.Path]::GetDirectoryName($p)) { Assert-Plain $p }
}
function Assert-Id([string]$id) {
    if ($id -cnotmatch '\A[A-Za-z0-9][A-Za-z0-9._+-]{0,63}\z' -or $id.EndsWith('.') -or $id -match '^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\.|$)') { throw "非法版本号：$id" }
}
function Assert-Relative([string]$name) {
    foreach ($part in $name.Split('/')) {
        if ($part -cnotmatch '\A[A-Za-z0-9_+.-]+\z' -or $part.EndsWith('.') -or $part -match '^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\.|$)') { throw "非法清单路径：$name" }
    }
}
function File-Hash([string]$path) {
    Assert-Plain $path
    return (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
}
function Read-Manifest([string]$root, [string]$prefix = 'release') {
    Assert-Chain $root
    $mf = Join-Path $root "$prefix.manifest"; $sf = Join-Path $root "$prefix.sig"
    Assert-Plain $mf; Assert-Plain $sf
    $bytes = [IO.File]::ReadAllBytes($mf); $signature = [IO.File]::ReadAllBytes($sf)
    $rsa = [Security.Cryptography.RSA]::Create()
    try {
        $key = New-Object Security.Cryptography.RSAParameters
        $key.Modulus = [Convert]::FromBase64String('@MODULUS@'); $key.Exponent = [Convert]::FromBase64String('@EXPONENT@')
        $rsa.ImportParameters($key)
        if (!$rsa.VerifyData($bytes, $signature, [Security.Cryptography.HashAlgorithmName]::SHA256, [Security.Cryptography.RSASignaturePadding]::Pkcs1)) { throw "发行清单签名无效：$mf" }
    } finally { $rsa.Dispose() }
    $lines = $utf8.GetString($bytes).Split("`n")
    if ($lines.Count -lt 4 -or $lines[0] -cne 'SSOptimizer-Windows-x64-v1' -or $lines[-1] -cne '') { throw "签名清单格式错误：$mf" }
    Assert-Id $lines[1]
    $files = @{}
    for ($i = 2; $i -lt $lines.Count - 1; $i++) {
        if ($lines[$i] -cnotmatch '\A([0-9a-f]{64})  (.+)\z') { throw "清单第 $($i+1) 行格式错误" }
        $name = $Matches[2]; $hash = $Matches[1]; Assert-Relative $name
        if ($files.ContainsKey($name) -or $name -in @('release.sig','release.manifest')) { throw "重复清单路径：$name" }
        $files[$name] = $hash
    }
    foreach ($name in @('agent.jar','payload.jar','runtime/bin/java.exe','mappings/game-full.tiny.gz','mod/mod_info.json','tools/Manage.ps1','tools/Manage.cmd','tools/Launch.cmd')) {
        if (!$files.ContainsKey($name)) { throw "清单缺失：$name" }
    }
    return @{ Id = $lines[1]; Files = $files; Manifest = $bytes; Signature = $signature }
}
function Visit-Files([string]$root, [string]$prefix, [hashtable]$seen) {
    foreach ($item in [IO.Directory]::EnumerateFileSystemEntries($root)) {
        Assert-Plain $item
        $name = $prefix + [IO.Path]::GetFileName($item)
        if ([IO.Directory]::Exists($item)) { Visit-Files $item ($name + '/') $seen }
        else { $seen.Add($name, $item) }
    }
}
function Verify-Version([string]$root, [string]$id = '') {
    $info = Read-Manifest $root
    if ($id -and $id -cne $info.Id) { throw "版本目录与签名 ID 不符：$root" }
    $seen = @{}; Visit-Files $root '' $seen
    $seen.Remove('release.manifest'); $seen.Remove('release.sig')
    foreach ($name in $seen.Keys) {
        if (!$info.Files.ContainsKey($name)) { throw "版本中含清单外文件，请先移至别处：$($seen[$name])" }
        if ((File-Hash $seen[$name]) -cne $info.Files[$name]) { throw "文件 SHA-256 不符：$($seen[$name])" }
    }
    if ($seen.Count -ne $info.Files.Count) { throw "版本文件不完整：$root" }
    return $info
}
function Assert-GameLayout([string]$root) {
    $core = Join-Path $root 'starsector-core'
    foreach ($name in @('starfarer_obf.jar','starfarer.api.jar','fs.common_obf.jar','fs.sound_obf.jar','data/config/settings.json')) {
        $path = Join-Path $core $name
        if (![IO.File]::Exists($path)) { throw "缺少 $path。请选择包含 starsector-core 的已有游戏根目录。游戏安装指引：https://fractalsoftworks.com/preorder/" }
    }
}
function Get-IdFile([string]$p) {
    Assert-Plain $p
    $id = [IO.File]::ReadAllText($p, $utf8).TrimEnd("`r", "`n"); Assert-Id $id; return $id
}
function Set-IdFile([string]$p, [string]$id) {
    Assert-Id $id
    $tmp = Join-Path ([IO.Path]::GetDirectoryName($p)) ('.current-' + [Guid]::NewGuid().ToString('N'))
    try {
        [IO.File]::WriteAllText($tmp, "$id`n", $utf8)
        if ([IO.File]::Exists($p)) { $null = Get-IdFile $p; [IO.File]::Replace($tmp, $p, [System.Management.Automation.Language.NullString]::Value) }
        else { [IO.File]::Move($tmp, $p) }
    } finally { if ([IO.File]::Exists($tmp)) { [IO.File]::Delete($tmp) } }
}
function Get-Current([string]$root) { return Get-IdFile (Join-Path $root 'current') }
function Set-Current([string]$root, [string]$id) { Set-IdFile (Join-Path $root 'current') $id }
function Get-InstalledManifest([string]$root, [string]$id) {
    Assert-Id $id; $info = Read-Manifest (Join-Path (Join-Path $root 'versions') $id)
    if ($info.Id -cne $id) { throw "入口所属的版本目录与清单不符：$id" }; return $info
}
function Sync-Tools([string]$root, $active, $next) {
    $receipt = Join-Path $root 'bootstrap'; $pending = Join-Path $receipt 'pending-version'
    $dir = Join-Path (Join-Path (Join-Path $root 'versions') $next.Id) 'tools'; Assert-Chain $dir
    foreach ($name in $toolNames) {
        $target = Join-Path $root $name
        if ([IO.File]::Exists($target)) {
            $actual = File-Hash $target
            if ($actual -cne $active.Files["tools/$name"] -and $actual -cne $next.Files["tools/$name"]) { throw "自有入口已被修改，请先备份并移出：$target" }
        } elseif (Test-Path -LiteralPath $target) { throw "入口路径已被占用：$target" }
        if ((File-Hash (Join-Path $dir $name)) -cne $next.Files["tools/$name"]) { throw "新入口摘要不符：$dir/$name" }
    }
    Set-IdFile $pending $next.Id
    foreach ($name in $toolNames) {
        $target = Join-Path $root $name
        if ([IO.File]::Exists($target) -and (File-Hash $target) -ceq $next.Files["tools/$name"]) { continue }
        $tmp = Join-Path $root ('.tool-' + [Guid]::NewGuid().ToString('N'))
        try {
            [IO.File]::Copy((Join-Path $dir $name), $tmp, $false)
            if ((File-Hash $tmp) -cne $next.Files["tools/$name"]) { throw "复制过程中入口已改变：$name" }
            if ([IO.File]::Exists($target)) { [IO.File]::Replace($tmp, $target, [System.Management.Automation.Language.NullString]::Value) }
            else { [IO.File]::Move($tmp, $target) }
        } finally { if ([IO.File]::Exists($tmp)) { [IO.File]::Delete($tmp) } }
    }
    Set-IdFile (Join-Path $receipt 'entry-version') $next.Id
    [IO.File]::Delete($pending)
}
function Write-Receipt([string]$root, $info, [string]$prefix) {
    [IO.File]::WriteAllBytes((Join-Path $root "$prefix.manifest"), $info.Manifest)
    [IO.File]::WriteAllBytes((Join-Path $root "$prefix.sig"), $info.Signature)
}
function Check-Owner([string]$root) {
    $null = Read-Manifest $root 'owner'
    $receipt = Join-Path $root 'bootstrap'
    if (![IO.Directory]::Exists($receipt)) {
        foreach ($name in ($toolNames + @('current'))) { if (Test-Path -LiteralPath (Join-Path $root $name)) { throw "入口缺少所属的签名清单：$root" } }
        return $null
    }
    $active = Read-Manifest $receipt
    $pointer = Join-Path $receipt 'entry-version'; $pending = Join-Path $receipt 'pending-version'
    if ([IO.File]::Exists($pointer)) { $active = Get-InstalledManifest $root (Get-IdFile $pointer) }
    if ([IO.File]::Exists($pending)) {
        $next = Get-InstalledManifest $root (Get-IdFile $pending); Sync-Tools $root $active $next; $active = $next
    }
    foreach ($name in $toolNames) {
        $p = Join-Path $root $name
        if ([IO.File]::Exists($p) -and (File-Hash $p) -cne $active.Files["tools/$name"]) { throw "入口与已安装管理器的签名不符：$p" }
    }
    return $active
}
function Ensure-Dir([string]$path) {
    if ([IO.Directory]::Exists($path)) { Assert-Chain $path; return }
    Ensure-Dir ([IO.Path]::GetDirectoryName($path))
    $null = [IO.Directory]::CreateDirectory($path); Assert-Plain $path
}
function Copy-Version([string]$source, [string]$dest, $info) {
    foreach ($name in $info.Files.Keys) {
        $target = Join-Path $dest $name; $origin = Join-Path $source $name
        Ensure-Dir ([IO.Path]::GetDirectoryName($target))
        [IO.File]::Copy($origin, $target, $false)
    }
    Write-Receipt $dest $info 'release'; $null = Verify-Version $dest $info.Id
}
function Remove-Empty([string]$root) {
    Assert-Plain $root
    if (@([IO.Directory]::EnumerateFileSystemEntries($root)).Count -eq 0) { [IO.Directory]::Delete($root, $false) }
}
function Delete-Version([string]$root, $info) {
    $dirs = @{}
    foreach ($name in $info.Files.Keys) {
        $p = Join-Path $root $name; Assert-Chain $p; [IO.File]::Delete($p)
        for ($d = [IO.Path]::GetDirectoryName($p); $d -ne $root; $d = [IO.Path]::GetDirectoryName($d)) { $dirs[$d] = $true }
    }
    foreach ($d in @($dirs.Keys | Sort-Object Length -Descending)) { Remove-Empty $d }
    [IO.File]::Delete((Join-Path $root 'release.manifest')); [IO.File]::Delete((Join-Path $root 'release.sig')); Remove-Empty $root
}
function Assert-JdkLaunch([string]$dir) {
    $java = Join-Path $dir 'runtime/bin/java.exe'
    try {
        $start = New-Object Diagnostics.ProcessStartInfo
        $start.FileName = $java; $start.Arguments = '--version'; $start.UseShellExecute = $false
        $process = [Diagnostics.Process]::Start($start)
        try {
            if (!$process.WaitForExit(30000)) { $process.Kill(); $process.WaitForExit(); throw 'java.exe 在 30 秒内未返回' }
            if ($process.ExitCode -ne 0) { throw "java.exe 退出码：$($process.ExitCode)" }
        } finally { $process.Dispose() }
    } catch {
        throw "固定 JDK 在此路径启动失败：$java。请将游戏安装到当前用户可写的较短路径（例如 D:\Games\Starsector），再双击 Install.cmd。原有实例与当前版本保留。原因：$_"
    }
}
function Install-Version([string]$source, [string]$root, [bool]$fresh) {
    $info = Verify-Version $source
    if ($fresh -and (File-Hash $PSCommandPath) -cne $info.Files['tools/Manage.ps1']) { throw '初次安装入口与已签名清单不符。' }
    $versions = Join-Path $root 'versions'; Ensure-Dir $versions
    $dest = Join-Path $versions $info.Id
    if ([IO.Directory]::Exists($dest)) {
        $old = Verify-Version $dest $info.Id
        if ([Convert]::ToBase64String($old.Manifest) -cne [Convert]::ToBase64String($info.Manifest)) { throw "版本号 $($info.Id) 已由不同的签名内容使用。" }
        Assert-JdkLaunch $dest
    } else {
        $stage = Join-Path $versions ('.install-' + [Guid]::NewGuid().ToString('N')); Ensure-Dir $stage
        try { Copy-Version $source $stage $info }
        catch { Write-Warning "新版本未启用，保留未完成的复制目录：$stage"; throw }
        try { Assert-JdkLaunch $stage } catch {
            $checked = Verify-Version $stage $info.Id; Delete-Version $stage $checked
            if ($fresh) { Remove-Empty $versions; Remove-Empty $root }
            throw
        }
        [IO.Directory]::Move($stage, $dest)
    }
    if ($fresh) { Write-Receipt $root $info 'owner' }
    $owner = Check-Owner $root
    if (!$owner) {
        $receipt = Join-Path $root 'bootstrap'; $stageReceipt = Join-Path $root ('.bootstrap-' + [Guid]::NewGuid().ToString('N'))
        Ensure-Dir $stageReceipt; Write-Receipt $stageReceipt $info 'release'; [IO.Directory]::Move($stageReceipt, $receipt)
        $owner = $info
    }
    Sync-Tools $root $owner $info
    Ensure-Dir (Join-Path $root 'instance'); Set-Current $root $info.Id
    Write-Host "已启用 $($info.Id)"
}
function Select-Version([string]$root, [string]$id) {
    $versions = Join-Path $root 'versions'; Assert-Chain $versions
    if (!$id) {
        $ids = @([IO.Directory]::EnumerateDirectories($versions) | ForEach-Object { [IO.Path]::GetFileName($_) } | Where-Object { !($_.StartsWith('.')) } | Sort-Object)
        Write-Host ('已安装：' + ($ids -join ', ')); $id = Read-Host '输入需要启用的版本号'
    }
    Assert-Id $id; $null = Verify-Version (Join-Path $versions $id) $id; Set-Current $root $id; Write-Host "已切换到 $id；实例数据保留。"
}
function Remove-Install([string]$root) {
    $owner = Check-Owner $root; $versions = Join-Path $root 'versions'; Assert-Chain $versions
    $verified = @{}
    foreach ($dir in [IO.Directory]::EnumerateDirectories($versions)) {
        $id = [IO.Path]::GetFileName($dir)
        if ($id.StartsWith('.')) { Write-Warning "保留未完成安装目录：$dir"; continue }
        Assert-Id $id; $verified[$dir] = Verify-Version $dir $id
        $exclusive = [IO.File]::Open((Join-Path $dir 'release.sig'), 'Open', 'Read', 'None'); $exclusive.Dispose()
        foreach ($file in $verified[$dir].Files.Keys) {
            $exclusive = [IO.File]::Open((Join-Path $dir $file), 'Open', 'Read', 'None'); $exclusive.Dispose()
        }
    }
    if ([IO.File]::Exists((Join-Path $root 'current'))) { $null = Get-Current $root }
    foreach ($dir in $verified.Keys) { Delete-Version $dir $verified[$dir] }
    Remove-Empty $versions; [IO.File]::Delete((Join-Path $root 'current'))
    foreach ($name in $toolNames) { [IO.File]::Delete((Join-Path $root $name)) }
    $receipt = Join-Path $root 'bootstrap'; [IO.File]::Delete((Join-Path $receipt 'entry-version')); [IO.File]::Delete((Join-Path $receipt 'release.manifest')); [IO.File]::Delete((Join-Path $receipt 'release.sig')); Remove-Empty $receipt
    Write-Host "已卸载程序，保留实例数据和初次安装的签名凭据：$root"
}
function Desktop-Link([string]$root, [string]$suffix, [bool]$remove) {
    if ($NoShortcut) { return }
    $path = Join-Path ([Environment]::GetFolderPath('DesktopDirectory')) "SSOptimizer $suffix.lnk"
    $target = Join-Path $root 'Launch.cmd'; $receipt = Join-Path $root 'desktop.sha256'
    if ($remove) {
        if (![IO.File]::Exists($receipt)) { return }
        Assert-Plain $receipt; $saved = [IO.File]::ReadAllText($receipt)
        if ($saved -cnotmatch '\A[0-9a-f]{64}\z') { throw "快捷方式归属记录无效：$receipt" }
        if ([IO.File]::Exists($path)) {
            if ((File-Hash $path) -cne $saved) { Write-Warning "保留修改过的快捷方式：$path" }
            else {
                $lnk = (New-Object -ComObject WScript.Shell).CreateShortcut($path)
                if ($lnk.TargetPath -ieq $target -and !$lnk.Arguments -and $lnk.Description -ceq "SSOptimizer $suffix") { [IO.File]::Delete($path) }
            }
        }
        [IO.File]::Delete($receipt)
    } elseif (!(Test-Path -LiteralPath $path) -and !(Test-Path -LiteralPath $receipt)) {
        $lnk = (New-Object -ComObject WScript.Shell).CreateShortcut($path); $lnk.TargetPath = $target; $lnk.WorkingDirectory = $root; $lnk.Description = "SSOptimizer $suffix"; $lnk.Save()
        $digest = $utf8.GetBytes((File-Hash $path)); $out = [IO.File]::Open($receipt, 'CreateNew', 'Write', 'None')
        try { $out.Write($digest, 0, $digest.Length) } finally { $out.Dispose() }
    }
}

$mutex = $null; $locked = $false; $lease = $null; $code = 0; $choiceForImport = $Action -eq 'Menu'
try {
    if ($Action -eq 'Install') {
        if (!$Game) { $Game = Pick-Folder '选择已安装的 Windows Starsector 游戏根目录' }
        if (!$Bundle) { $Bundle = Split-Path -Parent (Split-Path -Parent $PSScriptRoot) }
    } elseif (!$Game) { $Game = Split-Path -Parent $PSScriptRoot }
    $Game = [IO.Path]::GetFullPath($Game); $root = Join-Path $Game 'ssoptimizer-standalone'; Assert-Chain $Game
    if ($Action -eq 'Menu') {
        Write-Host '1 启动   2 安装更新   3 切换或回滚版本   4 诊断   5 准备缓存   6 卸载（保留实例）   7 导入旧存档副本'
        $choice = Read-Host '请选择'; $actions = @{'1'='Launch';'2'='Update';'3'='Select';'4'='Doctor';'5'='Prepare';'6'='Uninstall';'7'='Import-Save'}
        if (!$actions.ContainsKey($choice)) { throw '已取消。' }; $Action = $actions[$choice]
        if ($Action -eq 'Uninstall' -and (Read-Host '输入 UNINSTALL 确认') -cne 'UNINSTALL') { throw '已取消。' }
    }
    if ($Action -in @('Install','Update','Launch','Doctor','Prepare','Import-Save')) { Assert-GameLayout $Game }
    if ($Action -eq 'Import-Save') {
        Write-Host '导入会复制到实例的新槽并转换副本。原存档保留；请确认旧档写入时使用的游戏版本。'
        if (!$Source) { $Source = Pick-Folder '选择包含 descriptor.xml 的原版或 Miko RC8 存档槽目录' }
        if (!$ConfirmSource) {
            $suggested = if ([IO.File]::Exists((Join-Path $Game 'starsector-core/localization_version.json'))) { 'windows-zh-rc8' } else { 'windows-en-rc8' }
            $ConfirmSource = Read-Host "请选择旧档写入时的来源：windows-en-rc8 或 windows-zh-rc8。按当前安装语言建议 $suggested，回车采用建议值"
            if (!$ConfirmSource) { $ConfirmSource = $suggested }
        }
        if ($choiceForImport -and !$Slot) { $Slot = Read-Host '新槽名称（留空使用原槽名）' }
    }
    if ($Action -eq 'Update' -and !$Bundle) { $Bundle = Pick-Folder '选择新发行包解压目录（含 Install.cmd）' }
    $hash = [Security.Cryptography.SHA256]::Create()
    try { $tag = ([BitConverter]::ToString($hash.ComputeHash($utf8.GetBytes($root.ToUpperInvariant())))).Replace('-','').Substring(0,24) } finally { $hash.Dispose() }
    $mutex = New-Object Threading.Mutex($false, "Global\SSOptimizer-Standalone-$tag")
    try { $locked = $mutex.WaitOne(0) } catch [Threading.AbandonedMutexException] { $locked = $true; Write-Warning '前次管理进程已退出，重新校验版本。' }
    if (!$locked) { throw '其他安装、启动校验或版本管理操作正在执行，请稍后重试。' }
    $exists = [IO.Directory]::Exists($root)
    if ($exists) { $null = Check-Owner $root } elseif ($Action -ne 'Install') { throw "未找到安装：$root" }
    if ($Action -in @('Install','Update')) {
        $source = Join-Path ([IO.Path]::GetFullPath($Bundle)) 'version'
        if ($exists) {
            Install-Version $source $root $false
        } else {
            $temp = Join-Path $Game ('.ssoptimizer-install-' + [Guid]::NewGuid().ToString('N')); Ensure-Dir $temp
            try { Install-Version $source $temp $true; [IO.Directory]::Move($temp, $root) }
            catch { Write-Warning "首次安装未完成，原游戏未改动；暂存目录：$temp"; throw }
        }
        try { Desktop-Link $root $tag $false } catch { Write-Warning "桌面快捷方式创建失败：$_" }
        Write-Host "启动：$root\Launch.cmd；更新与版本选择：$root\Manage.cmd"
    } elseif ($Action -eq 'Select') { Select-Version $root $Version }
    elseif ($Action -eq 'Uninstall') {
        Remove-Install $root; try { Desktop-Link $root $tag $true } catch { Write-Warning "桌面快捷方式保留：$_" }
    } else {
        $id = Get-Current $root; $dir = Join-Path (Join-Path $root 'versions') $id; $null = Verify-Version $dir $id
        $instance = Join-Path $root 'instance'; Assert-Chain $instance
        $lease = [IO.File]::Open((Join-Path $dir 'release.sig'), 'Open', 'Read', 'Read')
        $mutex.ReleaseMutex(); $locked = $false
        $cliArgs = @('-cp',"$dir\agent.jar;$dir\lib\*",'ssoptimizer.standalone.launch.Main',$Action.ToLowerInvariant(),'--game',$Game,'--home',$dir,'--instance',$instance)
        if ($Action -eq 'Import-Save') {
            $cliArgs += @('--source', [IO.Path]::GetFullPath($Source), '--confirm-source', $ConfirmSource)
            if ($Slot) { $cliArgs += @('--slot', $Slot) }
        }
        if ($GameArgs.Count) { $cliArgs += '--'; $cliArgs += $GameArgs }
        $java = Join-Path $dir 'runtime/bin/java.exe'
        Push-Location -LiteralPath $root
        try { & $java @cliArgs; $code = $LASTEXITCODE } finally { Pop-Location }
    }
} catch { [Console]::Error.WriteLine("SSOptimizer：$_"); $code = 1 }
finally { if ($lease) { $lease.Dispose() }; if ($locked) { $mutex.ReleaseMutex() }; if ($mutex) { $mutex.Dispose() } }
exit $code

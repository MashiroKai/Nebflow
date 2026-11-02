#Requires -Version 5.1
<#
.SYNOPSIS
  stand jar 独立回退 —— 从备份目录恢复最近一个旧 jar 并重启 + 探活（Windows / PowerShell）。

.DESCRIPTION
  与 ops\swap-jar.ps1 **完全独立**（不 dot-source、不共用文件）：换装脚本本身跑挂了、
  或作者只想回退时，这一条命令就能救回来。只依赖三样东西：
    <BackupDir>\swap-<stamp>\ 里的备份 jar（+ backup.json 清单）、java、实例端口。

  流程：选备份 → 校验备份 sha256 → （在服务就优雅停）→ 还原到目标位置 → 校验 sha256 →
        启动 → 探活（/api/health + 首页 entry）→ 报告。

  设计红线：
    1) 停实例只走 CLI `stop` 子命令，且**恒用备份 jar 跑**（目标位置的 jar 可能已经是坏的）。
    2) 脚本自身不发强制信号（无 kill -9 / taskkill /F）。
    3) 幂等：目标位置已是该备份 jar 且实例健康、entry 命中 ⇒ no-op 退出 0。
    4) 每步落日志 <LogDir>\rollback-<stamp>.log；CLI 输出统一脱敏。
    5) 每个等待都有显式上限，到点判失败，绝不无限等。

.PARAMETER NFHome
  实例数据根；缺省 $env:STAND_HOME，再缺省 $env:USERPROFILE\.stand。

.PARAMETER Port
  实例端口，缺省 6138。

.PARAMETER BackupDir
  备份根；缺省 <NFHome>\backups\jar-swap。

.PARAMETER BackupId
  指定要恢复哪一个备份（备份目录名，如 swap-20261101-211233，或完整路径）；缺省=最新。

.PARAMETER LogDir
  日志目录；缺省 <NFHome>\logs\jar-swap。

.PARAMETER ExpectEntry
  回退后首页必须引用的 entry JS；缺省取 backup.json 里记录的换装前 entry，两者都没有则不校验。

.PARAMETER StopTimeoutSec
  停止后等待端口释放的上限（秒，缺省 90）。

.PARAMETER ReadyTimeoutSec
  启动后等待可访问的上限（秒，缺省 180）。

.PARAMETER JavaExe
  java 可执行文件；缺省 $env:JAVA_HOME\bin\java.exe，再缺省 PATH 上的 java。

.PARAMETER List
  只列出可用备份，不做任何改动。

.PARAMETER ForceInsideInstance
  本脚本跑在目标实例进程树里时默认拒绝（停机会连调用会话一起杀掉）。

.EXAMPLE
  # 看有哪些备份
  powershell -NoProfile -ExecutionPolicy Bypass -File ops\rollback-jar.ps1 -List

.EXAMPLE
  # 回退到最近一个备份
  powershell -NoProfile -ExecutionPolicy Bypass -File ops\rollback-jar.ps1

.EXAMPLE
  # 指定某一个备份
  powershell -NoProfile -ExecutionPolicy Bypass -File ops\rollback-jar.ps1 -BackupId swap-20261101-211233

.NOTES
  退出码：0 回退成功 / 已是该备份（no-op）| 1 找不到可用备份 | 2 停止失败（未还原）|
          3 还原失败 | 4 还原后起不来/探活不过

  实操提醒：不要把输出接进管道（实例的 perception-daemon 子进程会继承句柄、管道等不到
  EOF，看起来像卡住）。要留证据请用重定向：... -File ops\rollback-jar.ps1 ... > rollback.log 2>&1
#>
[CmdletBinding()]
param(
  [string]$NFHome,
  [int]$Port = 6138,
  [string]$BackupDir,
  [string]$BackupId,
  [string]$LogDir,
  [string]$ExpectEntry,
  [string]$JavaExe,
  [int]$StopTimeoutSec = 90,
  [int]$ReadyTimeoutSec = 180,
  [switch]$List,
  [switch]$ForceInsideInstance
)

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }
try { Add-Type -AssemblyName System.IO.Compression.FileSystem } catch { }

$EXIT_OK = 0
$EXIT_NO_BACKUP = 1
$EXIT_STOP_BAD = 2
$EXIT_RESTORE_BAD = 3
$EXIT_START_BAD = 4

$script:LogFile = $null
$script:Java = $null

# ═══════════════════════════════════════════════════════════════════════
# 基础工具（与 swap-jar.ps1 同形但故意各自一份：两条命令必须互不依赖）
# ═══════════════════════════════════════════════════════════════════════

function Protect-Text {
  param([string]$Text)
  if ([string]::IsNullOrEmpty($Text)) { return '' }
  $t = $Text
  $t = [regex]::Replace($t, '(?i)(bearer\s+)\S+', '$1***')
  $t = [regex]::Replace($t, '(?i)("?(?:token|secret|password|passwd|api[_-]?key|authorization|cookie)"?\s*[:=]\s*)("?[^"\s,}]+"?)', '$1***')
  return $t
}

function Write-Log {
  param([string]$Level, [string]$Message)
  $ts = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss.fff')
  $line = "[$ts] [$Level] $Message"
  if ($script:LogFile) { Add-Content -LiteralPath $script:LogFile -Value $line -Encoding UTF8 }
  switch ($Level) {
    'ERROR' { Write-Host $line -ForegroundColor Red }
    'WARN'  { Write-Host $line -ForegroundColor Yellow }
    'OK'    { Write-Host $line -ForegroundColor Green }
    default { Write-Host $line }
  }
}

function Get-Sha256 { param([string]$Path) (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Format-Bytes { param([long]$B) '{0:N0} B ({1:N1} MiB)' -f $B, ($B / 1MB) }

function Resolve-NFHome {
  param([string]$Explicit)
  $cand = $Explicit
  if (-not $cand -and $env:STAND_HOME) { $cand = $env:STAND_HOME }
  if (-not $cand) { $cand = Join-Path $env:USERPROFILE '.stand' }
  if (-not (Test-Path -LiteralPath $cand)) { throw "NF_HOME 不存在：$cand" }
  return (Get-Item -LiteralPath $cand).FullName
}

function Resolve-InstalledJar {
  param([string]$DataRoot)
  $binDir = Join-Path $DataRoot 'bin'
  $ptr = Join-Path $binDir 'current-version'
  if (Test-Path -LiteralPath $ptr) {
    $ver = (Get-Content -LiteralPath $ptr -TotalCount 1)
    if ($ver) { $ver = $ver.Trim() }
    if ($ver) {
      $cand = Join-Path $binDir ("versions\{0}\stand-assembly-{0}.jar" -f $ver)
      if (Test-Path -LiteralPath $cand) { return (Get-Item -LiteralPath $cand).FullName }
      $vdir = Join-Path $binDir ("versions\{0}" -f $ver)
      if (Test-Path -LiteralPath $vdir) {
        $j = Get-ChildItem -LiteralPath $vdir -Filter '*.jar' -ErrorAction SilentlyContinue |
             Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($j) { return $j.FullName }
      }
    }
  }
  if (Test-Path -LiteralPath $binDir) {
    $j = Get-ChildItem -LiteralPath $binDir -Filter 'stand-assembly-*.jar' -ErrorAction SilentlyContinue |
         Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($j) { return $j.FullName }
  }
  return $null
}

function Resolve-Java {
  param([string]$Explicit)
  if ($Explicit) {
    if (-not (Test-Path -LiteralPath $Explicit)) { throw "指定的 java 不存在：$Explicit" }
    return $Explicit
  }
  if ($env:JAVA_HOME) {
    $cand = Join-Path $env:JAVA_HOME 'bin\java.exe'
    if (Test-Path -LiteralPath $cand) { return $cand }
  }
  $cmd = Get-Command java -ErrorAction SilentlyContinue
  if ($cmd) { return $cmd.Source }
  throw '找不到 java（JAVA_HOME 未设且 PATH 上没有 java）'
}

function Get-ListenerPids {
  param([int]$TargetPort)
  $found = @()
  try {
    foreach ($r in (& netstat -ano -p tcp 2>$null)) {
      if ($r -match '^\s*TCP\s+\S+:(\d+)\s+\S+\s+LISTENING\s+(\d+)\s*$') {
        if ([int]$Matches[1] -eq $TargetPort) { $found += [int]$Matches[2] }
      }
    }
  } catch { }
  if ($found.Count -eq 0) {
    try {
      $found = @(Get-NetTCPConnection -LocalPort $TargetPort -State Listen -ErrorAction Stop |
                 ForEach-Object { [int]$_.OwningProcess })
    } catch { }
  }
  return @($found | Select-Object -Unique)
}

function Get-ProcCmdline {
  param([int]$ProcId)
  try {
    $p = Get-CimInstance Win32_Process -Filter "ProcessId=$ProcId" -ErrorAction Stop
    if ($p -and $p.CommandLine) { return [string]$p.CommandLine }
  } catch { }
  return $null
}

function Test-RunsInsideTarget {
  param([int]$TargetPid)
  $cur = $PID
  $chain = @()
  for ($i = 0; $i -lt 32; $i++) {
    if ($cur -le 0) { break }
    $chain += $cur
    if ($cur -eq $TargetPid) { return @{ Inside = $true; Chain = $chain } }
    $parent = 0
    try {
      $p = Get-CimInstance Win32_Process -Filter "ProcessId=$cur" -ErrorAction Stop
      if ($p -and $p.ParentProcessId) { $parent = [int]$p.ParentProcessId }
    } catch { $parent = 0 }
    if ($parent -le 0 -or $parent -eq $cur) { break }
    $cur = $parent
  }
  return @{ Inside = $false; Chain = $chain }
}

function Get-HttpText {
  param([string]$Url, [int]$TimeoutSec = 5)
  try {
    $r = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec $TimeoutSec
    return @{ Ok = $true; Status = [int]$r.StatusCode; Body = [string]$r.Content; Err = '' }
  } catch {
    return @{ Ok = $false; Status = 0; Body = ''; Err = $_.Exception.Message }
  }
}

function Get-Health {
  param([int]$TargetPort)
  $r = Get-HttpText -Url ("http://127.0.0.1:{0}/api/health" -f $TargetPort) -TimeoutSec 5
  if (-not $r.Ok) {
    return @{ Ok = $false; Status = 0; StatusField = ''; Version = ''; Detail = (Protect-Text $r.Err) }
  }
  $st = ''; $ver = ''
  try {
    $j = $r.Body | ConvertFrom-Json
    $st = [string]$j.status
    $ver = [string]$j.version
  } catch { }
  $ok = ($r.Status -eq 200 -and $st -eq 'ok')
  return @{ Ok = $ok; Status = $r.Status; StatusField = $st; Version = $ver; Detail = "HTTP $($r.Status) status='$st' version='$ver'" }
}

function Get-ServedAssets {
  param([int]$TargetPort)
  $r = Get-HttpText -Url ("http://127.0.0.1:{0}/" -f $TargetPort) -TimeoutSec 10
  if (-not $r.Ok) { return @{ Ok = $false; Js = @(); Css = @(); Detail = (Protect-Text $r.Err) } }
  $js = @([regex]::Matches($r.Body, 'app-[A-Za-z0-9]+\.js') | ForEach-Object { $_.Value } | Select-Object -Unique)
  $css = @([regex]::Matches($r.Body, 'app-[A-Za-z0-9]+\.css') | ForEach-Object { $_.Value } | Select-Object -Unique)
  return @{ Ok = $true; Js = $js; Css = $css; Detail = ("js=[{0}] css=[{1}]" -f ($js -join ','), ($css -join ',')) }
}

function Invoke-StandCli {
  param([string]$CliJar, [string[]]$CliArgs)
  $all = @('-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-jar', $CliJar) + $CliArgs
  $shown = ($all | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } }) -join ' '
  Write-Log 'RUN' ("{0} {1}" -f (Split-Path -Leaf $script:Java), $shown)
  $out = ''
  try {
    $out = & $script:Java @all 2>&1 | Out-String
    $code = $LASTEXITCODE
  } catch {
    $out = "invoke error: $($_.Exception.Message)"
    $code = -1
  }
  foreach ($l in ($out -split "`r?`n")) {
    if ($l -and $l.Trim()) { Write-Log 'OUT' (Protect-Text $l.Trim()) }
  }
  Write-Log 'RC' ("exit=$code")
  return @{ Code = $code; Out = $out }
}

function Stop-Instance {
  param([string]$CliJar, [int]$TargetPort, [string]$DataRoot, [int]$TimeoutSec)
  $r = Invoke-StandCli -CliJar $CliJar -CliArgs @('stop', '--home', $DataRoot, '--port', "$TargetPort")
  $deadline = (Get-Date).AddSeconds($TimeoutSec)
  while ((Get-Date) -lt $deadline) {
    if ((@(Get-ListenerPids -TargetPort $TargetPort)).Count -eq 0) {
      Write-Log 'INFO' ("端口 {0} 已释放（stop exit={1}）" -f $TargetPort, $r.Code)
      return $true
    }
    Start-Sleep -Milliseconds 400
  }
  Write-Log 'WARN' ("端口 {0} 未释放，重试一次 stop --force（仍是优雅路径）" -f $TargetPort)
  $r2 = Invoke-StandCli -CliJar $CliJar -CliArgs @('stop', '--force', '--home', $DataRoot, '--port', "$TargetPort")
  $deadline2 = (Get-Date).AddSeconds($TimeoutSec)
  while ((Get-Date) -lt $deadline2) {
    if ((@(Get-ListenerPids -TargetPort $TargetPort)).Count -eq 0) {
      Write-Log 'INFO' ("端口 {0} 已释放（重试 exit={1}）" -f $TargetPort, $r2.Code)
      return $true
    }
    Start-Sleep -Milliseconds 400
  }
  return $false
}

function Start-Instance {
  param([string]$Jar, [int]$TargetPort, [string]$DataRoot, [int]$TimeoutSec, [string[]]$ExpectJs, [string[]]$ExpectCssArr)
  $env:NEBFLOW_DAEMON_READY_TIMEOUT_SEC = [string]$TimeoutSec
  $r = Invoke-StandCli -CliJar $Jar -CliArgs @('start', '--home', $DataRoot, '--port', "$TargetPort", '--no-browser')
  if ($r.Code -ne 0) {
    return @{ Ok = $false; Detail = ("start 退出码 {0}（见上方 OUT 行）" -f $r.Code) }
  }
  $probeStart = Get-Date
  $deadline = $probeStart.AddSeconds($TimeoutSec)
  $last = '尚未探活'
  $mismatchStreak = 0
  while ((Get-Date) -lt $deadline) {
    $h = Get-Health -TargetPort $TargetPort
    if ($h.Ok) {
      $a = Get-ServedAssets -TargetPort $TargetPort
      if (-not $a.Ok) {
        $last = "健康通过但首页取不到（$($a.Detail)）"
        $mismatchStreak = 0
      } else {
        $jsOk = $true
        if ($ExpectJs -and $ExpectJs.Count -gt 0) { $jsOk = (($a.Js | Where-Object { $ExpectJs -contains $_ }).Count -gt 0) }
        $cssOk = $true
        if ($ExpectCssArr -and $ExpectCssArr.Count -gt 0) { $cssOk = (($a.Css | Where-Object { $ExpectCssArr -contains $_ }).Count -gt 0) }
        if ($jsOk -and $cssOk) {
          $waited = [int]((Get-Date) - $probeStart).TotalSeconds
          return @{ Ok = $true; Detail = ("health ok + entry 命中（{0}；等待 {1}s）" -f $a.Detail, $waited); Assets = $a; Health = $h }
        }
        $last = ("健康通过但 entry 不符：实际 {0}；期望 js=[{1}] css=[{2}]" -f $a.Detail, ($ExpectJs -join ','), ($ExpectCssArr -join ','))
        $mismatchStreak++
        if ($mismatchStreak -ge 5) { break }
      }
    } else {
      $mismatchStreak = 0
      $last = "health 未通过：$($h.Detail)"
    }
    Start-Sleep -Milliseconds 900
  }
  $waitedTotal = [int]((Get-Date) - $probeStart).TotalSeconds
  return @{ Ok = $false; Detail = ("探活失败：等待 {0}s（上限 {1}s）—— {2}" -f $waitedTotal, $TimeoutSec, $last) }
}

# ═══════════════════════════════════════════════════════════════════════
# 备份枚举
# ═══════════════════════════════════════════════════════════════════════

function Get-Backups {
  param([string]$Root)
  if (-not (Test-Path -LiteralPath $Root)) { return @() }
  $list = @()
  foreach ($d in (Get-ChildItem -LiteralPath $Root -Directory -ErrorAction SilentlyContinue)) {
    $jar = Get-ChildItem -LiteralPath $d.FullName -Filter '*.jar' -ErrorAction SilentlyContinue |
           Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $jar) { continue }
    $meta = $null
    $metaPath = Join-Path $d.FullName 'backup.json'
    if (Test-Path -LiteralPath $metaPath) {
      try { $meta = Get-Content -LiteralPath $metaPath -Raw -Encoding UTF8 | ConvertFrom-Json } catch { $meta = $null }
    }
    $list += [pscustomobject]@{
      Id          = $d.Name
      Dir         = $d.FullName
      Jar         = $jar.FullName
      JarBytes    = $jar.Length
      When        = $d.LastWriteTime
      OldSha256   = $(if ($meta) { [string]$meta.oldSha256 } else { $null })
      OldJarPath  = $(if ($meta) { [string]$meta.oldJarPath } else { $null })
      OldEntryJs  = $(if ($meta) { @($meta.oldEntryJs) } else { @() })
      OldEntryCss = $(if ($meta) { @($meta.oldEntryCss) } else { @() })
      Port        = $(if ($meta) { $meta.port } else { $null })
      NFHome      = $(if ($meta) { [string]$meta.nfHome } else { $null })
    }
  }
  return @($list | Sort-Object When -Descending)
}

# ═══════════════════════════════════════════════════════════════════════
# MAIN
# ═══════════════════════════════════════════════════════════════════════

$stamp = (Get-Date).ToString('yyyyMMdd-HHmmss')

try {
  $dataRoot = Resolve-NFHome -Explicit $NFHome
  if (-not $BackupDir) { $BackupDir = Join-Path (Join-Path $dataRoot 'backups') 'jar-swap' }
  if (-not $LogDir) { $LogDir = Join-Path (Join-Path $dataRoot 'logs') 'jar-swap' }
} catch {
  Write-Host ("FATAL: {0}" -f $_.Exception.Message) -ForegroundColor Red
  exit $EXIT_NO_BACKUP
}

$all = Get-Backups -Root $BackupDir

if ($List) {
  Write-Host ("备份根：{0}" -f $BackupDir)
  if ($all.Count -eq 0) { Write-Host '（没有可用备份）'; exit $EXIT_NO_BACKUP }
  $i = 0
  foreach ($b in $all) {
    $i++
    Write-Host ("[{0}] {1}  {2}  {3}" -f $i, $b.Id, $b.When.ToString('yyyy-MM-dd HH:mm:ss'), (Format-Bytes $b.JarBytes))
    Write-Host ("     jar   : {0}" -f $b.Jar)
    Write-Host ("     原位置: {0}" -f ($(if ($b.OldJarPath) { $b.OldJarPath } else { '(backup.json 未记录)' })))
    Write-Host ("     原 sha: {0}" -f ($(if ($b.OldSha256) { $b.OldSha256 } else { '(未记录)' })))
    Write-Host ("     原 entry: js=[{0}] css=[{1}]" -f ($b.OldEntryJs -join ','), ($b.OldEntryCss -join ','))
  }
  exit $EXIT_OK
}

New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
$script:LogFile = Join-Path $LogDir ("rollback-{0}.log" -f $stamp)
New-Item -ItemType File -Force -Path $script:LogFile | Out-Null

try { $script:Java = Resolve-Java -Explicit $JavaExe }
catch {
  Write-Log 'ERROR' $_.Exception.Message
  exit $EXIT_NO_BACKUP
}

Write-Log 'INFO' '══════════════════════════════════════════════════════'
Write-Log 'INFO' ' stand jar 独立回退（restore latest backup）'
Write-Log 'INFO' (" 时间      : {0}" -f (Get-Date).ToString('yyyy-MM-dd HH:mm:ss'))
Write-Log 'INFO' (" 脚本      : {0}" -f $MyInvocation.MyCommand.Path)
Write-Log 'INFO' (" NF_HOME   : {0}" -f $dataRoot)
Write-Log 'INFO' (" 端口      : {0}" -f $Port)
Write-Log 'INFO' (" 备份根    : {0}" -f $BackupDir)
Write-Log 'INFO' (" 日志      : {0}" -f $script:LogFile)
Write-Log 'INFO' '══════════════════════════════════════════════════════'

# 1) 选备份
if ($all.Count -eq 0) {
  Write-Log 'ERROR' ("找不到可用备份：{0} 下没有带 jar 的备份目录" -f $BackupDir)
  Write-Log 'ERROR' '（换装脚本每次运行都会在其下建 swap-<stamp>\ 并写 backup.json）'
  exit $EXIT_NO_BACKUP
}

$pick = $null
if ($BackupId) {
  $pick = $all | Where-Object { $_.Id -eq $BackupId -or $_.Dir -eq $BackupId } | Select-Object -First 1
  if (-not $pick) {
    Write-Log 'ERROR' ("指定的备份不存在：{0}（可用：{1}）" -f $BackupId, (($all | ForEach-Object { $_.Id }) -join ', '))
    exit $EXIT_NO_BACKUP
  }
} else {
  $pick = $all[0]
}
Write-Log 'INFO' ("选中备份：{0}（{1}；{2}）" -f $pick.Id, $pick.When.ToString('yyyy-MM-dd HH:mm:ss'), (Format-Bytes $pick.JarBytes))
Write-Log 'INFO' ("备份 jar：{0}" -f $pick.Jar)

# 2) 校验备份自身
$backupSha = Get-Sha256 -Path $pick.Jar
Write-Log 'INFO' ("备份 sha256：{0}" -f $backupSha)
if ($pick.OldSha256 -and $pick.OldSha256.ToLowerInvariant() -ne $backupSha) {
  Write-Log 'ERROR' ("备份自带校验失败：backup.json 记录 {0} ≠ 实际 {1} —— 备份文件已损坏，不拿它去还原" -f $pick.OldSha256, $backupSha)
  exit $EXIT_NO_BACKUP
}

# 3) 目标位置
$targetJar = $null
if ($pick.OldJarPath) {
  $parent = Split-Path -Parent $pick.OldJarPath
  if ($parent -and (Test-Path -LiteralPath $parent)) { $targetJar = $pick.OldJarPath }
}
if (-not $targetJar) {
  $targetJar = Resolve-InstalledJar -DataRoot $dataRoot
  if (-not $targetJar) {
    Write-Log 'ERROR' ("无法确定目标位置（backup.json 未记录 oldJarPath，且 {0}\bin 下解析不到 jar）" -f $dataRoot)
    exit $EXIT_RESTORE_BAD
  }
}
Write-Log 'INFO' ("目标位置：{0}" -f $targetJar)

$currentSha = $null
if (Test-Path -LiteralPath $targetJar) {
  $currentSha = Get-Sha256 -Path $targetJar
  Write-Log 'INFO' ("目标位置当前 sha256：{0}" -f $currentSha)
}

# 4) 期望 entry（回退后应命中的）
if ($ExpectEntry) { $expectJs = @($ExpectEntry) } else { $expectJs = @($pick.OldEntryJs) }
$expectCss = @($pick.OldEntryCss)
Write-Log 'INFO' ("回退后期望 entry：js=[{0}] css=[{1}]" -f ($expectJs -join ','), ($expectCss -join ','))

# 5) 幂等短路：已经是该备份 + 健康 + entry 命中
if ($currentSha -and $currentSha -eq $backupSha) {
  $h0 = Get-Health -TargetPort $Port
  $a0 = Get-ServedAssets -TargetPort $Port
  $entryOk = $true
  if ($expectJs.Count -gt 0 -and $a0.Ok) { $entryOk = (($a0.Js | Where-Object { $expectJs -contains $_ }).Count -gt 0) }
  if ($h0.Ok -and $entryOk) {
    Write-Log 'OK' '目标位置已是该备份 jar，且实例健康、entry 命中 ⇒ 无需回退（no-op，退出 0）'
    Write-Log 'INFO' ("health：{0}；首页 {1}" -f $h0.Detail, $a0.Detail)
    exit $EXIT_OK
  }
  Write-Log 'WARN' ("目标位置已是该备份 jar，但实例状态不符（health={0}；entry {1}）⇒ 继续走「停→还原→起」" -f $h0.Detail, $a0.Detail)
}

# 6) 在服务就优雅停（恒用备份 jar 跑 CLI）
$pids = @(Get-ListenerPids -TargetPort $Port)
if ($pids.Count -gt 0) {
  Write-Log 'INFO' ("端口 {0} 在服务（pid {1}）—— 先优雅停" -f $Port, ($pids -join ','))
  $cmd = Get-ProcCmdline -ProcId $pids[0]
  if ($cmd) { Write-Log 'INFO' ("pid {0} 命令行：{1}" -f $pids[0], (Protect-Text $cmd)) }
  if ($cmd -and $cmd -notmatch 'nebflow\.Main') {
    Write-Log 'ERROR' ("端口 {0} 被非 nebflow 进程占用（pid {1}），拒绝动手" -f $Port, $pids[0])
    exit $EXIT_STOP_BAD
  }
  $tree = Test-RunsInsideTarget -TargetPid $pids[0]
  if ($tree.Inside -and -not $ForceInsideInstance) {
    Write-Log 'ERROR' ("本脚本跑在目标实例 pid {0} 的进程树里 —— stop 会连调用会话一起杀掉；请在实例外的终端里跑（或 -ForceInsideInstance）" -f $pids[0])
    exit $EXIT_STOP_BAD
  }
  if (-not (Stop-Instance -CliJar $pick.Jar -TargetPort $Port -DataRoot $dataRoot -TimeoutSec $StopTimeoutSec)) {
    Write-Log 'ERROR' ("停止失败：端口 {0} 仍被占用，未还原（jar 未动）" -f $Port)
    exit $EXIT_STOP_BAD
  }
} else {
  Write-Log 'INFO' ("端口 {0} 当前无监听者（实例未在服务）—— 直接还原并启动" -f $Port)
}

# 7) 还原
if ($currentSha -ne $backupSha) {
  try {
    Copy-Item -LiteralPath $pick.Jar -Destination $targetJar -Force
  } catch {
    Write-Log 'ERROR' ("还原拷贝失败：{0}" -f $_.Exception.Message)
    exit $EXIT_RESTORE_BAD
  }
  $restoredSha = Get-Sha256 -Path $targetJar
  if ($restoredSha -ne $backupSha) {
    Write-Log 'ERROR' ("还原后 sha256 不符：{0} ≠ {1}" -f $restoredSha, $backupSha)
    exit $EXIT_RESTORE_BAD
  }
  Write-Log 'INFO' ("还原完成：{0}（sha256 {1}）" -f $targetJar, $restoredSha)
} else {
  Write-Log 'INFO' '目标位置已是备份 jar，跳过拷贝'
}

# 8) 启动 + 探活
$probe = Start-Instance -Jar $targetJar -TargetPort $Port -DataRoot $dataRoot -TimeoutSec $ReadyTimeoutSec -ExpectJs $expectJs -ExpectCssArr $expectCss
if (-not $probe.Ok) {
  Write-Log 'ERROR' ("回退失败：{0}" -f $probe.Detail)
  Write-Log 'ERROR' ("处理建议：确认 jar 为 {0}；手工启：java -jar <jar> start --home '{1}' --port {2}；日志 {3}" -f $targetJar, $dataRoot, $Port, $script:LogFile)
  exit $EXIT_START_BAD
}

$finalSha = Get-Sha256 -Path $targetJar
$finalHealth = Get-Health -TargetPort $Port
$finalAssets = Get-ServedAssets -TargetPort $Port
Write-Log 'OK' '══════════════════════════════════════════════════════'
Write-Log 'OK' ' 回退成功'
Write-Log 'OK' (" 实例       : http://127.0.0.1:{0}/" -f $Port)
Write-Log 'OK' (" 监听 pid   : {0}" -f ((@(Get-ListenerPids -TargetPort $Port)) -join ','))
Write-Log 'OK' (" jar        : {0}" -f $targetJar)
Write-Log 'OK' (" jar sha256 : {0}（备份 {1} 一致 ✓）" -f $finalSha, $backupSha)
Write-Log 'OK' (" health     : {0}" -f $finalHealth.Detail)
Write-Log 'OK' (" 首页 entry : {0}" -f $finalAssets.Detail)
Write-Log 'OK' (" 日志       : {0}" -f $script:LogFile)
Write-Log 'OK' '══════════════════════════════════════════════════════'
exit $EXIT_OK

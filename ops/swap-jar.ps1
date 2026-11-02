#Requires -Version 5.1
<#
.SYNOPSIS
  stand 换 jar + 重启（带自动回滚）—— Windows / PowerShell 版。

.DESCRIPTION
  在线换装一条命令：前置检查 → 优雅停 → 备份旧 jar → 换装新 jar → 启动 →
  探活（/api/health + 首页 entry hash）→ 成功保持运行；任何一步失败 ⇒ 自动回滚
  （停 → 还原旧 jar → 重新起 → 再探活）。

  设计红线（2026-11-01 换装批）：
    1) 停实例只走 CLI 的 `stop` 子命令（== `stand stop`）。ops/bin/stand.cmd 与
       <NF_HOME>\bin\stand.cmd 都只是「找 java + 按 current-version 找 jar + 原样转发
       argv」的薄壳，本脚本直接执行同一条命令；绕开薄壳的唯一原因：新 jar 一旦损坏，
       薄壳按指针解析到的正是坏 jar，连 stop 都跑不起来 —— 而本脚本可以用**已知可用的
       备份 jar** 跑 stop。
    2) 脚本自身不发任何强制信号（无 kill -9 / taskkill /F）。
    3) 幂等：目标位置已是新 jar、实例健康、entry 对得上 ⇒ no-op 退出 0。
    4) 每步落日志 <LogDir>\swap-<stamp>.log；CLI 输出统一过凭据脱敏。
    5) 回滚失败显式报出（最坏情况，需人工介入）。
    6) 每个等待都有显式上限（StopTimeoutSec / ReadyTimeoutSec），到点判失败，绝不无限等。

  entry hash 探活：换装成功与否不看「进程起来了」，看**首页 index.html 引用的 entry
  JS**（如 app-75C3M6MD.js）——它来自 jar 内 web-dist/index.html，是 jar 真的换了代码
  的硬证据。期望值不给就自动从新 jar 里读。

.PARAMETER NewJar
  新 jar 绝对路径（必填）。

.PARAMETER OldJar
  目标位置的 jar（= 将被替换的那个）。缺省从 <NFHome>\bin\current-version 解析。

.PARAMETER Port
  实例端口，缺省 6138。

.PARAMETER NFHome
  实例数据根；缺省 $env:STAND_HOME，再缺省 $env:USERPROFILE\.stand。

.PARAMETER LogDir
  日志目录；缺省 <NFHome>\logs\jar-swap。

.PARAMETER BackupDir
  备份根；缺省 <NFHome>\backups\jar-swap（每次一个 swap-<stamp> 子目录，含 backup.json）。

.PARAMETER ExpectOldSha256
  旧 jar 的期望 sha256（本批已证实值 dd92b172…）。传 '' 关闭该校验。

.PARAMETER ExpectNewSha256
  新 jar 的期望 sha256；不传则只记录不校验。

.PARAMETER ExpectEntry
  换装成功后首页必须引用的 entry JS（如 app-75C3M6MD.js）。不传则从新 jar 的
  web-dist/index.html 自动推导；两者都没有则只校验 /api/health。

.PARAMETER ExpectCss
  同上，CSS 维度。

.PARAMETER StopTimeoutSec
  停止后等待端口释放的上限（秒，缺省 90）。

.PARAMETER ReadyTimeoutSec
  启动后等待可访问的上限（秒，缺省 180）。

.PARAMETER BackupKeep
  保留最近几个备份目录（缺省 5，本次的永不删除）。

.PARAMETER JavaExe
  java 可执行文件；缺省 $env:JAVA_HOME\bin\java.exe，再缺省 PATH 上的 java。

.PARAMETER ForceInsideInstance
  本脚本的进程祖先里包含目标实例时默认拒绝执行（停机会连调用会话一起杀掉）；
  确知后果时加这个开关。

.PARAMETER CheckOnly
  只跑前置检查，零改动（不 stop、不备份、不换、不起）。

.EXAMPLE
  # 先看一眼（零改动、只读）
  powershell -NoProfile -ExecutionPolicy Bypass -File ops\swap-jar.ps1 -NewJar D:\build\new.jar -CheckOnly

.EXAMPLE
  # 真正换装（在实例外的终端里跑）
  powershell -NoProfile -ExecutionPolicy Bypass -File ops\swap-jar.ps1 -NewJar D:\build\new.jar

.NOTES
  退出码：0 成功 / 已是新 jar（no-op）| 2 前置检查拒绝（未改动任何东西）|
          5 停止或备份失败（jar 未改动）| 3 换装失败但自动回滚成功 |
          4 回滚失败（最坏情况，需人工介入）

  实操提醒：不要把本脚本的输出接进管道（cmd | tee / powershell ... | tail）。实例起来后
  会有一个 perception-daemon 子进程继承句柄，管道等不到 EOF，看起来像「卡住且没输出」。
  要留证据请用重定向：... -File ops\swap-jar.ps1 ... > swap.log 2>&1
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][string]$NewJar,
  [string]$OldJar,
  [int]$Port = 6138,
  [string]$NFHome,
  [string]$LogDir,
  [string]$BackupDir,
  [string]$ExpectOldSha256 = 'dd92b172a3cb682560a02f5eb8164dc1d4562d456e6363de99c152e10695d997',
  [string]$ExpectNewSha256,
  [string]$ExpectEntry,
  [string]$ExpectCss,
  [string]$JavaExe,
  [int]$StopTimeoutSec = 90,
  [int]$ReadyTimeoutSec = 180,
  [int]$BackupKeep = 5,
  [switch]$ForceInsideInstance,
  [switch]$CheckOnly
)

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }
try { Add-Type -AssemblyName System.IO.Compression.FileSystem } catch { }

$EXIT_OK = 0
$EXIT_REFUSED = 2
$EXIT_TOUCH_BAD = 3      # 换装失败但已回滚成功
$EXIT_ROLLBACK_BAD = 4   # 最坏情况
$EXIT_STOP_BAD = 5       # 停止/备份失败，jar 未动

$script:LogFile = $null
$script:StepNo = 0
$script:StepStart = Get-Date
$script:S = @{}

# ═══════════════════════════════════════════════════════════════════════
# 基础工具
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
    'ERROR'        { Write-Host $line -ForegroundColor Red }
    'ROLLBACK'     { Write-Host $line -ForegroundColor Yellow }
    'ROLLBACK-BAD' { Write-Host $line -ForegroundColor Red }
    'WARN'         { Write-Host $line -ForegroundColor Yellow }
    'OK'           { Write-Host $line -ForegroundColor Green }
    'REFUSED'      { Write-Host $line -ForegroundColor Red }
    default        { Write-Host $line }
  }
}

function Start-Step {
  param([string]$Title)
  $script:StepNo++
  $script:StepStart = Get-Date
  Write-Log 'STEP' ("{0}. {1}" -f $script:StepNo, $Title)
}

function Complete-Step {
  param([string]$Detail)
  $ms = [int]((Get-Date) - $script:StepStart).TotalMilliseconds
  Write-Log 'OK' ("{0}. {1} ({2} ms)" -f $script:StepNo, $Detail, $ms)
}

function Get-Sha256 {
  param([string]$Path)
  (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Format-Bytes {
  param([long]$Bytes)
  '{0:N0} B ({1:N1} MiB)' -f $Bytes, ($Bytes / 1MB)
}

function Resolve-FullPath {
  param([string]$Path, [string]$What)
  if (-not $Path) { throw "$What 路径为空" }
  if (-not (Test-Path -LiteralPath $Path)) { throw "$What 不存在：$Path" }
  return (Get-Item -LiteralPath $Path).FullName
}

function Resolve-NFHome {
  param([string]$Explicit)
  $cand = $Explicit
  if (-not $cand -and $env:STAND_HOME) { $cand = $env:STAND_HOME }
  if (-not $cand) { $cand = Join-Path $env:USERPROFILE '.stand' }
  if (-not (Test-Path -LiteralPath $cand)) { throw "NF_HOME 不存在：$cand" }
  return (Get-Item -LiteralPath $cand).FullName
}

# <NFHome>\bin\current-version → versions\<v>\stand-assembly-<v>.jar；再退化到扁平布局。
# 与 ops/bin/stand.cmd 的 stand_probe_install 同序（指针 → versions/ → 平铺）。
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
  throw "无法从 $binDir 解析已安装 jar（current-version 指针缺失，且无 stand-assembly-*.jar）"
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

# ═══════════════════════════════════════════════════════════════════════
# 探针：端口 / 进程 / HTTP / jar 内 entry
# ═══════════════════════════════════════════════════════════════════════

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

function Test-ProcAlive {
  param([int]$ProcId)
  try { return [bool](Get-Process -Id $ProcId -ErrorAction Stop) } catch { return $false }
}

function Get-ProcCmdline {
  param([int]$ProcId)
  try {
    $p = Get-CimInstance Win32_Process -Filter "ProcessId=$ProcId" -ErrorAction Stop
    if ($p -and $p.CommandLine) { return [string]$p.CommandLine }
  } catch { }
  return $null
}

# 本脚本（powershell 进程）是否跑在目标实例的进程树里 —— 是的话 stop 会杀掉调用会话。
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

# 从 jar 里读 web-dist/index.html：a) 推导新 jar 的 entry；b) 证明磁盘上的 jar 就是正在服务的那份
function Get-JarIndexHtml {
  param([string]$JarPath)
  $zip = $null
  try {
    $zip = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
    $e = $zip.Entries | Where-Object { $_.FullName -eq 'web-dist/index.html' } | Select-Object -First 1
    if (-not $e) { return $null }
    $sr = New-Object System.IO.StreamReader($e.Open())
    $txt = $sr.ReadToEnd()
    $sr.Dispose()
    return $txt
  } catch { return $null } finally { if ($zip) { $zip.Dispose() } }
}

function Get-JarAssets {
  param([string]$JarPath)
  $html = Get-JarIndexHtml -JarPath $JarPath
  if (-not $html) { return @{ Ok = $false; Js = @(); Css = @() } }
  $js = @([regex]::Matches($html, 'app-[A-Za-z0-9]+\.js') | ForEach-Object { $_.Value } | Select-Object -Unique)
  $css = @([regex]::Matches($html, 'app-[A-Za-z0-9]+\.css') | ForEach-Object { $_.Value } | Select-Object -Unique)
  return @{ Ok = $true; Js = $js; Css = $css }
}

# ═══════════════════════════════════════════════════════════════════════
# CLI（== `stand <sub>`；薄壳只做 java/jar 解析 + 原样转发，故此处直接执行同一条命令）
# ═══════════════════════════════════════════════════════════════════════

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
  <#
    优雅停止：只走 CLI stop（TERM 语义）。恒用「已知可用」的 jar 跑 —— 这样即使目标
    位置的 jar 已经损坏也能停。返回 $true = 已停住且端口已释放。
  #>
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

  # 端口还在：再试一次（同一条优雅路径，--force 只跳过归属门，仍非强杀）
  Write-Log 'WARN' ("端口 {0} 在 {1}s 内未释放，重试一次 stop --force（仍是优雅路径）" -f $TargetPort, $TimeoutSec)
  $r2 = Invoke-StandCli -CliJar $CliJar -CliArgs @('stop', '--force', '--home', $DataRoot, '--port', "$TargetPort")
  $deadline2 = (Get-Date).AddSeconds($TimeoutSec)
  while ((Get-Date) -lt $deadline2) {
    if ((@(Get-ListenerPids -TargetPort $TargetPort)).Count -eq 0) {
      Write-Log 'INFO' ("端口 {0} 已释放（重试 stop exit={1}）" -f $TargetPort, $r2.Code)
      return $true
    }
    Start-Sleep -Milliseconds 400
  }
  Write-Log 'ERROR' ("停止失败：端口 {0} 仍被 pid [{1}] 占用" -f $TargetPort, (@(Get-ListenerPids -TargetPort $TargetPort) -join ','))
  return $false
}

function Start-Instance {
  <#
    启动 + 等到「真的可访问」：/api/health ok，且首页 entry（给了才校验）对得上。
    有界：ReadyTimeoutSec 到点判失败。entry 不符是**确定性**结果（entry 来自 jar 内的
    index.html，不会自愈），连续 5 次不符即早退，不空等上限。
  #>
  param([string]$Jar, [int]$TargetPort, [string]$DataRoot, [int]$TimeoutSec, [string[]]$ExpectJs, [string[]]$ExpectCssArr)

  $env:NEBFLOW_DAEMON_READY_TIMEOUT_SEC = [string]$TimeoutSec   # 子进程继承（DaemonSpawn 读它）
  $r = Invoke-StandCli -CliJar $Jar -CliArgs @('start', '--home', $DataRoot, '--port', "$TargetPort", '--no-browser')
  if ($r.Code -ne 0) {
    return @{ Ok = $false; Detail = ("start 退出码 {0}（见上方 OUT 行）" -f $r.Code); Assets = $null; Health = $null }
  }

  $probeStart = Get-Date
  $deadline = $probeStart.AddSeconds($TimeoutSec)
  $last = '尚未探活'
  $lastHealth = $null
  $lastAssets = $null
  $mismatchStreak = 0
  while ((Get-Date) -lt $deadline) {
    $h = Get-Health -TargetPort $TargetPort
    $lastHealth = $h
    if ($h.Ok) {
      $a = Get-ServedAssets -TargetPort $TargetPort
      $lastAssets = $a
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
  return @{ Ok = $false; Detail = ("探活失败：等待 {0}s（上限 {1}s）—— {2}" -f $waitedTotal, $TimeoutSec, $last); Assets = $lastAssets; Health = $lastHealth }
}

# ═══════════════════════════════════════════════════════════════════════
# 回滚
# ═══════════════════════════════════════════════════════════════════════

function Write-BadCaseHelp {
  $s = $script:S
  Write-Log 'ROLLBACK-BAD' '─────────────────────────────────────────────'
  Write-Log 'ROLLBACK-BAD' '最坏情况：实例当前不可用 / jar 状态未知，需要人工介入。'
  Write-Log 'ROLLBACK-BAD' ("  1) 备份 jar（已知可用）：{0}" -f $s.BackupJar)
  Write-Log 'ROLLBACK-BAD' ("  2) 目标位置：{0}" -f $s.TargetJar)
  Write-Log 'ROLLBACK-BAD' ("  3) 手工还原：Copy-Item -LiteralPath '{0}' -Destination '{1}' -Force" -f $s.BackupJar, $s.TargetJar)
  Write-Log 'ROLLBACK-BAD' ("  4) 或跑独立回退脚本：powershell -NoProfile -File ops\rollback-jar.ps1 -NFHome '{0}' -Port {1} -BackupId '{2}'" -f $s.NFHome, $s.Port, (Split-Path -Leaf $s.BackupDir))
  Write-Log 'ROLLBACK-BAD' ("  5) 本次日志：{0}" -f $script:LogFile)
  Write-Log 'ROLLBACK-BAD' '─────────────────────────────────────────────'
}

function Invoke-Rollback {
  <#  返回退出码：3 = 回滚成功（旧 jar 已在服务）；4 = 回滚失败（最坏情况）  #>
  param([string]$Reason)

  Write-Log 'ROLLBACK' '════════════════ 自动回滚开始 ════════════════'
  Write-Log 'ROLLBACK' ("原因：{0}" -f $Reason)
  $s = $script:S
  Write-Log 'ROLLBACK' ("备份 jar：{0}（sha256 {1}）" -f $s.BackupJar, $s.OldSha)

  Write-Log 'ROLLBACK' '1/4 停掉当前实例'
  $stopped = Stop-Instance -CliJar $s.BackupJar -TargetPort $s.Port -DataRoot $s.NFHome -TimeoutSec $s.StopTimeoutSec
  if (-not $stopped) {
    Write-Log 'ROLLBACK-BAD' '回滚失败：当前实例停不下来（端口仍被占用），无法安全还原 jar'
    Write-BadCaseHelp
    return $EXIT_ROLLBACK_BAD
  }

  Write-Log 'ROLLBACK' '2/4 还原旧 jar'
  try {
    Copy-Item -LiteralPath $s.BackupJar -Destination $s.TargetJar -Force
  } catch {
    Write-Log 'ROLLBACK-BAD' ("回滚失败：还原拷贝出错 —— {0}" -f $_.Exception.Message)
    Write-BadCaseHelp
    return $EXIT_ROLLBACK_BAD
  }
  $restoredSha = Get-Sha256 -Path $s.TargetJar
  if ($restoredSha -ne $s.OldSha) {
    Write-Log 'ROLLBACK-BAD' ("回滚失败：还原后 sha256 不符（{0} ≠ {1}）" -f $restoredSha, $s.OldSha)
    Write-BadCaseHelp
    return $EXIT_ROLLBACK_BAD
  }
  Write-Log 'ROLLBACK' ("还原完成，sha256 校验通过：{0}" -f $restoredSha)

  Write-Log 'ROLLBACK' '3/4 用旧 jar 重新启动'
  $probe = Start-Instance -Jar $s.TargetJar -TargetPort $s.Port -DataRoot $s.NFHome -TimeoutSec $s.ReadyTimeoutSec `
                          -ExpectJs $s.OldJs -ExpectCssArr $s.OldCss
  if (-not $probe.Ok) {
    Write-Log 'ROLLBACK-BAD' ("回滚失败：旧 jar 起不来/探活不过 —— {0}" -f $probe.Detail)
    Write-BadCaseHelp
    return $EXIT_ROLLBACK_BAD
  }

  Write-Log 'ROLLBACK' '4/4 复核'
  $finalSha = Get-Sha256 -Path $s.TargetJar
  $finalHealth = Get-Health -TargetPort $s.Port
  $finalAssets = Get-ServedAssets -TargetPort $s.Port
  Write-Log 'ROLLBACK' ("复核：sha256={0}；health={1}；entry {2}" -f $finalSha, $finalHealth.Detail, $finalAssets.Detail)
  if ($finalSha -ne $s.OldSha -or -not $finalHealth.Ok) {
    Write-Log 'ROLLBACK-BAD' '回滚失败：复核不通过'
    Write-BadCaseHelp
    return $EXIT_ROLLBACK_BAD
  }
  Write-Log 'ROLLBACK' '════════════════ 回滚完成：旧 jar 已在服务 ════════════════'
  Write-Log 'ROLLBACK' ("实例：http://127.0.0.1:{0}/  （pid {1}）" -f $s.Port, (@(Get-ListenerPids -TargetPort $s.Port) -join ','))
  return $EXIT_TOUCH_BAD
}

function Refuse {
  param([string]$Reason, [int]$Code = $EXIT_REFUSED)
  Write-Log 'REFUSED' ("拒绝执行：{0}" -f $Reason)
  Write-Log 'REFUSED' ("未做任何改动（jar 未动、实例未停）。日志：{0}" -f $script:LogFile)
  exit $Code
}

function Remove-OldBackups {
  param([string]$Root, [int]$Keep)
  if (-not (Test-Path -LiteralPath $Root)) { return }
  $dirs = @(Get-ChildItem -LiteralPath $Root -Directory -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending)
  if ($dirs.Count -le $Keep) { return }
  $dirs | Select-Object -Skip $Keep | ForEach-Object {
    try {
      Remove-Item -LiteralPath $_.FullName -Recurse -Force
      Write-Log 'INFO' ("清理旧备份：{0}" -f $_.FullName)
    } catch {
      Write-Log 'WARN' ("清理旧备份失败：{0}" -f $_.FullName)
    }
  }
}

# ═══════════════════════════════════════════════════════════════════════
# MAIN
# ═══════════════════════════════════════════════════════════════════════

$stamp = (Get-Date).ToString('yyyyMMdd-HHmmss')

try {
  $S.NFHome = Resolve-NFHome -Explicit $NFHome
  $S.Port = $Port
  $S.StopTimeoutSec = $StopTimeoutSec
  $S.ReadyTimeoutSec = $ReadyTimeoutSec
  if (-not $LogDir) { $LogDir = Join-Path (Join-Path $S.NFHome 'logs') 'jar-swap' }
  if (-not $BackupDir) { $BackupDir = Join-Path (Join-Path $S.NFHome 'backups') 'jar-swap' }
  New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
  $script:LogFile = Join-Path $LogDir ("swap-{0}.log" -f $stamp)
  New-Item -ItemType File -Force -Path $script:LogFile | Out-Null
  $script:Java = Resolve-Java -Explicit $JavaExe
} catch {
  Write-Host ("FATAL: {0}" -f $_.Exception.Message) -ForegroundColor Red
  exit $EXIT_STOP_BAD
}

Write-Log 'INFO' '══════════════════════════════════════════════════════'
Write-Log 'INFO' ' stand 换 jar + 重启（带自动回滚）'
Write-Log 'INFO' (" 时间       : {0}" -f (Get-Date).ToString('yyyy-MM-dd HH:mm:ss'))
Write-Log 'INFO' (" 脚本       : {0}" -f $MyInvocation.MyCommand.Path)
Write-Log 'INFO' (" 主机/用户  : {0} / {1}（powershell pid {2}）" -f $env:COMPUTERNAME, $env:USERNAME, $PID)
Write-Log 'INFO' (" NF_HOME    : {0}" -f $S.NFHome)
Write-Log 'INFO' (" 端口       : {0}" -f $S.Port)
Write-Log 'INFO' (" 新 jar     : {0}" -f $NewJar)
Write-Log 'INFO' (" 日志       : {0}" -f $script:LogFile)
Write-Log 'INFO' (" java       : {0}" -f $script:Java)
Write-Log 'INFO' '══════════════════════════════════════════════════════'

# ── STEP 1 前置检查（全部只读） ─────────────────────────────────────────
Start-Step '前置检查（只读）'

try { $NewJarFull = Resolve-FullPath -Path $NewJar -What '新 jar' }
catch { Refuse $_.Exception.Message }
$S.NewJar = $NewJarFull
$newInfo = Get-Item -LiteralPath $NewJarFull
if ($newInfo.Length -lt 10MB) { Refuse ("新 jar 体积异常（{0}）：{1}" -f (Format-Bytes $newInfo.Length), $NewJarFull) }
$S.NewSha = Get-Sha256 -Path $NewJarFull
Write-Log 'INFO' ("新 jar  : {0}  size={1}  sha256={2}" -f $NewJarFull, (Format-Bytes $newInfo.Length), $S.NewSha)
if ($ExpectNewSha256 -and $ExpectNewSha256.Trim().ToLowerInvariant().Replace('-', '') -ne $S.NewSha) {
  Refuse ("新 jar sha256 不符：实际 {0}，期望 {1}" -f $S.NewSha, $ExpectNewSha256)
}

try {
  if ($OldJar) { $S.TargetJar = Resolve-FullPath -Path $OldJar -What '旧 jar（-OldJar）' }
  else { $S.TargetJar = Resolve-InstalledJar -DataRoot $S.NFHome }
} catch { Refuse $_.Exception.Message }
if ($S.TargetJar -eq $S.NewJar) { Refuse '新 jar 与目标位置是同一个文件（换装源=目标），拒绝执行' }

$oldInfo = Get-Item -LiteralPath $S.TargetJar
$S.OldSha = Get-Sha256 -Path $S.TargetJar
Write-Log 'INFO' ("旧 jar  : {0}  size={1}  sha256={2}" -f $S.TargetJar, (Format-Bytes $oldInfo.Length), $S.OldSha)

# 幂等：目标位置已是新 jar
if ($S.OldSha -eq $S.NewSha) {
  $h0 = Get-Health -TargetPort $S.Port
  $a0 = Get-ServedAssets -TargetPort $S.Port
  $newAssets0 = Get-JarAssets -JarPath $S.NewJar
  $entryOk = $true
  if ($newAssets0.Ok -and $newAssets0.Js.Count -gt 0 -and $a0.Ok) {
    $entryOk = (($a0.Js | Where-Object { $newAssets0.Js -contains $_ }).Count -gt 0)
  }
  if ($h0.Ok -and $entryOk) {
    Write-Log 'OK' '目标位置已是新 jar，且实例健康、entry 命中 ⇒ 无需换装（no-op，退出 0）'
    Write-Log 'INFO' ("health: {0}；首页 {1}" -f $h0.Detail, $a0.Detail)
    exit $EXIT_OK
  }
  Refuse ("目标位置已是新 jar，但实例状态不符（health={0}；entry {1}）—— 这是不常见状态，需人工先看一眼" -f $h0.Detail, $a0.Detail)
}

if ($ExpectOldSha256 -and $ExpectOldSha256.Trim().ToLowerInvariant().Replace('-', '') -ne $S.OldSha) {
  Refuse ("旧 jar sha256 不符：实际 {0}，期望 {1}（拒绝执行；如确认可传 -ExpectOldSha256 '' 关闭该校验）" -f $S.OldSha, $ExpectOldSha256)
}

# 端口在服务 + 谁是占用者
$pids = @(Get-ListenerPids -TargetPort $S.Port)
if ($pids.Count -eq 0) {
  Refuse ("端口 {0} 没有监听者 —— 实例当前不在服务；本脚本只做「在线换装」（先启动实例再来）" -f $S.Port)
}
$S.TargetPid = $pids[0]
Write-Log 'INFO' ("端口 {0} 监听 pid：{1}" -f $S.Port, ($pids -join ','))
$cmd = Get-ProcCmdline -ProcId $S.TargetPid
if (-not $cmd) { Refuse ("读不到 pid {0} 的命令行，无法确认它是本实例（拒绝，绝不盲停）" -f $S.TargetPid) }
Write-Log 'INFO' ("pid {0} 命令行：{1}" -f $S.TargetPid, (Protect-Text $cmd))
if ($cmd -notmatch 'nebflow\.Main') { Refuse ("pid {0} 不是 nebflow 实例（命令行无 nebflow.Main）" -f $S.TargetPid) }
if ($cmd.IndexOf($S.TargetJar, [System.StringComparison]::OrdinalIgnoreCase) -lt 0) {
  Refuse ("端口 {0} 上的进程用的不是 {1}（命令行里没有该路径）—— 换装目标与在线实例不一致" -f $S.Port, $S.TargetJar)
}

# 健康 + 当前 entry
$h1 = Get-Health -TargetPort $S.Port
if (-not $h1.Ok) { Refuse ("/api/health 不正常：{0}" -f $h1.Detail) }
Write-Log 'INFO' ("health OK：{0}" -f $h1.Detail)
$a1 = Get-ServedAssets -TargetPort $S.Port
if (-not $a1.Ok) { Refuse ("首页取不到，无法记录换装前 entry：{0}" -f $a1.Detail) }
$S.OldJs = $a1.Js
$S.OldCss = $a1.Css
Write-Log 'INFO' ("换装前首页：{0}" -f $a1.Detail)

# 磁盘上的旧 jar 是否就是正在服务的那份（用 jar 内 index.html 对照线上 entry）
$oldJarAssets = Get-JarAssets -JarPath $S.TargetJar
if ($oldJarAssets.Ok) {
  $jarMatchesLive = $false
  if ($oldJarAssets.Js.Count -gt 0) {
    $jarMatchesLive = (($a1.Js | Where-Object { $oldJarAssets.Js -contains $_ }).Count -gt 0)
  }
  $agreeText = '不一致'
  if ($jarMatchesLive) { $agreeText = '一致 ✓' }
  Write-Log 'INFO' ("旧 jar 内 entry：js=[{0}] css=[{1}]；与线上 entry {2}" -f ($oldJarAssets.Js -join ','), ($oldJarAssets.Css -join ','), $agreeText)
  if (-not $jarMatchesLive) {
    Refuse '磁盘上的旧 jar 与线上正在服务的 entry 不一致 —— 目标文件不是在线实例的代码，换装会白做'
  }
} else {
  Write-Log 'WARN' '无法读取旧 jar 的 web-dist/index.html（非 zip 布局？），跳过「磁盘 jar 与线上 entry 一致」这一校验'
}

# 新 jar 的 entry 期望值（未显式给出则从新 jar 推导）
$newJarAssets = Get-JarAssets -JarPath $S.NewJar
if ($ExpectEntry) { $S.ExpectJs = @($ExpectEntry) }
elseif ($newJarAssets.Ok -and $newJarAssets.Js.Count -gt 0) { $S.ExpectJs = $newJarAssets.Js }
else { $S.ExpectJs = @(); Write-Log 'WARN' '新 jar 里读不到 entry，换装后只校验 /api/health（entry 维度不校验）' }
if ($ExpectCss) { $S.ExpectCss = @($ExpectCss) }
elseif (-not $ExpectEntry -and $newJarAssets.Ok -and $newJarAssets.Css.Count -gt 0) { $S.ExpectCss = $newJarAssets.Css }
else { $S.ExpectCss = @() }
Write-Log 'INFO' ("期望 entry（换装后）：js=[{0}] css=[{1}]" -f ($S.ExpectJs -join ','), ($S.ExpectCss -join ','))

if ($CheckOnly) {
  Complete-Step '前置检查全部通过（-CheckOnly：到此为止，未改动任何东西）'
  exit $EXIT_OK
}

Complete-Step '前置检查全部通过'

# ── 自保：本脚本是否跑在目标实例进程树里 ───────────────────────────────
$tree = Test-RunsInsideTarget -TargetPid $S.TargetPid
Write-Log 'INFO' ("本脚本进程链：{0}" -f ($tree.Chain -join ' ← '))
if ($tree.Inside -and -not $ForceInsideInstance) {
  Refuse ("本脚本跑在目标实例 pid {0} 的进程树里 —— stop 会把本脚本和调用它的会话一起杀掉，换装必然半途而废。请在实例外的终端（cmd / PowerShell / 计划任务）里跑；确知后果可加 -ForceInsideInstance" -f $S.TargetPid)
}

# ── STEP 2 优雅停 ──────────────────────────────────────────────────────
Start-Step ("优雅停止（CLI stop；端口 {0}）" -f $S.Port)
$stopped = Stop-Instance -CliJar $S.TargetJar -TargetPort $S.Port -DataRoot $S.NFHome -TimeoutSec $S.StopTimeoutSec
if (-not $stopped) {
  Refuse ("停止失败（见上）：实例没停住，jar 未改动。请手工确认 pid {0} 后再来" -f $S.TargetPid) $EXIT_STOP_BAD
}
if (Test-ProcAlive -ProcId $S.TargetPid) {
  Write-Log 'WARN' ("pid {0} 仍存活但已不监听 {1}（进程退出中）" -f $S.TargetPid, $S.Port)
  Start-Sleep -Seconds 2
  if (Test-ProcAlive -ProcId $S.TargetPid) {
    Refuse ("pid {0} 仍存活，jar 文件可能仍被占用，拒绝在此时换装" -f $S.TargetPid) $EXIT_STOP_BAD
  }
}
Complete-Step ("实例已停（端口 {0} 空）" -f $S.Port)

# ── STEP 3 备份旧 jar ─────────────────────────────────────────────────
Start-Step '备份旧 jar'
$S.BackupDir = Join-Path $BackupDir ("swap-{0}" -f $stamp)
New-Item -ItemType Directory -Force -Path $S.BackupDir | Out-Null
$S.BackupJar = Join-Path $S.BackupDir (Split-Path -Leaf $S.TargetJar)
try {
  Copy-Item -LiteralPath $S.TargetJar -Destination $S.BackupJar -Force
} catch {
  Refuse ("备份拷贝失败：{0}（jar 未改动）" -f $_.Exception.Message) $EXIT_STOP_BAD
}
$backupSha = Get-Sha256 -Path $S.BackupJar
if ($backupSha -ne $S.OldSha) {
  Refuse ("备份校验失败：备份 sha256 {0} ≠ 原 {1}（jar 未改动）" -f $backupSha, $S.OldSha) $EXIT_STOP_BAD
}
$meta = [ordered]@{
  schemaVersion = 1
  createdAt     = (Get-Date).ToString('o')
  nfHome        = $S.NFHome
  port          = $S.Port
  oldJarPath    = $S.TargetJar
  backupJarPath = $S.BackupJar
  oldSha256     = $S.OldSha
  oldEntryJs    = $S.OldJs
  oldEntryCss   = $S.OldCss
  newJarPath    = $S.NewJar
  newSha256     = $S.NewSha
  newEntryJs    = $S.ExpectJs
  newEntryCss   = $S.ExpectCss
  stoppedPid    = $S.TargetPid
  log           = $script:LogFile
}
$metaPath = Join-Path $S.BackupDir 'backup.json'
($meta | ConvertTo-Json -Depth 4) | Set-Content -LiteralPath $metaPath -Encoding UTF8
Write-Log 'INFO' ("备份：{0}（sha256 {1}）+ 清单 {2}" -f $S.BackupJar, $backupSha, $metaPath)
Complete-Step '旧 jar 已备份并校验'

# ── STEP 4 换装 ───────────────────────────────────────────────────────
Start-Step '换装新 jar'
$copied = $false
for ($i = 1; $i -le 6; $i++) {
  try {
    Copy-Item -LiteralPath $S.NewJar -Destination $S.TargetJar -Force
    $copied = $true
    break
  } catch {
    Write-Log 'WARN' ("第 {0} 次拷贝失败（{1}），500ms 后重试" -f $i, $_.Exception.Message)
    Start-Sleep -Milliseconds 500
  }
}
if (-not $copied) {
  $rc = Invoke-Rollback -Reason '换装拷贝失败（目标文件仍为旧 jar 或半写状态）'
  exit $rc
}
$installedSha = Get-Sha256 -Path $S.TargetJar
if ($installedSha -ne $S.NewSha) {
  Write-Log 'ERROR' ("换装后 sha256 不符：{0} ≠ {1}" -f $installedSha, $S.NewSha)
  $rc = Invoke-Rollback -Reason '换装后 sha256 校验失败'
  exit $rc
}
Write-Log 'INFO' ("目标位置已是新 jar：{0}（sha256 {1}）" -f $S.TargetJar, $installedSha)
Complete-Step '新 jar 落位并校验'

# ── STEP 5/6 启动 + 探活 ──────────────────────────────────────────────
Start-Step '启动并探活（/api/health + 首页 entry hash）'
$probe = Start-Instance -Jar $S.TargetJar -TargetPort $S.Port -DataRoot $S.NFHome -TimeoutSec $S.ReadyTimeoutSec `
                        -ExpectJs $S.ExpectJs -ExpectCssArr $S.ExpectCss
if (-not $probe.Ok) {
  Write-Log 'ERROR' ("启动/探活失败：{0}" -f $probe.Detail)
  $rc = Invoke-Rollback -Reason ("启动或探活失败：{0}" -f $probe.Detail)
  exit $rc
}
$finalHealth = Get-Health -TargetPort $S.Port
$finalAssets = Get-ServedAssets -TargetPort $S.Port
Complete-Step ("实例可用：health={0}；首页 {1}" -f $finalHealth.Detail, $finalAssets.Detail)

# ── STEP 7 收尾 ───────────────────────────────────────────────────────
Remove-OldBackups -Root $BackupDir -Keep $BackupKeep

Write-Log 'OK' '══════════════════════════════════════════════════════'
Write-Log 'OK' ' 换装成功'
Write-Log 'OK' (" 实例       : http://127.0.0.1:{0}/" -f $S.Port)
Write-Log 'OK' (" 监听 pid   : {0}" -f ((@(Get-ListenerPids -TargetPort $S.Port)) -join ','))
Write-Log 'OK' (" jar        : {0}" -f $S.TargetJar)
Write-Log 'OK' (" jar sha256 : {0}" -f $installedSha)
Write-Log 'OK' (" health     : {0}" -f $finalHealth.Detail)
Write-Log 'OK' (" 首页 entry : {0}（期望 js=[{1}] css=[{2}]）" -f $finalAssets.Detail, ($S.ExpectJs -join ','), ($S.ExpectCss -join ','))
Write-Log 'OK' (" 备份       : {0}" -f $S.BackupJar)
Write-Log 'OK' (" 回退命令   : powershell -NoProfile -File ops\rollback-jar.ps1 -NFHome '{0}' -Port {1}" -f $S.NFHome, $S.Port)
Write-Log 'OK' (" 日志       : {0}" -f $script:LogFile)
Write-Log 'OK' '══════════════════════════════════════════════════════'
exit $EXIT_OK

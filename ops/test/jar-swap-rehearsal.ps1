#Requires -Version 5.1
<#
.SYNOPSIS
  ops\swap-jar.ps1 / ops\rollback-jar.ps1 的隔离演练台（Windows）——**分相位**执行。

.DESCRIPTION
  为什么分相位：2026-11-01 第一版「一条命令跑全链」的演练台在后台跑时零输出、卡住不动
  （进程还活着、文件只走到一步），无法判断卡在哪。本版把整条链拆成**单相位、短时长、
  有界超时、逐行即时输出**的动作，一次只跑一步：

    prepare     搭隔离实例（独立 home + 独立 jar 副本 + 独立 current-version），记录基线
    up          起隔离实例并探活（有界 ≤120s）——先单独证明「起 + 探活」这一段能通
    forward     DRILL A 正向：停 → 换 → 起 → 探活（期望 swap 退出码 0）
    idempotent  DRILL A2 幂等：再跑一遍（期望退出码 0，no-op）
    rollback    DRILL R0 独立回退脚本把实例退回旧 jar（期望退出码 0）
    corrupt     DRILL B 回滚①：新 jar 是损坏文件（期望 swap 退出码 3，实例仍可用且回到旧 jar）
    mismatch    DRILL C 回滚②：entry 期望值被改坏（期望 swap 退出码 3，实例仍可用且回到旧 jar）
    status      只读：打印隔离实例与在线实例当前状态
    cleanup     停实例 + 清临时目录 + 复核端口与在线实例未受影响
    all         顺序跑全部（仅供无人值守；排障请逐相位跑）

  隔离纪律：只碰 -Port（缺省 6157）与 -Root 下的 home；在线实例（缺省 6138）只做只读快照。

  超时纪律（本版硬要求）：
    * 每个「等实例」都有显式上限（StopTimeoutSec / ReadyTimeoutSec），到点判失败，不无限等。
    * 每次调子脚本都有 -ChildTimeoutSec 上限；到点报 CHILD-TIMEOUT 并让相位失败，**不强杀子进程**。
    * 子脚本的 stdout/stderr 重定向到文件（不共享父进程的管道），避免父/子同时写同一个
      捕获管道时的阻塞；子进程结束后再回显文件内容。

.PARAMETER Phase
  要跑的相位。

.PARAMETER Port
  演练端口（缺省 6157，必须 ≠ -LivePort）。

.PARAMETER Root
  演练根（缺省 %TEMP%\stand-jar-swap-rehearsal）。

.PARAMETER NewJar
  新 jar；缺省自动在仓内 target\prereq4-rw 或主 checkout 里找 *prereq4*.jar。

.PARAMETER ChildTimeoutSec
  单个子脚本调用的上限（秒，缺省 240）。

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File ops\test\jar-swap-rehearsal.ps1 -Phase prepare
  powershell -NoProfile -ExecutionPolicy Bypass -File ops\test\jar-swap-rehearsal.ps1 -Phase up
  powershell -NoProfile -ExecutionPolicy Bypass -File ops\test\jar-swap-rehearsal.ps1 -Phase forward

.NOTES
  退出码：0 该相位通过 | 1 该相位失败 | 2 环境不满足（端口被占 / 缺 jar / 相位前置未做）
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)]
  [ValidateSet('prepare', 'up', 'forward', 'idempotent', 'rollback', 'corrupt', 'mismatch', 'status', 'cleanup', 'all')]
  [string]$Phase,
  [int]$Port = 6157,
  [string]$Root,
  [string]$NewJar,
  [int]$LivePort = 6138,
  [string]$LiveHome,
  [int]$ChildTimeoutSec = 240
)

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$repoRoot = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSCommandPath))
$swapScript = Join-Path $repoRoot 'ops\swap-jar.ps1'
$rollbackScript = Join-Path $repoRoot 'ops\rollback-jar.ps1'
if (-not $Root) { $Root = Join-Path $env:TEMP 'stand-jar-swap-rehearsal' }
if (-not $LiveHome) { $LiveHome = Join-Path $env:USERPROFILE '.stand' }
if (-not $NewJar) {
  foreach ($r in @($repoRoot, 'D:\workspace@zcode\nebflow\nebflow-stand')) {
    $d = Join-Path $r 'target\prereq4-rw'
    if (Test-Path -LiteralPath $d) {
      $c = @(Get-ChildItem -LiteralPath $d -Filter 'stand-assembly-*-prereq4-*.jar' -ErrorAction SilentlyContinue |
             Sort-Object LastWriteTime -Descending)
      if ($c.Count -gt 0) { $NewJar = $c[0].FullName; break }
    }
  }
}

$Version = '2026.10.26-beta.2'
$DrillHome = Join-Path $Root 'home'
$DrillLogs = Join-Path $Root 'logs'
$DrillBackups = Join-Path $Root 'backups'
$DrillJunk = Join-Path $Root 'junk'
$StateFile = Join-Path $Root 'state.json'
$DrillJar = Join-Path $DrillHome ("bin\versions\{0}\stand-assembly-{0}.jar" -f $Version)

# ═══════════════════════════════════════════════════════════════════════
# 即时输出 + 探针（每行带时间戳，写一行刷一行）
# ═══════════════════════════════════════════════════════════════════════

function Say {
  param([string]$Text = '')
  [Console]::Out.WriteLine(("[{0}] {1}" -f (Get-Date).ToString('HH:mm:ss.fff'), $Text))
  [Console]::Out.Flush()
}

function Fail {
  param([string]$Why)
  Say "PHASE ${Phase}: FAIL —— $Why"
  exit 1
}

function Pass {
  param([string]$What)
  Say "PHASE ${Phase}: PASS —— $What"
  exit 0
}

function Get-Sha256 { param([string]$Path) (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Format-Bytes { param([long]$B) '{0:N0} B ({1:N1} MiB)' -f $B, ($B / 1MB) }

function Get-ListenerPids {
  param([int]$P)
  $found = @()
  try {
    foreach ($r in (& netstat -ano -p tcp 2>$null)) {
      if ($r -match '^\s*TCP\s+\S+:(\d+)\s+\S+\s+LISTENING\s+(\d+)\s*$') {
        if ([int]$Matches[1] -eq $P) { $found += [int]$Matches[2] }
      }
    }
  } catch { }
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

function Get-HealthDetail {
  param([int]$P)
  try {
    $r = Invoke-WebRequest -Uri ("http://127.0.0.1:{0}/api/health" -f $P) -UseBasicParsing -TimeoutSec 5
    $j = $r.Content | ConvertFrom-Json
    return @{ Ok = ([int]$r.StatusCode -eq 200 -and [string]$j.status -eq 'ok'); Detail = ("HTTP {0} status='{1}' version='{2}'" -f [int]$r.StatusCode, $j.status, $j.version) }
  } catch { return @{ Ok = $false; Detail = $_.Exception.Message } }
}

function Get-EntryDetail {
  param([int]$P)
  try {
    $r = Invoke-WebRequest -Uri ("http://127.0.0.1:{0}/" -f $P) -UseBasicParsing -TimeoutSec 10
    $js = @([regex]::Matches([string]$r.Content, 'app-[A-Za-z0-9]+\.js') | ForEach-Object { $_.Value } | Select-Object -Unique)
    $css = @([regex]::Matches([string]$r.Content, 'app-[A-Za-z0-9]+\.css') | ForEach-Object { $_.Value } | Select-Object -Unique)
    return @{ Ok = $true; Js = $js; Css = $css; Detail = ("js=[{0}] css=[{1}]" -f ($js -join ','), ($css -join ',')) }
  } catch { return @{ Ok = $false; Js = @(); Css = @(); Detail = $_.Exception.Message } }
}

function Resolve-Java {
  if ($env:JAVA_HOME) {
    $c = Join-Path $env:JAVA_HOME 'bin\java.exe'
    if (Test-Path -LiteralPath $c) { return $c }
  }
  $c = Get-Command java -ErrorAction SilentlyContinue
  if ($c) { return $c.Source }
  throw '找不到 java'
}

function Invoke-JarCli {
  <#  直接跑 jar 自己的 CLI 子命令（== `stand <sub>`），有界等待，逐行即时打印  #>
  param([string]$Jar, [string[]]$CliArgs, [int]$TimeoutSec = 120)
  $all = @('-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-jar', $Jar) + $CliArgs
  Say ("  RUN java " + (($all | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } }) -join ' '))
  $psi = New-Object System.Diagnostics.ProcessStartInfo
  $psi.FileName = $script:Java
  $psi.Arguments = (($all | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } }) -join ' ')
  $psi.UseShellExecute = $false
  $psi.RedirectStandardOutput = $true
  $psi.RedirectStandardError = $true
  $psi.RedirectStandardInput = $true
  $psi.WorkingDirectory = $Root
  $p = [System.Diagnostics.Process]::Start($psi)
  try { $p.StandardInput.Close() } catch { }
  $outTask = $p.StandardOutput.ReadToEndAsync()
  $errTask = $p.StandardError.ReadToEndAsync()
  if (-not $p.WaitForExit($TimeoutSec * 1000)) {
    Say ("  CHILD-TIMEOUT：java 子命令 {0}s 未退出（不强杀，相位失败）" -f $TimeoutSec)
    return @{ Code = -9; Out = '' ; Timeout = $true }
  }
  $p.WaitForExit()
  $out = [string]$outTask.Result + [string]$errTask.Result
  foreach ($l in ($out -split "`r?`n")) { if ($l -and $l.Trim()) { Say ("  > " + $l.Trim()) } }
  Say ("  RC exit={0}" -f $p.ExitCode)
  return @{ Code = $p.ExitCode; Out = $out; Timeout = $false }
}

function Invoke-ChildScript {
  <#
    用独立 powershell 子进程跑被测脚本。stdout/stderr 落文件（不共享父管道），
    有界等待；子进程结束后回显它的输出。返回 @{ Code; Out; Log; Timeout }
  #>
  param([string]$Script, [string[]]$ScriptArgs)
  $stamp = (Get-Date).ToString('HHmmss-fff')
  $tag = [System.IO.Path]::GetFileNameWithoutExtension($Script) + '-' + $stamp
  $outFile = Join-Path $DrillLogs ("child-{0}.out" -f $tag)
  New-Item -ItemType Directory -Force -Path $DrillLogs | Out-Null

  # 为什么走 cmd /c 而不是 Start-Process -PassThru：后者返回的 Process 对象在这里拿不到
  # ExitCode（实测为空），而 cmd /c 会把它转发的子进程退出码原样作为自己的退出码。
  # 输出重定向由 cmd 做（> file 2>&1），子脚本及其孙进程都写文件、不占本进程的管道。
  $inner = 'powershell.exe -NoProfile -ExecutionPolicy Bypass -File "' + $Script + '"'
  foreach ($a in $ScriptArgs) { $inner += ' "' + $a + '"' }
  $cmdLine = '/c ' + $inner + ' > "' + $outFile + '" 2>&1'
  Say ("  CHILD cmd " + $cmdLine)
  $t0 = Get-Date
  $psi = New-Object System.Diagnostics.ProcessStartInfo
  $psi.FileName = "$env:ComSpec"
  $psi.Arguments = $cmdLine
  $psi.UseShellExecute = $false
  $psi.CreateNoWindow = $true
  $p = [System.Diagnostics.Process]::Start($psi)
  $done = $p.WaitForExit($ChildTimeoutSec * 1000)
  if (-not $done) {
    Say ("  CHILD-TIMEOUT：子脚本 {0}s 未退出（pid {1}，不强杀；相位失败，请人工确认）" -f $ChildTimeoutSec, $p.Id)
    return @{ Code = -9; Out = ''; Log = $outFile; Timeout = $true; Seconds = [math]::Round(((Get-Date) - $t0).TotalSeconds, 1) }
  }
  $p.WaitForExit()
  $secs = [math]::Round(((Get-Date) - $t0).TotalSeconds, 1)
  $out = ''
  if (Test-Path -LiteralPath $outFile) { $out += (Get-Content -LiteralPath $outFile -Raw -Encoding UTF8) }
  foreach ($l in ($out -split "`r?`n")) { if ($l -and $l.Trim()) { Say ("  | " + $l.TrimEnd()) } }
  $code = $p.ExitCode
  Say ("  RC exit={0}  ({1}s)" -f $code, $secs)
  return @{ Code = $code; Out = $out; Log = $outFile; Timeout = $false; Seconds = $secs }
}

function Wait-Up {
  param([int]$P, [int]$TimeoutSec, [string]$MustContainJs)
  $t0 = Get-Date
  $deadline = $t0.AddSeconds($TimeoutSec)
  $last = '未探活'
  while ((Get-Date) -lt $deadline) {
    $h = Get-HealthDetail -P $P
    if ($h.Ok) {
      $a = Get-EntryDetail -P $P
      if (-not $MustContainJs -or ($a.Js -contains $MustContainJs)) {
        return @{ Ok = $true; Health = $h.Detail; Entry = $a.Detail; Js = $a.Js; Sec = [math]::Round(((Get-Date) - $t0).TotalSeconds, 1) }
      }
      $last = "健康 ok 但 entry 不符：$($a.Detail)（期望含 $MustContainJs）"
    } else { $last = "health 未通过：$($h.Detail)" }
    Start-Sleep -Milliseconds 700
  }
  return @{ Ok = $false; Health = $last; Entry = (Get-EntryDetail -P $P).Detail; Js = @(); Sec = [math]::Round(((Get-Date) - $t0).TotalSeconds, 1) }
}

function Wait-PortFree {
  param([int]$P, [int]$TimeoutSec)
  $deadline = (Get-Date).AddSeconds($TimeoutSec)
  while ((Get-Date) -lt $deadline) {
    if ((@(Get-ListenerPids -P $P)).Count -eq 0) { return $true }
    Start-Sleep -Milliseconds 400
  }
  return ((@(Get-ListenerPids -P $P)).Count -eq 0)
}

function Get-LiveSnapshot {
  $pids = @(Get-ListenerPids -P $LivePort)
  $h = Get-HealthDetail -P $LivePort
  $a = Get-EntryDetail -P $LivePort
  $cmd = $null
  if ($pids.Count -gt 0) { $cmd = Get-ProcCmdline -ProcId $pids[0] }
  return [ordered]@{
    At       = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
    Pids     = ($pids -join ',')
    HealthOk = $h.Ok
    Health   = $h.Detail
    Entry    = $a.Detail
    Cmdline  = $(if ($cmd) { $cmd } else { '(无监听者)' })
  }
}

function Save-State { param($Obj) ($Obj | ConvertTo-Json -Depth 5) | Set-Content -LiteralPath $StateFile -Encoding UTF8 }
function Load-State {
  if (-not (Test-Path -LiteralPath $StateFile)) { return $null }
  return (Get-Content -LiteralPath $StateFile -Raw -Encoding UTF8 | ConvertFrom-Json)
}

function Latest-Log {
  param([string]$Pattern)
  return (Get-ChildItem -LiteralPath $DrillLogs -Filter $Pattern -ErrorAction SilentlyContinue |
          Sort-Object LastWriteTime -Descending | Select-Object -First 1)
}

# ═══════════════════════════════════════════════════════════════════════
# 相位
# ═══════════════════════════════════════════════════════════════════════

function Phase-Prepare {
  Say "── prepare：搭隔离实例 ──"
  if ($Port -eq $LivePort) { Say "拒绝：演练端口 = 在线端口"; exit 2 }
  if (-not $NewJar -or -not (Test-Path -LiteralPath $NewJar)) { Say "拒绝：新 jar 不存在（$NewJar）"; exit 2 }
  $occ = @(Get-ListenerPids -P $Port)
  if ($occ.Count -gt 0) { Say ("拒绝：演练端口 {0} 已被 pid {1} 占用（{2}）" -f $Port, ($occ -join ','), (Get-ProcCmdline -ProcId $occ[0])); exit 2 }

  $live = Get-LiveSnapshot
  Say ("在线实例（只读快照）：pid {0}；{1}；{2}" -f $live.Pids, $live.Health, $live.Entry)

  # 旧 jar 取自在线实例自己的 jar（只读拷贝）
  $liveJar = $null
  if ($live.Cmdline -match '-cp\s+"?([^"]+\.jar)"?') { $liveJar = $Matches[1].Trim() }
  if (-not $liveJar -or -not (Test-Path -LiteralPath $liveJar)) {
    $ptr = Join-Path (Join-Path $LiveHome 'bin') 'current-version'
    if (Test-Path -LiteralPath $ptr) {
      $v = (Get-Content -LiteralPath $ptr -TotalCount 1).Trim()
      $cand = Join-Path (Join-Path $LiveHome 'bin') ("versions\{0}\stand-assembly-{0}.jar" -f $v)
      if (Test-Path -LiteralPath $cand) { $liveJar = $cand }
    }
  }
  if (-not $liveJar) { Say '拒绝：找不到在线实例的 jar'; exit 2 }

  if (Test-Path -LiteralPath $Root) { Say ("清残留：{0}" -f $Root); Remove-Item -LiteralPath $Root -Recurse -Force }
  New-Item -ItemType Directory -Force -Path $DrillLogs, $DrillBackups, $DrillJunk | Out-Null
  New-Item -ItemType Directory -Force -Path (Split-Path -Parent $DrillJar) | Out-Null
  $t0 = Get-Date
  Copy-Item -LiteralPath $liveJar -Destination $DrillJar -Force
  [System.IO.File]::WriteAllText((Join-Path $DrillHome 'bin\current-version'), $Version)
  $oldSha = Get-Sha256 -Path $DrillJar
  Say ("旧 jar 副本：{0}（{1}；{2}s）sha256={3}" -f $DrillJar, (Format-Bytes (Get-Item -LiteralPath $DrillJar).Length), [math]::Round(((Get-Date) - $t0).TotalSeconds, 1), $oldSha)
  Say ("来源：{0}（在线实例在用，只读拷贝）" -f $liveJar)

  # 新 jar 的 entry（从 jar 内 web-dist/index.html 读，用于判定换装是否真的生效）
  $newSha = Get-Sha256 -Path $NewJar
  $newEntry = $null
  try {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($NewJar)
    $e = $zip.Entries | Where-Object { $_.FullName -eq 'web-dist/index.html' } | Select-Object -First 1
    if ($e) {
      $sr = New-Object System.IO.StreamReader($e.Open()); $html = $sr.ReadToEnd(); $sr.Dispose()
      $m = [regex]::Match($html, 'app-[A-Za-z0-9]+\.js')
      if ($m.Success) { $newEntry = $m.Value }
    }
    $zip.Dispose()
  } catch { }
  Say ("新 jar：{0}（{1}）sha256={2}；entry 期望 {3}" -f $NewJar, (Format-Bytes (Get-Item -LiteralPath $NewJar).Length), $newSha, $newEntry)

  Save-State ([ordered]@{
      port = $Port; root = $Root; drillHome = $DrillHome; drillJar = $DrillJar
      oldSha = $oldSha; newSha = $newSha; newEntry = $newEntry
      liveBefore = $live
    })
  Pass ("隔离 home 就绪（{0}）；旧 jar {1}；新 jar {2}" -f $DrillHome, $oldSha.Substring(0, 12), $newSha.Substring(0, 12))
}

function Phase-Up {
  Say "── up：起隔离实例并探活（先单独验证这 1 段） ──"
  $st = Load-State
  if (-not $st) { Say '拒绝：先跑 -Phase prepare'; exit 2 }
  if (-not (Test-Path -LiteralPath $DrillJar)) { Say '拒绝：演练 jar 不存在，先跑 prepare'; exit 2 }
  if ((@(Get-ListenerPids -P $Port)).Count -gt 0) { Say ("拒绝：端口 {0} 已被占用，先跑 cleanup" -f $Port); exit 2 }

  $r = Invoke-JarCli -Jar $DrillJar -CliArgs @('start', '--home', $DrillHome, '--port', "$Port", '--no-browser') -TimeoutSec 180
  if ($r.Code -ne 0) { Fail ("start exit={0}" -f $r.Code) }
  $up = Wait-Up -P $Port -TimeoutSec 120
  if (-not $up.Ok) { Fail ("起不来/探活不过：{0}" -f $up.Health) }
  $sha = Get-Sha256 -Path $DrillJar
  Say ("已就绪（{0}s）：{1}；首页 {2}" -f $up.Sec, $up.Health, $up.Entry)

  $st | Add-Member -NotePropertyName baseEntry -NotePropertyValue ($up.Js | Select-Object -First 1) -Force
  $st | Add-Member -NotePropertyName baseSha -NotePropertyValue $sha -Force
  Save-State $st
  Pass ("隔离实例在服务（pid {0}；jar {1}；entry {2}）" -f ((@(Get-ListenerPids -P $Port)) -join ','), $sha.Substring(0, 12), $st.baseEntry)
}

function Phase-Forward {
  Say "── forward（DRILL A）：停 → 换 → 起 → 探活 ──"
  $st = Load-State
  if (-not $st) { Say '拒绝：先跑 prepare + up'; exit 2 }
  $r = Invoke-ChildScript -Script $swapScript -ScriptArgs @(
    '-NewJar', $NewJar, '-NFHome', $DrillHome, '-Port', "$Port",
    '-LogDir', $DrillLogs, '-BackupDir', $DrillBackups,
    '-StopTimeoutSec', '90', '-ReadyTimeoutSec', '120')
  if ($r.Timeout) { Fail '子脚本超时' }
  $sha = Get-Sha256 -Path $DrillJar
  $up = Wait-Up -P $Port -TimeoutSec 90
  Say ("换装后：jar sha={0}（期望 {1}）；{2}；首页 {3}" -f $sha.Substring(0, 12), $st.newSha.Substring(0, 12), $up.Health, $up.Entry)
  $log = Latest-Log -Pattern 'swap-*.log'
  if ($log) { Say ("swap 日志：{0}" -f $log.FullName) }
  if ($r.Code -ne 0) { Fail ("swap 退出码 {0}（期望 0）" -f $r.Code) }
  if ($sha -ne $st.newSha) { Fail '目标位置不是新 jar' }
  if (-not $up.Ok) { Fail ("换装后探活不过：{0}" -f $up.Health) }
  if ($st.newEntry -and ($up.Js -notcontains $st.newEntry)) { Fail ("entry 未变到 {0}：{1}" -f $st.newEntry, $up.Entry) }
  Pass ("正向换装成功：jar={0}；entry={1}；耗时 {2}s" -f $sha.Substring(0, 12), ($up.Js -join ','), $r.Seconds)
}

function Phase-Idempotent {
  Say "── idempotent（DRILL A2）：再跑一遍应当 no-op ──"
  $st = Load-State
  if (-not $st) { Say '拒绝：先跑 prepare + up + forward'; exit 2 }
  $before = Get-Sha256 -Path $DrillJar
  $r = Invoke-ChildScript -Script $swapScript -ScriptArgs @(
    '-NewJar', $NewJar, '-NFHome', $DrillHome, '-Port', "$Port",
    '-LogDir', $DrillLogs, '-BackupDir', $DrillBackups,
    '-StopTimeoutSec', '90', '-ReadyTimeoutSec', '120')
  if ($r.Timeout) { Fail '子脚本超时' }
  $after = Get-Sha256 -Path $DrillJar
  $up = Wait-Up -P $Port -TimeoutSec 60
  $log = Latest-Log -Pattern 'swap-*.log'
  $logText = ''
  if ($log) { $logText = (Get-Content -LiteralPath $log.FullName -Raw -Encoding UTF8); Say ("swap 日志：{0}" -f $log.FullName) }
  Say ("jar sha 前 {0} / 后 {1}；health {2}" -f $before.Substring(0, 12), $after.Substring(0, 12), $up.Health)
  if ($r.Code -ne 0) { Fail ("swap 退出码 {0}（期望 0）" -f $r.Code) }
  if ($before -ne $after) { Fail 'jar 被改动了（no-op 应该一个字节都不动）' }
  if ($logText -notmatch 'no-op') { Fail '日志里没有 no-op 证据' }
  if (-not $up.Ok) { Fail '实例状态被破坏了' }
  Pass ("幂等：重复跑 exit 0 且未改动 jar（日志含 no-op）；health {0}" -f $up.Health)
}

function Phase-Rollback {
  Say "── rollback（DRILL R0）：独立回退脚本把实例退回旧 jar ──"
  $st = Load-State
  if (-not $st) { Say '拒绝：先跑 prepare'; exit 2 }
  $r = Invoke-ChildScript -Script $rollbackScript -ScriptArgs @(
    '-NFHome', $DrillHome, '-Port', "$Port", '-LogDir', $DrillLogs, '-BackupDir', $DrillBackups,
    '-StopTimeoutSec', '90', '-ReadyTimeoutSec', '120')
  if ($r.Timeout) { Fail '子脚本超时' }
  $sha = Get-Sha256 -Path $DrillJar
  $up = Wait-Up -P $Port -TimeoutSec 90
  $log = Latest-Log -Pattern 'rollback-*.log'
  if ($log) { Say ("rollback 日志：{0}" -f $log.FullName) }
  Say ("回退后：jar sha={0}（期望旧 {1}）；{2}；首页 {3}" -f $sha.Substring(0, 12), $st.oldSha.Substring(0, 12), $up.Health, $up.Entry)
  if ($r.Code -ne 0) { Fail ("rollback 退出码 {0}（期望 0）" -f $r.Code) }
  if ($sha -ne $st.oldSha) { Fail '目标位置不是旧 jar' }
  if (-not $up.Ok) { Fail ("回退后探活不过：{0}" -f $up.Health) }
  if ($st.baseEntry -and ($up.Js -notcontains $st.baseEntry)) { Fail ("entry 未回到 {0}：{1}" -f $st.baseEntry, $up.Entry) }
  Pass ("独立回退可用：jar={0}；entry={1}；health ok" -f $sha.Substring(0, 12), ($up.Js -join ','))
}

function Ensure-OldJar {
  <#  回滚演练的前置：先把演练实例复位到「旧 jar + 健康 + entry 是旧值」  #>
  param($St)
  $sha = Get-Sha256 -Path $DrillJar
  if ($sha -eq $St.oldSha) {
    $h = Get-HealthDetail -P $Port
    $a = Get-EntryDetail -P $Port
    if ($h.Ok -and (-not $St.baseEntry -or ($a.Js -contains $St.baseEntry))) {
      Say ("  前置：已在旧 jar 且实例健康（{0}；{1}）" -f $sha.Substring(0, 12), $h.Detail)
      return
    }
  }
  Say ("  前置：当前 jar {0}（旧 jar 为 {1}），先用 rollback-jar.ps1 复位" -f $sha.Substring(0, 12), $St.oldSha.Substring(0, 12))
  $r = Invoke-ChildScript -Script $rollbackScript -ScriptArgs @(
    '-NFHome', $DrillHome, '-Port', "$Port", '-LogDir', $DrillLogs, '-BackupDir', $DrillBackups,
    '-StopTimeoutSec', '90', '-ReadyTimeoutSec', '120')
  if ($r.Code -ne 0) { Fail ("复位失败：rollback exit={0}" -f $r.Code) }
  $up = Wait-Up -P $Port -TimeoutSec 90
  if (-not $up.Ok) { Fail ("复位后实例不可用：{0}" -f $up.Health) }
  Say ("  前置：复位完成（jar {0}；{1}；首页 {2}）" -f (Get-Sha256 -Path $DrillJar).Substring(0, 12), $up.Health, $up.Entry)
}

function Phase-Corrupt {
  Say "── corrupt（DRILL B）：新 jar 是损坏文件 ⇒ 期望自动回滚 ──"
  $st = Load-State
  if (-not $st) { Say '拒绝：先跑 prepare'; exit 2 }
  Ensure-OldJar -St $st
  $bad = Join-Path $DrillJunk 'stand-assembly-BROKEN.jar'
  New-Item -ItemType Directory -Force -Path $DrillJunk | Out-Null
  $src = [System.IO.File]::OpenRead($NewJar)
  $buf = New-Object byte[] 1048576
  $dst = [System.IO.File]::Create($bad)
  $wrote = 0
  try {
    while ($wrote -lt (12 * 1048576)) {
      $n = $src.Read($buf, 0, $buf.Length)
      if ($n -le 0) { break }
      $dst.Write($buf, 0, $n); $wrote += $n
    }
  } finally { $dst.Dispose(); $src.Dispose() }
  Say ("损坏 jar：{0}（{1}，截断；java 会直接报 Invalid/corrupt）" -f $bad, (Format-Bytes (Get-Item -LiteralPath $bad).Length))

  $r = Invoke-ChildScript -Script $swapScript -ScriptArgs @(
    '-NewJar', $bad, '-NFHome', $DrillHome, '-Port', "$Port",
    '-LogDir', $DrillLogs, '-BackupDir', $DrillBackups,
    '-ExpectEntry', $st.newEntry, '-StopTimeoutSec', '90', '-ReadyTimeoutSec', '60')
  if ($r.Timeout) { Fail '子脚本超时' }
  $sha = Get-Sha256 -Path $DrillJar
  $up = Wait-Up -P $Port -TimeoutSec 120
  $log = Latest-Log -Pattern 'swap-*.log'
  $logText = ''
  if ($log) { $logText = (Get-Content -LiteralPath $log.FullName -Raw -Encoding UTF8); Say ("swap 日志：{0}" -f $log.FullName) }
  Say ("演练后：swap exit={0}（期望 3）；jar sha={1}（期望旧 {2}）；{3}；首页 {4}" -f $r.Code, $sha.Substring(0, 12), $st.oldSha.Substring(0, 12), $up.Health, $up.Entry)
  if ($r.Code -ne 3) { Fail ("swap 退出码 {0}，期望 3（换装失败但回滚成功）" -f $r.Code) }
  if ($logText -notmatch '自动回滚开始') { Fail '日志里没有「自动回滚开始」' }
  if ($logText -notmatch '回滚完成') { Fail '日志里没有「回滚完成」' }
  if ($sha -ne $st.oldSha) { Fail '实例未回到旧 jar' }
  if (-not $up.Ok) { Fail ("回滚后实例不可用：{0}" -f $up.Health) }
  if ($st.baseEntry -and ($up.Js -notcontains $st.baseEntry)) { Fail ("回滚后 entry 不是旧值：{0}" -f $up.Entry) }
  Pass ("损坏 jar 被检测到并自动回滚：exit 3，jar 回到 {0}，entry {1}，{2}s" -f $sha.Substring(0, 12), ($up.Js -join ','), $r.Seconds)
}

function Phase-Mismatch {
  Say "── mismatch（DRILL C）：entry 期望值被改坏 ⇒ 探活不过 ⇒ 期望自动回滚 ──"
  $st = Load-State
  if (-not $st) { Say '拒绝：先跑 prepare'; exit 2 }
  Ensure-OldJar -St $st
  $r = Invoke-ChildScript -Script $swapScript -ScriptArgs @(
    '-NewJar', $NewJar, '-NFHome', $DrillHome, '-Port', "$Port",
    '-LogDir', $DrillLogs, '-BackupDir', $DrillBackups,
    '-ExpectEntry', 'app-DEADBEEF.js', '-StopTimeoutSec', '90', '-ReadyTimeoutSec', '120')
  if ($r.Timeout) { Fail '子脚本超时' }
  $sha = Get-Sha256 -Path $DrillJar
  $up = Wait-Up -P $Port -TimeoutSec 120
  $log = Latest-Log -Pattern 'swap-*.log'
  $logText = ''
  if ($log) { $logText = (Get-Content -LiteralPath $log.FullName -Raw -Encoding UTF8); Say ("swap 日志：{0}" -f $log.FullName) }
  Say ("演练后：swap exit={0}（期望 3）；jar sha={1}（期望旧 {2}）；{3}；首页 {4}" -f $r.Code, $sha.Substring(0, 12), $st.oldSha.Substring(0, 12), $up.Health, $up.Entry)
  if ($r.Code -ne 3) { Fail ("swap 退出码 {0}，期望 3" -f $r.Code) }
  if ($logText -notmatch 'entry 不符') { Fail '日志里没有 entry 不符的判定' }
  if ($logText -notmatch '自动回滚开始') { Fail '日志里没有「自动回滚开始」' }
  if ($sha -ne $st.oldSha) { Fail '实例未回到旧 jar' }
  if (-not $up.Ok) { Fail ("回滚后实例不可用：{0}" -f $up.Health) }
  Pass ("entry 不符被检测到并自动回滚：exit 3，jar 回到 {0}，entry {1}，{2}s" -f $sha.Substring(0, 12), ($up.Js -join ','), $r.Seconds)
}

function Phase-Status {
  Say "── status（只读） ──"
  $pids = @(Get-ListenerPids -P $Port)
  Say ("隔离端口 {0} 监听 pid：{1}" -f $Port, $(if ($pids.Count) { $pids -join ',' } else { '无' }))
  if ($pids.Count -gt 0) {
    Say ("  命令行：{0}" -f (Get-ProcCmdline -ProcId $pids[0]))
    Say ("  health：{0}" -f (Get-HealthDetail -P $Port).Detail)
    Say ("  首页　：{0}" -f (Get-EntryDetail -P $Port).Detail)
  }
  if (Test-Path -LiteralPath $DrillJar) { Say ("隔离 jar sha：{0}" -f (Get-Sha256 -Path $DrillJar)) }
  $daemons = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -like "*$Root*" })
  Say ("属于演练根的 java 进程：{0}" -f $(if ($daemons.Count) { ($daemons | ForEach-Object { "$($_.ProcessId)" }) -join ',' } else { '无' }))
  $live = Get-LiveSnapshot
  Say ("在线实例：pid {0}；{1}；{2}" -f $live.Pids, $live.Health, $live.Entry)
  Pass '只读状态输出完毕'
}

function Phase-Cleanup {
  Say "── cleanup：停实例 + 清临时目录 + 复核 ──"
  $st = Load-State
  $pids = @(Get-ListenerPids -P $Port)
  if ($pids.Count -gt 0) {
    $jar = $DrillJar
    if (-not (Test-Path -LiteralPath $jar)) {
      $cand = Get-ChildItem -LiteralPath (Join-Path $DrillHome 'bin\versions') -Filter '*.jar' -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
      if ($cand) { $jar = $cand.FullName }
    }
    $r = Invoke-JarCli -Jar $jar -CliArgs @('stop', '--home', $DrillHome, '--port', "$Port") -TimeoutSec 120
    if (-not (Wait-PortFree -P $Port -TimeoutSec 90)) { Fail ("端口 {0} 停不下来" -f $Port) }
    Say ("网关已停（stop exit={0}），端口 {1} 已释放" -f $r.Code, $Port)
  } else { Say ("端口 {0} 本来就没监听者" -f $Port) }

  # 演练实例会 upsert 一个 perception-daemon 子进程（autoStart=true）；工程上没有优雅停机面，
  # 明确报出来，并只对「命令行含本次演练根」的那一个进程做定向终止。
  $leftovers = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
                 Where-Object { $_.CommandLine -like "*$Root*" })
  foreach ($lp in $leftovers) {
    Say ("残留 java 进程：pid {0} —— {1}" -f $lp.ProcessId, $lp.CommandLine.Substring(0, [Math]::Min(180, $lp.CommandLine.Length)))
    if ($lp.CommandLine -like "*$Root*" -and $lp.ProcessId -ne ($pids | Select-Object -First 1)) {
      Stop-Process -Id $lp.ProcessId -Force -ErrorAction SilentlyContinue
      Say ("  已定向终止演练残留 pid {0}（非在线实例）" -f $lp.ProcessId)
    }
  }
  Start-Sleep -Seconds 2
  $still = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -like "*$Root*" })
  Say ("清理后属于演练根的 java 进程：{0}" -f $(if ($still.Count) { ($still | ForEach-Object { $_.ProcessId }) -join ',' } else { '无' }))

  if (Test-Path -LiteralPath $Root) { Remove-Item -LiteralPath $Root -Recurse -Force }
  Say ("临时目录已删：{0}（存在={1}）" -f $Root, (Test-Path -LiteralPath $Root))

  $free6157 = (@(Get-ListenerPids -P $Port)).Count -eq 0
  Say ("演练端口 {0} 空闲：{1}" -f $Port, $free6157)

  $live = Get-LiveSnapshot
  $same = $true
  $detail = '无基线（未跑 prepare），仅报当前态'
  if ($st) {
    $b = $st.liveBefore
    $same = ($b.Pids -eq $live.Pids) -and ($b.HealthOk -eq $true) -and ($live.HealthOk -eq $true) -and
            ($b.Entry -eq $live.Entry) -and ($b.Cmdline -eq $live.Cmdline)
    $detail = ("pid {0}→{1}；health ok→{2}；entry 一致={3}" -f $b.Pids, $live.Pids, $live.HealthOk, ($b.Entry -eq $live.Entry))
  }
  Say ("在线实例 {0}：pid {1}；{2}；{3}" -f $LivePort, $live.Pids, $live.Health, $live.Entry)
  if (-not $same) { Fail ("在线实例状态与基线不一致：{0}" -f $detail) }
  if (-not $free6157) { Fail ("演练端口 {0} 仍被占用" -f $Port) }
  Pass ("清理完成；演练端口空闲；在线实例基线一致（{0}）" -f $detail)
}

# ═══════════════════════════════════════════════════════════════════════
# 入口
# ═══════════════════════════════════════════════════════════════════════

$script:Java = Resolve-Java
New-Item -ItemType Directory -Force -Path $DrillLogs | Out-Null
Say ("=== jar-swap rehearsal | phase={0} | port={1} | root={2} | java={3}" -f $Phase, $Port, $Root, $script:Java)

switch ($Phase) {
  'prepare'    { Phase-Prepare }
  'up'         { Phase-Up }
  'forward'    { Phase-Forward }
  'idempotent' { Phase-Idempotent }
  'rollback'   { Phase-Rollback }
  'corrupt'    { Phase-Corrupt }
  'mismatch'   { Phase-Mismatch }
  'status'     { Phase-Status }
  'cleanup'    { Phase-Cleanup }
  'all' {
    Say 'all：顺序跑（排障请逐相位跑）'
    & $PSCommandPath -Phase prepare -Port $Port -Root $Root -NewJar $NewJar -ChildTimeoutSec $ChildTimeoutSec
    & $PSCommandPath -Phase up -Port $Port -Root $Root -NewJar $NewJar -ChildTimeoutSec $ChildTimeoutSec
    & $PSCommandPath -Phase forward -Port $Port -Root $Root -NewJar $NewJar -ChildTimeoutSec $ChildTimeoutSec
    & $PSCommandPath -Phase idempotent -Port $Port -Root $Root -NewJar $NewJar -ChildTimeoutSec $ChildTimeoutSec
    & $PSCommandPath -Phase rollback -Port $Port -Root $Root -NewJar $NewJar -ChildTimeoutSec $ChildTimeoutSec
    & $PSCommandPath -Phase corrupt -Port $Port -Root $Root -NewJar $NewJar -ChildTimeoutSec $ChildTimeoutSec
    & $PSCommandPath -Phase mismatch -Port $Port -Root $Root -NewJar $NewJar -ChildTimeoutSec $ChildTimeoutSec
    & $PSCommandPath -Phase cleanup -Port $Port -Root $Root -NewJar $NewJar -ChildTimeoutSec $ChildTimeoutSec
  }
}

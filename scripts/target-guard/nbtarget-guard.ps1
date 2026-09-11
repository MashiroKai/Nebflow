<#
.SYNOPSIS
  nbtarget-guard.ps1 - Windows target-machine "three assertions" discipline guard (Nebflow sandbox minimal set, T1).

.DESCRIPTION
  Design anchor (single source of truth):
    ~/.nebflow/docs/Nebflow/  2026-09-10 sandbox unified-base design (Chinese filename; section anchors below)
      - section 2.6 table row "M1 - three assertions target discipline"
      - section 4 table row "T1" (whoami whitelist + %USERPROFILE% + Resolve-Path before recursive delete;
        dry-run prints the delete manifest first)
      - section 6 U3 (is `nbverify` a non-admin? UNVERIFIED - this script carries the probe)

  POSIX sibling (same vocabulary, different OS):
    scripts/lib/delguard.mjs  (delguard batch 2026-09-11: rc=2 = refused, resolved absolute path always
    printed, out-of-root targets refused, dry-run mode that touches nothing, blacklist for home/system roots)

  Assertions - all fail-closed, none can be switched off, only re-parameterised:
    (1) IDENTITY  whoami leaf AND $env:USERNAME must both equal -AllowedAccount.
    (2) HOME      $env:USERPROFILE must equal -AllowedProfile; the profile leaf must equal -AllowedAccount;
                  the Nebflow home ($env:NEBFLOW_HOME, else <profile>\.nebflow) must sit inside that profile;
                  -AllowedRoot must be an absolute non-drive-root path outside that profile.
    (3) PATH      every recursive delete/move target is re-resolved to a real absolute path and must not be:
                    a drive root, -AllowedRoot itself, the task root itself (unless -AllowTaskRoot),
                    outside <AllowedRoot>\<TaskId>, the profile / an ancestor of it / anything inside it,
                    <profile dir>\.. (the users dir) or anything under it that is not the allowed profile,
                    a path containing wildcard metacharacters (literal paths only),
                    a path whose chain up to the task root crosses a reparse point (junction/symlink),
                    a missing path (unless -AllowMissingTargets).
                  The same predicate is re-run at the delete point (second assertion) before execution.
    (4) ADMIN     (design U3 - UNVERIFIED on the target) the whitelisted account must NOT be a member of the
                  local Administrators group. The raw reading of every probe method is printed as evidence.
                  See -AdminCheckMode for the documented fail-closed behaviour when the probe is inconclusive.

  Order of operations (nothing is destroyed before the manifest exists):
    assert identity -> assert home -> probe admin -> plan+assert every target -> write manifest (JSONL)
    -> refuse (rc=2) OR execute -> append execution records.

  Exit codes (aligned with scripts/lib/delguard.mjs):
    0  OK (assertions passed; executed, or dry-run/probe only)
    2  REFUSED - an assertion denied (machine line: GUARD_DENY reason=<code>)
    64 usage error (missing/invalid parameters, non-Windows host)
    70 unexpected error
    74 manifest I/O failure (nothing is deleted)

  Encoding note: this file is deliberately ASCII-only. Windows PowerShell 5.1 (the shell available on the
  target machine) reads a BOM-less .ps1 as ANSI, so non-ASCII source is unsafe in this file. Keep it ASCII.

.EXAMPLE
  # Dry run on the target: print + persist the delete manifest, change nothing.
  powershell -NoProfile -ExecutionPolicy Bypass -File nbtarget-guard.ps1 `
      -TaskId t5-scratch -Target C:\nbtarget\work\t5-scratch\tmp\out -DryRun

.EXAMPLE
  # Assertions only (no manifest, no changes) - the T5 re-run form for the acceptance criteria.
  powershell -NoProfile -ExecutionPolicy Bypass -File nbtarget-guard.ps1 `
      -TaskId t5-probe -Target $env:USERPROFILE\Documents -Probe

.NOTES
  Author: Nebflow T1 (sandbox minimal set). Version: see $script:GuardVersion.
  This guard is process discipline, not a security boundary: it prevents mistakes made by an agent that
  follows the discipline, it does not stop a model that bypasses the script entirely (design 2.6 M1).
#>
[CmdletBinding()]
param(
  # --- injectable parameters (Q7 self-test matrix hooks) -------------------------------------------
  [string]$AllowedAccount = 'nbverify',
  [string]$AllowedProfile = 'C:\Users\nbverify',
  [string]$AllowedRoot    = 'C:\nbtarget\work',
  [string]$TaskId         = $env:NB_TASK_ID,

  # --- work to do ----------------------------------------------------------------------------------
  [string[]]$Target = @(),
  [ValidateSet('Delete', 'Move')][string]$Operation = 'Delete',
  [string]$Destination,

  # --- modes ---------------------------------------------------------------------------------------
  [switch]$Probe,           # run assertions only: no manifest write, no change, rc=0 pass / rc=2 refused
  [switch]$DryRun,          # write the manifest + print the plan, change nothing, rc=0 if all allowed

  # --- explicit relaxations (each one is a documented downgrade, never a silent bypass) -------------
  [switch]$AllowTaskRoot,       # permit the task root itself as a target (default: strict descendants only)
  [switch]$AllowMissingTargets, # treat a non-existent target as skippable instead of refused
  [string]$ManifestPath,        # injectable manifest output path (default <AllowedRoot>\<TaskId>\_guard\)
  [ValidateSet('Enforce', 'Warn', 'Skip')][string]$AdminCheckMode = 'Enforce',

  [switch]$Version
)

$ErrorActionPreference = 'Stop'
$script:GuardVersion = '1.0.0'
$script:GuardId      = 'nbtarget-guard'
$script:Refused      = $false
$script:DenyReasons  = New-Object System.Collections.ArrayList

# ---- exit codes (mirror scripts/lib/delguard.mjs) -------------------------------------------------
$EXIT_OK      = 0
$EXIT_REFUSED = 2
$EXIT_USAGE   = 64
$EXIT_ERROR   = 70
$EXIT_IO      = 74

# ==================================================================================================
# output helpers
# ==================================================================================================
function Write-GuardInfo {
  param([Parameter(Mandatory = $true)][string]$Text)
  Write-Output ("[{0}] {1}" -f $script:GuardId, $Text)
}

function Write-GuardWarn {
  param([Parameter(Mandatory = $true)][string]$Text)
  Write-Output ("[{0}] GUARD_WARN {1}" -f $script:GuardId, $Text)
}

function Write-GuardDeny {
  param(
    [Parameter(Mandatory = $true)][string]$Reason,
    [Parameter(Mandatory = $true)][string]$Message
  )
  # machine line on stdout (greppable in captured output), human sentence on stderr
  Write-Output ("GUARD_DENY reason={0} {1}" -f $Reason, $Message)
  [Console]::Error.WriteLine("[{0}] REFUSED ({1}): {2}" -f $script:GuardId, $Reason, $Message)
  [void]$script:DenyReasons.Add($Reason)
  $script:Refused = $true
}

# ==================================================================================================
# path / string helpers (Windows semantics; no filesystem access except where stated)
# ==================================================================================================
function Get-AccountLeaf {
  param([string]$Name)
  if ([string]::IsNullOrEmpty($Name)) { return '' }
  $parts = $Name -split '[\\/]'
  return $parts[$parts.Count - 1]
}

function Normalize-PathLiteral {
  param([string]$Path)
  if ([string]::IsNullOrWhiteSpace($Path)) { return $null }
  $p = $Path.Trim()
  # values pasted from error messages / command lines often arrive quoted
  while ($p.Length -ge 2 -and (
      ($p.StartsWith('"') -and $p.EndsWith('"')) -or ($p.StartsWith("'") -and $p.EndsWith("'")))) {
    $p = $p.Substring(1, $p.Length - 2).Trim()
  }
  if ($p -match '^[A-Za-z]:$') { $p = $p + '\' }   # "C:" alone means "current dir on C:" - take the root
  try {
    $full = [System.IO.Path]::GetFullPath($p)
  } catch {
    return $null
  }
  if ($full.Length -gt 3) { $full = $full.TrimEnd([char]'\') }
  return $full
}

function Test-PathEquals {
  param([string]$A, [string]$B)
  if ([string]::IsNullOrEmpty($A) -or [string]::IsNullOrEmpty($B)) { return $false }
  return [string]::Equals($A, $B, [System.StringComparison]::OrdinalIgnoreCase)
}

function Test-PathWithin {
  # true when Candidate == Root or Candidate is a descendant of Root
  param([string]$Candidate, [string]$Root)
  if ([string]::IsNullOrEmpty($Candidate) -or [string]::IsNullOrEmpty($Root)) { return $false }
  if (Test-PathEquals $Candidate $Root) { return $true }
  $rootWithSep = $Root
  if (-not $rootWithSep.EndsWith('\', [System.StringComparison]::Ordinal)) { $rootWithSep = $rootWithSep + '\' }
  return $Candidate.StartsWith($rootWithSep, [System.StringComparison]::OrdinalIgnoreCase)
}

function Test-IsDriveRoot {
  param([string]$Path)
  if ([string]::IsNullOrEmpty($Path)) { return $false }
  return ($Path -match '^[A-Za-z]:\\$')
}

function Test-IsAbsoluteWindowsPath {
  param([string]$Path)
  if ([string]::IsNullOrEmpty($Path)) { return $false }
  return ($Path -match '^[A-Za-z]:\\') -or ($Path -match '^\\\\[^\\]')
}

function Test-HasWildcard {
  param([string]$Path)
  if ([string]::IsNullOrEmpty($Path)) { return $false }
  # literal paths only: a wildcard target would make the delete unbounded
  return ($Path -match '[*?]') -or ($Path -match '\[')
}

function Test-ReparseInChain {
  # walks ResolvedPath upwards (inclusive) to StopAt; any reparse point => escape risk
  param([string]$Path, [string]$StopAt)
  $cursor = $Path
  $hops = 0
  while (-not [string]::IsNullOrEmpty($cursor) -and $hops -lt 256) {
    $hops++
    $item = $null
    try { $item = Get-Item -LiteralPath $cursor -Force -ErrorAction Stop } catch { return $false }
    if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) { return $true }
    if (Test-PathEquals $cursor $StopAt) { break }
    $parent = [System.IO.Path]::GetDirectoryName($cursor)
    if ([string]::IsNullOrEmpty($parent)) { break }
    if (Test-PathEquals $parent $cursor) { break }
    $cursor = $parent
  }
  return $false
}

function Resolve-TargetLiteral {
  param([string]$Raw)
  $rec = New-Object psobject -Property @{
    raw = $Raw; resolved = $null; exists = $false; isDirectory = $false; reparse = $false; linkTarget = $null
  }
  $normalized = Normalize-PathLiteral $Raw
  if ([string]::IsNullOrEmpty($normalized)) { return $rec }
  $rec.resolved = $normalized
  try {
    $item = Get-Item -LiteralPath $normalized -Force -ErrorAction Stop
    $rec.exists      = $true
    $rec.isDirectory = [bool]$item.PSIsContainer
    $rec.reparse     = (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0)
    try {
      $lt = $item.Target
      if ($lt) { $rec.linkTarget = ($lt -join ';') }
    } catch { }
  } catch {
    $rec.exists = $false
  }
  return $rec
}

# ==================================================================================================
# assertion (3): the path predicate. Returns $null when allowed, else a stable reason code.
# ==================================================================================================
function Get-TargetDenyReason {
  param(
    [string]$Resolved,
    [string]$TaskRoot,
    [switch]$AllowTaskRootEquality
  )
  if ([string]::IsNullOrEmpty($Resolved)) { return 'invalid-target' }
  if (Test-IsDriveRoot -Path $Resolved) { return 'drive-root' }
  if (Test-PathEquals $Resolved $AllowedRoot) { return 'allowed-root-itself' }
  if (Test-PathEquals $Resolved $TaskRoot) {
    if (-not ($AllowTaskRoot -or $AllowTaskRootEquality)) { return 'task-root-itself' }
  } elseif (-not (Test-PathWithin -Candidate $Resolved -Root $TaskRoot)) {
    return 'outside-allowed-root'
  }

  # Verify-red anchor (T1 acceptance): the self-test replaces the NEXT statement with
  #   if ($Resolved -eq '') { return 'profile-or-ancestor' }
  # and requires the profile-target case to flip from REFUSED(rc=2, reason=profile-or-ancestor) to ALLOW(rc=0).
  # MUTATION-ANCHOR(profile-clause)
  if (Test-PathWithin -Candidate $Resolved -Root $AllowedProfile) { return 'profile-or-ancestor' }

  $profileParent = [System.IO.Path]::GetDirectoryName($AllowedProfile)
  if (-not [string]::IsNullOrEmpty($profileParent)) {
    if (Test-PathEquals $Resolved $profileParent) { return 'users-root' }
    if ((Test-PathWithin -Candidate $Resolved -Root $profileParent) -and
        (-not (Test-PathWithin -Candidate $Resolved -Root $AllowedProfile))) {
      return 'other-user-profile'
    }
  }
  if (Test-ReparseInChain -Path $Resolved -StopAt $TaskRoot) { return 'reparse-point' }
  return $null
}

# ==================================================================================================
# assertion (4): the local Administrators probe (design U3 - UNVERIFIED)
# ==================================================================================================
function Get-AdminProbe {
  # Returns a record: { methods = @( @{name; available; members; raw; error} ), member = $true/$false/$null }
  $record = New-Object psobject -Property @{ methods = @(); member = $null; verdict = 'unknown' }

  # --- method 1: net localgroup <group>  (the criterion named by the design doc, section 6 U3) ------
  $m1 = New-Object psobject -Property @{ name = 'net-localgroup'; available = $false; members = @(); raw = @(); error = $null }
  try {
    $out = @(& net.exe localgroup Administrators 2>&1 | ForEach-Object { [string]$_ })
    $m1.raw = $out
    if ($LASTEXITCODE -eq 0 -and $out.Count -gt 0) {
      $m1.available = $true
      $inBlock = $false
      $members = New-Object System.Collections.ArrayList
      foreach ($line in $out) {
        $t = $line.Trim()
        if ($t -match '^-{3,}$') {
          if ($inBlock) { break }
          $inBlock = $true
          continue
        }
        if (-not $inBlock) { continue }
        if ([string]::IsNullOrWhiteSpace($t)) { continue }
        if ($t -match '\s') { continue }        # localized trailer sentences contain spaces
        [void]$members.Add($t)
      }
      $m1.members = $members.ToArray()
    } else {
      $m1.error = "net.exe localgroup Administrators exit=$LASTEXITCODE"
    }
  } catch {
    $m1.error = $_.Exception.Message
  }
  $record.methods += $m1

  # --- method 2: locale-independent SID probe (Win32_Group SID -> ADSI members) ---------------------
  # Target machine evidence: the LocalAccounts module (Get-LocalUser/Get-LocalGroupMember) is ABSENT on
  # KAI (20260910_sandbox-unified-base B/06-kai-acl-users.txt: "Get-LocalUser : ... is not recognized"),
  # so this guard must never depend on it. S-1-5-32-544 is the locale-independent Administrators SID.
  $m2 = New-Object psobject -Property @{ name = 'sid-adsi'; available = $false; members = @(); raw = @(); error = $null }
  try {
    $grp = Get-CimInstance -ClassName Win32_Group -Filter "LocalAccount=True AND SID='S-1-5-32-544'" -ErrorAction Stop
    if ($grp) {
      $m2.raw = @("Win32_Group SID=S-1-5-32-544 Domain=$($grp.Domain) Name=$($grp.Name)")
      $adsi = [ADSI]("WinNT://./" + $grp.Name + ",group")
      $members = New-Object System.Collections.ArrayList
      foreach ($m in @($adsi.psbase.Invoke('Members'))) {
        try {
          $n = $m.GetType().InvokeMember('Name', 'GetProperty', $null, $m, $null)
          $c = $m.GetType().InvokeMember('Class', 'GetProperty', $null, $m, $null)
          if ($c -eq 'User') { [void]$members.Add([string]$n) }
        } catch { }
      }
      $m2.members = $members.ToArray()
      $m2.available = $true
    } else {
      $m2.error = 'Win32_Group with SID S-1-5-32-544 not found'
    }
  } catch {
    $m2.error = $_.Exception.Message
  }
  $record.methods += $m2

  # --- method 3: token role check (always present; caveat: UAC-filtered tokens) ---------------------
  $m3 = New-Object psobject -Property @{ name = 'windows-principal'; available = $false; members = @(); raw = @(); error = $null }
  try {
    $id = [System.Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object System.Security.Principal.WindowsPrincipal($id)
    $isAdmin = $principal.IsInRole([System.Security.Principal.WindowsBuiltInRole]::Administrator)
    $m3.available = $true
    $m3.raw = @("WindowsIdentity=$($id.Name) IsInRole(Administrator)=$isAdmin",
                'caveat: IsInRole is evaluated against the current (possibly UAC-filtered) token')
    if ($isAdmin) { $m3.members = @($AllowedAccount) }
  } catch {
    $m3.error = $_.Exception.Message
  }
  $record.methods += $m3

  $seen = New-Object System.Collections.ArrayList
  foreach ($m in $record.methods) {
    if (-not $m.available) { continue }
    foreach ($mem in $m.members) {
      if (-not $seen.Contains([string]$mem)) { [void]$seen.Add([string]$mem) }
    }
  }
  if ($seen.Count -gt 0 -or ($record.methods | Where-Object { $_.available }).Count -gt 0) {
    $isMember = $false
    foreach ($mem in $seen) {
      if (Test-PathEquals (Get-AccountLeaf $mem) $AllowedAccount) { $isMember = $true }
    }
    $record.member = $isMember
    if ($isMember) { $record.verdict = 'IS-ADMIN' } else { $record.verdict = 'NOT-ADMIN' }
  }
  return $record
}

# ==================================================================================================
# manifest (JSONL) - written before any change; never optional
# ==================================================================================================
function Get-ManifestTargetPath {
  param([string]$TaskRoot, [string]$Timestamp)
  if (-not [string]::IsNullOrWhiteSpace($ManifestPath)) { return $ManifestPath }
  if ([string]::IsNullOrEmpty($TaskRoot)) { return $null }
  return (Join-Path (Join-Path $TaskRoot '_guard') ("delete-manifest-{0}.jsonl" -f $Timestamp))
}

function Write-ManifestRecord {
  param([Parameter(Mandatory = $true)]$Record, [Parameter(Mandatory = $true)][string]$Path)
  $json = $Record | ConvertTo-Json -Compress -Depth 6
  $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
  [System.IO.File]::AppendAllText($Path, $json + "`r`n", $utf8NoBom)
}

# ==================================================================================================
# main
# ==================================================================================================
if ($Version) {
  Write-Output ("{0} {1}" -f $script:GuardId, $script:GuardVersion)
  exit $EXIT_OK
}

Write-GuardInfo ("version={0} host={1} pid={2}" -f $script:GuardVersion, $env:COMPUTERNAME, $PID)

if ($env:OS -ne 'Windows_NT') {
  Write-GuardDeny -Reason 'not-windows' -Message ("this guard targets Windows ({0}); OS={1}" -f 'C:\ semantics + net localgroup', $env:OS)
  exit $EXIT_USAGE
}

$noWork = ($Target.Count -eq 0)
if ($noWork -and -not $Probe) {
  Write-GuardDeny -Reason 'no-target' -Message 'no -Target given (use -Probe for an assertion-only run)'
  exit $EXIT_USAGE
}
if ((-not $noWork) -and [string]::IsNullOrWhiteSpace($TaskId)) {
  Write-GuardDeny -Reason 'no-taskid' -Message 'a -Target list requires -TaskId (or $env:NB_TASK_ID)'
  exit $EXIT_USAGE
}

# ---- (1) identity ---------------------------------------------------------------------------------
$tokenName  = $null
$whoamiRaw  = $null
try { $tokenName = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name } catch { }
try { $whoamiRaw = (@(& whoami.exe 2>&1 | ForEach-Object { [string]$_ }) | Select-Object -First 1) } catch { }

$idOk = $true
if (-not (Test-PathEquals (Get-AccountLeaf $tokenName) $AllowedAccount)) {
  Write-GuardDeny -Reason 'identity-mismatch' -Message (
    "token identity '{0}' leaf != whitelisted account '{1}' - refusing to run on any other account" -f $tokenName, $AllowedAccount)
  $idOk = $false
}
if (-not (Test-PathEquals $env:USERNAME $AllowedAccount)) {
  Write-GuardDeny -Reason 'identity-env-mismatch' -Message (
    "`$env:USERNAME '{0}' != whitelisted account '{1}'" -f $env:USERNAME, $AllowedAccount)
  $idOk = $false
}
if (-not (Test-PathEquals (Get-AccountLeaf $whoamiRaw) $AllowedAccount)) {
  Write-GuardDeny -Reason 'identity-whoami-mismatch' -Message (
    "whoami '{0}' leaf != whitelisted account '{1}'" -f $whoamiRaw, $AllowedAccount)
  $idOk = $false
}
Write-GuardInfo ("IDENTITY account={0} whoami={1} token={2} -> {3}" -f $AllowedAccount, $whoamiRaw, $tokenName, $(if ($idOk) { 'PASS' } else { 'FAIL' }))

# ---- (2) home / profile ---------------------------------------------------------------------------
$userProfile  = Normalize-PathLiteral $env:USERPROFILE
$profileWant  = Normalize-PathLiteral $AllowedProfile
$allowedRootN = Normalize-PathLiteral $AllowedRoot
$profileParent = $null
if (-not [string]::IsNullOrEmpty($profileWant)) { $profileParent = [System.IO.Path]::GetDirectoryName($profileWant) }

$homeOk = $true
if ([string]::IsNullOrEmpty($profileWant)) {
  Write-GuardDeny -Reason 'profile-unparsable' -Message ("-AllowedProfile '{0}' is not a usable path" -f $AllowedProfile)
  $homeOk = $false
}
if ([string]::IsNullOrEmpty($userProfile)) {
  Write-GuardDeny -Reason 'userprofile-missing' -Message '$env:USERPROFILE is empty/unparsable - cannot prove isolation'
  $homeOk = $false
} elseif (-not (Test-PathEquals $userProfile $profileWant)) {
  Write-GuardDeny -Reason 'profile-mismatch' -Message (
    "`$env:USERPROFILE '{0}' != sanctioned profile '{1}' - refusing to run on a real user profile" -f $userProfile, $profileWant)
  $homeOk = $false
} elseif (-not (Test-PathEquals (Get-AccountLeaf $userProfile) $AllowedAccount)) {
  Write-GuardDeny -Reason 'profile-account-mismatch' -Message (
    "profile '{0}' belongs to '{1}', not to the whitelisted account '{2}'" -f $userProfile, (Get-AccountLeaf $userProfile), $AllowedAccount)
  $homeOk = $false
}

$nbHome = $env:NEBFLOW_HOME
if ([string]::IsNullOrWhiteSpace($nbHome)) {
  if (-not [string]::IsNullOrEmpty($userProfile)) { $nbHome = Join-Path $userProfile '.nebflow' }
}
$nbHomeN = Normalize-PathLiteral $nbHome
if ([string]::IsNullOrEmpty($nbHomeN)) {
  Write-GuardDeny -Reason 'nebflow-home-unparsable' -Message ("Nebflow home '{0}' is not a usable path" -f $nbHome)
  $homeOk = $false
} elseif (Test-PathWithin -Candidate $nbHomeN -Root $profileWant) {
  # inside the sanctioned profile: only this account can reach it - zero sharing by construction
} elseif ((-not [string]::IsNullOrEmpty($profileParent)) -and (Test-PathWithin -Candidate $nbHomeN -Root $profileParent)) {
  # inside the users dir but NOT the sanctioned profile => the real account (or another account) can see it
  Write-GuardDeny -Reason 'nebflow-home-in-other-profile' -Message (
    "Nebflow home '{0}' lives in another account's profile area (under '{1}') - zero-sharing assertion violated" -f `
    $nbHomeN, $profileParent)
  $homeOk = $false
} else {
  # outside every user profile: a dedicated isolated root (e.g. the gateway started with --home <work root>)
  Write-GuardInfo ("Nebflow home '{0}' is outside every user profile (dedicated isolated root)" -f $nbHomeN)
}

if ([string]::IsNullOrEmpty($allowedRootN)) {
  Write-GuardDeny -Reason 'allowed-root-unparsable' -Message ("-AllowedRoot '{0}' is not a usable path" -f $AllowedRoot)
  $homeOk = $false
} elseif (-not (Test-IsAbsoluteWindowsPath $allowedRootN)) {
  Write-GuardDeny -Reason 'allowed-root-not-absolute' -Message ("-AllowedRoot '{0}' must be an absolute path" -f $allowedRootN)
  $homeOk = $false
} elseif (Test-IsDriveRoot -Path $allowedRootN) {
  Write-GuardDeny -Reason 'allowed-root-drive-root' -Message ("-AllowedRoot '{0}' must not be a drive root" -f $allowedRootN)
  $homeOk = $false
} elseif (Test-PathWithin -Candidate $allowedRootN -Root $profileWant) {
  Write-GuardDeny -Reason 'allowed-root-in-profile' -Message (
    "-AllowedRoot '{0}' is inside the user profile '{1}' - the work root must live outside every profile" -f $allowedRootN, $profileWant)
  $homeOk = $false
} elseif ((-not [string]::IsNullOrEmpty($profileParent)) -and (Test-PathWithin -Candidate $allowedRootN -Root $profileParent)) {
  Write-GuardWarn ("-AllowedRoot '{0}' is inside '{1}' - deliberately verify that this is not a real user profile area" -f $allowedRootN, $profileParent)
}
Write-GuardInfo ("HOME profile={0} (want {1}) nebflowHome={2} allowedRoot={3} -> {4}" -f `
  $userProfile, $profileWant, $nbHomeN, $allowedRootN, $(if ($homeOk) { 'PASS' } else { 'FAIL' }))

# ---- (4) admin probe (design U3 - UNVERIFIED) -----------------------------------------------------
$admin = Get-AdminProbe
foreach ($m in $admin.methods) {
  $state = 'unavailable'
  if ($m.available) { $state = 'available' }
  Write-GuardInfo ("ADMIN-PROBE method={0} {1} members=[{2}]{3}" -f `
      $m.name, $state, ($m.members -join ', '), $(if ($m.error) { " error=$($m.error)" } else { '' }))
  foreach ($l in $m.raw) { Write-GuardInfo ("ADMIN-PROBE-RAW method={0} | {1}" -f $m.name, $l) }
}
Write-GuardInfo ("ADMIN verdict={0} account={1} (U3 unverified until this line is read on the target)" -f `
    $admin.verdict, $AllowedAccount)

if ($admin.verdict -eq 'IS-ADMIN') {
  $msg = "account '{0}' IS a member of the local Administrators group (design U3 precondition violated)" -f $AllowedAccount
  if ($AdminCheckMode -eq 'Enforce') {
    Write-GuardDeny -Reason 'admin-membership' -Message $msg
  } else {
    Write-GuardWarn ("{0} - AdminCheckMode={1}: continuing, verdict recorded in the manifest" -f $msg, $AdminCheckMode)
  }
} elseif ($admin.verdict -eq 'unknown') {
  $msg = "could not determine Administrators membership for '{0}' from any probe method" -f $AllowedAccount
  if ($AdminCheckMode -eq 'Enforce') {
    Write-GuardDeny -Reason 'admin-probe-inconclusive' -Message $msg
  } else {
    Write-GuardWarn ("{0} - AdminCheckMode={1}: continuing, verdict recorded in the manifest" -f $msg, $AdminCheckMode)
  }
}
if ($AdminCheckMode -eq 'Skip') {
  Write-GuardWarn 'AdminCheckMode=Skip: the U3 assertion was not evaluated for this run'
}

# ---- plan + assertion (3) for every target --------------------------------------------------------
$stamp = (Get-Date).ToString('yyyyMMdd-HHmmss')
$taskRoot = $null
if (-not [string]::IsNullOrEmpty($allowedRootN) -and -not [string]::IsNullOrWhiteSpace($TaskId)) {
  $taskRoot = Normalize-PathLiteral (Join-Path $allowedRootN $TaskId)
}
$plan = New-Object System.Collections.ArrayList

if (-not $noWork) {
  if ([string]::IsNullOrEmpty($taskRoot)) {
    Write-GuardDeny -Reason 'task-root-unparsable' -Message ("cannot build the task root from '{0}' + '{1}'" -f $allowedRootN, $TaskId)
  } else {
    Write-GuardInfo ("TASK-ROOT {0}" -f $taskRoot)
    foreach ($raw in $Target) {
      $entry = [ordered]@{
        kind = 'target'; raw = $raw; resolved = $null; exists = $false; isDirectory = $false
        reparse = $false; linkTarget = $null; decision = 'DENY'; reason = $null
      }
      $resolvedNow = $null
      if (Test-HasWildcard $raw) {
        $entry.reason = 'wildcard-not-allowed'
      } else {
        $r = Resolve-TargetLiteral $raw
        $entry.resolved = $r.resolved
        $entry.exists = $r.exists
        $entry.isDirectory = $r.isDirectory
        $entry.reparse = $r.reparse
        $entry.linkTarget = $r.linkTarget
        $resolvedNow = $r.resolved
        if ([string]::IsNullOrEmpty($r.resolved)) {
          $entry.reason = 'invalid-target'
        } elseif ((-not $r.exists) -and (-not $AllowMissingTargets)) {
          $entry.reason = 'not-found'
        } else {
          $reason = Get-TargetDenyReason -Resolved $r.resolved -TaskRoot $taskRoot
          if ([string]::IsNullOrEmpty($reason)) { $entry.decision = 'ALLOW' } else { $entry.reason = $reason }
        }
      }
      [void]$plan.Add([pscustomobject]$entry)
    }

    if ($Operation -eq 'Move') {
      if ([string]::IsNullOrWhiteSpace($Destination)) {
        Write-GuardDeny -Reason 'no-destination' -Message 'Operation=Move requires -Destination'
      } else {
        $destN = Normalize-PathLiteral $Destination
        $destEntry = [ordered]@{
          kind = 'destination'; raw = $Destination; resolved = $destN; exists = $false; isDirectory = $false
          reparse = $false; linkTarget = $null; decision = 'DENY'; reason = $null
        }
        if ([string]::IsNullOrEmpty($destN)) {
          $destEntry.reason = 'invalid-target'
        } elseif (-not (Test-Path -LiteralPath $destN -PathType Container)) {
          # -Destination is the container the items are moved INTO; it must already exist
          $destEntry.reason = 'destination-container-missing'
        } else {
          $destRec = Resolve-TargetLiteral $destN
          $destEntry.exists = $destRec.exists
          $destEntry.isDirectory = $destRec.isDirectory
          $destEntry.reparse = $destRec.reparse
          $destEntry.linkTarget = $destRec.linkTarget
          $reason = Get-TargetDenyReason -Resolved $destN -TaskRoot $taskRoot -AllowTaskRootEquality
          if ([string]::IsNullOrEmpty($reason)) { $destEntry.decision = 'ALLOW' } else { $destEntry.reason = $reason }
        }
        [void]$plan.Add([pscustomobject]$destEntry)

        # per-target landing spot must be free (a move that would overwrite is refused)
        if ($destEntry.decision -eq 'ALLOW') {
          foreach ($e in $plan) {
            if ($e.kind -ne 'target' -or $e.decision -ne 'ALLOW') { continue }
            $landingRaw = Join-Path $destN ([System.IO.Path]::GetFileName($e.resolved))
            if (Test-Path -LiteralPath $landingRaw) {
              $e.decision = 'DENY'
              $e.reason = 'destination-exists'
            }
          }
        }
      }
    }

    foreach ($e in $plan) {
      if ($e.decision -eq 'ALLOW') {
        Write-GuardInfo ("PLAN {0} ALLOW raw='{1}' resolved='{2}' exists={3} dir={4} reparse={5}" -f `
            $e.kind, $e.raw, $e.resolved, $e.exists, $e.isDirectory, $e.reparse)
      } else {
        Write-GuardDeny -Reason $e.reason -Message (
          "target {0} refused raw='{1}' resolved='{2}' taskRoot='{3}' allowedRoot='{4}' allowedProfile='{5}'" -f `
          $e.kind, $e.raw, $e.resolved, $taskRoot, $allowedRootN, $profileWant)
      }
    }
  }
}

# ---- manifest (always before any change; skipped only in -Probe mode) ------------------------------
$manifestPath = $null
if (-not $Probe) {
  $manifestPath = Get-ManifestTargetPath -TaskRoot $taskRoot -Timestamp $stamp
  if (-not [string]::IsNullOrEmpty($manifestPath)) {
    try {
      $manifestDir = [System.IO.Path]::GetDirectoryName($manifestPath)
      if (-not [string]::IsNullOrEmpty($manifestDir) -and -not (Test-Path -LiteralPath $manifestDir)) {
        New-Item -ItemType Directory -Path $manifestDir -Force | Out-Null
      }
      $selfHash = $null
      try { if ($PSCommandPath) { $selfHash = (Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash } } catch { }
      $header = [ordered]@{
        kind = 'header'; guard = $script:GuardId; version = $script:GuardVersion; scriptSha256 = $selfHash
        ts = (Get-Date).ToString('o'); computer = $env:COMPUTERNAME; pid = $PID
        operation = $Operation; dryRun = [bool]$DryRun; probe = [bool]$Probe
        account = $tokenName; whoami = $whoamiRaw; envUsername = $env:USERNAME
        userProfile = $userProfile; nebflowHome = $nbHomeN
        allowedAccount = $AllowedAccount; allowedProfile = $profileWant; allowedRoot = $allowedRootN; taskId = $TaskId
        taskRoot = $taskRoot; destination = $Destination
        allowTaskRoot = [bool]$AllowTaskRoot; allowMissingTargets = [bool]$AllowMissingTargets
        adminCheckMode = $AdminCheckMode; adminVerdict = $admin.verdict; manifestPath = $manifestPath
      }
      Write-ManifestRecord -Record $header -Path $manifestPath
      foreach ($e in $plan) {
        Write-ManifestRecord -Record ([ordered]@{
            kind = $e.kind; phase = 'planned'; ts = (Get-Date).ToString('o')
            raw = $e.raw; resolved = $e.resolved; exists = $e.exists; isDirectory = $e.isDirectory
            reparse = $e.reparse; linkTarget = $e.linkTarget
            decision = $e.decision; reason = $e.reason; taskRoot = $taskRoot
        }) -Path $manifestPath
      }
      if ($noWork) {
        Write-ManifestRecord -Record ([ordered]@{
            kind = 'assertion-only'; phase = 'planned'; ts = (Get-Date).ToString('o')
            decision = $(if ($script:Refused) { 'DENY' } else { 'ALLOW' }); reason = ($script:DenyReasons -join ',')
        }) -Path $manifestPath
      }
      Write-GuardInfo ("MANIFEST {0} (written before any change)" -f $manifestPath)
      if (-not (Test-PathWithin -Candidate $manifestPath -Root $allowedRootN)) {
        Write-GuardWarn ("manifest path '{0}' is outside the allowed root '{1}' (injected)" -f $manifestPath, $allowedRootN)
      }
    } catch {
      [Console]::Error.WriteLine("[{0}] manifest write failed: {1}" -f $script:GuardId, $_.Exception.Message)
      exit $EXIT_IO
    }
  } else {
    # no manifest can be derived (no task root): refuse rather than act without evidence
    [Console]::Error.WriteLine("[{0}] no manifest path could be derived from -AllowedRoot/-TaskId" -f $script:GuardId)
    Write-Output 'GUARD_DENY reason=no-manifest-path'
    exit $EXIT_IO
  }
}

# ---- refuse, or execute --------------------------------------------------------------------------
if ($script:Refused) {
  Write-GuardInfo ("REFUSED reasons=[{0}] executed=0" -f ($script:DenyReasons -join ','))
  exit $EXIT_REFUSED
}

if ($Probe) {
  foreach ($e in $plan) { Write-GuardInfo ("PROBE-OK {0} raw='{1}' resolved='{2}'" -f $e.kind, $e.raw, $e.resolved) }
  Write-GuardInfo 'PROBE OK - assertions passed, nothing was written and nothing was changed'
  exit $EXIT_OK
}

if ($DryRun) {
  foreach ($e in $plan) { Write-GuardInfo ("DRY-RUN would {0}: {1}" -f $Operation.ToLower(), $e.resolved) }
  Write-GuardInfo 'DRY-RUN - no changes made (manifest is the only artifact)'
  exit $EXIT_OK
}

$executed = 0
$failures = 0
foreach ($e in $plan) {
  if ($e.kind -ne 'target') { continue }
  # second assertion at the delete point (TOCTOU re-check, mirrors scripts/lib/delguard.mjs)
  $recheck = Resolve-TargetLiteral $e.resolved
  if ((-not $recheck.exists) -and $AllowMissingTargets) {
    # explicit relaxation: a missing target is a no-op, not an execution failure
    Write-GuardInfo ("SKIPPED (missing, -AllowMissingTargets) {0}" -f $recheck.resolved)
    Write-ManifestRecord -Record ([ordered]@{
        kind = 'target'; phase = 'executed'; ts = (Get-Date).ToString('o')
        resolved = $recheck.resolved; outcome = 'skipped-missing'; reason = $null
    }) -Path $manifestPath
    continue
  }
  $reason2 = Get-TargetDenyReason -Resolved $recheck.resolved -TaskRoot $taskRoot
  if (-not [string]::IsNullOrEmpty($reason2)) {
    [Console]::Error.WriteLine("[{0}] REFUSED at delete point ({1}): {2}" -f $script:GuardId, $reason2, $recheck.resolved)
    Write-Output ("GUARD_DENY reason={0} phase=delete-point resolved='{1}'" -f $reason2, $recheck.resolved)
    Write-ManifestRecord -Record ([ordered]@{
        kind = 'target'; phase = 'executed'; ts = (Get-Date).ToString('o')
        resolved = $recheck.resolved; outcome = 'REFUSED-REASSERT'; reason = $reason2
    }) -Path $manifestPath
    $failures++
    continue
  }
  try {
    if ($Operation -eq 'Delete') {
      Remove-Item -LiteralPath $recheck.resolved -Recurse -Force -ErrorAction Stop
      $outcome = 'removed'
    } else {
      $destFor = Normalize-PathLiteral (Join-Path $Destination ([System.IO.Path]::GetFileName($recheck.resolved)))
      Move-Item -LiteralPath $recheck.resolved -Destination $destFor -Force -ErrorAction Stop
      $outcome = "moved->$destFor"
    }
    $executed++
    Write-GuardInfo ("EXECUTED {0} {1}" -f $outcome, $recheck.resolved)
    Write-ManifestRecord -Record ([ordered]@{
        kind = 'target'; phase = 'executed'; ts = (Get-Date).ToString('o')
        resolved = $recheck.resolved; outcome = $outcome; reason = $null
    }) -Path $manifestPath
  } catch {
    $failures++
    [Console]::Error.WriteLine("[{0}] execution failed: {1} ({2})" -f $script:GuardId, $recheck.resolved, $_.Exception.Message)
    Write-ManifestRecord -Record ([ordered]@{
        kind = 'target'; phase = 'executed'; ts = (Get-Date).ToString('o')
        resolved = $recheck.resolved; outcome = 'FAILED'; reason = $_.Exception.Message
    }) -Path $manifestPath
  }
}
Write-ManifestRecord -Record ([ordered]@{
    kind = 'footer'; ts = (Get-Date).ToString('o'); operation = $Operation
    executed = $executed; failures = $failures; refused = 0
}) -Path $manifestPath

if ($failures -gt 0) {
  [Console]::Error.WriteLine("[{0}] {1} target(s) failed (executed={2})" -f $script:GuardId, $failures, $executed)
  exit $EXIT_ERROR
}
Write-GuardInfo ("OK executed={0} manifest={1}" -f $executed, $manifestPath)
exit $EXIT_OK

<#
.SYNOPSIS
  nbtarget-guard.selftest.ps1 - injectable-parameter self-test matrix for nbtarget-guard.ps1 (T1 / Q7).

.DESCRIPTION
  Runs the guard through the injectable-parameter matrix of the T1 acceptance criteria and asserts the exit
  code plus the machine-readable reason code. On the Windows target it produces the evidence T1 asks for:

    A1  Administrators probe reading (design U3)      -> reading recorded + Enforce/Warn consistency
    R1  whitelisted account != current account        -> REFUSED (rc=2, reason=identity-mismatch)
    R2  sanctioned profile != $env:USERPROFILE        -> REFUSED (rc=2, reason=profile-mismatch)
    R3  path inside the real user profile             -> REFUSED (rc=2, reason=outside-allowed-root)
    R4  another account's profile path                -> REFUSED (rc=2, reason=other-user-profile)
    R6  drive root                                    -> REFUSED (rc=2, reason=drive-root)
    R7  wildcard target                               -> REFUSED (rc=2, reason=wildcard-not-allowed)
    R8  task root itself (no -AllowTaskRoot)          -> REFUSED (rc=2, reason=task-root-itself)
    R9  allowed root itself                           -> REFUSED (rc=2, reason=allowed-root-itself)
    R10 missing target                                -> REFUSED (rc=2, reason=not-found)
    R11 missing target + -AllowMissingTargets         -> OK (rc=0)
    G1  sanctioned root, -DryRun                      -> OK, manifest written, target untouched
    G2  sanctioned root, execute                      -> OK, target removed, manifest outcome=removed
    G3  task root itself + -AllowTaskRoot             -> OK
    G4  injected -ManifestPath                        -> OK, manifest at the injected path
    M1  verify-red: weaken the profile clause in a copy -> original REFUSED, mutant ALLOW (case MUST flip)
    P1  no-touch proof for profile-touching runs      -> profile dir unchanged (existence/mtime/children)

  verify-red semantics (design 4 -> T1, last column): M1 copies the guard, replaces the anchored profile
  clause line with the weakened form `if ($Resolved -eq '') { ... }`, and asserts that the case which was RED
  on the original turns GREEN on the mutant. If the mutation cannot be applied, the case is UNRUN - never
  PASS: a mutation that silently does not apply would prove nothing.

  Every profile-touching case runs with -DryRun and is covered by P1 (no-touch proof): the matrix never
  writes inside a user profile.

  Output: the matrix table + per-case raw stdout/stderr (with -EvidenceDir, into <EvidenceDir>\raw\).
  ASCII-only on purpose (Windows PowerShell 5.1 reads a BOM-less .ps1 as ANSI).

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File nbtarget-guard.selftest.ps1 `
      -EvidenceDir C:\nbtarget\work\t1-evidence

.NOTES
  Exit codes: 0 all PASS | 1 some FAIL | 8 some UNRUN (no FAIL) | 9 not Windows / guard missing.
#>
[CmdletBinding()]
param(
  [string]$GuardPath,
  [string]$AllowedRoot = 'C:\nbtarget\work',
  [string]$FallbackRoot = 'C:\ProgramData\nbtarget-guard-selftest\work',
  [string]$ScratchDir = $env:TEMP,
  [string]$EvidenceDir,
  [string[]]$Case = @(),
  [switch]$ListCases,
  [switch]$SkipProfileCases,
  [string]$ShellExe = 'powershell.exe'
)

$ErrorActionPreference = 'Stop'
$script:SelftestVersion = '1.0.0'
$script:MutantPath = $null

$EXIT_OK     = 0
$EXIT_FAIL   = 1
$EXIT_UNRUN  = 8
$EXIT_NONWIN = 9

if (-not $GuardPath) { $GuardPath = Join-Path $PSScriptRoot 'nbtarget-guard.ps1' }

$caseIds = @('A1-admin-probe', 'R1-identity-mismatch', 'R2-profile-mismatch', 'R3-profile-target',
  'R4-other-profile', 'R6-drive-root', 'R7-wildcard', 'R8-task-root', 'R9-allowed-root', 'R10-not-found',
  'R11-missing-allowed', 'G1-green-dryrun', 'G2-green-execute', 'G3-allow-task-root', 'G4-injected-manifest',
  'M1-verify-red', 'P1-no-touch')

if ($ListCases) {
  foreach ($c in $caseIds) { Write-Output $c }
  exit $EXIT_OK
}

if ($env:OS -ne 'Windows_NT') {
  [Console]::Error.WriteLine("[nbguard-selftest] Windows-only matrix (C:\ path semantics + net localgroup); OS=$env:OS")
  [Console]::Error.WriteLine('[nbguard-selftest] nothing was run - report this as UNRUN (never as pass/fail)')
  exit $EXIT_NONWIN
}
if (-not (Test-Path -LiteralPath $GuardPath)) {
  [Console]::Error.WriteLine("[nbguard-selftest] guard not found: $GuardPath")
  exit $EXIT_NONWIN
}

# ==================================================================================================
# helpers
# ==================================================================================================
$results = New-Object System.Collections.ArrayList

function Add-Result {
  param([string]$Id, [string]$Title, [string]$Status, [string]$Detail)
  [void]$results.Add([pscustomobject]@{ id = $Id; title = $Title; status = $Status; detail = $Detail })
  [Console]::Out.WriteLine(("CASE {0,-20} {1,-6} {2}" -f $Id, $Status, $Detail))
}

function Normalize-P {
  param([string]$P)
  if ([string]::IsNullOrWhiteSpace($P)) { return $null }
  $x = $P.Trim()
  if ($x -match '^[A-Za-z]:$') { $x = $x + '\' }
  $f = [System.IO.Path]::GetFullPath($x)
  if ($f.Length -gt 3) { $f = $f.TrimEnd([char]'\') }
  return $f
}

function Invoke-GuardRun {
  # Runs the guard in a child process: separate stdout/stderr + a real exit code.
  # NOTE: progress lines go through [Console]::Out (not Write-Output) so that a caller capturing the
  # body's output stream only ever sees the assertion result, never the command echo.
  param([string[]]$ArgArray, [string]$GuardFile, [string]$Tag)
  $outFile = Join-Path $ScratchDir ("nbguard-{0}-{1}.out" -f $Tag, [guid]::NewGuid().ToString('N'))
  $errFile = Join-Path $ScratchDir ("nbguard-{0}-{1}.err" -f $Tag, [guid]::NewGuid().ToString('N'))
  $quoted = @()
  foreach ($a in $ArgArray) { if ($a -match '\s') { $quoted += ('"' + $a + '"') } else { $quoted += $a } }
  $all = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"' + $GuardFile + '"')) + $quoted
  $rc = -1
  try {
    $p = Start-Process -FilePath $ShellExe -ArgumentList $all -RedirectStandardOutput $outFile `
      -RedirectStandardError $errFile -NoNewWindow -Wait -PassThru
    $rc = $p.ExitCode
  } catch {
    [Console]::Error.WriteLine("[nbguard-selftest] failed to spawn '$ShellExe': $($_.Exception.Message)")
  }
  $out = ''
  $err = ''
  if (Test-Path -LiteralPath $outFile) { $out = [string](Get-Content -LiteralPath $outFile -Raw) }
  if (Test-Path -LiteralPath $errFile) { $err = [string](Get-Content -LiteralPath $errFile -Raw) }
  [Console]::Out.WriteLine(("  cmd: {0}" -f ($ShellExe + ' ' + ($all -join ' '))))
  [Console]::Out.WriteLine(("  rc={0}" -f $rc))
  if ($EvidenceDir) {
    try {
      $rawDir = Join-Path $EvidenceDir 'raw'
      if (-not (Test-Path -LiteralPath $rawDir)) { New-Item -ItemType Directory -Path $rawDir -Force | Out-Null }
      Set-Content -LiteralPath (Join-Path $rawDir ("{0}.out.txt" -f $Tag)) -Value $out -Encoding UTF8
      Set-Content -LiteralPath (Join-Path $rawDir ("{0}.err.txt" -f $Tag)) -Value $err -Encoding UTF8
      Set-Content -LiteralPath (Join-Path $rawDir ("{0}.cmd.txt" -f $Tag)) -Value ($ShellExe + ' ' + ($all -join ' ')) -Encoding UTF8
    } catch {
      [Console]::Error.WriteLine("[nbguard-selftest] evidence copy failed for $Tag : $($_.Exception.Message)")
    }
  }
  # own temp files are cleaned immediately (no residue in the scratch dir)
  if (Test-Path -LiteralPath $outFile) { Remove-Item -LiteralPath $outFile -Force }
  if (Test-Path -LiteralPath $errFile) { Remove-Item -LiteralPath $errFile -Force }
  return [pscustomobject]@{ rc = $rc; out = $out; err = $err }
}

function Get-ManifestFromOutput {
  param([string]$Out)
  foreach ($line in ($Out -split "`r?`n")) { if ($line -match 'MANIFEST (\S+) ') { return $matches[1] } }
  return $null
}

function Assert-Run {
  param($Run, [int]$ExpectRc, [string]$OutLike, [string]$OutNotLike, [string]$What)
  if ($Run.rc -ne $ExpectRc) {
    return ("{0}: expected rc={1} got rc={2}; stdout=[{3}] stderr=[{4}]" -f `
        $What, $ExpectRc, $Run.rc, ($Run.out -replace "`r?`n", ' | '), ($Run.err -replace "`r?`n", ' | '))
  }
  if ($OutLike -and ($Run.out -notmatch $OutLike)) {
    return ("{0}: stdout does not match '{1}'; stdout=[{2}]" -f $What, $OutLike, ($Run.out -replace "`r?`n", ' | '))
  }
  if ($OutNotLike -and ($Run.out -match $OutNotLike)) {
    return ("{0}: stdout unexpectedly matches '{1}'" -f $What, $OutNotLike)
  }
  return $null
}

function New-ScratchTree {
  param([string]$LeafParent, [string]$Leaf)
  $t = Join-Path $LeafParent $Leaf
  New-Item -ItemType Directory -Path (Join-Path $t 'a\b') -Force | Out-Null
  Set-Content -LiteralPath (Join-Path $t 'a\b\payload.txt') -Value 'nbtarget-guard selftest payload' -Encoding ASCII
  return $t
}

function Get-DirSignature {
  param([string]$Path)
  if (-not (Test-Path -LiteralPath $Path)) { return 'ABSENT' }
  $i = Get-Item -LiteralPath $Path -Force
  $n = @(Get-ChildItem -LiteralPath $Path -Force -ErrorAction SilentlyContinue).Count
  return ("EXISTS mtime={0} children={1}" -f $i.LastWriteTimeUtc.ToString('o'), $n)
}

function Get-MutatedGuard {
  # Builds the weakened copy used by M1/P1. Returns the path or a reason why it could not be built.
  param([string]$Source, [string]$OutPath)
  $lines = @(Get-Content -LiteralPath $Source)
  $anchor = -1
  for ($i = 0; $i -lt $lines.Count; $i++) {
    if ($lines[$i] -like '*MUTATION-ANCHOR(profile-clause)*') { $anchor = $i + 1; break }
  }
  if ($anchor -lt 0) { return 'anchor comment MUTATION-ANCHOR(profile-clause) not found in the guard' }
  if ($anchor -ge $lines.Count) { return 'anchor is the last line - nothing to mutate' }
  if (($lines[$anchor] -notmatch 'Test-PathWithin') -or ($lines[$anchor] -notmatch 'profile-or-ancestor')) {
    return ('the anchored line is not the expected profile clause: ' + $lines[$anchor])
  }
  $mutated = @($lines)
  $mutated[$anchor] = "  if (`$Resolved -eq '') { return 'profile-or-ancestor' }"
  Set-Content -LiteralPath $OutPath -Value $mutated -Encoding ASCII
  $h1 = (Get-FileHash -LiteralPath $Source -Algorithm SHA256).Hash
  $h2 = (Get-FileHash -LiteralPath $OutPath -Algorithm SHA256).Hash
  if ($h1 -eq $h2) { return 'mutation did not change the file (silent no-op) - case invalid' }
  return $null
}

# ==================================================================================================
# environment discovery
# ==================================================================================================
$stamp = (Get-Date).ToString('yyyyMMdd-HHmmss')
if (-not (Test-Path -LiteralPath $ScratchDir)) { New-Item -ItemType Directory -Path $ScratchDir -Force | Out-Null }
$currentAccount = $env:USERNAME
$currentProfile = Normalize-P $env:USERPROFILE
$profileParent = $null
if ($currentProfile) { $profileParent = [System.IO.Path]::GetDirectoryName($currentProfile) }
$currentProfileLeaf = $null
if ($currentProfile) { $currentProfileLeaf = Split-Path $currentProfile -Leaf }

# a work root that is writable and lives OUTSIDE every user profile (the guard refuses roots in a profile)
$workRoot = $null
$workRootNote = ''
foreach ($cand in @($AllowedRoot, $FallbackRoot)) {
  $c = Normalize-P $cand
  try {
    if (-not (Test-Path -LiteralPath $c)) { New-Item -ItemType Directory -Path $c -Force | Out-Null }
    $probe = Join-Path $c ('.nbguard-write-probe-' + [guid]::NewGuid().ToString('N'))
    Set-Content -LiteralPath $probe -Value 'probe' -Encoding ASCII
    Remove-Item -LiteralPath $probe -Force
    $workRoot = $c
    $workRootNote = $cand
    break
  } catch {
    $workRootNote = ("$cand is not usable: " + $_.Exception.Message)
  }
}
if (-not $workRoot) {
  [Console]::Error.WriteLine("[nbguard-selftest] no writable work root outside a user profile - aborting ($workRootNote)")
  exit $EXIT_NONWIN
}
$taskId = 'selftest-' + $stamp
$taskRoot = Join-Path $workRoot $taskId
$scratchTree = New-ScratchTree -LeafParent $taskRoot -Leaf 'scratch'

$profileProbePath = $null
if ($currentProfile) {
  # MUST be a strict descendant of the profile: M1 injects -AllowedRoot <profile parent> + -TaskId <profile
  # leaf>, so the injected task root IS the profile. A target equal to the profile would be refused by the
  # task-root-itself clause (clause 4) before the profile clause (clause 6) is reached, and the verify-red
  # case would silently stop testing clause 6 (found by mirror-guard-predicate.py, row "P1").
  foreach ($cand in @('Documents', 'Desktop', 'Downloads', 'AppData\Local')) {
    $p = Join-Path $currentProfile $cand
    if (Test-Path -LiteralPath $p -PathType Container) { $profileProbePath = $p; break }
  }
}

$otherProfileDir = $null
$otherProbeTarget = $null
if ($profileParent -and (Test-Path -LiteralPath $profileParent)) {
  foreach ($d in @(Get-ChildItem -LiteralPath $profileParent -Directory -Force -ErrorAction SilentlyContinue)) {
    if ($d.Name -eq $currentProfileLeaf) { continue }
    foreach ($sub in @('Documents', 'Desktop', 'AppData\Local', 'AppData')) {
      $p = Join-Path $d.FullName $sub
      if (Test-Path -LiteralPath $p) { $otherProfileDir = $d.FullName; $otherProbeTarget = $p; break }
    }
    if ($otherProbeTarget) { break }
  }
}

[Console]::Out.WriteLine(("[nbguard-selftest] version={0} guard={1} shell={2}" -f $script:SelftestVersion, $GuardPath, $ShellExe))
[Console]::Out.WriteLine(("[nbguard-selftest] account={0} profile={1}" -f $currentAccount, $currentProfile))
[Console]::Out.WriteLine(("[nbguard-selftest] NEBFLOW_HOME={0} (must be inside the profile or outside every profile)" -f $env:NEBFLOW_HOME))
[Console]::Out.WriteLine(("[nbguard-selftest] workRoot={0} (from {1}) taskRoot={2}" -f $workRoot, $workRootNote, $taskRoot))
[Console]::Out.WriteLine(("[nbguard-selftest] profileProbePath={0}" -f $profileProbePath))
[Console]::Out.WriteLine(("[nbguard-selftest] otherProfileDir={0} otherProbeTarget={1}" -f $otherProfileDir, $otherProbeTarget))
[Console]::Out.WriteLine(("[nbguard-selftest] {0} cases known (filter: {1})" -f $caseIds.Count, ($Case -join ',')))

$baseArgs = @('-AllowedRoot', $workRoot, '-TaskId', $taskId)

function Run-Case {
  param([string]$Id, [string]$Title, [scriptblock]$Requires, [scriptblock]$Body)
  if ($Case.Count -gt 0 -and ($Case -notcontains $Id)) { return }
  if ($Requires) {
    $why = & $Requires
    if ($why) { Add-Result -Id $Id -Title $Title -Status 'UNRUN' -Detail $why; return }
  }
  try {
    $why = & $Body
    if ($why -is [hashtable] -and $why.ContainsKey('unrun')) {
      # a body may report UNRUN when an environmental precondition is missing (never silently PASS)
      Add-Result -Id $Id -Title $Title -Status 'UNRUN' -Detail ([string]$why.unrun)
    } elseif ($why) {
      Add-Result -Id $Id -Title $Title -Status 'FAIL' -Detail $why
    } else {
      Add-Result -Id $Id -Title $Title -Status 'PASS' -Detail '-'
    }
  } catch {
    Add-Result -Id $Id -Title $Title -Status 'FAIL' -Detail ('exception: ' + $_.Exception.Message)
  }
}

# ==================================================================================================
# A1 - Administrators probe reading (design U3: the reading itself is the deliverable)
# ==================================================================================================
Run-Case -Id 'A1-admin-probe' -Title 'administrators probe reading + Enforce/Warn consistency' -Body {
  $common = @('-AllowedAccount', $currentAccount, '-AllowedProfile', $currentProfile, '-Probe')
  $warnRun = Invoke-GuardRun -GuardFile $GuardPath -Tag 'a1-warn' -ArgArray ($common + @('-AdminCheckMode', 'Warn'))
  if ($warnRun.out -notmatch 'ADMIN verdict=') {
    # the probe never ran: an earlier assertion refused. Never report this as PASS.
    return @{ unrun = ('the admin probe did not run (rc={0}); stdout=[{1}] - fix the environment ' +
        '(e.g. $env:NEBFLOW_HOME pointing at another account profile) and re-run' -f `
        $warnRun.rc, ($warnRun.out -replace "`r?`n", ' | ')) }
  }
  $why = Assert-Run -Run $warnRun -ExpectRc 0 -OutLike 'ADMIN verdict=' -What 'A1 warn-mode probe'
  if ($why) { return $why }
  $verdict = 'unknown'
  if ($warnRun.out -match 'ADMIN verdict=(\S+)') { $verdict = $matches[1] }
  if ($verdict -eq 'unknown') {
    return ('A1: probe inconclusive (no method produced a reading); stdout=[' + ($warnRun.out -replace "`r?`n", ' | ') + ']')
  }
  $enforceRun = Invoke-GuardRun -GuardFile $GuardPath -Tag 'a1-enforce' -ArgArray ($common + @('-AdminCheckMode', 'Enforce'))
  if ($verdict -eq 'NOT-ADMIN') {
    return (Assert-Run -Run $enforceRun -ExpectRc 0 -What ('A1 enforce-mode verdict=' + $verdict))
  }
  # IS-ADMIN is itself the U3 finding: Enforce must refuse loudly
  return (Assert-Run -Run $enforceRun -ExpectRc 2 -OutLike 'reason=admin-membership' -What (
      'A1 enforce-mode verdict=' + $verdict + ' (U3: the account IS an administrator - report this)'))
}

# ==================================================================================================
# R1 - identity mismatch ("run on a real user profile => must be red")
# ==================================================================================================
Run-Case -Id 'R1-identity-mismatch' -Title 'whitelisted account != current account => REFUSED' -Body {
  $r = Invoke-GuardRun -GuardFile $GuardPath -Tag 'r1' -ArgArray (@('-AllowedAccount', 'nbverify-not-this-host',
      '-AllowedProfile', $currentProfile, '-AdminCheckMode', 'Skip', '-Probe') + $baseArgs)
  $why = Assert-Run -Run $r -ExpectRc 2 -OutLike 'reason=identity-mismatch' -What 'R1 identity mismatch'
  if ($why) { return $why }
  if ($r.out -notmatch 'REFUSED reasons') { return 'R1: expected a REFUSED summary line' }
  return $null
}

# ==================================================================================================
# R2 - profile mismatch
# ==================================================================================================
Run-Case -Id 'R2-profile-mismatch' -Title 'sanctioned profile != $env:USERPROFILE => REFUSED' -Requires {
  if ($currentProfileLeaf -eq 'nbverify') { return 'running AS nbverify: the profile mismatch cannot be provoked here' }
  return $null
} -Body {
  $r = Invoke-GuardRun -GuardFile $GuardPath -Tag 'r2' -ArgArray (@('-AllowedAccount', $currentAccount,
      '-AllowedProfile', 'C:\Users\nbverify', '-AdminCheckMode', 'Skip', '-Probe') + $baseArgs)
  return (Assert-Run -Run $r -ExpectRc 2 -OutLike 'reason=profile-mismatch' -What 'R2 profile mismatch')
}

# ==================================================================================================
# R3 - a path inside the real user profile is refused
# ==================================================================================================
Run-Case -Id 'R3-profile-target' -Title 'path inside the real user profile as target => REFUSED' -Requires {
  if ($SkipProfileCases) { return 'skipped by -SkipProfileCases' }
  if (-not $profileProbePath) { return 'no probe path inside the current profile' }
  return $null
} -Body {
  $r = Invoke-GuardRun -GuardFile $GuardPath -Tag 'r3' -ArgArray (@('-AllowedAccount', $currentAccount,
      '-AllowedProfile', $currentProfile, '-AdminCheckMode', 'Skip', '-DryRun', '-Target', $profileProbePath) + $baseArgs)
  return (Assert-Run -Run $r -ExpectRc 2 -OutLike 'reason=outside-allowed-root' -What 'R3 profile target')
}

# ==================================================================================================
# R4 - another account's profile area is refused (users-root clause)
# ==================================================================================================
Run-Case -Id 'R4-other-profile' -Title "another account's profile path as target => REFUSED" -Requires {
  if (-not $otherProbeTarget) { return 'no second user profile with an existing subdirectory on this machine' }
  return $null
} -Body {
  $r = Invoke-GuardRun -GuardFile $GuardPath -Tag 'r4' -ArgArray (@('-AllowedAccount', $currentAccount,
      '-AllowedProfile', $currentProfile, '-AdminCheckMode', 'Skip', '-DryRun',
      '-AllowedRoot', $profileParent, '-TaskId', (Split-Path $otherProfileDir -Leaf),
      '-Target', $otherProbeTarget))
  return (Assert-Run -Run $r -ExpectRc 2 -OutLike 'reason=other-user-profile' -What 'R4 other-profile target')
}

# ==================================================================================================
# R6 - drive root
# ==================================================================================================
Run-Case -Id 'R6-drive-root' -Title 'drive root as target => REFUSED' -Body {
  $r = Invoke-GuardRun -GuardFile $GuardPath -Tag 'r6' -ArgArray (@('-AllowedAccount', $currentAccount,
      '-AllowedProfile', $currentProfile, '-AdminCheckMode', 'Skip', '-DryRun', '-Target', 'C:\') + $baseArgs)
  return (Assert-Run -Run $r -ExpectRc 2 -OutLike 'reason=drive-root' -What 'R6 drive root')
}

# ==================================================================================================
# R7 - wildcard target
# ==================================================================================================
Run-Case -Id 'R7-wildcard' -Title 'wildcard target => REFUSED' -Body {
  $r = Invoke-GuardRun -GuardFile $GuardPath -Tag 'r7' -ArgArray (@('-AllowedAccount', $currentAccount,
      '-AllowedProfile', $currentProfile, '-AdminCheckMode', 'Skip', '-DryRun',
      '-Target', (Join-Path $taskRoot '*')) + $baseArgs)
  return (Assert-Run -Run $r -ExpectRc 2 -OutLike 'reason=wildcard-not-allowed' -What 'R7 wildcard')
}

# ==================================================================================================
# R8 / R9 / R10 / R11 - containment clauses
# ==================================================================================================
Run-Case -Id 'R8-task-root' -Title 'task root itself without -AllowTaskRoot => REFUSED' -Body {
  $r = Invoke-GuardRun -GuardFile $GuardPath -Tag 'r8' -ArgArray (@('-AllowedAccount', $currentAccount,
      '-AllowedProfile', $currentProfile, '-AdminCheckMode', 'Skip', '-DryRun', '-Target', $taskRoot) + $baseArgs)
  return (Assert-Run -Run $r -ExpectRc 2 -OutLike 'reason=task-root-itself' -What 'R8 task root')
}

Run-Case -Id 'R9-allowed-root' -Title 'allowed root itself as target => REFUSED' -Body {
  $r = Invoke-GuardRun -GuardFile $GuardPath -Tag 'r9' -ArgArray (@('-AllowedAccount', $currentAccount,
      '-AllowedProfile', $currentProfile, '-AdminCheckMode', 'Skip', '-DryRun', '-Target', $workRoot) + $baseArgs)
  return (Assert-Run -Run $r -ExpectRc 2 -OutLike 'reason=allowed-root-itself' -What 'R9 allowed root')
}

Run-Case -Id 'R10-not-found' -Title 'missing target => REFUSED (fail-closed)' -Body {
  $r = Invoke-GuardRun -GuardFile $GuardPath -Tag 'r10' -ArgArray (@('-AllowedAccount', $currentAccount,
      '-AllowedProfile', $currentProfile, '-AdminCheckMode', 'Skip', '-DryRun',
      '-Target', (Join-Path $taskRoot 'does-not-exist')) + $baseArgs)
  return (Assert-Run -Run $r -ExpectRc 2 -OutLike 'reason=not-found' -What 'R10 missing target')
}

Run-Case -Id 'R11-missing-allowed' -Title 'missing target + -AllowMissingTargets => OK' -Body {
  $r = Invoke-GuardRun -GuardFile $GuardPath -Tag 'r11' -ArgArray (@('-AllowedAccount', $currentAccount,
      '-AllowedProfile', $currentProfile, '-AdminCheckMode', 'Skip', '-DryRun', '-AllowMissingTargets',
      '-Target', (Join-Path $taskRoot 'does-not-exist')) + $baseArgs)
  return (Assert-Run -Run $r -ExpectRc 0 -OutLike 'DRY-RUN' -What 'R11 missing target allowed')
}

# ==================================================================================================
# G1 / G2 - green path on the sanctioned root
# ==================================================================================================
Run-Case -Id 'G1-green-dryrun' -Title 'sanctioned root + -DryRun => OK, manifest written, target untouched' -Body {
  $r = Invoke-GuardRun -GuardFile $GuardPath -Tag 'g1' -ArgArray (@('-AllowedAccount', $currentAccount,
      '-AllowedProfile', $currentProfile, '-AdminCheckMode', 'Skip', '-DryRun', '-Target', $scratchTree) + $baseArgs)
  $why = Assert-Run -Run $r -ExpectRc 0 -OutLike 'DRY-RUN' -What 'G1 dry run'
  if ($why) { return $why }
  $m = Get-ManifestFromOutput -Out $r.out
  if (-not $m) { return 'G1: no MANIFEST line in stdout' }
  if (-not (Test-Path -LiteralPath $m)) { return ("G1: manifest '{0}' was not written" -f $m) }
  $body = Get-Content -LiteralPath $m -Raw
  if ($body -notmatch '"decision":"ALLOW"') { return 'G1: manifest has no ALLOW record' }
  if ($body -notmatch 'dryRun":true') { return 'G1: manifest header does not record dryRun=true' }
  if (-not (Test-Path -LiteralPath $scratchTree)) { return 'G1: the dry run removed the target (must never happen)' }
  return $null
}

Run-Case -Id 'G2-green-execute' -Title 'sanctioned root, real delete => OK, target gone, manifest outcome' -Body {
  $r = Invoke-GuardRun -GuardFile $GuardPath -Tag 'g2' -ArgArray (@('-AllowedAccount', $currentAccount,
      '-AllowedProfile', $currentProfile, '-AdminCheckMode', 'Skip', '-Target', $scratchTree) + $baseArgs)
  $why = Assert-Run -Run $r -ExpectRc 0 -OutLike 'OK executed=1' -What 'G2 execute'
  if ($why) { return $why }
  if (Test-Path -LiteralPath $scratchTree) { return 'G2: the target still exists after execution' }
  $m = Get-ManifestFromOutput -Out $r.out
  if (-not $m) { return 'G2: no MANIFEST line in stdout' }
  $body = Get-Content -LiteralPath $m -Raw
  if ($body -notmatch '"outcome":"removed"') { return 'G2: manifest has no outcome=removed record' }
  return $null
}

# ==================================================================================================
# G3 / G4 - documented relaxations and the injected manifest path
# ==================================================================================================
Run-Case -Id 'G3-allow-task-root' -Title 'task root itself + -AllowTaskRoot => OK' -Body {
  $r = Invoke-GuardRun -GuardFile $GuardPath -Tag 'g3' -ArgArray (@('-AllowedAccount', $currentAccount,
      '-AllowedProfile', $currentProfile, '-AdminCheckMode', 'Skip', '-DryRun', '-AllowTaskRoot',
      '-Target', $taskRoot) + $baseArgs)
  return (Assert-Run -Run $r -ExpectRc 0 -OutLike 'DRY-RUN' -What 'G3 allow task root')
}

Run-Case -Id 'G4-injected-manifest' -Title 'injected -ManifestPath => OK, manifest at the injected path' -Body {
  $inj = Join-Path $ScratchDir ("nbguard-manifest-{0}.jsonl" -f $stamp)
  $r = Invoke-GuardRun -GuardFile $GuardPath -Tag 'g4' -ArgArray (@('-AllowedAccount', $currentAccount,
      '-AllowedProfile', $currentProfile, '-AdminCheckMode', 'Skip', '-DryRun', '-AllowTaskRoot',
      '-ManifestPath', $inj, '-Target', $taskRoot) + $baseArgs)
  $why = Assert-Run -Run $r -ExpectRc 0 -OutLike 'MANIFEST' -What 'G4 injected manifest'
  if ($why) { return $why }
  if (-not (Test-Path -LiteralPath $inj)) { return ("G4: manifest '{0}' was not written" -f $inj) }
  $line = Get-ManifestFromOutput -Out $r.out
  if ($line -and -not ($line -eq $inj)) { return ("G4: stdout reports '{0}' instead of '{1}'" -f $line, $inj) }
  if ($r.out -notmatch 'GUARD_WARN manifest path') { return 'G4: expected the outside-the-allowed-root warning' }
  if ($EvidenceDir) {
    try { Copy-Item -LiteralPath $inj -Destination (Join-Path (Join-Path $EvidenceDir 'raw') 'g4-injected-manifest.jsonl') -Force } catch { }
  }
  Remove-Item -LiteralPath $inj -Force
  return $null
}

# ==================================================================================================
# M1 - verify-red: weaken the profile clause in a copy; the case MUST flip
# ==================================================================================================
Run-Case -Id 'M1-verify-red' -Title 'weakened profile clause => the profile-target case must flip' -Requires {
  if ($SkipProfileCases) { return 'skipped by -SkipProfileCases' }
  if (-not $profileProbePath) { return 'no probe path inside the current profile' }
  return $null
} -Body {
  $mutant = Join-Path $ScratchDir ("nbtarget-guard.mutant-{0}.ps1" -f $stamp)
  $why = Get-MutatedGuard -Source $GuardPath -OutPath $mutant
  if ($why) { return ('M1: mutation not applicable - ' + $why) }
  $script:MutantPath = $mutant
  $caseArgs = @('-AllowedAccount', $currentAccount, '-AllowedProfile', $currentProfile, '-AdminCheckMode', 'Skip',
    '-DryRun', '-AllowedRoot', $profileParent, '-TaskId', $currentProfileLeaf, '-Target', $profileProbePath)
  $orig = Invoke-GuardRun -GuardFile $GuardPath -Tag 'm1-original' -ArgArray $caseArgs
  $mut = Invoke-GuardRun -GuardFile $mutant -Tag 'm1-mutant' -ArgArray $caseArgs
  $why = Assert-Run -Run $orig -ExpectRc 2 -OutLike 'reason=profile-or-ancestor' -What 'M1 original'
  if ($why) {
    return ($why + ' | NOTE: the original run must refuse via the profile clause (reason=profile-or-ancestor) ' +
      'for this verify-red case to prove anything')
  }
  if ($mut.rc -eq 2 -and ($mut.out -match 'reason=profile-or-ancestor')) {
    return ('M1: the mutant still refuses via the profile clause - the clause would not be load-bearing; mutant stdout=[' +
      ($mut.out -replace "`r?`n", ' | ') + ']')
  }
  if ($mut.rc -ne 0) {
    return ('M1: mutant rc={0} but expected rc=0 (allowed once the clause is weakened); mutant stdout=[{1}]' -f `
        $mut.rc, ($mut.out -replace "`r?`n", ' | '))
  }
  # the mutant must have been allowed purely by the weakened clause: confirm the ALLOW decision is recorded
  if ($mut.out -notmatch 'PLAN target ALLOW') { return 'M1: mutant did not report an ALLOW plan for the target' }
  return $null
}

# ==================================================================================================
# P1 - no-touch proof: profile-touching runs must not modify the profile
# ==================================================================================================
Run-Case -Id 'P1-no-touch' -Title 'profile probe path unchanged by every profile-touching run' -Requires {
  if ($SkipProfileCases) { return 'skipped by -SkipProfileCases' }
  if (-not $profileProbePath) { return 'no probe path inside the current profile' }
  return $null
} -Body {
  $caseArgs = @('-AllowedAccount', $currentAccount, '-AllowedProfile', $currentProfile, '-AdminCheckMode', 'Skip',
    '-DryRun', '-AllowedRoot', $profileParent, '-TaskId', $currentProfileLeaf, '-Target', $profileProbePath)
  $sigBefore = Get-DirSignature -Path $profileProbePath
  [void](Invoke-GuardRun -GuardFile $GuardPath -Tag 'p1-guard' -ArgArray $caseArgs)
  if (-not $script:MutantPath -or -not (Test-Path -LiteralPath $script:MutantPath)) {
    $m = Join-Path $ScratchDir ("nbtarget-guard.mutant-{0}.ps1" -f $stamp)
    $why = Get-MutatedGuard -Source $GuardPath -OutPath $m
    if (-not $why) { $script:MutantPath = $m }
  }
  if ($script:MutantPath -and (Test-Path -LiteralPath $script:MutantPath)) {
    [void](Invoke-GuardRun -GuardFile $script:MutantPath -Tag 'p1-mutant' -ArgArray $caseArgs)
  } else {
    return 'P1: could not build the mutant copy, so the mutant run (the risky one) was not covered'
  }
  $sigAfter = Get-DirSignature -Path $profileProbePath
  if ($sigBefore -ne $sigAfter) {
    return ("P1: profile probe path changed: before='{0}' after='{1}'" -f $sigBefore, $sigAfter)
  }
  return $null
}

# ==================================================================================================
# summary + cleanup
# ==================================================================================================
if ($script:MutantPath -and (Test-Path -LiteralPath $script:MutantPath)) {
  if ($EvidenceDir) {
    try {
      $rawDir = Join-Path $EvidenceDir 'raw'
      if (-not (Test-Path -LiteralPath $rawDir)) { New-Item -ItemType Directory -Path $rawDir -Force | Out-Null }
      Copy-Item -LiteralPath $script:MutantPath -Destination (Join-Path $rawDir 'mutant-guard.ps1') -Force
    } catch { }
  }
  Remove-Item -LiteralPath $script:MutantPath -Force
}

$pass = @($results | Where-Object { $_.status -eq 'PASS' }).Count
$fail = @($results | Where-Object { $_.status -eq 'FAIL' }).Count
$unrun = @($results | Where-Object { $_.status -eq 'UNRUN' }).Count
[Console]::Out.WriteLine('')
[Console]::Out.WriteLine('[nbguard-selftest] matrix result')
[Console]::Out.WriteLine(("  PASS={0} FAIL={1} UNRUN={2}" -f $pass, $fail, $unrun))
foreach ($r in $results) { [Console]::Out.WriteLine(("  {0,-20} {1,-6} {2}" -f $r.id, $r.status, $r.title)) }
foreach ($r in $results) {
  if ($r.status -ne 'PASS') { [Console]::Out.WriteLine(("  detail[{0}] {1}" -f $r.id, $r.detail)) }
}
[Console]::Out.WriteLine(("[nbguard-selftest] evidence kept under {0} (manifests, task root)" -f $taskRoot))

if ($EvidenceDir) {
  try {
    if (-not (Test-Path -LiteralPath $EvidenceDir)) { New-Item -ItemType Directory -Path $EvidenceDir -Force | Out-Null }
    $lines = New-Object System.Collections.ArrayList
    [void]$lines.Add(('# nbtarget-guard.selftest matrix - ' + (Get-Date).ToString('o')))
    [void]$lines.Add(('guard={0}' -f $GuardPath))
    [void]$lines.Add(('account={0} profile={1} workRoot={2} taskRoot={3}' -f $currentAccount, $currentProfile, $workRoot, $taskRoot))
    [void]$lines.Add('')
    [void]$lines.Add('| case | status | title | detail |')
    [void]$lines.Add('|---|---|---|---|')
    foreach ($r in $results) {
      [void]$lines.Add(('| {0} | {1} | {2} | {3} |' -f $r.id, $r.status, $r.title, ($r.detail -replace '\|', '/')))
    }
    [void]$lines.Add(('PASS={0} FAIL={1} UNRUN={2}' -f $pass, $fail, $unrun))
    Set-Content -LiteralPath (Join-Path $EvidenceDir 'matrix-result.md') -Value $lines -Encoding UTF8
    [Console]::Out.WriteLine(('[nbguard-selftest] matrix written to {0}' -f (Join-Path $EvidenceDir 'matrix-result.md')))
  } catch {
    [Console]::Error.WriteLine("[nbguard-selftest] could not write the evidence dir: $($_.Exception.Message)")
  }
}

if ($fail -gt 0) { exit $EXIT_FAIL }
if ($unrun -gt 0) { exit $EXIT_UNRUN }
exit $EXIT_OK

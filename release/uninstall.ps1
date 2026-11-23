$ErrorActionPreference = "Stop"

# -- Brand values (L2 rebrand): rendered from repo-root brand.conf at -----
# -- release time (scripts/render-brand.sh); do not edit by hand. ----------
$ProductName = "stand"
$LowerName = "stand"
$HomeDir = ".stand"
# Install base (再生治本批 2026-10-22): STAND_HOME first-priority, mirrors install.ps1.
$StandBase = if ($env:STAND_HOME) { $env:STAND_HOME } else { Join-Path $env:USERPROFILE $HomeDir }
$ProgressPreference = "SilentlyContinue"

$InstallDir = "$env:LOCALAPPDATA\$ProductName"
$ConfigDir = $StandBase
$WrapperName = "stand"
# Exit codes: 0 = fully removed, 1 = not installed, 3 = partial (residue listed).
$PartialExit = 3
$ResidueFile = Join-Path $env:TEMP "$LowerName-uninstall-residue.txt"

Write-Host ""
Write-Host "  $ProductName Uninstaller" -ForegroundColor Yellow
Write-Host ""

# Check if installed
if (-not (Test-Path $InstallDir)) {
    Write-Host "  $ProductName is not installed." -ForegroundColor Red
    if ([Console]::IsInputRedirected -eq $false) { Read-Host "Press Enter to exit" }
    exit 1
}

# Every same-user JVM that runs an assembly jar out of InstallDir. The pid file
# only names ONE gateway; an orphan, a pre-upgrade instance or a daemon child
# recorded under another pid keeps its jar locked, so enumerate by command line.
# Get-CimInstance returns CommandLine for processes this shell did not spawn.
function Get-InstallProcesses {
    $dir = $InstallDir.TrimEnd('\')
    $jarPattern = '(?i)(stand|nebflow)-assembly-\d[^\\/" ]*\.jar'
    @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object {
        $_.ProcessId -ne $PID -and
        $_.CommandLine -and
        ($_.CommandLine -match $jarPattern) -and
        ($_.CommandLine.IndexOf($dir, [StringComparison]::OrdinalIgnoreCase) -ge 0)
    })
}

# Stop them all, re-enumerate until none is left or the deadline passes.
# Returns the survivors (empty = clean).
function Stop-InstallProcesses {
    $deadline = (Get-Date).AddSeconds(15)
    do {
        foreach ($p in @(Get-InstallProcesses)) {
            Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
        }
        Start-Sleep -Milliseconds 500
        $left = @(Get-InstallProcesses)
    } while ($left.Count -gt 0 -and (Get-Date) -lt $deadline)
    return ,$left
}

# Delete one file with short retries. A still-locked file is renamed aside
# (.pending-delete) as a best effort; whatever survives is returned as a
# residue line. Returns $null when the file is gone.
function Remove-FileLenient([string]$Path) {
    for ($i = 0; $i -lt 4; $i++) {
        try {
            Remove-Item -LiteralPath $Path -Force -ErrorAction Stop
            return $null
        } catch {
            Start-Sleep -Milliseconds 250
        }
    }
    $leaf = Split-Path -Leaf $Path
    try {
        Rename-Item -LiteralPath $Path -NewName ($leaf + ".pending-delete") -Force -ErrorAction Stop
        return "$Path  [locked; renamed to $leaf.pending-delete - delete it after reboot]"
    } catch {
        return "$Path  [locked: $($_.Exception.Message)]"
    }
}

# [1/4] Stop running instances (graceful launcher stop first, then sweep)
Write-Host "[1/4] Stopping running $ProductName processes..." -ForegroundColor Yellow
$wrapper = Join-Path $InstallDir "$WrapperName.cmd"
if (Test-Path $wrapper) {
    cmd /c "`"$wrapper`" stop" *> $null
}
$survivors = Stop-InstallProcesses
if ($survivors.Count -gt 0) {
    Write-Host "       Still running after stop (these jars stay locked):" -ForegroundColor Yellow
    foreach ($s in $survivors) {
        Write-Host "       PID $($s.ProcessId): $($s.CommandLine)" -ForegroundColor Yellow
    }
} else {
    Write-Host "       No $ProductName process left." -ForegroundColor Green
}

# [2/4] Remove install directory file by file; report what cannot go
Write-Host "[2/4] Removing $ProductName files..." -ForegroundColor Yellow
$residue = New-Object System.Collections.Generic.List[string]
foreach ($f in @(Get-ChildItem -LiteralPath $InstallDir -Recurse -Force -File -ErrorAction SilentlyContinue)) {
    $r = Remove-FileLenient $f.FullName
    if ($r) { $residue.Add($r) }
}
foreach ($d in @(Get-ChildItem -LiteralPath $InstallDir -Recurse -Force -Directory -ErrorAction SilentlyContinue | Sort-Object { $_.FullName.Length } -Descending)) {
    Remove-Item -LiteralPath $d.FullName -Force -ErrorAction SilentlyContinue
}
if (Test-Path $InstallDir) {
    Remove-Item -LiteralPath $InstallDir -Force -ErrorAction SilentlyContinue
}
if (Test-Path $InstallDir) {
    Write-Host "       Partly removed: $InstallDir" -ForegroundColor Yellow
} else {
    Write-Host "       Removed $InstallDir" -ForegroundColor Green
}

# [3/4] PATH
Write-Host "[3/4] Cleaning PATH..." -ForegroundColor Yellow
$userPath = [Environment]::GetEnvironmentVariable("Path", "User")
$newPath = ($userPath -split ";" | Where-Object { $_ -ne $InstallDir -and $_ -ne "" }) -join ";"
[Environment]::SetEnvironmentVariable("Path", $newPath, "User")
Write-Host "       Removed from user PATH" -ForegroundColor Green

# [4/4] Config (user's choice; a failed delete is reported, never fatal)
Write-Host "[4/4] Config..." -ForegroundColor Yellow
if (Test-Path $ConfigDir) {
    Write-Host "       Config directory found: $ConfigDir" -ForegroundColor DarkGray
    $answer = Read-Host "       Delete config too? (y/N)"
    if ($answer -eq "y" -or $answer -eq "Y") {
        try {
            Remove-Item -Recurse -Force -LiteralPath $ConfigDir -ErrorAction Stop
            Write-Host "       Config deleted." -ForegroundColor Green
        } catch {
            $residue.Add("$ConfigDir  [config delete failed: $($_.Exception.Message)]")
        }
    } else {
        Write-Host "       Config kept." -ForegroundColor DarkGray
    }
}

Write-Host ""
$exitCode = 0
if ($residue.Count -eq 0 -and -not (Test-Path $InstallDir)) {
    Write-Host "  $ProductName uninstalled." -ForegroundColor Green
} else {
    $exitCode = $PartialExit
    Write-Host "  $ProductName partially uninstalled: $($residue.Count) item(s) could not be removed:" -ForegroundColor Yellow
    foreach ($r in $residue) { Write-Host "    $r" -ForegroundColor Yellow }
    if (Test-Path $InstallDir) { Write-Host "    $InstallDir (directory not empty)" -ForegroundColor Yellow }
    Write-Host "  Reboot, then re-run the uninstaller or delete the items above by hand." -ForegroundColor Yellow
    try {
        $lines = @($residue) + @("Install dir still present: $(Test-Path $InstallDir)")
        Set-Content -LiteralPath $ResidueFile -Value $lines -Encoding UTF8
        Write-Host "  Residue list written to: $ResidueFile" -ForegroundColor Yellow
    } catch {
        Write-Host "  (could not write residue list: $($_.Exception.Message))" -ForegroundColor DarkGray
    }
}
Write-Host ""
if ([Console]::IsInputRedirected -eq $false) { Read-Host "Press Enter to exit" }
exit $exitCode

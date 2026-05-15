$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$InstallDir = "$env:LOCALAPPDATA\Nebflow"
$ConfigDir = Join-Path $env:USERPROFILE ".config\nebflow"

Write-Host ""
Write-Host "  Nebflow Uninstaller" -ForegroundColor Yellow
Write-Host ""

# Check if installed
if (-not (Test-Path $InstallDir)) {
    Write-Host "  Nebflow is not installed." -ForegroundColor Red
    if ([Console]::IsInputRedirected -eq $false) { Read-Host "Press Enter to exit" }
    exit 1
}

# Remove install directory
Write-Host "[1/3] Removing Nebflow files..." -ForegroundColor Yellow
Remove-Item -Recurse -Force $InstallDir
Write-Host "       Removed $InstallDir" -ForegroundColor Green

# Remove from PATH
Write-Host "[2/3] Cleaning PATH..." -ForegroundColor Yellow
$userPath = [Environment]::GetEnvironmentVariable("Path", "User")
$newPath = ($userPath -split ";" | Where-Object { $_ -ne $InstallDir }) -join ";"
[Environment]::SetEnvironmentVariable("Path", $newPath, "User")
Write-Host "       Removed from user PATH" -ForegroundColor Green

# Ask about config
Write-Host "[3/3] Config..." -ForegroundColor Yellow
if (Test-Path $ConfigDir) {
    Write-Host "       Config directory found: $ConfigDir" -ForegroundColor DarkGray
    $answer = Read-Host "       Delete config too? (y/N)"
    if ($answer -eq "y" -or $answer -eq "Y") {
        Remove-Item -Recurse -Force $ConfigDir
        Write-Host "       Config deleted." -ForegroundColor Green
    } else {
        Write-Host "       Config kept." -ForegroundColor DarkGray
    }
}

Write-Host ""
Write-Host "  Nebflow uninstalled." -ForegroundColor Green
Write-Host ""
if ([Console]::IsInputRedirected -eq $false) { Read-Host "Press Enter to exit" }

$ErrorActionPreference = "Stop"

$Version = if ($env:VERSION) { $env:VERSION } else { "1.0.0" }
$InstallDir = if ($env:INSTALL_DIR) { $env:INSTALL_DIR } else { "$env:LOCALAPPDATA\Nebflow" }
$JarName = "nebflow-assembly-$Version.jar"
$DownloadUrl = "https://nebflow-releases-1411212853.cos.ap-nanjing.myqcloud.com/$JarName"

Write-Host "==> Nebflow installer" -ForegroundColor Cyan
Write-Host "    Version: $Version"
Write-Host "    Install to: $InstallDir"

# Check Java
$java = Get-Command java -ErrorAction SilentlyContinue
if (-not $java) {
    Write-Host "ERROR: Java not found. Please install JDK 17 or higher." -ForegroundColor Red
    Write-Host "       https://adoptium.net/" -ForegroundColor Yellow
    exit 1
}

$javaVersion = & java -version 2>&1 | Select-String -Pattern '"(\d+)' | ForEach-Object { $_.Matches[0].Groups[1].Value }
if ($javaVersion -eq "1") {
    $javaVersion = & java -version 2>&1 | Select-String -Pattern '"1\.(\d+)' | ForEach-Object { $_.Matches[0].Groups[1].Value }
}
if ([int]$javaVersion -lt 17) {
    Write-Host "ERROR: Java $javaVersion detected. JDK 17+ is required." -ForegroundColor Red
    exit 1
}

$javaVerStr = & java -version 2>&1 | Select-Object -First 1
Write-Host "    Java: $javaVerStr"

# Download JAR
Write-Host "==> Downloading $JarName..."
New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
$jarPath = Join-Path $InstallDir $JarName
Invoke-WebRequest -Uri $DownloadUrl -OutFile $jarPath -UseBasicParsing

# Create wrapper script
$wrapperPath = Join-Path $InstallDir "nebflow.ps1"
$wrapperContent = @"
`$jar = Get-ChildItem "`$PSScriptRoot\nebflow-assembly-*.jar" | Sort-Object Name | Select-Object -Last 1
if (-not `$jar) {
    Write-Host "ERROR: nebflow JAR not found in `$PSScriptRoot" -ForegroundColor Red
    exit 1
}
& java --add-opens java.base/java.lang=ALL-UNNAMED -jar "`$jar.FullName" `$args
"@
Set-Content -Path $wrapperPath -Value $wrapperContent -Encoding UTF8

# Create CMD wrapper for non-PowerShell usage
$cmdPath = Join-Path $InstallDir "nebflow.cmd"
$cmdContent = @"
@echo off
for %%f in ("%~dp0nebflow-assembly-*.jar") do set JAR=%%f
if "%JAR%"=="" (
    echo ERROR: nebflow JAR not found in %~dp0
    exit /b 1
)
java --add-opens java.base/java.lang=ALL-UNNAMED -jar "%JAR%" %*
"@
Set-Content -Path $cmdPath -Value $cmdContent -Encoding ASCII

# Add to PATH (user level) if not already there
$userPath = [Environment]::GetEnvironmentVariable("Path", "User")
if ($userPath -notlike "*$InstallDir*") {
    [Environment]::SetEnvironmentVariable("Path", "$userPath;$InstallDir", "User")
    Write-Host "    Added $InstallDir to user PATH"
    Write-Host "    Please restart your terminal for PATH to take effect." -ForegroundColor Yellow
}

# Create config template
$configDir = Join-Path $env:USERPROFILE ".config\nebflow"
$configFile = Join-Path $configDir "nebflow.json"
if (-not (Test-Path $configFile)) {
    Write-Host "==> Creating default config at $configFile..."
    New-Item -ItemType Directory -Force -Path $configDir | Out-Null
    $configContent = @"
{
  "llm": {
    "providers": {
      "anthropic": {
        "baseUrl": "https://api.anthropic.com",
        "apiKey": "`${ANTHROPIC_API_KEY}",
        "protocol": "anthropic"
      }
    },
    "model": {
      "default": "anthropic/claude-sonnet-4-6"
    }
  },
  "mcpServers": {}
}
"@
    Set-Content -Path $configFile -Value $configContent -Encoding UTF8
    Write-Host "    Please edit $configFile to set your API key."
}

Write-Host ""
Write-Host "==> Done! Nebflow v$Version installed." -ForegroundColor Green
Write-Host "    Run: nebflow --help"
Write-Host "    Config: $env:USERPROFILE\.config\nebflow\nebflow.json"

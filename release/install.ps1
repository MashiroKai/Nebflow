$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

# Parse flags
$Channel = if ($env:CHANNEL) { $env:CHANNEL } else { "stable" }
$Region = ""
# Also check command-line args (for direct execution, not via iex)
if ($args -contains "-Beta") { $Channel = "beta" }
if ($args -contains "--channel=beta") { $Channel = "beta" }
if ($args -contains "-Cn") { $Region = "cn" }
if ($args -contains "-Global") { $Region = "global" }

# Resolve version
if ($Channel -eq "beta") {
    Write-Host "==> Resolving latest beta version..." -ForegroundColor Yellow
    try {
        $releases = Invoke-RestMethod -Uri "https://api.github.com/repos/MashiroKai/Nebflow-Release/releases" -TimeoutSec 10
        $beta = $releases | Where-Object { $_.prerelease -eq $true } | Select-Object -First 1
        if ($beta) {
            $BetaVersion = $beta.tag_name -replace '^v', '' -replace '-beta$', ''
        }
    } catch {}
    if (-not $BetaVersion) {
        Write-Host "ERROR: Could not find a beta release." -ForegroundColor Red
        Write-Host "       Visit https://github.com/MashiroKai/Nebflow/releases to check availability." -ForegroundColor Yellow
        exit 1
    }
    $Version = if ($env:VERSION) { $env:VERSION } else { $BetaVersion }
} else {
    $Version = if ($env:VERSION) { $env:VERSION } else { "1.00.006" }
}

$InstallDir = if ($env:INSTALL_DIR) { $env:INSTALL_DIR } else { "$env:LOCALAPPDATA\Nebflow" }
$JarName = "nebflow-assembly-$Version.jar"
$CosUrl = "https://nebflow-releases-1411212853.cos.ap-nanjing.myqcloud.com/$JarName"
$GhUrl = "https://github.com/MashiroKai/Nebflow-Release/releases/download/v$Version/$JarName"
$GhBetaUrl = "https://github.com/MashiroKai/Nebflow-Release/releases/download/v$Version-beta/$JarName"

Write-Host ""
Write-Host "  ███╗   ██╗███████╗██████╗ ███████╗██╗      ██████╗ ██╗    ██╗" -ForegroundColor Cyan
Write-Host "  ████╗  ██║██╔════╝██╔══██╗██╔════╝██║     ██╔═══██╗██║    ██║" -ForegroundColor Cyan
Write-Host "  ██╔██╗ ██║█████╗  ██████╔╝█████╗  ██║     ██║   ██║██║ █╗ ██║" -ForegroundColor Cyan
Write-Host "  ██║╚██╗██║██╔══╝  ██╔══██╗██╔══╝  ██║     ██║   ██║██║███╗██║" -ForegroundColor Cyan
Write-Host "  ██║ ╚████║███████╗██████╔╝██║     ███████╗╚██████╔╝╚███╔███╔╝" -ForegroundColor Cyan
Write-Host "  ╚═╝  ╚═══╝╚══════╝╚═════╝ ╚═╝     ╚══════╝ ╚═════╝  ╚══╝╚══╝" -ForegroundColor Cyan
Write-Host ""
Write-Host "  Nebflow v$Version Installer ($Channel)" -ForegroundColor DarkGray
Write-Host ""

# --- Check Java ---
Write-Host "[1/4] Checking Java..." -ForegroundColor Yellow

function Test-Java {
    $savedEAP = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & java -version 2>&1 | Out-String
        $match = [regex]::Match($output, '"(\d+)')
        if ($match.Success) {
            $ver = [int]$match.Groups[1].Value
            if ($ver -eq 1) {
                $match2 = [regex]::Match($output, '"1\.(\d+)')
                if ($match2.Success) { $ver = [int]$match2.Groups[1].Value }
            }
            if ($ver -ge 17) {
                $ErrorActionPreference = $savedEAP
                return $ver
            }
        }
    } catch {}
    # Fallback: check common install paths
    $jdkDirs = @(
        "C:\Program Files\Eclipse Adoptium\jdk-17*-hotspot\bin",
        "C:\Program Files\Temurin\jdk-17*\bin",
        "C:\Program Files\Java\jdk-17*\bin"
    )
    foreach ($dir in $jdkDirs) {
        $javaExe = Get-Item (Join-Path $dir "java.exe") -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($javaExe) {
            $output = & $javaExe.FullName -version 2>&1 | Out-String
            $match = [regex]::Match($output, '"(\d+)')
            if ($match.Success) {
                $ver = [int]$match.Groups[1].Value
                if ($ver -ge 17) {
                    # Add to PATH so subsequent commands find it
                    $env:Path = "$($javaExe.DirectoryName);$env:Path"
                    $ErrorActionPreference = $savedEAP
                    return $ver
                }
            }
        }
    }
    $ErrorActionPreference = $savedEAP
    return 0
}

$javaVer = Test-Java
if ($javaVer -ge 17) {
    $savedEAP = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $javaLine = & java -version 2>&1 | Select-Object -First 1 | Out-String
    $ErrorActionPreference = $savedEAP
    Write-Host "       OK: $($javaLine.Trim())" -ForegroundColor Green
} else {
    Write-Host "       Java 17+ not found. Installing..." -ForegroundColor Yellow

    # Download Temurin JDK 17 from mirror
    $jdkPath = "$env:TEMP\temurin-jdk17.msi"
    $jdkMirrors = @(
        "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/x64/windows/OpenJDK17U-jdk_x64_windows_hotspot_17.0.19_10.msi",
        "https://repo.huaweicloud.com/openjdk/17.0.2/openjdk-17.0.2_windows-x64_bin.msi"
    )

    $jdkDownloaded = $false
    foreach ($url in $jdkMirrors) {
        Write-Host "       Downloading JDK 17..." -ForegroundColor DarkGray
        $ProgressPreference = 'SilentlyContinue'
        Invoke-WebRequest -Uri $url -OutFile $jdkPath -UseBasicParsing
        if (Test-Path $jdkPath) {
            $size = (Get-Item $jdkPath).Length
            if ($size -gt 10000000) {
                $jdkDownloaded = $true
                break
            }
            Remove-Item $jdkPath -Force -ErrorAction SilentlyContinue
        }
        Write-Host "       Mirror failed, trying next..." -ForegroundColor DarkGray
    }

    if (-not $jdkDownloaded) {
        Write-Host "       Auto-install failed. Please install JDK 17 manually:" -ForegroundColor Red
        Write-Host "       https://adoptium.net/" -ForegroundColor Yellow
        exit 1
    }

    Write-Host "       Installing JDK 17..." -ForegroundColor DarkGray
    $proc = Start-Process msiexec.exe -ArgumentList "/i", $jdkPath, "/quiet", "ADDLOCAL=FeatureMain,FeatureEnvironment,FeatureJarFileRunWith" -Wait -PassThru
    Remove-Item $jdkPath -Force -ErrorAction SilentlyContinue

    if ($proc.ExitCode -ne 0) {
        Write-Host "       JDK install failed (exit code $($proc.ExitCode))." -ForegroundColor Red
        Write-Host "       Please install JDK 17 manually: https://adoptium.net/" -ForegroundColor Yellow
        exit 1
    }

    # Refresh PATH in current session
    $env:Path = [Environment]::GetEnvironmentVariable("Path", "Machine") + ";" + [Environment]::GetEnvironmentVariable("Path", "User")

    $javaVer = Test-Java
    if ($javaVer -lt 17) {
        Write-Host "       JDK installed but not detected. Please restart your terminal and run this script again." -ForegroundColor Red
        exit 1
    }
    Write-Host "       JDK 17 installed." -ForegroundColor Green
}

# --- Download Nebflow ---
Write-Host "[2/4] Downloading Nebflow v$Version..." -ForegroundColor Yellow

New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
$jarPath = Join-Path $InstallDir $JarName

# Clean up old versions (keep .nebflow user data untouched)
$oldJars = Get-ChildItem (Join-Path $InstallDir "nebflow-assembly-*.jar") -ErrorAction SilentlyContinue
foreach ($old in $oldJars) {
    if ($old.FullName -ne $jarPath) {
        Remove-Item $old.FullName -Force -ErrorAction SilentlyContinue
        Write-Host "       Removed old: $($old.Name)" -ForegroundColor DarkGray
    }
}

if (Test-Path $jarPath) {
    Write-Host "       Already up-to-date (v$Version), skipping download." -ForegroundColor Green
} else {
    # Auto-detect region for download source selection
    if (-not $Region) {
        $tz = [TimeZoneInfo]::Local.Id
        if ($tz -match "China|Shanghai|Chongqing|Hong_Kong|Taipei|Macau|Urumqi") {
            $Region = "cn"
        } elseif ($env:LANG -match "zh_CN|zh_TW|zh_HK" -or $env:LC_ALL -match "zh_CN|zh_TW|zh_HK") {
            $Region = "cn"
        } else {
            # Quick connectivity test: compare latency
            try {
                $cosTime = (Measure-Command {
                    Invoke-WebRequest -Uri "https://nebflow-releases-1411212853.cos.ap-nanjing.myqcloud.com/" -UseBasicParsing -TimeoutSec 3 | Out-Null
                }).TotalMilliseconds
            } catch { $cosTime = 9999 }
            try {
                $ghTime = (Measure-Command {
                    Invoke-WebRequest -Uri "https://github.com/favicon.ico" -UseBasicParsing -TimeoutSec 3 | Out-Null
                }).TotalMilliseconds
            } catch { $ghTime = 9999 }
            if ($cosTime -lt 500 -and $cosTime -lt ($ghTime / 2)) {
                $Region = "cn"
            } else {
                $Region = "global"
            }
        }
    }

    if ($Channel -eq "beta") {
        # Beta: always from GitHub
        Invoke-WebRequest -Uri $GhBetaUrl -OutFile $jarPath -UseBasicParsing -TimeoutSec 120
    } elseif ($Region -eq "cn") {
        # China: COS first (fast domestic CDN), GitHub fallback
        try {
            Invoke-WebRequest -Uri $CosUrl -OutFile $jarPath -UseBasicParsing -TimeoutSec 30
        } catch {
            Write-Host "       COS unavailable, trying GitHub..." -ForegroundColor Yellow
            Invoke-WebRequest -Uri $GhUrl -OutFile $jarPath -UseBasicParsing -TimeoutSec 120
        }
    } else {
        # Global: GitHub first (fast via public release repo), COS fallback
        try {
            Invoke-WebRequest -Uri $GhUrl -OutFile $jarPath -UseBasicParsing -TimeoutSec 30
        } catch {
            Write-Host "       GitHub unavailable, trying COS mirror..." -ForegroundColor Yellow
            Invoke-WebRequest -Uri $CosUrl -OutFile $jarPath -UseBasicParsing -TimeoutSec 120
        }
    }
    $size = [math]::Round((Get-Item $jarPath).Length / 1MB, 1)
    Write-Host "       Downloaded ($size MB) from $($Region) source" -ForegroundColor Green
}

# --- Install ripgrep (rg) for search support ---
Write-Host "[3/4] Installing ripgrep (rg)..." -ForegroundColor Yellow
if (Get-Command "rg" -ErrorAction SilentlyContinue) {
    Write-Host "       rg already available in PATH." -ForegroundColor Green
} elseif (Test-Path (Join-Path $InstallDir "rg.exe")) {
    Write-Host "       rg already cached." -ForegroundColor Green
} else {
    $rgUrl = "https://github.com/BurntSushi/ripgrep/releases/download/14.1.1/ripgrep-14.1.1-x86_64-pc-windows-msvc.zip"
    $rgMirrors = @(
        "https://ghproxy.net/https://github.com/BurntSushi/ripgrep/releases/download/14.1.1/ripgrep-14.1.1-x86_64-pc-windows-msvc.zip"
    )
    $rgZip = Join-Path $InstallDir "rg.zip"
    try {
        Write-Host "       Downloading rg 14.1.1..." -ForegroundColor DarkGray
        try {
            Invoke-WebRequest -Uri $rgUrl -OutFile $rgZip -UseBasicParsing -TimeoutSec 15
        } catch {
            Write-Host "       GitHub timeout, trying mirror..." -ForegroundColor DarkGray
            Invoke-WebRequest -Uri $rgMirrors[0] -OutFile $rgZip -UseBasicParsing -TimeoutSec 30
        }
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $zip = [System.IO.Compression.ZipFile]::OpenRead($rgZip)
        $entry = $zip.Entries | Where-Object { $_.Name -eq "rg.exe" } | Select-Object -First 1
        if ($entry) {
            [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $InstallDir "rg.exe"), $true)
            Write-Host "       rg installed to $InstallDir" -ForegroundColor Green
        }
        $zip.Dispose()
        Remove-Item $rgZip -Force -ErrorAction SilentlyContinue
    } catch {
        Write-Host "       rg download failed: $_" -ForegroundColor DarkGray
        Write-Host "       Search will rely on PATH install." -ForegroundColor DarkGray
    }
}

# --- Create wrapper scripts ---
Write-Host "[4/4] Creating launcher..." -ForegroundColor Yellow

# PowerShell wrapper
$wrapperPath = Join-Path $InstallDir "nebflow.ps1"
$wrapperContent = @"
`$jar = Get-ChildItem "`$PSScriptRoot\nebflow-assembly-*.jar" | Sort-Object Name | Select-Object -Last 1
if (-not `$jar) {
    Write-Host "ERROR: nebflow JAR not found in `$PSScriptRoot" -ForegroundColor Red
    exit 1
}
& java --add-opens java.base/java.lang=ALL-UNNAMED -jar `$jar.FullName `$args
"@
Set-Content -Path $wrapperPath -Value $wrapperContent -Encoding UTF8

# CMD wrapper
$cmdPath = Join-Path $InstallDir "nebflow.cmd"
$cmdContent = @"
@echo off
set "PATH=%PATH%;C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot\bin;C:\Program Files\Temurin\jdk-17*\bin;C:\Program Files\Java\jdk-17*\bin"
for %%f in ("%~dp0nebflow-assembly-*.jar") do set JAR=%%f
if "%JAR%"=="" (
    echo ERROR: nebflow JAR not found in %~dp0
    exit /b 1
)
java --add-opens java.base/java.lang=ALL-UNNAMED -jar "%JAR%" %*
"@
Set-Content -Path $cmdPath -Value $cmdContent -Encoding ASCII

# Add to PATH
$userPath = [Environment]::GetEnvironmentVariable("Path", "User")
if ($userPath -notlike "*$InstallDir*") {
    [Environment]::SetEnvironmentVariable("Path", "$userPath;$InstallDir", "User")
    Write-Host "       Added to PATH" -ForegroundColor Green
}

# --- Config ---
Write-Host "[4/4] Setting up config..." -ForegroundColor Yellow

$configDir = Join-Path $env:USERPROFILE ".nebflow"
$configFile = Join-Path $configDir "nebflow.json"
if (-not (Test-Path $configFile)) {
    New-Item -ItemType Directory -Force -Path $configDir | Out-Null
    $configContent = "{}"
    [System.IO.File]::WriteAllText($configFile, $configContent)
    Write-Host "       Config created: $configFile" -ForegroundColor Green
    Write-Host "       Please edit it to set your API key." -ForegroundColor Yellow
} else {
    Write-Host "       Config already exists." -ForegroundColor DarkGray
}

# --- Done ---
Write-Host ""
Write-Host "=====================================" -ForegroundColor Green
Write-Host "  Nebflow v$Version installed!" -ForegroundColor Green
Write-Host "=====================================" -ForegroundColor Green
Write-Host ""
Write-Host "  Commands:" -ForegroundColor White
Write-Host "    nebflow --help" -ForegroundColor Cyan
Write-Host "    nebflow start" -ForegroundColor Cyan
Write-Host ""
Write-Host "  Config: $env:USERPROFILE\.nebflow\nebflow.json" -ForegroundColor DarkGray
Write-Host ""
Write-Host "  NOTE: Restart your terminal for PATH to take effect." -ForegroundColor Yellow
Write-Host ""
if ([Console]::IsInputRedirected -eq $false) {
    Read-Host "Press Enter to exit"
}

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$Version = if ($env:VERSION) { $env:VERSION } else { "1.0.0" }
$InstallDir = if ($env:INSTALL_DIR) { $env:INSTALL_DIR } else { "$env:LOCALAPPDATA\Nebflow" }
$JarName = "nebflow-assembly-$Version.jar"
$DownloadUrl = "https://nebflow-releases-1411212853.cos.ap-nanjing.myqcloud.com/$JarName"

Write-Host ""
Write-Host "  _   _ _____ _     _     ___  " -ForegroundColor Cyan
Write-Host " | \ | | ____| |   | |   / _ \ " -ForegroundColor Cyan
Write-Host " |  \| |  _| | |   | |  | | | |" -ForegroundColor Cyan
Write-Host " | |\  | |___| |___| |__| |_| |" -ForegroundColor Cyan
Write-Host " |_| \_|_____|_____|_____\___/ " -ForegroundColor Cyan
Write-Host ""
Write-Host "  Installer v$Version" -ForegroundColor DarkGray
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

if (Test-Path $jarPath) {
    Write-Host "       Already exists, skipping. (Delete $jarPath to re-download)" -ForegroundColor DarkGray
} else {
    Invoke-WebRequest -Uri $DownloadUrl -OutFile $jarPath -UseBasicParsing
    $size = [math]::Round((Get-Item $jarPath).Length / 1MB, 1)
    Write-Host "       Downloaded ($size MB)" -ForegroundColor Green
}

# --- Create wrapper scripts ---
Write-Host "[3/4] Creating launcher..." -ForegroundColor Yellow

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
Write-Host "    nebflow --server" -ForegroundColor Cyan
Write-Host ""
Write-Host "  Config: $env:USERPROFILE\.nebflow\nebflow.json" -ForegroundColor DarkGray
Write-Host ""
Write-Host "  NOTE: Restart your terminal for PATH to take effect." -ForegroundColor Yellow
Write-Host ""
if ([Console]::IsInputRedirected -eq $false) {
    Read-Host "Press Enter to exit"
}

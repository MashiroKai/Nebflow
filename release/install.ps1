$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

# -- Brand values (L2 rebrand): rendered from repo-root brand.conf at -------
# -- release time (scripts/render-brand.sh); do not edit by hand. -----------
# The runtime dual-reads legacy names (L3), so rendering NEW values here
# keeps existing user data working - old dirs/env/config fall back.
$ProductName = "Nebflow"
$LowerName = "nebflow"
$CosBucket = "nebflow-releases-1411212853"
$HomeDir = ".nebflow"
$ConfigFile = "nebflow.json"
$WrapperName = "nebflow"
$CosBaseCn = "https://$CosBucket.cos.ap-nanjing.myqcloud.com"
# 仓库已转 private（#29，2026-09-01）：GitHub Releases 未认证下载 404——
# Nebflow jar 下载/版本解析统一走 COS（单一源）。第三方依赖
# （Temurin JDK/Git for Windows/ripgrep）与仓库 private 无关，仍走各自公共源。

# Parse flags
$Channel = if ($env:CHANNEL) { $env:CHANNEL } else { "stable" }
$Region = ""
# Also check command-line args (for direct execution, not via iex)
if ($args -contains "-Beta") { $Channel = "beta" }
if ($args -contains "--channel=beta") { $Channel = "beta" }
if ($args -contains "-Cn") { $Region = "cn" }
if ($args -contains "-Global") { $Region = "global" }

# Resolve version — COS version file first (China-friendly), GitHub API fallback
if ($Channel -eq "beta") {
    Write-Host "==> Resolving latest beta version..." -ForegroundColor Yellow
    if ($env:VERSION) {
        $Version = $env:VERSION
    } else {
        try {
            $BetaVersion = (Invoke-WebRequest -Uri "$CosBaseCn/latest-beta-version.txt" -UseBasicParsing -TimeoutSec 10).Content.Trim()
        } catch {}
        if (-not $BetaVersion) {
            Write-Host "ERROR: Could not find a beta release (COS version file unreachable)." -ForegroundColor Red
            Write-Host "       Check $CosBaseCn/latest-beta-version.txt" -ForegroundColor Yellow
            exit 1
        }
        $Version = $BetaVersion
    }
} else {
    Write-Host "==> Resolving latest stable version..." -ForegroundColor Yellow
    if ($env:VERSION) {
        $Version = $env:VERSION
    } else {
        try {
            $LatestVersion = (Invoke-WebRequest -Uri "$CosBaseCn/latest-version.txt" -UseBasicParsing -TimeoutSec 10).Content.Trim()
        } catch {}
        if (-not $LatestVersion) {
            Write-Host "ERROR: Could not resolve latest version (COS version file unreachable)." -ForegroundColor Red
            Write-Host "       Check $CosBaseCn/latest-version.txt" -ForegroundColor Yellow
            exit 1
        }
        $Version = $LatestVersion
    }
}

$InstallDir = if ($env:INSTALL_DIR) { $env:INSTALL_DIR } else { "$env:LOCALAPPDATA\$ProductName" }
$JarName = "$LowerName-assembly-$Version.jar"
$CosUrl = "$CosBaseCn/$JarName"

Write-Host ""
Write-Host "  ███╗   ██╗███████╗██████╗ ███████╗██╗      ██████╗ ██╗    ██╗" -ForegroundColor Cyan
Write-Host "  ████╗  ██║██╔════╝██╔══██╗██╔════╝██║     ██╔═══██╗██║    ██║" -ForegroundColor Cyan
Write-Host "  ██╔██╗ ██║█████╗  ██████╔╝█████╗  ██║     ██║   ██║██║ █╗ ██║" -ForegroundColor Cyan
Write-Host "  ██║╚██╗██║██╔══╝  ██╔══██╗██╔══╝  ██║     ██║   ██║██║███╗██║" -ForegroundColor Cyan
Write-Host "  ██║ ╚████║███████╗██████╔╝██║     ███████╗╚██████╔╝╚███╔███╔╝" -ForegroundColor Cyan
Write-Host "  ╚═╝  ╚═══╝╚══════╝╚═════╝ ╚═╝     ╚══════╝ ╚═════╝  ╚══╝╚══╝" -ForegroundColor Cyan
Write-Host ""
Write-Host "  $ProductName v$Version Installer ($Channel)" -ForegroundColor DarkGray
Write-Host ""

# --- Check Java ---
Write-Host "[1/7] Checking Java..." -ForegroundColor Yellow

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

# --- Check Git for Windows (provides bash.exe for Bash tool) ---
Write-Host "[2/7] Checking Git for Windows..." -ForegroundColor Yellow

function Test-GitBash {
    $candidates = @(
        "$env:ProgramFiles\Git\bin\bash.exe",
        "${env:ProgramFiles(x86)}\Git\bin\bash.exe"
    )
    if ($env:LOCALAPPDATA) {
        $candidates += "$env:LOCALAPPDATA\Programs\Git\bin\bash.exe"
    }
    foreach ($path in $candidates) {
        if (Test-Path $path) { return $true }
    }
    # Check PATH but exclude WSL's bash (C:\Windows\System32\bash.exe)
    $bashCmd = Get-Command bash -ErrorAction SilentlyContinue
    if ($bashCmd -and $bashCmd.Source -notlike "*\System32\bash.exe") {
        return $true
    }
    return $false
}

if (Test-GitBash) {
    Write-Host "       OK: Git Bash found" -ForegroundColor Green
} else {
    Write-Host "       Git Bash not found. Installing Git for Windows..." -ForegroundColor Yellow

    $gitInstalled = $false

    # Method 1: Try winget (cleanest if available)
    $wingetCmd = Get-Command winget -ErrorAction SilentlyContinue
    if ($wingetCmd) {
        Write-Host "       Trying winget..." -ForegroundColor DarkGray
        try {
            $proc = Start-Process winget -ArgumentList @(
                "install", "--id", "Git.Git", "-e", "--source", "winget",
                "--silent", "--accept-package-agreements", "--accept-source-agreements"
            ) -Wait -PassThru -NoNewWindow 2>&1
            if ($LASTEXITCODE -eq 0 -and (Test-GitBash)) {
                $gitInstalled = $true
            }
        } catch {}
    }

    # Method 2: Direct download with region-aware source selection
    if (-not $gitInstalled) {
        # Determine region (timezone-based, instant — reused by JAR download later)
        if (-not $Region) {
            $tz = [TimeZoneInfo]::Local.Id
            if ($tz -match "China|Shanghai|Chongqing|Hong_Kong|Taipei|Macau|Urumqi") {
                $Region = "cn"
            } elseif ($env:LANG -match "zh_CN|zh_TW|zh_HK" -or $env:LC_ALL -match "zh_CN|zh_TW|zh_HK") {
                $Region = "cn"
            } else {
                $Region = "global"
            }
        }

        $gitVer = "2.55.0.3"
        $gitTag = "v2.55.0.windows.3"
        $gitInstaller = "Git-$gitVer-64-bit.exe"
        $gitPath = "$env:TEMP\$gitInstaller"

        if ($Region -eq "cn") {
            # China: domestic mirrors first, GitHub fallback
            $gitMirrors = @(
                "https://registry.npmmirror.com/-/binary/git-for-windows/$gitTag/$gitInstaller",
                "https://ghproxy.net/https://github.com/git-for-windows/git/releases/download/$gitTag/$gitInstaller",
                "https://github.com/git-for-windows/git/releases/download/$gitTag/$gitInstaller"
            )
        } else {
            # Global: GitHub first, domestic mirror fallback
            $gitMirrors = @(
                "https://github.com/git-for-windows/git/releases/download/$gitTag/$gitInstaller",
                "https://registry.npmmirror.com/-/binary/git-for-windows/$gitTag/$gitInstaller"
            )
        }

        foreach ($url in $gitMirrors) {
            Write-Host "       Downloading Git for Windows..." -ForegroundColor DarkGray
            try {
                Invoke-WebRequest -Uri $url -OutFile $gitPath -UseBasicParsing -TimeoutSec 300
                if (Test-Path $gitPath) {
                    $size = (Get-Item $gitPath).Length
                    if ($size -gt 50000000) { break }
                }
            } catch {
                Remove-Item $gitPath -Force -ErrorAction SilentlyContinue
                Write-Host "       Mirror failed, trying next..." -ForegroundColor DarkGray
            }
        }

        if (Test-Path $gitPath -and (Get-Item $gitPath).Length -gt 50000000) {
            Write-Host "       Installing Git for Windows..." -ForegroundColor DarkGray
            $proc = Start-Process $gitPath -ArgumentList "/VERYSILENT", "/NORESTART", "/NOCANCEL", "/SP-", "/CLOSEAPPLICATIONS", "/RESTARTAPPLICATIONS" -Wait -PassThru
            Remove-Item $gitPath -Force -ErrorAction SilentlyContinue

            # Refresh PATH so subsequent checks find bash
            $env:Path = [Environment]::GetEnvironmentVariable("Path", "Machine") + ";" + [Environment]::GetEnvironmentVariable("Path", "User")

            if ($proc.ExitCode -eq 0 -and (Test-GitBash)) {
                Write-Host "       Git for Windows installed." -ForegroundColor Green
            } else {
                Write-Host "       Git install may have failed (exit $($proc.ExitCode))." -ForegroundColor Red
                Write-Host "       Please install manually: https://git-scm.com/download/win" -ForegroundColor Yellow
            }
        } else {
            Write-Host "       Git download failed from all mirrors." -ForegroundColor Red
            Write-Host "       Please install manually: https://git-scm.com/download/win" -ForegroundColor Yellow
            Write-Host "       (Bash tool requires Git for Windows)" -ForegroundColor Yellow
        }
    }
}

# --- Download $ProductName ---
Write-Host "[3/7] Downloading $ProductName v$Version..." -ForegroundColor Yellow

New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
$jarPath = Join-Path $InstallDir $JarName

# Clean up old versions (keep .nebflow user data untouched)
$oldJars = @(Get-ChildItem (Join-Path $InstallDir "$LowerName-assembly-*.jar") -ErrorAction SilentlyContinue) + @(Get-ChildItem (Join-Path $InstallDir "nebflow-assembly-*.jar") -ErrorAction SilentlyContinue)
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
                    Invoke-WebRequest -Uri "$CosBaseCn/" -UseBasicParsing -TimeoutSec 3 | Out-Null
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

    # #29: 仓库 private 后 GitHub Releases 未认证 404——COS 单一源
    try {
        Invoke-WebRequest -Uri $CosUrl -OutFile $jarPath -UseBasicParsing -TimeoutSec 120
    } catch {
        Write-Host "ERROR: Download failed from COS. Check $CosUrl" -ForegroundColor Red
        exit 1
    }
    $size = [math]::Round((Get-Item $jarPath).Length / 1MB, 1)
    Write-Host "       Downloaded ($size MB) from COS" -ForegroundColor Green
}

# --- Install ripgrep (rg) for search support ---
Write-Host "[4/7] Installing ripgrep (rg)..." -ForegroundColor Yellow
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

# --- Download Whisper voice model ---
Write-Host "[5/7] Voice model (Whisper, ~75MB one-time)..." -ForegroundColor Yellow
$modelDir = Join-Path $env:USERPROFILE "$HomeDir\voice-models\onnx-community\whisper-base"
$onnxEncPath = Join-Path $modelDir "onnx\encoder_model_quantized.onnx"

if (Test-Path $onnxEncPath) {
    Write-Host "       Already installed." -ForegroundColor Green
} else {
    # Determine mirror
    if (-not $Region) { $Region = "global" }
    $hfBase = if ($Region -eq "cn") {
        "https://hf-mirror.com/onnx-community/whisper-base/resolve/main"
    } else {
        "https://huggingface.co/onnx-community/whisper-base/resolve/main"
    }

    New-Item -ItemType Directory -Force -Path (Join-Path $modelDir "onnx") | Out-Null

    # Download config files (small)
    $configFiles = @("config.json", "tokenizer.json", "generation_config.json", "preprocessor_config.json")
    foreach ($cf in $configFiles) {
        $target = Join-Path $modelDir $cf
        if (-not (Test-Path $target)) {
            try { Invoke-WebRequest -Uri "$hfBase/$cf" -OutFile $target -UseBasicParsing -TimeoutSec 15 } catch {}
        }
    }

    # Download quantized ONNX models (encoder ~22MB + decoder ~51MB)
    Write-Host "       Downloading model..." -ForegroundColor DarkGray
    $dlOk = $true
    try {
        Invoke-WebRequest -Uri "$hfBase/onnx/encoder_model_quantized.onnx" -OutFile $onnxEncPath -UseBasicParsing -TimeoutSec 120
    } catch { $dlOk = $false }
    try {
        $decPath = Join-Path $modelDir "onnx\decoder_model_merged_quantized.onnx"
        Invoke-WebRequest -Uri "$hfBase/onnx/decoder_model_merged_quantized.onnx" -OutFile $decPath -UseBasicParsing -TimeoutSec 120
    } catch { $dlOk = $false }
    if ($dlOk) {
        $encSz = [math]::Round((Get-Item $onnxEncPath).Length / 1MB, 1)
        Write-Host "       Voice model installed ($encSz MB encoder)." -ForegroundColor Green
    } else {
        Write-Host "       Download failed (voice will use CDN on first use)." -ForegroundColor DarkGray
    }
}

# --- Create wrapper scripts ---
Write-Host "[6/7] Creating launcher..." -ForegroundColor Yellow

# PowerShell wrapper
$wrapperPath = Join-Path $InstallDir "$WrapperName.ps1"
$wrapperContent = @"
`$jar = @(Get-ChildItem "`$PSScriptRoot\nebflow-assembly-*.jar") + @(Get-ChildItem "`$PSScriptRoot\$LowerName-assembly-*.jar") | Sort-Object Name | Select-Object -Last 1
if (-not `$jar) {
    Write-Host "ERROR: $ProductName JAR not found in `$PSScriptRoot" -ForegroundColor Red
    exit 1
}
& java --add-opens java.base/java.lang=ALL-UNNAMED -jar `$jar.FullName `$args
"@
Set-Content -Path $wrapperPath -Value $wrapperContent -Encoding UTF8

# CMD wrapper
$cmdPath = Join-Path $InstallDir "$WrapperName.cmd"
$cmdContent = @"
@echo off
set "PATH=%PATH%;C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot\bin;C:\Program Files\Temurin\jdk-17*\bin;C:\Program Files\Java\jdk-17*\bin"
for %%f in ("%~dp0nebflow-assembly-*.jar" "%~dp0$LowerName-assembly-*.jar") do set JAR=%%f
if "%JAR%"=="" (
    echo ERROR: %~dp0 JAR not found
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
Write-Host "[7/7] Setting up config..." -ForegroundColor Yellow

$configDir = Join-Path $env:USERPROFILE "$HomeDir"
$configFile = Join-Path $configDir "$ConfigFile"
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
Write-Host "  $ProductName v$Version installed!" -ForegroundColor Green
Write-Host "=====================================" -ForegroundColor Green
Write-Host ""
Write-Host "  Commands:" -ForegroundColor White
Write-Host "    $WrapperName --help" -ForegroundColor Cyan
Write-Host "    $WrapperName start" -ForegroundColor Cyan
Write-Host ""
Write-Host "  Config: $env:USERPROFILE\$HomeDir\$ConfigFile" -ForegroundColor DarkGray
Write-Host ""
Write-Host "  NOTE: Restart your terminal for PATH to take effect." -ForegroundColor Yellow
Write-Host ""
if ([Console]::IsInputRedirected -eq $false) {
    Read-Host "Press Enter to exit"
}

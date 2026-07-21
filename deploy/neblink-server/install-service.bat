@echo off
REM ============================================================
REM NebLink Server - Windows Service Installation (Rust)
REM
REM Prerequisites:
REM   - neblink-server.exe in the same directory
REM   - NSSM (auto-installed via Chocolatey if missing)
REM
REM Usage:
REM   1. Build: cargo build --release (on any machine with Rust)
REM   2. Copy target\release\neblink-server.exe here
REM   3. Run this script as Administrator
REM ============================================================

setlocal

set SERVICE_NAME=NebLinkServer
set EXE_PATH=%~dp0neblink-server.exe
set LOG_DIR=%~dp0logs

REM Check admin privileges
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo ERROR: Please run as Administrator.
    exit /b 1
)

REM Check EXE exists
if not exist "%EXE_PATH%" (
    echo ERROR: neblink-server.exe not found in %~dp0
    echo Build with: cargo build --release
    echo Or download a pre-built release.
    exit /b 1
)

REM Create log directory
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"

REM Ensure NSSM is available
where nssm >nul 2>nul
if %errorlevel% neq 0 (
    echo NSSM not found. Installing via Chocolatey...
    where choco >nul 2>nul
    if %errorlevel% neq 0 (
        echo ERROR: NSSM and Chocolatey not found.
        echo Install one of:
        echo   choco install nssm -y
        echo   or download from https://nssm.cc/download
        exit /b 1
    )
    choco install nssm -y || (
        echo ERROR: Failed to install NSSM.
        exit /b 1
    )
)

REM Remove existing service if present
nssm status %SERVICE_NAME% >nul 2>nul
if %errorlevel% equ 0 (
    echo Removing existing service...
    nssm stop %SERVICE_NAME% >nul 2>nul
    nssm remove %SERVICE_NAME% confirm >nul 2>nul
)

REM Install service
echo Installing %SERVICE_NAME%...
nssm install %SERVICE_NAME% "%EXE_PATH%"

REM Configure
nssm set %SERVICE_NAME% AppDirectory "%~dp0" >nul
nssm set %SERVICE_NAME% DisplayName "NebLink Server" >nul
nssm set %SERVICE_NAME% Description "Nebflow device discovery and NebLink server (port 9090)" >nul
nssm set %SERVICE_NAME% Start SERVICE_AUTO_START >nul

REM Log rotation (10MB per file)
nssm set %SERVICE_NAME% AppStdout "%LOG_DIR%\stdout.log" >nul
nssm set %SERVICE_NAME% AppStderr "%LOG_DIR%\stderr.log" >nul
nssm set %SERVICE_NAME% AppRotateFiles 1 >nul
nssm set %SERVICE_NAME% AppRotateBytes 10485760 >nul

REM Crash recovery: auto-restart on any exit, 5s delay
nssm set %SERVICE_NAME% AppExit Default Restart >nul
nssm set %SERVICE_NAME% AppRestartDelay 5000 >nul
REM Graceful shutdown: 10s to drain connections before force-kill
nssm set %SERVICE_NAME% AppStopMethodConsole 10000 >nul
REM Throttle: if 3 rapid restarts in 10s, pause to avoid crash loop
nssm set %SERVICE_NAME% AppThrottle 10000 >nul

REM Start
echo Starting service...
nssm start %SERVICE_NAME%

echo.
echo ===============================================
echo  NebLink Server installed successfully!
echo ===============================================
echo  Service:  %SERVICE_NAME%
echo  Port:     9090
echo  Binary:   %EXE_PATH%
echo  Logs:     %LOG_DIR%\^*.log
echo  Auto-start on boot: YES
echo  Crash recovery:     AUTO-RESTART (5s delay)
echo  Memory:   ~5 MB (no JVM!)
echo.
echo  Verify:   curl http://localhost:9090/api/health
echo  Stop:     nssm stop %SERVICE_NAME%
echo  Start:    nssm start %SERVICE_NAME%
echo  Remove:   uninstall-service.bat
echo.

endlocal

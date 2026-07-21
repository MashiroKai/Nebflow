@echo off
REM ============================================================
REM Nebflow Coordinator - Windows Service Installation
REM
REM Prerequisites:
REM   - Java 17+ in PATH
REM   - NSSM (auto-installed via Chocolatey if missing)
REM   - nebflow.jar in the same directory as this script
REM
REM Usage:
REM   1. Build JAR: sbt assembly
REM   2. Copy target/scala-3.5.2/nebflow.jar here
REM   3. Run this script as Administrator
REM ============================================================

setlocal

set SERVICE_NAME=NebflowCoordinator
set JAR_PATH=%~dp0nebflow.jar
set LOG_DIR=%~dp0logs

REM Check admin privileges
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo ERROR: Please run as Administrator.
    exit /b 1
)

REM Check Java
where java >nul 2>nul
if %errorlevel% neq 0 (
    echo ERROR: Java not found in PATH. Install Java 17+.
    exit /b 1
)

REM Check JAR exists
if not exist "%JAR_PATH%" (
    echo ERROR: nebflow.jar not found in %~dp0
    echo Build it with: sbt assembly
    echo Then copy target\scala-3.5.2\nebflow.jar here.
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
nssm install %SERVICE_NAME% java -cp "%JAR_PATH%" nebflow.coordinator.CoordinatorMain

REM Configure
nssm set %SERVICE_NAME% AppDirectory "%~dp0" >nul
nssm set %SERVICE_NAME% DisplayName "Nebflow Coordinator Server" >nul
nssm set %SERVICE_NAME% Description "Nebflow device discovery and coordination service (port 9090)" >nul
nssm set %SERVICE_NAME% Start SERVICE_AUTO_START >nul
nssm set %SERVICE_NAME% AppStdout "%LOG_DIR%\stdout.log" >nul
nssm set %SERVICE_NAME% AppStderr "%LOG_DIR%\stderr.log" >nul
nssm set %SERVICE_NAME% AppRotateFiles 1 >nul
nssm set %SERVICE_NAME% AppRotateBytes 10485760 >nul

REM Start
echo Starting service...
nssm start %SERVICE_NAME%

echo.
echo ===============================================
echo  Nebflow Coordinator installed successfully!
echo ===============================================
echo  Service:  %SERVICE_NAME%
echo  Port:     9090
echo  JAR:      %JAR_PATH%
echo  Logs:     %LOG_DIR%\^*.log
echo  Auto-start on boot: YES
echo.
echo  Verify:   curl http://localhost:9090/api/health
echo  Stop:     nssm stop %SERVICE_NAME%
echo  Start:    nssm start %SERVICE_NAME%
echo  Remove:   uninstall-service.bat
echo.

endlocal

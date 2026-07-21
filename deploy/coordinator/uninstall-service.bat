@echo off
REM ============================================================
REM Nebflow Coordinator - Remove Windows Service
REM ============================================================

setlocal
set SERVICE_NAME=NebflowCoordinator

net session >nul 2>&1
if %errorlevel% neq 0 (
    echo ERROR: Please run as Administrator.
    exit /b 1
)

echo Stopping %SERVICE_NAME%...
nssm stop %SERVICE_NAME% 2>nul

echo Removing service registration...
nssm remove %SERVICE_NAME% confirm

echo.
echo Service removed. JAR and log files were NOT deleted.
echo To reinstall: install-service.bat

endlocal

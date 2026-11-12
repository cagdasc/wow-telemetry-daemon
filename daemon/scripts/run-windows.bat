@echo off
setlocal

rem Set this to the folder containing WoWCombatLog-MMDDYY_HHMMSS.txt files.
set "LOG_DIR=C:\Program Files (x86)\World of Warcraft\_retail_\Logs"
if defined WOW_COMBAT_LOG_DIR set "LOG_DIR=%WOW_COMBAT_LOG_DIR%"

rem NewLog: back up and remove old logs, then wait for a new one.
rem Latest: read the most recent existing log.
rem WOW_RUN_MODE or the first argument skips the prompt.
set "RUN_MODE="
if defined WOW_RUN_MODE set "RUN_MODE=%WOW_RUN_MODE%"
if not "%~1"=="" set "RUN_MODE=%~1"

if not defined RUN_MODE (
    echo WoW Telemetry daemon
    echo.
    echo   [N] New log: back up and remove old logs, then wait for a new one
    echo   [L] Latest log: read the most recent existing log
    echo.
    choice /C NL /N /M "Choose a mode [N/L]: "
    if errorlevel 2 (set "RUN_MODE=Latest") else (set "RUN_MODE=NewLog")
    echo.
)

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-windows.ps1" -LogDirectory "%LOG_DIR%" -Mode "%RUN_MODE%"
set "EXIT_CODE=%ERRORLEVEL%"

echo.
if not "%EXIT_CODE%"=="0" echo Daemon launcher exited with code %EXIT_CODE%.
pause
exit /b %EXIT_CODE%

@echo off
setlocal

rem Set this to the folder containing WoWCombatLog-MMDDYY_HHMMSS.txt files.
set "LOG_DIR=C:\Program Files (x86)\World of Warcraft\_retail_\Logs"
if defined WOW_COMBAT_LOG_DIR set "LOG_DIR=%WOW_COMBAT_LOG_DIR%"

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-windows.ps1" -LogDirectory "%LOG_DIR%"
set "EXIT_CODE=%ERRORLEVEL%"

echo.
if not "%EXIT_CODE%"=="0" echo Daemon launcher exited with code %EXIT_CODE%.
pause
exit /b %EXIT_CODE%

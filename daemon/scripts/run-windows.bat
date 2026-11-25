@echo off
setlocal

rem Launch interactive PowerShell runner passing all arguments
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-windows.ps1" %*
set "EXIT_CODE=%ERRORLEVEL%"

if not "%EXIT_CODE%"=="0" (
    echo.
    echo Launcher exited with code %EXIT_CODE%.
    pause
)
exit /b %EXIT_CODE%

@echo off
setlocal DisableDelayedExpansion
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -STA -ExecutionPolicy Bypass -File "%~dp0Manage.ps1" -Action Launch
set "code=%ERRORLEVEL%"
if not "%code%"=="0" pause
exit /b %code%

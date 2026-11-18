@echo off
setlocal DisableDelayedExpansion
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -STA -ExecutionPolicy Bypass -File "%~dp0version\tools\Manage.ps1" -Action Install
set "code=%ERRORLEVEL%"
pause
exit /b %code%

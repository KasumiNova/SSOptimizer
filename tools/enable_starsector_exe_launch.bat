@echo off
setlocal EnableExtensions

set "SCRIPT_DIR=%~dp0"
set "PS_SCRIPT=%SCRIPT_DIR%enable_starsector_exe_launch.ps1"

if not exist "%PS_SCRIPT%" (
    echo ERROR: 未找到 PowerShell 脚本: %PS_SCRIPT%
    exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%PS_SCRIPT%" %*
exit /b %errorlevel%

@echo off
setlocal EnableExtensions

set "GAME_ROOT=%~dp0"
set "LAUNCHER_DIR=%GAME_ROOT%starsector-core"
set "LAUNCHER=%LAUNCHER_DIR%\starsector.bat"
if not exist "%LAUNCHER%" (
    echo ERROR: Missing SSOptimizer Windows launcher: %LAUNCHER%
    exit /b 1
)

pushd "%LAUNCHER_DIR%"
call "%LAUNCHER%" %*
set "EXIT_CODE=%errorlevel%"
popd
exit /b %EXIT_CODE%
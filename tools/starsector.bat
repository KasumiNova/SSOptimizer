@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "SCRIPT_DIR=%~dp0"
cd /d "%SCRIPT_DIR%"
for %%I in ("%SCRIPT_DIR%..") do set "GAME_ROOT=%%~fI"
set "JAVA_EXE="

for /r "%SCRIPT_DIR%" %%I in (java.exe) do (
    if not defined JAVA_EXE call :tryCandidate "%%~fI"
)
if defined JAVA_EXE goto runGame

for /r "%GAME_ROOT%" %%I in (java.exe) do (
    if not defined JAVA_EXE call :tryCandidate "%%~fI"
)
if defined JAVA_EXE goto runGame

if defined JAVA_HOME call :tryCandidate "%JAVA_HOME%\bin\java.exe"
if defined JAVA_EXE goto runGame

for /f "delims=" %%I in ('where java.exe 2^>nul') do (
    if not defined JAVA_EXE call :tryCandidate "%%~fI"
)
if defined JAVA_EXE goto runGame

echo ERROR: ?????? Java 25 ???????????/?????JAVA_HOME ? PATH?
exit /b 1

:runGame
"%JAVA_EXE%" -javaagent:../mods/ssoptimizer/jars/SSOptimizer.jar -Dfile.encoding=UTF-8 -noverify -XX:+UnlockDiagnosticVMOptions -XX:+ShowCodeDetailsInExceptionMessages -XX:+PrintCommandLineFlags -XX:+TieredCompilation -XX:+DisableExplicitGC -XX:+AlwaysPreTouch -XX:+ParallelRefProcEnabled -XX:+UseZGC -XX:ReservedCodeCacheSize=256m -XX:CompilerDirectivesFile=./compiler_directives.txt -Djdk.xml.maxElementDepth=10000 -XX:-BytecodeVerificationLocal -XX:-BytecodeVerificationRemote -Djava.util.Arrays.useLegacyMergeSort=true --enable-preview --add-opens=java.base/sun.nio.ch=ALL-UNNAMED --add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/java.nio.Buffer.UNSAFE=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.util.concurrent=ALL-UNNAMED --add-opens=java.base/java.util.concurrent.locks=ALL-UNNAMED --add-opens=java.base/jdk.internal.ref=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED --add-opens=java.base/java.lang.ref=ALL-UNNAMED --add-opens=java.base/java.text=ALL-UNNAMED --add-opens=java.desktop/java.awt.font=ALL-UNNAMED --add-opens=java.desktop/java.awt.Rectangle=ALL-UNNAMED --add-opens=java.desktop/java.awt=ALL-UNNAMED --add-exports=java.base/jdk.internal.ref=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED --add-exports=java.base/sun.nio.ch=ALL-UNNAMED -Xms4096m -Xmx4096m -Xss4m -Dcom.fs.starfarer.settings.paths.saves=../saves -Dcom.fs.starfarer.settings.paths.screenshots=../screenshots -Dcom.fs.starfarer.settings.paths.mods=../mods -Dcom.fs.starfarer.settings.paths.logs=. -Dssoptimizer.font.ttf.enable=true -Dlog4j.configuration=file:./log4j.properties -Djava.library.path=./native/windows -Dcom.fs.starfarer.settings.windows=true -classpath janino.jar;commons-compiler.jar;commons-compiler-jdk.jar;starfarer.api.jar;starfarer_obf.jar;jogg-0.0.7.jar;jorbis-0.0.15.jar;json.jar;lwjgl.jar;jinput.jar;log4j-1.2.9.jar;lwjgl_util.jar;fs.sound_obf.jar;fs.common_obf.jar;xstream-1.4.10.jar;txw2-3.0.2.jar;jaxb-api-2.4.0-b180830.0359.jar;webp-imageio-0.1.6.jar com.fs.starfarer.StarfarerLauncher %*
if errorlevel 1 pause
exit /b %errorlevel%

:tryCandidate
set "CANDIDATE=%~1"
if not exist "%CANDIDATE%" goto :eof
set "VERSION_FILE=%TEMP%\ssoptimizer-java-version-%RANDOM%-%RANDOM%.txt"
"%CANDIDATE%" -version >"%VERSION_FILE%" 2>&1
findstr /C:"25." "%VERSION_FILE%" >nul
if not errorlevel 1 set "JAVA_EXE=%CANDIDATE%"
del "%VERSION_FILE%" >nul 2>&1
goto :eof

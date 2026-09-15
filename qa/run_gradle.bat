@echo off
rem QA-side Gradle build script (relative paths; runs in any workspace / CI).
chcp 65001 >nul

rem qa\ 's parent is the repo root, so derive android project / toolchain from %~dp0..
cd /d "%~dp0..\android"

set "JAVA_HOME=%~dp0..\build-tools\jdk-17"
set "ANDROID_HOME=%~dp0..\build-tools\android-sdk"
set "ANDROID_SDK_ROOT=%ANDROID_HOME%"
set "PATH=%JAVA_HOME%\bin;%PATH%"

echo [INFO] JAVA_HOME    = %JAVA_HOME%
echo [INFO] ANDROID_HOME = %ANDROID_HOME%
echo [INFO] GRADLE       = %~dp0..\build-tools\gradle-8.2

rem Dependencies go through the Tencent Cloud maven mirror; keep online (no --offline,
rem which would fail when dependencies are not fully cached).
"%~dp0..\build-tools\gradle-8.2\bin\gradle.bat" --no-daemon assembleDebug > "%~dp0gradle_build.log" 2>&1
set "RC=%ERRORLEVEL%"

echo Exit code: %RC%
rem Non-interactive / CI friendly: never pause, propagate the exit code.
exit /b %RC%

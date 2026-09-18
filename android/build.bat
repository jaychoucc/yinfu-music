@echo off
chcp 65001 >nul
cd /d "%~dp0"

set "JAVA_HOME=%~dp0..\build-tools\jdk\jdk-17.0.20.1+1"
set "ANDROID_HOME=%~dp0..\build-tools\android-sdk"
set "ANDROID_SDK_ROOT=%ANDROID_HOME%"
set "PATH=%JAVA_HOME%\bin;%PATH%"

echo [INFO] JAVA_HOME    = %JAVA_HOME%
echo [INFO] ANDROID_HOME = %ANDROID_HOME%
echo [INFO] GRADLE       = %~dp0..\build-tools\gradle-8.2
echo.
echo [INFO] Running assembleDebug...
echo.

"%~dp0..\build-tools\gradle-8.2\bin\gradle.bat" --no-daemon assembleDebug
if errorlevel 1 (
    echo.
    echo [ERROR] Build failed.
    pause
    exit /b 1
)

echo.
echo [OK] APK(s) generated under:
echo      %~dp0app\build\outputs\apk\debug\
echo.
echo [INFO] Latest APK:
dir /b /od "%~dp0app\build\outputs\apk\debug\yinfu-music-*.apk" 2>nul | findstr "yinfu-music" || echo      (none)
pause

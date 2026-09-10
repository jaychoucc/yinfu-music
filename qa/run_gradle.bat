@echo off
chcp 65001 >nul
cd /d "C:\Users\b5311\WorkBuddy\2026-09-10-11-29-51\yinfu-music\android"

set "JAVA_HOME=C:\Users\b5311\WorkBuddy\2026-09-10-11-29-51\yinfu-music\build-tools\jdk-17"
set "ANDROID_HOME=C:\Users\b5311\WorkBuddy\2026-09-10-11-29-51\yinfu-music\build-tools\android-sdk"
set "ANDROID_SDK_ROOT=%ANDROID_HOME%"
set "PATH=%JAVA_HOME%\bin;%PATH%"

echo [INFO] JAVA_HOME    = %JAVA_HOME%
echo [INFO] ANDROID_HOME = %ANDROID_HOME%

"C:\Users\b5311\WorkBuddy\2026-09-10-11-29-51\yinfu-music\build-tools\gradle-8.2\bin\gradle.bat" --no-daemon --offline assembleDebug > "C:\Users\b5311\WorkBuddy\2026-09-10-11-29-51\yinfu-music\qa\gradle_build.log" 2>&1
echo Exit code: %ERRORLEVEL%
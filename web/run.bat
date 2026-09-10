@echo off
chcp 65001 >nul
cd /d "%~dp0"

if exist ".venv\Scripts\python.exe" goto :run

echo [INFO] .venv not found, creating virtual environment...
python -m venv .venv
if errorlevel 1 (
    echo [ERROR] Failed to create venv. Make sure Python 3.10+ is on PATH.
    pause
    exit /b 1
)

echo [INFO] Installing dependencies...
".venv\Scripts\python.exe" -m pip install --upgrade pip
".venv\Scripts\python.exe" -m pip install -r requirements.txt
if errorlevel 1 (
    echo [ERROR] Dependency installation failed.
    pause
    exit /b 1
)

:run
echo [INFO] Starting yinfu-music web server on http://127.0.0.1:58652
start "" http://127.0.0.1:58652
".venv\Scripts\python.exe" server.py
pause

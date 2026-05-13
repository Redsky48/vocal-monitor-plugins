@echo off
REM Double-click this file to launch the plugin test app.
REM Works from any location — finds the repo root automatically.
setlocal

cd /d "%~dp0..\.."

where java >nul 2>nul
if errorlevel 1 (
    echo Java is not on your PATH. Install a JDK (Adoptium / Microsoft / Oracle)
    echo from https://adoptium.net/ and try again.
    pause
    exit /b 1
)

java tools\test-app\TestApp.java
if errorlevel 1 (
    echo.
    echo Test app exited with an error.
    pause
)

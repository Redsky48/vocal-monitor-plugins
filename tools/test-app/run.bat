@echo off
REM Double-click this file to launch the plugin test app.
REM Works from any location and finds Java even if it isn't on PATH.
setlocal EnableDelayedExpansion

cd /d "%~dp0..\.."

REM 1. Try whatever's already on PATH.
set "JAVA_EXE="
where java >nul 2>nul
if not errorlevel 1 (
    set "JAVA_EXE=java"
    goto :run
)

REM 2. JAVA_HOME / JDK_HOME hints.
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" (
        set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
        goto :run
    )
)
if defined JDK_HOME (
    if exist "%JDK_HOME%\bin\java.exe" (
        set "JAVA_EXE=%JDK_HOME%\bin\java.exe"
        goto :run
    )
)

REM 3. Scan Program Files for any installed JDK (Microsoft, Adoptium,
REM    Oracle, Zulu, Liberica, Corretto — any vendor that lays out a
REM    standard <vendor>\jdk-<version>\bin\java.exe).
for %%R in ("%ProgramFiles%" "%ProgramFiles(x86)%" "%ProgramW6432%") do (
    if exist "%%~R" (
        for /d %%V in ("%%~R\Microsoft\jdk-*" "%%~R\Eclipse Adoptium\jdk-*" ^
                      "%%~R\Java\jdk-*" "%%~R\Java\jdk*" ^
                      "%%~R\Zulu\zulu-*" "%%~R\BellSoft\LibericaJDK-*" ^
                      "%%~R\Amazon Corretto\jdk*" "%%~R\Semeru\jdk-*") do (
            if exist "%%~V\bin\java.exe" (
                set "JAVA_EXE=%%~V\bin\java.exe"
                goto :run
            )
        )
    )
)

echo Could not find a Java installation. Install a JDK (Adoptium /
echo Microsoft / Oracle) from https://adoptium.net/ and try again.
echo.
echo If you DO have a JDK installed in a non-standard location, set
echo the JAVA_HOME environment variable to point at it.
pause
exit /b 1

:run
echo Using Java: %JAVA_EXE%
REM Force UTF-8 so non-ASCII characters in source files / button labels
REM render correctly on Windows JREs that default to windows-1252.
"%JAVA_EXE%" -Dfile.encoding=UTF-8 tools\test-app\TestApp.java
if errorlevel 1 (
    echo.
    echo Test app exited with an error.
    pause
)

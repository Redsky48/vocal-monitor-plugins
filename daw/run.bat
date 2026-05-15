@echo off
REM Double-click this file to launch the Vocal Monitor DAW.
REM
REM Works from any location, finds a JDK even if it isn't on PATH,
REM and KEEPS the console window open if something goes wrong so the
REM Gradle / Kotlin / Compose error is actually readable instead of
REM flashing past.
REM
REM First run downloads Gradle 8.9 + Kotlin 2.0.20 + Compose Desktop
REM 1.7.0 (~150 MB) into ~/.gradle.  Subsequent runs are instant.

setlocal EnableDelayedExpansion

REM Always run from the daw/ folder this script lives in, regardless
REM of where it was double-clicked from.
cd /d "%~dp0"

REM ── 1. Find a JDK (Gradle needs JAVA_HOME pointing at a JDK, not
REM    just a JRE — DAW also needs the in-process JavaCompiler API
REM    so the plugin loader can compile .java plugin sources). ──

set "JAVA_HOME_FOUND="

REM 1a. Already-set JAVA_HOME wins, but only if it actually has java.exe.
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" (
        set "JAVA_HOME_FOUND=%JAVA_HOME%"
        goto :have_jdk
    )
)
if defined JDK_HOME (
    if exist "%JDK_HOME%\bin\java.exe" (
        set "JAVA_HOME_FOUND=%JDK_HOME%"
        goto :have_jdk
    )
)

REM 1b. Scan Program Files for any installed JDK 17+.  Matches the
REM     same vendor layouts the test-app's run.bat checks for.
for %%R in ("%ProgramFiles%" "%ProgramFiles(x86)%" "%ProgramW6432%") do (
    if exist "%%~R" (
        for /d %%V in ("%%~R\Microsoft\jdk-*" "%%~R\Eclipse Adoptium\jdk-*" ^
                      "%%~R\Java\jdk-*" "%%~R\Java\jdk*" ^
                      "%%~R\Zulu\zulu-*" "%%~R\BellSoft\LibericaJDK-*" ^
                      "%%~R\Amazon Corretto\jdk*" "%%~R\Semeru\jdk-*") do (
            if exist "%%~V\bin\java.exe" (
                set "JAVA_HOME_FOUND=%%~V"
                goto :have_jdk
            )
        )
    )
)

REM 1c. Fall back to whatever `java` is on PATH but only as a last
REM     resort — Gradle prefers JAVA_HOME set.
where java >nul 2>nul
if not errorlevel 1 (
    for /f "delims=" %%J in ('where java') do (
        for %%X in ("%%~dpJ..") do (
            if exist "%%~fX\bin\java.exe" (
                set "JAVA_HOME_FOUND=%%~fX"
                goto :have_jdk
            )
        )
    )
)

echo.
echo Could not find a JDK.  Install one (e.g. Adoptium / Microsoft
echo Build of OpenJDK / Oracle JDK) from https://adoptium.net/ and
echo set JAVA_HOME to point at the install folder.
echo.
pause
exit /b 1

:have_jdk
set "JAVA_HOME=%JAVA_HOME_FOUND%"
set "PATH=%JAVA_HOME%\bin;%PATH%"
echo Using JDK: %JAVA_HOME%
echo.

REM ── 2. Run the DAW via the Gradle wrapper. ──
REM `--console=plain` keeps output non-interactive (no progress bar
REM that breaks under double-click) so any error is readable.
call "%~dp0gradlew.bat" --console=plain run
set "RC=%errorlevel%"

if not "%RC%"=="0" (
    echo.
    echo Gradle exited with error %RC%.
    echo Scroll up to see what went wrong, then close this window.
    echo.
    pause
)

endlocal

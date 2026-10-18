@echo off
rem ============================================================
rem  springai-code-tui launcher (Windows)
rem  Requirement: JDK 17+
rem  Config: Copy bin\config.env.example to bin\config.env
rem          and fill in at least one API key.
rem          See bin\config.env.example for all options.
rem ============================================================
setlocal
set "APP_HOME=%~dp0.."

rem --- Load optional config.env ---
rem Search order: CODETUI_CONFIG > bin\config.env > %USERPROFILE%\.codetui\config.env
set "CONFIG="
if defined CODETUI_CONFIG if exist "%CODETUI_CONFIG%" set "CONFIG=%CODETUI_CONFIG%"
if not defined CONFIG if exist "%~dp0config.env" set "CONFIG=%~dp0config.env"
if not defined CONFIG if exist "%USERPROFILE%\.codetui\config.env" set "CONFIG=%USERPROFILE%\.codetui\config.env"
if defined CONFIG (
    for /f "usebackq eol=# tokens=1,* delims==" %%A in ("%CONFIG%") do set "%%A=%%B"
)

rem --- Locate Java ---
if defined JAVA_HOME (
    set "JAVA=%JAVA_HOME%\bin\java.exe"
) else (
    set "JAVA=java"
)

if "%JAVA%"=="java" (
    where java >nul 2>nul
    if errorlevel 1 (
        echo ERROR: Java not found. Install JDK 17+ or set JAVA_HOME. 1>&2
        exit /b 1
    )
) else (
    if not exist "%JAVA%" (
        echo ERROR: Java not found at %JAVA%. Check JAVA_HOME. 1>&2
        exit /b 1
    )
)

rem Log directory: defaults to logs\ under the install dir （ avoid polluting user project dirs）; falls back to %USERPROFILE%\.codetui\logs if creation fails.
set "LOG_DIR=%APP_HOME%\logs"
mkdir "%LOG_DIR%" 2>nul
if not exist "%LOG_DIR%\" (
    set "LOG_DIR=%USERPROFILE%\.codetui\logs"
    mkdir "%USERPROFILE%\.codetui\logs" 2>nul
)

rem --- Launch ---
"%JAVA%" %JAVA_OPTS% -Dfile.encoding=UTF-8 -Dcodetui.log.dir="%LOG_DIR%" -jar "%APP_HOME%\springai-code-tui.jar" %*
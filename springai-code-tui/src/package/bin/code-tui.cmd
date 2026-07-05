@echo off
rem springai-code-tui 启动脚本（Windows）。解压后直接运行本脚本。
rem 需求：JDK 21+。API key 通过环境变量提供：DEEPSEEK_API_KEY / ANTHROPIC_API_KEY / OPENAI_API_KEY
setlocal
set "APP_HOME=%~dp0.."

if defined JAVA_HOME (
    set "JAVA=%JAVA_HOME%\bin\java.exe"
) else (
    set "JAVA=java"
)

where "%JAVA%" >nul 2>nul
if errorlevel 1 (
    echo 错误: 未找到 java。请安装 JDK 21+ 或设置 JAVA_HOME 后重试。 1>&2
    exit /b 1
)

rem 日志目录：默认写到安装目录下 logs\，不污染用户项目目录；创建失败则回退到 %USERPROFILE%\.codetui\logs。
set "LOG_DIR=%APP_HOME%\logs"
mkdir "%LOG_DIR%" 2>nul
if not exist "%LOG_DIR%\" (
    set "LOG_DIR=%USERPROFILE%\.codetui\logs"
    mkdir "%USERPROFILE%\.codetui\logs" 2>nul
)

"%JAVA%" %JAVA_OPTS% -Dcodetui.log.dir="%LOG_DIR%" -jar "%APP_HOME%\springai-code-tui.jar" %*

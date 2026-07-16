@echo off
rem springai-code-tui 启动脚本（Windows）。解压后直接运行本脚本。
rem 需求：JDK 21+。
rem 配置：把 bin\config.env.example 复制为 bin\config.env 并填 API key（至少一家）；或直接用环境变量。
rem   可配项：DEEPSEEK_API_KEY / ZHIPU_API_KEY / DASHSCOPE_API_KEY / ANTHROPIC_API_KEY / OPENAI_API_KEY（及各自 *_BASE_URL）、
rem   CODETUI_LLM_READ_TIMEOUT_SECONDS、CODETUI_SUBAGENT_CONCURRENCY、JAVA_OPTS。
setlocal
set "APP_HOME=%~dp0.."

rem 加载可选 config.env（KEY=VALUE；# 开头为注释）。取消注释的行覆盖同名环境变量。
rem 查找：CODETUI_CONFIG > bin\config.env（与本脚本同目录）> %USERPROFILE%\.codetui\config.env
set "CONFIG="
if defined CODETUI_CONFIG if exist "%CODETUI_CONFIG%" set "CONFIG=%CODETUI_CONFIG%"
if not defined CONFIG if exist "%~dp0config.env" set "CONFIG=%~dp0config.env"
if not defined CONFIG if exist "%USERPROFILE%\.codetui\config.env" set "CONFIG=%USERPROFILE%\.codetui\config.env"
if defined CONFIG (
    for /f "usebackq eol=# tokens=1,* delims==" %%A in ("%CONFIG%") do set "%%A=%%B"
)

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

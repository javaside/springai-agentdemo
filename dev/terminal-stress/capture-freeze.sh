#!/bin/sh
# code-tui 冻结瞬间取证脚本。
#
# 为什么需要它：故障只在「输出中 + 人打字」的瞬间出现，且极短；事后手动敲 jstack/lsappinfo
# 测到的都是「不是故障的时候」。本脚本把「冻结瞬间需要同时抓取的全部证据」收敛成一条命令，
# 让下次冻住时能在 10 秒内拿到现场快照。
#
# 用法（在任意其他窗口执行，进程卡住时）：
#   dev/terminal-stress/capture-freeze.sh            # 自动找 code-tui 进程
#   dev/terminal-stress/capture-freeze.sh <pid>      # 指定 pid（多开时必须指定）
#
# 产物：默认为 /tmp/codetui-freeze-<时间戳>/ 下的一组文件 + summary.txt
# 可通过环境变量 CODETUI_CAPTURE_DIR 指定输出目录。

set -u

OUT="${CODETUI_CAPTURE_DIR:-/tmp/codetui-freeze-$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$OUT" || exit 1

PID="${1:-}"
if [ -z "$PID" ]; then
    PID=$(pgrep -f 'springai-code-tui\.jar' | head -1)
fi
if [ -z "$PID" ]; then
    echo "找不到 code-tui 进程；请显式传入 pid" >&2
    exit 1
fi
if ! ps -p "$PID" >/dev/null 2>&1; then
    echo "pid $PID 不存在" >&2
    exit 1
fi

JAVA_HOME_BIN=$(dirname "$(readlink -f "$(command -v java)")")
JSTACK="$JAVA_HOME_BIN/jstack"
TTY=$(ps -o tty= -p "$PID" | tr -d ' ')
CWD=$(lsof -a -p "$PID" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p')

echo "== 正在取证 pid=$PID tty=/dev/$TTY =="
echo "== 产物目录：$OUT"

# 1) Java 线程转储（判定有无 BLOCKED、main/pty-writer/tui-input-reader 各停在哪个调用）
[ -x "$JSTACK" ] && "$JSTACK" -l "$PID" > "$OUT/jstack.txt" 2>&1
# 2) 线程级实时 CPU（找出真正在烧 CPU 的线程；冻结时若全为 0 则为「挨饿」形态）
top -l 2 -pid "$PID" -stats pid,command,cpu,threads,state -n 20 > "$OUT/top.txt" 2>&1
# 3) 进程状态：stat 含 T(=SIGTTIN/SIGTTOU 停住)？控制终端是谁？cwd 是哪个项目？
ps -o pid,ppid,pgid,stat,tty,etime,time,pcpu,command -p "$PID" > "$OUT/ps.txt" 2>&1
ps -eo pid,ppid,pgid,stat,tty,etime,command > "$OUT/ps-all.txt" 2>&1
# 4) macOS GUI 注册与激活态（判断是否被当成 GUI 应用、是否抢了前台）
lsappinfo list 2>/dev/null | grep -B2 -A6 "pid = $PID" > "$OUT/lsappinfo.txt" 2>&1
lsappinfo front > "$OUT/lsappinfo-front.txt" 2>&1
osascript -e 'tell application "System Events" to get name of every process whose background only is false' \
    > "$OUT/non-background-apps.txt" 2>&1
# 5) native 栈（Java 侧与 Terminal 侧都要；区分「应用不写」与「终端不读」）
sample "$PID" 2 -file "$OUT/sample-java.txt" >/dev/null 2>&1
# 本机 pgrep -x Terminal 实测匹配不到；且 macOS 的 comm 是完整可执行路径，
# 故按路径结尾匹配（实测 Terminal 的 comm = /System/.../Terminal.app/Contents/MacOS/Terminal）。
TERM_PID=$(ps -eo pid=,comm= | awk '$2 ~ /(^|\/)Terminal$/ { print $1; exit }')
[ -n "${TERM_PID:-}" ] && sample "$TERM_PID" 2 -file "$OUT/sample-terminal.txt" >/dev/null 2>&1
# 6) tty 两端状态：raw 模式？输入队列是否有积压？
if [ -n "$TTY" ]; then
    stty -f "/dev/$TTY" -a      > "$OUT/stty-$TTY.txt" 2>&1
    stty -f "/dev/$TTY" size    >> "$OUT/stty-$TTY.txt" 2>&1
fi
# 7) 网络连接（区分「模型侧挂住」与「本地渲染挂住」）
lsof -nP -a -i -p "$PID"        > "$OUT/sockets.txt" 2>&1
# 8) Terminal 自身视角：哪个 tab、busy 与否、前台窗口是谁
osascript -e 'tell application "Terminal"
set out to ""
repeat with w in windows
  set out to out & "visible=" & (visible of w) & " front=" & ((index of w) is 1) & " tty=" & (tty of selected tab of w) & " busy=" & (busy of selected tab of w) & linefeed
end repeat
return out
end tell' > "$OUT/terminal-tabs.txt" 2>&1
# 9) 应用日志尾（回合是否还在推进、有无异常）
LOG_DIR=$(ps -o command= -p "$PID" | tr ' ' '\n' | sed -n 's/^-Dcodetui\.log\.dir=//p')
[ -z "${LOG_DIR:-}" ] && LOG_DIR="$HOME/springai-code-tui-1.25.0/logs"
tail -120 "$LOG_DIR/springai-code-tui.log" > "$OUT/app-log-tail.txt" 2>&1
# 11) Terminal 侧健康信号（本故障线的关键证据）：TTBuffer 越界 + IME 光标更新。
#     为什么必须查这里：这类异常被 Terminal 自身捕获，**不产生 .ips 崩溃报告**，
#     只能从系统日志看到——早期只查 DiagnosticReports 的排查因此全部漏检。
#     TTBuffer 越界持续出现 = 终端进入「回滚缓冲记账错乱」状态，即「整屏卡死、打不了字」。
log show --last 10m --predicate 'process == "Terminal" AND (eventMessage CONTAINS "TTBuffer" OR eventMessage CONTAINS "scheduleUpdateCursorLocation")' --style compact > "$OUT/terminal-log-10m.txt" 2>&1

# 10) 崩溃报告有没有新增（区分本故障与 Terminal.app 段错误）
ls -lat "$HOME/Library/Logs/DiagnosticReports/"*.ips "$HOME/Library/Logs/DiagnosticReports/Retired/"*.ips \
    > "$OUT/crash-reports.txt" 2>&1

# 判读摘要（不做结论，只把关键事实摘出来）
{
    echo "pid=$PID  tty=/dev/$TTY  cwd=${CWD:-?}  时间=$(date '+%F %T')"
    echo
    echo "--- 进程状态（stat 含 T 说明被 SIGTTIN/SIGTTOU 停住）---"
    grep -v '^ *PID' "$OUT/ps.txt" 2>/dev/null
    echo
    echo "--- 线程状态统计（按 Thread.State 行聚合；状态行在线程名行的下一行）---"
    grep -E 'Thread[.]State:' "$OUT/jstack.txt" 2>/dev/null \
        | sed -E 's/.*Thread[.]State: ([A-Z_]+).*/\1/' | sort | uniq -c | sort -rn
    echo "BLOCKED 线程数: $(grep -c 'waiting to lock' "$OUT/jstack.txt" 2>/dev/null)"
    echo
    echo "--- main / pty-writer / tui-input-reader 的栈顶 ---"
    for t in main pty-writer tui-input-reader; do
        echo "[$t]"
        awk -v pat="\"$t\"" 'index($0, pat) == 1 { f=1; next } f && /^$/ { exit } f' \
            "$OUT/jstack.txt" 2>/dev/null | head -8
        echo
    done
    echo "--- 是否注册为 GUI 应用 / 当前前台 ---"
    grep -E 'type=|ApplicationType|LSWantsToComeForward' "$OUT/lsappinfo.txt" 2>/dev/null | head -5
    echo "front: $(lsappinfo info -only pid,name "$(cat "$OUT/lsappinfo-front.txt" 2>/dev/null)" 2>/dev/null | tr '\n' ' ')"
    echo
    echo "--- Terminal 侧采样是否仍在读（有 read/kevent 说明终端线程活着）---"
    if [ -s "$OUT/sample-terminal.txt" ]; then
        grep -cE 'kevent|read [(]in libsystem' "$OUT/sample-terminal.txt" 2>/dev/null \
            | sed 's/^/Terminal 采样中 read\/kevent 行数: /'
    else
        echo "Terminal 采样缺失（TERM_PID 未取到或 sample 失败）"
    fi
    echo
    echo "--- Terminal 侧异常（TTBuffer 越界=终端卡死的关键信号；被捕获故无 .ips）---"
    echo "TTBuffer 越界次数(近10分钟): $(grep -c TTBuffer "$OUT/terminal-log-10m.txt" 2>/dev/null)"
    echo "IME 光标更新次数(近10分钟): $(grep -c scheduleUpdateCursorLocation "$OUT/terminal-log-10m.txt" 2>/dev/null)"
    grep -m3 'TTBuffer' "$OUT/terminal-log-10m.txt" 2>/dev/null | cut -c1-150
    echo
    echo "--- 还在飞的连接 ---"
    echo "ESTABLISHED 套接字数: $(grep -c ESTABLISHED "$OUT/sockets.txt" 2>/dev/null)"
} > "$OUT/summary.txt" 2>&1

echo
cat "$OUT/summary.txt"
echo
echo "== 全部产物：$OUT =="
ls -l "$OUT"

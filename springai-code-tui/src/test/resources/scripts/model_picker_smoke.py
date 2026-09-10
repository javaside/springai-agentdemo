#!/usr/bin/env python3
"""PTY smoke test for the /model picker leaving stale content stuck on screen
when a todo panel is already showing above it.

Reported bug: with a populated todo panel visible (a real, long-running
session almost always has one), repeatedly opening the /model picker (type
"/model" + Enter) and cancelling with Esc leaves fragments of the
slash-command autocomplete menu permanently stuck on screen instead of being
redrawn away, accumulating one extra stuck copy every couple of attempts.

Root cause (see InlineDisplay#resizeDisplay in springai-tamboui-inline-patch):
the live region is one shared column — todo panel on top, slash-menu/picker
in the middle, input box + status line pinned at the bottom. When the todo
panel's row count dominates the top-vs-shifted heuristic (shiftAlignsBetter),
resizeDisplay takes the "non-shift" grow/shrink path. Its shrink branch used
to position the terminal's delete-line (DL) escape at `newHeight` (top-stable
+ bottom-stable row count) instead of at the actual number of unchanged rows
at the top — so DL fired partway into the shrinking middle section, deleting
the wrong rows and leaving the first few rows of that section (and the
"↑↓ 选择 · Tab 补全 …" status hint under some layouts) stuck forever. Fixed by
computing the real common-prefix length and using it as both the DL position
and the internal snapshot's realignment boundary.

Needs a populated todo panel AND a short terminal (rows tight enough that the
live region is genuinely pinned to the screen's last row — same trick
background_smoke.py uses for "does a panel push the input box off screen").
A tall terminal with little prior scrollback does not reproduce this: the
live region just grows into blank space below, so the wrong DL position never
runs into content a human would notice.

Unit tests cannot fully verify this either: byte-substring assertions (as in
InlineDisplayDiffTest) can check DL is issued at the right *position*, but
only a real ANSI-interpreting replay (pyte) proves the terminal's *content*
ends up correct. This script is that end-to-end check.

No real API key needed: a local stub HTTP server speaks the DeepSeek SSE
dialect (same technique as interjection_smoke.py / background_smoke.py).
First user message -> stub replies with a TodoWrite tool call (13 items,
so the panel also exercises the "... 还有 N 项" cap, matching the reported
screenshot); the follow-up tool-result call -> stub replies with plain
text, ending the turn so the picker cycles run against an idle session.

Usage:
    /usr/bin/python3 scripts/model_picker_smoke.py

Exit code 0 + "SMOKE PASS" on success, non-zero + "SMOKE FAIL: <reason>" on
failure. Always prints the screen snapshot after each cycle for a human to
eyeball.
"""
import fcntl
import json
import os
import select
import struct
import subprocess
import sys
import tempfile
import termios
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# pyte lives in the user site-packages for /usr/bin/python3.
import site
sys.path.insert(0, site.getusersitepackages())
import pyte  # noqa: E402

# 20 行故意偏小：live 区必须真贴到屏幕最后一行才会撞见 resizeDisplay 的错误 DL 定位
# （见文件头「Root cause」）；窗口够高、下面还有空白时，长高只是往空白里长，同一个
# bug 不会产生任何人眼看得见的后果。同款「小窗口才能逼出问题」手法见 background_smoke.py。
ROWS, COLS = 20, 100

# 从源码位置 src/test/resources/scripts/ 上溯到模块根 springai-code-tui（5 层 dirname）。
MODULE_ROOT = os.path.dirname(
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
)
CLASSES_DIR = os.path.join(MODULE_ROOT, "target", "classes")
CP_FILE = os.path.join(MODULE_ROOT, "target", "cp.txt")
MAIN_CLASS = "io.github.javaside.springai.codetui.CodeTuiApplication"

WELCOME_1 = "Spring AI Code TUI"
SLASH_MODEL_DESC = "切换 AI 模型"          # /model 在斜杠补全菜单里的描述文本（唯一标识该行）
GHOST_STATUS_HINT = "↑↓ 选择 · Tab 补全 · Enter 运行 · Esc 关闭"   # 斜杠补全菜单激活时的状态行提示
PICKER_HEADER = "选择模型"                 # /model 选择器面板标题
MODEL_ID = "deepseek-v4-pro"
TODO_MARK = "冒烟待办"                     # 待办内容里的唯一标记子串
FINAL_REPLY = "冒烟回复：回合收尾。"


def die(msg, screen=None):
    print("SMOKE FAIL: %s" % msg)
    if screen is not None:
        print_screen("LAST SEEN SCREEN", screen)
    sys.exit(1)


def print_screen(label, lines):
    print("=" * 20 + " %s " % label + "=" * 20)
    for i, line in enumerate(lines):
        print("%3d| %s" % (i, line.rstrip()))
    print("=" * (42 + len(label)))


# ── 桩模型：第一次调用吐 TodoWrite 工具调用（13 项，触发「还有 N 项」折叠），
#    第二次（工具结果之后）纯文本收尾 ──────────────────────────────────────
def _sse(payload):
    return ("data: " + json.dumps(payload, ensure_ascii=False) + "\n\n").encode()


def _chunk(delta, finish=None):
    return {
        "id": "mp-smoke-1",
        "object": "chat.completion.chunk",
        "created": 1,
        "model": MODEL_ID,
        "choices": [{"index": 0, "delta": delta, "finish_reason": finish}],
    }


def _text_chunks(text):
    return [
        _sse(_chunk({"role": "assistant", "content": ""})),
        _sse(_chunk({"content": text})),
        _sse(_chunk({}, finish="stop")),
    ]


def _todo_items(n=13):
    items = []
    for i in range(1, n + 1):
        status = "completed" if i <= 8 else ("in_progress" if i == 9 else "pending")
        items.append({
            "content": "%s %d" % (TODO_MARK, i),
            "activeForm": "正在处理 %s %d" % (TODO_MARK, i),
            "status": status,
        })
    return items


def _tool_call_chunks():
    call = {
        "index": 0,
        "id": "call_mp_1",
        "type": "function",
        "function": {"name": "TodoWrite", "arguments": json.dumps({"todos": _todo_items()})},
    }
    return [
        _sse(_chunk({"role": "assistant", "content": "", "tool_calls": [call]})),
        _sse(_chunk({}, finish="tool_calls")),
    ]


class StubModel(BaseHTTPRequestHandler):
    lock = threading.Lock()
    call_count = 0

    def log_message(self, fmt, *args):
        pass

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(length) or b"{}")
        messages = body.get("messages") or []
        last = messages[-1] if messages else {}
        role = last.get("role", "")

        if role == "tool":
            chunks = _text_chunks(FINAL_REPLY)
        else:
            chunks = _tool_call_chunks()

        with StubModel.lock:
            StubModel.call_count += 1

        try:
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            self.end_headers()
            for c in chunks:
                self.wfile.write(c)
                self.wfile.flush()
            self.wfile.write(b"data: [DONE]\n\n")
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass


def start_stub():
    srv = ThreadingHTTPServer(("127.0.0.1", 0), StubModel)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv, "http://127.0.0.1:%d" % srv.server_address[1]


class PtySession:
    def __init__(self, cmd, cwd, env, rows=None, cols=None):
        rows = ROWS if rows is None else rows
        cols = COLS if cols is None else cols
        self.rows, self.cols = rows, cols
        self.master_fd, self.slave_fd = os.openpty()
        winsize = struct.pack("HHHH", rows, cols, 0, 0)
        fcntl.ioctl(self.slave_fd, termios.TIOCSWINSZ, winsize)

        self.proc = subprocess.Popen(
            cmd,
            stdin=self.slave_fd,
            stdout=self.slave_fd,
            stderr=self.slave_fd,
            cwd=cwd,
            env=env,
            start_new_session=True,
        )
        os.close(self.slave_fd)
        self.slave_fd = None

        self.screen = pyte.Screen(cols, rows)
        self.stream = pyte.ByteStream(self.screen)

        flags = fcntl.fcntl(self.master_fd, fcntl.F_GETFL)
        fcntl.fcntl(self.master_fd, fcntl.F_SETFL, flags | os.O_NONBLOCK)

        self._pending = b""

    def write(self, data):
        os.write(self.master_fd, data)

    def type_slow(self, text, delay=0.12):
        for ch in text.encode():
            self.write(bytes([ch]))
            self.pump(delay)

    def pump(self, duration):
        deadline = time.time() + duration
        while time.time() < deadline:
            remaining = max(0.0, deadline - time.time())
            r, _, _ = select.select([self.master_fd], [], [], min(0.2, remaining))
            if self.master_fd in r:
                try:
                    chunk = os.read(self.master_fd, 65536)
                except OSError:
                    break
                if not chunk:
                    break
                self._pending += chunk
                self._handle_dsr()
                self.stream.feed(self._pending)
                self._pending = b""

    def _handle_dsr(self):
        needle = b"\x1b[6n"
        idx = self._pending.find(needle)
        while idx != -1:
            self.write(b"\x1b[1;1R")
            idx = self._pending.find(needle, idx + len(needle))

    def screen_text(self):
        return "\n".join(self.screen.display)

    def wait_for(self, substring, timeout=15):
        deadline = time.time() + timeout
        while time.time() < deadline:
            self.pump(0.2)
            if substring in self.screen_text():
                return True
            if self.proc.poll() is not None:
                die(
                    "process exited early (code=%s) while waiting for %r"
                    % (self.proc.returncode, substring),
                    self.screen.display,
                )
        die("timed out waiting for %r" % substring, self.screen.display)

    def close(self):
        try:
            if self.proc.poll() is None:
                self.proc.terminate()
                for _ in range(20):
                    if self.proc.poll() is not None:
                        break
                    time.sleep(0.1)
                if self.proc.poll() is None:
                    self.proc.kill()
        except ProcessLookupError:
            pass
        try:
            os.close(self.master_fd)
        except OSError:
            pass


def build_classpath():
    if not os.path.isdir(CLASSES_DIR):
        die("target/classes missing at %s; run mvn compile first" % CLASSES_DIR)
    if not os.path.isfile(CP_FILE):
        die("target/cp.txt missing at %s" % CP_FILE)
    with open(CP_FILE) as f:
        deps = f.read().strip()
    return CLASSES_DIR + os.pathsep + deps


def open_and_cancel_picker(session, cycle_no, downs=15):
    """敲 /model、等补全菜单出现、回车进选择器、按 ↓ 浏览几下（复现用户「选择 model」的动作）、
    Esc 退出。返回退出后的整屏文本。"""
    session.type_slow("/model")
    session.pump(0.3)
    session.write(b"\r")
    session.wait_for(PICKER_HEADER, timeout=10)
    session.pump(0.3)
    for _ in range(downs):
        session.write(b"\x1b[B")   # Down
        session.pump(0.1)
    session.pump(0.3)
    session.write(b"\x1b")   # Esc
    session.pump(0.6)
    print_screen("AFTER CYCLE %d (Esc)" % cycle_no, session.screen.display)
    return session.screen_text()


def main():
    classpath = build_classpath()
    tmpdir = tempfile.mkdtemp(prefix="codetui-smoke-")
    srv, base_url = start_stub()

    env = dict(os.environ)
    env["TERM"] = "xterm-256color"
    env["DEEPSEEK_API_KEY"] = "sk-dummy-not-real"
    env["DEEPSEEK_BASE_URL"] = base_url
    env["DEEPSEEK_MODELS"] = MODEL_ID

    cmd = ["java", "-cp", classpath, MAIN_CLASS]

    print("Launching: %s" % " ".join(cmd))
    print("cwd=%s stub=%s" % (tmpdir, base_url))

    session = PtySession(cmd, tmpdir, env)
    try:
        session.wait_for(WELCOME_1, timeout=20)
        print("Startup welcome banner OK.")

        session.write(b"hi\r")
        session.wait_for("%s 9" % TODO_MARK, timeout=15)     # in_progress 项（第 9 条）先出现
        session.wait_for(FINAL_REPLY, timeout=15)             # 回合真正收尾（第二次模型调用已回）
        session.pump(0.5)
        print_screen("TODO PANEL PRIMED", session.screen.display)

        # 两个标记都只应在「斜杠补全菜单/选择器货真价实开着」时出现在屏幕上——Esc 之后
        # 应该一个都不剩。踩坑记录：最初只查 SLASH_MODEL_DESC，20 行窗口下第二轮开始卡住不走
        # 的其实是状态行提示 GHOST_STATUS_HINT，单标记版本因此假绿过一次。
        ghost_markers = (SLASH_MODEL_DESC, GHOST_STATUS_HINT)

        def ghost_count(text):
            return sum(text.count(m) for m in ghost_markers)

        counts = []
        for i in range(1, 6):
            text = open_and_cancel_picker(session, i)
            n = ghost_count(text)
            counts.append(n)
            print("cycle %d: ghost marker occurrences on screen = %d" % (i, n))

        session.write(b"/exit\r")
        session.pump(1.0)

        failures = []
        if counts[-1] != 0:
            failures.append(
                "after Esc, stale slash-menu/picker content still on screen (count=%d, expected 0)"
                % counts[-1]
            )
        if any(counts[i] > counts[i - 1] for i in range(1, len(counts))):
            failures.append(
                "stale ghost-marker count grew across repeated cycles (accumulating ghosts): %r"
                % counts
            )

        if failures:
            die("; ".join(failures))

        print("SMOKE PASS")
        return 0
    finally:
        session.close()
        srv.shutdown()


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""PTY smoke test for terminal-resize sweeping (ResizeSweeper).

Drives the code-tui app on a real pseudo-terminal, resizes the window with a
real TIOCSWINSZ (the kernel delivers SIGWINCH to the child), and asserts that
after each resize the screen still holds exactly ONE input box at the new
width, the typed text survives, and -- crucially -- the welcome banner above
the box is untouched.

WHAT THIS SCRIPT CAN AND CANNOT SEE
-----------------------------------
The user-visible defect (stale wrapped fragments of the old frame left on
screen) needs a terminal that REFLOWS its content on resize -- Terminal.app,
iTerm2 and tmux do, pyte does not. So this script cannot reproduce the
original defect; that half is confirmed by a human (or tmux capture) in a
reflowing terminal.

WHAT THIS SCRIPT ACTUALLY PROTECTS (each one mutation-verified)
---------------------------------------------------------------
PROTECTED -- sweep over-reach. ResizeSweeper erases from the display area's
top row to the end of the screen (ESC[J) right before the library repaints.
Erasure needs no reflow, so pyte sees it: an extra 3-row up-move before ESC[J
wipes real history and the line-by-line history comparison goes red. (A weaker
"sentinel string still on screen" assertion stayed GREEN under the same
mutation -- the sentinels happened to sit above the over-reach; that is why
the comparison is line-by-line over everything above the input box.)

PROTECTED -- sweep presence. Disabling the sweep leaves stale frame rows on
screen and displaces the repainted box downward even in pyte (a tick can
repaint at the old width between TIOCSWINSZ and the resize event; those
too-wide rows wrap and walk the cursor down). Same history assertion goes red.

PROTECTED -- startup regression. The app must NOT scroll a screenful at launch
(an earlier design did, leaving the top half of the screen blank); asserted by
requiring the welcome banner to sit at the very top of the screen.

PROTECTED -- no line truncation (print-time and replay-time). A message is
submitted so the dummy-key 401 error line (~78 cols, wider than the 60-col
terminal) lands in scrollback: the print path must WRAP it (TextWrap /
wrapSegments), because InlineDisplay.println hard-truncates over-wide lines --
user-reported "reply text not fully shown". After each resize the history
check asserts CONTENT preservation (whitespace-squeezed substring), not
line-identity: the settle replay legitimately re-wraps at the new width
(joins lines when widening, splits when narrowing), and this check stays red
for both sweep over-reach (a line's content vanishes wholesale) and replay
truncation (line tails vanish).

PROTECTED -- IME anchor (hardware cursor parking). pyte's cursor IS the
hardware cursor, which Terminal.app uses as the IME preedit anchor: pinning it
permanently to the box's TOP BORDER row (an earlier resize fix did) floats the
pinyin composition one row above the input line -- user-reported "typing feels
misaligned". Steady state (no resize in flight, and again after a settle
replay finishes) must park the cursor on the input TEXT row; the row-0 pinning
is allowed only inside a resize window (parkCursorAtTop).

NOT COVERED -- how the swept screen actually looks in a REFLOWING terminal
(fragment-free narrow, multiplexer-coalesced rapid drags). pyte does not
reflow; that half is confirmed in tmux (see ResizeSweeper class doc) and
ultimately by a human dragging a real window.

PROTECTED -- settle replay wipes the scrollback. Real reflowing terminals
(Terminal.app reproduced via AppleScript drag, 2026-08) push the old frame's
wrapped rows into the SCROLLBACK on every narrow drag -- ESC[J sweeping can
never reach them, so each drag deposited a full stale copy of the UI (banner +
box + status line, plus screenfuls of blanks) into history: scrolling back was
garbage. The fix: the stage-2 settle replay (ResizeSettle +
CodeTuiView.replayAfterResize) erases scrollback too (ESC[3J, same path as
/clear) and replays the WHOLE scrollTail keep, so scrolling back shows a clean
re-wrapped conversation instead of frame corpses. pyte has no scrollback, so
this script asserts the CONTRACT on the raw byte stream: after each resize
settles (~300ms quiet), ESC[3J must have been emitted. Screen-level truth is
confirmed by the Terminal.app AppleScript repro (history contains exactly one
copy of the banner after repeated drags).

COVERED ONLY AS A CONTRACT -- how the replayed scrollback actually looks in a
reflowing terminal (fragment-free, no duplicate banner) -- see above; pyte
cannot see it. tmux-verified earlier: slow drag 100->60, rapid drag 100<->45,
and widen-back all settle to conversation tail + one clean box.

Usage:
    /usr/bin/python3 src/test/resources/scripts/resize_smoke.py

Exit code 0 + "SMOKE PASS" on success, non-zero + "SMOKE FAIL: <reason>".
"""
import fcntl
import os
import re
import select
import struct
import subprocess
import sys
import tempfile
import termios
import time

# pyte lives in the user site-packages for /usr/bin/python3.
import site
sys.path.insert(0, site.getusersitepackages())
import pyte  # noqa: E402

# 40 行而不是 30：45 列下重放会把横幅/长行折成约 1.8 倍高，30 行屏装不下、最早几行会
# 滚进 pyte 看不见的 scrollback，内容断言会被「滚出屏」假触发。40 行时全部内容留在可见屏。
ROWS = 40
NARROW_COLS = 60
WIDE_COLS = 100
FINAL_COLS = 45

# 从源码位置 src/test/resources/scripts/ 上溯到模块根 springai-code-tui（5 层 dirname）。
MODULE_ROOT = os.path.dirname(
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
)
CLASSES_DIR = os.path.join(MODULE_ROOT, "target", "classes")
CP_FILE = os.path.join(MODULE_ROOT, "target", "cp.txt")
MAIN_CLASS = "io.github.javaside.springai.codetui.CodeTuiApplication"

WELCOME = "Spring AI Code TUI"
TYPED = "abcdefghij" * 3          # 30 chars: fits on one visual line at all three widths

# 输入框的上边框：整行只有圆角与横线。欢迎横幅的上边框带标题文字，故不会误命中。
BOX_TOP = re.compile(r"^╭─+╮$")


def die(msg, screen=None):
    print("SMOKE FAIL: %s" % msg)
    if screen is not None:
        print_screen("LAST SEEN SCREEN", screen)
    sys.exit(1)


def print_screen(label, lines):
    print("=" * 20 + " %s " % label + "=" * 20)
    for i, line in enumerate(lines):
        if line.rstrip():
            print("%3d| %s" % (i, line.rstrip()))
    print("=" * (42 + len(label)))


class PtySession:
    def __init__(self, cmd, cwd, env, rows, cols):
        self.rows, self.cols = rows, cols
        self.master_fd, self.slave_fd = os.openpty()
        fcntl.ioctl(self.slave_fd, termios.TIOCSWINSZ, struct.pack("HHHH", rows, cols, 0, 0))
        self.proc = subprocess.Popen(
            cmd, stdin=self.slave_fd, stdout=self.slave_fd, stderr=self.slave_fd,
            cwd=cwd, env=env, start_new_session=True,
        )
        os.close(self.slave_fd)
        self.slave_fd = None
        self.screen = pyte.Screen(cols, rows)
        self.stream = pyte.ByteStream(self.screen)
        flags = fcntl.fcntl(self.master_fd, fcntl.F_GETFL)
        fcntl.fcntl(self.master_fd, fcntl.F_SETFL, flags | os.O_NONBLOCK)
        self._pending = b""
        self.raw = b""     # 应用写往终端的全部原始字节：断言「协议契约」用（如 ESC[3J）

    def write(self, data):
        os.write(self.master_fd, data)

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
                self.raw += chunk
                self._handle_dsr()
                self.stream.feed(self._pending)
                self._pending = b""

    def _handle_dsr(self):
        needle = b"\x1b[6n"
        idx = self._pending.find(needle)
        while idx != -1:
            self.write(b"\x1b[1;1R")
            idx = self._pending.find(needle, idx + len(needle))

    def resize(self, rows, cols):
        """真 TIOCSWINSZ：内核会给子进程前台进程组发 SIGWINCH。"""
        fcntl.ioctl(self.master_fd, termios.TIOCSWINSZ, struct.pack("HHHH", rows, cols, 0, 0))
        self.rows, self.cols = rows, cols
        self.screen.resize(rows, cols)     # 注意：pyte 不做 reflow，见文件头说明

    def screen_text(self):
        return "\n".join(self.screen.display)

    def wait_stable(self, quiet=0.6, timeout=6.0):
        """泵到屏幕连续 quiet 秒不再变化为止（或超时）。

        固定 pump(2.0) 会把快照抓在重绘中途：曾经稳定复现「上边框还是旧宽度、下面三行已是新宽度」
        的假失败。等稳定既去掉了这种抖动，也保留了发现<b>真</b>缺陷的能力——真错的话屏幕会
        稳定地错着，照样断言得到。
        """
        deadline = time.time() + timeout
        last = None
        stable_since = None
        while time.time() < deadline:
            self.pump(0.2)
            now = self.screen_text()
            if now != last:
                last = now
                stable_since = time.time()
            elif stable_since is not None and time.time() - stable_since >= quiet:
                return True
        return False

    def wait_for_raw(self, needle, mark, timeout=4.0):
        """泵到原始字节流自 mark 起出现 needle 为止；条件轮询而非死等（settle≈300ms 有抖动）。"""
        deadline = time.time() + timeout
        while time.time() < deadline:
            if needle in self.raw[mark:]:
                return True
            self.pump(0.2)
        return needle in self.raw[mark:]

    def wait_for(self, substring, timeout=20):
        deadline = time.time() + timeout
        while time.time() < deadline:
            self.pump(0.2)
            if substring in self.screen_text():
                return True
            if self.proc.poll() is not None:
                die("process exited early (code=%s) while waiting for %r"
                    % (self.proc.returncode, substring), self.screen.display)
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


def box_tops(session):
    """屏上所有「无标题圆角框」的上边框：[(行号, 显示宽度), ...]。"""
    found = []
    for i, line in enumerate(session.screen.display):
        s = line.rstrip()
        if BOX_TOP.match(s):
            found.append((i, len(s)))
    return found


def banner_row(session):
    """欢迎横幅标题所在行号；不在屏上返回 None。"""
    for i, line in enumerate(session.screen.display):
        if WELCOME in line:
            return i
    return None


def assert_screen(session, label, cols, expected_above=None):
    """resize 后的通用断言：单框、新宽度、文本在、框上方历史的<b>内容一字不丢</b>。

    expected_above 是 resize 前「输入框上方所有行」的快照。停稳重放会按新宽度<b>重新折行</b>
    （拖宽把折开的行接回去、拖窄折出更多行）——行数和断点都合法地变，所以不能逐行比对；
    改比内容：快照每一行去掉空白后，必须是「当前框上方所有行拼接」的子串。
    这对两类缺陷同样敏感：sweep 越界吃掉一行 → 该行内容整个消失，必红；
    重放截断（用户实报「回复文字没显示全」的根因）→ 行尾内容消失，必红。
    """
    lines = list(session.screen.display)
    failures = []
    tops = box_tops(session)
    if len(tops) != 1:
        failures.append("期望屏上只有 1 个输入框，实际 %d 个：%r" % (len(tops), tops))
    elif tops[0][1] != cols:
        failures.append("输入框应按新宽度重画、宽 %d，实际 %d" % (cols, tops[0][1]))
    if TYPED not in session.screen_text():
        failures.append("输入的文本丢了：%r" % TYPED)
    if expected_above is not None and tops:
        box_row = tops[0][0]
        got = "".join("".join(l.split()) for l in lines[:box_row])
        for i, want in enumerate(expected_above):
            w = "".join(want.split())
            if w and w not in got:
                failures.append("第 %d 行的历史内容丢了：%r——sweep 越界吃掉或重放截断"
                                % (i, want.strip()))
                break
    if failures:
        print_screen(label, lines)
        die("%s: " % label + "; ".join(failures))
    print("%s OK: 单个输入框，宽 %d，文本与上方历史内容完好" % (label, cols))


def assert_cursor_on_text_row(session, label, expect_col, timeout=4.0):
    """稳态下硬件光标必须停在输入<b>文本行</b>（框顶+1），列跟在已输入文本后。

    pyte 的 cursor 就是硬件光标 = Terminal.app 的 IME 预编辑锚点；钉在框顶边框行
    拼音就浮到边框上（用户实报「打字错位」）。停稳重放完成后 parkCursorAtTop 收尾、
    下一帧才恢复停放，故轮询等待而非固定 sleep。
    """
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        tops = box_tops(session)
        if tops:
            want = tops[0][0] + 1
            cur = session.screen.cursor
            last = (cur.y, cur.x, want)
            if cur.y == want and cur.x == expect_col:
                print("%s OK: 硬件光标在文本行 (row=%d, col=%d)" % (label, cur.y, cur.x))
                return
        session.pump(0.2)
    die("%s: 硬件光标应停在输入文本行 (row=%s, col=%d)，实际 (row=%s, col=%s)——IME 拼音会错位"
        % (label, last[2] if last else "?", expect_col,
           last[0] if last else "?", last[1] if last else "?"),
        list(session.screen.display))


def build_classpath():
    if not os.path.isdir(CLASSES_DIR):
        die("target/classes missing at %s; run mvn compile first" % CLASSES_DIR)
    if not os.path.isfile(CP_FILE):
        die("target/cp.txt missing at %s; run "
            "mvn -pl springai-code-tui dependency:build-classpath "
            "-Dmdep.outputFile=target/cp.txt" % CP_FILE)
    with open(CP_FILE) as f:
        return CLASSES_DIR + os.pathsep + f.read().strip()


def main():
    classpath = build_classpath()
    tmpdir = tempfile.mkdtemp(prefix="codetui-resize-smoke-")
    env = dict(os.environ)
    env["TERM"] = "xterm-256color"
    env["DEEPSEEK_API_KEY"] = "sk-dummy-not-real"

    cmd = ["java", "-cp", classpath, MAIN_CLASS]
    print("Launching: %s\ncwd=%s" % (" ".join(cmd), tmpdir))

    session = PtySession(cmd, tmpdir, env, ROWS, NARROW_COLS)
    try:
        session.wait_for(WELCOME, timeout=30)
        session.pump(1.0)

        # 启动不许滚屏：横幅必须贴在屏幕顶部（上一版启动硬滚一屏、半屏空白，实机翻过车）。
        row = banner_row(session)
        if row is None or row > 2:
            die("启动后欢迎横幅应贴屏顶（前 3 行），实际在第 %r 行——启动被多余的滚屏/输出顶下去了"
                % row, list(session.screen.display))
        print("STARTUP OK: 横幅贴屏顶（第 %d 行）" % row)

        # 制造一条超过终端宽度的输出：发一条消息，dummy key 会打出带完整 URL 的 401 错误行
        # （~78 列 > 60 列终端）。打印链路必须把它折行下沉而不是定宽截断——「模型回复文字
        # 没显示全」的回归照妖镜；后续 resize 断言同时覆盖「重放按新宽度重折不丢内容」。
        session.write(b"hi\r")
        session.wait_for("completions", timeout=30)
        session.wait_stable()

        session.write(TYPED.encode())
        session.wait_stable()
        assert_screen(session, "BEFORE(%d cols)" % NARROW_COLS, NARROW_COLS)
        assert_cursor_on_text_row(session, "BEFORE CURSOR", 1 + len(TYPED))
        before_lines = list(session.screen.display)
        above = before_lines[:box_tops(session)[0][0]]   # 输入框上方的全部历史，逐行快照

        mark = len(session.raw)
        session.resize(ROWS, WIDE_COLS)
        session.wait_stable()
        assert_screen(session, "WIDEN(%d cols)" % WIDE_COLS, WIDE_COLS, expected_above=above)
        # 停稳重放必须连回滚缓冲一起抹（ESC[3J）：真 reflow 终端（Terminal.app 实测）每次拖拽
        # 都会把旧帧折行残骸推进 scrollback，ESC[J] 够不着——不抹掉，「往上翻」全是界面尸体。
        if not session.wait_for_raw(b"\x1b[3J", mark):
            die("WIDEN 停稳后未发出 ESC[3J（抹回滚缓冲）——scrollback 里会堆积旧帧残骸",
                list(session.screen.display))
        print("WIDEN SETTLE OK: 已发出 ESC[3J 抹回滚缓冲")

        mark = len(session.raw)
        session.resize(ROWS, FINAL_COLS)
        session.wait_stable()
        assert_screen(session, "NARROW(%d cols)" % FINAL_COLS, FINAL_COLS, expected_above=above)
        if not session.wait_for_raw(b"\x1b[3J", mark):
            die("NARROW 停稳后未发出 ESC[3J（抹回滚缓冲）——scrollback 里会堆积旧帧残骸",
                list(session.screen.display))
        print("NARROW SETTLE OK: 已发出 ESC[3J 抹回滚缓冲")
        # 停稳重放收尾后 parkCursorAtTop 必须复位：光标回文本行，否则接下来打字 IME 又错位
        assert_cursor_on_text_row(session, "AFTER-SETTLE CURSOR", 1 + len(TYPED))

        print_screen("BEFORE resize (%d cols)" % NARROW_COLS, before_lines)
        print_screen("FINAL (%d cols)" % FINAL_COLS, list(session.screen.display))

        session.write(b"/exit\r")
        session.pump(1.0)

        print("SMOKE PASS")
        return 0
    finally:
        session.close()


if __name__ == "__main__":
    sys.exit(main())

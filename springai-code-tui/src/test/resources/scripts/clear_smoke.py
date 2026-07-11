#!/usr/bin/env python3
"""PTY smoke test for the /clear command.

Launches the code-tui app on a real pseudo-terminal, drives /help then
/clear, and uses pyte to render the terminal screen so we can assert that
/clear actually clears the screen (no stale /help scrollback) and re-prints
the welcome banner.

Unit tests cannot reach this path because CodeTuiView's reflection-based
ScreenCleaner only activates when a real Terminal/PTY is present
(runner() == null in the test harness). This script is the only way to
verify the real clear-screen behavior end-to-end.

Usage:
    /usr/bin/python3 scripts/clear_smoke.py

Exit code 0 + "SMOKE PASS" on success, non-zero + "SMOKE FAIL: <reason>" on
failure. Always prints the BEFORE and AFTER screen snapshots for a human to
eyeball.
"""
import fcntl
import os
import select
import signal
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

ROWS, COLS = 40, 120

# 从源码位置 src/test/resources/scripts/ 上溯到模块根 springai-code-tui（5 层 dirname）：
# scripts → resources → test → src → springai-code-tui。（从 src/test/pty/ 迁来时深了一层，故 4→5。）
MODULE_ROOT = os.path.dirname(
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
)
CLASSES_DIR = os.path.join(MODULE_ROOT, "target", "classes")
CP_FILE = os.path.join(MODULE_ROOT, "target", "cp.txt")
MAIN_CLASS = "io.github.javaside.springai.codetui.CodeTuiApplication"

WELCOME_1 = "Spring AI Code TUI"
WELCOME_2 = "终端编码智能体"
WELCOME_3 = "目录"
HELP_CMDS = "可用命令："
HELP_KEYS = "快捷键："
HELP_CLEAR_LINE = "/clear   清空上下文，开新会话"


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


class PtySession:
    def __init__(self, cmd, cwd, env):
        self.master_fd, self.slave_fd = os.openpty()
        # Set window size before spawning so the child inherits it.
        winsize = struct.pack("HHHH", ROWS, COLS, 0, 0)
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
        # Parent doesn't need the slave end.
        os.close(self.slave_fd)
        self.slave_fd = None

        self.screen = pyte.Screen(COLS, ROWS)
        self.stream = pyte.ByteStream(self.screen)

        # Make master fd non-blocking for select-based reads.
        flags = fcntl.fcntl(self.master_fd, fcntl.F_GETFL)
        fcntl.fcntl(self.master_fd, fcntl.F_SETFL, flags | os.O_NONBLOCK)

        self._pending = b""

    def write(self, data):
        os.write(self.master_fd, data)

    def pump(self, duration):
        """Read available output for `duration` seconds, feeding pyte and
        auto-replying to DSR cursor-position queries (ESC[6n)."""
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
        # Detect ESC[6n cursor position request and reply with a fake
        # cursor report so JLine doesn't block waiting for it.
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


def main():
    classpath = build_classpath()
    tmpdir = tempfile.mkdtemp(prefix="codetui-smoke-")

    env = dict(os.environ)
    env["TERM"] = "xterm-256color"
    env["DEEPSEEK_API_KEY"] = "sk-dummy-not-real"
    # Make sure we don't accidentally inherit other provider keys that might
    # change startup behavior; keep it minimal but harmless if present.

    cmd = ["java", "-cp", classpath, MAIN_CLASS]

    print("Launching: %s" % " ".join(cmd))
    print("cwd=%s" % tmpdir)

    session = PtySession(cmd, tmpdir, env)
    try:
        session.wait_for(WELCOME_1, timeout=20)
        text = session.screen_text()
        if WELCOME_2 not in text or WELCOME_3 not in text:
            die(
                "startup welcome banner missing expected substrings (%r / %r)"
                % (WELCOME_2, WELCOME_3),
                session.screen.display,
            )
        print("Startup welcome banner OK.")

        session.write(b"/help\r")
        session.wait_for(HELP_CMDS, timeout=15)
        session.pump(0.5)

        before_text = session.screen_text()
        before_lines = list(session.screen.display)
        if HELP_CMDS not in before_text:
            die("BEFORE snapshot missing %r" % HELP_CMDS, before_lines)
        if HELP_KEYS not in before_text:
            die("BEFORE snapshot missing %r" % HELP_KEYS, before_lines)
        print("/help output rendered OK (BEFORE snapshot captured).")

        session.write(b"/clear\r")
        session.pump(1.5)
        # Extra pumping to let any deferred re-render settle.
        session.pump(1.0)

        after_text = session.screen_text()
        after_lines = list(session.screen.display)

        print_screen("BEFORE (/help output)", before_lines)
        print_screen("AFTER (/clear output)", after_lines)

        failures = []
        if HELP_CMDS in after_text:
            failures.append("AFTER still contains help marker %r (screen not cleared)" % HELP_CMDS)
        if HELP_KEYS in after_text:
            failures.append("AFTER still contains help marker %r (screen not cleared)" % HELP_KEYS)
        if WELCOME_1 not in after_text:
            failures.append("AFTER missing welcome banner %r" % WELCOME_1)
        if WELCOME_2 not in after_text:
            failures.append("AFTER missing welcome banner %r" % WELCOME_2)

        # Clean shutdown.
        session.write(b"/exit\r")
        session.pump(1.0)

        if failures:
            die("; ".join(failures))

        print("SMOKE PASS")
        return 0
    finally:
        session.close()


if __name__ == "__main__":
    sys.exit(main())

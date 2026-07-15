#!/usr/bin/env python3
"""PTY smoke test for readline-style edit shortcuts in the input box.

Drives the real app on a pseudo-terminal and verifies:
  - Ctrl+W deletes the previous word (screen shows shortened input)
  - Ctrl+U clears to line start (input box back to empty-cursor state)
  - Ctrl+A + Ctrl+K wipes the line from the start
  - Alt+B / typed text keep rendering correctly (cursor path sanity)

Unit tests already cover the word-boundary logic; this script proves the
control bytes actually arrive as CTRL/ALT-modified KeyEvents through the
real JLine backend + EventParser (Ctrl+A..Z arrive as raw bytes 1..26,
Alt+x as ESC-prefixed) and that rendering keeps up.

Usage:
    /usr/bin/python3 scripts/edit_shortcut_smoke.py
"""
import fcntl
import os
import select
import struct
import subprocess
import sys
import tempfile
import termios
import time

import site
sys.path.insert(0, site.getusersitepackages())
import pyte  # noqa: E402

ROWS, COLS = 40, 120

MODULE_ROOT = os.path.dirname(
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
)
CLASSES_DIR = os.path.join(MODULE_ROOT, "target", "classes")
CP_FILE = os.path.join(MODULE_ROOT, "target", "cp.txt")
MAIN_CLASS = "io.github.javaside.springai.codetui.CodeTuiApplication"

WELCOME_1 = "Spring AI Code TUI"

CTRL_A = b"\x01"
CTRL_E = b"\x05"
CTRL_K = b"\x0b"
CTRL_U = b"\x15"
CTRL_W = b"\x17"
ALT_B = b"\x1bb"


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
        winsize = struct.pack("HHHH", ROWS, COLS, 0, 0)
        fcntl.ioctl(self.slave_fd, termios.TIOCSWINSZ, winsize)

        self.proc = subprocess.Popen(
            cmd, stdin=self.slave_fd, stdout=self.slave_fd, stderr=self.slave_fd,
            cwd=cwd, env=env, start_new_session=True,
        )
        os.close(self.slave_fd)
        self.slave_fd = None

        self.screen = pyte.Screen(COLS, ROWS)
        self.stream = pyte.ByteStream(self.screen)

        flags = fcntl.fcntl(self.master_fd, fcntl.F_GETFL)
        fcntl.fcntl(self.master_fd, fcntl.F_SETFL, flags | os.O_NONBLOCK)
        self._pending = b""

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
                die("process exited early (code=%s) while waiting for %r"
                    % (self.proc.returncode, substring), self.screen.display)
        die("timed out waiting for %r" % substring, self.screen.display)

    def wait_gone(self, substring, timeout=10):
        deadline = time.time() + timeout
        while time.time() < deadline:
            self.pump(0.2)
            if substring not in self.screen_text():
                return True
        die("timed out waiting for %r to disappear" % substring, self.screen.display)

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

    cmd = ["java", "-cp", classpath, MAIN_CLASS]
    print("Launching: %s" % " ".join(cmd))

    session = PtySession(cmd, tmpdir, env)
    try:
        session.wait_for(WELCOME_1, timeout=20)
        print("Startup OK.")

        # 1) Ctrl+W deletes previous word
        session.write(b"alpha beta gamma")
        session.wait_for("alpha beta gamma")
        session.write(CTRL_W)
        session.wait_gone("gamma")
        if "alpha beta" not in session.screen_text():
            die("Ctrl+W wiped too much; expected 'alpha beta' to remain",
                session.screen.display)
        print("Ctrl+W OK (deleted 'gamma', kept 'alpha beta').")

        # 2) Ctrl+U clears to line start
        session.write(CTRL_U)
        session.wait_gone("alpha beta")
        print("Ctrl+U OK (line cleared).")

        # 3) Ctrl+A then Ctrl+K wipes the line from the start
        session.write(b"one two three")
        session.wait_for("one two three")
        session.write(CTRL_A)
        session.pump(0.4)
        session.write(CTRL_K)
        session.wait_gone("one two three")
        print("Ctrl+A + Ctrl+K OK (line wiped from start).")

        # 4) Alt+B jumps a word back, then typing inserts there:
        #    "foo bar" + Alt+B + "X" => "foo Xbar"
        session.write(b"foo bar")
        session.wait_for("foo bar")
        session.write(ALT_B)
        session.pump(0.4)
        session.write(b"X")
        session.wait_for("foo Xbar")
        print("Alt+B OK ('foo Xbar' rendered).")

        session.write(CTRL_U)
        session.pump(0.3)
        session.write(b"/exit\r")
        session.pump(1.0)

        print("SMOKE PASS")
        return 0
    finally:
        session.close()


if __name__ == "__main__":
    sys.exit(main())

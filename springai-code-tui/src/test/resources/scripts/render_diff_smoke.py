#!/usr/bin/env python3
"""PTY smoke test for silent unchanged frames and local inline patches."""
import importlib.util
import os
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("resize_smoke", os.path.join(HERE, "resize_smoke.py"))
resize_smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(resize_smoke)

ROWS = 30
COLS = 100
BOX_TOP_UTF8 = "╭".encode()
STATUS_HINT_UTF8 = "Enter 发送".encode()


def die(msg, session):
    resize_smoke.die(msg, list(session.screen.display))


def main():
    classpath = resize_smoke.build_classpath()
    tmpdir = tempfile.mkdtemp(prefix="codetui-render-diff-smoke-")
    env = dict(os.environ)
    env["TERM"] = "xterm-256color"
    env["DEEPSEEK_API_KEY"] = "sk-dummy-not-real"
    cmd = ["java", "-Dcodetui.syncOutput=never", "-cp", classpath, resize_smoke.MAIN_CLASS]
    session = resize_smoke.PtySession(cmd, tmpdir, env, ROWS, COLS)
    try:
        session.wait_for(resize_smoke.WELCOME, timeout=30)
        session.wait_stable(quiet=1.1)

        mark = len(session.raw)
        session.pump(0.5)
        idle = session.raw[mark:]
        if idle:
            die("静止 500ms 应零输出，实际 %d 字节：%r" % (len(idle), idle[:160]), session)
        print("IDLE OK: 500ms / ~15 ticks emitted zero bytes")

        for ch in "abc":
            mark = len(session.raw)
            session.write(ch.encode())
            session.pump(0.15)
            delta = session.raw[mark:]
            if b"\x1b[K" in delta:
                die("输入字符不应触发整行擦除", session)
            if BOX_TOP_UTF8 in delta:
                die("输入字符不应重写输入框边框", session)
            if STATUS_HINT_UTF8 in delta:
                die("输入字符不应重写状态栏全文", session)
        if "abc" not in session.screen_text():
            die("ASCII 输入未正确显示", session)
        print("ASCII OK: local patches without EL/border/status rewrite")

        mark = len(session.raw)
        session.write("中".encode())
        session.pump(0.2)
        delta = session.raw[mark:]
        if "中".encode() not in delta or b"\x1b[K" in delta:
            die("CJK 应完整局部提交且不擦整行：%r" % delta, session)
        if "abc中" not in session.screen_text():
            die("CJK 输入未正确显示", session)
        print("CJK OK: complete glyph patch")

        session.write(b"\x15/exit\r")
        deadline = time.time() + 5
        while time.time() < deadline and session.proc.poll() is None:
            session.pump(0.2)
        print("SMOKE PASS")
        return 0
    finally:
        session.close()


if __name__ == "__main__":
    sys.exit(main())

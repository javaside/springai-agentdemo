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
BOX_SIDE_UTF8 = "│".encode()
STATUS_HINT_UTF8 = "Enter 发送".encode()


def die(msg, session):
    resize_smoke.die(msg, list(session.screen.display))


def main():
    classpath = resize_smoke.build_classpath()
    tmpdir = tempfile.mkdtemp(prefix="codetui-render-diff-smoke-")
    env = dict(os.environ)
    env["TERM"] = "xterm-256color"
    env["DEEPSEEK_API_KEY"] = "sk-dummy-not-real"
    # 隔离 HOME：不加载 ~/.codetui/mcp.json（本地可能启用了 chrome-devtools 等真实 MCP），
    # 否则启动后 MCP 就绪回调会打一行 scrollback，破坏「静止 500ms 零输出」契约。
    isolated_home = tempfile.mkdtemp(prefix="codetui-render-diff-home-")
    env["HOME"] = isolated_home
    # hardwareCursor=always 复刻 Apple Terminal 的 IME 路径（硬件光标可见、预编辑锚在文本行），
    # 让「CJK 上屏后重申行尾竖线」这条修复在真实光标可见语义下被断言。
    cmd = ["java", "-Dcodetui.syncOutput=never", "-Dcodetui.hardwareCursor=always",
           "-Duser.home=" + isolated_home,
           "-cp", classpath, resize_smoke.MAIN_CLASS]
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

        # 光标带修复语义：任何触及光标行 ±1 的编辑（含 ASCII）都会整行重申输入框
        # 顶边框/文本行/底边框（拼音被取消时应用收不到事件，损坏只能靠下一次编辑修复），
        # 但绝不用 EL、绝不重写带外行（状态栏）。
        for ch in "abc":
            mark = len(session.raw)
            session.write(ch.encode())
            session.pump(0.15)
            delta = session.raw[mark:]
            if b"\x1b[K" in delta:
                die("输入字符不应触发整行擦除", session)
            if STATUS_HINT_UTF8 in delta:
                die("输入字符不应重写状态栏全文（带外行）", session)
            if BOX_SIDE_UTF8 not in delta:
                die("编辑必须重申光标带（含边框竖线）：%r" % delta, session)
        if "abc" not in session.screen_text():
            die("ASCII 输入未正确显示", session)
        print("ASCII OK: EL-free patches, cursor band reasserted, status untouched")

        mark = len(session.raw)
        session.write("中".encode())
        session.pump(0.2)
        delta = session.raw[mark:]
        if "中".encode() not in delta or b"\x1b[K" in delta:
            die("CJK 应完整局部提交且不擦整行：%r" % delta, session)
        if BOX_SIDE_UTF8 not in delta:
            die("CJK 提交必须重申行尾竖线，以恢复 IME 预编辑覆盖：%r" % delta, session)
        if "╮".encode() not in delta or "╯".encode() not in delta:
            die("CJK 提交必须重申光标行上下的边框圆角（右侧缺角案例）：%r" % delta, session)
        if "abc中" not in session.screen_text():
            die("CJK 输入未正确显示", session)
        input_rows = [line for line in session.screen.display if "abc中" in line]
        if len(input_rows) != 1:
            die("CJK 输入行应唯一，实际 %d 行" % len(input_rows), session)
        input_row = input_rows[0]
        if input_row[0] != "│" or input_row[-1] != "│":
            die("CJK 输入后左右竖线必须保留：%r" % input_row, session)
        print("CJK OK: complete glyph patch, band corners and borders reasserted")

        # 删空输入后边框与四角必须完好（空框缺角案例），且修复窗口耗尽后恢复静止零输出。
        session.write(b"\x15")
        session.pump(0.6)
        rows = list(session.screen.display)
        box_rows = [i for i, line in enumerate(rows) if line.lstrip().startswith("╭")]
        if not box_rows:
            die("删空后找不到输入框顶边框", session)
        top = max(box_rows)
        if not rows[top].rstrip().endswith("╮"):
            die("删空后顶边框右圆角缺失：%r" % rows[top], session)
        if not rows[top + 1].rstrip().endswith("│"):
            die("删空后文本行右竖线缺失：%r" % rows[top + 1], session)
        if not rows[top + 2].rstrip().endswith("╯"):
            die("删空后底边框右圆角缺失：%r" % rows[top + 2], session)
        mark = len(session.raw)
        session.pump(0.5)
        idle = session.raw[mark:]
        if idle:
            die("修复窗口耗尽后应恢复零输出，实际 %d 字节：%r" % (len(idle), idle[:160]), session)
        print("REPAIR-DRAIN OK: borders intact after clearing input, silence restored")

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

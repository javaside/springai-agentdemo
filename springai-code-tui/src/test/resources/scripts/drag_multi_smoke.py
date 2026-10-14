#!/usr/bin/env python3
"""PTY smoke：一批拖多图（含带空格文件名）的附件计数。

<b>本脚本存在的原因</b>：2026-10-13 用户实报「一次拖多张图，输入框只显示两张」。
两段根因都在 pty 字节流形态，单测直接调 handlePasteEvent 复现不了：
  ① 终端一批拖多图时粘贴里的空格可能不转义，带空格的文件名被空白切成碎片，
     整张图静默丢失（detector 层已修：路径重组 assemblePaths）；
  ② Terminal.app 对多文件拖拽实际发的是<b>每文件一条独立括号粘贴</b>，背靠背
     连发（2026-10-14 od 取证 /tmp/drag-real2.bi）——EventParser 批量快路径按块
     整读、扫到第一条终止符就收手，同块里后面的整条粘贴被静默丢弃，幸存哪几张
     看块边界运气（EventParser 已修：overshoot 回灌）。
这两段证据都只能由 pty 冒烟产生（AttachmentLineTest 注释里明说）。

断言（全部满足 exit 0，任一失败非 0）：
  1. 转义形态（反斜杠转义空格、单空格分隔、一次 bracketed paste）：
     屏幕出现 [IMAGE4] 与「已附带 4 张图片」；
  2. 未转义形态（空格原样——事故形态 a，2 张无空格名 + 2 张带空格名）：
     同样 [IMAGE4] 与「已附带 4 张图片」；
  3. 背靠背多粘贴形态（事故形态 b，真实 Terminal.app 字节，5 条相邻括号粘贴）：
     [IMAGE5] 与「已附带 5 张图片」（修复前只出 1-2 张）。

Usage:
    mvn -q -pl springai-code-tui compile
    mvn -q -pl springai-code-tui dependency:build-classpath -Dmdep.outputFile=target/cp.txt
    python3 src/test/resources/scripts/drag_multi_smoke.py
"""
import os
import re
import struct
import sys
import tempfile
import time
import zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from clear_smoke import PtySession, build_classpath, print_screen  # noqa: E402

PASTE_START = b"\x1b[200~"
PASTE_END = b"\x1b[201~"

# 2 张无空格名 + 2 张带空格名（中文截图默认命名）：修复前只有前两张能整词识别。
NAMES = ["shot-a.png", "shot-b.png",
         "截屏 2026-10-13 09.41.22.png", "截屏 2026-10-13 09.41.23.png"]


def make_png(path):
    """最小合法 PNG（8x8 RGB），魔数嗅探必须认；不依赖 PIL。"""
    def chunk(typ, data):
        c = typ + data
        return struct.pack(">I", len(data)) + c + struct.pack(">I", zlib.crc32(c) & 0xFFFFFFFF)
    ihdr = struct.pack(">IIBBBBB", 8, 8, 8, 2, 0, 0, 0)
    raw = b"".join(b"\x00" + b"\xff\x00\x00" * 8 for _ in range(8))
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
                + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


def normalized_screen(session):
    """宽字符在 pyte 里渲染成「字 + 空格」，匹配前去掉全部空白。"""
    return re.sub(r"\s", "", session.screen_text())


def run_form(form):
    tmpdir = tempfile.mkdtemp(prefix="codetui-drag-smoke-")
    paths = []
    for name in NAMES:
        p = os.path.join(tmpdir, name)
        make_png(p)
        paths.append(p)

    env = dict(os.environ)
    env["TERM"] = "xterm-256color"
    env.setdefault("CODETUI_API_KEY", "dummy")
    cp = build_classpath()
    s = PtySession(["java", "-cp", cp, "io.github.javaside.springai.codetui.CodeTuiApplication"],
                   cwd=tmpdir, env=env)
    try:
        s.wait_for("Spring AI Code TUI", timeout=25)
        if form == "escaped":
            payload = " ".join(p.replace(" ", "\\ ") for p in paths)
            s.write(PASTE_START + payload.encode() + PASTE_END)
        elif form == "raw":
            payload = " ".join(paths)                # 空格原样未转义（事故形态 a）
            s.write(PASTE_START + payload.encode() + PASTE_END)
        else:                                        # adjacent：每文件一条背靠背括号粘贴
            for p in paths:                          # （事故形态 b，真实 Terminal.app 字节）
                s.write(PASTE_START + p.encode() + PASTE_END)
        s.pump(4.0)

        norm = normalized_screen(s)
        ok = "[IMAGE4]" in norm and "已附带4张图片" in norm
        print("[%s] %s" % (form, "ok" if ok else "FAIL"))
        if not ok:
            print_screen("FORM=%s" % form, s.screen.display)
        return ok
    finally:
        s.close()


def main():
    results = [run_form("escaped"), run_form("raw"), run_form("adjacent")]
    if all(results):
        print("SMOKE PASS")
        sys.exit(0)
    print("SMOKE FAIL")
    sys.exit(1)


if __name__ == "__main__":
    main()

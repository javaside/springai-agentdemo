#!/usr/bin/env python3
"""PTY smoke test for terminal attention cues (tab title + BEL).

Claude Code-style attention: when a turn completes, or a modal (ask /
permission / plan approval) starts waiting for the user, the app must
write the tab/window title via OSC 0/2 and ring the terminal BEL -- so
the tab itself shows the user something is waiting, even when the window
is in the background. This script verifies, on a real PTY:

  1. DONE cue: after a full turn completes, the raw stream contains an
     OSC title containing "已完成" and at least one BEL (0x07).
  2. Esc suppression: cancelling a turn with Esc must NOT emit the DONE
     title/BEL (the user is obviously present -- they just pressed a key).
  3. Restore: after the user presses a key post-DONE, the default title
     is written back.

Unit tests cannot reach this path: TerminalAttention reflects into the
private TamboUI Backend, which only exists with a real terminal
(runner() != null). pyte tracks OSC titles (screen.title) and BELs
(Screen.bell hook), so we can assert on the raw byte stream and the
interpreted title.

The stub model speaks the DeepSeek SSE dialect (copied from
stream_box_smoke.py); no real key or network needed.

Run after a fresh package (the script runs target/classes):

    mvn -q -pl springai-tamboui-inline-patch -am install -DskipTests
    mvn -q -pl springai-code-tui -am package -DskipTests
    mvn -q -pl springai-code-tui dependency:build-classpath -Dmdep.outputFile=target/cp.txt
    /usr/bin/python3 src/test/resources/scripts/attention_smoke.py

Exit code 0 + "SMOKE PASS" on success, non-zero + "SMOKE FAIL: <reason>".
"""
import importlib.util
import json
import os
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("resize_smoke", os.path.join(HERE, "resize_smoke.py"))
rs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rs)

ROWS, COLS = 30, 80
MODEL_ID = "deepseek-chat"

# 原始字节流断言用的标记（TerminalAttention 写的文案）。
# v1.21 口径：tab 标题只承担提醒——平态是纯品牌串 "Code TUI"（无项目名，项目名常驻状态行行尾）。
# ⚠ 恢复断言必须整条精确匹配（b"0;" + DEFAULT_TITLE + ST 终止）：DEFAULT_TITLE 是 DONE 标题
# （✓ Code TUI 已完成）的子串，宽松 contains 会把 DONE 标题误认成恢复标题。
DONE_TITLE = "已完成".encode()
WAITING_TITLE = "等待你的输入".encode()
DEFAULT_TITLE = "Code TUI".encode()
RESTORED_OSC = b"\x1b]0;" + DEFAULT_TITLE + b"\x1b\\"      # 整条恢复写（OSC 0 + ST）
BEL = b"\x07"
OSC_DONE = b"\x1b]0;"            # 任意 OSC 0 写头的前缀（文案另判）
REPLY = "回合内容输出完毕。"


def _sse(payload):
    return ("data: " + json.dumps(payload, ensure_ascii=False) + "\n\n").encode()


def _chunk(delta, finish=None):
    body = {"id": "attention-1", "object": "chat.completion.chunk", "created": 1, "model": MODEL_ID,
            "choices": [{"index": 0, "delta": delta, "finish_reason": finish}]}
    return body


class StubModel(BaseHTTPRequestHandler):
    daemon_threads = True

    def log_message(self, fmt, *args):
        pass

    def do_POST(self):
        self.rfile.read(int(self.headers.get("Content-Length", 0)))
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        try:
            self._write(_sse(_chunk({"role": "assistant", "content": ""})))
            self._write(_sse(_chunk({"content": REPLY})))
            self._write(_sse(_chunk({}, finish="stop")))
            self._write(b"data: [DONE]\n\n")
        except (BrokenPipeError, ConnectionResetError):
            return

    def _write(self, data):
        self.wfile.write(data)
        self.wfile.flush()


def start_stub():
    srv = ThreadingHTTPServer(("127.0.0.1", 0), StubModel)
    srv.daemon_threads = True
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv, "http://127.0.0.1:%d" % srv.server_address[1]


def die(msg, raw=b""):
    print("SMOKE FAIL: %s" % msg)
    if raw:
        tail = raw[-3000:]
        print("---- raw tail (repr, last 3000B) ----")
        print(repr(tail))
    sys.exit(1)


def osc_titles(raw):
    """从原始字节流里解出全部 OSC 0/2 标题（BEL 或 ST 终止）。"""
    titles = []
    i = 0
    while True:
        i = raw.find(b"\x1b]", i)
        if i < 0:
            break
        body = raw[i + 2:]
        end_bel = body.find(BEL)
        end_st = body.find(b"\x1b\\")
        if end_bel < 0 and end_st < 0:
            break
        if end_bel < 0 or (0 <= end_st < end_bel):
            text, nxt = body[:end_st], i + 2 + end_st + 2
        else:
            text, nxt = body[:end_bel], i + 2 + end_bel + 1
        titles.append(text)
        i = nxt
    return titles


def main():
    classpath = rs.build_classpath()
    srv, base_url = start_stub()
    tmpdir = tempfile.mkdtemp(prefix="codetui-attention-smoke-")
    home = os.path.join(tmpdir, "home")
    os.makedirs(home, exist_ok=True)

    env = dict(os.environ)
    env["TERM"] = "xterm-256color"
    env["DEEPSEEK_API_KEY"] = "sk-dummy-not-real"
    env["DEEPSEEK_BASE_URL"] = base_url
    env["DEEPSEEK_MODELS"] = MODEL_ID
    for key in ("ZHIPU_API_KEY", "DASHSCOPE_API_KEY", "ANTHROPIC_API_KEY", "OPENAI_API_KEY",
                "OPENCODE_GO_API_KEY"):
        env.pop(key, None)

    cmd = ["java", "-Duser.home=" + home, "-cp", classpath, rs.MAIN_CLASS]
    print("Launching: %s" % " ".join(cmd))
    session = rs.PtySession(cmd, tmpdir, env, ROWS, COLS)
    session.screen.bell = lambda *a: None   # pyte 的 bell 是 stub，别让它打印
    try:
        session.wait_for(rs.WELCOME, timeout=40)
        session.wait_stable(quiet=0.8)

        # ── 场景 1：Esc 取消（先做，别让 DONE 标题挂在后面场景的断言窗口里）──
        # 时序：发消息 → 回显出现（submit 已同步置 THINKING）→ 立即 Esc。
        # 注意：从本脚本的写入到子进程读到按键隔着 pty 队列，而桩模型瞬时完成——
        # 赶不上取消的真回合。为让回合「至少活到 Esc 被读到」，先等回显、pump 一小段
        # 再发 Esc；若仍错过（DONE 已写、随后又被恢复默认标题），退而断言：
        # 取消后标题恢复为默认（用户在场，不该留着「已完成」挂在 tab 上）。
        mark1 = len(session.raw)
        session.write("先不打，取消我\r".encode())
        session.wait_for("先不打，取消我", timeout=10)   # 回显 = 回合已起
        session.pump(0.6)
        session.write(b"\x1b")                     # Esc 取消
        session.pump(1.5)
        seg1 = session.raw[mark1:]
        titles1 = osc_titles(seg1)
        done_titles = [t for t in titles1 if DONE_TITLE in t]
        if done_titles:
            # 取消没赶上（回合先完成了）：那 DONE 之后必须已恢复默认标题（Esc 在场清掉提示）。
            # 整条 OSC 精确匹配（RESTORED_OSC）：contains 会撞上 DONE 标题里的 "Code TUI" 子串。
            restored1 = [t for t in titles1 if t == b"0;" + DEFAULT_TITLE]
            if not restored1:
                die("DONE 后用户按 Esc 却未恢复默认标题；本段标题: %r" % titles1, seg1)
            # 且默认标题的写入必须在 DONE 之后（顺序：DONE → 恢复）
            if seg1.find(RESTORED_OSC) < seg1.find(DONE_TITLE):
                die("默认标题出现在 DONE 之前（顺序异常）", seg1)
            print("Esc arrived after completion: DONE title was written then restored (acceptable).")
        else:
            print("Esc suppression OK (no DONE title on cancel).")

        # ── 场景 2：完整回合 → DONE 标题 + BEL ──
        mark = len(session.raw)
        session.write("你好\r".encode())
        deadline = time.time() + 15
        while time.time() < deadline:
            session.pump(0.2)
            if REPLY.encode() in session.raw or REPLY in session.screen_text():
                break
        session.pump(2.0)   # 让 drain 的边沿检测拍上（33ms 一拍，2s 足够）

        seg = session.raw[mark:]
        titles = osc_titles(seg)
        done_titles = [t for t in titles if DONE_TITLE in t]
        if not done_titles:
            die("回合完成后没有 OSC 标题含「已完成」；实际标题: %r" % titles, seg)
        if BEL not in seg:
            die("回合完成后没有发出 BEL", seg)
        if session.screen.title is None or DONE_TITLE.decode() not in session.screen.title:
            die("pyte 解析的最终标题不含「已完成」: %r" % session.screen.title, seg)
        print("DONE cue OK (title=%r, BEL present)." % session.screen.title)

        # ── 场景 3：用户按键 → 恢复默认标题 ──
        mark2 = len(session.raw)
        session.write(b"a")                       # 任意按键
        session.pump(1.0)
        seg2 = session.raw[mark2:]
        restored = [t for t in osc_titles(seg2) if t == b"0;" + DEFAULT_TITLE]
        if not restored:
            die("用户按键后没有恢复默认标题；本段标题: %r" % osc_titles(seg2), seg2)
        print("Restore OK (default title written back).")

        session.write(b"/exit\r")
        session.pump(1.0)
        print("SMOKE PASS")
        return 0
    finally:
        session.close()


if __name__ == "__main__":
    sys.exit(main())

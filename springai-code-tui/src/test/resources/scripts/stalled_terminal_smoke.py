#!/usr/bin/env python3
"""Stalled-reader smoke: output flood + 3s read-side stall + typing during stall.

Root-cause scenario of the async pty writer fix (commit 497c4c65): while the
terminal read side stalls (IME composition / render backlog in real terminals),
the kernel pty buffer (~1-2 KiB on macOS) fills and the pty-writer thread
blocks inside write(2). The render/event loop must keep processing keys.

The main assertion is ORDER-based (robust on a local full-speed pty):
  async-write world  -> complete key echo first becomes visible at a raw
     offset AFTER the last output line's offset (echo frames queue into the
     FIFO writer queue behind all output batches).
  synchronous-write world (hypothetical) -> the event loop itself blocks in
     write(2); after recovery queued key events interleave with the remaining
     output batches, so the complete echo would appear BEFORE the last line.

Mutation switch CODETUI_STALLED_MUTATE_SYNC_WRITE=1 flips the order
assertion to the direction a synchronous-write world would satisfy. The smoke
must exit non-zero with the switch on: the observable discriminates both ways.

NOT claimed here: writer-queue saturation / deferred batches (needs >1 MiB of
queued chars; 2000 lines ≈ 130K chars). That behavior is pinned by unit tests
(InlineTuiRunnerEventDrivenTest#keyEventsProcessedWhilePtyWriteIsStuck and
InlineDisplayAsyncWriterTest).
"""
import importlib.util
import json
import os
import re
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("resize_smoke", os.path.join(HERE, "resize_smoke.py"))
rs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rs)

ROWS, COLS = 45, 100
MODEL_ID = "deepseek-chat"
LINE_COUNT = 2000
# 每行内容 ~46 chars（UTF-8 ~122B），批 payload 含 ANSI ~60-65 char/行：总内容 ~92K char、
# 批 payload ~13 万 char——远超内核 pty 缓冲（~1-2KiB，首批即写满、writer 卡死），
# 不到 writer 软预算 1MiB（饱和场景归单测，见 docstring）。
LINE_TEMPLATE = "停摆-%05d " + "读端停摆期间按键必须存活" * 3
LINE_PATTERN = re.compile(rb"\xe5\x81\x9c\xe6\x91\x86-(\d{5})")   # "停摆-NNNNN" 的 UTF-8

STALL_SECONDS = 3.0
# 按键发在停摆的「前 2.0s」：输出（2000 行 ≈ 7 个 writer 批）在停摆开始后 ~1s 内
# 全部入队完毕，最后一键（t≈2.0s）的回显帧必然排在所有输出批之后——顺序断言只看
# 「完整回显」（含最后一键），早于输出入队完毕的早期按键不影响判据。
KEY_WINDOW_SECONDS = 2.0
KEY_INTERVAL = 0.15          # 2.0s / 0.15s ≈ 13-14 键
ECHO_LIVENESS_SECONDS = 60.0  # 宽松活性兜底（顺序断言才是主判据）
QUIET_SECONDS = 2.0
TOTAL_TIMEOUT_SECONDS = 180.0

MUTATE = os.environ.get("CODETUI_STALLED_MUTATE_SYNC_WRITE") == "1"


def _sse(payload):
    return ("data: " + json.dumps(payload) + "\n\n").encode()


def _chunk(delta, finish=None):
    return {
        "id": "stalled-smoke-1", "object": "chat.completion.chunk", "created": 1,
        "model": MODEL_ID,
        "choices": [{"index": 0, "delta": delta, "finish_reason": finish}],
    }


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
            self.wfile.write(_sse(_chunk({"role": "assistant", "content": ""})))
            self.wfile.flush()
            for first in range(1, LINE_COUNT + 1, 25):
                last = min(first + 25, LINE_COUNT + 1)
                text = "".join((LINE_TEMPLATE % i) + "\n" for i in range(first, last))
                self.wfile.write(_sse(_chunk({"content": text})))
                self.wfile.flush()
                time.sleep(0.005)
            self.wfile.write(_sse(_chunk({}, finish="stop")))
            self.wfile.write(b"data: [DONE]\n\n")
        except (BrokenPipeError, ConnectionResetError):
            return


def start_stub():
    srv = ThreadingHTTPServer(("127.0.0.1", 0), StubModel)
    srv.daemon_threads = True
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv, "http://127.0.0.1:%d" % srv.server_address[1]


def main():
    deadline = time.monotonic() + TOTAL_TIMEOUT_SECONDS
    classpath = rs.build_classpath()
    srv, base_url = start_stub()
    tmpdir = tempfile.mkdtemp(prefix="codetui-stalled-smoke-")
    home = os.path.join(tmpdir, "home")
    os.makedirs(home)
    env = dict(os.environ)
    env.update({
        "TERM": "xterm-256color",
        "DEEPSEEK_API_KEY": "sk-dummy-not-real",
        "DEEPSEEK_BASE_URL": base_url,
        "DEEPSEEK_MODELS": MODEL_ID,
    })
    for key in ("ZHIPU_API_KEY", "DASHSCOPE_API_KEY", "ANTHROPIC_API_KEY", "OPENAI_API_KEY",
                "OPENCODE_GO_API_KEY", "BOCHA_API_KEY", "BRAVE_API_KEY"):
        env.pop(key, None)

    print("Launching (%dx%d, stall %.1fs, mutate=%s): java -cp … %s" %
          (ROWS, COLS, STALL_SECONDS, MUTATE, rs.MAIN_CLASS))
    session = rs.PtySession(["java", "-Duser.home=" + home,
                             "-Dcodetui.hardwareCursor=always",
                             "-cp", classpath, rs.MAIN_CLASS],
                            tmpdir, env, ROWS, COLS)
    try:
        session.wait_for(rs.WELCOME, timeout=min(40, max(1, deadline - time.monotonic())))
        session.wait_stable(quiet=0.6, timeout=8)

        # ── 幕一：启动输出高峰，等首批行开始落屏 ──────────────────────
        session.write(b"print stalled lines\r")
        output_mark = len(session.raw)
        stall_started = None
        while time.monotonic() < deadline:
            session.pump(0.050)
            if LINE_PATTERN.search(session.raw[output_mark:]):
                stall_started = time.monotonic()
                break
            if session.proc.poll() is not None:
                rs.die("process exited before output started", list(session.screen.display))
        if stall_started is None:
            rs.die("model output never started", list(session.screen.display))

        # ── 幕二+三：停 pump（读端停摆——内核缓冲写满、pty-writer 卡在 write(2)），
        #    停摆前 2.0s 内持续打字。写 master 独立于读方向，立即返回。───────────
        stall_end = stall_started + STALL_SECONDS
        key_window_end = stall_started + KEY_WINDOW_SECONDS
        keys_sent = []
        next_key = time.monotonic()
        while time.monotonic() < stall_end:
            if time.monotonic() < key_window_end and time.monotonic() >= next_key:
                ch = chr(ord("a") + len(keys_sent) % 26)
                session.write(ch.encode())
                keys_sent.append(ch)
                next_key = time.monotonic() + KEY_INTERVAL
            time.sleep(0.020)
        if not keys_sent:
            rs.die("停摆窗口内必须至少发出一个键（KEY_WINDOW/KEY_INTERVAL 配置错误）",
                   list(session.screen.display))
        expected_echo = "".join(keys_sent)
        print("Stall over: %d keys typed during %.1fs read-side stall" %
              (len(keys_sent), STALL_SECONDS))

        # ── 幕四（判据一：顺序）：恢复 pump，双探测——
        #    echo_offset = 完整回显首次在屏幕可见那一刻的 len(raw)；
        #    last_output_offset = 末行字节在 raw 中首次出现的偏移。────────────
        echo_offset = None
        last_output_offset = None
        echo_liveness_deadline = stall_end + ECHO_LIVENESS_SECONDS
        while time.monotonic() < deadline:
            session.pump(0.050)
            if last_output_offset is None:
                # 末行标记 = "停摆-02000" 短字节序列（%05d 格式化）：比全行匹配稳健、
                # 与完整性扫描同源，行内插 ANSI/折行均不影响。
                idx = session.raw.find(("停摆-%05d" % LINE_COUNT).encode())
                if idx >= 0:
                    last_output_offset = idx
            if echo_offset is None:
                screen = "\n".join(session.screen.display)
                if expected_echo in screen.replace(" ", ""):
                    echo_offset = len(session.raw)
            if echo_offset is not None and last_output_offset is not None:
                break
            if echo_offset is None and time.monotonic() > echo_liveness_deadline:
                rs.die("complete key echo (%r) not visible %.0fs after stall end" %
                       (expected_echo, ECHO_LIVENESS_SECONDS), list(session.screen.display))
            if session.proc.poll() is not None:
                rs.die("process exited during recovery", list(session.screen.display))
        if echo_offset is None or last_output_offset is None:
            rs.die("markers incomplete: echo_offset=%r last_output_offset=%r" %
                   (echo_offset, last_output_offset), list(session.screen.display))
        print("echo_offset=%d, last_output_offset=%d" % (echo_offset, last_output_offset))

        async_order = echo_offset > last_output_offset
        if MUTATE:
            # 证红：断言翻转方向（同步写世界满足的方向）。异步世界必须红。
            if async_order:
                rs.die("MUTATION RED: async world satisfies echo_offset > last_output_offset; "
                       "flipped assertion must fail — the smoke lost its teeth")
        else:
            if not async_order:
                rs.die("order assertion failed: complete echo (raw offset %d) must appear "
                       "AFTER last output line (offset %d) — async writer FIFO guarantee. "
                       "If output queueing exceeded the %.1fs key window on a slow machine, "
                       "raise STALL_SECONDS/KEY_WINDOW_SECONDS margin." %
                       (echo_offset, last_output_offset, KEY_WINDOW_SECONDS),
                       list(session.screen.display))

        # ── 幕四（判据二：完整性）：2000 行一行不少（增量游标扫描，防全量重扫）。
        found = set()
        scan_from = output_mark
        while time.monotonic() < deadline and len(found) < LINE_COUNT:
            session.pump(0.050)
            margin_start = max(output_mark, scan_from - 64)   # 跨 chunk 边界保护
            for m in LINE_PATTERN.finditer(session.raw[margin_start:]):
                found.add(int(m.group(1)))
            scan_from = len(session.raw)
            if session.proc.poll() is not None:
                rs.die("process exited before output completed", list(session.screen.display))
        if len(found) < LINE_COUNT:
            missing = [i for i in range(1, LINE_COUNT + 1) if i not in found]
            rs.die("output incomplete after recovery: %d/%d, missing head=%r" %
                   (len(found), LINE_COUNT, missing[:10]), list(session.screen.display))
        print("Output complete: %d/%d lines, no loss" % (len(found), LINE_COUNT))

        # ── 幕四（判据三：静止）：排空后零字节 ─────────────────────────
        last_len = len(session.raw)
        quiet_since = time.monotonic()
        while time.monotonic() < deadline:
            session.pump(0.050)
            if len(session.raw) != last_len:
                last_len = len(session.raw)
                quiet_since = time.monotonic()
            elif time.monotonic() - quiet_since >= QUIET_SECONDS:
                print("Terminal byte-quiet for %.1fs after drain" % QUIET_SECONDS)
                print("SMOKE PASS")
                return
            if session.proc.poll() is not None:
                rs.die("process exited while waiting for quiescence", list(session.screen.display))
        rs.die("terminal never became byte-quiet", list(session.screen.display))
    finally:
        try:
            session.close()
        except Exception:
            pass
        srv.shutdown()


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""流式输出期间输入框的 PTY 实机冒烟：<b>不许闪出第二条边框，也不许吃掉正文</b>。

用户实报（2026-08-16）三条，根因不同、都只有实机看得见：

  1. <b>「输入框上下各多一根线，一闪又没，反复出现」</b>。流式预览行（`printer.preview`）随
     token 不停出现/消失，live 区在 4/5 行间反复增减。旧实现在 live 区<b>底部</b>加减行，
     输入框相对终端整体挪了一行，于是逐行差分把每一行都判成变化、逐行重画——重画到第一行时
     屏上同时有新旧两条<b>顶</b>边框，到第三行时两条<b>底</b>边框。Apple Terminal 不支持
     同步输出（DECSET 2026，见 SynchronizedOutput.knownTerminal），这些中间态会真画到屏上。
     修复：{@code InlineDisplay#resizeDisplay} 改在 live 区<b>顶部</b>插/删行（IL/DL），
     终端一次原子上/下移，存活行一个字节都不重发。

  2. <b>「输入框和状态栏重叠、排版全乱、正文被吃掉」</b>。批末恢复 live 区时若把行间的 LF
     换成 CUD（`ESC[1B`），底行不再滚屏：状态行盖在输入框底边框上，此后 live 区记账整体
     偏移一行，后续帧把输入框画进 scrollback、边打边吃已输出的正文。

  3. <b>「次要文字在深色窗口里看不见」</b>。ANSI 亮黑（SGR 90）的实际取值由终端 profile 决定，
     深色配色下常与背景同色。单测（ThemeContrastTest）只证调色板的取值，这里证颜色确实按
     256 色灰阶发到了终端。

<b>为什么单测不够</b>：前两条是「终端坐标系里的事故」——离屏 Buffer 的单测两边共用同一套排版，
`InlineDisplayDiffTest` 也只能断言字节序列的形状；「屏幕上到底有几条边框」「正文有没有被盖掉」
必须由真 VT 解释器（pyte）回放才看得见。第三条则是「样式有没有真发出去」：调色板改对了，
渲染链路上任何一处把它丢掉都照样是黑的。

<b>怎么看见「一闪而过」的中间态</b>：整帧采样看不到——它只存在于一帧<b>内部</b>。本脚本用
`-Dcodetui.syncOutput=always` 把每次 submit 用 DECSET 2026 括起来，再把原始字节流按
<b>每个游标动作</b>切片重放，逐刀检查屏上满宽圆角框的条数。中间态多出一条 = 用户看到的那一闪。

桩模型说 DeepSeek SSE 方言（照抄 background_smoke.py 的办法），<b>按片</b>吐字、多数分片不含
换行，以复现预览行抖动。不需要真实 key、不需要网络。

运行前<b>必须重新 package</b>（跑的是 target/classes，patch 模块还需 install），否则跑的是旧字节码：

    mvn -q -pl springai-tamboui-inline-patch install -DskipTests
    mvn -q -pl springai-code-tui package -DskipTests
    mvn -q -pl springai-code-tui dependency:build-classpath -Dmdep.outputFile=target/cp.txt
    /usr/bin/python3 src/test/resources/scripts/stream_box_smoke.py

<b>变异实测（2026-08-16）</b>：
  * 把 resizeDisplay 的顶部 IL/DL 去掉（回到底部增减）→ `中间态双边框` 断言红，报 50 处；
  * 把 appendLiveArea 行间的 LF 换回 CUD → `正文完整` 与 `静态单框` 断言红（正文丢行、
    状态行与底边框重叠）；
  * 把 Theme.DIM 改回 Color.DARK_GRAY → `无亮黑` 断言红，报 15 处，而 `无重影` 仍绿。
  三条互不遮蔽，各由不同断言判红。
"""
import importlib.util
import json
import os
import re
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("resize_smoke", os.path.join(HERE, "resize_smoke.py"))
rs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rs)

import pyte  # noqa: E402  （resize_smoke 已把 user site-packages 放进 sys.path）

ROWS, COLS = 30, 80
MODEL_ID = "deepseek-chat"
LINES = 12                      # 正文行数：足够把 scrollback 推到屏幕底部、逼出滚屏路径
CHUNK = 6                       # 每个 SSE 分片的字符数（不含换行 → 预览行反复出现/消失）
BODY = "第 %02d 行流式正文，用来把 scrollback 推满整屏并制造预览行抖动。"

BOX_TOP = re.compile(r"^╭─+╮$")
BOX_BOTTOM = re.compile(r"^╰─+╯$")
BEGIN, END = b"\x1b[?2026h", b"\x1b[?2026l"
# 一个 token = 一条 ESC 序列，或一段普通字符
TOKEN = re.compile(rb"\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)|\x1b\[[0-9;?]*[a-zA-Z]|\x1b[a-zA-Z=>]|[^\x1b]+")
CHECKPOINT = re.compile(rb"^(?:\r|\n|\x1b\[[0-9]*[ABCDLM])$")
SGR = re.compile(rb"\x1b\[([0-9;]*)m")

USAGE = {
    "prompt_tokens": 12000, "completion_tokens": 300, "total_tokens": 12300,
    "prompt_tokens_details": {"cached_tokens": 9600},
}


# ── 桩模型 ────────────────────────────────────────────────────────────────
def _sse(payload):
    return ("data: " + json.dumps(payload, ensure_ascii=False) + "\n\n").encode()


def _chunk(delta, finish=None, usage=None):
    body = {"id": "stream-box-1", "object": "chat.completion.chunk", "created": 1, "model": MODEL_ID,
            "choices": [{"index": 0, "delta": delta, "finish_reason": finish}]}
    if usage is not None:
        body["usage"] = usage
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
            for i in range(LINES):
                body = BODY % i
                for k in range(0, len(body), CHUNK):
                    self._write(_sse(_chunk({"content": body[k:k + CHUNK]})))
                    time.sleep(0.03)
                self._write(_sse(_chunk({"content": "\n"})))
                time.sleep(0.03)
            self._write(_sse(_chunk({}, finish="stop", usage=USAGE)))
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


# ── 断言 ──────────────────────────────────────────────────────────────────
def ansi_black_hits(raw):
    """原始字节流里把<b>前景</b>设成 ANSI 黑/亮黑的次数。

    不能拿 ESC[90m 做子串匹配：AnsiCellWriter 发的是<b>合并形态</b> ESC[0;90m，子串永远命中
    不到（实测 v1.14.0 流式 422KB 中 ESC[90m 出现 0 次、ESC[0;90m 出现 276 次），照那样写就是
    一条永远绿的假断言。必须按分号切参数，并跳过 38;5;N 与 38;2;r;g;b 的内层数字，否则
    indexed(30) 会被误判成 ANSI 黑。
    """
    hits = 0
    for m in SGR.finditer(raw):
        params = m.group(1).split(b";")
        i = 0
        while i < len(params):
            token = params[i]
            if token in (b"38", b"48") and i + 1 < len(params):
                if params[i + 1] == b"5":
                    i += 3
                    continue
                if params[i + 1] == b"2":
                    i += 5
                    continue
            if token in (b"30", b"90"):
                hits += 1
            i += 1
    return hits


def box_counts(screen):
    """屏上满宽圆角框的上/下边框条数。欢迎横幅比终端窄，不会误命中。"""
    try:
        lines = screen.display
    except IndexError:            # pyte 对个别空 cell 会抛，跳过该次检查
        return None
    top = bottom = 0
    for line in lines:
        s = line.rstrip()
        if len(s) != COLS:
            continue
        if BOX_TOP.match(s):
            top += 1
        if BOX_BOTTOM.match(s):
            bottom += 1
    return top, bottom


def audit_intermediate_frames(raw):
    """逐游标动作重放，返回 (中间态双边框次数, 最严重的一帧文本)。"""
    screen = pyte.Screen(COLS, ROWS)
    stream = pyte.ByteStream(screen)
    ghosts = 0
    worst = None
    for m in TOKEN.finditer(raw):
        tok = m.group(0)
        stream.feed(tok)
        if not (CHECKPOINT.match(tok) or b"\n" in tok or b"\r" in tok):
            continue
        counts = box_counts(screen)
        if counts is None:
            continue
        top, bottom = counts
        if top > 1 or bottom > 1:
            ghosts += 1
            if worst is None or top + bottom > worst[0]:
                try:
                    worst = (top + bottom, list(screen.display))
                except IndexError:
                    pass
    return ghosts, (worst[1] if worst else None)


def main():
    classpath = rs.build_classpath()
    srv, base_url = start_stub()
    tmpdir = tempfile.mkdtemp(prefix="codetui-stream-box-smoke-")
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

    # syncOutput=always 只为把每次 submit 括起来便于切帧；hardwareCursor=always 复刻 Apple Terminal。
    cmd = ["java", "-Duser.home=" + home, "-Dcodetui.syncOutput=always",
           "-Dcodetui.hardwareCursor=always", "-cp", classpath, rs.MAIN_CLASS]
    print("Launching (%dx%d): %s" % (ROWS, COLS, " ".join(cmd)))
    session = rs.PtySession(cmd, tmpdir, env, ROWS, COLS)
    try:
        session.wait_for(rs.WELCOME, timeout=40)
        session.wait_stable(quiet=0.8)

        mark = len(session.raw)
        session.write("请输出内容\r".encode())
        deadline = time.time() + LINES * 0.6 + 8
        while time.time() < deadline:
            session.pump(0.2)
            if (BODY % (LINES - 1)) in session.screen_text():
                break
        # 回合完成后仍可能有按需 IME/光标带补帧；以画面连续静止为完成信号，
        # 不再假定固定数量的 100ms tick。
        if not session.wait_stable(quiet=1.0, timeout=10):
            rs.die("回合结束后的按需 IME 补帧未完成", list(session.screen.display))
        raw = session.raw[mark:]

        ghosts, worst = audit_intermediate_frames(raw)
        if ghosts:
            if worst:
                rs.print_screen("最严重的中间态", worst)
            rs.die("流式期间闪出第二条输入框边框 %d 次——用户实报「输入框上下多两根线」；"
                   "live 区增减行必须走顶部 IL/DL，不能逐行重画" % ghosts, list(session.screen.display))
        print("无重影 OK: 逐游标动作重放，全程只有一条输入框边框")

        black = ansi_black_hits(raw)
        if black:
            rs.die("流式期间 %d 次把前景设成 ANSI 黑/亮黑——ANSI 0–15 由终端 profile 决定，"
                   "深色窗口下常与背景同色；次要文字必须走 256 色灰阶（见 Theme 的三档）" % black,
                   list(session.screen.display))
        print("无亮黑 OK: 流式期间未把前景设成 ANSI 黑/亮黑")

        text = session.screen_text()
        missing = [i for i in range(LINES) if (BODY % i) not in text]
        if missing:
            rs.die("正文有 %d 行没显示（缺 %r）——live 区错位会把输入框画到 scrollback 上、吃掉正文"
                   % (len(missing), missing[:4]), list(session.screen.display))
        print("正文完整 OK: %d 行流式正文全部在屏" % LINES)

        counts = box_counts(session.screen)
        if counts != (1, 1):
            rs.die("回合结束后应恰好一条上边框 + 一条下边框，实际 %r——状态行与底边框重叠的信号"
                   % (counts,), list(session.screen.display))
        print("静态单框 OK: 回合结束后输入框完整")

        session.write(b"/exit\r")
        session.pump(1.0)
        print("SMOKE PASS")
        return 0
    finally:
        session.close()
        srv.shutdown()


if __name__ == "__main__":
    sys.exit(main())

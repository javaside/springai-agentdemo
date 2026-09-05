#!/usr/bin/env python3
"""markdown 表格渲染的 PTY 实机冒烟：<b>回合以表格结尾时，表格必须自己出现并且列对齐</b>。

用户实报（2026-09-04）：「表格显示就乱了」。根因不是宽度计算有 bug，而是渲染器<b>没有</b>
表格分支——模型按<b>字符数</b>补齐格子、终端按<b>显示宽度</b>排版（CJK 占 2 列），照抄必错位。
修复是攒够整块再按显示宽度算列宽重排（表头加粗 + 一条 `─` + 空格对齐，不画竖线）。

<b>为什么单测不够</b>，三条都只有实机看得见：

  1. <b>「表格出不来」</b>。scrollback 只能追加，所以表格块只能先缓冲、块结束才输出，而
     「什么时候算块结束」靠五条 flush 触发点。回合<b>以表格结尾</b>是这功能最主要的场景，
     也恰恰是最容易漏的一条：那一批 queue/pending/streaming 全空、IDLE 且无后台任务时
     `animationDemandActive()` 也 false，<b>不再排下一批</b>——表格要么靠 ctxUsage 的 500ms
     防抖偶然救回（晚半秒），要么一直不出、直到用户按键。所以本脚本提交后<b>不再按任何键</b>，
     只等画面静止，然后断言表格已经在屏上。
     ⚠ `-c` 回放是最安全的一条路，但它<b>验不出</b>这条：`replayHistory` 末尾固定补一条 INFO
     分割线，天然把缓冲顶出来。必须造实时回合。

  2. <b>「个别行裂开」</b>。排出的行只要比 inner 宽 1 列就会被 `SegmentedWrap` 二次折行撕成
     两段、续段再加一层缩进——「大部分行齐、个别行裂开」是最难看的形态。离屏 Buffer 的单测
     两边共用同一套宽度口径，看不出来；真 VT 里数一下表格占了几行就知道。

  3. <b>宽度 oracle 本身的偏差</b>。`CharWidth` 把 VS16 文字型 emoji（如 `✔️` = U+2714 U+FE0F）
     算 1 列而多数终端画 2 列，含这类字符的行整体右移一列。这不是本功能能修的（全项目共用同一
     oracle），但表格是<b>最容易暴露</b>它的地方，所以放一个 emoji 格子把偏差<b>打印出来</b>
     （不判红）。注意 pyte 用的是另一套 wcwidth，两边不一致时先判断哪边跟 Terminal.app 一致，
     别拿 pyte 当真相。

桩模型说 DeepSeek SSE 方言（照抄 stream_box_smoke.py 的办法），按片吐字、表格是回复的<b>最后</b>
一块内容。不需要真实 key、不需要网络。

运行前<b>必须重新 package</b>（跑的是 target/classes，patch 模块还需 install），否则跑的是旧字节码：

    mvn -q -pl springai-tamboui-inline-patch install -DskipTests
    mvn -q -pl springai-code-tui package -DskipTests
    mvn -q -pl springai-code-tui dependency:build-classpath -Dmdep.outputFile=target/cp.txt
    /usr/bin/python3 src/test/resources/scripts/table_render_smoke.py
"""
import importlib.util
import json
import os
import re
import sys
import tempfile
import threading
import time
import unicodedata
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("resize_smoke", os.path.join(HERE, "resize_smoke.py"))
rs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rs)

ROWS, COLS = 30, 80
MODEL_ID = "deepseek-chat"
CHUNK = 7                       # 每个 SSE 分片的字符数（跨行切片，逼出跨批消费）

LEAD = "下面是参数说明。\n\n"
# 表格是回复的最后一块内容，且<b>没有</b>结尾空行——块结束只能靠回合结束的兜底 flush
TABLE_SRC = (
    "| 参数 | 类型 | 默认值 | 说明 |\n"
    "|------|------|--------|------|\n"
    "| codetui.syncOutput | String | auto | 控制是否使用终端同步输出扩展，取值 never/auto。|\n"
    "| codetui.hardwareCursor | String | auto | 控制硬件光标可见性，IME 路径需要 always。|\n"
    "| codetui.emojiProbe | String | ✔️ | 宽度 oracle 偏差探针，见脚本文档第 3 条。|"
)
REPLY = LEAD + TABLE_SRC

USAGE = {
    "prompt_tokens": 1200, "completion_tokens": 90, "total_tokens": 1290,
    "prompt_tokens_details": {"cached_tokens": 0},
}

SEP_RUN = re.compile(r"^\s*─+\s*$")


# ── 桩模型 ────────────────────────────────────────────────────────────────
def _sse(payload):
    return ("data: " + json.dumps(payload, ensure_ascii=False) + "\n\n").encode()


def _chunk(delta, finish=None, usage=None):
    body = {"id": "table-1", "object": "chat.completion.chunk", "created": 1, "model": MODEL_ID,
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
            for k in range(0, len(REPLY), CHUNK):
                self._write(_sse(_chunk({"content": REPLY[k:k + CHUNK]})))
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


# ── 断言辅助 ──────────────────────────────────────────────────────────────
def disp_width(s):
    """显示宽度（East Asian W/F 算 2 列）。

    ⚠ 不能拿 python 的字符下标当屏幕列用：pyte 的 display 把一个宽字符收在<b>一个</b>字符位里，
    「参数」占 2 个字符位却是 4 个屏幕列。照字符下标比列起点，表头与 ASCII 数据行永远差 2，
    是一条必红的假断言。
    """
    return sum(2 if unicodedata.east_asian_width(ch) in ("W", "F") else 1 for ch in s)


def screen_lines(session):
    return [line.rstrip() for line in session.screen.display]


def separator_rows(lines):
    """表格分隔线所在行号（欢迎横幅的圆角边框含 ╭╰ 不会误命中）。"""
    return [i for i, s in enumerate(lines) if s.strip() and SEP_RUN.match(s)]


def column_start(line, col_index):
    """第 col_index（0 基）列内容的<b>显示</b>起始列。列间是 2 空格，行首是 2 空格缩进。"""
    body = line[2:]
    if not body.strip():
        return None
    starts, i, n = [0], 0, len(body)
    while i < n:
        if body[i] == " " and i + 1 < n and body[i + 1] == " ":
            j = i
            while j < n and body[j] == " ":
                j += 1
            if j < n:
                starts.append(j)
            i = j
        else:
            i += 1
    if col_index >= len(starts):
        return None
    return disp_width(body[:starts[col_index]]) + 2


def has_emoji(s):
    """含 VS16 文字型 emoji 的行：宽度 oracle 已知有偏差，不参与严格断言（见文档第 3 条）。"""
    return "✔" in s


def is_row_start(s):
    """是「一条表格行的首段」而不是格内折行的<b>续段</b>。

    续段的前几列是空白（内容对齐到自己那一列），拿它比第 2 列起点必然不齐——那是正确排版，
    不是缺陷。判据：缩进之后立刻就是内容。
    """
    return len(s) > 2 and s[2] != " "


def main():
    classpath = rs.build_classpath()
    srv, base_url = start_stub()
    tmpdir = tempfile.mkdtemp(prefix="codetui-table-smoke-")
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

    cmd = ["java", "-Duser.home=" + home, "-Dcodetui.hardwareCursor=always",
           "-cp", classpath, rs.MAIN_CLASS]
    print("Launching (%dx%d): %s" % (ROWS, COLS, " ".join(cmd)))
    session = rs.PtySession(cmd, tmpdir, env, ROWS, COLS)
    try:
        session.wait_for(rs.WELCOME, timeout=40)
        session.wait_stable(quiet=0.8)

        session.write("请给我参数表\r".encode())
        # ⚠ 提交之后<b>不再按任何键</b>：表格必须靠回合结束的兜底 flush 自己出现。
        # 以画面连续静止为完成信号（回合结束后仍可能有按需补帧）。
        deadline = time.time() + 40
        while time.time() < deadline:
            session.pump(0.2)
            if separator_rows(screen_lines(session)):
                break
        if not session.wait_stable(quiet=1.2, timeout=15):
            rs.die("回合结束后的按需补帧未完成", screen_lines(session))

        lines = screen_lines(session)
        seps = separator_rows(lines)
        if not seps:
            rs.die("回合以表格结尾，但屏幕上没有 ─ 分隔线——表格没落地。"
                   "这就是「回合结束兜底 flush」漏掉时用户看到的样子（不按键就永远不出）", lines)
        if len(seps) != 1:
            rs.die("应恰好一条分隔线，实际 %d 条——表格被劈成两半（半张对齐 + 半张原样）" % len(seps),
                   lines)
        print("自己出现 OK: 未按任何键，表格在回合结束后落地")

        sep = seps[0]
        header = lines[sep - 1]
        body_rows = []
        for i in range(sep + 1, len(lines)):
            if not lines[i].strip() or not lines[i].startswith("  "):
                break
            body_rows.append(lines[i])
        if len(body_rows) < 3:
            rs.die("分隔线下面应至少有 3 行数据（3 条数据行），实际 %d" % len(body_rows), lines)

        # 原样输出的判据：重排后不该再有原文竖线
        leftovers = [s for s in [header] + body_rows if "|" in s]
        if leftovers:
            rs.die("表格行里还留着原文竖线 %r——这几行没被重排（降级或触发点顺序错）" % leftovers[:2],
                   lines)
        print("已重排 OK: 表头与数据行都不含原文竖线")

        # 分隔线长度 = 表格总宽；表格总宽 ≤ inner，否则会被二次折行撕开
        sep_width = disp_width(lines[sep].strip())
        if sep_width + 2 > COLS:
            rs.die("分隔线宽 %d + 缩进 2 超过终端宽 %d——行宽破了硬不变量，会被二次折行"
                   % (sep_width, COLS), lines)
        strict = [s for s in [header] + body_rows if not has_emoji(s) and is_row_start(s)]
        for s in strict:
            if disp_width(s) > COLS:
                rs.die("表格行显示宽度 %d 超过终端宽 %d：%r" % (disp_width(s), COLS, s), lines)
        print("不裂行 OK: 分隔线 %d 列，各行均 ≤ %d 列" % (sep_width, COLS))

        # 列起点对齐：第 2 列在表头与每条数据行上的显示偏移必须一致
        want = column_start(header, 1)
        if want is None:
            rs.die("定位不到表头第 2 列：%r" % header, lines)
        for s in strict:
            got = column_start(s, 1)
            if got is not None and got != want:
                rs.die("第 2 列起点不齐：表头 %d，数据行 %d（%r）" % (want, got, s), lines)
        print("列对齐 OK: 第 2 列起点在表头与数据行上一致（第 %d 列）" % want)

        # 宽度 oracle 偏差：只报告不判红（见脚本文档第 3 条 / 设计 §4）
        emoji_rows = [s for s in body_rows if has_emoji(s)]
        if emoji_rows:
            got = column_start(emoji_rows[0], 1)
            print("emoji 探针: 含 ✔️ 的行第 2 列起点 %r（其余行 %d）——CharWidth 把 U+2714 算 1 列、"
                  "VS16 算 0 列，多数终端画 2 列，含这类字符的行会整体右移；pyte 的 wcwidth 又是"
                  "第三套，两边不一致时先判断哪边跟 Terminal.app 一致，别拿 pyte 当真相"
                  % (got, want))

        session.write(b"/exit\r")
        session.pump(1.0)
        print("SMOKE PASS")
        return 0
    finally:
        session.close()
        srv.shutdown()


if __name__ == "__main__":
    sys.exit(main())

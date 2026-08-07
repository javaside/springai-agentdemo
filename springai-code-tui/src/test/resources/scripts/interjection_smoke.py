#!/usr/bin/env python3
"""「回合中插话」的 PTY 实机冒烟。

<b>这是整个功能唯一的端到端验证</b>。单测到不了两类东西：

  1. <b>接线本身</b>。`AgentTools.build` 那条「`InterjectingChatModel.wrap()` 有没有真套上」、
     以及 `CodeTuiApplication` 传的是不是 `runtime.interjections()` 那一份，<b>没有任何单测覆盖</b>
     （给 build 造一堆 mock provider 只能证明 mock 接得上）。那行被误删，全模块 1322 个测试
     一个都不会红。本脚本是唯一的网。
  2. <b>真终端里的回显与状态栏</b>。离屏 Buffer 的单测两边共用同一套排版，看不出分歧。

<b>没有真实 key 怎么跑</b>：照抄 permission_smoke.py / background_smoke.py 的办法——脚本内起一个
说 OpenAI/DeepSeek SSE 方言的桩模型，把 DEEPSEEK_BASE_URL 指过去。桩收到带标记的消息后
<b>先睡一段再应答</b>，制造出一个稳定的「回合正跑到一半」的窗口；应答是一个 `Glob` 工具调用
（READ_ONLY，权限引擎自动放行，不会弹审批面板），于是工具跑完还会有<b>第二次模型调用</b>——
插话正是在那一次被送出去的。<b>不需要真实 key、不需要网络。</b>

<b>本脚本最有价值的一条断言</b>（场景一里的 `check_delivered_to_model`）：屏幕上看到回显
<b>证明不了插话送到了模型</b>——回显那行是 UI 自己打的，队列里躺着不动也照样有。真凭据是
桩<b>实际收到的请求体</b>（`StubModel.sent`）：第二次调用的消息表末尾必须真的多出一条
带 `[interjection]` 包裹的 user，且它<b>紧跟在 tool 结果后面</b>。落在
`assistant(tool_calls)` 与 `tool` 之间就是悬空 tool_calls，真实网关直接 400。
这与 permission_smoke 里「屏幕上『收到了回复』恒真、真凭据是发出去的历史」是同一条纪律。

<b>变异实测（2026-08-07，务必读）</b>：把 `AgentTools.build` 里那行
`ChatClient.builder(InterjectingChatModel.wrap(visionModel, interjections))` 改回
`ChatClient.builder(visionModel)`（= 接线被误删）之后——

  * 全模块 <b>1322 个单测一个都没红</b>，证实了「这条接线没有任何单测覆盖」；
  * 本脚本的 `check_delivered_to_model` <b>红了</b>，且是为正确的理由红的
    （角色序列里根本没有那条插话）；
  * 但 <b>`check_interject_echo_and_counter` 照样绿</b>——回显和「插话 1 条」在接线断掉时
    完全正常，因为它们读的是 UI 自己的队列，队列只是躺着不动。

所以：<b>别拿回显/计数当送达的证据</b>，这个功能的唯一一道网是 `check_delivered_to_model`。
删它等于删掉整个功能的验收。

覆盖的四个场景：

  | 场景         | 操作                                   | 期望                                     |
  |--------------|----------------------------------------|------------------------------------------|
  | `/queue` 补全 | 输入 `/qu` → Tab                       | 补全成 `/queue`                          |
  | 插话回显      | 回合进行中输入一句 + Enter             | scrollback 出现 `› …`，且**不含**「待送达」 |
  | 状态栏计数    | 同上，插话后立刻读状态行               | 含「插话 1 条」；送达后该段消失            |
  | Esc 回填      | 回合中插话 → Esc                       | 输入框里出现刚才那句话                    |

⚠ <b>读状态行必须取当前屏幕的那一行</b>，不能拿子串去撞整个 scrollback——旧帧还在屏上，
`"插话 1 条" in screen_text()` 会在插话早已送达之后<b>依然为真</b>（见
pty-waitfor-matches-stale-scrollback）。本脚本用 {@link status_row} 取<b>最后一个非空行</b>
（`render()` 里 `statusLine()` 是最后一个元素，故它就是状态行）。

运行前<b>必须重新 package</b>，否则跑的是旧 jar，会得到一个看起来很像回归的假失败：

    mvn -q -pl springai-code-tui package -DskipTests
    mvn -q -pl springai-code-tui dependency:build-classpath -Dmdep.outputFile=target/cp.txt
    /usr/bin/python3 src/test/resources/scripts/interjection_smoke.py

成功 exit 0 + "SMOKE PASS"，失败非零 + "SMOKE FAIL: <原因>"（并打印最后一屏供人眼复核）。
"""
import json
import os
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from clear_smoke import (  # noqa: E402
    PtySession,
    build_classpath,
    die,
    print_screen,
    MAIN_CLASS,
    WELCOME_1,
)
from permission_smoke import find_row, wait_until  # noqa: E402

ENTER = b"\r"
TAB = b"\t"
ESC = b"\x1b"
CTRL_U = b"\x15"

MODEL_ID = "deepseek-v4-pro"

# 桩的路由标记：带上它的用户消息 → 桩先睡 STUB_SLEEP 秒（这就是「回合跑到一半」的窗口），
# 再吐一个 Glob 工具调用，于是工具跑完必然还有第二次模型调用。
SLOW_MARKER = "GLOBSLOWNOW"
STUB_SLEEP = 15.0

# 三段文本刻意互不相同：同一句话在两个场景里复用，第二个场景就会命中第一个留下的陈旧
# scrollback 而假绿（本仓踩过，见 pty-waitfor-matches-stale-scrollback）。
INTERJECT_TEXT = "换个思路"          # 场景二/三（回显 + 计数 + 送达）
REFILL_TEXT = "改用方案 B"           # 场景四（Esc 回填）
REFILL_SENTINEL = "尾巴"             # Esc 之后补打的哨兵，见 check_esc_refill

PLAIN_REPLY = "冒烟回复：一切正常。"
FINAL_REPLY = "冒烟回复：回合收尾。"

# 插话包裹标签（须与 InterjectingChatModel.OPEN / CLOSE 一致）。
OPEN_TAG = "[interjection]"
CLOSE_TAG = "[/interjection]"

# 状态栏里的插话计数段（CodeTuiView#statusLine 的 ijs）。
IJ_COUNT_1 = "插话 1 条"
QUEUED_MARK = "已排队"               # 走错路由（排队而非插话）时状态栏会显示这个
DELIVERY_STATE_WORD = "待送达"       # 回显那行刻意<b>不</b>写送达状态，见 CodeTuiView 注释
ESC_REFILL_NOTICE = "插话已放回输入框"


# ── 桩模型 ────────────────────────────────────────────────────────────────
def _sse(payload):
    return ("data: " + json.dumps(payload, ensure_ascii=False) + "\n\n").encode()


def _chunk(delta, finish=None):
    return {
        "id": "ij-smoke-1",
        "object": "chat.completion.chunk",
        "created": 1,
        "model": MODEL_ID,
        "choices": [{"index": 0, "delta": delta, "finish_reason": finish}],
    }


class StubModel(BaseHTTPRequestHandler):
    """最小的 DeepSeek 兼容 /chat/completions SSE 端点，按<b>最后一条消息</b>路由。

    路由表（顺序即优先级）：
      * 最后一条含 `[/interjection]`  → 纯文本收尾。插话被送达时它就排在消息表末尾，
                                        故这条分支的命中本身就是「插话确实走到模型」的信号。
      * 最后一条 role == "tool"       → 纯文本收尾（本回合没有插话时走这里）。
      * 最后一行含 SLOW_MARKER        → 睡 STUB_SLEEP 秒，再吐一个 Glob 工具调用。
      * 其余                          → 纯文本。
    """

    requests = []      # (最后一条消息的 role, 应答种类)，仅供人眼复核
    sent = []          # 每次请求<b>实际发出去</b>的完整 messages —— 送达断言的唯一凭据
    lock = threading.Lock()

    def log_message(self, fmt, *args):   # 别把 HTTP 日志喷进 pty
        pass

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(length) or b"{}")
        messages = body.get("messages") or []
        last = messages[-1] if messages else {}
        role = last.get("role", "")
        content = last.get("content") or ""
        if not isinstance(content, str):
            content = json.dumps(content, ensure_ascii=False)

        # 只看<b>最后一行</b>：会话层会把连续同角色消息折在一起，否则一条旧的
        # "…GLOBSLOWNOW" 用户消息会永远把后面每一回合都重新拖进工具循环。
        tail = (content.strip().splitlines() or [""])[-1]
        slow = False

        if CLOSE_TAG in content:
            kind, chunks = "text_after_interjection", self._text_chunks(FINAL_REPLY)
        elif role == "tool":
            kind, chunks = "text_after_tool", self._text_chunks(FINAL_REPLY)
        elif SLOW_MARKER in tail:
            kind, chunks = "glob_call", self._tool_call_chunks("Glob", {"pattern": "*"})
            slow = True
        else:
            kind, chunks = "text", self._text_chunks(PLAIN_REPLY)

        with StubModel.lock:
            StubModel.requests.append((role, kind))
            StubModel.sent.append(messages)

        if slow:
            # 睡在应答<b>之前</b>：此刻应用已提交、状态是「● 思考中…」，而 SSE 流还没开始。
            # 这段睡眠就是冒烟往里塞插话的窗口。
            time.sleep(STUB_SLEEP)

        try:
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            self.end_headers()
            for c in chunks:
                self.wfile.write(c)
                self.wfile.flush()
            self.wfile.write(b"data: [DONE]\n\n")
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            # Esc 取消回合会掐断连接，而桩这时正睡着。这是<b>预期</b>路径（场景四），
            # 不是失败——不吞掉的话 ThreadingHTTPServer 会往 stderr 喷一整段栈。
            pass

    @staticmethod
    def _text_chunks(text):
        return [
            _sse(_chunk({"role": "assistant", "content": ""})),
            _sse(_chunk({"content": text})),
            _sse(_chunk({}, finish="stop")),
        ]

    @staticmethod
    def _tool_call_chunks(name, args):
        call = {
            "index": 0,
            "id": "call_ij_%d" % len(StubModel.requests),
            "type": "function",
            "function": {"name": name, "arguments": json.dumps(args)},
        }
        return [
            _sse(_chunk({"role": "assistant", "content": "", "tool_calls": [call]})),
            _sse(_chunk({}, finish="tool_calls")),
        ]


def start_stub():
    srv = ThreadingHTTPServer(("127.0.0.1", 0), StubModel)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv, "http://127.0.0.1:%d" % srv.server_address[1]


# ── 屏幕助手 ──────────────────────────────────────────────────────────────
def status_row(session):
    """<b>当前屏幕</b>的状态行文本（最后一个非空行）。

    `render()` 里 `statusLine()` 是 column 的最后一个元素，故它就是内联块的最后一行。

    <b>为什么不用子串撞整屏</b>：状态栏是就地重绘的，但屏上还留着 scrollback 与上一帧的
    残迹；`"插话 1 条" in screen_text()` 会在插话早已送达之后依然为真，那条断言就变成了
    「不会失败的测试」。取当前屏幕的那一行，才是在断言「此刻的状态」。
    """
    for y in range(len(session.screen.display) - 1, -1, -1):
        if session.screen.display[y].strip():
            return session.screen.display[y]
    return ""


def self_test_status_row(session):
    """先证明 status_row 真的取到了状态行，再拿它下断言。

    否则「状态行里含插话计数」这条可能因为 status_row 永远返回 "" 而<b>永远不会失败</b>
    ——空闲态状态行有一组固定文案，取不到就说明这个助手坏了。
    """
    row = status_row(session)
    for needle in ("Enter 发送", "Ctrl+C 退出"):
        if needle not in row:
            die("status_row 自检失败：空闲态状态行应含 %r，实际取到 %r" % (needle, row.strip()),
                session.screen.display)
    print("自检 OK: status_row 取到的确实是状态行（%r）." % row.strip())


def wait_thinking(session, what):
    """等到<b>当前状态行</b>进入「● 思考中…」。

    不能用 `wait_for("思考中")`：跑到第二个场景时，第一个场景留下的思考态残迹还在屏上，
    那句 wait 会立刻返回，于是插话被打进了一个<b>已经空闲</b>的会话——走的是普通提交，
    整个场景静默失去意义。
    """
    wait_until(session, lambda: "思考中" in status_row(session), 25, what)


# ── 场景一：/queue 的 Tab 补全 ────────────────────────────────────────────
def check_queue_completion(session):
    """`/qu` + Tab 必须补全成 `/queue`。

    <b>光 wait_for("/queue") 是假阳性</b>：`/queue` 同时出现在斜杠菜单那一行里，补不补全
    它都在屏上。补全后紧接着打一个哨兵字符才有鉴别力——补上了读作 `/queuex`，
    Tab 被吞了则读作 `/qux`（这正是 permission_smoke 里 `/modelx` 的同一招；
    Tab 补全曾在发布版里整个失效而单测全绿）。
    """
    session.write(b"/qu")
    session.wait_for("❯ /queue")

    session.write(TAB)
    session.write(b"x")
    session.wait_for("/queuex")
    if "/qux" in session.screen_text():
        die("Tab 没有补全（输入仍是 /qux）", session.screen.display)
    print("/queue 补全 OK: `/qu` + Tab → `/queue`（哨兵读作 /queuex）.")

    session.write(CTRL_U)
    session.pump(0.3)


# ── 场景二 + 三：回显、状态栏计数、以及「真的送到了模型」 ──────────────────
def check_interject_echo_and_counter(session):
    """回合进行中插话：scrollback 回显原话、状态栏出现「插话 1 条」。

    两条断言各自钉住一个刻意的设计：

      * 回显那行<b>不写送达状态</b>。内联 TUI 打进 scrollback 的行事后改不了，写「待送达」
        的话，插话送出去之后那行会永远停在错的状态上。
      * 送没送出去<b>只由状态栏实时反映</b>，所以状态栏那一段是插话唯一的实时反馈，
        它必须真的出现。

    还顺带钉住<b>路由</b>：忙时 Enter 必须走插话而不是排队。两条路都会在屏上留下
    `› 换个思路`（排队面板用的是同一个前缀），只有状态栏能区分——插话是「插话 N 条」，
    排队是「已排队 N 条」。所以这里既断言前者在、也断言后者不在。
    """
    session.write(("帮我看看 " + SLOW_MARKER).encode() + ENTER)
    wait_thinking(session, "第一个回合进入思考态（桩正在睡）")

    session.write(INTERJECT_TEXT.encode() + ENTER)
    session.wait_for("› " + INTERJECT_TEXT, timeout=10)
    print("插话回显 OK: scrollback 出现 %r." % ("› " + INTERJECT_TEXT))

    if DELIVERY_STATE_WORD in session.screen_text():
        die("回显行写了 %r —— scrollback 里的行改不了，送达后会永远停在错的状态上"
            % DELIVERY_STATE_WORD, session.screen.display)

    row = status_row(session)
    if IJ_COUNT_1 not in row:
        die("状态行没有插话计数：期望含 %r，实际 %r" % (IJ_COUNT_1, row.strip()),
            session.screen.display)
    if QUEUED_MARK in row:
        die("忙时 Enter 走成了排队而不是插话（状态行含 %r）：%r"
            % (QUEUED_MARK, row.strip()), session.screen.display)
    print("状态栏计数 OK: 当前状态行 = %r." % row.strip())
    print_screen("插话已投递、尚未送达（人眼复核）", session.screen.display)


def check_delivered_to_model(session):
    """<b>本脚本最重要的一条</b>：插话真的进了发往模型的那张消息表，且位置合法。

    屏幕上的回显与计数<b>都证明不了这件事</b>——它们是 UI 自己画的，`InterjectingChatModel`
    压根没被套上（`AgentTools.build` 里那一行被误删）时它们照样全绿，队列只是躺着不动。
    唯一的凭据是桩<b>实际收到的请求体</b>。

    三条一起看：
      1. 某次请求的消息表里出现了 `[interjection]` 包裹的原话 —— 装饰器确实套上了；
      2. 它在<b>消息表末尾</b> —— 追加点没错；
      3. 它的<b>前一条是 tool 结果</b> —— 落在 `assistant(tool_calls)` 与 `tool` 之间
         就是悬空 tool_calls，真实网关直接 400（本项目在 Esc 中断那条路上踩过一次）。
    """
    session.wait_for(FINAL_REPLY, timeout=90)
    session.pump(0.8)

    with StubModel.lock:
        sent = [list(m) for m in StubModel.sent]
        kinds = list(StubModel.requests)

    hit = None
    for msgs in sent:
        for i, m in enumerate(msgs):
            c = m.get("content") or ""
            if isinstance(c, str) and OPEN_TAG in c and INTERJECT_TEXT in c:
                hit = (msgs, i, m)
                break
        if hit:
            break

    if hit is None:
        roles = [[m.get("role") for m in msgs] for msgs in sent]
        die("插话<b>没有</b>出现在任何一次发往模型的请求里 —— 接线断了"
            "（InterjectingChatModel 没套上，或用的不是同一个 Interjections）。"
            "桩收到 %d 次请求，角色序列=%s，种类=%s" % (len(sent), roles, kinds),
            session.screen.display)

    msgs, idx, msg = hit
    roles = [m.get("role") for m in msgs]
    if msg.get("role") != "user":
        die("插话的角色是 %r，应为 user（角色序列=%s）" % (msg.get("role"), roles),
            session.screen.display)
    if idx != len(msgs) - 1:
        die("插话不在消息表末尾（在第 %d/%d 条，角色序列=%s）" % (idx, len(msgs), roles),
            session.screen.display)
    if idx == 0 or msgs[idx - 1].get("role") != "tool":
        prev = msgs[idx - 1].get("role") if idx else "<无>"
        die("插话前一条是 %r，必须是 tool 结果 —— 落在 assistant(tool_calls) 与 tool 之间"
            "就是悬空 tool_calls，真实网关直接 400（角色序列=%s）" % (prev, roles),
            session.screen.display)
    if CLOSE_TAG not in (msg.get("content") or ""):
        die("插话文本没有闭合标签 %r（内容=%r）" % (CLOSE_TAG, msg.get("content")),
            session.screen.display)

    print("送达证明 OK: 插话以 %r 包裹、排在消息表末尾、紧跟 tool 结果（角色序列=%s）."
          % (OPEN_TAG, roles))

    # 送达之后计数段必须消失：它是「尚未送达」的实时反馈，不清就变成了骗人的常驻文案。
    row = status_row(session)
    if "插话" in row:
        die("插话已送达但状态行仍显示计数：%r" % row.strip(), session.screen.display)
    print("计数清零 OK: 送达后状态行不再有插话段（%r）." % row.strip())


# ── 场景四：Esc 把插话回填输入框 ──────────────────────────────────────────
def check_esc_refill(session):
    """回合中插话 → Esc：那句话必须回到<b>输入框</b>，不是被丢弃。

    按 Esc 通常正是「别跑了，听我的」，那句话不该跟着一起没。已送达的那条尤其不能丢——
    取消走 `doOnCancel`，`handleComplete` 不跑、没人补它进历史，不还给用户就是
    「模型看过、历史没有、用户也拿不回来」。

    <b>怎么证明它在输入框里而不只是在 scrollback 里</b>：插话的回显行 `› 改用方案 B`
    本来就留在 scrollback 上，光找这个子串是不会失败的测试。Esc 之后补打一个哨兵字符，
    只有当文本真的在<b>输入缓冲区</b>里、且光标落在文末时，屏上才会读到拼起来的整串
    （`moveCursorToEnd` 正是为了这个）。
    """
    session.write(("再来一次 " + SLOW_MARKER).encode() + ENTER)
    wait_thinking(session, "第二个回合进入思考态（Esc 场景）")

    session.write(REFILL_TEXT.encode() + ENTER)
    session.wait_for("› " + REFILL_TEXT, timeout=10)

    session.write(ESC)
    wait_until(session, lambda: ESC_REFILL_NOTICE in status_row(session),
               15, "Esc 后状态行提示 %r（实际：%r）" % (ESC_REFILL_NOTICE, status_row(session)))
    print("Esc 提示 OK: %r." % status_row(session).strip())

    session.write(REFILL_SENTINEL.encode())
    session.wait_for(REFILL_TEXT + REFILL_SENTINEL, timeout=10)
    print("Esc 回填 OK: 输入框里读到 %r（哨兵拼上了，证明它在输入缓冲区而非 scrollback）."
          % (REFILL_TEXT + REFILL_SENTINEL))
    print_screen("Esc 回填后的输入框（人眼复核）", session.screen.display)

    session.write(CTRL_U)
    session.pump(0.3)


# ── main ─────────────────────────────────────────────────────────────────
def main():
    classpath = build_classpath()
    tmpdir = tempfile.mkdtemp(prefix="codetui-interject-smoke-")
    # 隔离用户层：真实 ~/.codetui 的权限规则/技能/记忆都会改变启动形态。
    home_dir = os.path.join(tmpdir, "home")
    os.makedirs(home_dir, exist_ok=True)
    # 给 Glob 一点东西可找（找不到也不影响：工具返回什么都会产生一条 tool 结果）。
    with open(os.path.join(tmpdir, "notes.txt"), "w") as f:
        f.write("interjection smoke\n")

    srv, base_url = start_stub()
    print("Stub model on %s（标记 %s 触发：睡 %.0fs → Glob 工具调用）"
          % (base_url, SLOW_MARKER, STUB_SLEEP))

    env = dict(os.environ)
    env["TERM"] = "xterm-256color"            # 不设则渲染全空白
    env["DEEPSEEK_API_KEY"] = "sk-dummy-not-real"
    env["DEEPSEEK_BASE_URL"] = base_url
    env["DEEPSEEK_MODELS"] = MODEL_ID
    # 别让开发机上的其他 key 多挂 provider（会改默认模型，也可能真的发出网络请求）。
    for k in ("ZHIPU_API_KEY", "DASHSCOPE_API_KEY", "ANTHROPIC_API_KEY", "OPENAI_API_KEY",
              "BOCHA_API_KEY", "BRAVE_API_KEY"):
        env.pop(k, None)

    cmd = ["java", "-Duser.home=%s" % home_dir, "-cp", classpath, MAIN_CLASS]
    print("Launching: %s" % " ".join(cmd))
    print("cwd=%s" % tmpdir)

    # PtySession 自己 openpty + TIOCSWINSZ（40×120）。0×0 的话什么都读不到。
    session = PtySession(cmd, tmpdir, env)
    try:
        session.wait_for(WELCOME_1, timeout=40)
        print("Startup OK.")
        self_test_status_row(session)

        check_queue_completion(session)
        check_interject_echo_and_counter(session)
        check_delivered_to_model(session)
        check_esc_refill(session)

        session.write(b"/exit\r")
        deadline = time.time() + 10
        while time.time() < deadline and session.proc.poll() is None:
            session.pump(0.2)

        with StubModel.lock:
            print("桩共收到 %d 次请求：%s" % (len(StubModel.requests), StubModel.requests))
        print("SMOKE PASS")
        return 0
    finally:
        session.close()
        srv.shutdown()


if __name__ == "__main__":
    sys.exit(main())

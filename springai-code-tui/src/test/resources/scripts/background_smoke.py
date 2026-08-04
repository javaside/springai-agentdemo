#!/usr/bin/env python3
"""后台子 agent（run_in_background）的 PTY 实机冒烟。

单测跑在离屏 Buffer（ViewScreen）上，测不到<b>真实 ANSI 输出 + InlineDisplay 行数记账</b>
那一层。本脚本只覆盖那一层够得着、而单测够不着的四件事：

  1. `/tasks` 的<b>空态</b>：零任务也照常开面板，并说明后台任务从哪来。
  2. 有后台任务时：⏱ 面板真的画出来了，状态栏尾部真的带 `⏱ N 个后台任务`。
  3. 任务完成后<b>自动起一个回合</b>，且通知文本按 `\\n` 逐物理行渲染——
     「一个 OutputLine = 一个物理行」是本项目栽过的坑：多行字符串走 println
     会被塌成一行截断，离屏 Buffer 里两边共用同一套拆行逻辑，看不出来。
  4. `/tasks` → `k` → Enter 真的把任务改成「已终止」。

<b>本脚本最有价值的一条断言</b>（场景 2 里）：⏱ 面板在输入框<b>上方</b>多占若干行，
在小窗口（24 行）下带 3 条任务时，<b>输入框是否还在可见区域内</b>。那是 InlineDisplay
的行数记账问题，离屏 Buffer 永远测不出来——它没有「屏幕只有 24 行」这个约束。

<b>没有真实 LLM 怎么造出后台任务</b>：照抄 permission_smoke.py 的办法——脚本内起一个
说 OpenAI/DeepSeek SSE 方言的桩模型，把 DEEPSEEK_BASE_URL 指过去。桩按<b>最后一条消息</b>
路由，既能对主 agent 吐出 `Task(run_in_background=true)` 的工具调用，也能扮演被派出去的
子 agent 本身（子 agent 用的是同一个 provider）。整条链是真的：Task 工具 → SubagentRunner
.runInBackground → 常驻后台池 → 注册表 → ConversationState → ⏱ 面板 / 自动送达。
<b>不需要真实 key、不需要网络。</b>

<b>为什么开两个会话</b>：场景 2/4 要一个 24 行的小窗口（不小就测不出输入框被顶出屏幕），
而场景 1/3 要在常规窗口下看清 scrollback 里那一整块通知文本。挤在一个会话里，⏱ 面板会
一直带着前面场景留下的任务行，两边互相压缩可视区。分开跑，各自的断言才有鉴别力。

运行前：
    mvn -pl springai-code-tui clean package -DskipTests
    mvn -q -pl springai-code-tui dependency:build-classpath \\
        -Dmdep.outputFile=target/cp.txt
    /usr/bin/python3 src/test/resources/scripts/background_smoke.py

成功 exit 0 + "SMOKE PASS"，失败非零 + "SMOKE FAIL: <原因>"（并打印最后一屏供人眼复核）。
"""
import json
import os
import re
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
from permission_smoke import find_row, assert_rows_below, wait_until  # noqa: E402

ESC = b"\x1b"
CTRL_U = b"\x15"
ENTER = b"\r"

MODEL_ID = "deepseek-v4-pro"

# ── 桩模型的路由标记 ──────────────────────────────────────────────────────
# 主 agent 侧：用户消息里带这些标记时才吐工具调用，普通闲聊回合保持干净。
BG_ONE_MARKER = "BGONENOW"      # -> 一个 Task(run_in_background=true)，子 agent 很快跑完
BG_THREE_MARKER = "BGTHREENOW"  # -> 三个 Task(run_in_background=true)，子 agent 永不结束
# 子 agent 侧：桩靠子 agent 的 user 消息（= Task 调用里的 prompt）认出自己在扮谁。
QUICK_JOB = "QUICKJOB"          # 睡 2s 后返回三行结果
FOREVER_JOB = "FOREVERJOB"      # 每 2s 吐一个 token，测试窗口内永不结束

# 子 agent 的最终文本：<b>三行</b>，每行一个唯一标记。
# 「按 \n 正确换行」这条断言全靠它——三个标记必须落在三个<b>连续物理行</b>上，
# 塌成一行的话后两个标记会连同第一行一起被截断（见 one-outputline-is-one-physical-line）。
JOB_LINE_1 = "结论一 BGLINEA"
JOB_LINE_2 = "结论二 BGLINEB"
JOB_LINE_3 = "结论三 BGLINEC"
QUICK_RESULT = JOB_LINE_1 + "\n" + JOB_LINE_2 + "\n" + JOB_LINE_3

QUICK_DESC = "冒烟快任务"
FOREVER_DESC = "冒烟长任务"
PLAIN_REPLY = "冒烟回复：一切正常。"

# ── 屏幕上要断言的文本 ────────────────────────────────────────────────────
TASKS_PANEL_TITLE = "⏱ 后台任务（↑↓ 选择"          # /tasks 面板标题（带操作提示的那个）
TASKS_EMPTY = "（暂无后台任务）"
BG_PANEL_PREFIX = "⏱ 后台任务 ("                    # 常驻 ⏱ 面板标题（带计数）
NOTIFY_HEAD = "[后台任务完成]"
NOTIFY_TAIL = "以上是你先前派出的后台任务的结果，请据此继续。"
KILL_CONFIRM = "⚠ 确认终止"
# 定位<b>面板里</b>那句确认时不能用 KILL_CONFIRM：状态行也以「⚠ 确认终止」开头（短版本，不带 id），
# 而 find_row 按设计取<b>最后</b>一个匹配（活面板钉在底部、scrollback 在上面）——于是它会稳定
# 抓到状态行那条，断言「确认句里有选中的 id」就永远失败。用只在面板里出现的这半句做锚点。
# 这与「wait_for 命中陈旧 scrollback」是同一族陷阱：子串在屏上不唯一。
KILL_CONFIRM_PANEL = "已跑出的进度会丢失"
KILLED_MARK = "已终止"
BOX_TOP, BOX_BOTTOM = "╭", "╰"                      # 圆角输入框的上下边框
BOX_PROBE = "BOXVISIBLE"                            # 打进输入框的唯一标记（不按 Enter）

TASK_ID_RE = re.compile(r"task_[0-9a-f]{8}")


# ── 桩模型 ────────────────────────────────────────────────────────────────
def _sse(payload):
    return ("data: " + json.dumps(payload, ensure_ascii=False) + "\n\n").encode()


def _chunk(delta, finish=None):
    return {
        "id": "bg-smoke-1",
        "object": "chat.completion.chunk",
        "created": 1,
        "model": MODEL_ID,
        "choices": [{"index": 0, "delta": delta, "finish_reason": finish}],
    }


def _task_call(idx, description, prompt):
    """一个 run_in_background=true 的 Task 工具调用（OpenAI function-call 形态）。"""
    return {
        "index": idx,
        "id": "call_bg_%d_%d" % (idx, int(time.time() * 1000) % 100000),
        "type": "function",
        "function": {
            "name": "Task",
            "arguments": json.dumps({
                "description": description,
                "prompt": prompt,
                "subagent_type": "explore",
                "run_in_background": True,
            }, ensure_ascii=False),
        },
    }


class StubModel(BaseHTTPRequestHandler):
    """最小的 DeepSeek 兼容 /chat/completions SSE 端点，主 agent 与子 agent 共用。

    按<b>最后一条消息</b>路由，脚本不必数回合数。子 agent 与主 agent 靠 user 消息里的
    标记区分——子 agent 的 user 消息就是 Task 调用里的 prompt，由本脚本自己写死。
    """

    daemon_threads = True
    lock = threading.Lock()
    requests = []          # (角色, 回复种类)，供诊断

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
        # 只看<b>最后一行</b>：会话层会把连续同角色消息折叠，否则一条旧的
        # 「…BGONENOW」用户消息会永远重新触发工具调用（照抄 permission_smoke 的教训）。
        tail = (content.strip().splitlines() or [""])[-1]

        if role == "tool":
            kind = "text"
        elif FOREVER_JOB in content:
            kind = "forever"
        elif QUICK_JOB in content:
            kind = "quick"
        elif BG_THREE_MARKER in tail:
            kind = "bg3"
        elif BG_ONE_MARKER in tail:
            kind = "bg1"
        else:
            kind = "text"

        with StubModel.lock:
            StubModel.requests.append((role, kind))

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        try:
            self._emit(kind)
        except (BrokenPipeError, ConnectionResetError):
            return   # 客户端（被终止的后台任务 / 退出的 JVM）断开：正常，静默收摊

    def _emit(self, kind):
        if kind == "forever":
            # <b>不能只是 sleep 再回复</b>：那样连接长时间空闲，可能撞上客户端的读超时，
            # 任务会以 FAILED 结束、进而被自动送达，把「一直在跑」的场景毁掉。
            # 改成每 2s 吐一个 token：连接一直活着，任务在测试窗口内稳定停在 RUNNING。
            self._write(_sse(_chunk({"role": "assistant", "content": ""})))
            for _ in range(300):
                time.sleep(2.0)
                self._write(_sse(_chunk({"content": "."})))
            return
        if kind == "quick":
            time.sleep(2.0)   # 给「⏱ 面板出现 → 任务完成 → 自动起回合」留出可观测的过程
            self._text(QUICK_RESULT)
            return
        if kind == "bg1":
            self._calls([_task_call(0, QUICK_DESC, QUICK_JOB + " 请给出三行结论")])
            return
        if kind == "bg3":
            self._calls([_task_call(i, FOREVER_DESC + str(i + 1),
                                    FOREVER_JOB + " 长期调查 " + str(i + 1))
                         for i in range(3)])
            return
        self._text(PLAIN_REPLY)

    def _write(self, data):
        self.wfile.write(data)
        self.wfile.flush()

    def _text(self, text):
        self._write(_sse(_chunk({"role": "assistant", "content": ""})))
        self._write(_sse(_chunk({"content": text})))
        self._write(_sse(_chunk({}, finish="stop")))
        self._write(b"data: [DONE]\n\n")

    def _calls(self, calls):
        self._write(_sse(_chunk({"role": "assistant", "content": "", "tool_calls": calls})))
        self._write(_sse(_chunk({}, finish="tool_calls")))
        self._write(b"data: [DONE]\n\n")


def start_stub():
    srv = ThreadingHTTPServer(("127.0.0.1", 0), StubModel)
    srv.daemon_threads = True
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv, "http://127.0.0.1:%d" % srv.server_address[1]


# ── 会话工具 ──────────────────────────────────────────────────────────────
def launch(base_url, rows, cols, prefix):
    """起一个隔离的应用实例（自带临时 cwd + 临时 user.home）。"""
    classpath = build_classpath()
    tmpdir = tempfile.mkdtemp(prefix=prefix)
    home_dir = os.path.join(tmpdir, "home")
    os.makedirs(home_dir, exist_ok=True)

    env = dict(os.environ)
    env["TERM"] = "xterm-256color"          # 不设的话 JLine 退化成哑终端，屏幕读出来是空的
    env["DEEPSEEK_API_KEY"] = "sk-dummy-not-real"
    env["DEEPSEEK_BASE_URL"] = base_url
    env["DEEPSEEK_MODELS"] = MODEL_ID
    env["CODETUI_BACKGROUND_CONCURRENCY"] = "6"   # 三个长任务 + 一个快任务要能同时在飞
    # 别让开发机上的其它 key 引入额外 provider / 额外默认模型。
    for k in ("ZHIPU_API_KEY", "DASHSCOPE_API_KEY", "ANTHROPIC_API_KEY", "OPENAI_API_KEY"):
        env.pop(k, None)

    cmd = ["java", "-Duser.home=%s" % home_dir, "-cp", classpath, MAIN_CLASS]
    print("Launching (%dx%d): %s" % (rows, cols, " ".join(cmd)))
    print("cwd=%s" % tmpdir)
    session = PtySession(cmd, tmpdir, env, rows=rows, cols=cols)
    session.wait_for(WELCOME_1, timeout=40)
    return session, tmpdir


def task_ids_on_screen(session):
    """屏幕上出现过的 task id（按出现顺序去重）。"""
    seen = []
    for line in session.screen.display:
        for m in TASK_ID_RE.findall(line):
            if m not in seen:
                seen.append(m)
    return seen


def quit_app(session, budget=20.0):
    """/exit 并断言按时退出。

    <b>顺带钉住一条</b>：三个后台任务的 HTTP 还挂在桩上（读操作不响应 interrupt），
    退出清理是<b>有界</b>的（shutdownBackground 硬限 2s + 守护线程），所以照样该按时退出。
    """
    session.write(b"/exit\r")
    t0 = time.time()
    while time.time() - t0 < budget:
        if session.proc.poll() is not None:
            print("/exit OK: %.2fs 内退出（后台任务仍挂在桩上，退出清理有界）." % (time.time() - t0))
            return
        session.pump(0.2)
    die("/exit 后 %.1fs 未退出（疑似被后台任务拖住）" % budget, session.screen.display)

# ── 场景 1：/tasks 空态 ───────────────────────────────────────────────────
def check_tasks_panel_empty(session):
    """启动即 `/tasks`：零任务也照常开面板，并说清后台任务从哪来。

    <b>锚定标题行往下数、不各自 find</b>：空态说明与常驻 ⏱ 面板共用「⏱ 后台任务」前缀，
    分别 find 会互相误命中；而这一条恰恰要证明「空态说明就在标题的下一行」。
    """
    session.write(b"/tasks\r")
    session.wait_for(TASKS_PANEL_TITLE, timeout=10)
    session.pump(0.6)
    print_screen("场景 1: /tasks 空态", session.screen.display)

    anchor = find_row(session, TASKS_PANEL_TITLE)
    assert_rows_below(session, anchor, [TASKS_EMPTY], "/tasks 空态")
    if BG_PANEL_PREFIX in session.screen_text():
        die("零任务时不该出现常驻 ⏱ 面板（它该一行都不占）", session.screen.display)
    print("场景 1 OK：/tasks 空态开得出来、说明就在标题下一行、常驻面板零占行.")

    session.write(ESC)
    wait_until(session, lambda: TASKS_PANEL_TITLE not in session.screen_text(),
               6, "Esc 关掉 /tasks 面板")
    print("场景 1 OK：Esc 关闭面板.")


# ── 场景 2：⏱ 面板 + 状态栏后缀 + 输入框可见性 ────────────────────────────
def check_background_panel_and_input_visible(session):
    """三个后台任务在跑：⏱ 面板画出来了、状态栏带 ⏱ 后缀，且<b>输入框仍在可见区内</b>。

    <b>本脚本最有价值的一条</b>：⏱ 面板占在输入框上方（标题 1 行 + 每任务 1 行），
    小窗口（24 行）下它挤掉的是 scrollback 还是输入框，取决于 InlineDisplay 的行数记账。
    离屏 Buffer 的单测没有「屏幕只有 24 行」这个约束，两边一起错就一起绿。

    输入框可见性怎么证：往框里打一个<b>唯一标记</b>（不按 Enter，不进会话），
    然后要求它出现在屏幕上、且上下两条圆角边框都在。<b>光断言「╭ 在屏上」不够</b>——
    ╭ 也出现在启动横幅里；标记文本是这一屏独有的。
    """
    session.write(("派活 " + BG_THREE_MARKER).encode() + b"\r")
    # 三条 ⏱ 面板行都出现 = 三个任务都登记进 ConversationState 了
    wait_until(session, lambda: sum(1 for ln in session.screen.display
                                    if ln.lstrip().startswith("▶ task_")) == 3,
               60, "⏱ 面板出现三条运行中的任务行")
    session.pump(1.0)

    text = session.screen_text()
    if BG_PANEL_PREFIX not in text:
        die("⏱ 常驻面板没出现（应有「%s3 运行 · 0 完成)」标题）" % BG_PANEL_PREFIX,
            session.screen.display)
    title_row = find_row(session, BG_PANEL_PREFIX)
    if "(3 运行" not in session.screen.display[title_row]:
        die("⏱ 面板计数不对：%r" % session.screen.display[title_row].strip(),
            session.screen.display)

    # 状态栏后缀。<b>不能只匹配「⏱」</b>：面板标题里也有它，屏幕上到处都是。
    # 宽度用 120（同其余脚本）：整条状态行显示宽 109，在 100 列下尾部的「· /tasks」
    # 会被<b>如实截掉</b>（这是设计好的取舍，见 backgroundStatusSuffix 的注释——
    # 后缀刻意挂在最尾，宁可它先被截）。窄窗口断言这一条只是在测截断，不是在测后缀。
    status = session.screen.display[session.rows - 1]
    if "⏱ 3 个后台任务" not in status:
        die("状态栏尾部没有「⏱ 3 个后台任务」后缀，实际：%r" % status.strip(),
            session.screen.display)
    if "/tasks" not in status:
        die("状态栏后缀没提示 /tasks，实际：%r" % status.strip(), session.screen.display)
    print("场景 2 OK：⏱ 面板 3 条、状态栏尾部含「⏱ 3 个后台任务 · /tasks」.")

    # ── 输入框可见性（本任务的核心疑点）──
    session.write(BOX_PROBE.encode())      # 只打字、不发送
    wait_until(session, lambda: BOX_PROBE in session.screen_text(),
               8, "输入框里的探针文本 %r 出现在屏幕上" % BOX_PROBE)
    session.pump(0.5)
    print_screen("场景 2: 24 行窗口 · 3 条后台任务 · 输入框可见性", session.screen.display)

    probe_row = find_row(session, BOX_PROBE)
    if probe_row < 0:
        die("输入框被 ⏱ 面板顶出可见区：探针 %r 不在屏幕上" % BOX_PROBE, session.screen.display)
    if probe_row >= session.rows - 1:
        die("输入框压到了状态行上（探针在第 %d 行，窗口共 %d 行）" % (probe_row, session.rows),
            session.screen.display)
    above = session.screen.display[probe_row - 1] if probe_row > 0 else ""
    below = session.screen.display[probe_row + 1] if probe_row + 1 < session.rows else ""
    if BOX_TOP not in above:
        die("输入框上边框被顶出屏幕：探针上一行是 %r" % above.rstrip(), session.screen.display)
    if BOX_BOTTOM not in below:
        die("输入框下边框被挤出屏幕：探针下一行是 %r" % below.rstrip(), session.screen.display)
    # ⏱ 面板必须在输入框<b>上方</b>——顺序反了说明布局串位（render 的 column 顺序被改坏）。
    if title_row >= probe_row:
        die("⏱ 面板没画在输入框上方（面板第 %d 行、输入框第 %d 行）" % (title_row, probe_row),
            session.screen.display)
    print("场景 2 OK：24 行窗口 + 3 条后台任务时，输入框（含上下边框）完整可见，"
          "在第 %d 行、状态行第 %d 行." % (probe_row, session.rows - 1))

    session.write(CTRL_U)                  # 清掉探针，别让它污染后面的场景
    session.pump(0.4)


# ── 场景 3：任务完成 → 自动起回合 + 通知按 \n 逐行 ────────────────────────
def check_auto_turn_wraps_lines(session):
    """快任务跑完 → 自动起一个回合，通知文本按 `\\n` 拆成<b>连续物理行</b>。

    这是「一个 OutputLine = 一个物理行」那条坑的实机守卫：多行字符串走 println
    会被塌成一行、后面的内容随之被截断。三行结果各带唯一标记，锚定通知抬头往下数——
    只断言「三个标记都在屏上」是<b>抓不到</b>塌行的（塌成一行时它们仍都在同一行里）。
    """
    session.write(("派活 " + BG_ONE_MARKER).encode() + b"\r")
    wait_until(session, lambda: NOTIFY_HEAD in session.screen_text(),
               90, "自动起的回合把「%s」通知送进 scrollback" % NOTIFY_HEAD)
    wait_until(session, lambda: NOTIFY_TAIL in session.screen_text(),
               30, "通知末尾那句「%s」" % NOTIFY_TAIL)
    session.pump(1.0)
    print_screen("场景 3: 自动起回合 + 通知换行", session.screen.display)

    # 通知抬头本身是<b>用户块</b>（自动起的回合，文本以 user 消息进会话），带 › 提示符。
    head_row = find_row(session, NOTIFY_HEAD)
    if "›" not in session.screen.display[head_row]:
        die("通知抬头没以用户块形态回显（缺 › 提示符）：%r"
            % session.screen.display[head_row].rstrip(), session.screen.display)

    # 抬头下面依次是：空行、任务摘要行、三行结果。逐行核对 = 同时钉住「没塌行」与「顺序没乱」。
    task_ids = task_ids_on_screen(session)
    if not task_ids:
        die("屏幕上找不到任何 task id（通知摘要行该带它）", session.screen.display)
    assert_rows_below(session, head_row,
                      ["", task_ids[-1], JOB_LINE_1, JOB_LINE_2, JOB_LINE_3],
                      "后台完成通知")
    summary_row = session.screen.display[head_row + 2]
    if " · ✓" not in summary_row:
        die("通知摘要行没标成功（缺「· ✓」）：%r" % summary_row.rstrip(), session.screen.display)
    print("场景 3 OK：自动起回合，通知的三行结果落在三个连续物理行上（未塌成一行）.")

    # ⏱ 面板翻成「0 运行 · 1 完成」——证明完成状态真的回流到了 UI 镜像。
    title_row = find_row(session, BG_PANEL_PREFIX)
    if title_row < 0 or "(0 运行 · 1 完成)" not in session.screen.display[title_row]:
        die("⏱ 面板没翻成「0 运行 · 1 完成」，实际：%r"
            % (session.screen.display[title_row].strip() if title_row >= 0 else "<无面板>"),
            session.screen.display)
    print("场景 3 OK：⏱ 面板翻成「0 运行 · 1 完成」.")


# ── 场景 4：/tasks → k → 确认 → 已终止 ───────────────────────────────────
def check_kill_from_panel(session):
    """在 /tasks 面板里选中第二条、按 k、Enter 确认，那一条变成 ⊘ … 已终止。

    <b>先移到第二条再杀</b>：三条任务的文本只差末尾数字，杀第一条时「选中项」与
    「第一条」是同一行，选错了也看不出来。移一格，行号与内容就能互相印证。
    """
    session.write(b"/tasks\r")
    session.wait_for(TASKS_PANEL_TITLE, timeout=10)
    session.pump(0.6)

    anchor = find_row(session, TASKS_PANEL_TITLE)
    rows = [session.screen.display[anchor + i] for i in (1, 2, 3)]
    if not rows[0].lstrip().startswith("❯"):
        die("面板默认没高亮第一条：%r" % rows[0].rstrip(), session.screen.display)

    session.write(b"\x1b[B")               # ↓ 到第二条
    session.pump(0.6)
    anchor = find_row(session, TASKS_PANEL_TITLE)
    second = session.screen.display[anchor + 2]
    if not second.lstrip().startswith("❯"):
        die("↓ 之后高亮没落到第二条：%r" % second.rstrip(), session.screen.display)
    victim = TASK_ID_RE.search(second)
    if not victim:
        die("第二条上读不到 task id：%r" % second.rstrip(), session.screen.display)
    victim = victim.group(0)
    print("场景 4：选中第二条 %s，准备终止." % victim)

    session.write(b"k")
    wait_until(session, lambda: KILL_CONFIRM in session.screen_text(),
               8, "按 k 后弹出终止确认")
    session.pump(0.4)
    print_screen("场景 4: 终止确认（列表被替换成一行确认）", session.screen.display)
    confirm_row = session.screen.display[find_row(session, KILL_CONFIRM_PANEL)]
    if victim not in confirm_row:
        die("确认行里的 id 不是选中的那条（应为 %s）：%r" % (victim, confirm_row.rstrip()),
            session.screen.display)
    # 确认态<b>替换</b>整个列表：另外两条任务行此时不该还在屏上。
    if any(ln.lstrip().startswith("▶ task_") for ln in session.screen.display):
        die("确认态没把列表替换掉（还看得见任务行），「要确认什么」会淹没在列表里",
            session.screen.display)

    session.write(ENTER)
    # 断言<b>状态栏那句唯一的反馈</b>，而不是光看「已终止」三个字——后者也出现在任务行里，
    # 而任务行的刷新与状态栏是两条路径，只匹配子串会让其中一条坏掉也照样绿。
    wait_until(session, lambda: ("已终止后台任务 " + victim) in session.screen_text(),
               10, "状态栏回显「已终止后台任务 %s」" % victim)
    session.pump(0.6)
    print_screen("场景 4: 终止之后", session.screen.display)

    anchor = find_row(session, TASKS_PANEL_TITLE)
    killed_row = session.screen.display[anchor + 2]
    if victim not in killed_row:
        die("被杀的那条不在原位（第二行）了：%r" % killed_row.rstrip(), session.screen.display)
    if "⊘" not in killed_row or KILLED_MARK not in killed_row:
        die("被杀的那条没变成「⊘ … 已终止」：%r" % killed_row.rstrip(), session.screen.display)
    # 另外两条必须<b>还在跑</b>——杀一条把全部带走是最坏的形态，而这一条只有多任务时能证。
    others = [session.screen.display[anchor + 1], session.screen.display[anchor + 3]]
    for ln in others:
        if "▶" not in ln:
            die("终止一条时把别的任务也带走了：%r" % ln.rstrip(), session.screen.display)
    print("场景 4 OK：%s 变成「⊘ … 已终止」，另外两条仍在运行." % victim)

    session.write(ESC)
    session.pump(0.4)


def main():
    srv, base_url = start_stub()
    print("桩模型在 %s" % base_url)

    # 会话 A（24 行小窗口）：空态 → 三个长任务 → 输入框可见性 → 面板终止。
    # <b>行数是 24（关键），列数照旧 120</b>：本任务的疑点是「面板会不会把输入框顶出屏幕」，
    # 那是<b>行</b>的账。列压窄只会把状态行尾部截掉（那是设计好的），测不出任何东西。
    session, _ = launch(base_url, 24, 120, "codetui-bg-smoke-a-")
    try:
        check_tasks_panel_empty(session)
        check_background_panel_and_input_visible(session)
        check_kill_from_panel(session)
        quit_app(session)
    finally:
        session.close()

    # 会话 B（40 行）：完整看一遍自动送达那一整块通知文本。
    session, _ = launch(base_url, 40, 120, "codetui-bg-smoke-b-")
    try:
        check_auto_turn_wraps_lines(session)
        quit_app(session)
    finally:
        session.close()
        srv.shutdown()

    print("桩模型收到的请求：%s" % StubModel.requests)
    print("SMOKE PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())

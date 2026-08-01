#!/usr/bin/env python3
"""PTY smoke test for the permission layer (期 1 + 期 2).

Drives the real app on a pseudo-terminal and verifies the things no
in-process test can reach:

  1. Shift+Tab (ESC[Z) really routes to the permission-mode cycle through the
     full JLine backend + EventParser + CodeTuiView chain, and the status bar
     shows the new mode.  期 2 起循环是三档
     (DEFAULT -> ACCEPT_EDITS -> PLAN -> DEFAULT).  Also that the two
     pre-existing bare-Tab handlers (slash-menu completion, /mcp panel expand)
     still work AND no longer swallow Shift+Tab.
  2. /permissions prints its read-only report into the scrollback.
  3. The approval panel renders legibly under InlineDisplay: five options, one
     physical line each, foreground-only highlight (a background bar bleeds
     into the next line — see Theme.PICK_SEL).  Asserted on pyte's *attribute*
     buffer, not just the text.
  4. 期 2 的计划审批面板：正文进 scrollback / 面板只放三个选项 / 各占一个连续物理行 /
     高亮纯前景 / 选「批准」后模式真的切档（= 阻塞的工具线程被唤醒了）/
     「继续完善计划」的反馈只落在面板里而不是主输入框。
  5. 装饰符的列宽（⏸ ⏵ 📋 ❯）与 PLAN 档状态行的单行布局——应用用自己的 CharWidth 排版，
     终端用另一张表，两边的分歧只有 pty 看得见（离屏 Buffer 单测两边共用同一张表）。

To reach (3) without a live model we point DEEPSEEK_BASE_URL at a local stub
that speaks the OpenAI/DeepSeek SSE dialect and emits a `Bash(git push …)`
tool call on demand.  That exercises the real chain end-to-end:
tool call -> PermissionCallback -> engine decides ASK -> the tool thread
*blocks* on the ArrayBlockingQueue -> modal queue -> panel -> the answer wakes
the thread.  The blocking handshake is the whole point of the feature and is
the part unit tests can only simulate.

Usage:
    mvn -q -pl springai-code-tui package -DskipTests
    mvn -q -pl springai-code-tui dependency:build-classpath \
        -Dmdep.outputFile=target/cp.txt
    /usr/bin/python3 scripts/permission_smoke.py

Exit code 0 + "SMOKE PASS" on success, non-zero + "SMOKE FAIL: <reason>"
otherwise.  Failures dump the last rendered screen for a human to eyeball.
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

SHIFT_TAB = b"\x1b[Z"
TAB = b"\t"
ESC = b"\x1b"
CTRL_U = b"\x15"
BACKSPACE = b"\x7f"

MODE_DEFAULT = "权限模式：默认"
MODE_ACCEPT = "权限模式：自动接受编辑"
MODE_PLAN = "权限模式：计划模式"
# The persistent status-bar tag (CodeTuiView#modeTag) — distinct wording from the
# transient notice above precisely so a test cannot confuse the two.
MODE_TAG_ACCEPT = "⏵⏵ 自动接受编辑"
MODE_TAG_PLAN = "⏸ 计划模式"
PERM_BOTTOM_LINE = "内置底线"
MCP_PANEL_TITLE = "MCP 服务器"
MCP_EXPANDED = "（未连接，无工具信息）"
PANEL_TITLE = "⚠ 需要授权：Bash"
PANEL_WAITING = "⏸ 等待授权"
OPT_ONCE = "1. 允许一次"
OPT_SESSION = "2. 允许，本会话不再问"
OPT_ALWAYS = "3. 允许，永久"
OPT_DENY = "4. 拒绝，让模型换个做法"
OPT_CANCEL = "5. 拒绝并中断本回合"
SUGGESTED_LINE = "允许后将记下规则"
# 三选项形态下取代 SUGGESTED_LINE 的说明行（见 CodeTuiView#permissionChildren）
MISSING_OPTS_NOTE = "这类调用不提供"

# The 3-option form: built-in danger checks produce PermissionDecision.askOnly
# (suggested() == null), so the two "remember this" options must be hidden —
# no allow rule can silence the built-in floor, showing them would be a lie.
PANEL_TITLE_SSH = "⚠ 需要授权：Write"
OPT3_DENY = "2. 拒绝，让模型换个做法"
OPT3_CANCEL = "3. 拒绝并中断本回合"

# 计划审批面板（期 2）。正文进 scrollback，面板只放标题 + 三个选项。
PLAN_PANEL_TITLE = "📋 计划待批准"
PLAN_OPT_ACCEPT = "1. 批准，自动接受编辑"
PLAN_OPT_DEFAULT = "2. 批准，逐个确认"
PLAN_OPT_KEEP = "3. 继续完善计划"
PLAN_BODY_HEAD = "测试计划"     # 计划正文的标题行（应出现在 scrollback，不在面板里）
PLAN_BODY_STEP = "第一步"       # 正文的一条，用来证明正文真的下沉了
PLAN_APPROVED = "✓ 已批准计划 · 自动接受编辑"   # finishPlan 下沉的确认行
PLAN_FEEDBACK_HINT = "Esc 返回选项"             # 自由文本子模式的提示行

# The stub model emits a tool call only when the user's message carries one of
# these markers, so "chat normally" turns stay panel-free.
PUSH_MARKER = "PUSHNOW"      # -> Bash(git push …)   : 5-option form
SSH_MARKER = "SSHNOW"        # -> Write(.ssh/config) : 3-option form (built-in floor)
PLAN_MARKER = "PLANNOW"      # -> ExitPlanMode(plan) : 计划审批面板
DENIED_REPLY = "好的，我不推送了。"
PLAIN_REPLY = "冒烟回复：一切正常。"
PLAN_TEXT = "# 测试计划\n\n- 第一步\n- 第二步"

MODEL_ID = "deepseek-v4-pro"


# ── stub model server ────────────────────────────────────────────────────
def _sse(payload):
    return ("data: " + json.dumps(payload, ensure_ascii=False) + "\n\n").encode()


def _chunk(delta, finish=None):
    return {
        "id": "smoke-1",
        "object": "chat.completion.chunk",
        "created": 1,
        "model": MODEL_ID,
        "choices": [{"index": 0, "delta": delta, "finish_reason": finish}],
    }


class StubModel(BaseHTTPRequestHandler):
    """Minimal DeepSeek-compatible /chat/completions SSE endpoint.

    Routing is driven by the *last* message so the script never has to count
    turns: a tool result -> plain text; a user message carrying PUSH_MARKER ->
    a `Bash(git push origin main)` tool call; anything else -> plain text.
    """

    requests = []          # (role_of_last_message, kind_of_reply) for assertions
    lock = threading.Lock()

    def log_message(self, fmt, *args):   # silence stderr noise into the pty
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

        # Only the *last line* counts: the session layer folds consecutive
        # same-role messages, so an older "…PUSHNOW" user turn would otherwise
        # keep re-triggering the tool call forever.
        tail = (content.strip().splitlines() or [""])[-1]

        if role == "tool":
            kind, chunks = "text", self._text_chunks(DENIED_REPLY)
        elif PUSH_MARKER in tail:
            kind, chunks = "bash_call", self._tool_call_chunks(
                "Bash", {"command": "git push origin main"})
        elif PLAN_MARKER in tail:
            # 计划模式的唯一出口：模型提交一份 markdown 计划等待人批准。
            kind, chunks = "plan_call", self._tool_call_chunks(
                "ExitPlanMode", {"plan": PLAN_TEXT})
        elif SSH_MARKER in tail:
            # Relative path: the app resolves it against cwd (the temp dir), so
            # the `.ssh` path segment is what trips the built-in check — nothing
            # outside the sandbox is ever named, let alone written.
            kind, chunks = "write_call", self._tool_call_chunks(
                "Write", {"filePath": ".ssh/config", "content": "Host smoke\n"})
        else:
            kind, chunks = "text", self._text_chunks(PLAIN_REPLY)

        with StubModel.lock:
            StubModel.requests.append((role, kind))

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        for c in chunks:
            self.wfile.write(c)
            self.wfile.flush()
        self.wfile.write(b"data: [DONE]\n\n")
        self.wfile.flush()

    @staticmethod
    def _text_chunks(text):
        out = [_sse(_chunk({"role": "assistant", "content": ""}))]
        out.append(_sse(_chunk({"content": text})))
        out.append(_sse(_chunk({}, finish="stop")))
        return out

    @staticmethod
    def _tool_call_chunks(name, args):
        call = {
            "index": 0,
            "id": "call_smoke_%d" % len(StubModel.requests),
            "type": "function",
            "function": {"name": name, "arguments": json.dumps(args)},
        }
        return [
            _sse(_chunk({"role": "assistant", "content": "", "tool_calls": [call]})),
            _sse(_chunk({}, finish="tool_calls")),
        ]


def start_stub():
    srv = ThreadingHTTPServer(("127.0.0.1", 0), StubModel)
    t = threading.Thread(target=srv.serve_forever, daemon=True)
    t.start()
    return srv, "http://127.0.0.1:%d" % srv.server_address[1]


# ── screen helpers ───────────────────────────────────────────────────────
def find_row(session, needle):
    """Index of the first screen row containing `needle`, or -1."""
    for i, line in enumerate(session.screen.display):
        if needle in line:
            return i
    return -1


def row_backgrounds(session, y):
    """Distinct background colours present on screen row `y` (pyte attrs)."""
    row = session.screen.buffer[y]
    return {row[x].bg for x in range(session.screen.columns)}


def self_test_bg_detection():
    """Prove row_backgrounds() can actually see a background colour.

    Without this the bleed assertion in check_panel_layout() would be a test
    that cannot fail: if pyte ever stopped reporting `bg`, every row would
    look like {"default"} and the check would go permanently green.
    """
    import pyte
    scr = pyte.Screen(20, 3)
    stream = pyte.ByteStream(scr)
    stream.feed(b"plain\r\n\x1b[41mred bar\x1b[0m\r\n")

    class _Fake:
        pass
    fake = _Fake()
    fake.screen = scr
    if row_backgrounds(fake, 0) != {"default"}:
        die("self-test: 无背景色的行被误判为有背景色")
    if "red" not in row_backgrounds(fake, 1):
        die("self-test: pyte 背景色探测失效（ESC[41m 行读不到 red），串色断言会永远绿")
    print("Self-test OK: 背景色探测有效（能区分 default 与 red）.")


def probe_char_widths():
    """报告几个装饰符在终端侧占几列（诊断用，不做断言）。

    应用自己用 {@code CharWidth} 排版，终端用它自己的宽度表——两边不一致就会整行错位。
    离屏 Buffer 的单测两边用的是<b>同一张表</b>，故这个分歧只有 pty 看得见。
    列宽 > 2 或读不出来直接判失败（那说明探测本身坏了，后面的单行断言会变成不会失败的测试）。

    <b>已实测到的一处分歧</b>（2026-08-01）：📋 U+1F4CB 在应用侧 {@code CharWidth.of}=2、
    pyte 侧=1。无害且刻意不改：它只出现在面板/todo 标题这种<b>左对齐、无补白算式</b>的短行上，
    最坏是整行右移一列，不会折行（下面的行号连续性断言正是这条的守卫）。真要换窄符号，
    先确认它没被卷进任何按显示宽度补白/截断的计算（如 userBlock 的灰底补齐）。
    ⏸ / ⏵ / ❯ 两边都是 1 列，无分歧。
    """
    import pyte
    for ch, name in (("⏸", "U+23F8 计划模式标识"),
                     ("⏵", "U+23F5 自动接受编辑标识"),
                     ("📋", "U+1F4CB 计划面板标题"),
                     ("❯", "U+276F 高亮箭头")):
        scr = pyte.Screen(10, 1)
        pyte.ByteStream(scr).feed((ch + "X").encode())
        row = scr.display[0]
        col = row.find("X")
        if col < 1 or col > 2:
            die("宽度探测失效：%s 后的 X 落在列 %d（行=%r）" % (ch, col, row))
        print("宽度探测: %s %s → 终端按 %d 列渲染." % (ch, name, col))


def check_status_line_single_row(session, tag):
    """状态行必须仍是<b>一个物理行</b>：装饰符若被终端按双宽渲染，接近满宽的状态行会被挤到折行。

    两条一起看才有鉴别力：① 标识所在行的下一行必须是空的（折行会把尾巴顶下去）；
    ② 行尾的「Ctrl+C 退出」仍在同一行上（挤掉尾部同样是排版破了，只是方向相反）。
    """
    y = find_row(session, tag)
    if y < 0:
        die("状态行上找不到 %r" % tag, session.screen.display)
    row = session.screen.display[y]
    if "Ctrl+C 退出" not in row:
        die("状态行尾部被挤掉了（%r 不在同一物理行上）：%r" % ("Ctrl+C 退出", row.rstrip()),
            session.screen.display)
    below = session.screen.display[y + 1] if y + 1 < len(session.screen.display) else ""
    if below.strip():
        die("状态行下面还有内容 %r —— 疑似被折行（%r 的列宽与应用的排版不一致）"
            % (below.strip(), tag), session.screen.display)


def wait_until(session, pred, timeout, what):
    deadline = time.time() + timeout
    while time.time() < deadline:
        session.pump(0.2)
        if pred():
            return True
        if session.proc.poll() is not None:
            die("process exited early (code=%s) while waiting for %s"
                % (session.proc.returncode, what), session.screen.display)
    die("timed out waiting for %s" % what, session.screen.display)


def assert_absent(session, needle, why):
    if needle in session.screen_text():
        die("%s: %r should NOT be on screen" % (why, needle), session.screen.display)


def last_mode_line(session):
    """Last 「权限模式：X（Shift+Tab 循环切换）」 line printed by /permissions."""
    hit = None
    for line in session.screen.display:
        if "权限模式：" in line and "循环切换" in line:
            hit = line.strip()
    return hit


def restore_default_mode(session):
    """从 ACCEPT_EDITS 循环回 DEFAULT（期 2 起是三档，要按<b>两下</b>）并核实。

    <b>不能</b>用 {@code wait_for(MODE_DEFAULT)} 核实：屏幕上一旦留有 /permissions 报告
    （"权限模式：默认（Shift+Tab 循环切换）"），这句 wait 就会命中那条<b>陈旧的 scrollback</b>
    而立刻返回——期 1 的两档写法正是这样在三档下静默变绿，把后面的场景带进了错档
    （实测：check_mcp_tab_guard 因此拿到 PLAN 而非 DEFAULT）。走一次报告才是真凭据。
    """
    session.write(SHIFT_TAB)                 # ACCEPT_EDITS -> PLAN
    session.pump(0.4)
    session.write(SHIFT_TAB)                 # PLAN -> DEFAULT
    session.pump(0.4)
    assert_mode_via_report(session, "默认")


def assert_mode_via_report(session, label):
    """Run /permissions and assert the report shows `label` as the live mode.

    Needed because the status-bar notice is *not* visible while a menu or
    panel owns the status line (see statusLine(): slashMenuActive/pickingMcp
    are checked before state.notice()).  The report is the only user-visible
    proof that a Shift+Tab pressed inside a menu really cycled the mode.
    Callers must alternate labels so this can't pass on a stale line.
    """
    session.write(b"/permissions\r")
    wait_until(session, lambda: (last_mode_line(session) or "").startswith("权限模式：" + label),
               8, "/permissions 报告显示「%s」（实际：%s）" % (label, last_mode_line(session)))


# ── scenarios ────────────────────────────────────────────────────────────
def check_mode_cycle(session):
    """Shift+Tab cycles DEFAULT -> ACCEPT_EDITS -> PLAN -> DEFAULT (期 2 起是三档；
    no BYPASS: the app was started without --dangerously-skip-permissions)."""
    session.write(SHIFT_TAB)
    session.wait_for(MODE_ACCEPT)
    assert_absent(session, "跳过权限检查", "BYPASS 不该出现在无启动参数的循环里")
    print("Shift+Tab OK: 切到「自动接受编辑」.")

    session.write(SHIFT_TAB)
    session.wait_for(MODE_PLAN)
    assert_absent(session, "跳过权限检查", "BYPASS 不该出现在无启动参数的循环里")
    print("Shift+Tab OK: 第二下到「计划模式」（期 2 新增的第三档）.")

    session.write(SHIFT_TAB)
    session.wait_for(MODE_DEFAULT)
    assert_absent(session, "跳过权限检查", "BYPASS 不该出现在无启动参数的循环里")
    print("Shift+Tab OK: 第三下回「默认」（三档闭环）.")

    # 再走一整圈：确认循环是稳定的三档，而不是首圈碰巧对。
    for expect in (MODE_ACCEPT, MODE_PLAN, MODE_DEFAULT):
        session.write(SHIFT_TAB)
        session.wait_for(expect)
        assert_absent(session, "跳过权限检查", "BYPASS 不该出现在无启动参数的循环里")
    print("Shift+Tab OK: 第二圈同样是 默认→自动接受编辑→计划模式→默认（BYPASS 未混入）.")


def check_mode_indicator_persists(session):
    """The mode must stay visible after the switch notice is gone.

    Before this indicator existed the mode was only ever shown in three places
    that all disappear: the Shift+Tab notice (wiped by the very next keypress),
    the /permissions report and the BYPASS launch banner (both scroll away).
    So the assertion that matters is not "the notice appeared" — it is "the
    mode is still on screen once the notice is provably gone".
    """
    session.write(SHIFT_TAB)
    session.wait_for(MODE_ACCEPT)                    # transient notice
    session.write(b"x")                              # any key wipes the notice
    session.wait_for(MODE_TAG_ACCEPT)
    assert_absent(session, MODE_ACCEPT,
                  "notice 还在，这条断言就没证明「常驻标识」而只是又看了一遍 notice")
    print("常驻标识 OK: notice 清掉后状态栏仍显示「%s」." % MODE_TAG_ACCEPT)

    session.write(CTRL_U)                            # drop the typed 'x'
    session.write(SHIFT_TAB)                         # ACCEPT_EDITS -> PLAN
    session.wait_for(MODE_PLAN)
    session.write(b"x")
    session.wait_for(MODE_TAG_PLAN)
    assert_absent(session, MODE_PLAN,
                  "notice 还在，这条断言就没证明「常驻标识」而只是又看了一遍 notice")
    assert_absent(session, MODE_TAG_ACCEPT, "上一档的标识没被换掉")
    print("常驻标识 OK: notice 清掉后状态栏仍显示「%s」." % MODE_TAG_PLAN)

    # ⏸ (U+23F8) 的列宽在各终端不一致，而状态行本就接近终端宽度：挤位就会折行、撑破单行布局。
    # 只有 pty 能证伪这件事（离屏 Buffer 用的是应用自己的 CharWidth，两边一起错就一起绿）。
    check_status_line_single_row(session, MODE_TAG_PLAN)
    # 冷薄荷 MODE_PLAN（indexed 115）此前只验过 Style 对象相等——这里验它在真实屏幕上不带背景色，
    # 并把整屏打出来供人眼复核对比度。
    y = find_row(session, MODE_TAG_PLAN)
    bgs = row_backgrounds(session, y)
    if bgs != {"default"}:
        die("计划模式状态行带了背景色 %s（底色会串到下一行，见 Theme.PICK_SEL）" % sorted(bgs),
            session.screen.display)
    print_screen("PLAN MODE STATUS LINE (冷薄荷对比度，人眼复核)", session.screen.display)
    print("计划模式标识 OK: 单物理行、尾部未被挤掉、无背景色残留.")

    session.write(CTRL_U)
    session.write(SHIFT_TAB)                         # PLAN -> DEFAULT
    session.wait_for(MODE_DEFAULT)
    session.write(b"x")
    wait_until(session, lambda: MODE_TAG_PLAN not in session.screen_text(),
               8, "切回默认后标识应消失（常态不占位），实际仍在屏上")
    assert_absent(session, MODE_TAG_ACCEPT, "切回默认后不该残留任何模式标识")
    session.write(CTRL_U)
    print("常驻标识 OK: 切回「默认」后标识消失、不粘住.")


def check_plan_approval(session):
    """计划审批面板端到端（期 2）：PLAN 档下模型调 ExitPlanMode → 正文进 scrollback、
    面板弹三个选项 → 选「1」唤醒工具线程并把模式切到 ACCEPT_EDITS。

    这条走的是完整链路：tool call → PlanApprovalBridge 阻塞握手 → 模态队列 → 面板 →
    应答唤醒线程 → engine.setMode。<b>模式真的变了</b>是「工具线程被唤醒」唯一的用户可见证据
    （工具返回值只回给模型，不进屏幕）。

    Enters with mode=DEFAULT, leaves with mode=DEFAULT.
    """
    session.write(SHIFT_TAB)
    session.wait_for(MODE_ACCEPT)
    session.write(SHIFT_TAB)
    session.wait_for(MODE_PLAN)

    session.write(("先出个方案 " + PLAN_MARKER).encode() + b"\r")
    session.wait_for(PLAN_PANEL_TITLE, timeout=40)
    session.pump(0.6)
    print_screen("PLAN APPROVAL PANEL (as rendered)", session.screen.display)

    text = session.screen_text()
    # 正文在 scrollback、选项在面板：几十行的计划塞进行内面板会把输入框顶出屏幕。
    for needle in (PLAN_BODY_HEAD, PLAN_BODY_STEP, "第二步"):
        if needle not in text:
            die("计划正文没进 scrollback（缺 %r）" % needle, session.screen.display)
    for needle in (PLAN_OPT_ACCEPT, PLAN_OPT_DEFAULT, PLAN_OPT_KEEP):
        if needle not in text:
            die("计划面板缺少 %r" % needle, session.screen.display)
    if "1-3 快选" not in text:
        die("状态栏没有「1-3 快选」提示", session.screen.display)

    # 标题 + 三个选项各占一个<b>连续</b>物理行。这条钉的是「一个 println = 一个物理行」，
    # 也顺带钉住 📋 / ❯ 的列宽：任一处折行都会把行号打散。
    rows = [find_row(session, n) for n in
            (PLAN_PANEL_TITLE, PLAN_OPT_ACCEPT, PLAN_OPT_DEFAULT, PLAN_OPT_KEEP)]
    if rows != [rows[0] + i for i in range(4)]:
        die("面板标题 + 三选项没有各占一个连续物理行（行号=%s），疑似折行/塌行" % rows,
            session.screen.display)
    # 正文必须在面板<b>上方</b>的 scrollback 里，而不是被塞进面板
    body_row = find_row(session, PLAN_BODY_STEP)
    if body_row >= rows[0]:
        die("计划正文（行 %d）没有排在面板（行 %d）上方，疑似被塞进了面板"
            % (body_row, rows[0]), session.screen.display)

    sel_row = find_row(session, "❯ 1. 批准，自动接受编辑")
    if sel_row < 0:
        die("默认高亮不在第一项", session.screen.display)
    for y, why in ((sel_row, "高亮行"), (sel_row + 1, "高亮行的下一行")):
        bgs = row_backgrounds(session, y)
        if bgs != {"default"}:
            die("%s带了背景色 %s —— 底色条会串到下一行（见 Theme.PICK_SEL）" % (why, sorted(bgs)),
                session.screen.display)
    print("计划面板 OK: 正文在 scrollback、标题+三选项各占一连续行、高亮纯前景、状态栏 1-3 快选.")

    # 选「1」：应答 → 工具线程醒 → engine 切到 ACCEPT_EDITS。
    session.write(b"1")
    wait_until(session, lambda: "❯ 1. 批准，自动接受编辑" in session.screen_text(),
               5, "数字 1 把高亮移到第 1 项")
    session.write(b"\r")
    wait_until(session, lambda: PLAN_PANEL_TITLE not in session.screen_text(),
               10, "计划面板在确认后关闭")
    session.wait_for(PLAN_APPROVED, timeout=20)
    session.write(b"x")                               # 清掉 notice，让常驻标识露出来
    session.wait_for(MODE_TAG_ACCEPT, timeout=20)
    session.write(CTRL_U)
    print("计划批准 OK: 面板收起、下沉确认行、模式真的切到「自动接受编辑」"
          "（= 阻塞的工具线程确实被唤醒了）.")

    # 子模式：选「3」先收一段反馈。反馈必须落在面板里，不能落进主输入框。
    session.write(SHIFT_TAB)                          # ACCEPT_EDITS -> PLAN
    session.wait_for(MODE_PLAN)
    session.write(("再改改 " + PLAN_MARKER).encode() + b"\r")
    session.wait_for(PLAN_PANEL_TITLE, timeout=40)
    session.pump(0.5)

    session.write(b"3")
    wait_until(session, lambda: "❯ 3. 继续完善计划" in session.screen_text(),
               5, "数字 3 把高亮移到第 3 项")
    session.write(b"\r")
    session.wait_for(PLAN_FEEDBACK_HINT)
    if PLAN_OPT_ACCEPT in session.screen_text():
        die("进了子模式却还显示三个选项（面板没换形态）", session.screen.display)

    sentinel = "补上回滚"
    session.write(sentinel.encode())
    session.wait_for(sentinel)
    session.pump(0.4)
    print_screen("PLAN FEEDBACK SUB-MODE (as rendered)", session.screen.display)
    hits = [i for i, line in enumerate(session.screen.display) if sentinel in line]
    if len(hits) != 1:
        die("反馈文本出现了 %d 次（行=%s）—— 多半是同时落进了主输入框" % (len(hits), hits),
            session.screen.display)
    if "│" in session.screen.display[hits[0]]:
        die("反馈文本落进了输入框（那一行带圆角边框）", session.screen.display)

    session.write(b"\r")
    wait_until(session, lambda: PLAN_PANEL_TITLE not in session.screen_text(),
               10, "提交反馈后面板收起")
    session.wait_for("继续完善计划", timeout=20)      # finishPlan 下沉的确认行
    print("计划反馈子模式 OK: 反馈只落在面板里（主输入框为空）、Enter 提交后面板收起.")

    session.write(SHIFT_TAB)                          # PLAN -> DEFAULT
    session.wait_for(MODE_DEFAULT)
    session.write(b"x")
    session.pump(0.3)
    session.write(CTRL_U)


def check_permissions_report(session):
    session.write(b"/permissions\r")
    session.wait_for(PERM_BOTTOM_LINE)
    text = session.screen_text()
    for needle in ("权限模式：默认（Shift+Tab 循环切换）", "当前没有自定义规则"):
        if needle not in text:
            die("/permissions 报告缺少 %r" % needle, session.screen.display)
    print("/permissions OK: 模式 + 规则 + 内置底线三段都在.")


def check_slash_tab_guard(session):
    """Bare Tab still completes in the slash menu; Shift+Tab does not.

    Enters with mode=DEFAULT, leaves with mode=DEFAULT.
    """
    # "/model" is on screen regardless — it sits in the welcome banner, the
    # idle status line AND the menu row — so `wait_for("/model")` after Tab is
    # a false positive.  Typing a sentinel char right after Tab is what makes
    # the assertion discriminating: completed input reads "/modelx", an
    # ignored Tab reads "/modx".  (Tab completion was dead in the shipped app
    # until c8d933c with every unit test green — this must not go quiet again.)
    session.write(b"/mod")
    session.wait_for("❯ /model")

    # Shift+Tab must not be eaten by the completion handler.
    session.write(SHIFT_TAB)
    session.write(b"x")
    session.wait_for("/modx")
    assert_absent(session, "/modelx", "Shift+Tab 在斜杠菜单里被当成了补全键")
    print("斜杠菜单：Shift+Tab 没有触发补全（输入仍是 /modx）.")

    session.write(BACKSPACE)
    session.wait_for("❯ /model")
    session.write(TAB)
    session.write(b"x")
    session.wait_for("/modelx")
    print("斜杠菜单：裸 Tab 仍然补全（输入变成 /modelx，旧行为未回归）.")
    session.write(CTRL_U)
    session.pump(0.3)

    # …and the Shift+Tab above must actually have reached the mode cycle.  The
    # status-bar notice is hidden while the menu owns the status line, so the
    # /permissions report is the only user-visible proof.
    assert_mode_via_report(session, "自动接受编辑")
    print("斜杠菜单：Shift+Tab 确实切了模式（/permissions 报告为准）.")

    restore_default_mode(session)                   # 三档：要按两下才回到 DEFAULT


def check_mcp_tab_guard(session):
    """Bare Tab still expands the /mcp row; Shift+Tab cycles the mode instead.

    Enters with mode=DEFAULT, leaves with mode=DEFAULT.
    """
    session.write(b"/mcp\r")
    session.wait_for(MCP_PANEL_TITLE)
    session.pump(0.4)

    session.write(SHIFT_TAB)
    session.pump(0.8)
    assert_absent(session, MCP_EXPANDED, "Shift+Tab 在 MCP 面板里被当成了展开键")
    print("MCP 面板：Shift+Tab 没有触发展开（守卫生效）.")

    session.write(TAB)
    session.wait_for(MCP_EXPANDED)
    print("MCP 面板：裸 Tab 仍然展开工具清单（旧行为未回归）.")

    session.write(ESC)
    wait_until(session, lambda: MCP_PANEL_TITLE not in session.screen_text(),
               5, "Esc 关闭 MCP 面板")
    session.pump(0.3)
    assert_mode_via_report(session, "自动接受编辑")
    print("MCP 面板：Shift+Tab 确实切了模式（/permissions 报告为准）.")

    restore_default_mode(session)                   # 三档：要按两下才回到 DEFAULT


def check_panel_layout(session):
    """The five options render one-per-physical-line with a foreground-only
    highlight.  A background bar on the selected row is the known bleed bug."""
    text = session.screen_text()
    for needle in (OPT_ONCE, OPT_SESSION, OPT_ALWAYS, OPT_DENY, OPT_CANCEL, PANEL_WAITING):
        if needle not in text:
            die("审批面板缺少 %r" % needle, session.screen.display)

    # Header block: title / target / reason / suggested rule, one physical row
    # each and contiguous (a wrapped or collapsed row shifts these apart).
    head = [find_row(session, n) for n in
            (PANEL_TITLE, "git push origin main", "只读白名单", "允许后将记下规则")]
    if head != [head[0] + i for i in range(4)]:
        die("面板抬头四行不连续（行号=%s），疑似折行/塌行" % head, session.screen.display)

    rows = [find_row(session, n) for n in
            (OPT_ONCE, OPT_SESSION, OPT_ALWAYS, OPT_DENY, OPT_CANCEL)]
    if rows != sorted(rows) or len(set(rows)) != 5:
        die("五个选项没有各占一个物理行（行号=%s）" % rows, session.screen.display)
    if rows[-1] - rows[0] != 4:
        die("选项行不连续（行号=%s），疑似被折行或塌行" % rows, session.screen.display)

    sel_row = find_row(session, "❯ 1. 允许一次")
    if sel_row < 0:
        die("默认高亮不在第一项", session.screen.display)
    bgs = row_backgrounds(session, sel_row)
    if bgs != {"default"}:
        die("高亮行用了背景色 %s —— 底色条会串到下一行（见 Theme.PICK_SEL）" % sorted(bgs),
            session.screen.display)
    nxt = row_backgrounds(session, sel_row + 1)
    if nxt != {"default"}:
        die("高亮行的下一行沾到背景色 %s（串行）" % sorted(nxt), session.screen.display)
    print("面板渲染 OK: 五选项各占一行、高亮纯前景、下一行无串色.")


def check_highlight_moves(session):
    session.write(b"\x1b[B")                      # Down
    wait_until(session, lambda: "❯ 2. 允许，本会话不再问" in session.screen_text(),
               5, "↓ 把高亮移到第 2 项")
    if "❯ 1. 允许一次" in session.screen_text():
        die("↓ 之后第 1 项仍带 ❯（高亮没被清掉）", session.screen.display)
    session.write(b"4")                           # numeric quick-select: move only
    wait_until(session, lambda: "❯ 4. 拒绝，让模型换个做法" in session.screen_text(),
               5, "数字 4 把高亮移到第 4 项")
    if PANEL_TITLE not in session.screen_text():
        die("数字快选不该直接确认（面板提前关闭了）", session.screen.display)
    print("面板按键 OK: ↓ 移动高亮、数字只移动不确认.")


def check_deny_resumes_turn(session):
    """Enter on 「拒绝，让模型换个做法」 wakes the blocked tool thread with DENY
    and the turn continues — the stub replies once it sees the tool result."""
    session.write(b"\r")
    wait_until(session, lambda: PANEL_TITLE not in session.screen_text(),
               10, "审批面板在确认后关闭")
    session.wait_for(DENIED_REPLY, timeout=30)
    with StubModel.lock:
        roles = [r for r, _ in StubModel.requests]
    if "tool" not in roles:
        die("模型没有收到 tool 结果 —— 阻塞的工具线程没被唤醒（requests=%s）"
            % StubModel.requests, session.screen.display)
    print("DENY 握手 OK: 工具线程被唤醒、回合继续、模型收到 tool 结果并续答.")


def check_askonly_panel(session):
    """The 3-option form, reached through the real chain: a Write under `.ssh`
    trips the built-in floor, which yields askOnly (suggested() == null).

    Both facts matter — that the floor fires at all, and that the two
    "remember this" options are hidden (no allow rule can silence the floor,
    so offering them would be a lie).
    """
    session.write(("写个配置 " + SSH_MARKER).encode() + b"\r")
    session.wait_for(PANEL_TITLE_SSH, timeout=40)
    session.pump(0.6)
    print_screen("ASK-ONLY PANEL (3 options, built-in floor)", session.screen.display)

    text = session.screen_text()
    for needle in (OPT_ONCE, OPT3_DENY, OPT3_CANCEL):
        if needle not in text:
            die("askOnly 面板缺少 %r" % needle, session.screen.display)
    for forbidden in (OPT_SESSION, OPT_ALWAYS, SUGGESTED_LINE):
        if forbidden in text:
            die("askOnly 面板不该出现 %r（内置底线消不掉，显示出来就是骗人）" % forbidden,
                session.screen.display)
    if "1-3 快选" not in text:
        die("状态栏快选提示没有跟着选项数变成 1-3", session.screen.display)
    # 少了两项就必须说明为什么——否则面板看着像漏了选项（这是实地反馈来的困惑）。
    if MISSING_OPTS_NOTE not in text:
        die("askOnly 面板没有解释为什么少了「本会话 / 永久」两项", session.screen.display)

    rows = [find_row(session, n) for n in (OPT_ONCE, OPT3_DENY, OPT3_CANCEL)]
    if rows != [rows[0] + i for i in range(3)]:
        die("三个选项没有各占一个连续物理行（行号=%s）" % rows, session.screen.display)
    sel = find_row(session, "❯ 1. 允许一次")
    if row_backgrounds(session, sel) != {"default"} or \
            row_backgrounds(session, sel + 1) != {"default"}:
        die("askOnly 面板高亮用了背景色（会串到下一行）", session.screen.display)
    print("askOnly 面板 OK: 只有三项、无「永久/本会话」、有缺项说明、高亮纯前景.")


def check_cancel_turn(session):
    """Esc = 拒绝并中断本回合.  The turn ends and the *next* message must not
    blow up with a dangling-tool_calls 400.

    Entered with the askOnly panel open (see check_askonly_panel).
    """
    before = len(StubModel.requests)
    session.write(ESC)
    wait_until(session, lambda: PANEL_TITLE_SSH not in session.screen_text(),
               10, "Esc 关闭审批面板并中断回合")
    session.pump(1.0)

    session.write("中断之后再说一句".encode() + b"\r")
    session.wait_for(PLAIN_REPLY, timeout=30)
    text = session.screen_text()
    with StubModel.lock:
        after = StubModel.requests[before:]
    for bad in ("400", "Error", "错误"):
        if bad in text:
            die("中断后的下一条消息出错（屏幕含 %r；stub 请求=%s）" % (bad, after),
                session.screen.display)
    print("Esc 中断 OK: 回合结束、下一条消息正常（stub 收到 %d 次请求：%s）."
          % (len(after), after))


# ── main ─────────────────────────────────────────────────────────────────
def main():
    self_test_bg_detection()
    probe_char_widths()
    classpath = build_classpath()
    tmpdir = tempfile.mkdtemp(prefix="codetui-perm-smoke-")
    # Isolate the user layer: the real ~/.codetui/permissions.json would change
    # what /permissions prints and could pre-allow the very call we want asked.
    home_dir = os.path.join(tmpdir, "home")
    os.makedirs(home_dir, exist_ok=True)

    # One disabled MCP server so the /mcp panel has a row to expand with Tab.
    # Disabled = no npx, no network, no child process.
    cfg_dir = os.path.join(tmpdir, ".codetui")
    os.makedirs(cfg_dir, exist_ok=True)
    with open(os.path.join(cfg_dir, "mcp.json"), "w") as f:
        json.dump({"mcpServers": {"probe": {
            "command": "true", "args": [], "enabled": False}}}, f)

    srv, base_url = start_stub()
    print("Stub model on %s" % base_url)

    env = dict(os.environ)
    env["TERM"] = "xterm-256color"
    env["DEEPSEEK_API_KEY"] = "sk-dummy-not-real"
    env["DEEPSEEK_BASE_URL"] = base_url
    env["DEEPSEEK_MODELS"] = MODEL_ID
    # Don't let a developer's other keys add providers (and other default models).
    for k in ("ZHIPU_API_KEY", "DASHSCOPE_API_KEY", "ANTHROPIC_API_KEY", "OPENAI_API_KEY"):
        env.pop(k, None)

    cmd = ["java", "-Duser.home=%s" % home_dir, "-cp", classpath, MAIN_CLASS]
    print("Launching: %s" % " ".join(cmd))
    print("cwd=%s" % tmpdir)

    session = PtySession(cmd, tmpdir, env)
    try:
        session.wait_for(WELCOME_1, timeout=30)
        print("Startup OK.")

        check_mode_cycle(session)
        check_mode_indicator_persists(session)
        check_plan_approval(session)
        check_permissions_report(session)
        check_slash_tab_guard(session)
        check_mcp_tab_guard(session)

        # Real ASK end-to-end: the stub asks for `git push`, which is not in
        # the read-only whitelist, so the engine returns ASK and the tool
        # thread parks until the panel is answered.
        session.write(("帮我推送 " + PUSH_MARKER).encode() + b"\r")
        session.wait_for(PANEL_TITLE, timeout=40)
        session.pump(0.6)
        print_screen("APPROVAL PANEL (as rendered)", session.screen.display)
        check_panel_layout(session)
        check_highlight_moves(session)
        check_deny_resumes_turn(session)
        check_askonly_panel(session)
        check_cancel_turn(session)

        # The Write never ran: the turn was cancelled at the approval panel.
        if os.path.exists(os.path.join(tmpdir, ".ssh", "config")):
            die("Esc 中断后 Write 仍然落了盘 —— 工具在应答前就执行了")
        print("中断后 .ssh/config 未被写入（拦截确实在执行之前）.")

        session.write(b"/exit\r")
        deadline = time.time() + 10
        while time.time() < deadline and session.proc.poll() is None:
            session.pump(0.2)
        if session.proc.poll() is None:
            die("/exit 后 10s 未退出（疑似有工具线程仍 park 着）", session.screen.display)
        print("/exit OK: %ds 内退出，无线程卡死." % 10)

        print("SMOKE PASS")
        return 0
    finally:
        session.close()
        srv.shutdown()


if __name__ == "__main__":
    sys.exit(main())

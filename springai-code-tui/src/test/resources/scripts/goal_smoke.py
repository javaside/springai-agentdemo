#!/usr/bin/env python3
"""「/goal 自主循环」的 PTY 实机冒烟：一轮 UNSAT→SAT 闭环 + 接线真凭据 + 落库存活网。

<b>这是整个功能唯一的端到端验证</b>（spec §12 步骤 4）。单测到不了四类东西：

  1. <b>接线本身</b>。`CodeTuiApplication` 里那行 `AgentTools.wireGoal(runtime, agent, goalRunner,
     goalEvaluator)` 有没有真接上（→ `CodingAgent.bindGoal` → `agent.goal()/goalRunner()/
     goalEvaluator()` 从 null 变实），以及 `CodeTuiView` 空闲批的 goal 槽（`goalSlotTick`）有没有
     真的发起自动轮与评估，<b>没有任何单测覆盖</b>——给 View/Agent 造 mock 只能证明 mock 接得上。
     wireGoal 断掉则 `agent.goal()` 恒 null：goal 槽 no-op、`/goal` 直接回「goal 未启用」，
     自动轮与评估一次都不会发生。
  2. <b>评估器路由</b>。`CODETUI_GOAL_EVALUATOR_MODEL=provider:model` 经
     `ProviderRegistry.requestSelection` 精确路由 + 每请求基础 options（含 modelId）——
     配置错了只会静默 warn + 回退 aux 底座，屏幕上什么也看不出来。
  3. <b>C1 落库存活</b>。评估结论标记（`[goal 评估]` 合成块）被中段插进会话事件表；
     若退回「尾部追加」形态，`CodingAgent.submit` 的 `foldTrailingUserIntoOutbound` 会把它
     折进出站文本并<b>从会话删除</b>——轨迹销毁 + 用户下一条消息被混成标记前缀。单测里
     没有「下一回合 submit」这个凶手，只有本脚本的完整回合循环杀得到它。
  4. <b>真终端里的状态栏指示与一行式总结</b>。离屏 Buffer 的单测两边共用同一套排版。

<b>真凭据 = 桩收到的请求体，不是屏幕</b>（interjection/quota 冒烟的同一条纪律）：

  * <b>主模型桩</b>收到的对话请求序列：≥3 次；带 `[goal 继续 1/` 前缀的请求 user 文本含
    「评估器结论（上一轮）：（首轮）」行，带 `[goal 继续 2/` 前缀的那次含第 1 次评估的
    REASON 原文——verdict 真的回流进了下一自动轮 prompt。wireGoal/goal 槽断掉则 `[goal 继续`
    请求一次都不会发生（只有用户 kickoff 那些次）。
  * <b>评估器桩</b>收到 ≥2 次请求，user 文本含「目标条件：」「轮次：」——评估器接线的唯一证据
    （屏幕上的「评估中」是 UI 自己画的）。

<b>每个 goal 自动轮必须带一次工具调用（Glob，READ_ONLY 自动放行）</b>：一来逼出「assistant
(tool_calls) → tool 结果 → assistant 收尾」的真实轮形状——落库标记的中段插入点在 tool 结果与
收尾 assistant 之间，这正是 C1 修复钉住的<b>合法形态</b>（终审报告明说零工具轮 `[user,
assistant]` 会让标记插出「磁盘相邻双 user」的已知良性边沿，repo 的测试纪律是全用真实工具轮
形状）；二来顺带钉住「评估素材的 toolCallCount>0」口径（零工具会被 stalled 熔断当机器信号，
advancing 也会 +1 连击）。

<b>没有真实 key 怎么跑</b>：照 quota_wait_smoke.py 的办法——脚本内起一个说 OpenAI 方言的桩，
`ZHIPU_BASE_URL` <b>必须显式覆盖</b>为本机桩地址（本机环境变量里的 ZHIPU_BASE_URL 是真实
Coding Plan 端点，不覆盖就打真网烧配额）。主模型与评估器<b>同 provider 不同 modelId</b>：
`ZHIPU_MODELS=glm-5.3,glm-5.3-eval`（首项=主对话默认模型），`CODETUI_GOAL_EVALUATOR_MODEL=
zhipu:glm-5.3-eval` 指到第二项——桩按请求体 `model` 字段路由：

  | model 字段        | 方言              | 应答                                                  |
  |-------------------|-------------------|-------------------------------------------------------|
  | glm-5.3           | 流式 SSE（主链）  | 固定一段「本轮做了工作」文本                           |
  | glm-5.3-eval      | 非流式 JSON       | 第 1 次 UNSATISFIED 四标记行 → 第 2 次 SATISFIED       |

评估器是 `ChatClient...call()`（非流式）→ openai-java sync client，桩必须回
`application/json` 的 chat.completion（不是 SSE）；主链是流式 → SSE。HTTP/1.1 + SSE 发完
`[DONE]` 关连接（quota 头注的保真要求，照抄）。

<b>C1 落库断言</b>（本脚本最重要的一组）：会话文件在 `<cwd>/.codetui/sessions/<sessionId>.json`
（FileSessionRepository 按 `user.dir` 落盘，与 user.home 无关；user.home 只隔离日志/用户配置）。
断言：① 存在 `[goal 评估]` 标记事件（UNSAT 与 SAT 各一）；② 标记事件<b>不是尾事件</b>且
<b>后一条恰是 ASSISTANT</b>（中段插入的契约形状）；③ 每个含 `[goal 评估]` 的事件都是<b>独立
合成块</b>（strip 后以 `[goal 评估]` 开头、`[/goal 评估]` 结尾、闭合标记后无残余文本）——
fold 销毁（标记消失）与 fold 混入（标记文本被折进别的 user 事件）两个方向的回归都会红。

覆盖的场景（gap 用 `CODETUI_GOAL_TURN_GAP_SECONDS=1` 缩短等待）：

  | 步骤 | 操作                              | 期望                                                          |
  |------|-----------------------------------|---------------------------------------------------------------|
  | 1    | 发 kickoff 消息                   | 主桩收第 1 次对话请求（纯文本回复）；回复落地                   |
  | 2    | `/goal <条件>`                    | 屏出「◎ goal 已设定」；状态行出现 `◎ goal` 指示               |
  | 3    | （自动）首轮                      | 主桩收到 `[goal 继续 1/` 请求 → Glob 工具调用 → 工具结果收尾    |
  | 4    | （自动）评估 #1                   | 评估桩第 1 次请求；UNSATISFIED → 屏不出暂停                   |
  | 5    | （自动）gap 后第 2 轮             | 主桩收到 `[goal 继续 2/` 请求，user 含 UNSAT REASON 原文       |
  | 6    | （自动）评估 #2                   | SATISFIED → 屏出 `◎ goal 终态：SATISFIED` 一行式总结          |
  | 7    | 读会话文件                        | C1 三断言（存在/非尾/独立合成块）                              |
  | 8    | `/goal stop`                      | 终态守卫 notice（无事可清）；`/exit`                           |

运行前<b>必须重新 package</b>，否则跑的是旧 jar（假失败）：

    mvn -q -DskipTests install
    mvn -q -pl springai-code-tui package -DskipTests
    mvn -q -pl springai-code-tui dependency:build-classpath -Dmdep.outputFile=target/cp.txt
    /usr/bin/python3 springai-code-tui/src/test/resources/scripts/goal_smoke.py

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
from permission_smoke import wait_until  # noqa: E402

ENTER = b"\r"

# ── 模型身份：同 provider（zhipu）不同 modelId，桩按请求体 model 字段路由 ──────────
MAIN_MODEL = "glm-5.3"          # ZHIPU_MODELS 首项 = 主对话默认模型
EVAL_MODEL = "glm-5.3-eval"     # 第二项，CODETUI_GOAL_EVALUATOR_MODEL 指到它
PROVIDER = "zhipu"

# goal 配置（显式钉死，防开发机环境变量漂移）：6 轮上限足够本场景；gap 1s 缩短等待。
GOAL_MAX_TURNS = 6
GOAL_GAP_SECONDS = 1

KICKOFF_TEXT = "开始 GOALSMOKE 的前置准备"
GOAL_CONDITION = "把 GOALSMOKE 目标推进到完成"

MAIN_REPLY_1 = "冒烟回复：前置工作已完成。"
MAIN_REPLY = "冒烟回复：本轮已推进目标工作。"

# 评估器应答（四标记行协议，GoalVerdict.parse 逐行行首匹配）。
EVAL_REPLY_1 = (
    "VERDICT: UNSATISFIED\n"
    "REASON: 还没完成：步骤一刚做完，剩余步骤未动\n"
    "PROGRESS: advancing\n"
    "STATE: ✓步骤一 ✗步骤二 ✗验证"
)
EVAL_REPLY_2 = (
    "VERDICT: SATISFIED\n"
    "REASON: 目标达成：全部步骤完成且验证通过\n"
    "PROGRESS: advancing\n"
    "STATE: ✓步骤一 ✓步骤二 ✓验证"
)
UNSAT_REASON = "还没完成：步骤一刚做完，剩余步骤未动"

# 自动轮 prompt 契约（GoalManager.buildAutoTurnPromptLocked / GoalText.continuePrefix）。
CONTINUE_1 = "[goal 继续 1/"
CONTINUE_2 = "[goal 继续 2/"
EVAL_REASON_LINE = "评估器结论（上一轮）："
FIRST_TURN_MARK = "（首轮）"

# 评估器 user prompt 契约（ChatClientGoalEvaluator.renderUser）。
EVAL_COND_MARK = "目标条件："
EVAL_TURN_MARK = "轮次："

# UI 文案（CodeTuiView：handleGoalCommand / goalLeadingSpan / goalNoticeIfTransitioned）。
GOAL_SET_LINE = "◎ goal 已设定"
GOAL_LEADING = "◎ goal"                  # 状态栏前导段（N/M、评估中、⏳ 倒计时任一形态）
GOAL_FINAL_LINE = "◎ goal 终态：SATISFIED"
GOAL_PAUSED_MARK = "◎ goal 已暂停"       # 全程不得出现（本场景无熔断/无 Esc）
GOAL_STOP_GUARD = "没有进行中的 goal"    # 终态后 /goal stop 的 M4 守卫 notice

# C1 落库标记（GoalText.EVAL_OPEN/CLOSE）。
EVAL_OPEN = "[goal 评估]"
EVAL_CLOSE = "[/goal 评估]"


# ── 桩模型（主链 SSE + 评估器 JSON，同一端口按 model 字段分流） ────────────────────
def _sse(payload):
    return ("data: " + json.dumps(payload, ensure_ascii=False) + "\n\n").encode()


def _chunk(delta, finish=None):
    return {
        "id": "goal-smoke-main",
        "object": "chat.completion.chunk",
        "created": 1,
        "model": MAIN_MODEL,
        "choices": [{"index": 0, "delta": delta, "finish_reason": finish}],
    }


class StubModel(BaseHTTPRequestHandler):
    """最小的 OpenAI 兼容 /chat/completions 端点。

    ⚠ 必须说 HTTP/1.1（quota 冒烟头注的保真要求：HTTP/1.0 应答 OkHttp 读不到）；
    SSE 发完 [DONE] 关连接（应用的流完成信号是 EOF，keep-alive 会把回合挂死在「思考中」）。

    路由表（按请求体 model 字段 + 最后一条消息，顺序即优先级）：
      * model == EVAL_MODEL → 评估器：非流式 JSON chat.completion；第 1 次 UNSATISFIED、
        第 2 次起 SATISFIED（本场景恰好两次）。
      * 主链（glm-5.3）按<b>最后一条消息</b>路由：
          - role == "tool"       → 纯文本收尾（工具轮的第二次调用）；
          - user 以 `[goal 继续` 开头 → Glob 工具调用（READ_ONLY 自动放行）——逼出真实轮形状
            `[user, assistant(tool_calls), tool, assistant]`（C1 合法形态，见文件头注）；
          - 其余（kickoff）      → 纯文本。

    `main_reqs` / `eval_reqs` 记录每次请求的完整 messages——请求序列断言的唯一凭据。
    """

    protocol_version = "HTTP/1.1"   # 保真要求：openai-java 读不到 HTTP/1.0 的应答

    main_reqs = []      # 主链每次请求的 messages（线程安全经 lock 访问）
    eval_reqs = []      # 评估器每次请求的 messages
    lock = threading.Lock()

    def log_message(self, fmt, *args):   # 别把 HTTP 日志喷进 pty
        pass

    @staticmethod
    def _last_role(messages):
        return messages[-1].get("role") if messages else ""

    @staticmethod
    def _last_user_text(messages):
        for m in reversed(messages):
            if m.get("role") == "user":
                c = m.get("content") or ""
                if not isinstance(c, str):
                    c = json.dumps(c, ensure_ascii=False)
                return c
        return ""

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(length) or b"{}")
        messages = body.get("messages") or []
        model = body.get("model") or ""

        with StubModel.lock:
            if model == EVAL_MODEL:
                StubModel.eval_reqs.append(messages)
                n_eval = len(StubModel.eval_reqs)
            else:
                StubModel.main_reqs.append(messages)
                n_main = len(StubModel.main_reqs)

        try:
            if model == EVAL_MODEL:
                self._answer_eval(n_eval)
            else:
                self._answer_main(messages)
        except (BrokenPipeError, ConnectionResetError):
            # 应用退出/取消会掐断连接而桩可能正在写；预期路径，不是失败。
            pass

    def _answer_eval(self, n):
        """非流式 JSON（ChatClient .call() → sync client）：四标记行协议文本。"""
        text = EVAL_REPLY_1 if n == 1 else EVAL_REPLY_2
        payload = json.dumps({
            "id": "goal-smoke-eval-%d" % n,
            "object": "chat.completion",
            "created": 1,
            "model": EVAL_MODEL,
            "choices": [{"index": 0, "message": {"role": "assistant", "content": text},
                         "finish_reason": "stop"}],
            "usage": {"prompt_tokens": 10, "completion_tokens": 20, "total_tokens": 30},
        }, ensure_ascii=False).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)
        self.wfile.flush()

    def _answer_main(self, messages):
        last_role = self._last_role(messages)
        last_user = self._last_user_text(messages).strip()
        if last_role == "tool":
            chunks = self._text_chunks(MAIN_REPLY)              # 工具轮收尾：纯文本
        elif last_user.startswith("[goal 继续"):
            # goal 自动轮首发：吐一个 Glob 工具调用（READ_ONLY 自动放行，不弹审批面板）。
            chunks = self._tool_call_chunks("Glob", {"pattern": "*"})
        else:
            chunks = self._text_chunks(MAIN_REPLY_1)            # kickoff：纯文本
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
        finally:
            # 真机网关在 [DONE] 后结束 SSE 流；应用侧的流完成信号是 EOF——keep-alive 挂住
            # 连接会让回合永远停在「思考中」（quota 冒烟实测踩过）。照抄关连接纪律。
            self.close_connection = True

    @staticmethod
    def _text_chunks(text):
        return [
            _sse(_chunk({"role": "assistant", "content": ""})),
            _sse(_chunk({"content": text})),
            _sse(_chunk({}, finish="stop")),
        ]

    @staticmethod
    def _tool_call_chunks(name, args):
        with StubModel.lock:
            call_id = "call_goal_%d" % len(StubModel.main_reqs)
        call = {
            "index": 0,
            "id": call_id,
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


# ── 屏幕助手（照既有冒烟：状态行必须取当前屏幕的那一行，不能撞 scrollback） ────────
def status_row(session):
    """<b>当前屏幕</b>的状态行文本（最后一个非空行）——goal 前导段（◎ goal N/M 等）就在这。"""
    for y in range(len(session.screen.display) - 1, -1, -1):
        if session.screen.display[y].strip():
            return session.screen.display[y]
    return ""


def main_count():
    with StubModel.lock:
        return len(StubModel.main_reqs)


def eval_count():
    with StubModel.lock:
        return len(StubModel.eval_reqs)


def continue_reqs():
    """主链请求里<b>末条消息</b>是 `[goal 继续` user prompt 的那些（自动轮首发请求），按到达序。

    不能只按「有 user 文本以 [goal 继续 开头」过滤：工具轮的第二次请求（末条是 tool 结果）
    里，最后一条 user 仍是本轮的 continue prompt，会把同一轮数两次。
    """
    with StubModel.lock:
        reqs = list(StubModel.main_reqs)
    out = []
    for r in reqs:
        if not r:
            continue
        if r[-1].get("role") != "user":
            continue
        if StubModel._last_user_text(r).strip().startswith("[goal 继续"):
            out.append(r)
    return out


def last_user_text(messages):
    return StubModel._last_user_text(messages)


# ── C1 落库断言：会话文件里的 [goal 评估] 标记事件 ────────────────────────────────
def check_session_store(workdir):
    """读 `<cwd>/.codetui/sessions/<sessionId>.json`（FileSessionRepository 落盘处）。

    三断言（fold 销毁标记 bug 的唯一端到端网）：
      1. 存在 UNSAT 与 SAT 各至少一条标记事件；
      2. 每条标记事件都不是尾事件，且<b>后一条恰是 ASSISTANT</b>（中段插入契约：插在最后
         一条 AssistantMessage 之前 → 后面必跟 assistant，foldTrailingUserIntoOutbound
         只折<b>尾部</b> user，永远够不到它）；
      3. 每个含 `[goal 评估]` 文本的事件都是独立合成块：strip 后以 EVAL_OPEN 开头、
         EVAL_CLOSE 结尾、闭合标记之后无残余——fold 混入（「[goal 评估]…[/goal 评估]\\n\\n
         <用户文本>」合成一条 user）会撞红第 3 条，fold 删除（标记整个消失）撞红第 1 条。
    """
    sessions_dir = os.path.join(workdir, ".codetui", "sessions")
    if not os.path.isdir(sessions_dir):
        die("会话目录 %s 不存在——回合从未落库？" % sessions_dir)
    files = [f for f in os.listdir(sessions_dir) if f.endswith(".json")]
    if not files:
        die("会话目录 %s 下没有 .json 会话文件" % sessions_dir)

    events = []
    for name in files:
        with open(os.path.join(sessions_dir, name), "r", errors="replace") as f:
            data = json.load(f)
        events.extend(data.get("events") or [])
    if not events:
        die("会话文件里没有任何事件")

    marker_idx = []
    for i, ev in enumerate(events):
        content = ev.get("content") or ""
        if EVAL_OPEN not in content:
            continue
        marker_idx.append(i)
        # 断言 3：独立合成块（strip 开头是 EVAL_OPEN、结尾是 EVAL_CLOSE、其后无残余）。
        stripped = content.strip()
        close_end = stripped.rfind(EVAL_CLOSE)
        if not stripped.startswith(EVAL_OPEN):
            die("第 %d 条事件含 %r 但不是以它开头（被折进别的消息文本？内容前缀=%r）——fold 混入回归"
                % (i, EVAL_OPEN, stripped[:60]), None)
        if close_end < 0 or stripped[close_end + len(EVAL_CLOSE):].strip():
            die("第 %d 条标记事件的 %r 之后还有残余文本（%r）——fold 混入回归"
                % (i, EVAL_CLOSE, stripped[close_end + len(EVAL_CLOSE):][:80]), None)

    if not marker_idx:
        die("会话落库里没有任何 %r 标记事件——评估结论被 fold 销毁或压根没落库（wireGoal 的 "
            "bindSession 断了？）" % EVAL_OPEN)

    for i in marker_idx:
        # 断言 2：非尾事件且后一条恰是 ASSISTANT（中段插入契约形状）。
        if i == len(events) - 1:
            die("标记事件（第 %d/%d 条）是尾事件——退回了「尾部追加」形态，下一次 submit 的 "
                "foldTrailingUserIntoOutbound 会把它折进出站文本并删除" % (i, len(events)), None)
        nxt = events[i + 1].get("messageType") or ""
        if nxt != "ASSISTANT":
            die("标记事件（第 %d 条）后一条是 %r，期望 ASSISTANT——中段插入位置回归"
                % (i, nxt), None)

    verdicts = [(events[i].get("content") or "") for i in marker_idx]
    if not any("UNSATISFIED" in v for v in verdicts):
        die("标记事件里没有 UNSATISFIED：%s" % [v[:80] for v in verdicts], None)
    if not any("SATISFIED" in v and "UNSATISFIED" not in v for v in verdicts):
        die("标记事件里没有 SATISFIED：%s" % [v[:80] for v in verdicts], None)

    print("C1 落库 OK: %d 条 %r 标记（UNSAT+SAT 各在）、全部非尾事件且后一条恰是 ASSISTANT、"
          "全部为独立合成块（无 fold 混入/销毁）。" % (len(marker_idx), EVAL_OPEN))
    print("C1 事件序列（messageType）: %s" % [e.get("messageType") for e in events])


# ── 场景主体 ────────────────────────────────────────────────────────────────────
def run_scenario(session):
    # 步骤 1：kickoff 用户消息（主桩第 1 次对话请求）。
    session.write(KICKOFF_TEXT.encode() + ENTER)
    session.wait_for(MAIN_REPLY_1, timeout=60)
    wait_until(session, lambda: main_count() >= 1, 15, "主桩收到第 1 次对话请求")
    print("kickoff OK: 主桩第 1 次请求已到，回复落地。")

    # 步骤 2：/goal 设定（activate 立即立起状态，首轮自动轮由空闲批 goal 槽发出）。
    session.write(("/goal " + GOAL_CONDITION + ENTER.decode()).encode())
    session.wait_for(GOAL_SET_LINE, timeout=15)
    wait_until(session, lambda: GOAL_LEADING in status_row(session), 30,
               "状态行出现 %r 指示（N/M、评估中、⏳ 倒计时任一形态；当前：%r）"
               % (GOAL_LEADING, status_row(session)))
    print("设定 OK: 屏出 %r，状态行含 %r（当前：%r）。"
          % (GOAL_SET_LINE, GOAL_LEADING, status_row(session).strip()))

    # 步骤 3：自动轮 1（goal 槽 dispatch → Glob 工具调用 → 工具结果收尾，真实轮形状）。
    wait_until(session, lambda: len(continue_reqs()) >= 1, 60, "主桩收到 [goal 继续 1/] 对话请求")
    u2 = last_user_text(continue_reqs()[0])
    if not u2.strip().startswith(CONTINUE_1):
        die("自动轮 1 请求的 user 文本不以 %r 开头（实际前缀=%r）——自动轮 prompt 接线断了"
            % (CONTINUE_1, u2.strip()[:60]), session.screen.display)
    if EVAL_REASON_LINE not in u2 or FIRST_TURN_MARK not in u2:
        die("自动轮 1 请求的 user 文本缺 %r/%r 行（实际=%r）——自动轮 prompt 模板回归"
            % (EVAL_REASON_LINE, FIRST_TURN_MARK, u2[:200]), session.screen.display)
    print("自动轮 1 OK: 请求 user 以 %r 开头，含 %r（%r）。"
          % (CONTINUE_1, EVAL_REASON_LINE, FIRST_TURN_MARK))
    session.wait_for(MAIN_REPLY, timeout=60)      # 工具轮收尾文本落地（回合才算完）

    # 步骤 4：评估 #1（UNSATISFIED）→ 评估桩第 1 次请求。
    wait_until(session, lambda: eval_count() >= 1, 60, "评估桩收到第 1 次评估请求")
    with StubModel.lock:
        eval1 = StubModel.eval_reqs[0]
    e1_user = last_user_text(eval1)
    if EVAL_COND_MARK not in e1_user or EVAL_TURN_MARK not in e1_user:
        die("第 1 次评估请求的 user 文本缺 %r/%r 要素（实际=%r）——评估器接线断了"
            % (EVAL_COND_MARK, EVAL_TURN_MARK, e1_user[:200]), session.screen.display)
    print("评估 #1 OK: 评估桩收到请求，user 文本含 %r/%r（UNSATISFIED 应答中）。"
          % (EVAL_COND_MARK, EVAL_TURN_MARK))

    # UNSAT 放行 → gap 倒计时（1s）→ 自动轮 2。若误判 stalled 熔断（PAUSED）则永远等不到。
    wait_until(session, lambda: len(continue_reqs()) >= 2, 60,
               "主桩收到 [goal 继续 2/] 对话请求（gap %ds 后的自动轮 2）" % GOAL_GAP_SECONDS)
    u3 = last_user_text(continue_reqs()[1])
    if not u3.strip().startswith(CONTINUE_2):
        die("自动轮 2 请求的 user 文本不以 %r 开头（实际前缀=%r）——UNSAT 后的续轮接线断了"
            % (CONTINUE_2, u3.strip()[:60]), session.screen.display)
    if EVAL_REASON_LINE not in u3 or UNSAT_REASON not in u3:
        die("自动轮 2 请求的 user 文本缺 %r 行或第 1 次评估的 REASON 原文 %r（实际=%r）"
            "——verdict 没有回流进下一自动轮 prompt"
            % (EVAL_REASON_LINE, UNSAT_REASON, u3[:300]), session.screen.display)
    print("自动轮 2 OK: 请求 user 以 %r 开头，且含上一轮 UNSAT 的 REASON 原文"
          "（verdict 回流真凭据）。" % CONTINUE_2)

    # 步骤 6：评估 #2（SATISFIED）→ 终态一行式总结。
    wait_until(session, lambda: eval_count() >= 2, 60, "评估桩收到第 2 次评估请求")
    session.wait_for(GOAL_FINAL_LINE, timeout=30)
    print("终态 OK: 屏出 %r。" % GOAL_FINAL_LINE)
    print_screen("goal 终态（人眼复核）", session.screen.display)

    if GOAL_PAUSED_MARK in session.screen_text():
        die("全程出现 %r——本场景（advancing 的 UNSAT）不应触发任何熔断" % GOAL_PAUSED_MARK,
            session.screen.display)

    # 步骤 8：终态后 /goal stop 的 M4 守卫（无事可清）→ /exit。
    session.write(b"/goal stop\r")
    session.wait_for(GOAL_STOP_GUARD, timeout=15)
    print("终态守卫 OK: /goal stop 后 notice=%r。" % GOAL_STOP_GUARD)
    session.write(b"/exit\r")
    deadline = time.time() + 15
    while time.time() < deadline and session.proc.poll() is None:
        session.pump(0.2)


def check_stub_credentials():
    """收尾的真凭据总断言（请求序列形状），屏幕证明不了这些。"""
    with StubModel.lock:
        main_n = len(StubModel.main_reqs)
        eval_n = len(StubModel.eval_reqs)
        eval_users = [last_user_text(m) for m in StubModel.eval_reqs]
        tool_last = sum(1 for m in StubModel.main_reqs if StubModel._last_role(m) == "tool")
    if main_n < 3:
        die("主桩只收到 %d 次对话请求（期望 ≥3：kickoff + 两个自动轮的工具循环）——goal 接线断了"
            "（wireGoal/goal 槽断掉则 [goal 继续] 请求一次都不会发生，只有 kickoff 那 1 次）" % main_n,
            None)
    if eval_n < 2:
        die("评估桩只收到 %d 次请求（期望 ≥2）——评估器接线断了" % eval_n, None)
    if len(continue_reqs()) < 2:
        die("主桩只收到 %d 次 [goal 继续] 请求（期望 ≥2：两个自动轮）——自动轮接线断了"
            % len(continue_reqs()), None)
    if tool_last < 2:
        die("主桩只收到 %d 次末条为 tool 结果的请求（期望 ≥2：两个自动轮各一次工具循环）——"
            "工具轮形状没跑出来，C1 落库断言的前提不成立" % tool_last, None)
    for i, u in enumerate(eval_users, 1):
        if EVAL_COND_MARK not in u or EVAL_TURN_MARK not in u:
            die("第 %d 次评估请求的 user 文本缺 %r/%r 要素（实际=%r）"
                % (i, EVAL_COND_MARK, EVAL_TURN_MARK, u[:200]), None)
    print("真凭据 OK: 主桩 %d 次对话请求（含 %d 次 [goal 继续]、%d 次工具结果续叫）、"
          "评估桩 %d 次请求（评估 user 均含 %r/%r）。"
          % (main_n, len(continue_reqs()), tool_last, eval_n, EVAL_COND_MARK, EVAL_TURN_MARK))


# ── main ───────────────────────────────────────────────────────────────────────
def main():
    classpath = build_classpath()
    tmpdir = tempfile.mkdtemp(prefix="codetui-goal-smoke-")
    # user.home 只隔离日志/用户层配置；会话仓库按 user.dir（cwd）落盘，天然在本 tmpdir 内。
    home_dir = os.path.join(tmpdir, "home")
    os.makedirs(home_dir, exist_ok=True)
    # 给 Glob 一点东西可找（照 interjection_smoke 先例；找不到也不影响工具轮形状成立）。
    with open(os.path.join(tmpdir, "notes.txt"), "w") as f:
        f.write("goal smoke\n")

    srv, base_url = start_stub()
    print("Stub model on %s（model=%s → 主链流式 SSE；model=%s → 评估器非流式 JSON："
          "第 1 次 UNSATISFIED、第 2 次 SATISFIED）" % (base_url, MAIN_MODEL, EVAL_MODEL))

    env = dict(os.environ)
    # 洗掉开发机上的一切 CODETUI_GOAL_*（防串台），再显式钉死本场景值。
    for k in list(env):
        if k.startswith("CODETUI_GOAL_"):
            env.pop(k, None)
    env["TERM"] = "xterm-256color"            # 不设则渲染全空白
    env["ZHIPU_API_KEY"] = "sk-dummy-not-real"
    # 注意：这把 key 现在也会让生成的 TUI 注册 ZhipuWebSearch——搜索工具不认 ZHIPU_BASE_URL，
    # 被调用会真连 open.bigmodel.cn（烧真配额）。当前 stub 模型不会调它，改脚本做自由工具选择时留意。
    # ⚠ 必须显式覆盖：本机环境变量 ZHIPU_BASE_URL 可能是真实 Coding Plan 端点，
    # 不覆盖就打真网（烧真配额）。zhipu provider 走 spring-ai-openai（openai-java SDK），
    # baseUrl 后拼 chat/completions，桩端点任意路径都会命中。
    env["ZHIPU_BASE_URL"] = base_url
    # 同 provider 双 modelId：首项=主对话默认模型，第二项=评估器专属（requestSelection 精确路由）。
    env["ZHIPU_MODELS"] = MAIN_MODEL + "," + EVAL_MODEL
    env["CODETUI_GOAL_EVALUATOR_MODEL"] = PROVIDER + ":" + EVAL_MODEL
    env["CODETUI_GOAL_MAX_TURNS"] = str(GOAL_MAX_TURNS)
    env["CODETUI_GOAL_TURN_GAP_SECONDS"] = str(GOAL_GAP_SECONDS)
    # 别让开发机上的其他 key 多挂 provider（会改默认模型，也可能真的发出网络请求）。
    for k in ("DEEPSEEK_API_KEY", "DASHSCOPE_API_KEY", "ANTHROPIC_API_KEY", "OPENAI_API_KEY",
              "OPENCODE_GO_API_KEY", "BOCHA_API_KEY", "BRAVE_API_KEY"):
        env.pop(k, None)

    cmd = ["java", "-Duser.home=%s" % home_dir, "-cp", classpath, MAIN_CLASS]
    print("Launching: %s" % " ".join(cmd))
    print("cwd=%s" % tmpdir)

    # PtySession 自己 openpty + TIOCSWINSZ（40×120）。0×0 的话什么都读不到。
    session = PtySession(cmd, tmpdir, env)
    try:
        session.wait_for(WELCOME_1, timeout=40)
        print("Startup OK.")

        run_scenario(session)
        check_stub_credentials()
        check_session_store(tmpdir)

        with StubModel.lock:
            print("桩共收到：主链 %d 次、评估器 %d 次。"
                  % (len(StubModel.main_reqs), len(StubModel.eval_reqs)))
        print("SMOKE PASS")
        return 0
    finally:
        session.close()
        srv.shutdown()


if __name__ == "__main__":
    sys.exit(main())

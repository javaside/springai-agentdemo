#!/usr/bin/env python3
"""「智谱 Coding Plan 限额等待（等到重置时刻自动续跑）」的 PTY 实机冒烟。

<b>这是整个功能唯一的端到端验证</b>。单测到不了两类东西：

  1. <b>接线本身</b>。`AgentTools.build` 里那行「`RetryingStreamChatModel.wrap(..., quotaBridge)` 有没有
     真套上」、`AgentTools.wireL1` 有没有把桥 bind 到 `CodingAgent.onL1QuotaWait`、以及
     `CodeTuiApplication` 传的是不是 runtime 里那一份桥，<b>没有任何单测覆盖</b>（Task 8 评审点名
     的 M2：quota 接线缺端到端守卫）。桥被误删，全模块单测一个都不会红。本脚本是唯一的网。
  2. <b>真终端里的 ⏳ 状态行与倒计时</b>。离屏 Buffer 的单测两边共用同一套排版，看不出分歧。

<b>变异实测（2026-09-22，务必读）</b>：把 `AgentTools.build` 里
`RetryingStreamChatModel.wrap(provider.chatModel(), bridge, quotaBridge)` 的三参改回两参
（= 限额 UI 桥接线被误删；限额等待本身仍在 `RetryPolicy` 限额分支里照常发生，只是
没有任何 UI 上报）之后——全模块单测<b>一个都没红</b>；本脚本在
`wait_quota_row`（等状态行出现 ⏳ 限额等待）<b>红了</b>，且是为正确的理由红的：最后一屏
显示回合卡在「● 思考中…」（等待真的在发生），但屏幕上<b>从头到尾没有 ⏳ 行</b>——桥断了，
`onQuotaWaitScheduled` 没人调，用户什么都看不到。`AgentTools.wireL1` 的
`rt.quotaBridge().bind(agent::onL1QuotaWait)` 那一行被删是同形的失败（sink 恒 null、no-op），
本脚本同样红在 `wait_quota_row`。所以：<b>别拿「等待行为」当接线证据</b>——睡 35s 靠的是
RetryPolicy，接线断掉它照样睡；这个功能唯一的一道网是本脚本的 ⏳ 行断言。

<b>没有真实 key 怎么跑</b>：照 interjection_smoke.py 的办法——脚本内起一个说 OpenAI SSE 方言的桩模型，
把 `ZHIPU_BASE_URL` 指过去（⚠ 本机环境变量里的 `ZHIPU_BASE_URL` 是真实 Coding Plan 端点，脚本
<b>必须显式覆盖</b>为桩地址，绝不能打真网）。桩对带标记的消息先回 3 次 HTTP 429（body 为真机原文，
重置时刻 = 首个 429 时刻 + 35 秒北京时间），之后回正常 SSE 文本。<b>不需要真实 key、不需要网络。</b>

<b>本脚本最有价值的一组断言</b>（scenario_one 里的 stub 时序断言）：屏幕上看到 ⏳ 行<b>证明不了</b>
「真的睡到了重置时刻」——那行是 UI 画自己的状态，就算 `RetryPolicy` 限额分支没接上（走老的 1s 起
指数退避），屏幕上也会有别的重试行。真凭据是桩<b>实际收到的请求时序</b>：
成功的那次调用必须落在 429 body 里<b>声明的那一个重置时刻</b> ±10s 内——应用只能靠解析 message
拿到 T，它能踩准 T 只可能是「睡到重置点」。这与 interjection_smoke 的
「别拿面板当证据，真凭据是桩收到的请求体」是同一条纪律。

<b>真机事实（脚本头部必须钉死，断言都长在它们上面）</b>：

  * openai-java SDK（4.49.0，源码核实）的 `RetryingHttpClient` 对 HTTP 429 会<b>内部自动重试</b>
    `maxRetries=2` 次（退避 0.5·2^n 秒封顶 8s、±25% 抖动），之后才把 `RateLimitException`
    抛给应用层。因此桩看到的 429 阶段是 <b>3 次调用</b>（1 初始 + 2 SDK 内部重试），随后才进入
    应用层限额等待，等待结束再来 1 次成功调用——<b>共 4 次</b>。任务书里「第 1→2 次调用间隔
    ≈35s」的字面写法被 SDK 内部重试占掉了前两次间隔，本脚本改为等价的真凭据：
    <b>最后一个 429 → 成功的间隔 ≈35s</b>，且<b>成功时刻 ≈ body 声明的重置时刻</b>；
  * `RateLimitException.getMessage() == "429: " + error.message`（SDK 构造器自拼前缀）；
  * 重置时刻是北京时间（`yyyy-MM-dd HH:mm:ss`，无反引号），应用按 Asia/Shanghai 解析；
    本脚本生成 T 用固定 UTC+8（中国无夏令时），与机器时区无关；
  * 应用日志（logback FILE appender）落在 `<user.home>/.codetui/logs/springai-code-tui.log`，
    脚本用 `-Duser.home=<tmp>/home` 隔离，从这里读 `QuotaLimitDetector` 的 WARN 观测钩子；
  * <b>桩必须说 HTTP/1.1</b>（`protocol_version = "HTTP/1.1"`）：实测 openai-java/OkHttp
    对 BaseHTTPRequestHandler 默认 HTTP/1.0 状态行 + 立即关连接的 429 应答<b>读不到响应</b>
    （Connection reset / unexpected end of stream），SDK 把它变成 `OpenAIIoException: Request failed`
    而非 `RateLimitException`——429 业务码与 message 全丢，识别器根本无从谈起。真实网关都是
    HTTP/1.1，这是桩的保真要求（本仓既有冒烟的 200 SSE 应答恰好不踩这条，429 应答是第一个踩的）；
    相应地，200 SSE 应答发完 `[DONE]` 后必须关连接——应用侧的流完成信号是 EOF，keep-alive
    挂住连接会让回合永远停在「思考中」（实测）。

覆盖的两个场景：

  | 场景     | 操作                                       | 期望（真凭据优先）                              |
  |----------|--------------------------------------------|-------------------------------------------------|
  | 限额续跑 | 发带 QUOTANOW 标记的消息                   | 桩收 4 次调用（3×429 + 1×200）；200 落在重置时刻 ±10s；等待期屏上 ⏳ 限额等待 且无 ↻ 重试中；日志有 QuotaLimitDetector WARN；回合正常完成 |
  | Esc 取消 | 再发带 QUOTANOW2 标记的消息，⏳ 出现后按 Esc | 进程回 IDLE（已取消当前回合）；桩不再收到新调用（取消语义） |

运行前<b>必须重新 package</b>，否则跑的是旧 jar，会得到一个看起来很像回归的假失败：

    mvn -q -pl springai-code-tui package -DskipTests
    mvn -q -pl springai-code-tui dependency:build-classpath -Dmdep.outputFile=target/cp.txt
    /usr/bin/python3 src/test/resources/scripts/quota_wait_smoke.py

成功 exit 0 + "SMOKE PASS"，失败非零 + "SMOKE FAIL: <原因>"（并打印最后一屏供人眼复核）。
"""
import json
import os
import sys
import tempfile
import threading
import time
from datetime import datetime, timedelta, timezone
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
ESC = b"\x1b"
CTRL_U = b"\x15"

# 北京时间固定 UTC+8：Asia/Shanghai 无夏令时，避免依赖 zoneinfo/机器时区。
CST = timezone(timedelta(hours=8))

MODEL_ID = "glm-5.3"                       # zhipu 默认模型（桩应答里回显用，不做断言）

# 重置时刻 = 首个 429 时刻 + QUOTA_DELAY_S 秒。⚠ 必须 > 实现里的 MIN_QUOTA_WAIT_MS=30s 下限，
# 这样测的是「真睡到重置时刻」而非下限兜底（若 T 过近，waitMs 会顶到 30s 下限，间隔断言失真）。
QUOTA_DELAY_S = 35
# openai-java 4.49.0 RetryingHttpClient 默认 maxRetries（源码核实）：429 在应用层看到之前，
# SDK 内部先重试 2 次。断言「共 4 次调用」长在这个数上；SDK 改版会让冒烟红得响。
SDK_MAX_RETRIES = 2
# SDK 内部重试的退避窗口（0.5·2^n 封顶 8s + 25% 抖动）：三个 429 必须簇在这个窗口内，
# 证明间隔大头是应用层限额等待，而不是别的慢重试。
SDK_RETRY_WINDOW_S = 12.0

Q1_MARKER = "QUOTANOW"                     # 场景一：3×429 后成功
Q2_MARKER = "QUOTANOW2"                    # 场景二：恒 429（回合被 Esc 取消，永远到不了成功）
Q1_TEXT = "帮我看看 " + Q1_MARKER
Q2_TEXT = "再查一下 " + Q2_MARKER
SUCCESS_REPLY = "冒烟回复：限额等待后自动续跑成功。"

# 真机 429 body 的 message 模板（code=1308，Coding Plan 5 小时上限；无反引号）。
QUOTA_MSG_TEMPLATE = "已达到 5 小时的使用上限。您的限额将在 %s 重置。"

# UI 文案（与实现钉死：ConversationState.onQuotaWaitScheduled / 普通重试的 ↻ 行）。
QUOTA_LABEL = "⏳ 限额等待"
RETRY_LABEL = "↻ 重试中"
CANCEL_NOTICE = "已取消当前回合"
# QuotaLimitDetector 的观测钩子 WARN 前缀（日志断言的真凭据：识别器真在工作）。
DETECTOR_WARN = "智谱限额错误：code=1308"


def beijing_at(epoch_seconds):
    """epoch 秒 → `yyyy-MM-dd HH:mm:ss` 北京时间（固定 UTC+8）。"""
    return datetime.fromtimestamp(epoch_seconds, CST).strftime("%Y-%m-%d %H:%M:%S")


# ── 桩模型 ────────────────────────────────────────────────────────────────
def _sse(payload):
    return ("data: " + json.dumps(payload, ensure_ascii=False) + "\n\n").encode()


def _chunk(delta, finish=None):
    return {
        "id": "qw-smoke-1",
        "object": "chat.completion.chunk",
        "created": 1,
        "model": MODEL_ID,
        "choices": [{"index": 0, "delta": delta, "finish_reason": finish}],
    }


class StubModel(BaseHTTPRequestHandler):
    """最小的 OpenAI 兼容 /chat/completions 端点，按<b>最后一条消息</b>里的标记路由。

    ⚠ 必须说 HTTP/1.1（见文件头注）：HTTP/1.0 默认值会让 OkHttp 读不到 429 应答。

    路由表（顺序即优先级）：
      * 最后一行含 Q2_MARKER → 恒 429（Esc 场景：回合等不到成功，桩也不该再被叫）
      * 最后一行含 Q1_MARKER → 前 1+SDK_MAX_RETRIES 次 429，之后 200 文本
      * 其余                    → 200 文本（理论到不了）

    `calls` 记录每次请求的 `(标记, 到达时刻, 应答状态码, 429 内嵌的重置时刻)`——
    <b>时序断言的唯一凭据</b>。屏幕上的 ⏳ 行画不出这些。
    """

    protocol_version = "HTTP/1.1"   # 保真要求：openai-java 读不到 HTTP/1.0 的 429 应答

    calls = []                # 每次请求一条记录；线程安全经 lock 访问
    reset_by_marker = {}      # 每个标记首个请求算出的重置时刻（同标记的 429 用同一个 T）
    lock = threading.Lock()

    def log_message(self, fmt, *args):   # 别把 HTTP 日志喷进 pty
        pass

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(length) or b"{}")
        messages = body.get("messages") or []
        last = messages[-1] if messages else {}
        content = last.get("content") or ""
        if not isinstance(content, str):
            content = json.dumps(content, ensure_ascii=False)

        # 只看<b>最后一行</b>：会话层会把连续同角色消息折在一起，旧回合的标记会一直留在历史里。
        tail = (content.strip().splitlines() or [""])[-1]
        if Q2_MARKER in tail:
            marker = "esc"
        elif Q1_MARKER in tail:
            marker = "quota"
        else:
            marker = "other"

        t = time.time()
        with StubModel.lock:
            if marker not in StubModel.reset_by_marker:
                StubModel.reset_by_marker[marker] = beijing_at(t + QUOTA_DELAY_S)
            reset_text = StubModel.reset_by_marker[marker]
            prev = sum(1 for c in StubModel.calls if c[0] == marker)
            entry = [marker, t, None, reset_text]
            StubModel.calls.append(entry)

        if marker == "esc" or (marker == "quota" and prev < 1 + SDK_MAX_RETRIES):
            status = self._answer_429(reset_text)
        else:
            status = self._answer_ok()
        entry[2] = status

    def _answer_429(self, reset_text):
        """真机 429：body 原文（code=1308 + 内嵌重置时刻），无 Retry-After 头。"""
        payload = json.dumps(
            {"error": {"code": "1308", "message": QUOTA_MSG_TEMPLATE % reset_text}},
            ensure_ascii=False).encode()
        self.send_response(429)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)
        self.wfile.flush()
        return 429

    def _answer_ok(self):
        try:
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            self.end_headers()
            for c in self._text_chunks(SUCCESS_REPLY):
                self.wfile.write(c)
                self.wfile.flush()
            self.wfile.write(b"data: [DONE]\n\n")
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            # Esc 取消会掐断连接，而桩可能正在写。预期路径，不是失败。
            pass
        finally:
            # 真机网关在 [DONE] 后结束 SSE 流；应用侧的流完成信号是 EOF——桩既然必须说
            # HTTP/1.1（见头注：否则 429 应答读不到），就不能 keep-alive 挂住连接，不然
            # 回合会永远停在「思考中」（实测踩过）。关连接 = 模拟真机流的收尾。
            self.close_connection = True
        return 200

    @staticmethod
    def _text_chunks(text):
        return [
            _sse(_chunk({"role": "assistant", "content": ""})),
            _sse(_chunk({"content": text})),
            _sse(_chunk({}, finish="stop")),
        ]


def start_stub():
    srv = ThreadingHTTPServer(("127.0.0.1", 0), StubModel)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv, "http://127.0.0.1:%d" % srv.server_address[1]


# ── 屏幕/日志助手 ─────────────────────────────────────────────────────────
def status_row(session):
    """<b>当前屏幕</b>的状态行文本（最后一个非空行）。

    为什么不用子串撞整屏：状态栏就地重绘，屏上还留着 scrollback 与上一帧残迹；
    「⏳ 限额等待」被打进过 scrollback（INFO 行）后，拿它撞整屏在等待结束之后依然为真，
    断言就成了「不会失败的测试」。取当前屏幕的那一行，才是在断言「此刻的状态」。
    （与 interjection_smoke.status_row 同一纪律。）
    """
    for y in range(len(session.screen.display) - 1, -1, -1):
        if session.screen.display[y].strip():
            return session.screen.display[y]
    return ""


def log_text(home_dir):
    """读应用的 FILE appender 日志（logback：`${user.home}/.codetui/logs/springai-code-tui.log`）。"""
    path = os.path.join(home_dir, ".codetui", "logs", "springai-code-tui.log")
    if not os.path.isfile(path):
        return ""
    with open(path, "r", errors="replace") as f:
        return f.read()


def wait_quota_row(session, what):
    """等到<b>当前状态行</b>进入「⏳ 限额等待」（RETRYING 态标签）。

    不能用 wait_for("⏳ 限额等待")：跑到第二个场景时，第一个场景留下的 ⏳ INFO 行还在
    scrollback 里，那句 wait 会立刻返回，Esc 就被按进了一个还没进入等待的回合。
    """
    wait_until(session, lambda: QUOTA_LABEL in status_row(session), 25, what)


def snapshot_marker(marker):
    with StubModel.lock:
        return [list(c) for c in StubModel.calls if c[0] == marker]


# ── 场景一：限额等待 → 等到重置时刻自动续跑 ────────────────────────────────
def scenario_one(session, home_dir):
    session.write(Q1_TEXT.encode() + ENTER)
    wait_quota_row(session, "第一个回合进入限额等待（桩正在回 429）")
    print("UI OK: 状态行出现 %r（当前：%r）." % (QUOTA_LABEL, status_row(session).strip()))

    # 信息流里也应有 ⏳ INFO 行（onQuotaWaitScheduled 推的），且两态互斥：↻ 重试中不得出现。
    if QUOTA_LABEL not in session.screen_text():
        die("信息流里没有 %r —— onQuotaWaitScheduled 没把 ⏳ 行打进 scrollback" % QUOTA_LABEL,
            session.screen.display)
    if RETRY_LABEL in session.screen_text():
        die("两态互斥被破坏：限额等待期间屏上出现 %r —— 限额分支错误地触发了普通重试回调"
            % RETRY_LABEL, session.screen.display)
    print("UI OK: 信息流含 %r，且全程无 %r（⏳/↻ 两态互斥）." % (QUOTA_LABEL, RETRY_LABEL))

    # 观测钩子（识别器真在工作）：日志必须出现 QuotaLimitDetector 的 WARN，且带业务码、resetAt
    # 与真机 message 原文（含北京时间 T）。
    wait_until(session, lambda: DETECTOR_WARN in log_text(home_dir), 10,
               "日志出现 QuotaLimitDetector 的 WARN")
    warn_lines = [l for l in log_text(home_dir).splitlines() if DETECTOR_WARN in l]
    line = warn_lines[-1]
    quota_calls = snapshot_marker("quota")
    reset_text = quota_calls[0][3]
    if "code=1308" not in line or "resetAt=" not in line or reset_text not in line:
        die("观测钩子日志不完整：期望含 code=1308、resetAt= 与真机 message 原文（%r），实际 %r"
            % (reset_text, line), session.screen.display)
    print("日志 OK: QuotaLimitDetector WARN 出现（%r）." % line.strip())

    # 等到回合完成：等待结束后的续跑应拿到正常 SSE 应答并落地。
    session.wait_for(SUCCESS_REPLY, timeout=60)
    wait_until(session, lambda: "Enter 发送" in status_row(session), 10,
               "回合完成后状态行回到 IDLE")
    print("回合完成 OK: %r 落地，状态行回到 IDLE." % SUCCESS_REPLY)
    print_screen("限额等待续跑完成（人眼复核）", session.screen.display)

    # ── 真凭据：桩实际收到的请求时序 ───────────────────────────────────────
    calls = snapshot_marker("quota")
    if len(calls) != 1 + SDK_MAX_RETRIES + 1:
        die("桩收到 %d 次调用，期望恰 %d 次（1 初始 + %d 次 SDK 内部重试 + 1 次等待后续跑）"
            % (len(calls), 1 + SDK_MAX_RETRIES + 1, SDK_MAX_RETRIES), session.screen.display)
    statuses = [c[2] for c in calls]
    if statuses[:1 + SDK_MAX_RETRIES] != [429] * (1 + SDK_MAX_RETRIES) or statuses[-1] != 200:
        die("应答状态序列 %s，期望前 %d 次 429、最后一次 200" % (statuses, 1 + SDK_MAX_RETRIES),
            session.screen.display)

    first_t = calls[0][1]                     # 首个 429（= T 的基准点）
    last429_t = calls[-2][1]                  # SDK 内部重试的最后一个 429
    success_t = calls[-1][1]                  # 等待后的成功调用
    if last429_t - first_t > SDK_RETRY_WINDOW_S:
        die("3 个 429 未簇在 %.0fs 内（实际 %.1fs）——间隔大头不是应用层限额等待"
            % (SDK_RETRY_WINDOW_S, last429_t - first_t), session.screen.display)

    # 硬证据（核心行为）：最后一个 429 → 成功 的间隔 ≈ 35s（±10s）。
    # 老实现的 1s 起指数退避封顶 30s，两次相邻应用级调用最多 ~30s，且 UI 会走 ↻ 行——
    # 这一条与上面的 ↻ 互斥断言合起来，把「睡到重置点」与「指数退避」彻底分开。
    gap = success_t - last429_t
    if not (QUOTA_DELAY_S - 10 <= gap <= QUOTA_DELAY_S + 10):
        die("最后一个 429 → 成功 的间隔 %.1fs，期望 ≈%ds（±10s）——不是睡到重置时刻"
            % (gap, QUOTA_DELAY_S), session.screen.display)
    print("时序 OK: 最后一个 429 → 成功 间隔 %.1fs（≈%ds）——睡到了重置时刻，"
          "不是 1s 起的指数退避." % (gap, QUOTA_DELAY_S))

    # 最强证据：成功调用落在 429 body 里<b>声明的重置时刻</b> ±10s 内。
    # 应用只能靠解析 message 拿到 T；踩得准 T 只可能是「睡到 T 再重试」。
    reset_epoch = datetime.strptime(reset_text, "%Y-%m-%d %H:%M:%S").replace(tzinfo=CST).timestamp()
    if abs(success_t - reset_epoch) > 10:
        die("成功调用（%.1f）偏离 body 声明的重置时刻 %s（%.1f）超 ±10s——等待时长与解析出的 T 对不上"
            % (success_t, reset_text, reset_epoch), session.screen.display)
    print("重置时刻 OK: 成功调用落在 body 声明的重置时刻 %s 的 ±10s 内（偏差 %.1fs）."
          % (reset_text, success_t - reset_epoch))


# ── 场景二（Esc 取消语义）：等待期间按 Esc → 回 IDLE，桩不再被叫 ─────────────
def scenario_two(session):
    session.write(Q2_TEXT.encode() + ENTER)
    wait_quota_row(session, "第二个回合进入限额等待（Esc 场景）")

    session.write(ESC)
    wait_until(session, lambda: CANCEL_NOTICE in status_row(session), 15,
               "Esc 后状态行出现 %r（实际：%r）" % (CANCEL_NOTICE, status_row(session)))
    print("Esc OK: 状态行出现 %r（%r）." % (CANCEL_NOTICE, status_row(session).strip()))

    # 消费掉 sticky notice（任意按键）后，状态行恢复常态 IDLE 行。
    session.write(b"x")
    session.pump(0.3)
    session.write(CTRL_U)
    session.pump(0.3)
    wait_until(session, lambda: "Enter 发送" in status_row(session), 10,
               "Esc 后状态行回到 IDLE（实际：%r）" % status_row(session))
    if QUOTA_LABEL in status_row(session) or RETRY_LABEL in status_row(session):
        die("Esc 后状态行仍含等待/重试标签：%r" % status_row(session).strip(),
            session.screen.display)
    print("回 IDLE OK: 状态行恢复常态（%r）." % status_row(session).strip())

    # 取消语义硬证据：Esc 之后桩<b>不再收到新调用</b>（等待的 Mono.delay 被 dispose 掐断，
    # 没有到点复活）。先让它静置几秒再比对计数。
    esc_before = len(snapshot_marker("esc"))
    session.pump(12.0)
    esc_after = len(snapshot_marker("esc"))
    if esc_after != esc_before:
        die("Esc 后桩又收到 %d 次新调用（%d → %d）——取消没有终止等待，到点复活了"
            % (esc_after - esc_before, esc_before, esc_after), session.screen.display)
    if any(c[2] == 200 for c in snapshot_marker("esc")):
        die("Esc 场景的回合不该拿到成功应答", session.screen.display)
    print("取消语义 OK: Esc 后静置 12s，桩调用数保持 %d（等待被真正取消，无到点复活）." % esc_before)


# ── main ─────────────────────────────────────────────────────────────────
def main():
    classpath = build_classpath()
    tmpdir = tempfile.mkdtemp(prefix="codetui-quota-smoke-")
    # 隔离用户层：真实 ~/.codetui 的权限规则/技能/记忆都会改变启动形态；
    # 日志（logback `${user.home}/.codetui/logs`）也因此落在本 tmpdir，供观测钩子断言读取。
    home_dir = os.path.join(tmpdir, "home")
    os.makedirs(home_dir, exist_ok=True)

    srv, base_url = start_stub()
    print("Stub model on %s（标记 %s → 3×429 后成功；%s → 恒 429 供 Esc 场景；重置时刻 = 首个 429 + %ds）"
          % (base_url, Q1_MARKER, Q2_MARKER, QUOTA_DELAY_S))

    env = dict(os.environ)
    env["TERM"] = "xterm-256color"            # 不设则渲染全空白
    env["ZHIPU_API_KEY"] = "sk-dummy-not-real"
    # ⚠ 必须显式覆盖：本机环境变量 ZHIPU_BASE_URL 已被用户设为真实 Coding Plan 端点，
    # 不覆盖就打真网（烧真配额）。zhipu provider 走 spring-ai-openai（openai-java SDK），
    # baseUrl 后拼 /chat/completions，故桩端点的任意路径都会命中。
    env["ZHIPU_BASE_URL"] = base_url
    env.pop("ZHIPU_MODELS", None)             # 用内置默认清单（glm-5.3）
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

        scenario_one(session, home_dir)
        scenario_two(session)

        session.write(b"/exit\r")
        deadline = time.time() + 10
        while time.time() < deadline and session.proc.poll() is None:
            session.pump(0.2)

        with StubModel.lock:
            calls = [list(c) for c in StubModel.calls]
        print("桩共收到 %d 次请求：%s" % (len(calls),
              [(c[0], "%.1f" % c[1], c[2]) for c in calls]))
        print("SMOKE PASS")
        return 0
    finally:
        session.close()
        srv.shutdown()


if __name__ == "__main__":
    sys.exit(main())

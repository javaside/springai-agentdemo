# Code TUI pty 异步写·实机验收与收尾优化 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付 pty 异步写修复（commit 497c4c65）的最终验证工具与两项收尾优化——「读端停摆」自动化冒烟（顺序型判据 + 可证红开关）、/clear 屏障降级提示行、大延迟批写侧分片。

**Architecture:** 冒烟脚本沿用既有 pty 冒烟体系（`resize_smoke.py` 的 `PtySession` 真 pty + pyte 回放 + 本地 SSE 桩），核心差异是「输出高峰期间**停止 pump**」制造读端停摆——pty-writer 卡死在 write(2) 而事件循环必须仍处理按键。核心断言是**字节顺序型**而非毫秒时限：异步写世界里按键回显帧排在 FIFO 写队列的全部输出批之后（完整回显首次可见的 raw 偏移 > 末行输出偏移）；同步写世界里事件循环本身卡死、恢复后按键与剩余输出交错（偏移关系相反）——顺序判据在两个世界方向相反，本地全速 pty 上稳健。两项优化分别落在 `CodeTuiView`（降级提示 helper）与 `AsyncPtyWriter`（超批分片入队）。

**Tech Stack:** Python 3（`/usr/bin/python3` + pyte，仅用户级）、Java 21、Maven、既有 JUnit 5。

**Spec:** `docs/superpowers/specs/2026-09-02-code-tui-async-pty-writer-design.md`（§7 内存上界、§10 后续工作 = 本计划 Task 1/2/3 + Task 4 收尾）

## Global Constraints

- 冒烟脚本放 `springai-code-tui/src/test/resources/scripts/`，从仓库根目录用 `/usr/bin/python3` 直接运行，不进 Maven 测试生命周期；成功结束时必须打印 `SMOKE PASS`（README 既有约定）。
- 冒烟不依赖网络与真实 API key：用本地 SSE 桩（`ThreadingHTTPServer`，抄 fairness smoke 的 `StubModel` 模式）。
- 冒烟必须有「证红开关」（mutation env var）：开关打开时脚本必须以非零退出码失败——没有证红的冒烟测不到东西（既有 fairness smoke 的 `CODETUI_FAIRNESS_MUTATE_*` 先例）。
- Java 侧改动走 TDD：先写失败测试，跑红后再实现；实现后跑**全模块**回归。
- 单测命令统一带 `-Dsurefire.failIfNoSpecifiedTests=false`（项目既有约定）。
- 提交信息用中文、含根因/行为说明（见仓库 git log 风格）。
- **不得声明本冒烟覆盖「writer 队列饱和 / 延迟批出现」**——2000 行的内容 ~92K UTF-16 char，加 ANSI 定位/SGR 后批 payload ~13 万 char，均 < 1MiB 软预算（8-11 倍余量）；饱和与延迟批行为由既有单测钉住（`InlineTuiRunnerEventDrivenTest#keyEventsProcessedWhilePtyWriteIsStuck` 断言 `hasDeferredOutput`、`InlineDisplayAsyncWriterTest` 覆盖延迟批）。本冒烟的独有价值是：**真内核 write(2) 卡死场景下的按键公平性与输出完整性**。

---

### Task 1: 读端停摆冒烟（stalled_terminal_smoke.py）——核心验收工具

**Files:**
- Create: `springai-code-tui/src/test/resources/scripts/stalled_terminal_smoke.py`
- Modify: `springai-code-tui/src/test/resources/scripts/README.md`（表格登记 + 本地命令清单；表格插入位置：`resize_smoke.py` 行之后、`stream_box_smoke.py` 行之前——保持本地脚本字母序）

**Interfaces:**
- Consumes: `resize_smoke.py` 的 `PtySession`（构造 `(cmd, cwd, env, rows, cols)`、`write(bytes)`、`pump(duration)`、`raw`、`screen.display`（pyte）、`wait_for/wait_stable/close/die/build_classpath/WELCOME/MAIN_CLASS`）。只 import resize_smoke，SSE 桩在脚本内自建（抄 fairness smoke 模式）。
- Produces: 无 Java 侧产物；退出码 0=通过且打印 `SMOKE PASS`。证红开关 `CODETUI_STALLED_MUTATE_SYNC_WRITE=1`。

**断言设计（为什么是顺序型而不是时限型）：**
停摆期间 pty-writer 卡死在 write(2)，但事件循环自由——按键照常处理，回显帧入 FIFO 写队列，排在已积压的全部输出批**后面**。恢复 pump 后：末行输出先落盘、完整回显后落盘 → `完整回显首次可见时的 len(raw)` **>** `末行输出字节在 raw 中的偏移`。同步写世界里事件循环本身卡死、停摆期间零处理；恢复后排队按键与剩余 ~85% 输出批**交错**出队 → 完整回显落在末行之前 → 偏移关系相反。顺序判据在两个世界方向相反，且与本地全速排空耗时无关（毫秒时限在异步世界也会 >50ms，归因不了同步写——这是本计划首轮审核确认并废弃的方案）。证红开关=翻转断言方向：异步世界里翻转断言必红，证明观测量双向有分辨力。

- [ ] **Step 1: 写完整脚本**

```python
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
```

- [ ] **Step 2: 从仓库根构建并首跑（预期 PASS）**

```bash
mvn -q -pl springai-tamboui-inline-patch -am install -DskipTests
mvn -q -pl springai-code-tui -am package -DskipTests
mvn -q -pl springai-code-tui dependency:build-classpath -Dmdep.outputFile=target/cp.txt
/usr/bin/python3 springai-code-tui/src/test/resources/scripts/stalled_terminal_smoke.py
```

Expected: 依次打印 `Stall over: 13 keys…`（±1）、`echo_offset=… > last_output_offset=…`、`Output complete: 2000/2000, no loss`、`byte-quiet …`、`SMOKE PASS`，退出码 0。若顺序断言红且消息含「slow machine」提示：先确认机器负载（SSE 桩 0.4s 送完、批处理 ~1s 入队完毕，正常余量 1s），确因环境慢再把 `STALL_SECONDS` 提到 4.0、`KEY_WINDOW_SECONDS` 提到 3.0——**不要改断言方向或删除顺序判据**。

- [ ] **Step 3: 证红验证（开关打开必须以非零退出）**

```bash
CODETUI_STALLED_MUTATE_SYNC_WRITE=1 /usr/bin/python3 \
  springai-code-tui/src/test/resources/scripts/stalled_terminal_smoke.py
echo "exit=$?"
```

Expected: 打印到 `echo_offset=… > last_output_offset=…` 后 `rs.die("MUTATION RED: …")`，`exit=1`。若居然 PASS（exit=0）：说明顺序判据失去分辨力（回显居然先于末行落盘）——这是 bug 信号，回查 `useAsyncWriter` 接线与 FIFO 语义，不是改测试。

- [ ] **Step 4: 登记 README**

`springai-code-tui/src/test/resources/scripts/README.md`：表格在 `resize_smoke.py` 行之后、`stream_box_smoke.py` 行之前插入（保持字母序）：

```markdown
| `stalled_terminal_smoke.py` | 输出高峰期间读端停摆 3s（pty-writer 卡 write(2)）：完整回显落盘晚于末行输出（顺序判据）、恢复后 2000 行零丢失、排空后零字节。证红：`CODETUI_STALLED_MUTATE_SYNC_WRITE=1` 翻转顺序断言。 | 本地 SSE 桩 |
```

并在「本地/无网络命令」清单追加：

```bash
/usr/bin/python3 springai-code-tui/src/test/resources/scripts/stalled_terminal_smoke.py
```

- [ ] **Step 5: Commit**

```bash
git add springai-code-tui/src/test/resources/scripts/stalled_terminal_smoke.py \
        springai-code-tui/src/test/resources/scripts/README.md
git commit -m "$(cat <<'EOF'
test(tui): 读端停摆冒烟——输出高峰+停 pump+停摆期间打字

复现异步 pty 写修复针对的根因场景（pty 读端停摆→内核缓冲写满→
pty-writer 卡在 write(2)），事件循环必须仍处理按键。主判据是
字节顺序型：异步写世界里完整回显帧排在 FIFO 队列全部输出批之后
（echo_offset > last_output_offset），同步写世界方向相反——与
本地排空耗时无关，稳健可证红（开关翻转断言方向）。
恢复后 2000 行零丢失、排空后 2s 零字节。
队列饱和/延迟批场景归单测（keyEventsProcessedWhilePtyWriteIsStuck
与 InlineDisplayAsyncWriterTest），本冒烟不声明覆盖。
EOF
)"
```

---

### Task 2: 屏障降级提示优化（独立说明行 + 双分支共用 helper）

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java:2152-2166`（/clear 两个降级分支）
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/CodeTuiViewClearTest.java`

**Interfaces:**
- Consumes: `ScreenCleaner.clear(runner)` 返回 false 的两种成因（反射失败 / 清屏屏障不成立）。`clear` 的签名与返回值**不改**。
- Produces: 私有 helper `pushClearDegradedNotice()`（两条 `state.pushInfo`：分隔行 + 说明行），`CodeTuiView` 内部使用，无新公开 API。

**背景：** 规格文档 §7/§10：屏障降级时 writer 在飞旧批仍会晚到——旧会话内容出现在「新会话」分割线**之后**，用户无人解释。两行独立提示（分隔行 + 说明行）消除困惑；helper 收敛两处降级分支（运行态 else 与测试态 else），防文案漂移。

**测试断言路径（重要，勿踩已修掉的坑）：** 测试态 `runner()==null`，`/clear` 走外层 else → `state.pushInfo` 只进 `pending`——coordinator 处于 NEW 态（未 `startForTest`），通知被丢弃、**没有批会消费 pending**。因此**不调 `tickForTest()`**（它会跑批把 pending 取走进 outputQueue，导致 poll 取到 0 条），直接 `pollPending` 收集断言。

- [ ] **Step 1: 写失败测试**

`CodeTuiViewClearTest.java` 类末尾（最后一个 `}` 前）加——沿用文件既有的 `type()` 辅助、`RecordingHandler` 桩与 `feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER))` 驱动方式：

```java
    /**
     * 降级提示必须是两条独立 INFO：分隔行 + 说明行。
     * 屏障降级时 writer 在飞旧批仍会晚到（旧内容出现在分割线之后），说明行消除
     * 「清屏了旧内容又冒出来」的困惑；独立成行才能被断言钉住（合并成一行会红）。
     */
    @Test
    void clearDegradedShowsSeparateExplanationLine() {
        ConversationState s = new ConversationState();
        RecordingHandler h = new RecordingHandler();
        CodeTuiView v = new CodeTuiView(s, h, Path.of("."));

        type(v, "/clear");
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
        // 不调 tickForTest：测试态无批消费，pushInfo 停在 pending，直接取。

        java.util.List<String> lines = new java.util.ArrayList<>();
        for (ConversationState.OutputLine ol; (ol = s.pollPending()) != null; ) {
            lines.add(ol.text());
        }
        long separators = lines.stream()
                .filter(l -> l.startsWith("───") && l.contains("新会话")).count();
        long explanations = lines.stream()
                .filter(l -> l.contains("上方若浮现旧内容")).count();
        assertEquals(1, separators, "分隔行必须是独立一条: " + lines);
        assertEquals(1, explanations, "说明行必须是独立另一条（不得与分隔行合并）: " + lines);
    }
```

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn -pl springai-code-tui -am test -Dtest=CodeTuiViewClearTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL——`说明行必须是独立另一条` 红（`explanations=0`；现文案只有一行分隔行，`separators=1` 先通过）。

- [ ] **Step 3: 实现 helper 并替换两处降级分支**

`CodeTuiView.java` 中（放在 `/clear` 分支附近，如 `resetTailDedup()` 前后）新增：

```java
    /**
     * /clear 降级提示（真清屏失败：反射失败或清屏屏障不成立——writer 积压排不空）。
     * 说明行文案只对「屏障不成立」成因准确（反射失败时无积压、无旧内容晚到），
     * 但两个成因共用一行是刻意取舍：调用方拿不到成因区分，多打一行无害、少打
     * 一行会在真实需要时缺席。屏障降级时 writer 在飞旧批仍会晚到——旧内容出现
     * 在分割线之后，说明行消除「清屏了旧内容又冒出来」的困惑（规格 §7/§10）。
     * 运行态与测试态两处降级分支共用本 helper，防文案漂移。
     */
    private void pushClearDegradedNotice() {
        state.pushInfo("─── 新会话（上下文已清空）───");
        state.pushInfo("终端输出积压未排空：上方若浮现旧内容，属上一会话残留");
    }
```

`runOnRenderThread` 内的 else 分支改为：

```java
                    } else {
                        pushClearDegradedNotice();
                    }
```

外层 else（`runner()==null` 测试态）同样改为：

```java
            } else {
                pushClearDegradedNotice();
            }
```

- [ ] **Step 4: 跑测试确认通过 + 全模块回归**

```bash
mvn -pl springai-code-tui -am test -Dtest=CodeTuiViewClearTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -pl springai-code-tui test -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: 新用例 PASS；全量 1831+ 全绿（全仓已核实无既有测试/冒烟断言旧的单行文案，`clear_smoke.py` 断言的是帮助行——不需要改任何既有断言）。

- [ ] **Step 5: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/CodeTuiViewClearTest.java
git commit -m "$(cat <<'EOF'
feat(code-tui): /clear 屏障降级提示拆两条独立行并收敛双分支 helper

屏障不成立时 writer 在飞旧批仍会晚到，旧内容会出现在「新会话」
分割线之后；独立说明行消除困惑。两处降级分支收敛为共用 helper
防文案漂移。
EOF
)"
```

---

### Task 3: 大延迟批写侧分片（>软预算单批切片入队）

**Files:**
- Modify: `springai-tamboui-inline-patch/src/main/java/dev/tamboui/inline/AsyncPtyWriter.java`（`submit` 的队空豁免路径）
- Test: `springai-tamboui-inline-patch/src/test/java/dev/tamboui/inline/AsyncPtyWriterTest.java`（新增 1 例 + 修订既有 1 例）

**Interfaces:**
- Consumes: 无新依赖。
- Produces: `submit(String)` 行为细化——队空豁免改为「分片豁免」：`cost > byteBudget` 的单批按 `byteBudget` 切片串行入队（对外仍一次调用、语义不变）；签名不变，调用方无感。无新常量、无新公开成员。

**背景：** 规格文档 §5.1/§10：>1MiB 的合并延迟批现在靠队空豁免**整体**占一个队列位，写完前小帧（打字回显）全排在它后面。分片后每个分片 ≤1MiB，小帧可在分片间入队（ArrayBlockingQueue FIFO），延迟分布平滑。字节序 = 提交序不变。

**既有用例修订（必须做，否则 Step 4 必红）：** `oversizedBatchAcceptedOnEmptyQueue` 现断言 `assertEquals(1, backend.writtenCount())`——分片后 20KiB 会以 3 片落盘，该断言必须同步放宽（本任务 Step 1 一并改）。

- [ ] **Step 1: 写失败测试 + 修订既有用例**

`AsyncPtyWriterTest.java` 的 `RecordingBackend` 桩补字符计数（在现有 `chunks.add(data)` 处累加）：

```java
        private final AtomicInteger chars = new AtomicInteger();
        @Override public void writeRaw(String data) {
            chunks.add(data);
            chars.addAndGet(data.length());
        }
        int totalCharsWritten() { return chars.get(); }
```

（若现有桩写法略有出入，在现有 `writeRaw` 里加 `chars.addAndGet(data.length())` 即可，断言不变。）

既有用例 `oversizedBatchAcceptedOnEmptyQueue` 的断言改为（分片后 20KiB 多片落盘）：

```java
            assertTrue(writer.submit(payload(20 * 1024)),
                    "队空豁免：20KiB 批 > 8KiB 软预算，队空时必须接受（前进性）");
            assertTrue(writer.flush().await(2, TimeUnit.SECONDS));
            assertEquals(20 * 1024, backend.totalCharsWritten(), "分片不得丢字（字符级总量）");
```

（片数断言移交下面的新增用例钉住；本用例改为钉字符级总量不丢字——分片不得丢字。）

新增用例（放在该用例之后）：

```java
    /**
     * 超软预算单批的分片契约（规格 §10 写侧平滑）：对外一次 submit 返回 true；
     * 队列侧按 ≤软预算切片、按提交序落盘——顺序钉（拼接还原原 payload）防乱序，
     * 片长钉（每片 ≤ 软预算）防「假分片」。
     */
    @Test
    void oversizedSingleBatchIsShardedInOrderIntoBudgetSizedChunks() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        try (AsyncPtyWriter writer = new AsyncPtyWriter(backend, 8 * 1024)) {
            String payload = payload(20 * 1024);
            assertTrue(writer.submit(payload), "队空时超预算单批必须接受（对外语义不变）");
            writer.flush();
            assertTrue(writer.awaitFlushed(3, TimeUnit.SECONDS));
            assertTrue(backend.writtenCount() >= 2,
                    "20KiB 对 8KiB 预算应分片为多次 writeRaw（实测 " + backend.writtenCount() + " 次）");
            assertEquals(payload, String.join("", backend.chunks),
                    "分片必须保序、不丢不重（字节序 = 提交序）");
            for (String piece : backend.chunks) {
                assertTrue(piece.length() <= 8 * 1024,
                        "每片不得超过软预算（实测 " + piece.length() + " chars）");
            }
        }
    }

    /**
     * 分片边界不得切开 UTF-16 代理对（审核 MAJOR-1）：被切开的两片各自经终端
     * PrintWriter 编码 UTF-8 时，不成对代理落为替换符 {@code ?}——内容永久损坏。
     * 奇数 ASCII 前缀 + 重复星面字符构造 >8KiB 批，使 8192 边界必然落在某个代理对中间。
     *
     * <p><b>断言必须有分辨力（终审 R5）</b>：RecordingBackend 只记录 String 引用，
     * char 层切开再拼回 equals 恒真——观测不到损坏。故本例用<b>往返编码桩</b>：
     * writeRaw 时做一次 UTF-8 编码再解码（new String(data.getBytes(UTF_8), UTF_8)，
     * 与生产 PrintWriter 的编码路径同损坏语义），孤立代理立即变 ?，拼接立红。
     * 验证方式：临时删掉实现里的代理对回退（end--）跑本例必须红，恢复后必须绿。
     */
    @Test
    void shardingNeverSplitsSurrogatePairs() throws Exception {
        RoundTripBackend backend = new RoundTripBackend();
        try (AsyncPtyWriter writer = new AsyncPtyWriter(backend, 8 * 1024)) {
            String astral = "\uD834\uDD1E";   // U+1D11E（𝄞），高+低代理对
            StringBuilder sb = new StringBuilder("x");   // 奇数前缀：8192 边界落在代理对中间
            while (sb.length() < 20 * 1024) {
                sb.append(astral);
            }
            String payload = sb.toString();
            assertTrue(writer.submit(payload));
            writer.flush();
            assertTrue(writer.awaitFlushed(3, TimeUnit.SECONDS));
            assertEquals(payload, String.join("", backend.chunks),
                    "分片边界切开代理对 → 孤立代理经 UTF-8 往返变 ? → 拼接损坏——"
                            + "实现必须回退边界避开代理对");
        }
    }

    /** 往返编码桩：模拟生产 PrintWriter 的 UTF-8 编码路径（孤立代理 → ?）。 */
    private static final class RoundTripBackend extends NoopBackend {
        final java.util.List<String> chunks = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void writeRaw(String data) {
            chunks.add(new String(data.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.charset.StandardCharsets.UTF_8));
        }

        @Override
        public void flush() {
        }
    }
```

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn -pl springai-tamboui-inline-patch test -Dtest=AsyncPtyWriterTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL——`应分片为多次 writeRaw` 红（现状 20KiB 是 1 次 writeRaw）。

- [ ] **Step 3: 实现分片豁免（整方法替换，只有这一份实现）**

用下面整段替换 `AsyncPtyWriter.submit`（保留类内其余成员不动）：

```java
    /**
     * 提交一批待写字节；饱和时<b>立即返回 false，绝不在调用线程上等</b>。
     *
     * <p>两级判定：大批（&gt; 软预算/16，scrollback 批粒度）撞软预算即拒；
     * 小帧（live 差分粒度）豁免软预算、只撞硬预算（2×软预算）才拒。
     * 调用方被拒时的正确动作：作废自己的增量基线（如
     * {@code InlineDisplay.previousFrameValid = false}），恢复后全量重画自愈。
     *
     * <p><b>队空豁免（前进性保证）</b>：队列已排空（{@code current == 0}）时任何
     * 单批都接受——否则「大于软预算的批」（延迟批合并产物、超大单批）会
     * 0 + cost &gt; budget 恒真、<b>永远无法入队</b>，恢复后死锁。
     *
     * <p><b>豁免的分片化（规格 §10 写侧平滑）</b>：cost &gt; 软预算的豁免批按
     * 软预算切片串行入队——单个超批不再整体占一个队列位，后续小帧可在分片之间
     * 入队，不被挡到批尾。字节序 = 提交序不变；写线程按片释放计费。
     *
     * <p><b>计费不变量（审核 BLOCKER-1）</b>：{@code bytesQueued} 恒等于「已入队
     * 片的字符和 + 零字节 FLUSH 标记（不占计费）」。分片中途 offer 失败时必须
     * 即时回滚<b>未入队尾片</b>的计费——否则 bytesQueued 永久虚高、isSaturated
     * 恒真、上层背压闸永久关死（freeze-forever 回归，且无自愈路径：只有 markDead
     * 会清零）。失败场景并非只有「4GiB payload 占满条目」：零字节的 FLUSH 标记
     * 同样占条目数（写线程卡在 flush 时队列可为「计费 0 但条目满」），故回滚是
     * 必需而非防御。已入队的是本批前缀（写线程照写、保序无害），按已接受返回。
     *
     * <p><b>代理对不切分（审核 MAJOR-1）</b>：UTF-16 高/低代理对被边界切开时，
     * 两片各自经 PrintWriter 编码 UTF-8，不成对代理落为替换符 {@code ?}——
     * 内容永久损坏（模型输出的 emoji/扩展区 CJK 常见）。边界回退 1 char 避开。
     */
    public boolean submit(String payload) {
        if (payload == null || payload.isEmpty() || closed.get() || dead.get()) {
            return true;   // 空/关闭态视为已接受（no-op 语义）
        }
        int cost = payload.length();
        int smallFrameThreshold = Math.max(1, byteBudget / 16);
        boolean small = cost <= smallFrameThreshold;
        long limit = small ? byteBudget * 2L : byteBudget;
        while (true) {
            long current = bytesQueued.get();
            if (current != 0 && current + cost > limit) {
                return false;   // 饱和即拒（isSaturated 由 bytesQueued 直读）
            }
            if (bytesQueued.compareAndSet(current, current + cost)) {
                break;
            }
        }
        if (cost <= byteBudget) {
            if (!queue.offer(new Chunk(payload))) {
                bytesQueued.addAndGet(-cost);   // 条目数满：回滚字节预算
                return false;
            }
            return true;
        }
        // 分片路径：按软预算切片、串行入队；边界避开代理对；失败回滚尾片计费。
        int offered = 0;
        for (int at = 0; at < cost; ) {
            int end = Math.min(cost, at + byteBudget);
            if (end < cost && Character.isHighSurrogate(payload.charAt(end - 1))
                    && Character.isLowSurrogate(payload.charAt(end))) {
                end--;                      // 边界落在代理对中间：回退 1 char（片短 1 char 无碍）
            }
            if (end == at) {                // 保险：budget 过小（<2）时仍前进 2 char 防死循环
                end = Math.min(cost, at + 2);
            }
            if (!queue.offer(new Chunk(payload.substring(at, end)))) {
                bytesQueued.addAndGet(-(cost - offered));   // 回滚未入队尾片计费（审核 BLOCKER-1）
                return true;   // 罕见部分接受：前缀已入队（保序无害），计费与实际入队严格一致
            }
            offered = end;
            at = end;
        }
        return true;
    }
```

- [ ] **Step 4: 跑测试确认通过 + 全模块回归**

```bash
mvn -pl springai-tamboui-inline-patch test -Dtest=AsyncPtyWriterTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -pl springai-tamboui-inline-patch test
```

Expected: 新用例 PASS（3 片、拼接还原、片长 ≤8KiB）；既有用例（含 Step 1 修订后的 `oversizedBatchAcceptedOnEmptyQueue`）全绿；全模块 67+ 全绿。分片路径只影响 `cost > byteBudget` 的提交——小帧与其余用例（单批 ≤32KiB < 64KiB 预算等）走原路径，行为逐字节不变。

- [ ] **Step 5: Commit**

```bash
git add springai-tamboui-inline-patch/src/main/java/dev/tamboui/inline/AsyncPtyWriter.java \
        springai-tamboui-inline-patch/src/test/java/dev/tamboui/inline/AsyncPtyWriterTest.java
git commit -m "$(cat <<'EOF'
feat(tui): 超预算单批分片入队——小帧延迟不再被单个超批放大

队空豁免原本让 >1MiB 的合并延迟批整体占一个队列位，写完前小帧
（打字回显）全部排在它后面。改为按软预算切片串行入队：字节序
不变（分片保序由测试钉住），分片间可插入小帧，回显延迟分布平滑。
EOF
)"
```

---

### Task 4: 收尾——规格 §10 更新 + 全量验收

**Files:**
- Modify: `docs/superpowers/specs/2026-09-02-code-tui-async-pty-writer-design.md`（§10 后续工作）

**Interfaces:**
- Consumes: Task 1/2/3 的交付物。
- Produces: 规格文档 §10 三项标记完成；验收记录。

- [ ] **Step 1: 更新规格 §10（并补 §5.1 分片化一句）**

把 §10 列表改为（保留人工项）：

```markdown
## 10. 后续工作

- ~~pty 实机验收：读端停摆冒烟~~ 已交付：`stalled_terminal_smoke.py`
  （顺序判据 + 证红开关；见 plans/2026-09-02-pty-async-verification-and-polish.md Task 1）。
- ~~屏障降级分支的外观优化（提示行）~~ 已交付：/clear 降级两条独立提示行。
- ~~大延迟批的写侧分片~~ 已交付：AsyncPtyWriter 队空豁免分片化。
- 真实 Terminal.app + 中文 IME 下「输出高峰 + 拼字」长测（人工动作，无法自动化）。
```

并在 §5.1 的「队空豁免（前进性）」条目末尾补一句（实现已分片化，文档对齐）：

```markdown
   ＞B 的豁免单批按 B 切片串行入队（2026-09 分片化：单个超批不再整体占一个
   队列位，小帧可在分片间插入；分片边界避开 UTF-16 代理对）。
```

- [ ] **Step 2: 全量验收（注意顺序——patch 改动必须先 install，冒烟与 code-tui 测试才用上新 jar）**

```bash
mvn -q -pl springai-tamboui-inline-patch -am install -DskipTests
/usr/bin/python3 springai-code-tui/src/test/resources/scripts/stalled_terminal_smoke.py
mutlog=$(mktemp)
CODETUI_STALLED_MUTATE_SYNC_WRITE=1 /usr/bin/python3 \
    springai-code-tui/src/test/resources/scripts/stalled_terminal_smoke.py > "$mutlog" 2>&1
mutstatus=$?
cat "$mutlog"   # 展示完整日志供人核（不用管道 tee——其退出码会吞掉 python 的）
if [ "$mutstatus" -eq 0 ]; then
  echo "MUTATION DID NOT REDDEN — 冒烟失去证红能力"; rm -f "$mutlog"; exit 1
fi
grep -q "MUTATION RED" "$mutlog" || { echo "退出非零（$mutstatus）但红在别处（非证红断言）——检查日志"; rm -f "$mutlog"; exit 1; }
rm -f "$mutlog"
echo "MUTATION RED as expected（且红在证红断言上）"
mvn -pl springai-tamboui-inline-patch test
mvn -pl springai-code-tui test -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: 冒烟 PASS（含 `SMOKE PASS`）；mutation 分支打印 `MUTATION RED as expected`；两模块测试全绿。

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/specs/2026-09-02-code-tui-async-pty-writer-design.md
git commit -m "$(cat <<'EOF'
docs(spec): pty 异步写 §10 三项后续工作标记完成

读端停摆冒烟、/clear 降级提示行、超预算批分片均已交付；
§5.1 补分片化说明。剩余仅真实 Terminal.app + IME 人工长测。
EOF
)"
```

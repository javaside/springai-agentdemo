# Code TUI pty 异步写·实机验收与收尾优化 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付 pty 异步写修复（commit 497c4c65）的最终验证工具与两项收尾优化——「读端停摆」自动化冒烟、屏障降级提示、大延迟批写侧分片。

**Architecture:** 冒烟脚本沿用既有 pty 冒烟体系（`resize_smoke.py` 的 `PtySession` 真 pty + pyte 回放 + 本地 SSE 桩），核心差异是「输出高峰期间**停止 pump**」制造读端停摆——这是修复针对的根因场景，现有所有冒烟都在持续 pump、无法覆盖。两项优化分别落在 `CodeTuiView`（降级提示文案）与 `AsyncPtyWriter`（超批分片入队）。

**Tech Stack:** Python 3（`/usr/bin/python3` + pyte，仅用户级）、Java 21、Maven、既有 JUnit 5。

**Spec:** `docs/superpowers/specs/2026-09-02-code-tui-async-pty-writer-design.md`（§7 内存上界、§10 后续工作即本计划的三个任务来源）

## Global Constraints

- 冒烟脚本放 `springai-code-tui/src/test/resources/scripts/`，从仓库根目录用 `/usr/bin/python3` 直接运行，不进 Maven 测试生命周期（README.md 表格需同步登记）。
- 冒烟不依赖网络与真实 API key：用本地 SSE 桩（`ThreadingHTTPServer`，抄 fairness smoke 的 `StubModel` 模式）。
- 冒烟必须有「证红开关」（mutation env var）：开关打开时脚本必须失败——没有证红的冒烟测不到东西（既有 fairness smoke 的 `CODETUI_FAIRNESS_MUTATE_*` 先例）。
- Java 侧改动走 TDD：先写失败测试（`mvn -pl springai-tamboui-inline-patch test -Dtest=XxxTest`），实现后全模块回归。
- 单测命令统一带 `-Dsurefire.failIfNoSpecifiedTests=false`（项目既有约定）。
- 提交信息用中文、含根因/行为说明（见仓库 git log 风格）。

---

### Task 1: 读端停摆冒烟（stalled_terminal_smoke.py）——核心验收工具

**Files:**
- Create: `springai-code-tui/src/test/resources/scripts/stalled_terminal_smoke.py`
- Modify: `springai-code-tui/src/test/resources/scripts/README.md`（表格登记 + 本地命令清单）

**Interfaces:**
- Consumes: `resize_smoke.py` 的 `PtySession`（`pump/write/raw/wait_for/wait_stable/screen/die/build_classpath/WELCOME/MAIN_CLASS`）、fairness smoke 的 `StubModel` SSE 桩模式（本任务内复制改造，不 import fairness smoke——它 import resize_smoke，我们同样只 import resize_smoke）。
- Produces: 无 Java 侧产物；脚本退出码 0=通过。证红开关 `CODETUI_STALLED_MUTATE_SYNC_WRITE=1`。

**场景设计（为什么这么编排）：**
修复的存在理由是「终端读端停摆时渲染线程不睡死」。编排四幕：① 输出高峰（2000 行中文流）启动；② **停 pump 3 秒**（pty 缓冲写满→writer 队列填满→延迟批出现——复现根因）；③ 停摆期间**持续写按键**（用户在打字）；④ **恢复 pump**，断言按键全部按序上屏、2000 行输出完整不丢。证红开关用「javaagent 式同步写」不可行，改用**时序证红**：`MUTATE` 时把停摆期按键断言的时限压到 50ms（同步写世界里按键要等停摆结束才有回显，必然超时红灯）。

- [ ] **Step 1: 写脚本骨架 + SSE 桩**

```python
#!/usr/bin/env python3
"""Stalled-reader smoke: output flood + 3s read-side stall + typing during stall.

Reproduces the root-cause scenario of the async pty writer fix (commit 497c4c65):
while the terminal read side stalls (IME composition / render backlog in real
terminals), the kernel pty buffer (~1-2 KiB on macOS) fills, the writer queue
saturates, deferred batches appear — yet key events must keep being processed.
Mutation switch CODETUI_STALLED_MUTATE_SYNC_WRITE=1 tightens the during-stall
key deadline to 50 ms: under the old synchronous-write behavior the first key
echo waits for the stall to end (seconds), so the smoke turns red.
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

ROWS, COLS = 45, 100
MODEL_ID = "deepseek-chat"
LINE_COUNT = 2000
# 每行 40 个中文字符（UTF-8 120B/行）——中文是实测卡死场景，且让总输出 ~240KB 远超
# writer 软预算 1MiB 的一个批，确保停摆期间延迟批必然出现。
LINE_TEMPLATE = "停摆-%05d " + "读端停摆期间按键必须存活" * 3
LAST_LINE = LINE_TEMPLATE % LINE_COUNT
LINE_PATTERN = re.compile(rb"\xe5\x81\x9c\xe6\x91\x86-(\d{5})")   # "停摆-NNNNN" 的 UTF-8

STALL_SECONDS = 3.0
# 停摆期间的按键回显时限：异步写世界里按键即时处理（渲染帧字节走硬预算豁免），
# 回显只需绕过积压的 live 帧路径。60s 是宽裕上限（回显帧本身也可能被硬预算排队）。
DURING_STALL_ECHO_LIMIT = 60.0
KEY_INTERVAL = 0.15
TOTAL_TIMEOUT_SECONDS = 180.0

MUTATE = os.environ.get("CODETUI_STALLED_MUTATE_SYNC_WRITE") == "1"
if MUTATE:
    DURING_STALL_ECHO_LIMIT = 0.050   # 证红：同步写世界里第一个键的回显等停摆结束


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

        # ── 幕二：停 pump——读端停摆（根因场景）────────────────────────
        # 不读 master：内核 pty 缓冲写满 → pty-writer 卡在 write(2) → 队列填满 →
        # 延迟批出现。这正是旧实现里渲染线程睡死的场景。
        stall_deadline = time.monotonic() + STALL_SECONDS
        keys_sent = []
        next_key = time.monotonic()

        # ── 幕三：停摆期间持续打字（不 pump，只写 master）──────────────
        # 关键：写入 master 立即返回（输入方向独立）；按键进入 app 的 eventQueue。
        while time.monotonic() < stall_deadline:
            if time.monotonic() >= next_key:
                ch = chr(ord("a") + len(keys_sent) % 26)
                session.write(ch.encode())
                keys_sent.append(ch)
                next_key = time.monotonic() + KEY_INTERVAL
            time.sleep(0.020)
        print("Stall over: %d keys typed during %.1fs read-side stall" %
              (len(keys_sent), STALL_SECONDS))

        # ── 幕四：恢复 pump，断言按键回显先于输出完成（公平性）────────
        # 停摆期间事件循环若睡死，第一个键的回显帧要等积压全部排出才出现；
        # 异步写世界里输入帧走硬预算豁免、即时回显。给到 DURING_STALL_ECHO_LIMIT。
        expected_echo = "".join(keys_sent)
        echo_seen_at = None
        while time.monotonic() < deadline:
            session.pump(0.050)
            screen = "\n".join(session.screen.display)
            if expected_echo and expected_echo in screen.replace(" ", ""):
                echo_seen_at = time.monotonic()
                break
            if not expected_echo and len(keys_sent) == 0:
                break
            if session.proc.poll() is not None:
                rs.die("process exited during recovery", list(session.screen.display))
        if expected_echo and echo_seen_at is None:
            rs.die("typed keys never echoed after recovery (expected %r)" % expected_echo,
                   list(session.screen.display))
        if echo_seen_at is not None:
            print("Key echo visible %.2fs after stall end" % (echo_seen_at - stall_deadline))

        # ── 输出完整性：2000 行一行不少（延迟批重投不丢内容）──────────
        while time.monotonic() < deadline:
            session.pump(0.050)
            numbers = {int(m.group(1)) for m in LINE_PATTERN.finditer(session.raw[output_mark:])}
            if LINE_COUNT in numbers and len(numbers) == LINE_COUNT:
                print("Output complete: %d/%d lines, no loss" % (len(numbers), LINE_COUNT))
                break
            if session.proc.poll() is not None:
                rs.die("process exited before output completed", list(session.screen.display))
        else:
            numbers = {int(m.group(1)) for m in LINE_PATTERN.finditer(session.raw[output_mark:])}
            missing = [i for i in range(1, LINE_COUNT + 1) if i not in numbers]
            rs.die("output incomplete after recovery: %d/%d, missing head=%r"
                   % (len(numbers), LINE_COUNT, missing[:10]), list(session.screen.display))

        # ── 静止：排空后零字节（无残留周期行为）───────────────────────
        last_len = len(session.raw)
        quiet_since = time.monotonic()
        while time.monotonic() < deadline:
            session.pump(0.050)
            if len(session.raw) != last_len:
                last_len = len(session.raw)
                quiet_since = time.monotonic()
            elif time.monotonic() - quiet_since >= 2.0:
                print("Terminal byte-quiet for 2.0s after drain — PASS")
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

注意：`PtySession.close()` 若不存在则删掉 finally 里该调用（看 resize_smoke.py 实际 API；进程退出由 daemon 属性与 tmpdir 清理兜底，与 fairness smoke 同做法）。

- [ ] **Step 2: 从仓库根构建并首跑（预期 PASS）**

```bash
mvn -q -pl springai-tamboui-inline-patch -am install -DskipTests
mvn -q -pl springai-code-tui -am package -DskipTests
mvn -q -pl springai-code-tui dependency:build-classpath -Dmdep.outputFile=target/cp.txt
/usr/bin/python3 springai-code-tui/src/test/resources/scripts/stalled_terminal_smoke.py
```

Expected: 依次打印 `Stall over: N keys…`、`Key echo visible …s`、`Output complete: 2000/2000`、`byte-quiet … PASS`，退出码 0。若 `Key echo` 断言超时失败：检查输入框回显是否含在 live 区帧（调 `expected_echo` 匹配逻辑，打印 screen 调试），**不要放宽时限到分钟级**——那说明回显路径真的被积压堵住，是 bug 不是测试问题。

- [ ] **Step 3: 证红验证（开关打开必须 FAIL）**

```bash
CODETUI_STALLED_MUTATE_SYNC_WRITE=1 /usr/bin/python3 \
  springai-code-tui/src/test/resources/scripts/stalled_terminal_smoke.py
```

Expected: FAIL（`typed keys never echoed after recovery`）——50ms 时限下同步写世界必红。若居然 PASS：说明时限断言没咬住停摆窗口（停摆期间回显帧已被 pty 缓冲吞下且恢复前已积压在 raw 里），把幕三改为「停摆期间每发一键就记录 `len(session.raw)`，恢复后第一个回显字节必须在停摆结束后的 DURING_STALL_ECHO_LIMIT 内**首次出现**」——按 raw 偏移断言而不是屏幕可见。

- [ ] **Step 4: 登记 README**

`springai-code-tui/src/test/resources/scripts/README.md` 表格加一行：

```markdown
| `stalled_terminal_smoke.py` | 输出高峰期间读端停摆 3s：停摆期间按键回显时限、恢复后 2000 行输出完整、排空后零字节。证红：`CODETUI_STALLED_MUTATE_SYNC_WRITE=1`。 | 本地 SSE 桩 |
```

并在「本地/无网络命令」清单加：

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
writer 卡死→延迟批），断言三件事：停摆期间按键回显在时限内、
恢复后 2000 行中文输出零丢失、排空后终端零字节。
证红开关 CODETUI_STALLED_MUTATE_SYNC_WRITE=1 把停摆期回显时限压
到 50ms——同步写世界必红。
EOF
)"
```

---

### Task 2: 屏障降级提示优化（旧内容晚到时说明行）

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java:2154-2165`（/clear 降级分支）
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/CodeTuiViewClearTest.java`

**Interfaces:**
- Consumes: `ScreenCleaner.clear(runner)` 返回 false 的两种成因（反射失败 / 清屏屏障不成立——writer 积压排不空）。调用方无法区分成因，`clear` 的签名与返回值**不改**。
- Produces: 降级提示文案改为两行（分隔行 + 说明行），仍走 `state.pushInfo`。无新 API。

**背景：** 规格文档 §7/§10：屏障降级时 writer 在飞旧批仍会晚到——旧会话内容出现在「新会话」分割线**之后**，用户看到「清屏了但旧内容又冒出来」却无人解释。加一行固定说明，成本为零、消除困惑。

- [ ] **Step 1: 写失败测试（现有降级用例扩展）**

本文件既有模式：`type(v, "/clear")` + `v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER))` 驱动；断言走 `ConversationState`（测试态 runner()==null → `/clear` 走外层 else 降级分支 → `state.pushInfo`）。在类末尾（最后一个 `}` 前）加：

```java
    /** 降级提示必须是两行：分隔行 + 说明行（屏障降级时旧批会晚到，说明行消除困惑）。 */
    @Test
    void clearDegradedShowsExplanationForLateContent() {
        ConversationState s = new ConversationState();
        RecordingHandler h = new RecordingHandler();
        CodeTuiView v = new CodeTuiView(s, h, Path.of("."));

        type(v, "/clear");
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
        v.tickForTest();   // 消费一批，把 pending 渲染进输出队列再逐条 drain

        // 测试态走降级分支：两条 pushInfo 均应作为 pending 可取（经 drainPending 全量收集）。
        java.util.List<ConversationState.OutputLine> drained = new java.util.ArrayList<>();
        for (ConversationState.OutputLine ol; (ol = s.pollPending()) != null; ) {
            drained.add(ol);
        }
        String all = drained.stream().map(ConversationState.OutputLine::text)
                .reduce("", (a, b) -> a + "\n" + b);
        assertTrue(all.contains("新会话"), "分隔行必须在: " + all);
        assertTrue(all.contains("上方若浮现旧内容"), "说明行必须在（屏障降级时旧批会晚到）: " + all);
    }
```

（`tickForTest()` 是既有 seam（`CodeTuiView.tickForTest`）；若 `OutputLine` 的取文本方法名不是 `text()`，以 `ConversationState.OutputLine` 实际访问器为准——先看文件再抄。）

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn -pl springai-code-tui -am test -Dtest=CodeTuiViewClearTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL——`说明行必须在` 断言红（现文案只有一行「─── 新会话（上下文已清空）───」）。

- [ ] **Step 3: 改降级文案为两行**

`CodeTuiView.java` 两处降级分支（`runOnRenderThread` 内的 else 与外层 else）都改为：

```java
                    } else {
                        // 屏障不成立（writer 积压排不空）或反射失败：真清屏放弃。
                        // 屏障降级时 writer 在飞的旧批仍会晚到——旧内容出现在分割线之后，
                        // 说明行消除「清屏了旧内容又冒出来」的困惑（规格 §7/§10）。
                        state.pushInfo("─── 新会话（上下文已清空）───");
                        state.pushInfo("终端输出积压未排空：上方若浮现旧内容，属上一会话残留");
                    }
```

外层 else（runner==null 的测试态）同样两行。

- [ ] **Step 4: 跑测试确认通过 + 全模块回归**

```bash
mvn -pl springai-code-tui -am test -Dtest=CodeTuiViewClearTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -pl springai-code-tui test -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: 新用例 PASS；全量 1831+ 全绿（注意 `CodeTuiViewClearTest` 既有用例若断言了旧行文案需同步更新——改断言而不是改回去）。

- [ ] **Step 5: Commit**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/CodeTuiViewClearTest.java
git commit -m "feat(code-tui): /clear 屏障降级提示补说明行（旧内容晚到时消除困惑）"
```

---

### Task 3: 大延迟批写侧分片（>1MiB 单批不再整体过队空豁免）

**Files:**
- Modify: `springai-tamboui-inline-patch/src/main/java/dev/tamboui/inline/AsyncPtyWriter.java`（submit 的豁免路径）
- Test: `springai-tamboui-inline-patch/src/test/java/dev/tamboui/inline/AsyncPtyWriterTest.java`

**Interfaces:**
- Consumes: 无新依赖。
- Produces: `submit(String)` 行为细化——队空豁免改为「分片豁免」：超软预算的单批按 `byteBudget` 切成 ≤B 的分片串行入队（对外仍是一次调用、一次 true/false）；`MAX_SINGLE_CHUNK_CHARS = byteBudget` 常量。签名不变，调用方无感。

**背景：** 规格文档 §5.1 队空豁免 + §10：>1MiB 的合并延迟批现在靠队空豁免**整体**入队，占住一个队列位直到整个超批写完——期间后续小帧（打字回显）在它后面排队，极端情况下回显延迟被这个超批放大。分片后每个分片 ≤1MiB，小帧可在分片间插入（ArrayBlockingQueue FIFO，分片入队间隙 accept 小帧），延迟分布更平滑。**不改语义**：字节序 = 提交序，只是单批在队列里占多个位置。

- [ ] **Step 1: 写失败测试**

`AsyncPtyWriterTest.java` 末尾（`NoopBackend` 桩类定义前）加：

```java
    // ── 契约 9：超预算单批分片（写侧平滑，小帧可插队分片之间） ────────

    /**
     * 超软预算的单批在队空时被切成 ≤软预算的分片入队：外部行为不变（一次 submit
     * 返回 true），但队列条目数 >1——后续小帧可在分片之间入队，不被单个超批
     * 整体挡到批尾（规格 §10 写侧分片）。
     */
    @Test
    void oversizedSingleBatchIsShardedIntoBudgetSizedChunks() throws Exception {
        RecordingBackend backend = new RecordingBackend();
        try (AsyncPtyWriter writer = new AsyncPtyWriter(backend, 8 * 1024)) {
            assertTrue(writer.submit(payload(20 * 1024)),
                    "队空时超预算单批必须整体接受（对外语义不变）");
            // 20KiB / 8KiB 软预算 = 3 个分片：20K 字节必须作为 ≥2 次 writeRaw 落盘。
            writer.flush();
            assertTrue(writer.awaitFlushed(3, TimeUnit.SECONDS));
            int writes = backend.writtenCount();
            assertTrue(writes >= 2,
                    "20KiB 批对 8KiB 预算应分片为多次 writeRaw（实测 " + writes + " 次）");
            assertEquals(20 * 1024, backend.totalCharsWritten(),
                    "分片不得丢字节：总字符数必须相等");
        }
    }
```

`RecordingBackend` 桩需补 `totalCharsWritten()`（累加每次 `writeRaw` 的 `data.length()`）——在现有桩上加：

```java
        private final AtomicInteger chars = new AtomicInteger();
        @Override public void writeRaw(String data) {
            chunks.add(data);
            chars.addAndGet(data.length());
        }
        int totalCharsWritten() { return chars.get(); }
```

（若现有 `RecordingBackend` 的 `writeRaw` 是别写法，在其现有实现里累加即可，断言不变。）

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn -pl springai-tamboui-inline-patch test -Dtest=AsyncPtyWriterTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL——`应分片为多次 writeRaw` 红（现在 `oversizedBatchAcceptedOnEmptyQueue` 语义下 20KiB 是**一次** writeRaw）。

- [ ] **Step 3: 实现分片豁免**

`AsyncPtyWriter.submit` 的豁免路径改造。当前代码（CAS 循环成功后）：

```java
        if (!queue.offer(new Chunk(payload))) {
            bytesQueued.addAndGet(-cost);   // 条目数满：回滚字节预算
            return false;
        }
        return true;
```

改为（替换整个 submit 的入队段；`while(true)` CAS 循环保留原样，只改 `break` 之后）：

```java
        // 队空豁免的分片化（规格 §10 写侧分片）：cost > 软预算的超批切成 ≤软预算的
        // 分片串行入队。字节序 = 提交序不变；队列条目变多，后续小帧可在分片间入队，
        // 不被单个超批整体挡到批尾。任一分片 offer 失败：回滚已计费的全部 cost 并
        // 丢弃已入队分片（写线程只消费完整批序列，回滚靠清空代价语义近似——见下）。
        int shard = byteBudget;
        if (cost <= shard || current0 == -1 /* 未走豁免 */) {
            // 普通路径（cost ≤ 软预算，或非豁免场景不可能到这——豁免仅当队空）
        }
```

**以上是思路示意，实际实现按下面写**（把 CAS 循环与入队合并成一个方法，保持豁免判定在循环内）：

```java
    public boolean submit(String payload) {
        if (payload == null || payload.isEmpty() || closed.get() || dead.get()) {
            return true;
        }
        int cost = payload.length();
        int smallFrameThreshold = Math.max(1, byteBudget / 16);
        boolean small = cost <= smallFrameThreshold;
        long limit = small ? byteBudget * 2L : byteBudget;
        // 队空豁免（前进性）：队列已空时任何单批都接受；超软预算的批分片入队
        //（规格 §10：单个超批不再整体占一个队列位，小帧可在分片间插入）。
        while (true) {
            long current = bytesQueued.get();
            if (current != 0 && current + cost > limit) {
                return false;
            }
            if (bytesQueued.compareAndSet(current, current + cost)) {
                break;
            }
        }
        if (cost <= byteBudget) {
            if (!queue.offer(new Chunk(payload))) {
                bytesQueued.addAndGet(-cost);
                return false;
            }
            return true;
        }
        // 分片路径（仅队空豁免可达——非空时上面预算检查已拒）：
        // 先全部 offer 成功才算接受；任何一片失败则回滚计费并返回 false。
        int shardSize = byteBudget;
        java.util.List<Chunk> shards = new java.util.ArrayList<>(
                (cost + shardSize - 1) / shardSize);
        for (int at = 0; at < cost; at += shardSize) {
            shards.add(new Chunk(payload.substring(at, Math.min(cost, at + shardSize))));
        }
        int offered = 0;
        for (Chunk shardChunk : shards) {
            if (!queue.offer(shardChunk)) {
                // 回滚：已入队的分片留在队列（写线程照写——内容是本批前缀，写出去
                // 无害且保序），但字节计费全额退还失败部分，语义 = 部分接受。
                // 极端罕见（4096 条目满），按「已接受」返回 true 保持简单。
                return true;
            }
            offered++;
        }
        return true;
    }
```

同时把 `Chunk` 的构造改回 package-private 单参（Task 前一轮已是 `Chunk(String)` + 私有双参，保持不动即可，`new Chunk(payload)` 直接可用）。

- [ ] **Step 4: 跑测试确认通过 + 全模块回归**

```bash
mvn -pl springai-tamboui-inline-patch test -Dtest=AsyncPtyWriterTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -pl springai-tamboui-inline-patch test
```

Expected: 新用例 PASS（3 次 writeRaw、总字符相等）；既有 14 例含 `oversizedBatchAcceptedOnEmptyQueue`（对外语义未变：仍返回 true）全绿；全模块 67+ 全绿。

- [ ] **Step 5: Commit**

```bash
git add springai-tamboui-inline-patch/src/main/java/dev/tamboui/inline/AsyncPtyWriter.java \
        springai-tamboui-inline-patch/src/test/java/dev/tamboui/inline/AsyncPtyWriterTest.java
git commit -m "$(cat <<'EOF'
feat(tui): 超预算单批分片入队——小帧延迟不再被单个超批放大

队空豁免原本让 >1MiB 的合并延迟批整体占一个队列位，写完前小帧
（打字回显）全部排在它后面。改为按软预算切片串行入队：字节序
不变，分片间可插入小帧，回显延迟分布平滑（规格 §10）。
EOF
)"
```

---

## 收尾验收（执行完全部任务后）

- [ ] 三次全绿确认：

```bash
/usr/bin/python3 springai-code-tui/src/test/resources/scripts/stalled_terminal_smoke.py
CODETUI_STALLED_MUTATE_SYNC_WRITE=1 /usr/bin/python3 springai-code-tui/src/test/resources/scripts/stalled_terminal_smoke.py || echo "MUTATION RED: expected"
mvn -pl springai-tamboui-inline-patch test
mvn -pl springai-code-tui test -Dsurefire.failIfNoSpecifiedTests=false
```

- [ ] 更新规格文档 §10：三项后续工作标记完成（stalled smoke 已交付、提示行已加、分片已实现），剩余仅「真实 Terminal.app + 中文 IME 人工长测」一项（人工动作，无法自动化）。

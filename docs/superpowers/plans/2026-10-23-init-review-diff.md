# /init /review /diff Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 三个新斜杠命令：`/init`（agent 生成/改进项目级 AGENTS.md）、`/review`（agent 只读审查工作树）、`/diff`（不经模型，git diff 逐字打进 scrollback）。

**Architecture:** `/init`//`review` 是提示词命令（`/continue` 同构，零新机制）；`/diff` 走 `GitSnapshot`（包级小类，git 执行可注入接缝）+ daemon 线程 + 专用 `Kind.DIFF` 单色通道 + `diffEpoch` 代际守卫（模仿 main 上刚落地的 `sessionEpoch`）。

**Tech Stack:** Java 17、JUnit 5、ProcessBuilder（全仓生产首例，spec 申报）。

**Spec:** `docs/superpowers/specs/2026-10-23-init-review-diff-design.md`（12 条事实依据 + 4 条淘汰备选 + 设计评审驱动修订，执行者须先读）。

## Global Constraints

- 语言级别锁 17；注释/提交正文中文；注释写**为什么**。
- 单类测试命令：`mvn -pl springai-code-tui -am test -Dtest='XxxTest' -Dsurefire.failIfNoSpecifiedTests=false`。
- 分支 `feature/init-review-diff`；`feat:`/`docs:` 前缀；功能与测试同提交。
- 新增命令与全部既有命令无前缀包含（spec 事实 #12）；补全菜单自动适配，零额外接线。
- daemon 回推必须带代际守卫（`sessionEpoch` 同构）；`pushRawLines` 不受 `acceptingTurnId` 门控。

---

### Task 1: `GitSnapshot`（git 执行接缝）

**Files:**
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/GitSnapshot.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/GitSnapshotTest.java`

**Interfaces:**
- Consumes: 无（纯 JDK）。
- Produces:
  - `record GitSnapshot(String statLine, List<String> body, List<String> untracked, String error)`——error 非 null 时其余为空；`static GitSnapshot capture(Path root, GitCommand git)`；`static boolean isGitRepo(Path root, GitCommand git)`。
  - `@FunctionalInterface interface GitCommand { List<String> run(Path root, String... args) throws IOException; }` + `GitCommand PROCESS`（ProcessBuilder 默认实现：`git -C root <args>`，10s 超时，非零退出抛 IOException 带 stderr 首行）。
  - Task 3（/review 预检）与 Task 4（/diff）依赖。

- [ ] **Step 1: 写失败测试**

```java
package io.github.javaside.springai.codetui.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GitSnapshot：git 执行接缝（ProcessBuilder 是全仓生产首例——接缝即测试通道，spec 事实#6）。 */
class GitSnapshotTest {

    /** 真实 git 用例的公共准备：@TempDir 里 init + 配 user（先例 AgentToolsTodoReminderEndToEndTest）。 */
    private static void initRepo(Path dir) throws IOException, InterruptedException {
        runOk(dir, "git", "init", "-q");
        runOk(dir, "git", "config", "user.email", "t@t");
        runOk(dir, "git", "config", "user.name", "t");
    }

    private static void runOk(Path dir, String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).start();
        if (p.waitFor() != 0) {
            throw new IOException(String.join(" ", cmd));
        }
    }

    @Test
    void capturesDiffStatBodyAndUntracked(@TempDir Path dir) throws Exception {
        initRepo(dir);
        Files.writeString(dir.resolve("a.txt"), "one\n");
        runOk(dir, "git", "add", "a.txt");
        runOk(dir, "git", "commit", "-q", "-m", "init");
        Files.writeString(dir.resolve("a.txt"), "one\ntwo\n");
        Files.writeString(dir.resolve("new.txt"), "全新的文件，内容不必看\n");

        GitSnapshot s = GitSnapshot.capture(dir, GitSnapshot.PROCESS);

        assertNull(s.error(), "正常路径无错");
        assertNotNull(s.statLine(), "有 --stat 尾行");
        assertTrue(s.body().stream().anyMatch(l -> l.startsWith("+two")), "diff 正文含新增行，实际: " + s.body());
        assertTrue(s.body().stream().anyMatch(l -> l.startsWith("-one") || l.contains("a.txt")), "含被改文件");
        assertEquals(1, s.untracked().size(), "未跟踪只列一个");
        assertTrue(s.untracked().get(0).startsWith("new.txt"), "未跟踪列名（可带字节数），实际: " + s.untracked());
    }

    @Test
    void cleanTreeGivesEmptySnapshot(@TempDir Path dir) throws Exception {
        initRepo(dir);
        Files.writeString(dir.resolve("a.txt"), "x\n");
        runOk(dir, "git", "add", ".");
        runOk(dir, "git", "commit", "-q", "-m", "init");

        GitSnapshot s = GitSnapshot.capture(dir, GitSnapshot.PROCESS);

        assertNull(s.error());
        assertTrue(s.body().isEmpty(), "干净树正文为空");
        assertTrue(s.untracked().isEmpty());
    }

    @Test
    void nonGitDirOrBrokenGitGoesToErrorChannel(@TempDir Path dir) {
        GitSnapshot no = GitSnapshot.capture(dir, GitSnapshot.PROCESS);
        assertNotNull(no.error(), "非 git 目录走 error 通道而非异常");
        assertTrue(no.body().isEmpty());

        GitSnapshot broken = GitSnapshot.capture(dir,
                (root, args) -> { throw new IOException("git not found"); });
        assertEquals("git not found", broken.error(), "IOException 的 message 进 error");
    }

    @Test
    void isGitRepoDetectsInsideWorkTree(@TempDir Path dir) throws Exception {
        // ⚠ 假设声明：@TempDir 须不在任何 git 工作树内（rev-parse 会向上搜索父目录；JUnit 默认系统 tmpdir 满足）
        assertFalse(GitSnapshot.isGitRepo(dir, GitSnapshot.PROCESS));
        initRepo(dir);
        assertTrue(GitSnapshot.isGitRepo(dir, GitSnapshot.PROCESS));
    }

    @Test
    void largeDiffOverPipeBuffer_survives(@TempDir Path dir) throws Exception {
        // 主场景钉子：>64KB 的 diff（OS 管道缓冲量级）必须完整捕获——若实现退回「先 waitFor 后读管道」，
        // 此用例必以「git 超时」假失败（计划评审实证的互锁）。
        initRepo(dir);
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            big.append("line-").append(i).append("-").append("x".repeat(60)).append('\n');
        }
        Files.writeString(dir.resolve("big.txt"), "seed\n");
        runOk(dir, "git", "add", "big.txt");
        runOk(dir, "git", "commit", "-q", "-m", "init");
        Files.writeString(dir.resolve("big.txt"), big.toString());

        GitSnapshot s = GitSnapshot.capture(dir, GitSnapshot.PROCESS);

        assertNull(s.error(), "大 diff 不许超时: " + s.error());
        assertTrue(s.body().size() > 2000, "全部行捕获，实际: " + s.body().size());
    }

    @Test
    void stubCoversTimeoutAndNonZeroExit_channels(@TempDir Path dir) {
        // 桩测 error 通道两分支（spec 测试策略明文）：非零退出/超时由 IOException 消息承载进 error
        GitSnapshot.GitCommand failing = (root, args) -> { throw new IOException("fatal: not a git repository"); };
        assertEquals("fatal: not a git repository", GitSnapshot.capture(dir, failing).error());

        GitSnapshot.GitCommand timing = (root, args) -> { throw new IOException("git 超时：diff HEAD"); };
        assertTrue(GitSnapshot.capture(dir, timing).error().contains("超时"));
    }
}
```

- [ ] **Step 2: 跑测试确认红**

Run: `mvn -pl springai-code-tui -am test -Dtest='GitSnapshotTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败 `cannot find symbol: GitSnapshot`。

- [ ] **Step 3: 最小实现**

```java
package io.github.javaside.springai.codetui.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 一次 git 工作树快照（/diff 与 /review 预检共用）。git 执行抽成 {@link GitCommand} 接缝：
 * 这是全仓生产代码第一处 ProcessBuilder（spec 事实#6 申报）——不接缝则「git 不可用/非仓库/超时」
 * 分支在单测里永远造不出来（得操纵 PATH）。仓库探测不靠 .git 目录存在性：worktree 下它是文件。
 */
public record GitSnapshot(String statLine, List<String> body, List<String> untracked, String error) {

    /** 正常路径紧凑构造。 */
    public static GitSnapshot ok(String statLine, List<String> body, List<String> untracked) {
        return new GitSnapshot(statLine, List.copyOf(body), List.copyOf(untracked), null);
    }

    /** error 路径：其余字段一律空（调用方只看 error，不猜部分结果）。 */
    public static GitSnapshot failed(String error) {
        return new GitSnapshot(null, List.of(), List.of(), error);
    }

    @FunctionalInterface
    public interface GitCommand {
        List<String> run(Path root, String... args) throws IOException;
    }

    /**
     * ProcessBuilder 默认实现：stdout/stderr 重定向到临时文件——**刻意不走管道**。
     * 「先 waitFor 后读管道」在输出超过 OS 管道缓冲（约 64KB）时互锁：git 阻塞在写、
     * waitFor 永不返回、10s 超时假失败——而大 diff 恰是 /diff 的主场景（计划评审实证）。
     * 10s 超时 destroyForcibly；非零退出码抛 IOException（stderr 首行入消息）。
     */
    public static final GitCommand PROCESS = (root, args) -> {
        List<String> cmd = new ArrayList<>(args.length + 3);
        cmd.add("git"); cmd.add("-C"); cmd.add(root.toString()); cmd.addAll(List.of(args));
        Path outTmp;
        Path errTmp;
        Process p;
        try {
            outTmp = Files.createTempFile("codetui-git-out", ".tmp");
            errTmp = Files.createTempFile("codetui-git-err", ".tmp");
            p = new ProcessBuilder(cmd)
                    .redirectOutput(outTmp.toFile())
                    .redirectError(errTmp.toFile())
                    .start();
        } catch (IOException e) {
            throw new IOException("git 不可用：" + e.getMessage());
        }
        try {
            try {
                if (!p.waitFor(10, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    throw new IOException("git 超时：" + String.join(" ", args));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("git 被中断");
            }
            String stderr = Files.readString(errTmp, StandardCharsets.UTF_8).strip();
            if (p.exitValue() != 0) {
                throw new IOException(stderr.isEmpty() ? ("git 退出码 " + p.exitValue())
                        : stderr.lines().findFirst().orElse("git 失败"));
            }
            return Files.readAllLines(outTmp, StandardCharsets.UTF_8);
        } finally {
            try { Files.deleteIfExists(outTmp); } catch (IOException ignore) { }
            try { Files.deleteIfExists(errTmp); } catch (IOException ignore) { }
        }
    };

    /** 是否在 git 工作树内（/review 预检用）。 */
    public static boolean isGitRepo(Path root, GitCommand git) {
        try {
            return git.run(root, "rev-parse", "--is-inside-work-tree").equals(List.of("true"));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** 采集 staged+unstaged 合一的 diff（--stat 尾行 + 正文）与未跟踪文件（列名+字节数）。 */
    public static GitSnapshot capture(Path root, GitCommand git) {
        try {
            List<String> stat = git.run(root, "diff", "HEAD", "--stat");
            List<String> body = git.run(root, "diff", "HEAD");
            List<String> rawUntracked = git.run(root, "ls-files", "--others", "--exclude-standard");
            List<String> untracked = new ArrayList<>(rawUntracked.size());
            for (String name : rawUntracked) {
                long size;
                try {
                    size = Files.size(root.resolve(name));
                } catch (IOException e) {
                    size = -1;
                }
                untracked.add(size >= 0 ? name + " (" + size + " bytes)" : name);
            }
            String statLine = stat.isEmpty() ? "" : stat.get(stat.size() - 1);
            return ok(statLine, body, untracked);
        } catch (IOException | RuntimeException e) {
            return failed(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }
}
```

- [ ] **Step 4: 跑测试确认绿**

Run: `mvn -pl springai-code-tui -am test -Dtest='GitSnapshotTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 4/4 PASS。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/GitSnapshot.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/GitSnapshotTest.java
git commit -m "feat: GitSnapshot——git 执行接缝与工作树快照（/diff /review 共用）"
```

---

### Task 2: `Kind.DIFF` + `pushRawLines` + 着色

**Files:**
- Modify: `ui/ConversationState.java`（Kind 枚举 +1、`pushRawLines`）
- Modify: `ui/Theme.java`（**必改**：`styleFor` 在这里（94-108 行附近）且是穷举 switch 无 default——不加 `case DIFF` 整模块编译不过；加 `case DIFF -> null` 走基色）
- Modify: `ui/ScrollbackPrinter.java`（`lineCursor` 对 DIFF 行做 +绿/-红 前缀双样式选择——每条 OutputLine 单样式足够，不必多 span）
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/RawDiffChannelTest.java`（新建）

**Interfaces:**
- Consumes: 既有 `OutputLine(text, kind)` 双参构造、`Theme.styleFor`（Theme.java:94-108，包级静态穷举 switch）、`lineCursor`（ScrollbackPrinter，default 分支天然接住新 Kind——CodeTuiView 渲染路由 614 行 `default -> lineCursor` 已核实）、`pushInfo` 的锁模式（`ConversationState.java:282-289`）、`drainPending()`（返回 `List<OutputLine>`，ConversationState.java:533-538——**`pollPending()` 返回单条，别用错**）。
- Produces: `ConversationState.pushRawLines(List<String> lines, OutputLine.Kind kind)`——锁内逐行入 pending、锁外一次 publish；**不受 `acceptingTurnId` 门控**（UI 主动输出，非模型流）。Task 4 消费。

- [ ] **Step 1: 写失败测试**

```java
package io.github.javaside.springai.codetui.ui;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** pushRawLines + Kind.DIFF：UI 主动输出通道（不受 turnId 门控、不走 markdown）。 */
class RawDiffChannelTest {

    /** 照抄 ScrollbackPrinterTest 的记录型 Sink（两方法接口——不是函数式接口，不能 lambda）。 */
    private static final class RecordingSink implements ScrollbackPrinter.Sink {
        final List<String> lines = new ArrayList<>();
        @Override public void println(dev.tamboui.buffer.Text text) { lines.add(text.toString()); }
        @Override public void println(String s) { lines.add(s); }
    }

    @Test
    void pushRawLines_appendsAllLinesInOrder_regardlessOfAcceptingTurnId() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(7L);   // 有活跃回合——门控管的是模型流，UI 主动输出不受控，此处钉住该契约

        s.pushRawLines(List.of("+新增", "-删除", " context"), OutputLine.Kind.DIFF);

        List<ConversationState.OutputLine> lines = s.drainPending();
        assertEquals(3, lines.size(), "三行全进 pending");
        assertEquals("+新增", lines.get(0).text());
        assertEquals(OutputLine.Kind.DIFF, lines.get(1).kind());
        assertEquals(" context", lines.get(2).text(), "逐字不改写（前导空格保留）");
    }

    @Test
    void emptyListIsNoop_publishStillSafe() {
        ConversationState s = new ConversationState();
        s.pushRawLines(List.of(), OutputLine.Kind.DIFF);
        assertEquals(0, s.drainPending().size());
    }

    @Test
    void diffKindRendersVerbatim_notMarkdown() {
        // 正向断言（禁恒真写法）：DIFF 行经 printer.line() 落到 Sink 的文本逐字保留——
        // `#` 不被吃、`- xxx` 不变列表项、`|` 不触发表格攒块。若 DIFF 被误路由到 markdown
        // 游标，这三类行必被改写，断言红。
        RecordingSink sink = new RecordingSink();
        ScrollbackPrinter p = new ScrollbackPrinter(sink, Path.of("/work"), () -> 80);   // 三参构造（同 ScrollbackPrinterTest）
        p.line(new ConversationState.OutputLine("- 删掉的行 #不是标题", OutputLine.Kind.DIFF));
        p.line(new ConversationState.OutputLine("| a | b |", OutputLine.Kind.DIFF));
        p.line(new ConversationState.OutputLine("+新增行", OutputLine.Kind.DIFF));

        String all = String.join("\n", sink.lines);
        assertTrue(all.contains("- 删掉的行 #不是标题"), "`-` 与 `#` 原样存活，实际: " + all);
        assertTrue(all.contains("| a | b |"), "`|` 不触发表格，实际: " + all);
        assertTrue(all.contains("+新增行"), "`+` 原样存活，实际: " + all);
    }
}
```

（Sink/构造器形状以 `ScrollbackPrinterTest.java:22-35` 为准——执行时打开核对；断言目标不变：三类 markdown 敏感字符逐字存活。）

- [ ] **Step 2: 跑测试确认红**

Run: `mvn -pl springai-code-tui -am test -Dtest='RawDiffChannelTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败（`Kind.DIFF`/`pushRawLines` 不存在）。

- [ ] **Step 3: 最小实现**

`ConversationState`（Kind 枚举加 `DIFF`，pushInfo 旁加）：

```java
    /**
     * UI 主动输出通道（/diff 专用）：锁内逐行入 pending、锁外一次 publish——同 {@link #pushInfo} 的并发契约，
     * 但不受 {@code acceptingTurnId} 门控（它管的是模型流的迟到丢弃，这里是 UI 自己的输出）。
     */
    public void pushRawLines(java.util.List<String> lines, OutputLine.Kind kind) {
        if (lines.isEmpty()) {
            return;
        }
        Change change;
        synchronized (this) {
            for (String line : lines) {
                pending.add(new OutputLine(line, kind));
            }
            change = changed(UiDirty.OUTPUT | UiDirty.VIEW);
        }
        publish(change);
    }
```

`Theme.styleFor`（Theme.java:94-108，包级静态穷举 switch **无 default**——不加 case 编译不过，这正是 TDD 的第一个红）加：

```java
            case DIFF -> null;   // DIFF 基色由 lineCursor 按行前缀覆盖（Theme 是纯调色板，不放行为）
```

`ScrollbackPrinter` 的 `lineCursor`（或其调用的样式选择处，对齐实际结构）给 DIFF 行加**前缀单样式**：

```java
    // DIFF 行 +绿/-红/其余基色：每行单样式足够（前缀判断不改写内容）；色对用 Theme 既有增删色
    // （Theme.java:85-89 附近 Color.indexed(114)/(210)，与 DiffRenderer 的增删底色同源）。
    static Style diffStyleFor(String text) {
        if (text.startsWith("+")) return Style.color(114);   // 对齐 Theme 真实增色常量名
        if (text.startsWith("-")) return Style.color(210);   // 对齐 Theme 真实删色常量名
        return null;
    }
```

（常量名/色值以 Theme.java 85-89 的既有 ADD/DEL 定义为准——执行时打开对齐，别新造色值；`Style` 的真实类型名同样对齐。）

- [ ] **Step 4: 跑测试确认绿 + 打印器回归**

Run: `mvn -pl springai-code-tui -am test -Dtest='RawDiffChannelTest,ScrollbackPrinterTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 全 PASS。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/ConversationState.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/Theme.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/ScrollbackPrinter.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/RawDiffChannelTest.java
git commit -m "feat: Kind.DIFF 单色通道与 pushRawLines（/diff 的逐字输出管道）"
```

---

### Task 3: `/init` 与 `/review` 命令

**Files:**
- Modify: `ui/CodeTuiView.java`（COMMANDS 两行 + `/clear` 命令分支后两个命令分支；`/review` 预检）
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/CodeTuiViewInitReviewTest.java`（新建，桩照 `CodeTuiViewContinueDigestTest` 先例）

**Interfaces:**
- Consumes: Task 1 `GitSnapshot.isGitRepo`；既有 `dispatch(String, String)` / `state.enqueue(String, String)`（/continue 分支 `CodeTuiView.java:2588-2606` 的形状）、`GitSnapshot.PROCESS`。
- Produces: 两个命令分支（无外部接口；Task 5 文档引用）。

- [ ] **Step 1: 写失败测试**（桩与驱动照 ContinueDigestTest：`setInputForTest("/init")` + `feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER))`；桩记录 `dispatch`/`enqueue` 的入参——对齐 ContinueDigestTest 的记录型 SubmitHandler 形状）

用例：
1. `/init` 空闲：dispatch 被调，提示词含「`AGENTS.md`」「项目级」「重启」「改进」（四个关键句各断言一次——杀得死提示词被误改/截断的变异）；
2. `/init` 忙碌（`state.onTurnStarted(1L)`）：入队而非 dispatch，队列快照含同样关键句；
3. `/review` 空闲：dispatch 提示词含「只读」「正确性」「安全」「测试」「可维护性」「严重度」；
4. `/review` 非 git 目录（`@TempDir` 空目录即非仓库）：不 dispatch 不入队，`state.notice()` 含「git」；
5. 两命令进 `↑` 历史：`historySnapshotForTest` **不存在**——等价路径：跑完命令后 `feedKeyForTest(KeyEvent.ofKey(KeyCode.UP))` + `inputTextForTest()`（CodeTuiView.java:1495 附近）断言输入框回填 `/init`（`addHistory` 对斜杠命令统一生效，CodeTuiView.java:2465-2475）。

（执行时打开 `CodeTuiViewContinueDigestTest.java:43-103` 抄桩与断言形状——桩是记录型 SubmitHandler 记 `submitted`，闲路断 `submitted.get()`、忙路断 `state.queuedSnapshot()`；断言目标如上五条。闸门一律用 `busy()`（= state.isBusy() ‖ hasInFlightSubagents()，CodeTuiView.java:2718-2720）——Esc 取消后子 agent 收尾期也得入队，照抄 /continue。）

- [ ] **Step 2: 跑测试确认红**

Run: `mvn -pl springai-code-tui -am test -Dtest='CodeTuiViewInitReviewTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL（命令未注册，输入被当普通消息 dispatch——关键句断言必红）。

- [ ] **Step 3: 最小实现**

COMMANDS 表（`/permissions` 行后）两行：

```java
            new SlashCommand("/init",   "分析项目并生成/改进 AGENTS.md（重启后生效）"),
            new SlashCommand("/review", "只读审查工作树改动（正确性/安全/测试/可维护性）"),
```

命令分支（`/clear` 分支后，照 /continue 的 `busy → enqueue / 闲 → dispatch` 形状）：

```java
        if (cmd.equals("/init")) {                   // 提示词命令：agent 用既有工具自己干（/continue 同构）
            clearInput();
            String prompt = "请分析当前项目并生成项目级 AGENTS.md（目标固定为工作目录下的 AGENTS.md，"
                    + "不要碰用户级 ~/.codetui/AGENTS.md）。步骤：先读构建文件（pom.xml 等）、模块布局与测试约定，"
                    + "若已存在 AGENTS.md 则先通读并在保留其既有事实的基础上改进，不要整篇重写；"
                    + "内容只写「从代码推不出来的事实」（构建/测试命令、关键约定、注意事项），保持简洁。"
                    + "写完后简述你写了什么，并明确告诉用户：AGENTS.md 在启动时一次性加载，重启 code-tui 后生效。";
            if (busy()) state.enqueue(prompt, null); else dispatch(prompt, null);
            return;
        }
        if (cmd.equals("/review")) {                 // 只读审查；非 git 目录 UI 预检拒绝（白烧一回合不如一行 notice）
            clearInput();
            if (!GitSnapshot.isGitRepo(root, GitSnapshot.PROCESS)) {
                state.setNotice("当前目录不是 git 仓库，没有可审查的工作树改动");
                return;
            }
            String prompt = "请只读审查当前工作树的改动（不要动手修改，除非我明确要求）。"
                    + "先跑 git diff HEAD 与 git status（含未跟踪文件）了解全貌，再逐文件审阅改动内容，"
                    + "从正确性、安全、测试、可维护性四个维度找问题，按严重程度从高到低输出："
                    + "每条给出文件:行号、问题描述、为什么要紧、建议修法；没有问题的维度不用硬凑。"
                    + "未跟踪的新文件也要看（用 Read 读内容），但同样不要修改。";
            if (busy()) state.enqueue(prompt, null); else dispatch(prompt, null);
            return;
        }
```

（`dispatch` 的真实签名以 /continue 分支为准——评审引证为 `dispatch(prompt, null)`；若名字不同照抄 /continue 的调用形状。）

- [ ] **Step 4: 跑测试确认绿 + 命令回归**

Run: `mvn -pl springai-code-tui -am test -Dtest='CodeTuiViewInitReviewTest,CodeTuiViewContinueDigestTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 全 PASS。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/CodeTuiViewInitReviewTest.java
git commit -m "feat: /init 与 /review 提示词命令（/review 非 git 预检）"
```

---

### Task 4: `/diff` 命令（daemon + epoch + 单飞）

**Files:**
- Modify: `ui/CodeTuiView.java`（COMMANDS 一行 + 命令分支 + `diffEpoch`/`diffRunning` 字段 + daemon 方法；`/clear` 分支与 `switchToSession` 各 +1 行 `diffEpoch++`）
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/CodeTuiViewDiffTest.java`（新建）

**Interfaces:**
- Consumes: Task 1 `GitSnapshot.capture/PROCESS`、Task 2 `pushRawLines`/`Kind.DIFF`；既有 `sessionEpoch` 形状（main d5ebee34：volatile int、捕获-回写前校验）、`publishLocalViewChange`。
- Produces: `/diff` 命令（无外部接口）。**测试注入点**：`GitCommand` 经包级可覆写成员或构造默认 + 包级 setter（对齐测试桩最小侵入；推荐 `private volatile GitSnapshot.GitCommand diffGit = GitSnapshot.PROCESS;` 包级可写）。

- [ ] **Step 1: 写失败测试**（桩注入 `diffGit`；驱动同 Task 3；断言走 `state.drainPending()` 的 Kind/文本与顺序）

用例：
1. **正常输出**：桩返回 `GitSnapshot.ok("3 files changed", List.of("+a", "-b", "@@ -1 +1 @@"), List.of("new.txt (10 bytes)"))` → pending 序列：INFO（含 statLine 与「工作树改动」）→ 三行 `Kind.DIFF` 逐字 → INFO（含 `new.txt`）；等待轮询 `diffRunningForTest()` 变 false（daemon 完成信号——见下）再断言，不用裸 sleep；
2. **端到端渲染路由钉子**：用 CodeTuiView 的**四参测试构造器**（`CodeTuiView.java:422`，注入记录型 `ScrollbackPrinter.Sink`）驱动 `/diff`（桩 body 含 `- 删 #x`、`| a |`）→ 等 daemon 完成 → 断言 sink 收到的文本里 `-`/`#`/`|` 逐字存活——守护渲染路由（CodeTuiView.java:614 的 `default -> lineCursor`）不被改成送 markdown 游标；
3. **空改动**：`GitSnapshot.ok("", List.of(), List.of())` → 仅一行 INFO 含「干净」；
4. **error 通道**：`GitSnapshot.failed("git 不可用")` → 一行 ERROR 含「git 不可用」；
5. **代际守卫**（负断言必须有完成信号才具备杀死力）：CountDownLatch 桩挂起 → `/diff` → 执行 `/clear`（diffEpoch++）→ `finally` 放行 latch → 轮询 `diffRunningForTest()==false`（daemon 确已跑完）→ 断言 `drainPending()` **无任何 DIFF 行**（对照用例 1 的正向出现，「删掉 epoch 校验」变异可被杀死）；
6. **单飞**：latch 桩挂起期间第二次输入 `/diff` → notice 含「正在生成」（测试线程串行按键，无竞态）。

（实现侧须提供包级 `boolean diffRunningForTest()` 钩子——否则负断言在 daemon 未跑到写点时假绿；latch 释放务必放 finally。）

- [ ] **Step 2: 跑测试确认红**

Run: `mvn -pl springai-code-tui -am test -Dtest='CodeTuiViewDiffTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL（命令未注册）。

- [ ] **Step 3: 最小实现**

字段区（sessionEpoch 旁）：

```java
    /** /diff 代际：/clear 与会话切换时自增——在飞 diff daemon 的迟到批次据此弃写（sessionEpoch 同构）。 */
    private volatile int diffEpoch;
    /** /diff 单飞：git 快照通常亚秒级，但连按两次也不该两条交错输出（mcpConnecting 同构）。 */
    private volatile boolean diffRunning;
    /** git 执行接缝：生产 PROCESS；测试注入桩（GitSnapshot 的设计目的）。 */
    volatile GitSnapshot.GitCommand diffGit = GitSnapshot.PROCESS;
```

`/clear` 分支（`outputQueue.clear()` 行旁，实际 2506 行附近）与 `switchToSession`（其 `sessionEpoch++` 在 4401 行附近——`diffEpoch++` 紧挨它放，等价且更一致）各加一行：

```java
            diffEpoch++;   // 在飞 /diff 的迟到批次从此弃写——它的输出属于刚被清掉的会话画面
```

命令分支（/review 分支后）：

```java
        if (cmd.equals("/diff")) {                   // 不经模型：git 快照逐字打进 scrollback（忙时可看，只读无闸门）
            clearInput();
            if (diffRunning) {
                state.setNotice("正在生成 diff…");
                return;
            }
            int epoch = diffEpoch;
            diffRunning = true;
            Thread t = new Thread(() -> runDiff(epoch), "git-diff");
            t.setDaemon(true);
            t.start();
            return;
        }
```

daemon 方法（/resume 面板方法组附近）：

```java
    /**
     * git 快照 → 分批下沉。每批 ≤200 行（输出批 ≤300 行的预算内留余量给同批其它输出），批间校验
     * {@code epoch != diffEpoch} 即弃（/clear、会话切换都换代）——迟到的 diff 属于旧画面。
     * Esc 不取消：git 亚秒级 + 回写毫秒级，进程取消的复杂度不值得（spec 定案取舍，reference.md 有述）。
     */
    private void runDiff(int epoch) {
        try {
            GitSnapshot s = GitSnapshot.capture(root, diffGit);
            if (epoch != diffEpoch) {
                return;
            }
            if (s.error() != null) {
                state.pushRawLines(java.util.List.of("git 执行失败：" + s.error()), OutputLine.Kind.ERROR);
                return;
            }
            if (s.body().isEmpty() && s.untracked().isEmpty()) {
                state.pushInfo("工作树干净（相对 HEAD 无改动、无未跟踪文件）");
                return;
            }
            state.pushInfo("↺ 工作树改动（相对 HEAD）· " + (s.statLine().isEmpty() ? "" : s.statLine()));
            for (int from = 0; from < s.body().size(); from += 200) {
                if (epoch != diffEpoch) {
                    return;   // 中途被 /clear 或会话切换：剩余批次不打了
                }
                state.pushRawLines(s.body().subList(from, Math.min(from + 200, s.body().size())),
                        OutputLine.Kind.DIFF);
            }
            if (!s.untracked().isEmpty()) {
                var lines = new java.util.ArrayList<String>();
                lines.add("未跟踪文件（不在上方 diff 中）：");
                lines.addAll(s.untracked());
                state.pushRawLines(lines, OutputLine.Kind.INFO);
            }
        } finally {
            diffRunning = false;
        }
    }
```

COMMANDS 表一行（`/resume` 行后）：`new SlashCommand("/diff", "查看工作树改动（git diff + 未跟踪清单，不经模型）"),`

测试钩子（其它 ForTest 钩子旁）：

```java
    boolean diffRunningForTest() { return diffRunning; }   // 代际守卫负断言的完成信号（无它必假绿）
```

- [ ] **Step 4: 跑测试确认绿 + 面板回归**

Run: `mvn -pl springai-code-tui -am test -Dtest='CodeTuiViewDiffTest,CodeTuiViewSessionsPanelTest,CodeTuiViewContinueDigestTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 全 PASS（SessionsPanelTest 含 /clear 路径——diffEpoch++ 不许碰坏它）。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/CodeTuiViewDiffTest.java
git commit -m "feat: /diff 命令（daemon 快照 + 代际守卫 + 单飞，Kind.DIFF 逐字下沉）"
```

---

### Task 5: 文档 + 全量回归

**Files:**
- Modify: `springai-code-tui/docs/guide/reference.md`（命令表 3 行；「已知限制」若有被 /review /diff 影响的条目同步）
- Modify: `springai-code-tui/docs/implementation-map.md`（§5 命令表 3 行：`/init`//`review` 闸门「/review 闲检+git 预检」、`/diff`「无（只读）」）
- Modify: `springai-code-tui/README.md`（常用操作 3 行，/resume //fork 行附近）

- [ ] **Step 1: 三处文档同步**（措辞对齐各文件既有表格；`/diff` 注明「Esc 不取消、亚秒级」）。
- [ ] **Step 2: 全量回归**

Run: `mvn -pl springai-code-tui -am test`
Expected: 全 PASS（已知 flaky `CodingAgentSpikeTest.todoTurnIdBinding` 若红按 CONTRIBUTING 单跑复核）。

- [ ] **Step 3: 提交**

```bash
git add springai-code-tui/docs/guide/reference.md springai-code-tui/docs/implementation-map.md springai-code-tui/README.md
git commit -m "docs: /init /review /diff 补 reference 与 implementation-map、README"
```

---

## Plan Self-Review（已执行；2026-10-24 评审修订后更新）

1. **Spec 覆盖**：GitSnapshot（T1）、DIFF 通道+pushRawLines（T2）、/init /review 含预检（T3）、/diff 含 epoch/单飞/分批/Esc 取舍（T4）、文档三处（T5）——spec 全部定案有任务承接；「未跟踪只列名」「无忙碌闸门」「/review 预检」「重启后生效」均在代码块内落地。
2. **占位符扫描**：执行期对齐注记（ScrollbackPrinterTest 的 Sink 形状、Theme 色值常量名、/continue 分支签名）均给出先例文件与不变式；无 TBD/TODO。
3. **类型一致性**：`GitSnapshot.capture(Path, GitCommand)` 在 T1 定义、T3/T4 消费一致；`pushRawLines(List<String>, Kind)` 在 T2 定义、T4 消费一致；`diffGit` 注入点与 `diffRunningForTest()` 钩子只在 T4；`Kind.DIFF` 全文一处定义。
4. **评审修订记录（subagent 评审，2026-10-24）**：已修 3 高 + 4 中——①T1 PROCESS 管道死锁（waitFor 先于读流，>64KB 大 diff 必假超时）改**临时文件重定向**并补 >64KB/超时/非零退出三用例；②T2 补 `Theme.java`（styleFor 在 Theme 非 ScrollbackPrinter，穷举 switch 无 default，漏加则模块编译不过；Files 与 git add 均补）；③T2 测试三处（Sink 是两方法接口非函数式 + 三参构造、`pollPending()` 返回单条改 `drainPending()`、恒真断言改正向）；④T3 闸门 `state.isBusy()`→`busy()`（对齐 /continue，覆盖子 agent 收尾期）；⑤T4 补 `diffRunningForTest()` 完成信号（负断言防假绿）+ 端到端渲染路由钉子（四参构造器 + sink 逐字断言）+ 锚点行号写实；⑥`↑` 历史等价路径（钩子不存在）写明；⑦输出头行补 ↺、TempDir 假设声明。spec 侧行号漂移（/continue 实际 2595-2613 等）已按实值修正到计划。

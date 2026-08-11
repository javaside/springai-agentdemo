# code-tui 行内差分渲染实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 code-tui 的行内 live 区只提交真实变化的终端单元格，静止帧零输出，并消除 Windows Terminal 下输入框、动画和 resize 的可见闪烁。

**Architecture:** 在 Maven reactor 中加入一个以 TamboUI 0.4.0 `InlineDisplay` 为基线的最小兼容模块，用同 FQN 类优先覆盖官方 `tamboui-core` 中的行内实现；兼容类维护前后帧 Buffer、生成宽字符安全 patch，并把一帧合成一次 raw write/flush。code-tui 用一个小型批处理桥在 drain 周期内合并 scrollback 打印，并把 resize 改成 120ms 静默后单次重放；业务 Element 树、输入模型和动画保持不变。

**Tech Stack:** Java 17、Maven reactor、TamboUI 0.4.0（`Buffer`/`Cell`/`AnsiCellWriter`/`Backend`）、JUnit 5、Python 3 PTY + pyte、ANSI/VT、DEC mode 2026。

## Global Constraints

- 逻辑画面和逻辑光标均不变时，首帧之后终端输出严格为零。
- 普通帧不得执行整行 `EL`（`ESC[K`）或整块清空；只允许错误恢复、`/clear` 与 resize 停稳重放执行受控重建。
- 保留现有 30 FPS 波光动画；动画只更新变化 cell，不得重写输入框。
- DEC mode 2026 只是可选增强；关闭或不支持时，差分渲染仍须完全正确。
- 每个 live 帧至多一次 raw write 和一次 flush；静止帧两者均为零。
- 一个 code-tui drain 中的 scrollback 输出合并为一个批次，批次末至多提交一次 live 帧。
- resize 连续 120ms 无新宽度事件后只重放一次；拖动过程中不逐事件 `ESC[J`。
- Windows Terminal/ConPTY 是主要人工验收环境；macOS/Linux PTY 回归也必须通过。
- 不修改 `~/.m2`，不依赖开发机本地 jar；兼容源码、构建和发布必须在仓库与 CI 中可重复。
- 复制的 TamboUI 源文件保留 MIT/SPDX 头；项目根 `LICENSE` 不替代上游文件头。

---

## 文件结构

### 新增兼容模块

- `springai-tamboui-inline-patch/pom.xml`：构建只含行内兼容实现的优先 classpath jar，依赖官方 `tamboui-core:0.4.0`。
- `springai-tamboui-inline-patch/src/main/java/dev/tamboui/inline/InlineDisplay.java`：TamboUI 0.4.0 同 FQN 兼容实现；负责帧快照、差分计划、单批提交、高度变化、scrollback 批处理和受控恢复。
- `springai-tamboui-inline-patch/src/main/java/dev/tamboui/inline/InlinePatch.java`：纯差分规划器；产生宽字符安全的 `PatchRun`，不做 I/O。
- `springai-tamboui-inline-patch/src/main/java/dev/tamboui/inline/SynchronizedOutput.java`：DEC 2026 策略和包裹序列，支持 `auto/always/never`。
- `springai-tamboui-inline-patch/src/test/java/dev/tamboui/inline/InlinePatchTest.java`：纯 Buffer 差分、宽字符与高度重叠测试。
- `springai-tamboui-inline-patch/src/test/java/dev/tamboui/inline/InlineDisplayDiffTest.java`：记录型 Backend 字节、write/flush 次数、println 批处理和恢复测试。
- `springai-tamboui-inline-patch/src/test/java/dev/tamboui/inline/SynchronizedOutputTest.java`：模式策略测试。

### code-tui 接线

- `pom.xml`：聚合兼容模块，并让 code-tui 依赖可解析。
- `springai-code-tui/pom.xml`：把 patch jar 放在 TamboUI 依赖前；增加重复类来源与发布 classpath 回归检查。
- `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/InlineRenderBatch.java`：隔离对兼容 `InlineDisplay` 批处理 API 的反射访问；旧版/异常时安全降级。
- `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java`：drain 包裹批次；resize 去除逐事件 sweep；静默窗口改成 4 个 33ms tick（约 132ms，满足至少 120ms）。
- `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/ResizeSettle.java`：更新单级防抖语义注释。
- 删除 `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/ResizeSweeper.java` 及其测试：差分渲染后不再逐事件清屏。
- `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/InlineRenderBatchTest.java`：反射结构、嵌套和异常降级测试。
- `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/ResizeSettleTest.java`：4 tick 合并契约。
- `springai-code-tui/src/test/resources/scripts/render_diff_smoke.py`：真实 PTY 原始字节契约。
- `springai-code-tui/src/test/resources/scripts/resize_smoke.py`：改写为“拖动中无 `ESC[J`、停稳一次 `ESC[3J`”契约。
- `springai-code-tui/src/test/resources/scripts/README.md`：登记新脚本与运行命令。

---

### Task 1: 建立可重复构建的 TamboUI 行内兼容模块

**Files:**
- Create: `springai-tamboui-inline-patch/pom.xml`
- Create: `springai-tamboui-inline-patch/src/main/java/dev/tamboui/inline/InlineDisplay.java`
- Create: `springai-tamboui-inline-patch/src/test/java/dev/tamboui/inline/InlineDisplayBaselineTest.java`
- Modify: `pom.xml:57-63`
- Modify: `springai-code-tui/pom.xml:63-80`

**Interfaces:**
- Consumes: 官方 `dev.tamboui:tamboui-core:0.4.0` 的 `Buffer`、`Backend`、`AnsiCellWriter`、`Text`。
- Produces: patch jar 中的 `dev.tamboui.inline.InlineDisplay`，构造器和现有公开方法与 0.4.0 二进制兼容。

- [ ] **Step 1: 写兼容模块的基线测试**

复制上游 0.4.0 `InlineDisplay` 的 MIT/SPDX 头和实现到新模块；先只新增测试，测试确认关键二进制入口及类来源：

```java
package dev.tamboui.inline;

import dev.tamboui.layout.Size;
import dev.tamboui.terminal.Backend;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;

class InlineDisplayBaselineTest {
    @Test
    void patchClassKeepsTamboui040Surface() throws Exception {
        assertNotNull(InlineDisplay.class.getMethod("render", java.util.function.BiConsumer.class,
                int.class, int.class, int.class));
        assertNotNull(InlineDisplay.class.getMethod("println", String.class));
        assertNotNull(InlineDisplay.class.getMethod("println", dev.tamboui.text.Text.class));
        assertNotNull(InlineDisplay.class.getMethod("release"));
    }

    @Test
    void classIsLoadedFromPatchArtifact() {
        String source = InlineDisplay.class.getProtectionDomain().getCodeSource().getLocation().toString();
        assertTrue(source.contains("springai-tamboui-inline-patch"), source);
    }
}
```

- [ ] **Step 2: 创建 patch POM 并接入 reactor**

`springai-tamboui-inline-patch/pom.xml` 使用父工程，artifactId 为 `springai-tamboui-inline-patch`，直接依赖 `dev.tamboui:tamboui-core` 和 test scope `junit-jupiter`。父 `pom.xml` 在 `springai-code-tui` 之前加入：

```xml
<module>springai-tamboui-inline-patch</module>
```

code-tui 在所有 `dev.tamboui` 依赖之前加入：

```xml
<dependency>
    <groupId>io.github.javaside</groupId>
    <artifactId>springai-tamboui-inline-patch</artifactId>
    <version>${project.version}</version>
</dependency>
```

- [ ] **Step 3: 运行测试确认类来源和 API**

Run:

```bash
mvn -q -pl springai-tamboui-inline-patch,springai-code-tui -am \
  -Dtest=InlineDisplayBaselineTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: PASS；测试输出中的 protection-domain 路径来自 patch 模块，不是 `~/.m2/dev/tamboui/tamboui-core`。

- [ ] **Step 4: 验证发布 classpath 顺序**

Run:

```bash
mvn -q -pl springai-code-tui -am package -DskipTests
jar xf springai-code-tui/target/springai-code-tui.jar META-INF/MANIFEST.MF
python3 - <<'PY'
from pathlib import Path
s = Path('META-INF/MANIFEST.MF').read_text()
assert s.index('springai-tamboui-inline-patch') < s.index('tamboui-core'), s
print('patch precedes tamboui-core')
PY
rm -rf META-INF
```

Expected: `patch precedes tamboui-core`。

- [ ] **Step 5: 提交**

```bash
git add pom.xml springai-code-tui/pom.xml springai-tamboui-inline-patch
git commit -m "build(tui): 接入 TamboUI 行内兼容模块"
```

---

### Task 2: 用纯规划器生成最小、宽字符安全的行内 patch

**Files:**
- Create: `springai-tamboui-inline-patch/src/main/java/dev/tamboui/inline/InlinePatch.java`
- Create: `springai-tamboui-inline-patch/src/test/java/dev/tamboui/inline/InlinePatchTest.java`

**Interfaces:**
- Consumes: `Buffer previous`、`Buffer current`。
- Produces:
  - `record PatchRun(int row, int startCol, int endColExclusive)`
  - `static List<PatchRun> runs(Buffer previous, Buffer current)`
  - `static Buffer preserveOverlap(Buffer previous, int newWidth, int newHeight)`

- [ ] **Step 1: 写最小差分失败测试**

```java
@Test
void identicalFramesHaveNoRuns() {
    Buffer a = Buffer.withLines("abc", "def");
    assertEquals(List.of(), InlinePatch.runs(a, a.copy()));
}

@Test
void adjacentChangesMergeWithinOneRow() {
    Buffer before = Buffer.withLines("abcdef");
    Buffer after = before.copy();
    after.setString(2, 0, "XY", Style.EMPTY);
    assertEquals(List.of(new InlinePatch.PatchRun(0, 2, 4)), InlinePatch.runs(before, after));
}

@Test
void runsNeverMergeAcrossRows() {
    Buffer before = Buffer.withLines("abc", "def");
    Buffer after = before.copy();
    after.setString(2, 0, "X", Style.EMPTY);
    after.setString(0, 1, "Y", Style.EMPTY);
    assertEquals(List.of(
            new InlinePatch.PatchRun(0, 2, 3),
            new InlinePatch.PatchRun(1, 0, 1)), InlinePatch.runs(before, after));
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```bash
mvn -q -pl springai-tamboui-inline-patch -Dtest=InlinePatchTest test
```

Expected: FAIL，`InlinePatch` 不存在。

- [ ] **Step 3: 实现最小按行归并**

```java
final class InlinePatch {
    record PatchRun(int row, int startCol, int endColExclusive) {
        PatchRun {
            if (row < 0 || startCol < 0 || endColExclusive <= startCol) {
                throw new IllegalArgumentException("invalid patch run");
            }
        }
    }

    static List<PatchRun> runs(Buffer previous, Buffer current) {
        if (!previous.area().equals(current.area())) {
            throw new IllegalArgumentException("same-sized buffers required");
        }
        List<PatchRun> out = new ArrayList<>();
        for (int y = 0; y < current.height(); y++) {
            int start = -1;
            for (int x = 0; x < current.width(); x++) {
                boolean changed = !previous.get(x, y).equals(current.get(x, y));
                if (changed && start < 0) start = x;
                if (!changed && start >= 0) {
                    out.add(new PatchRun(y, start, x));
                    start = -1;
                }
            }
            if (start >= 0) out.add(new PatchRun(y, start, current.width()));
        }
        return List.copyOf(out);
    }
}
```

- [ ] **Step 4: 增加宽字符边界与样式测试**

```java
@Test
void continuationChangeExpandsToWideCharacterOwner() {
    Buffer before = Buffer.empty(Rect.of(8, 1));
    before.setString(1, 0, "中", Style.EMPTY);
    Buffer after = before.copy();
    after.set(2, 0, Cell.EMPTY);
    assertEquals(List.of(new InlinePatch.PatchRun(0, 1, 3)), InlinePatch.runs(before, after));
}

@Test
void styleOnlyChangeProducesOneCellRun() {
    Buffer before = Buffer.withLines("abc");
    Buffer after = before.copy();
    after.set(1, 0, after.get(1, 0).style(Style.EMPTY.reversed()));
    assertEquals(List.of(new InlinePatch.PatchRun(0, 1, 2)), InlinePatch.runs(before, after));
}
```

修改 `runs()`：初始 run 若落在任一帧 continuation cell 就向左找 owner；结束边界后任一帧仍是 continuation 就向右吃完。相邻扩展后的 run 再合并。

- [ ] **Step 5: 增加高度重叠快照测试和实现**

```java
@Test
void preserveOverlapKeepsOnlySharedRectangle() {
    Buffer old = Buffer.withLines("abcd", "efgh");
    Buffer grown = InlinePatch.preserveOverlap(old, 6, 3);
    assertEquals(Rect.of(6, 3), grown.area());
    assertEquals("a", grown.get(0, 0).symbol());
    assertEquals("h", grown.get(3, 1).symbol());
    assertTrue(grown.get(5, 2).isEmpty());
}
```

实现按 `min(old.width,newWidth) × min(old.height,newHeight)` 复制 cell 的 `preserveOverlap()`。

- [ ] **Step 6: 运行模块测试并提交**

Run:

```bash
mvn -q -pl springai-tamboui-inline-patch test
```

Expected: PASS。

```bash
git add springai-tamboui-inline-patch/src
git commit -m "feat(tui): 添加宽字符安全的行内差分规划"
```

---

### Task 3: 将 InlineDisplay 普通帧改成零空刷、单批差分提交

**Files:**
- Modify: `springai-tamboui-inline-patch/src/main/java/dev/tamboui/inline/InlineDisplay.java`
- Create: `springai-tamboui-inline-patch/src/test/java/dev/tamboui/inline/InlineDisplayDiffTest.java`

**Interfaces:**
- Consumes: `InlinePatch.runs(previous, current)`。
- Produces: 同尺寸帧的最小 raw patch；测试用包私有 `Buffer previousFrameForTest()`。

- [ ] **Step 1: 建立记录型 Backend**

在 `InlineDisplayDiffTest` 中实现 `RecordingBackend`：`writeRaw(byte[])` 追加到 `ByteArrayOutputStream` 并计数，`flush()` 计数，`size()` 返回固定尺寸；提供 `mark()`、`bytesSince(mark)`、`resetCounts()`。不要用 `backend.draw()`，因为行内 patch 必须相对 live 区而不是绝对屏幕坐标。

- [ ] **Step 2: 写静止帧零输出失败测试**

```java
@Test
void identicalSecondFrameWritesAndFlushesNothing() {
    InlineDisplay d = display(20, 2);
    d.render((a, b) -> b.setString(0, 0, "hello", Style.EMPTY), 2, 1, 0);
    backend.resetCounts();

    d.render((a, b) -> b.setString(0, 0, "hello", Style.EMPTY), 2, 1, 0);

    assertEquals(0, backend.writeCalls());
    assertEquals(0, backend.flushCalls());
    assertArrayEquals(new byte[0], backend.output());
}
```

Run:

```bash
mvn -q -pl springai-tamboui-inline-patch \
  -Dtest=InlineDisplayDiffTest#identicalSecondFrameWritesAndFlushesNothing test
```

Expected: FAIL；基线实现仍包含 `ESC[K` 和整行内容。

- [ ] **Step 3: 引入双 Buffer，但先只实现静止短路**

将单个 `buffer` 拆为：

```java
private Buffer currentBuffer;
private Buffer previousBuffer;
private boolean previousFrameValid;
private int lastCursorX = -1;
private int lastCursorY;
```

每帧清 `currentBuffer`、render 到当前帧；若 `previousFrameValid && previousBuffer.equals(currentBuffer)` 且光标相同，直接 return，不 write、不 flush；成功提交后交换 Buffer。

- [ ] **Step 4: 写单字符与样式局部更新失败测试**

```java
@Test
void oneCellChangeDoesNotEraseOrRewriteOtherRows() {
    InlineDisplay d = twoLineFrame("input", "thinking");
    backend.resetCounts();
    d.render((a, b) -> {
        b.setString(0, 0, "input!", Style.EMPTY);
        b.setString(0, 1, "thinking", Style.EMPTY);
    }, 2, 6, 0);
    String raw = backend.outputUtf8();
    assertFalse(raw.contains("\u001b[K"), raw);
    assertFalse(raw.contains("thinking"), raw);
    assertEquals(1, backend.writeCalls());
    assertEquals(1, backend.flushCalls());
}

@Test
void shimmerStyleChangeDoesNotRewriteInputRow() {
    InlineDisplay d = styledTwoLineFrame(false);
    backend.resetCounts();
    renderStyledTwoLineFrame(d, true);
    String raw = backend.outputUtf8();
    assertFalse(raw.contains("input-border"), raw);
    assertFalse(raw.contains("\u001b[K"), raw);
}
```

- [ ] **Step 5: 实现相对 live 区的单批 patch**

新增 `StringBuilder batch`；先从 `(lastCursorX,lastCursorY)` 回到 live 区第 0 行第 0 列，再按 `PatchRun` 移到目标行列，用 `AnsiCellWriter(batch::append)` 写 run 中非 continuation cell。run 中目标为空格时写普通空格以清旧尾部，禁止 `EL`。最后移动到新逻辑光标，追加 SGR reset（仅本批写过样式时），调用一次：

```java
backend.writeRaw(batch.toString());
backend.flush();
```

不要混用多个 `backend.moveCursor*()`，否则无法保证一次 raw write。

- [ ] **Step 6: 写仅光标变化测试并实现**

```java
@Test
void cursorOnlyChangeDoesNotRewriteCells() {
    InlineDisplay d = oneLineFrame("abc", 0, 0);
    backend.resetCounts();
    d.render((a, b) -> b.setString(0, 0, "abc", Style.EMPTY), 1, 2, 0);
    String raw = backend.outputUtf8();
    assertFalse(raw.contains("abc"), raw);
    assertFalse(raw.contains("\u001b[K"), raw);
    assertEquals(1, backend.writeCalls());
}
```

Buffer 相同、光标不同时生成只含相对移动的批次。

- [ ] **Step 7: 跑 100 帧零空刷与 CJK 回归**

增加循环测试：首帧后重复 100 次相同 render，累计 write/flush 为 0；把 `"中"` 替换为 `"文"` 后 raw 中只出现完整新字，不出现 continuation 的空 symbol。

Run:

```bash
mvn -q -pl springai-tamboui-inline-patch test
```

Expected: PASS。

- [ ] **Step 8: 提交**

```bash
git add springai-tamboui-inline-patch/src
git commit -m "fix(tui): 行内普通帧改为最小差分提交"
```

---

### Task 4: 保留动态高度重叠帧并提供受控 live 区恢复

**Files:**
- Modify: `springai-tamboui-inline-patch/src/main/java/dev/tamboui/inline/InlineDisplay.java`
- Modify: `springai-tamboui-inline-patch/src/test/java/dev/tamboui/inline/InlineDisplayDiffTest.java`

**Interfaces:**
- Consumes: `InlinePatch.preserveOverlap()`。
- Produces: `void invalidateFrame()`（public，供 resize/clear 后使快照失效）；高度变化与失效恢复均只重建 live 区。

- [ ] **Step 1: 写增高不重画重叠行失败测试**

```java
@Test
void growingOnlyDrawsNewAndActuallyChangedRows() {
    InlineDisplay d = oneLineFrame("stable", 0, 0);
    backend.resetCounts();
    d.render((a, b) -> {
        b.setString(0, 0, "stable", Style.EMPTY);
        b.setString(0, 1, "new", Style.EMPTY);
    }, 2, 0, 0);
    String raw = backend.outputUtf8();
    assertFalse(raw.contains("stable"), raw);
    assertTrue(raw.contains("new"), raw);
    assertFalse(raw.contains("\u001b[K"), raw);
}
```

- [ ] **Step 2: 实现高度变化快照保留**

`resizeDisplay()` 结构操作完成后，用 `preserveOverlap(previousBuffer,width,newHeight)` 替换旧快照，而不是创建全空 previous；current Buffer 按新尺寸创建。增高的新区域自然与空 previous 比较后进入 patch；缩短区域先 `deleteLines`，保留区继续 diff。

结构序列也写入当前帧 `StringBuilder batch`，不在 `resizeDisplay()` 内 flush。

- [ ] **Step 3: 写缩短与失效恢复测试**

```java
@Test
void shrinkingDoesNotRewriteSurvivingRows() {
    InlineDisplay d = display(20, 3);
    d.render((a, b) -> {
        b.setString(0, 0, "surviving", Style.EMPTY);
        b.setString(0, 1, "removed", Style.EMPTY);
    }, 2, 0, 0);
    backend.resetCounts();

    d.render((a, b) -> b.setString(0, 0, "surviving", Style.EMPTY), 1, 0, 0);

    String raw = backend.outputUtf8();
    assertFalse(raw.contains("surviving"), raw);
    assertFalse(raw.contains("removed"), raw);
    assertTrue(raw.contains("\u001b[1M"), raw);
}

@Test
void invalidatedFrameRebuildsLiveAreaOnce() {
    InlineDisplay d = oneLineFrame("stable", 0, 0);
    d.invalidateFrame();
    backend.resetCounts();
    renderSame(d, "stable");
    assertTrue(backend.outputUtf8().contains("stable"));
    backend.resetCounts();
    renderSame(d, "stable");
    assertEquals(0, backend.writeCalls());
}
```

- [ ] **Step 4: 实现受控恢复**

`invalidateFrame()` 只置 `previousFrameValid=false`。下一次 render 以全空 previous Buffer 对当前 live 区做完整覆盖，不调用 `backend.clear()`、`ESC[J` 或 `ESC[3J`；成功后重新置 valid。

- [ ] **Step 5: 运行测试并提交**

```bash
mvn -q -pl springai-tamboui-inline-patch test
git add springai-tamboui-inline-patch/src
git commit -m "fix(tui): 动态高度保留行内帧重叠区域"
```

---

### Task 5: 合并 scrollback 打印并停止逐行重画 live 区

**Files:**
- Modify: `springai-tamboui-inline-patch/src/main/java/dev/tamboui/inline/InlineDisplay.java`
- Modify: `springai-tamboui-inline-patch/src/test/java/dev/tamboui/inline/InlineDisplayDiffTest.java`
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/InlineRenderBatch.java`
- Create: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/InlineRenderBatchTest.java`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java:267-287,381-472`

**Interfaces:**
- Patch `InlineDisplay` produces public `void beginPrintBatch()`、`void endPrintBatch()`。
- `InlineRenderBatch` produces `static AutoCloseable open(InlineToolkitRunner runner)`；`close()` 必须幂等且不抛。

- [ ] **Step 1: 写 InlineDisplay 批次失败测试**

```java
@Test
void printBatchDoesNotRedrawLiveAreaPerLine() {
    InlineDisplay d = oneLineFrame("LIVE", 0, 0);
    backend.resetCounts();
    d.beginPrintBatch();
    d.println("one");
    d.println("two");
    d.println("three");
    d.endPrintBatch();
    String raw = backend.outputUtf8();
    assertEquals(1, count(raw, "one"));
    assertEquals(1, count(raw, "two"));
    assertEquals(1, count(raw, "three"));
    assertEquals(0, count(raw, "LIVE"));
    assertEquals(1, backend.writeCalls());
    assertEquals(1, backend.flushCalls());
}
```

- [ ] **Step 2: 实现 InlineDisplay 打印批次**

`beginPrintBatch()` 创建/复用 `StringBuilder printBatch` 并增加 depth；批内 `println(String/Text)` 只向 builder 追加“回 live 顶部、insert line、消息、局部行尾清理、换到新 live 顶部”的序列，不 flush、不调用帧重画。`endPrintBatch()` depth 回到 0 时一次 write/flush；live Buffer 仍有效，因为插行整体移动了已显示 live 区。

非批次 `println()` 内部使用 `beginPrintBatch(); try ... finally endPrintBatch();`，保持现有 API 兼容。

- [ ] **Step 3: 写 code-tui 批处理桥结构测试**

```java
@Test
void patchDisplayExposesBatchMethods() throws Exception {
    Class<?> display = Class.forName("dev.tamboui.inline.InlineDisplay");
    assertNotNull(display.getMethod("beginPrintBatch"));
    assertNotNull(display.getMethod("endPrintBatch"));
}

@Test
void openWithoutStartedRunnerIsSafeNoop() throws Exception {
    try (AutoCloseable ignored = InlineRenderBatch.open(null)) {
        assertNotNull(ignored);
    }
}
```

- [ ] **Step 4: 实现隔离反射桥**

`InlineRenderBatch.open(runner)` 反射链固定为 `runner.tuiRunner()` → 私有 `viewport` → 私有 `display`，调用 `beginPrintBatch()`；返回的 close guard 调 `endPrintBatch()`。任何反射或调用异常返回 no-op guard，并记录 debug 日志；不得抛进渲染线程。反射只负责访问尚未公开贯穿 Toolkit 的兼容 API，核心差分不依赖反射。

- [ ] **Step 5: 在 drain 外围包批次**

把 `drain()` 拆为：

```java
private void drain() {
    try (AutoCloseable ignored = InlineRenderBatch.open(runner())) {
        drainInsideBatch();
    } catch (Exception impossible) {
        log.debug("关闭行内打印批次失败，已降级", impossible);
    }
}
```

原 drain 正文原样移入 `drainInsideBatch()`。后台 dispatch 的早 return 仍会经过 try-with-resources close。

- [ ] **Step 6: 运行相关测试并提交**

```bash
mvn -q -pl springai-tamboui-inline-patch,springai-code-tui -am \
  -Dtest=InlineDisplayDiffTest,InlineRenderBatchTest -Dsurefire.failIfNoSpecifiedTests=false test

git add springai-tamboui-inline-patch/src springai-code-tui/src/main springai-code-tui/src/test
git commit -m "fix(tui): 合并 scrollback 打印并保留 live 帧"
```

---

### Task 6: 加入可降级的 DEC 2026 同步输出

**Files:**
- Create: `springai-tamboui-inline-patch/src/main/java/dev/tamboui/inline/SynchronizedOutput.java`
- Create: `springai-tamboui-inline-patch/src/test/java/dev/tamboui/inline/SynchronizedOutputTest.java`
- Modify: `springai-tamboui-inline-patch/src/main/java/dev/tamboui/inline/InlineDisplay.java`
- Modify: `springai-tamboui-inline-patch/src/test/java/dev/tamboui/inline/InlineDisplayDiffTest.java`

**Interfaces:**
- Produces `enum Mode { AUTO, ALWAYS, NEVER }`。
- Produces `static SynchronizedOutput from(Map<String,String> env, String property)`。
- Produces `String wrap(String payload)`。

- [ ] **Step 1: 写策略失败测试**

```java
@Test
void neverLeavesPayloadUntouched() {
    assertEquals("patch", SynchronizedOutput.from(Map.of(), "never").wrap("patch"));
}

@Test
void alwaysWrapsWithMode2026() {
    assertEquals("\u001b[?2026hpatch\u001b[?2026l",
            SynchronizedOutput.from(Map.of(), "always").wrap("patch"));
}

@Test
void autoEnablesOnlyForKnownTerminalIdentity() {
    assertTrue(SynchronizedOutput.from(Map.of("WT_SESSION", "x"), "auto").enabled());
    assertTrue(SynchronizedOutput.from(Map.of("TERM_PROGRAM", "WezTerm"), "auto").enabled());
    assertFalse(SynchronizedOutput.from(Map.of("TERM", "xterm-256color"), "auto").enabled());
}
```

- [ ] **Step 2: 实现无阻塞能力策略**

读取系统属性 `codetui.syncOutput`，合法值 `auto/always/never`，缺省 `auto`；`auto` 只认明确终端身份：`WT_SESSION`、`TERM_PROGRAM=WezTerm|iTerm.app`、`KITTY_WINDOW_ID`。不发送 DECRQM 查询，不读取输入流，不阻塞事件解析。未知终端降级为关闭；`always` 供已知支持但未被识别的终端显式开启。

- [ ] **Step 3: 包裹 live 与 print 批次**

所有非空 batch 在唯一 `backend.writeRaw(...)` 之前调用 `syncOutput.wrap(payload)`；空 payload 仍严格零输出。确保 `?2026l` 在 payload 后，即便 AnsiCellWriter 追加 SGR reset 也包含在同步事务内。

- [ ] **Step 4: 验证关闭同步输出仍满足差分契约**

在 `InlineDisplayDiffTest` 构造 display 时注入/设置 `never`，重跑静止、波光、CJK、批次测试；再单独用 `always` 断言每个非空帧恰好一对 `?2026h/l`。

- [ ] **Step 5: 测试并提交**

```bash
mvn -q -pl springai-tamboui-inline-patch test
git add springai-tamboui-inline-patch/src
git commit -m "feat(tui): 支持可降级的同步输出事务"
```

---

### Task 7: resize 改为 120ms 合并后单次重放

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java:140-166,364-401,576-618`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/ResizeSettle.java`
- Modify: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/ResizeSettleTest.java`
- Delete: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/ResizeSweeper.java`
- Delete: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/ResizeSweeperTest.java`
- Modify: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/CodeTuiViewCursorParkTest.java`

**Interfaces:**
- Consumes: `ResizeSettle(4)`，33ms drain 下约 132ms 静默窗口。
- Produces: resize 事件只 `changed()`；停稳时 `replayAfterResize()` 一次。

- [ ] **Step 1: 先改 ResizeSettle 生产参数测试**

在测试中增加明确契约：changed 后前 3 tick false，第 4 tick true；再次连续 changed 会重置 4 tick。保留纯状态机既有其余测试。

- [ ] **Step 2: 从 CodeTuiView 删除逐事件清扫**

全局 ResizeEvent handler 改为只做：

```java
if (event instanceof ResizeEvent re && re.width() > 0 && re.width() != lastSeenWidth) {
    lastSeenWidth = re.width();
    resizeSettle.changed();
    parkCursorAtTop = true;
}
```

轮询宽度兜底同样只 `changed()`，不调用 `ResizeSweeper.sweep()`。字段初始化改为：

```java
private final ResizeSettle resizeSettle = new ResizeSettle(4);
```

- [ ] **Step 3: 删除 ResizeSweeper 并更新注释**

删除实现和测试；`ResizeSettle`、`CodeTuiView` 和 `resize_smoke.py` 中“两级修复/逐事件清扫”的描述统一改成“事件合并 + 停稳单次重放”。不要保留失效的反射结构测试。

- [ ] **Step 4: 保持 IME 光标生命周期**

ResizeEvent 首次变化仍 `parkCursorAtTop=true`；`replayAfterResize()` finally/调用后必须恢复 false。更新 `CodeTuiViewCursorParkTest`：平时 cursorY 为文本行，resize 窗口为 0，停稳重放后回文本行。

- [ ] **Step 5: 跑 UI resize 单测并提交**

```bash
mvn -q -pl springai-code-tui -am \
  -Dtest=ResizeSettleTest,CodeTuiViewCursorParkTest -Dsurefire.failIfNoSpecifiedTests=false test

git add -A springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui \
  springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui
git commit -m "fix(tui): resize 合并后只做一次停稳重放"
```

---

### Task 8: 增加真实 PTY 的零空刷与局部更新字节契约

**Files:**
- Create: `springai-code-tui/src/test/resources/scripts/render_diff_smoke.py`
- Modify: `springai-code-tui/src/test/resources/scripts/resize_smoke.py`
- Modify: `springai-code-tui/src/test/resources/scripts/README.md`

**Interfaces:**
- Consumes: 已构建的 code-tui classes、`target/cp.txt`、pyte。
- Produces: 可在 Unix PTY 自动验证的原始 VT 字节契约；Windows 使用同一观察项人工验收。

- [ ] **Step 1: 从现有脚本提取最小 PTY 启动骨架到新脚本**

复用 `resize_smoke.py` 的 `PtySession`、窗口大小、DSR 应答与 classpath 构造；新脚本启动到欢迎页稳定后记录 `mark=len(session.raw)`。

- [ ] **Step 2: 写空闲零输出断言**

泵 500ms（覆盖约 15 个 tick），断言 `session.raw[mark:] == b""`。注意首次上下文统计刷新可能在 1s 发生，因此先额外泵到状态稳定，再取 mark；若状态文本实际变化，等待该帧完成后重新取 mark。

- [ ] **Step 3: 写输入局部更新断言**

逐字输入 `abc`，每字后取增量：

```python
assert b"\x1b[K" not in delta
assert BOX_TOP_UTF8 not in delta
assert STATUS_HINT_UTF8 not in delta
```

输入 `中` 的 UTF-8 后断言完整字节出现，且屏幕输入行为 `abc中`。

- [ ] **Step 4: 写动画不重画输入框断言**

使用脚本内 DeepSeek SSE 桩让应用保持 `THINKING` 至少 500ms；对该窗口 raw 增量断言：包含 SGR/光标 patch，但不包含圆角输入框横线 UTF-8、`ESC[K` 或整个状态行静态前缀的重复全文。

- [ ] **Step 5: 改写 resize 脚本契约**

删除“每次 resize 必须看到 sweep `ESC[J`”描述和断言；快速执行 100→60→100→45，记录最后一次 resize 前 mark：

- 前 100ms 内不得出现 `ESC[J` 或 `ESC[3J`；
- 稳定后 `ESC[3J` 恰好一次；
- 最终只有一个 45 列输入框，输入文本与 scrollback 内容保留；
- 硬件光标最终回输入文本行。

- [ ] **Step 6: 构建并运行 PTY 脚本**

```bash
mvn -q -pl springai-code-tui -am package
mvn -q -pl springai-code-tui dependency:build-classpath -Dmdep.outputFile=target/cp.txt
/usr/bin/python3 springai-code-tui/src/test/resources/scripts/render_diff_smoke.py
/usr/bin/python3 springai-code-tui/src/test/resources/scripts/resize_smoke.py
```

Expected: 两个脚本均输出 `SMOKE PASS`。

- [ ] **Step 7: 更新脚本索引并提交**

README 表格新增 `render_diff_smoke.py`，明确它检查“静止零字节、输入/动画不含整行擦除、CJK 完整 patch”；更新运行命令。

```bash
git add springai-code-tui/src/test/resources/scripts
git commit -m "test(tui): 增加行内差分渲染 PTY 契约"
```

---

### Task 9: 全量验证、发布包检查与 Windows 人工验收

**Files:**
- Modify only if verification exposes defects; fixes must stay within approved design scope.

**Interfaces:**
- Consumes: Tasks 1–8 的完整实现。
- Produces: 可发布、可重复构建且通过自动化与 Windows Terminal 验收的修复。

- [ ] **Step 1: 运行 patch 与 code-tui 全量测试**

```bash
mvn -q -pl springai-tamboui-inline-patch,springai-code-tui -am test
```

Expected: BUILD SUCCESS，全部测试通过。

- [ ] **Step 2: 运行 code-tui 全部无网络 PTY 冒烟**

先生成 classpath，再运行：

```bash
mvn -q -pl springai-code-tui dependency:build-classpath -Dmdep.outputFile=target/cp.txt
for s in clear_smoke.py attachment_smoke.py permission_smoke.py background_smoke.py \
         model_memory_smoke.py render_diff_smoke.py resize_smoke.py; do
  /usr/bin/python3 "springai-code-tui/src/test/resources/scripts/$s" || exit 1
done
```

Expected: 每个脚本输出各自 PASS；若环境缺 pyte，先按项目既有脚本要求安装到 `/usr/bin/python3` 的 user site，而不是跳过结论。

- [ ] **Step 3: 构建 dist 并验证 patch jar 排序**

```bash
mvn -q -pl springai-code-tui -am clean package -Pdist
python3 - <<'PY'
from pathlib import Path
import zipfile
z = next(Path('springai-code-tui/target').glob('*-dist.zip'))
with zipfile.ZipFile(z) as f:
    names = f.namelist()
    patch = [n for n in names if 'springai-tamboui-inline-patch' in n]
    core = [n for n in names if 'tamboui-core-0.4.0' in n]
    assert len(patch) == 1, patch
    assert len(core) == 1, core
print('dist contains patch and upstream core')
PY
```

Expected: `dist contains patch and upstream core`；随后解压运行 `java -verbose:class`，确认 `dev.tamboui.inline.InlineDisplay` 来源是 patch jar。

- [ ] **Step 4: Windows Terminal/ConPTY 人工验收**

在 Windows Terminal 中用发布包依次验证并记录结果：

1. 空闲 10 秒：输入框与状态栏不闪。
2. 连续输入/退格 ASCII：仅字符和反显光标变化。
3. 中文 IME 输入完整句子：预编辑候选锚在文本行，无错位、半字或边框闪动。
4. 发起模型思考和工具调用：波光保留，输入框不闪。
5. 启动后台任务：后台 RUNNING 波光保留，其他行不闪。
6. 打开/关闭 `/model`、`/permissions`、`/tasks`：高度变化无残影。
7. 快速拖动窗口宽度后松手：拖动中无反复白屏，停稳后只恢复一次，scrollback 干净。
8. 分别以 `-Dcodetui.syncOutput=never` 和 `always` 运行：两者都正确；`always` 的重建原子性更好。

Expected: 八项全部通过。若无法访问 Windows 环境，不得宣称 Windows 验收完成；明确记录“自动化通过，Windows 人工验收待执行”。

- [ ] **Step 5: 检查工作区和最终差异**

```bash
git status --short
git diff --check
git log --oneline --decorate -12
```

Expected: 无未提交改动，`git diff --check` 无输出。

- [ ] **Step 6: 若验证阶段产生修复则单独提交**

先用 `git status --short` 列出验证阶段改动，只暂存本计划涉及的 patch 模块、code-tui UI、测试或脚本文件，然后提交：

```bash
git status --short
git add springai-tamboui-inline-patch springai-code-tui/src/main springai-code-tui/src/test pom.xml springai-code-tui/pom.xml
git commit -m "fix(tui): 修正行内差分验证发现的问题"
```

若 `git status --short` 还列出计划范围外的用户改动，不得把它们加入该提交。

若无修复，不创建空提交。

---

## 规格覆盖自检

- 静止零输出：Task 3 单测 + Task 8 PTY。
- 输入/动画局部刷新：Task 2–3 + Task 8。
- 宽字符与 IME：Task 2–3 自动化 + Task 9 Windows 人工验收。
- 每帧单 write/flush：Task 3 记录型 Backend。
- 高度变化：Task 4 + 现有面板 PTY + Task 9。
- scrollback 批处理：Task 5。
- DEC 2026 可降级：Task 6，`never` 仍跑全部核心契约。
- resize 120ms 合并：Task 7–8。
- `/clear` 保留：Task 7 不改 `/clear`，Task 9 跑 `clear_smoke.py`。
- 可重复构建与发布：Task 1、Task 9 dist 检查。
- 上游最小补丁边界：兼容模块只复制/新增 `dev.tamboui.inline` 必要类，不修改本地 Maven 仓库。

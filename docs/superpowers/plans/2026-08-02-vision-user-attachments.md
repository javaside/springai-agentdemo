# 视觉输入 期 2 实施计划（用户贴图）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让用户在输入框里写（或拖入）图片路径，模型就能真看见那张图。

**Architecture:** 识别出图片路径后，把它渲染成 `[file reference]` 块**追加进待发文本**——期 1 的 `VisionMaterializer` 已经会处理 user 消息里的引用块，故 `SubmitHandler` / `CodingAgent` / 兑现器 / 预算**一行都不用改**。这与既有的斜杠技能注入是同一套路：会话持久化「注入后」的文本，实时 UI 只显示用户原文。

**Tech Stack:** Java 17（`maven.compiler.release=17`，**无类型模式 switch、无 record pattern**）、TamboUI（内联 TUI）、Apache Tika（`MagicSniffer`，已有）、JUnit 5、pty + pyte 实机冒烟。

**设计依据：** [2026-08-02 用户贴图设计](../specs/2026-08-02-vision-user-attachments-design.md)

---

## 全局纪律（每个任务都适用）

- **验证命令必须模块作用域**：`mvn test -pl springai-code-tui`。
  **绝不**加 `-DfailIfNoSpecifiedTests=false`——整仓跑会被 3 个空模块打挂，加这个参数只是把问题盖住。
- **断言用 JUnit `org.junit.jupiter.api.Assertions`**。本模块**没有 assertj 依赖**（pom 里只有 junit-jupiter，140+ 个既有测试统一用 JUnit 断言），**不要往 pom 加**。
- **Java 17**：不要写 `case String s ->` 这类类型模式 switch，也不要写 record pattern，编译不过。
- **绝不用 `git stash` / `git commit --amend` / `git add -A`**——本项目在并行 agent 下已因此出过两次事故（stash 差 105 秒卷走别人的工作；amend 真把改动折进了别人的提交）。`git add` 逐个列文件。
- **要干净基线或做变异验证，用 `git worktree`**：
  ```bash
  git worktree add --detach /tmp/mut1 HEAD
  # 在 /tmp/mut1 里改一行、跑测试、看红不红
  git worktree remove --force /tmp/mut1
  ```
  它只在 `.git/worktrees/` 加元数据，**不碰共用工作树的索引与文件**。
- **联网测试默认跳过**（类级 `CODETUI_LIVE_TESTS=1` 门控），你不需要也不应该去跑它们。
- 提交信息用中文正文，首行 `feat(vision): …` / `test(vision): …` / `fix(vision): …`。

### 基线

改动前：`mvn test -pl springai-code-tui` → **1054 run / 0 failures / 9 skipped / BUILD SUCCESS**。

---

## ⚠️ 本项目在 TUI 上栽过的坑（动 UI 前必读）

| 坑 | 后果 |
|---|---|
| **焦点导航吞键** | `Tab` / `Shift+Tab` 曾经**根本到不了应用**——TamboUI 的 `FOCUS_NEXT/PREVIOUS` 先吃掉了。而单测走 `feedKeyForTest` 绕过路由器，**原理上抓不到**。`Ctrl+G` 有同样的风险 |
| **`scope` 每帧 eager 求值** | `scope(cond, panel())` 每帧都会构造 panel，面板方法必须首行判空 |
| **一个 `OutputLine` = 一个物理行** | 字符串里塞 `\n` 会被 `println` 塌成一行并截断 |
| **裸 `q`/`Q` 曾绑定退出** | 已改为只有 `Ctrl+C`。加新键位前先确认没和 readline 键位冲突 |

**结论：凡是经按键路由器的行为，单测绿不构成证据，必须 pty 实机（Task 5）。**

---

## 文件结构

**新建**

| 文件 | 职责 |
|---|---|
| `ui/DetectedImage.java` | 识别结果 record |
| `ui/ImageAttachmentDetector.java` | **纯函数 + 缓存**：文本 + root → `List<DetectedImage>`。全部识别判据在此，可完全离线单测 |

**修改**

| 文件 | 改动 |
|---|---|
| `ui/CodeTuiView.java` | 附件行渲染、`Ctrl+G`、能力闸门、提交前注入引用块 |
| `ui/HistoryReplay.java` | 把引用块渲成 `📎 name (w×h)` |
| `springai-code-tui/README.md` | 「视觉输入」小节补用户贴图 |
| `src/test/resources/scripts/` | 新增 pty 冒烟脚本 |

**刻意不改**：`SubmitHandler`、`CodingAgent`、`agent/media/` 下任何文件。若你发现非改不可，**先停下来报告**——那说明设计判断错了，不该由实现临时决定。

---

### Task 1：`ImageAttachmentDetector` —— 识别器（纯函数，整期核心）

全部识别复杂度都压在这一个类里：转义形态、路径解析、魔数判定、上限。UI 层因此只剩渲染和按键。

#### 四条判据，**全部**满足才算一张图

1. **路径独立成词**——前后是空白或行首行尾
2. **相对路径以 project root 为基准**（不是进程 CWD）
3. **文件真实存在且可读**——注意**刻意不要求在 root 内**（拖进来的桌面截图本来就在项目外）
4. **魔数是图片**（`MagicSniffer`，不看扩展名）

#### 为什么判据 3 不做包含校验（这条别自作主张加上）

期 1 的 `FileReferenceParser` 会拒掉越界路径，那是**防外部注入**（网页里写一段 `[file reference] path: ../../../etc/id_rsa` 被 Read 进来）。但这里是**用户自己拖进来的文件**，意图明确。把那道校验搬到这里，「从桌面拖图」这个最自然的用法会直接失效。

（越界的图仍然安全：Task 3 会把它**复制进 artifacts**，写进引用块的 `path` 因此落回 root 内。）

**Files:**
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/DetectedImage.java`
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/ImageAttachmentDetector.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/ImageAttachmentDetectorTest.java`

- [ ] **Step 1: 先写测试**

```java
package io.github.javaside.springai.codetui.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageAttachmentDetectorTest {

    @TempDir Path root;
    @TempDir Path outside;

    private Path png(Path dir, String rel) throws Exception {
        Path p = dir.resolve(rel);
        Files.createDirectories(p.getParent() == null ? dir : p.getParent());
        ImageIO.write(new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB), "png", p.toFile());
        return p;
    }

    private List<DetectedImage> detect(String text) {
        return new ImageAttachmentDetector().detect(text, root);
    }

    // ── 基本识别 ────────────────────────────────────────────

    @Test
    void detectsRelativePathAgainstProjectRoot() throws Exception {
        png(root, "docs/bug.png");
        List<DetectedImage> got = detect("看下 docs/bug.png 里这个报错");
        assertEquals(1, got.size());
        assertEquals("bug.png", got.get(0).name());
        assertEquals(120, got.get(0).width());
        assertEquals(80, got.get(0).height());
        assertTrue(got.get(0).insideRoot());
    }

    @Test
    void detectsAbsolutePathOutsideRoot() throws Exception {
        Path p = png(outside, "shot.png");
        List<DetectedImage> got = detect("看下 " + p + " 这个");
        assertEquals(1, got.size());
        assertTrue(!got.get(0).insideRoot(), "项目外的图应标记 insideRoot=false");
    }

    // ── 拖拽的转义形态（macOS 上最常见的入图方式）─────────────

    /**
     * macOS Terminal.app / iTerm2 拖拽时<b>用反斜杠转义空格</b>。漏了这条，
     * 「从桌面拖中文截图」（默认文件名就带空格）完全失效。
     */
    @Test
    void handlesBackslashEscapedSpaces() throws Exception {
        Path p = png(outside, "截屏 2026-08-02.png");
        String dragged = p.toString().replace(" ", "\\ ");
        assertEquals(1, detect("看下 " + dragged).size(), "反斜杠转义的路径没认出来");
    }

    @Test
    void handlesSingleQuotedPath() throws Exception {
        Path p = png(outside, "my shot.png");
        assertEquals(1, detect("看下 '" + p + "'").size());
    }

    @Test
    void handlesDoubleQuotedPath() throws Exception {
        Path p = png(outside, "my shot.png");
        assertEquals(1, detect("看下 \"" + p + "\"").size());
    }

    @Test
    void handlesChineseFileName() throws Exception {
        png(root, "docs/界面截图.png");
        assertEquals(1, detect("看下 docs/界面截图.png").size());
    }

    @Test
    void detectsMultiplePathsInOneMessage() throws Exception {
        png(root, "a.png");
        png(root, "b.png");
        assertEquals(2, detect("对比 a.png 和 b.png").size());
    }

    // ── 反例：这些都不该附 ───────────────────────────────────

    /** 魔数不是图片就不附——扩展名不可信。 */
    @Test
    void ignoresNonImageDespiteImageExtension() throws Exception {
        Files.writeString(root.resolve("fake.png"), "这其实是文本");
        assertTrue(detect("看下 fake.png").isEmpty(), "按扩展名误判成图片了");
    }

    @Test
    void ignoresMissingFile() {
        assertTrue(detect("看下 docs/nope.png").isEmpty());
    }

    /** 必须独立成词，不能从更长的词里切子串。 */
    @Test
    void ignoresPathEmbeddedInLongerToken() throws Exception {
        png(root, "bug.png");
        assertTrue(detect("见 xxbug.pngyy 这个").isEmpty(), "从更长的词里切出了子串");
    }

    @Test
    void emptyAndNullTextAreSafe() {
        assertTrue(detect("").isEmpty());
        assertTrue(detect(null).isEmpty());
        assertTrue(new ImageAttachmentDetector().detect("x", null).isEmpty());
    }

    // ── 上限 ────────────────────────────────────────────────

    /** 与期 1 的 VisionBudget.MAX_USER_IMAGES 同一个常量，不另设。 */
    @Test
    void capsAtMaxUserImagesAndReportsOverflow() throws Exception {
        for (int i = 0; i < 5; i++) png(root, "i" + i + ".png");
        ImageAttachmentDetector.Result r =
                new ImageAttachmentDetector().detectWithOverflow(
                        "i0.png i1.png i2.png i3.png i4.png", root);
        assertEquals(io.github.javaside.springai.codetui.agent.media.VisionBudget.MAX_USER_IMAGES,
                r.images().size());
        assertEquals(5 - io.github.javaside.springai.codetui.agent.media.VisionBudget.MAX_USER_IMAGES,
                r.overflow());
    }

    // ── 缓存 ────────────────────────────────────────────────

    /** 边打边识别会对同一路径反复嗅探；同一实例第二次不该重新读盘。 */
    @Test
    void repeatedDetectionOfSamePathIsCached() throws Exception {
        Path p = png(root, "c.png");
        ImageAttachmentDetector d = new ImageAttachmentDetector();
        assertEquals(1, d.detect("看 c.png", root).size());
        Files.delete(p);                       // 删掉原文件
        assertEquals(1, d.detect("看 c.png", root).size(), "第二次没命中缓存（重新读盘了）");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn test -pl springai-code-tui -Dtest=ImageAttachmentDetectorTest
```
期望：编译失败，`找不到符号: 类 DetectedImage`。**看到失败才继续。**

- [ ] **Step 3: 写 `DetectedImage`**

```java
package io.github.javaside.springai.codetui.ui;

import java.nio.file.Path;

/**
 * 从输入框文本里识别出的一张待附图片。
 *
 * @param file       磁盘上的真实文件（已确认存在、可读、魔数是图片）
 * @param name       给模型看的可读名字（取文件名）
 * @param width      像素宽（解析不出时为 0）
 * @param height     像素高（解析不出时为 0）
 * @param insideRoot 是否在 project root 内。<b>决定提交时怎么处理</b>：root 内指原文件
 *                   （模型 Read 得到当前内容），root 外必须复制进 artifacts——否则写进引用块的
 *                   越界 path 会被 {@code FileReferenceParser} 的注入防线整块丢弃，且无任何报错。
 */
public record DetectedImage(Path file, String name, int width, int height, boolean insideRoot) {
}
```

- [ ] **Step 4: 写 `ImageAttachmentDetector`**

```java
package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.media.VisionBudget;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 从输入框文本里识别待附的图片。识别的<b>全部</b>复杂度在这里，UI 层只剩渲染与按键。
 *
 * <p><b>为什么不引入 {@code @} 标记</b>：用户明确选了裸路径自动识别（拖拽白送支持——终端
 * 不传文件、只把路径当粘贴插进来）。代价是无法用语法表达「这个路径不要当图片」，
 * {@code 把 docs/bug.png 复制到 tmp/} 必然误附。该代价由<b>附件行可见 + Ctrl+G 撤销</b>承担，
 * <b>不靠规则消灭</b>——任何试图从句意猜意图的规则都比误附本身更不可预测。
 *
 * <p><b>刻意不做 root 包含校验</b>：那道校验（{@code PathContainment}）是防外部注入的，
 * 属于<b>引用块解析</b>阶段。用户自己拖进来的文件意图明确，在这里拦会让「从桌面拖图」失效。
 * 越界的图由调用方复制进 artifacts，写进引用块的 path 因此落回 root 内。
 *
 * <p><b>缓存</b>：边打边识别意味着每次击键都要重新扫一遍文本。按「路径字符串 + mtime」缓存，
 * 否则每个字符都要读盘做魔数嗅探。
 */
public final class ImageAttachmentDetector {

    /** 识别结果 + 因超上限被丢弃的张数（附件行要如实说出来，不能静默截断）。 */
    public record Result(List<DetectedImage> images, int overflow) {
        static final Result EMPTY = new Result(List.of(), 0);
    }

    /** 缓存：绝对路径 + mtime → 识别结果（空表示「不是可附的图片」）。 */
    private final Map<String, Optional<DetectedImage>> cache = new ConcurrentHashMap<>();

    /** 识别（截断到上限）。 */
    public List<DetectedImage> detect(String text, Path root) {
        return detectWithOverflow(text, root).images();
    }

    /** 识别，并报告被上限丢弃了几张。 */
    public Result detectWithOverflow(String text, Path root) {
        if (text == null || text.isBlank() || root == null) return Result.EMPTY;
        List<DetectedImage> all = new ArrayList<>();
        for (String token : tokenize(text)) {
            resolve(token, root).ifPresent(all::add);
        }
        if (all.size() <= VisionBudget.MAX_USER_IMAGES) {
            return new Result(List.copyOf(all), 0);
        }
        return new Result(List.copyOf(all.subList(0, VisionBudget.MAX_USER_IMAGES)),
                all.size() - VisionBudget.MAX_USER_IMAGES);
    }

    // 以下 tokenize / resolve 的实现见 Step 5、Step 6
}
```

- [ ] **Step 5: 实现 `tokenize`（转义形态全在这里）**

加进 `ImageAttachmentDetector`：

```java
    /**
     * 按空白切词，但尊重三种<b>终端拖拽会产生</b>的转义形态：
     * <ul>
     *   <li>{@code \ } 反斜杠转义空格 —— macOS Terminal.app / iTerm2 <b>默认</b>，
     *       中文截图默认文件名就带空格，漏了这条「从桌面拖图」完全失效；</li>
     *   <li>{@code '...'} 单引号包裹；</li>
     *   <li>{@code "..."} 双引号包裹。</li>
     * </ul>
     * 返回的是<b>已去转义</b>的词。引号内的空白不切词；反斜杠后的下一个字符原样保留。
     */
    static List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {                       // 反斜杠后的字符原样收，不参与切词
                cur.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (quote != 0) {
                if (c == quote) quote = 0; else cur.append(c);
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (Character.isWhitespace(c)) {
                if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }
```

- [ ] **Step 6: 实现 `resolve`（判据 2/3/4 + 缓存）**

```java
    /** 一个词能否解析成可附的图片。任何一条判据不过 → 空（当普通文本，不报错不提示）。 */
    private Optional<DetectedImage> resolve(String token, Path root) {
        Path file = toPath(token, root);
        if (file == null) return Optional.empty();
        String key;
        try {
            if (!Files.isRegularFile(file) || !Files.isReadable(file)) return Optional.empty();
            key = file.toAbsolutePath() + "|" + Files.getLastModifiedTime(file).toMillis();
        } catch (Exception e) {
            return Optional.empty();
        }
        return cache.computeIfAbsent(key, k -> sniff(file, root));
    }

    /** 词 → 路径。{@code ~} 展开；相对路径以 <b>project root</b> 为基准（不是进程 CWD）。 */
    private static Path toPath(String token, Path root) {
        if (token.isEmpty() || token.length() > 4096) return null;   // 4096: 常见 PATH_MAX，防病态输入
        try {
            String s = token;
            if (s.equals("~")) return null;                           // 家目录本身不是文件
            if (s.startsWith("~/")) {
                return Path.of(System.getProperty("user.home")).resolve(s.substring(2));
            }
            Path p = Path.of(s);
            return p.isAbsolute() ? p : root.resolve(p);
        } catch (Exception e) {                                        // 非法路径字符（NUL 等）
            return null;
        }
    }

    /** 读文件头判魔数 + 取尺寸。<b>绝不抛异常</b>——这跑在每次击键上。 */
    private static Optional<DetectedImage> sniff(Path file, Path root) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            var s = io.github.javaside.springai.codetui.agent.media.MagicSniffer.sniff(bytes);
            if (s.kind() != io.github.javaside.springai.codetui.agent.media.MediaKind.IMAGE) {
                return Optional.empty();
            }
            var dim = io.github.javaside.springai.codetui.agent.media.ImageDimensions.of(bytes);
            boolean inside = file.toAbsolutePath().normalize()
                    .startsWith(root.toAbsolutePath().normalize());
            return Optional.of(new DetectedImage(file, file.getFileName().toString(),
                    dim.map(d -> d[0]).orElse(0), dim.map(d -> d[1]).orElse(0), inside));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
```

**注意**：`MagicSniffer` / `MediaKind` / `ImageDimensions` / `VisionBudget` 都在 `agent.media` 包且是 `public`，跨包可见。**先 `grep` 确认它们的可见性与方法签名**，不一致就按实际调整，不要猜。

- [ ] **Step 7: 跑测试确认通过**

```bash
mvn test -pl springai-code-tui -Dtest=ImageAttachmentDetectorTest
```
期望：`Tests run: 13, Failures: 0`。

- [ ] **Step 8: 变异验证（强制，用 `git worktree`，一次只拆一处）**

本项目在期 1 抓到过 **5 条「不会失败的测试」**，其中 3 条出自计划书的示例代码。所以逐个隔离验证：

| 变异 | 期望变红 |
|---|---|
| `tokenize` 去掉反斜杠分支（`\\` 当普通字符） | `handlesBackslashEscapedSpaces` |
| `sniff` 去掉 `kind() != IMAGE` 判断 | `ignoresNonImageDespiteImageExtension` |
| `toPath` 里相对路径改成 `Path.of(s).toAbsolutePath()`（用进程 CWD） | `detectsRelativePathAgainstProjectRoot` |
| `detectWithOverflow` 去掉截断（返回全部） | `capsAtMaxUserImagesAndReportsOverflow` |
| `resolve` 不查缓存（每次重新 sniff） | `repeatedDetectionOfSamePathIsCached` |

**任一没变红，那条测试就是摆设，必须先修测试再继续。**

- [ ] **Step 9: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/DetectedImage.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/ImageAttachmentDetector.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/ImageAttachmentDetectorTest.java
git commit -m "feat(vision): 输入框图片路径识别器

四条判据全中才附：独立成词、相对路径以 project root 为基准、
文件真实存在可读、魔数是图片（不看扩展名）。

刻意不做 root 包含校验——那道校验是防外部注入的、属于引用块解析
阶段；用户自己拖进来的文件意图明确，在这里拦会让「从桌面拖图」失效。
越界的图由提交侧复制进 artifacts，path 因此落回 root 内。

转义形态三种都吃：反斜杠转义空格（macOS 终端拖拽的默认形态，
中文截图文件名就带空格，漏了则该用法完全失效）、单引号、双引号。

按「路径 + mtime」缓存——边打边识别意味着每次击键都要重扫一遍。"
```

---

### Task 2：附件行 + `Ctrl+G` 撤销

#### 为什么是 `Ctrl+G` 而不是 `Ctrl+D`

本项目**刻意实现了 readline 键位**（`Ctrl+A/E/W/U/K`、`Alt+B/F`，见 `CodeTuiView.onEditShortcut`）。readline 里 `Ctrl+D` 是「删除光标处字符」、空行时是 EOF/退出——绑给取消附件会跟肌肉记忆打架。`Ctrl+G` 在 readline 里正是 **abort 当前操作**，语义恰好对上，且本项目未占用。

#### ⚠️ 这个任务的单测证明不了什么

`Ctrl+G` 要经过 TamboUI 的**按键路由器**才能到应用，而本项目的单测入口 `feedKeyForTest` **绕过路由器**。`Tab`/`Shift+Tab` 就是这么被 `FOCUS_NEXT/PREVIOUS` 悄悄吃掉的——**单测全绿、实机死键**。

所以本任务的单测只验「状态机对不对」，「键到不到得了应用」由 **Task 5 的 pty 实机**负责。这不是偷懒，是把证据放在唯一能产生它的地方。

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/AttachmentLineTest.java`（新建）

- [ ] **Step 1: 先读现状**

```bash
grep -n "onInputKey\|onEditShortcut\|private final class InputBox\|preferredSize\|visualRowCount" \
  springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java | head -20
```

重点看三处：`onInputKey`（按键拦截点）、`InputBox.preferredSize`（输入框高度计算）、`InputBox.render`（画框）。附件行要画在输入框**下方**，不是框内。

也读一眼期 1 的离屏渲染测试工具 `ui/ViewScreen.java`——它能把整棵 UI 渲进 `Buffer` 再回读文本，本任务的渲染断言靠它。

- [ ] **Step 2: 写测试**

新建 `AttachmentLineTest.java`。**用 `ViewScreen` 断言屏幕上真有那行**，不要只测纯函数——本项目抓到过「内容构造得出来、却被前面某个分支提前 return 挡掉」的缺陷。

```java
package io.github.javaside.springai.codetui.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttachmentLineTest {

    /** 纯函数：识别结果 → 附件行文本。张数、超限、取消提示都在这一行里。 */
    @Test
    void oneImageShowsNameAndCancelHint() {
        String line = CodeTuiView.attachmentLine(1, 0, "bug.png");
        assertTrue(line.contains("1 张"), line);
        assertTrue(line.contains("bug.png"), line);
        assertTrue(line.contains("Ctrl+G"), "没告诉用户怎么取消：" + line);
    }

    /** 多张时不逐个列名（会撑爆一行），只给数量。 */
    @Test
    void multipleImagesShowCountWithoutListingAll() {
        String line = CodeTuiView.attachmentLine(3, 0, "a.png");
        assertTrue(line.contains("3 张"), line);
    }

    /** 超上限必须如实说出来——静默截断会让用户以为都附上了。 */
    @Test
    void overflowIsReportedNotSilentlyDropped() {
        String line = CodeTuiView.attachmentLine(3, 2, "a.png");
        assertTrue(line.contains("2"), "被丢弃的张数没说：" + line);
    }

    /** 没有图时不出这一行——常态纯文本输入多一行恒定噪音会稀释真正要看的东西。 */
    @Test
    void noImagesMeansNoLine() {
        assertTrue(CodeTuiView.attachmentLine(0, 0, null).isEmpty());
    }

    /** 取消后附件行改为「已取消」，且不再显示张数——否则看不出取消生效了没有。 */
    @Test
    void cancelledStateIsVisuallyDistinct() {
        String line = CodeTuiView.attachmentLineCancelled();
        assertTrue(line.contains("已取消"), line);
        assertFalse(line.contains("Ctrl+G"), "已取消还提示 Ctrl+G 是噪音：" + line);
    }
}
```

**另外必须写一条渲染断言**（放同一个测试类）：构造一个 `CodeTuiView`、让 `inputState` 含一个真实图片路径、用 `ViewScreen.of(view)` 回读屏幕，断言附件行**真的出现在屏幕上**。参考 `ui/` 下已有用 `ViewScreen` 的测试怎么造 view。

**如果你发现造 view 的成本过高**（依赖一堆桩），退而求其次：把附件行的**渲染分支条件**抽成一个包级静态纯函数（如 `static boolean shouldShowAttachmentLine(int count, boolean cancelled)`）并测它——但要在报告里说明你没能做渲染级断言，以及为什么。

- [ ] **Step 3: 跑测试确认失败**

```bash
mvn test -pl springai-code-tui -Dtest=AttachmentLineTest
```
期望：编译失败，找不到 `attachmentLine`。

- [ ] **Step 4: 实现附件行文本（纯函数）**

加进 `CodeTuiView`：

```java
    /**
     * 附件行文本。<b>纯函数</b>，便于单测——渲染分支顺序类的缺陷本项目栽过，
     * 内容函数与渲染必须分开测。
     *
     * @param count    已识别（未取消）的图片张数
     * @param overflow 因超上限被丢弃的张数，<b>必须如实显示</b>：静默截断会让用户以为都附上了
     * @param firstName 第一张的文件名，只在 count==1 时用（多张时列名会撑爆一行）
     */
    static String attachmentLine(int count, int overflow, String firstName) {
        if (count <= 0) return "";
        StringBuilder b = new StringBuilder("  ⏎ 已附带 ").append(count).append(" 张图片");
        if (count == 1 && firstName != null) b.append("（").append(firstName).append("）");
        if (overflow > 0) b.append("，另有 ").append(overflow).append(" 张超出上限未附");
        b.append("  · Ctrl+G 取消");
        return b.toString();
    }

    /** 取消后的附件行。刻意不再提示 Ctrl+G——已经取消了，再提示是噪音。 */
    static String attachmentLineCancelled() {
        return "  ⏎ 已取消附件";
    }
```

- [ ] **Step 5: 接上状态与按键**

在 `CodeTuiView` 加两个字段：

```java
    private final ImageAttachmentDetector imageDetector = new ImageAttachmentDetector();
    /** 本次输入是否已按 Ctrl+G 取消附件。提交或清空输入后复位——否则取消一次就永久失效。 */
    private boolean attachmentsCancelled = false;
```

在 `onInputKey` 里拦 `Ctrl+G`（**放在把键转交给 textArea 之前**，否则会被当普通字符吃掉）：

```java
        if (k.isCtrl() && k.character() == 'g') {     // 按实际 KeyEvent API 调整
            attachmentsCancelled = true;
            return EventResult.handled();
        }
```

**`KeyEvent` 判 Ctrl+字母的真实 API 请先 `grep` 现有 `isCtrlC()` / `onEditShortcut` 怎么写的**，照抄同一种写法，不要自创。

复位点：`submitInput()` 成功提交后、以及 `inputState.clear()` 的各处，把 `attachmentsCancelled = false`。**`/clear`、`/model` 等斜杠命令分支里也要复位**——它们都会 `inputState.clear()`。

- [ ] **Step 6: 渲染附件行**

在 `InputBox` 的 `preferredSize` 里为附件行**多留一行**（有附件时才留），在 `render` 里画出来。

⚠️ **本项目铁律：一个 `OutputLine` = 一个物理行。** 附件行必须是单行，字符串里不能有 `\n`。

⚠️ **`preferredSize` 与 `render` 的高度必须一致**——多留了行不画会留白，画了没留会被裁掉。

- [ ] **Step 7: 跑测试 + 全模块回归**

```bash
mvn test -pl springai-code-tui -Dtest=AttachmentLineTest
mvn test -pl springai-code-tui              # 基线 1054
```

- [ ] **Step 8: 变异验证（强制）**

| 变异 | 期望变红 |
|---|---|
| `attachmentLine` 去掉 overflow 那段 | `overflowIsReportedNotSilentlyDropped` |
| `attachmentLine` 在 count==0 时也返回文本 | `noImagesMeansNoLine` |
| `attachmentLineCancelled` 里加回 `Ctrl+G` | `cancelledStateIsVisuallyDistinct` |

- [ ] **Step 9: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/AttachmentLineTest.java
git commit -m "feat(vision): 输入框附件行 + Ctrl+G 撤销

裸路径自动识别必然会误附（把 docs/bug.png 复制到 tmp/ 三条判据全中），
代价由「可见 + 可撤销」承担而不是靠规则消灭。附件行实时显示，
超上限如实说出被丢了几张——静默截断会让用户以为都附上了。

取消键选 Ctrl+G 而非 Ctrl+D：本项目刻意实现 readline 键位，
readline 里 Ctrl+D 是删除字符/EOF，Ctrl+G 才是 abort。

注意：Ctrl+G 能否真到达应用，单测证明不了（feedKeyForTest 绕过
路由器，Tab 就是这么被焦点导航悄悄吃掉的），由 pty 冒烟负责。"
```

---

### Task 3：提交前注入引用块 + 能力闸门

这一步是整期的**汇合点**：把识别结果变成期 1 认得的引用块。

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/AttachmentInjectionTest.java`（新建）

- [ ] **Step 1: 先写测试**

```java
package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.media.FileReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttachmentInjectionTest {

    @TempDir Path root;
    @TempDir Path outside;

    private Path png(Path dir, String rel) throws Exception {
        Path p = dir.resolve(rel);
        Files.createDirectories(p.getParent() == null ? dir : p.getParent());
        ImageIO.write(new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB), "png", p.toFile());
        return p;
    }

    /** 正文在前、引用块在后，中间隔一个换行——模型先读到用户在问什么。 */
    @Test
    void referenceBlockIsAppendedAfterUserText() throws Exception {
        Path p = png(root, "docs/bug.png");
        String out = CodeTuiView.injectAttachments("看下这个报错",
                List.of(new DetectedImage(p, "bug.png", 64, 48, true)), root);
        assertTrue(out.startsWith("看下这个报错"), out);
        assertTrue(out.contains(FileReference.OPEN), "没有引用块");
        assertTrue(out.contains("name: bug.png"), out);
    }

    /**
     * 项目内的图指原路径、不复制——你更新了 design.png，模型该看到新版。
     */
    @Test
    void insideRootImagePointsAtOriginalFileNotACopy() throws Exception {
        Path p = png(root, "docs/design.png");
        String out = CodeTuiView.injectAttachments("照这个改",
                List.of(new DetectedImage(p, "design.png", 64, 48, true)), root);
        assertTrue(out.contains("path: docs/design.png"),
                "项目内的图被复制成 artifact 了，模型将永远看到旧版：\n" + out);
    }

    /**
     * ★ 项目外的图<b>必须</b>复制进 artifacts：按原路径写进引用块的话，
     * 会被 FileReferenceParser 的越界防线整块丢弃，且没有任何报错——图静默消失。
     */
    @Test
    void outsideRootImageMustBeCopiedIntoArtifacts() throws Exception {
        Path p = png(outside, "shot.png");
        String out = CodeTuiView.injectAttachments("看这个",
                List.of(new DetectedImage(p, "shot.png", 64, 48, false)), root);
        assertTrue(out.contains("path: .codetui/artifacts/"),
                "项目外的图没复制进 artifacts，引用块会被解析器丢弃：\n" + out);
        assertTrue(out.contains("name: shot.png"), "复制后丢了原始文件名：" + out);
    }

    /** ★ 产出的引用块必须能被期 1 的解析器认回来——两边格式漂移就白做了。 */
    @Test
    void producedBlockIsAcceptedByPhaseOneParser() throws Exception {
        Path p = png(root, "a.png");
        String out = CodeTuiView.injectAttachments("看",
                List.of(new DetectedImage(p, "a.png", 64, 48, true)), root);
        var refs = io.github.javaside.springai.codetui.agent.media.FileReferenceParser.parse(out, root);
        assertEquals(1, refs.size(), "期 1 的解析器不认这个块：\n" + out);
        assertEquals("a.png", refs.get(0).name());
    }

    @Test
    void noAttachmentsMeansTextUnchanged() {
        assertEquals("原样", CodeTuiView.injectAttachments("原样", List.of(), root));
    }
}
```

**`producedBlockIsAcceptedByPhaseOneParser` 是这个任务最重要的一条**：它把「我产出的格式」和「期 1 消费的格式」钉在一起。少了它，两边各自演化、格式一漂移就是图静默消失。

- [ ] **Step 2: 跑测试确认失败**

```bash
mvn test -pl springai-code-tui -Dtest=AttachmentInjectionTest
```

- [ ] **Step 3: 实现 `injectAttachments`**

```java
    /**
     * 把识别到的图片渲成 {@code [file reference]} 块追加进待发文本。
     *
     * <p><b>为什么走文本注入而不是新开 API</b>：期 1 的 {@code VisionMaterializer} 已经会处理
     * user 消息里的引用块（路径 U，有完整测试，只是没有入口去产生这种消息）。走这条则
     * {@code SubmitHandler} / {@code CodingAgent} / 兑现器 / 预算一行都不用改。与既有的
     * 斜杠技能注入同套路：会话持久化「注入后」的文本，实时 UI 只显示用户原文。
     *
     * <p><b>项目内不复制、项目外必须复制</b>：前者是为了「你更新了 design.png，模型该看到新版」；
     * 后者是硬约束——按原路径写进引用块会被 {@code FileReferenceParser} 的越界防线整块丢弃，
     * <b>且没有任何报错</b>，图就这么静默消失。
     */
    static String injectAttachments(String text, List<DetectedImage> images, Path root) {
        if (images == null || images.isEmpty()) return text;
        StringBuilder b = new StringBuilder(text);
        for (DetectedImage img : images) {
            MediaArtifact a = img.insideRoot()
                    ? existingFileArtifact(img, root)
                    : copyIntoArtifacts(img, root);
            if (a == null) continue;              // 单张失败不连累其余，也不打断提交
            b.append('\n').append(FileReference.render(
                    a, FileReference.DELIVERY_NOT_IN_VIEW,
                    "user attachment; not currently in view"));
        }
        return b.toString();
    }
```

`existingFileArtifact` / `copyIntoArtifacts` 的实现你来写。要点：

- **`existingFileArtifact`**：`source=EXISTING_FILE`、`ownedByStore=false`、`relativePath` 用 root 相对路径、`originalName` 用文件名。sha 用**文件绝对路径的 SHA-256**（期 1 对 `EXISTING_FILE` 就是这么做的，见 `MediaExternalizingCallback.referenceExistingFile`——**先去读那段照抄**，别自创）。
- **`copyIntoArtifacts`**：直接用现成的 `MediaArtifactStore.put(bytes, declaredMime, originalName)`，它已经做了内容寻址、原子写、去重。artifacts 目录是 `root.resolve(".codetui").resolve("artifacts")`。

- [ ] **Step 4: 接进 `submitInput`**

在斜杠命令分支**之后**、`dispatch()` **之前**：

```java
        var det = imageDetector.detectWithOverflow(text, root);
        List<DetectedImage> attached = attachmentsCancelled ? List.of() : det.images();
        if (!attached.isEmpty() && !VisionModels.supportsImage(onSubmit.currentModel())) {
            state.setNotice("当前模型 " + onSubmit.currentModel()
                    + " 不支持图片输入，用 /model 换一个（输入已保留）");
            return;                              // ★ 不清空 inputState：切完模型直接回车重发
        }
        String effective = injectAttachments(text, attached, root);
```

⚠️ **拦住时绝不能 `inputState.clear()`**——不保留输入的话每次都要重贴，这功能不会有人用。

⚠️ 传给 `dispatch` 的是 `effective`，但 `addHistory(text)` 用的仍是**原文**（↑↓ 回溯不该翻出机器格式）。

- [ ] **Step 5: 跑测试 + 全模块回归**

```bash
mvn test -pl springai-code-tui -Dtest=AttachmentInjectionTest
mvn test -pl springai-code-tui
```

- [ ] **Step 6: 变异验证（强制，`git worktree`，一次只拆一处）**

| 变异 | 期望变红 |
|---|---|
| `insideRoot` 分支也走 `copyIntoArtifacts` | `insideRootImagePointsAtOriginalFileNotACopy` |
| `!insideRoot` 分支也走 `existingFileArtifact` | `outsideRootImageMustBeCopiedIntoArtifacts` **和** `producedBlockIsAcceptedByPhaseOneParser`（若你也为项目外的图加了解析断言） |
| `FileReference.render` 的 delivery 换成一个不存在的字符串 | `producedBlockIsAcceptedByPhaseOneParser` **不一定会红** —— 若真不红，说明解析器不校验 delivery，**如实报告，别硬编测试** |

最后一条是个**开放观察**，不是必须变红项。我要的是你去看一眼再告诉我。

- [ ] **Step 7: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/AttachmentInjectionTest.java
git commit -m "feat(vision): 提交前把附件渲成引用块 + 能力闸门

走文本注入而非新开 API：期 1 的兑现器已经会处理 user 消息里的
引用块，只是没有入口产生这种消息。故 SubmitHandler/CodingAgent/
兑现器/预算一行都不用改。

项目内的图指原文件（你更新了 design.png，模型该看到新版）；
项目外的必须复制进 artifacts——按原路径写会被 FileReferenceParser
的越界防线整块丢弃，且没有任何报错，图静默消失。

模型不支持视觉时拦住不发，且输入原地保留：不保留的话每次都要
重贴，这功能不会有人用。"
```

---

### Task 4：`-c` 回放把引用块渲成一行

存储里那条 user 消息含**八行引用块**。不处理的话 `-c` 之后满屏机器格式。

`HistoryReplay` 里**已有现成先例**——它剥 `<skill_instruction>` 前缀那段（`stripSkillInstruction`），注释写着「会话持久化的是注入后的有效文本；实时 UI 只显示用户原文，回放须一致」。引用块是同一个问题，照抄同一个套路。

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/HistoryReplay.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/HistoryReplayTest.java`（若已存在则追加用例）

- [ ] **Step 1: 先读现状**

```bash
sed -n '30,45p;85,105p' springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/HistoryReplay.java
ls springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/ | grep -i history
```

- [ ] **Step 2: 写测试**

```java
    /** 引用块要渲成一行可读的，否则 -c 之后满屏机器格式。 */
    @Test
    void referenceBlockBecomesSingleReadableLine() {
        String stored = "看下这个报错\n"
                + "[file reference]\nid: sha256:abcd\nkind: image\nmime_type: image/png\n"
                + "size_bytes: 1234\ndimensions: 1440x900\nname: bug.png\n"
                + "path: docs/bug.png\ndelivery: not_in_view\nreason: x\n[/file reference]";
        String shown = HistoryReplay.stripFileReferences(stored);
        assertTrue(shown.contains("📎 bug.png"), shown);
        assertTrue(shown.contains("1440×900"), shown);
        assertFalse(shown.contains("[file reference]"), "机器格式漏出来了：" + shown);
        assertTrue(shown.startsWith("看下这个报错"), "正文被动了：" + shown);
    }

    /** 多个引用块各渲一行。 */
    @Test
    void multipleReferenceBlocksEachBecomeOneLine() {
        String one = "[file reference]\nkind: image\nname: a.png\ndimensions: 10x10\n"
                + "path: a.png\n[/file reference]";
        String two = one.replace("a.png", "b.png");
        String shown = HistoryReplay.stripFileReferences("对比\n" + one + "\n" + two);
        assertTrue(shown.contains("📎 a.png"), shown);
        assertTrue(shown.contains("📎 b.png"), shown);
        assertFalse(shown.contains("[/file reference]"), shown);
    }

    /** 没有引用块时原样返回——不该给普通消息加工。 */
    @Test
    void plainTextIsUntouched() {
        assertEquals("普通消息", HistoryReplay.stripFileReferences("普通消息"));
    }

    /** 残缺块（只有开标记）保底原样，不误删正文——与 stripSkillInstruction 同纪律。 */
    @Test
    void unterminatedBlockIsLeftAloneRatherThanEatingTheRest() {
        String broken = "看这个\n[file reference]\nkind: image";
        assertEquals(broken, HistoryReplay.stripFileReferences(broken));
    }
```

- [ ] **Step 3: 实现 `stripFileReferences`**

```java
    /**
     * 把引用块换成一行 {@code 📎 name (w×h)}。
     *
     * <p>与 {@link #stripSkillInstruction} 同一个理由：会话持久化的是「注入后」的有效文本，
     * 而实时 UI 只显示用户原文（{@code onUserMessage} 收到的是注入前的），回放须一致。
     * 不处理的话 {@code -c} 之后每张图都甩八行机器格式给用户看。
     *
     * <p>残缺块（有开标记无闭标记）<b>保底原样</b>，不误删后面的正文——与
     * {@code stripSkillInstruction} 同纪律。
     */
    static String stripFileReferences(String text) {
        if (text == null || !text.contains(FileReference.OPEN)) return text;
        StringBuilder out = new StringBuilder();
        int from = 0;
        while (true) {
            int open = text.indexOf(FileReference.OPEN, from);
            if (open < 0) break;
            int close = text.indexOf(FileReference.CLOSE, open);
            if (close < 0) break;                       // 残缺：剩余部分原样收尾
            int end = close + FileReference.CLOSE.length();
            out.append(text, from, open).append(summarise(text.substring(open, end)));
            from = end;
        }
        out.append(text.substring(from));
        return out.toString();
    }

    /** 一个块 → 一行。缺字段就少显示，不报错。 */
    private static String summarise(String block) {
        String name = field(block, "name");
        String dim = field(block, "dimensions");
        if (name == null) name = "图片";
        return "📎 " + name + (dim == null ? "" : " (" + dim.replace("x", "×") + ")");
    }

    private static String field(String block, String key) {
        for (String line : block.split("\n")) {
            if (line.startsWith(key + ": ")) return line.substring(key.length() + 2).trim();
        }
        return null;
    }
```

- [ ] **Step 4: 接进 USER 分支**

约 39 行：

```java
                    out.add(new OutputLine("› " + stripFileReferences(
                            stripSkillInstruction(safe(m.getText()))), Kind.USER));
```

⚠️ **顺序**：先剥技能前缀再剥引用块（两者互不重叠，但保持一个确定顺序便于推理）。

⚠️ **本项目铁律**：一个 `OutputLine` = 一个物理行。`stripFileReferences` 的产物里可能仍有 `\n`（正文本来就是多行），这没问题——`userBlock` 自身会按 `\n` 拆行、软折，与实时同路径（见该处既有注释）。

- [ ] **Step 5: 跑测试 + 回归**

```bash
mvn test -pl springai-code-tui -Dtest=HistoryReplayTest
mvn test -pl springai-code-tui
```

- [ ] **Step 6: 变异验证（强制）**

| 变异 | 期望变红 |
|---|---|
| `stripFileReferences` 直接 `return text` | `referenceBlockBecomesSingleReadableLine` |
| 残缺块分支改成「剩下的全删」 | `unterminatedBlockIsLeftAloneRatherThanEatingTheRest` |

- [ ] **Step 7: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/HistoryReplay.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/HistoryReplayTest.java
git commit -m "feat(vision): -c 回放把引用块渲成 📎 一行

存储里那条 user 消息含八行引用块，不处理的话 -c 之后满屏机器格式。
与既有的 stripSkillInstruction 同理由、同纪律：会话存的是注入后的
文本，实时 UI 只显示原文，回放须一致；残缺块保底原样不误删正文。"
```

---

### Task 5：pty 实机冒烟（**本期唯一能证明按键有效的证据**）

#### 为什么必须做

`Ctrl+G` 要经 TamboUI 的**按键路由器**才能到应用，而单测入口 `feedKeyForTest` **绕过路由器**。本项目的前科：`Tab` / `Shift+Tab` 被 `FOCUS_NEXT/PREVIOUS` 悄悄吃掉，**单测全绿、实机死键，无人发现**（因为单测走的正是绕过路由器的那个入口）。

**Files:**
- Create: `springai-code-tui/src/test/resources/scripts/attachment_smoke.py`

- [ ] **Step 1: 读现成脚本照抄骨架**

```bash
ls springai-code-tui/src/test/resources/scripts/
sed -n '1,60p' springai-code-tui/src/test/resources/scripts/permission_smoke.py
```

⚠️ **两个必须照抄的设置**（本项目踩过）：
- `pty.fork` 默认窗口 **0×0，渲染全空白** → 必须 `ioctl TIOCSWINSZ` 设真实尺寸
- 必须 `TERM=xterm-256color`，否则读不到真实屏幕

⚠️ **陈旧 scrollback 陷阱**：`screen_text()` / `wait_for` / `find_row` **都看得到历史**，断言「当前状态」不能只靠子串——历史里出现过一次就会误判通过。`permission_smoke.py` 里有个 `assert_rows_below(session, anchor, needles, what)` 就是为此写的，**直接复用**。

- [ ] **Step 2: 写断言（至少这五条）**

1. 输入一个真实图片的相对路径 → **附件行出现**，含「1 张」与「Ctrl+G 取消」
2. 按 **`Ctrl+G`** → 附件行变成「已取消附件」
   （**这条是本任务的全部意义**——它是唯一能证明 `Ctrl+G` 没被路由器吞掉的证据）
3. 取消后再敲一个字符 → **不该**重新出现「已附带」（取消状态在本次输入内保持）
4. 提交后再输入同一路径 → 附件行**重新出现**（取消状态已复位，不是永久失效）
5. 输入一个**非图片**路径（如 `pom.xml`）→ **不出现**附件行

测试图片就在脚本里用 Python 生成一个最小 PNG 落到临时项目目录，别往仓库塞二进制夹具。

- [ ] **Step 3: 跑**

```bash
cd springai-code-tui && python3 src/test/resources/scripts/attachment_smoke.py
```
期望：`SMOKE PASS` 且断言条数与你写的一致。

**若第 2 条失败**（`Ctrl+G` 到不了应用）：那正是这个任务存在的理由。参考期 1 修 `Tab` 的做法——`configure()` 里 unbind 抢键的默认绑定。**改完必须重跑脚本**，不要靠推理认为修好了。

- [ ] **Step 4: 提交**

```bash
git add springai-code-tui/src/test/resources/scripts/attachment_smoke.py
git commit -m "test(vision): 附件行与 Ctrl+G 的 pty 实机冒烟

Ctrl+G 要经 TamboUI 按键路由器才能到应用，而单测入口 feedKeyForTest
绕过路由器——Tab/Shift+Tab 就是这么被焦点导航悄悄吃掉的，单测全绿、
实机死键、无人发现。这个脚本是本期唯一能证明按键有效的证据。

照抄 permission_smoke.py 的 ioctl TIOCSWINSZ + TERM 设置（pty.fork
默认 0×0 渲染全空白），并复用 assert_rows_below 避开陈旧 scrollback。"
```

---

### Task 6：文档

**Files:**
- Modify: `springai-code-tui/README.md`
- Modify: `docs/superpowers/plans/2026-08-02-vision-input-phase1.md`（标注期 2 已完成）

- [ ] **Step 1: 改 README 的「本期不支持用户直接贴图」警告框**

期 1 在「视觉输入」小节开头放了一个显眼的警告框，说贴图属期 2、尚未实现。**那个框现在必须删掉或改写**——留着就是过期的谎言。

改成正面描述用法，至少写清六件事：

1. 输入框里直接写图片路径即可，**也可以把文件拖进终端**（终端会把路径当粘贴插进来）
2. 拖拽含空格的文件名（macOS 中文截图默认就带空格）也能认——反斜杠转义、单双引号三种形态都吃
3. **最多 3 张**，超出会在附件行注明
4. **误附时按 `Ctrl+G` 取消**——自动识别不可能完美，`把 docs/bug.png 复制到 tmp/` 这种句子会被误认
5. 模型不支持视觉时**拦住不发**，输入框内容保留，`/model` 切完直接回车重发
6. **项目内的图指原文件**（你更新了它，模型看到新版）；**项目外的图会复制一份进 `.codetui/artifacts/`**

- [ ] **Step 2: 标注期 1 计划书**

在 `2026-08-02-vision-input-phase1.md` 顶部那段「✅ 期 1 已全部完成」后面加一句，指向期 2 的计划与完成状态。

- [ ] **Step 3: 提交**

```bash
git add springai-code-tui/README.md docs/superpowers/plans/2026-08-02-vision-input-phase1.md
git commit -m "docs(vision): README 补用户贴图用法，删掉过期的「本期不支持」警告

期 1 那个警告框留着就是过期的谎言。改成正面描述：直接写路径或把
文件拖进终端、三种转义形态都吃、最多 3 张、误附按 Ctrl+G 取消、
模型不支持视觉时拦住且保留输入、项目内指原文件项目外复制一份。"
```

---

## 自审结果

**1 · spec 覆盖检查**

| spec 章节 | 落点 |
|---|---|
| §1 为什么改动面小（文本注入复用期 1） | Task 3 |
| §2.1 裸路径、不引入标记 | Task 1（识别器整体） |
| §2.2 四条判据 + 刻意不做包含校验 | Task 1 Step 4/6 + 测试反例 |
| §2.3 转义形态（反斜杠/单双引号/`~`/中文） | Task 1 Step 5 `tokenize` + 4 条测试 |
| §2.4 上限 3 张 | Task 1 `detectWithOverflow` |
| §3.1 边打边识别 + 缓存 | Task 1 缓存 + Task 2 接线 |
| §3.2 `Ctrl+G`（含为何不用 `Ctrl+D`） | Task 2 |
| §3.3 能力闸门 + 输入保留 | Task 3 Step 4 |
| §4 提交路径 | Task 3 |
| §5 快照语义（项目内指原文件 / 项目外复制） | Task 3 两条测试 + 两个变异 |
| §5.1 「项目外必须复制」是硬约束 | Task 3 `outsideRootImageMustBeCopiedIntoArtifacts` |
| §6 `-c` 回放 | Task 4 |
| §7 组件 | 文件结构表 |
| §8.1 可离线单测的 | Task 1 的 13 条 |
| §8.2 必须 pty 实机的 | Task 5 |
| §8.3 用户实机验收（亲自拖带空格中文名） | 见下「交付后须用户确认」 |
| §9 非目标 | 不实现即满足 |
| §10 已知代价 | Task 2 提交信息 + Task 6 README |

**无缺口。**

**2 · 占位扫描**：无 TBD。三处**刻意的「按实际调整」**——`KeyEvent` 判 Ctrl+字母的 API、`existingFileArtifact` 的 sha 算法、`HistoryReplayTest` 是否已存在——都明写了「先 `grep`/读现成代码照抄，不要猜」。这是本项目的既定纪律（有过 `javap` 手打路径命中 `.m2` 旧 jar 的教训）。

Task 3 Step 6 的第三个变异是**开放观察**而非必须变红项，已在原地标明。

**3 · 类型一致性**：`DetectedImage(file, name, width, height, insideRoot)` / `ImageAttachmentDetector.detect(text, root)` / `.detectWithOverflow(text, root) → Result(images, overflow)` / `CodeTuiView.attachmentLine(count, overflow, firstName)` / `.attachmentLineCancelled()` / `.injectAttachments(text, images, root)` / `HistoryReplay.stripFileReferences(text)` —— 各任务间调用签名已逐一对齐。

**4 · 风险最高的一步**：Task 5 的第 2 条断言。若 `Ctrl+G` 到不了应用，Task 2 的实现要改（unbind 抢键的默认绑定），**且这是单测原理上抓不到的那类问题**。

---

## 交付后须用户确认（自动化替代不了）

- **亲自把一个带空格的中文文件名从桌面拖进输入框**，确认识别成功。
  spec §2.3 里 macOS 终端「反斜杠转义空格」的行为是**推断**，须实证。若你用的终端行为不同，以实测为准并回来改 `tokenize`。


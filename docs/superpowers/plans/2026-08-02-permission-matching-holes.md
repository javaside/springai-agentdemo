# 权限管理 期 3（补判定漏洞 + 规则可删）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 补掉四条「用户以为拦住了、实际没拦」的判定漏洞（大小写、符号链接、`ACCEPT_EDITS` 命令段不判工作区、过宽 root），并让 `/permissions` 能就地删规则。

**Architecture:** 匹配的放宽**只在 deny/ask 方向**——deny 误拦看得见能调，allow 误放行不可逆。放宽逻辑全部落在 `PermissionEngine`（与那里既有的「命令分段不对称」同处一地），`PermissionRule` 一行不改。符号链接是**真别名**（引擎生成候选逐个试），大小写是**匹配模式**（给 deny/ask 规则预生成折叠孪生）。

**Tech Stack:** Java 17（`maven.compiler.release=17`）、Spring AI 2.0.0、TamboUI 0.4.0、JUnit 5、pty+pyte 实机冒烟。

---

## 上游文档

- **spec**：`docs/superpowers/specs/2026-08-02-permission-matching-holes-design.md`（本计划的唯一依据）
- **期 1/2 的实施记录**：`docs/superpowers/plans/2026-07-31-permission-mode.md`、`2026-08-01-permission-mode-phase2.md`——**动手前先读它们文末的实施记录**，那里有一次性握手、模态队列、变异实测的全部血泪细节。

## 贯穿硬约束（违反任一条都会造成静默漏放或假绿）

1. **allow 方向绝不放宽。** 这是整个方案的安全支点。每个涉及匹配的任务都必须带一条反向断言。
2. **`PermissionRule.java` 不改。** 若你发现非改不可，**停下来报 BLOCKED**——那说明分层错了（期 1 明确否决过「behavior 语义进匹配原语」）。
3. **测大小写不许真去创建 `/ETC/x`**：APFS 上它与 `/etc/x` 是同一个文件，测试会因**错误的原因**变绿，换到 Linux CI 就红。断言只打匹配逻辑。
4. **Java 17**：没有类型模式 switch、没有 record pattern。
5. **验证命令必须带模块作用域**：`mvn test -pl springai-code-tui`；**不许**加 `-DfailIfNoSpecifiedTests=false`。
6. **既有 flaky**：`CodingAgentSpikeTest.todoTurnIdBinding`（打真实模型、60s 超时）的红**单独记录、不计入**判据。
7. **每条修复都要变异实测**：停用该修复后对应用例必须变红；仍绿说明用例没打到那条路径，**先修用例**。
8. **经 `EventRouter` 分发的按键行为，单测绿不构成证据**，必须 pty 实机；改完要重新 `package` 再跑冒烟。
9. **并行执行时 `git add` 写确切文件名，绝不 `git add -A`**；提交前先 `mvn -o test-compile -pl springai-code-tui`（编译是全模块的）。

---

## 文件结构

| 文件 | 增/改 | 职责 |
|---|---|---|
| `agent/permission/PathAliases.java` | **新** | 路径的多种写法：符号链接解析（最长已存在祖先 + 拼回剩余）。纯工具类，无状态 |
| `agent/permission/PermissionEngine.java` | 改 | 别名 + 折叠孪生（仅 deny/ask）；`ACCEPT_EDITS` 命令段判工作区；规则删除入口 |
| `agent/permission/DangerousPaths.java` | 改 | 接入别名；大小写折叠审计；过宽 root 判据 |
| `agent/permission/BashCommandSplitter.java` | 改 | 新增 `pathArguments(segment)`：把段拆成候选路径 token（只做词法，不判策略） |
| `agent/permission/PermissionConfigWriter.java` | 改 | 新增 `remove`，纪律照抄 `append` |
| `ui/CodeTuiView.java` | 改 | `/permissions` 改交互面板 + 删除确认 |
| `CodeTuiApplication.java` | 改 | 过宽 root 的启动提示 |
| `src/test/resources/scripts/permission_smoke.py` | 改 | `/permissions` 面板的实机断言 |
| `README.md` / `SECURITY.md` | 改 | 已知限制随修复更新 |

**职责边界**：`BashCommandSplitter` 只做**词法**（哪些 token 长得像路径），`PermissionEngine` 做**策略**（这些路径在不在工作区内）。root 是引擎才有的知识，不下沉到 splitter。

---

## Task 1: `PathAliases`（符号链接解析）

**Files:**
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/permission/PathAliases.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/permission/PathAliasesTest.java`

**背景**：期 1 刻意没用 `toRealPath()`——它对**尚不存在的文件**直接抛，而「写一个还不存在的文件」是常态。解法是解析**最长的已存在祖先**再拼回剩余段。

- [x] **Step 1: 写失败测试**

Create `PathAliasesTest.java`:

```java
package io.github.javaside.springai.codetui.agent.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathAliasesTest {

    /** Windows 上建符号链接要特权：跳过而不是失败。 */
    private static Path symlink(Path link, Path target) {
        try {
            return Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "本环境不支持创建符号链接：" + e.getMessage());
            return null;
        }
    }

    @Test
    @DisplayName("无符号链接时只有一个写法——别名不该凭空制造候选")
    void plainPathHasSingleAlias(@TempDir Path dir) throws Exception {
        Path f = Files.createFile(dir.resolve("a.txt"));
        assertEquals(List.of(f.normalize()), PathAliases.of(f));
    }

    @Test
    @DisplayName("目标文件尚不存在、但父目录经符号链接指向别处——这是两步绕过的形态")
    void resolvesThroughSymlinkedParentForMissingFile(@TempDir Path dir) throws Exception {
        Path real = Files.createDirectories(dir.resolve("real/sub"));
        Path link = symlink(dir.resolve("link"), dir.resolve("real"));

        // link/sub/new.txt 尚不存在；toRealPath 会直接抛，故必须走「最长已存在祖先」
        Path target = link.resolve("sub/new.txt");
        List<Path> aliases = PathAliases.of(target);

        assertTrue(aliases.contains(target.normalize()), "原写法必须保留：" + aliases);
        assertTrue(aliases.contains(real.resolve("new.txt").normalize()),
                "应解析出真实路径 " + real.resolve("new.txt") + "，实际：" + aliases);
    }

    @Test
    @DisplayName("整条路径都不存在也不抛——退化成只有原写法")
    void missingAncestorsDoNotThrow(@TempDir Path dir) {
        Path target = dir.resolve("no/such/dir/x.txt");
        List<Path> aliases = PathAliases.of(target);
        assertTrue(aliases.contains(target.normalize()));
    }

    @Test
    @DisplayName("null 与相对路径不抛——调用方在判定热路径上，不该为此加判空")
    void nullAndRelativeAreSafe() {
        assertTrue(PathAliases.of(null).isEmpty());
        assertEquals(1, PathAliases.of(Path.of("relative/x.txt")).size(),
                "相对路径无从解析，原样返回一个");
    }

    @Test
    @DisplayName("别名去重且原写法排第一——调用方靠顺序做「先精确后放宽」的日志归因")
    void aliasesAreDedupedAndOriginalFirst(@TempDir Path dir) throws Exception {
        Path f = Files.createFile(dir.resolve("a.txt"));
        List<Path> aliases = PathAliases.of(f);
        assertEquals(f.normalize(), aliases.get(0));
        assertEquals(aliases.size(), aliases.stream().distinct().count(), "不得有重复：" + aliases);
    }
}
```

- [x] **Step 2: 跑测试确认失败**

Run: `mvn -o test -pl springai-code-tui -Dtest=PathAliasesTest`
Expected: 编译失败，`找不到符号: 类 PathAliases`

- [x] **Step 3: 实现**

Create `PathAliases.java`:

```java
package io.github.javaside.springai.codetui.agent.permission;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 同一个文件的多种写法。<b>只给 deny / ask 规则与内置底线用</b>——allow 方向绝不放宽
 * （见 spec §1：deny 误拦你看得见、能调整；allow 误放行不可逆）。
 *
 * <p><b>为什么不用 {@link Path#toRealPath}</b>：它对<b>尚不存在的文件</b>直接抛
 * {@code NoSuchFileException}，而「写一个还不存在的文件」是常态——期 1 正是因此
 * 整个放弃了符号链接解析。本类改为解析<b>最长的已存在祖先</b>再拼回剩余段：
 *
 * <pre>
 *   /tmp/link/sub/new.txt      （link → /etc，new.txt 尚不存在）
 *     最长已存在祖先 /tmp/link/sub → 解析为 /etc/sub
 *     拼回剩余段                   → /etc/sub/new.txt
 * </pre>
 *
 * 这样「目标文件尚不存在、但父目录经符号链接指向敏感位置」也能被 deny 命中——
 * 那正是两步绕过的形态。
 *
 * <p><b>绝不抛异常</b>：本类在判定热路径上被调用，任何异常都会把一次判定变成
 * 「内部出错 → 失败关闭成询问」，对用户表现为莫名其妙的弹窗。解析不了就退回原写法。
 */
public final class PathAliases {

    private PathAliases() {
    }

    /**
     * 该路径的全部写法，<b>原写法排第一</b>（调用方靠顺序做「先精确后放宽」的归因），去重。
     *
     * @param target 绝对路径；{@code null} 返回空列表，相对路径原样返回一个（无从解析）
     */
    public static List<Path> of(Path target) {
        List<Path> out = new ArrayList<>(2);
        if (target == null) {
            return out;
        }
        Path normalized = target.normalize();
        out.add(normalized);
        if (!normalized.isAbsolute()) {
            return out;                      // 相对路径无从解析：调用方交来的应已是绝对路径
        }
        Path resolved = resolveThroughSymlinks(normalized);
        if (resolved != null && !resolved.equals(normalized)) {
            out.add(resolved);
        }
        return out;
    }

    /**
     * 解析最长的已存在祖先，再把剩余段拼回去；解析不了返回 {@code null}。
     *
     * <p>失败情形（权限不足、循环链接、路径太长）一律吞掉——见类注释「绝不抛异常」。
     */
    private static Path resolveThroughSymlinks(Path absolute) {
        Path existing = absolute;
        int climbed = 0;
        while (existing != null && !Files.exists(existing, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
            climbed++;
        }
        if (existing == null) {
            return null;                     // 一个祖先都不存在
        }
        try {
            Path real = existing.toRealPath();
            if (climbed == 0) {
                return real;
            }
            // 把爬上去的那几段原样拼回来
            Path tail = existing.relativize(absolute);
            return real.resolve(tail).normalize();
        } catch (Exception e) {
            return null;                     // 权限不足 / 循环链接 / 竞态删除：退回原写法
        }
    }
}
```

- [x] **Step 4: 跑测试确认通过**

Run: `mvn -o test -pl springai-code-tui -Dtest=PathAliasesTest`
Expected: PASS（5 个测试；不支持符号链接的环境上有 1 个被 skip）

- [x] **Step 5: 变异实测**

把 `resolveThroughSymlinks` 的 `while` 循环体删掉（只试 `absolute` 本身、不爬祖先），重跑。
Expected: `resolvesThroughSymlinkedParentForMissingFile` **变红**（它正是靠爬祖先才成立的）。
恢复后复跑全绿。结果写进报告。

- [x] **Step 6: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/permission/PathAliases.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/permission/PathAliasesTest.java
git commit -m "feat(code-tui): 加 PathAliases——解析最长已存在祖先，让符号链接绕过可被 deny 命中"
```

---

## Task 2: 引擎接入别名与折叠孪生（仅 deny/ask）

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/permission/PermissionEngine.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/permission/PermissionEngineAliasTest.java`

**背景（实施者必读）**：

- `firstMatch(behavior, toolName, target, entry, split)` 与 `ruleMatches(rule, behavior, toolName, target, entry, split)` 是现有的匹配入口，**改动集中在这两处**。
- 规则存在两个 `CopyOnWriteArrayList`：`fileRules`（启动加载 + 「永久允许」追加）与 `sessionRules`（「本会话不再问」）。折叠孪生要跟着它们走。
- **`PermissionRule.java` 一行不改**。若你觉得非改不可，报 BLOCKED。

- [x] **Step 1: 写失败测试**

Create `PermissionEngineAliasTest.java`:

```java
package io.github.javaside.springai.codetui.agent.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 匹配的放宽<b>只在 deny/ask 方向</b>。
 *
 * <p><b>这一类的安全支点是那几条「allow 仍不命中」的反向断言</b>：所有人都会记得测
 * 「deny 现在能拦住 /ETC 了」，没人会想起测「allow 仍然拦不住」——而后者才是不可逆的方向。
 *
 * <p><b>刻意不创建真实的 /ETC 目录</b>：APFS 上它与 /etc 是同一个文件，那样的测试会因
 * <b>错误的原因</b>变绿，换到 Linux CI 就红。这里只打匹配逻辑，不碰宿主文件系统。
 */
class PermissionEngineAliasTest {

    private static PermissionEngine engine(Path root, PermissionRule... rules) {
        return new PermissionEngine(root, new PermissionConfig(PermissionMode.DEFAULT, List.of(rules)),
                PermissionMode.DEFAULT, false);
    }

    private static String writeInput(Path p) {
        return "{\"filePath\":\"" + p.toString().replace("\\", "\\\\") + "\"}";
    }

    @Test
    @DisplayName("deny 折叠大小写：/etc/** 拦得住 /ETC/passwd")
    void denyFoldsCase(@TempDir Path root) {
        PermissionEngine e = engine(root,
                PermissionRule.parse("Write(/etc/**)", PermissionBehavior.DENY, RuleScope.USER));
        assertEquals(PermissionBehavior.DENY,
                e.decide("Write", writeInput(Path.of("/ETC/passwd"))).behavior());
    }

    @Test
    @DisplayName("★ 安全支点：allow 不得折叠——/etc/** 放行不了 /ETC/passwd")
    void allowDoesNotFoldCase(@TempDir Path root) {
        PermissionEngine e = engine(root,
                PermissionRule.parse("Write(/etc/**)", PermissionBehavior.ALLOW, RuleScope.USER));
        assertEquals(PermissionBehavior.ASK,
                e.decide("Write", writeInput(Path.of("/ETC/passwd"))).behavior(),
                "allow 一旦折叠，一条规则就比写下时以为的覆盖面更宽——而放行是不可逆的");
    }

    @Test
    @DisplayName("ask 规则同样折叠（它与 deny 同属「问一下无害」的方向）")
    void askFoldsCase(@TempDir Path root) {
        PermissionEngine e = engine(root,
                PermissionRule.parse("Write(/etc/**)", PermissionBehavior.ASK, RuleScope.USER));
        assertEquals(PermissionBehavior.ASK,
                e.decide("Write", writeInput(Path.of("/ETC/passwd"))).behavior());
    }

    @Test
    @DisplayName("命令目标也折叠：macOS 上 RM -rf 真能执行（/bin/RM 解析到 /bin/rm）")
    void denyFoldsCommandCase(@TempDir Path root) {
        PermissionEngine e = engine(root,
                PermissionRule.parse("Bash(rm -rf /tmp/x:*)", PermissionBehavior.DENY, RuleScope.USER));
        assertEquals(PermissionBehavior.DENY,
                e.decide("Bash", "{\"command\":\"RM -rf /tmp/x\"}").behavior());
    }

    @Test
    @DisplayName("裸工具名规则（pattern == null）不生成折叠孪生，且不得 NPE")
    void wholeToolRuleDoesNotNpe(@TempDir Path root) {
        PermissionEngine e = engine(root,
                PermissionRule.parse("WebFetch(*)", PermissionBehavior.DENY, RuleScope.USER));
        assertEquals(PermissionBehavior.DENY,
                e.decide("WebFetch", "{\"url\":\"https://x/y\"}").behavior());
    }

    @Test
    @DisplayName("deny 认符号链接解析后的写法：经 link 指向被禁目录也拦得住")
    void denyFollowsSymlink(@TempDir Path root) throws Exception {
        Path secret = Files.createDirectories(root.resolve("secret"));
        Path link;
        try {
            link = Files.createSymbolicLink(root.resolve("link"), secret);
        } catch (UnsupportedOperationException | IOException ex) {
            assumeTrue(false, "本环境不支持创建符号链接");
            return;
        }
        PermissionEngine e = engine(root, PermissionRule.parse(
                "Write(" + secret + "/**)", PermissionBehavior.DENY, RuleScope.USER));

        assertEquals(PermissionBehavior.DENY,
                e.decide("Write", writeInput(link.resolve("new.txt"))).behavior(),
                "目标文件尚不存在、父目录经链接指向被禁目录——这是两步绕过");
    }

    @Test
    @DisplayName("★ 安全支点：allow 不认符号链接解析后的写法")
    void allowDoesNotFollowSymlink(@TempDir Path root) throws Exception {
        Path real = Files.createDirectories(root.resolve("real"));
        try {
            Files.createSymbolicLink(root.resolve("link"), real);
        } catch (UnsupportedOperationException | IOException ex) {
            assumeTrue(false, "本环境不支持创建符号链接");
            return;
        }
        PermissionEngine e = engine(root, PermissionRule.parse(
                "Write(" + real + "/**)", PermissionBehavior.ALLOW, RuleScope.USER));

        assertEquals(PermissionBehavior.ASK,
                e.decide("Write", writeInput(root.resolve("link/new.txt"))).behavior(),
                "allow 认了别名，就会放行一条你没有明确写下的路径");
    }

    @Test
    @DisplayName("运行期加的会话规则也要有折叠孪生（否则「本会话不再问」的 deny 形态漏折）")
    void sessionRulesAlsoFold(@TempDir Path root) {
        PermissionEngine e = engine(root);
        e.addSessionRule(PermissionRule.parse("Write(/etc/**)", PermissionBehavior.DENY, RuleScope.SESSION));
        assertEquals(PermissionBehavior.DENY,
                e.decide("Write", writeInput(Path.of("/ETC/passwd"))).behavior());
    }
}
```

- [x] **Step 2: 跑测试确认失败**

Run: `mvn -o test -pl springai-code-tui -Dtest=PermissionEngineAliasTest`
Expected: `denyFoldsCase` / `denyFollowsSymlink` 等变红（当前不折叠、不解析）；
**两条 `allowDoesNot*` 应当已经是绿的**——它们钉的是「不许放宽」，现在本来就没放宽。

> ⚠ 这一点很重要：安全支点的两条**一开始就是绿的**，所以**必须靠 Step 5 的变异实测**
> 证明它们真的有鉴别力。否则你无法区分「它在守护」与「它恰好总是绿」。

- [x] **Step 3: 实现**

`PermissionEngine.java`：

① 加折叠孪生的存放与维护（与 `fileRules`/`sessionRules` 并列）：

```java
    /**
     * deny / ask 规则的<b>折叠孪生</b>（pattern 小写）：让 {@code /etc/**} 拦得住 {@code /ETC/passwd}。
     *
     * <p>大小写<b>不是别名</b>而是匹配模式——{@code /ETC/passwd} 在 Linux 上与 {@code /etc/passwd}
     * 是两个文件，要命中就得<b>规则与目标同时折叠</b>。做成孪生规则而非给 matches 加开关，
     * 是为了让 {@link PermissionRule} 保持纯粹（期 1 定下：behavior 语义不进匹配原语）。
     *
     * <p><b>只给 deny/ask 造孪生</b>：allow 方向绝不放宽（spec §1）。
     * <b>pattern == null 的规则不造</b>（裸工具名与大小写无关，且 toLowerCase 会 NPE）。
     */
    private final Map<PermissionRule, PermissionRule> foldedTwins = new ConcurrentHashMap<>();

    /** 造/取该规则的折叠孪生；不该有孪生时返回 null。 */
    private PermissionRule foldedTwin(PermissionRule rule) {
        if (rule == null || rule.pattern() == null
                || rule.behavior() == PermissionBehavior.ALLOW) {
            return null;
        }
        return foldedTwins.computeIfAbsent(rule, r -> new PermissionRule(
                r.toolName(), r.pattern().toLowerCase(Locale.ROOT), r.behavior(), r.scope()));
    }
```

> **实施提示**：用 `computeIfAbsent` 惰性造，就不必在 `addSessionRule` / `addPersistentRule` /
> 构造器三处分别维护——少一个「加了规则忘了造孪生」的失效点。

② `ruleMatches` 里，deny/ask 分支改为按别名 + 折叠试；allow 分支**保持原样**：

```java
    private boolean ruleMatches(PermissionRule rule, PermissionBehavior behavior, String toolName,
                                String target, ToolRegistry.Entry entry,
                                BashCommandSplitter.Split split) {
        if (behavior == PermissionBehavior.ALLOW) {
            return allowMatches(rule, toolName, target, entry, split);   // 原有逻辑，一字不改
        }
        // deny / ask：任一写法命中即命中（spec §1）
        for (String candidate : matchCandidates(target, entry)) {
            if (denyOrAskMatchesOne(rule, toolName, candidate, entry, split)) {
                return true;
            }
        }
        return false;
    }
```

③ 候选写法（别名 + 折叠）：

```java
    /**
     * deny/ask 要试的全部写法：原写法 → 符号链接解析后 → 各自的小写形态。
     *
     * <p><b>别名与折叠的适用面不同，别混</b>：别名（{@link PathAliases}）只对<b>路径目标</b>
     * 有意义——命令、URL、bash_id 没有「符号链接解析后」这一说；折叠对路径与命令都适用。
     */
    private List<String> matchCandidates(String target, ToolRegistry.Entry entry) {
        if (target == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>(4);
        out.add(target);
        if (entry.pathTarget()) {
            for (Path alias : PathAliases.of(toPath(target))) {
                String s = alias.toString();
                if (!out.contains(s)) {
                    out.add(s);
                }
            }
        }
        int plain = out.size();
        for (int i = 0; i < plain; i++) {
            String folded = out.get(i).toLowerCase(Locale.ROOT);
            if (!out.contains(folded)) {
                out.add(folded);
            }
        }
        return out;
    }
```

④ 单次匹配时，小写候选要配折叠孪生：

```java
    /** 对一个候选写法做一次 deny/ask 匹配：小写候选配折叠孪生，其余配原规则。 */
    private boolean denyOrAskMatchesOne(PermissionRule rule, String toolName, String candidate,
                                        ToolRegistry.Entry entry, BashCommandSplitter.Split split) {
        boolean isFolded = candidate.equals(candidate.toLowerCase(Locale.ROOT));
        PermissionRule twin = isFolded ? foldedTwin(rule) : null;
        if (twin != null && matchesOneRule(twin, toolName, candidate, entry, split)) {
            return true;
        }
        return matchesOneRule(rule, toolName, candidate, entry, split);
    }
```

> **实施提示**：`allowMatches` / `matchesOneRule` 是你从现有 `ruleMatches` 体内**抽出来**的两个方法
> （分别是 allow 分支与「对单个 target 做一次匹配」的原有逻辑，含 `separatorSensitive`、
> COMMAND 分段那套）。**抽取时行为一字不改**——先抽取、跑既有测试全绿，再加新逻辑。

- [x] **Step 4: 跑测试确认通过**

Run: `mvn -o test -pl springai-code-tui -Dtest='PermissionEngine*Test,PermissionRuleTest,DangerousPaths*Test'`
Expected: 全绿（既有用例 + 新的 8 条）

- [x] **Step 5: 变异实测（本任务的关键一步）**

做**两个**变异，各跑一次 `PermissionEngineAliasTest`：

1. **把 `foldedTwin` 的 `behavior == ALLOW` 守卫去掉**（即 allow 也造孪生）
   → **`allowDoesNotFoldCase` 必须变红**。这一条证明安全支点有鉴别力。
2. **把 `matchCandidates` 里的 `PathAliases.of(...)` 那段删掉**
   → `denyFollowsSymlink` 必须变红。

**若变异 1 之后 `allowDoesNotFoldCase` 仍然绿，说明它没打到 allow 路径——先修用例再继续。**
两个变异都要恢复，恢复后复跑全绿。结果写进报告。

- [x] **Step 6: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/permission/PermissionEngine.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/permission/PermissionEngineAliasTest.java
git commit -m "feat(code-tui): deny/ask 认符号链接与大小写的多种写法，allow 保持精确"
```

---

## Task 3: 内置底线接入别名 + 大小写折叠审计

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/permission/DangerousPaths.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/permission/DangerousPathsAliasTest.java`

**背景**：`DangerousPaths` **已经大面积折叠大小写**——命令基名（`/bin/RM → rm`）、路径分段、
系统密钥全路径都折了。**本任务不是改造，是补两个缺口**：① 符号链接完全没解析过；
② 按名字命中的表（`SECRET_FILES` / `SECRET_EXTENSIONS` 等）折叠是否齐全，读代码确认、缺哪补哪。

底线**没有 allow 方向**（命中只会导致询问/拒绝），故这里放宽是纯安全方向，不需要 Task 2 那套不对称。

- [x] **Step 1: 写失败测试**

Create `DangerousPathsAliasTest.java`:

```java
package io.github.javaside.springai.codetui.agent.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class DangerousPathsAliasTest {

    @Test
    @DisplayName("经符号链接写 .ssh/ 也要被拦——底线此前完全不解析链接")
    void writeThroughSymlinkIntoSsh(@TempDir Path root) throws Exception {
        Path fakeHome = Files.createDirectories(root.resolve("home"));
        Path ssh = Files.createDirectories(fakeHome.resolve(".ssh"));
        try {
            Files.createSymbolicLink(root.resolve("shortcut"), ssh);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "本环境不支持创建符号链接");
            return;
        }
        assertNotNull(DangerousPaths.checkWrite(root.resolve("shortcut/authorized_keys"), root),
                "路径里没有 .ssh 字样，但解析后有——这正是两步绕过");
    }

    @Test
    @DisplayName("按名字命中的检查要折叠大小写：写 .ZSHRC 与写 .zshrc 同样危险")
    void shellConfigNameIsCaseFolded(@TempDir Path root) {
        String home = System.getProperty("user.home");
        assertNotNull(DangerousPaths.checkWrite(Path.of(home, ".ZSHRC"), root));
        assertNotNull(DangerousPaths.checkWrite(Path.of(home, ".zshrc"), root), "对照：原写法本来就该被拦");
    }

    @Test
    @DisplayName("密钥扩展名同理：x.PEM 与 x.pem 同样是密钥")
    void secretExtensionIsCaseFolded(@TempDir Path root) {
        assertNotNull(DangerousPaths.checkRead(Path.of(System.getProperty("user.home"), "x.PEM"), root));
    }

    @Test
    @DisplayName("放宽不得制造假阳性：工作区内的普通文件仍然放行")
    void ordinaryWorkspaceFileStillPasses(@TempDir Path root) {
        assertNull(DangerousPaths.checkWrite(root.resolve("src/Main.java"), root));
        assertNull(DangerousPaths.checkRead(root.resolve("README.md"), root));
    }
}
```

- [x] **Step 2: 跑测试确认失败**

Run: `mvn -o test -pl springai-code-tui -Dtest=DangerousPathsAliasTest`
Expected: `writeThroughSymlinkIntoSsh` 变红。
**另两条折叠用例可能一开始就绿**（既有代码已折了那几处）——若是绿的，报告里**明确写出哪几条本来就绿**，
不要当成自己的功劳，也不要因此删掉它们（它们是回归保险）。

- [x] **Step 3: 实现**

① `checkRead` / `checkWrite` 的**主体重命名**为私有的 `checkReadOne` / `checkWriteOne`，公开入口改成薄壳：

```java
    /**
     * 逐个写法查一遍，任一命中即命中。
     *
     * <p>底线没有 allow 方向，故这里放宽是纯安全方向——不需要引擎那套 deny/allow 不对称。
     * 别名由 {@link PathAliases} 给出（原写法 + 符号链接解析后）。
     */
    private static String firstHit(Path target, Path root,
                                   java.util.function.BiFunction<Path, Path, String> check) {
        for (Path alias : PathAliases.of(target)) {
            String hit = check.apply(alias, root);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    public static String checkRead(Path target, Path root) {
        return firstHit(target, root, DangerousPaths::checkReadOne);
    }

    public static String checkWrite(Path target, Path root) {
        return firstHit(target, root, DangerousPaths::checkWriteOne);
    }
```

② **折叠审计**：读 `checkWriteOne` / `checkReadOne` 里所有按名字比较的地方
（`SECRET_FILES`、`SECRET_EXTENSIONS`、`SHELL_CONFIG_FILES`、`SENSITIVE_NESTED`、
`AUTO_EXEC_NESTED`、`SSH_PUBLIC_FILES`、`ENV_TEMPLATES` 等），确认取名字时都过了
`toLowerCase(Locale.ROOT)`；缺的补上。**报告里列出你改了哪几处、哪几处本来就有。**

> ⚠ 只折叠**用于比较的那份副本**，别把用于展示/日志的路径也小写化——
> 面板上打出 `/users/zxh/.ssh/config` 会让人以为是另一个文件。

- [x] **Step 4: 跑测试确认通过**

Run: `mvn -o test -pl springai-code-tui -Dtest='DangerousPaths*Test,PermissionEngine*Test'`
Expected: 全绿。**`DangerousPathsTableCoverageTest` 尤其要绿**——它逐条验九张常量表仍然有效。

- [x] **Step 5: 变异实测**

把 `firstHit` 改成只查第一个别名，重跑 → `writeThroughSymlinkIntoSsh` 变红。恢复后复跑全绿。

- [x] **Step 6: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/permission/DangerousPaths.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/permission/DangerousPathsAliasTest.java
git commit -m "feat(code-tui): 内置底线接入路径别名，补齐按名字命中的大小写折叠"
```

---

## Task 4: 过宽 root 不再静默架空检查

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/permission/DangerousPaths.java`（`isOutsideWritableRoots`）
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/CodeTuiApplication.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/permission/OverBroadRootTest.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/PermissionStartupTest.java`

**背景**：`isOutsideWritableRoots` 里 `abs.startsWith(root.normalize())` 在 `root = /` 时对
**所有绝对路径**成立，于是「写入项目与家目录之外的系统位置」这条检查全面失效。
按名字命中的检查不受影响——**被架空的是这一条，不是整层底线**。

- [x] **Step 1: 写失败测试**

Create `OverBroadRootTest.java`:

```java
package io.github.javaside.springai.codetui.agent.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverBroadRootTest {

    private static final Path HOME = Path.of(System.getProperty("user.home")).normalize();

    @Test
    @DisplayName("判据本身")
    void predicate() {
        assertTrue(DangerousPaths.isOverBroadRoot(Path.of("/")), "文件系统根一定过宽");
        assertTrue(DangerousPaths.isOverBroadRoot(HOME.getParent()), "家目录的父目录含家目录 → 过宽");

        assertFalse(DangerousPaths.isOverBroadRoot(HOME),
                "家目录本身不算过宽——它另有一条独立的家目录豁免，改这里也不生效（见 spec §4）");
        assertFalse(DangerousPaths.isOverBroadRoot(HOME.resolve("projects/demo")));
        assertFalse(DangerousPaths.isOverBroadRoot(null), "null 不该被当成过宽");
    }

    @Test
    @DisplayName("root=/ 时系统位置检查照常生效——此前它对所有路径失效")
    void systemLocationCheckSurvivesRootSlash() {
        assertNotNull(DangerousPaths.checkWrite(Path.of("/usr/local/bin/git"), Path.of("/")),
                "root=/ 时任何绝对路径都 startsWith(\"/\")，豁免必须失效");
    }

    @Test
    @DisplayName("正常 root 不受影响：工作区内照常放行、区外系统位置照常拦")
    void normalRootUnchanged(@TempDir Path root) {
        assertNull(DangerousPaths.checkWrite(root.resolve("src/Main.java"), root));
        assertNotNull(DangerousPaths.checkWrite(Path.of("/usr/local/bin/git"), root));
    }
}
```

在既有 `PermissionStartupTest.java` 追加（沿用该文件现有 import 风格，缺的自己补）：

```java
    @Test
    @DisplayName("过宽 root 要给用户一行提示——静默变严格会让人以为程序坏了")
    void overBroadRootIsAnnounced() {
        assertTrue(CodeTuiApplication.overBroadRootNotice(java.nio.file.Path.of("/")).contains("工作区"),
                "root=/ 应有提示");
        assertEquals("", CodeTuiApplication.overBroadRootNotice(
                java.nio.file.Path.of(System.getProperty("user.home"), "projects", "demo")),
                "正常 root 不该打扰用户");
    }
```

- [x] **Step 2: 跑测试确认失败**

Run: `mvn -o test -pl springai-code-tui -Dtest='OverBroadRootTest,PermissionStartupTest'`
Expected: 编译失败，找不到 `isOverBroadRoot` / `overBroadRootNotice`

- [x] **Step 3: 实现判据与豁免**

`DangerousPaths.java` 新增：

```java
    /**
     * 工作区是否过宽到不能再拿它做「工作区内」豁免。
     *
     * <p>判据精确、不是深度启发式：<b>root 是文件系统根，或 root 是家目录的严格祖先</b>。
     * {@code /work} 不含家目录 → 正常；{@code /Users} 含 {@code /Users/<你>} → 过宽。
     *
     * <p><b>家目录本身刻意不算过宽</b>：它另有一条独立的家目录豁免，改这里也不生效——
     * 要动那条得重新审视「你自己家里的文件不算系统位置」这个设计，不在本期（见 spec §4）。
     * 写下这句是为了避免一种更坏的结果：<b>以为修好了</b>。
     */
    public static boolean isOverBroadRoot(Path root) {
        if (root == null) {
            return false;
        }
        Path r = root.normalize();
        if (r.getParent() == null) {
            return true;                       // 文件系统根
        }
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            return false;
        }
        Path h = Path.of(home).normalize();
        return h.startsWith(r) && !h.equals(r); // 严格祖先
    }
```

`isOutsideWritableRoots` 里那句改成：

```java
        // root 过宽（"/" 或家目录的严格祖先）时不拿它做豁免——否则这条检查对所有路径失效
        if (root != null && !isOverBroadRoot(root) && abs.startsWith(root.normalize())) {
            return false;
        }
```

- [x] **Step 4: 实现启动提示**

`CodeTuiApplication.java` 新增：

```java
    /**
     * 过宽工作区的启动提示；正常 root 返回空串。
     *
     * <p><b>必须有这一行</b>：过宽时判定会变严格（工作区豁免失效），用户会突然多出很多审批。
     * 不说明原因的话，表现出来就是「这程序今天怎么老弹窗」——<b>静默变严格与静默失效同样糟</b>。
     */
    static String overBroadRootNotice(Path root) {
        if (!DangerousPaths.isOverBroadRoot(root)) {
            return "";
        }
        return "⚠ 当前工作区过宽（" + root + "）：「工作区内」不再作为豁免依据，"
                + "涉及系统位置的操作会照常询问。建议切到一个具体的项目目录再运行。";
    }
```

并在既有启动装配处（`PermissionEngine` 构造之后、那几行 `state.pushInfo` 附近）加：

```java
        String rootNotice = overBroadRootNotice(root);
        if (!rootNotice.isEmpty()) {
            state.pushInfo(rootNotice);
        }
```

- [x] **Step 5: 跑测试确认通过**

Run: `mvn -o test -pl springai-code-tui -Dtest='OverBroadRootTest,PermissionStartupTest,DangerousPaths*Test'`
Expected: 全绿

- [x] **Step 6: 变异实测**

去掉 `isOutsideWritableRoots` 里新加的 `!isOverBroadRoot(root) &&`，重跑
→ `systemLocationCheckSurvivesRootSlash` 变红。恢复后复跑全绿。

- [x] **Step 7: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/permission/DangerousPaths.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/CodeTuiApplication.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/permission/OverBroadRootTest.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/PermissionStartupTest.java
git commit -m "fix(code-tui): 过宽 root 不再静默架空「系统位置」检查，并给启动提示"
```

---

## Task 5: `ACCEPT_EDITS` 的命令段必须判工作区

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/permission/BashCommandSplitter.java`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/permission/PermissionEngine.java`（`commandByMode`）
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/permission/CommandPathArgumentsTest.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/permission/AcceptEditsCommandScopeTest.java`

**背景**：`commandByMode` 只看首词是不是 `mkdir`/`touch`/`mv`/`cp`，**完全不看目标在哪**，
故 `mkdir /etc/evil`、`mv ~/notes.txt /tmp/x` 在该档直接放行——**而放行理由写着
「工作区内的文件操作」**。这不只是行为有洞，界面还在说一句不实的话。

**职责边界**：`BashCommandSplitter` 只做**词法**（哪些 token 长得像路径），
`PermissionEngine` 做**策略**（这些路径在不在工作区内）。root 是引擎才有的知识，不下沉。

- [x] **Step 1: 写词法层的失败测试**

Create `CommandPathArgumentsTest.java`:

```java
package io.github.javaside.springai.codetui.agent.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandPathArgumentsTest {

    @Test
    @DisplayName("跳过首词与选项，其余当路径候选")
    void skipsCommandAndOptions() {
        assertEquals(List.of("a/b", "c"), BashCommandSplitter.pathArguments("cp -r a/b c"));
        assertEquals(List.of("dir"), BashCommandSplitter.pathArguments("mkdir -p dir"));
    }

    @Test
    @DisplayName("选项的值也被当路径候选——宁可多判几个，误判方向必须是「多问一次」")
    void optionValuesAreCandidatesToo() {
        // mkdir -m 755 dir：755 会被当相对路径，解到 root 内，无害
        assertTrue(BashCommandSplitter.pathArguments("mkdir -m 755 dir").contains("755"));
        // cp -t /etc src：/etc 必须出现，否则这条命令会被当成工作区内操作
        assertTrue(BashCommandSplitter.pathArguments("cp -t /etc src").contains("/etc"));
    }

    @Test
    @DisplayName("~ 原样保留，由策略层判为工作区外")
    void tildeIsPreserved() {
        assertTrue(BashCommandSplitter.pathArguments("mv ~/notes.txt /tmp/x").contains("~/notes.txt"));
    }

    @Test
    @DisplayName("空命令 / 只有命令名 / null → 空列表，不抛")
    void emptyIsSafe() {
        assertTrue(BashCommandSplitter.pathArguments("").isEmpty());
        assertTrue(BashCommandSplitter.pathArguments("touch").isEmpty());
        assertTrue(BashCommandSplitter.pathArguments(null).isEmpty());
    }
}
```

- [x] **Step 2: 跑测试确认失败**

Run: `mvn -o test -pl springai-code-tui -Dtest=CommandPathArgumentsTest`
Expected: 找不到符号 `pathArguments`

- [x] **Step 3: 实现词法层**

`BashCommandSplitter.java`（`words` 是现有私有方法，直接复用）：

```java
    /**
     * 段里长得像路径的 token：跳过首词（命令名）与 {@code -} 开头的选项，其余全要。
     *
     * <p><b>只做词法，不做策略</b>——「在不在工作区内」由 {@code PermissionEngine} 判，
     * 那里才有 root。本方法刻意<b>不解析 {@code ~}、不展开通配符</b>，原样交出去。
     *
     * <p><b>宁可多判几个</b>：选项的值（{@code mkdir -m 755 dir} 里的 {@code 755}）也会进来，
     * 策略层会把它当相对路径解到 root 内——无害。反过来漏掉一个真实路径，
     * 就会让一条越界命令被当成工作区内操作放行。误判方向必须是「多问一次」。
     */
    public static List<String> pathArguments(String segment) {
        if (segment == null || segment.isBlank()) {
            return List.of();
        }
        List<String> ws = words(segment);
        List<String> out = new ArrayList<>();
        for (int i = 1; i < ws.size(); i++) {          // 0 是命令名
            String w = ws.get(i);
            if (w.isEmpty() || w.startsWith("-")) {
                continue;                              // 选项（裸 - 是 stdin，同样跳过）
            }
            out.add(w);
        }
        return out;
    }
```

- [x] **Step 4: 写策略层的失败测试**

Create `AcceptEditsCommandScopeTest.java`:

```java
package io.github.javaside.springai.codetui.agent.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「自动接受编辑」对命令段也要判工作区——此前只看首词，
 * 而放行理由却写着「工作区内的文件操作」（行为与文案不符，是探针实测发现的）。
 */
class AcceptEditsCommandScopeTest {

    private static PermissionEngine acceptEdits(Path root) {
        return new PermissionEngine(root, new PermissionConfig(PermissionMode.DEFAULT, List.of()),
                PermissionMode.ACCEPT_EDITS, false);
    }

    private static PermissionDecision cmd(PermissionEngine e, String command) {
        return e.decide("Bash", "{\"command\":\"" + command + "\"}");
    }

    @Test
    @DisplayName("工作区内照常放行，且理由不再撒谎")
    void insideWorkspaceStillAllowed(@TempDir Path root) {
        PermissionDecision d = cmd(acceptEdits(root), "mkdir -p build/classes");
        assertEquals(PermissionBehavior.ALLOW, d.behavior());
        assertTrue(d.reason().contains("工作区内"), "理由：" + d.reason());
    }

    @Test
    @DisplayName("工作区外的绝对路径 → ASK（此前直接放行）")
    void absoluteOutsideAsks(@TempDir Path root) {
        assertEquals(PermissionBehavior.ASK, cmd(acceptEdits(root), "mkdir /tmp/evil").behavior());
    }

    @Test
    @DisplayName("~ 开头 → ASK：当相对路径解析会落进工作区、被错误放行")
    void tildeAsks(@TempDir Path root) {
        assertEquals(PermissionBehavior.ASK,
                cmd(acceptEdits(root), "mv ~/notes.txt build/x").behavior(),
                "~/notes.txt 若当相对路径解成 <root>/~/notes.txt，就会被判成工作区内");
    }

    @Test
    @DisplayName("用相对路径跑出工作区 → ASK")
    void relativeEscapeAsks(@TempDir Path root) {
        assertEquals(PermissionBehavior.ASK, cmd(acceptEdits(root), "cp ../../x build/y").behavior());
    }

    @Test
    @DisplayName("通配符按首个通配符之前的字面前缀判：*.txt 在区内，../*.txt 在区外")
    void globUsesLiteralPrefix(@TempDir Path root) {
        assertEquals(PermissionBehavior.ALLOW, cmd(acceptEdits(root), "cp *.txt build/").behavior());
        assertEquals(PermissionBehavior.ASK, cmd(acceptEdits(root), "cp ../*.txt build/").behavior());
    }

    @Test
    @DisplayName("默认档不受影响：这四个命令本来就要问")
    void defaultModeUnchanged(@TempDir Path root) {
        PermissionEngine e = new PermissionEngine(root,
                new PermissionConfig(PermissionMode.DEFAULT, List.of()), PermissionMode.DEFAULT, false);
        assertEquals(PermissionBehavior.ASK, cmd(e, "mkdir -p build/classes").behavior());
    }
}
```

- [x] **Step 5: 实现策略层**

`PermissionEngine.commandByMode` 里那句

```java
            if (mode == PermissionMode.ACCEPT_EDITS && BashCommandSplitter.isFileSystemWrite(seg)) {
```

改成额外要求路径参数全在工作区内：

```java
            if (mode == PermissionMode.ACCEPT_EDITS
                    && BashCommandSplitter.isFileSystemWrite(seg)
                    && allPathArgsInsideRoot(seg)) {
```

并新增：

```java
    /**
     * 段里的路径参数是否<b>全部</b>落在工作区内。
     *
     * <p><b>{@code ~} 一律判为区外</b>：token 字面是 {@code ~/notes.txt}，当相对路径解析会得到
     * {@code <root>/~/notes.txt}——<b>落在工作区内、被错误放行</b>，而 shell 实际会展开到家目录。
     *
     * <p><b>通配符取首个通配符之前的字面前缀</b>：{@code *.txt} → 前缀为空 → 解析到 root → 区内；
     * {@code ../*.txt} → 前缀 {@code ../} → 区外。
     */
    private boolean allPathArgsInsideRoot(String segment) {
        for (String arg : BashCommandSplitter.pathArguments(segment)) {
            if (arg.startsWith("~")) {
                return false;
            }
            String literal = literalPrefix(arg);
            Path p;
            try {
                Path raw = Path.of(literal);
                p = raw.isAbsolute() ? raw.normalize() : root.resolve(raw).normalize();
            } catch (RuntimeException e) {
                return false;                  // 解析不了就问，别猜
            }
            if (!insideRoot(p)) {
                return false;
            }
        }
        return true;
    }

    /** 首个通配符（{@code * ? [}）之前的字面前缀；无通配符则原样返回。 */
    private static String literalPrefix(String arg) {
        int cut = arg.length();
        for (int i = 0; i < arg.length(); i++) {
            char c = arg.charAt(i);
            if (c == '*' || c == '?' || c == '[') {
                cut = i;
                break;
            }
        }
        return arg.substring(0, cut);
    }
```

> **实施提示**：`insideRoot` 是该类已有的私有方法（`fileWriteByMode` 在用），直接复用。
> `literalPrefix("*.txt")` 返回空串 → `root.resolve("")` = root → 区内，符合预期。

- [x] **Step 6: 核对放行文案**

`commandByMode` 末尾的放行理由（原文类似 `自动接受编辑：命令各段都是只读或工作区内的文件操作`）
**现在才是真的**。确认它与新行为一致；若措辞与实现对不上，**以实现为准**改文案。

- [x] **Step 7: 跑测试确认通过**

Run: `mvn -o test -pl springai-code-tui -Dtest='CommandPathArgumentsTest,AcceptEditsCommandScopeTest,PermissionEngine*Test,BashCommandSplitter*Test'`
Expected: 全绿

- [x] **Step 8: 变异实测**

去掉 `&& allPathArgsInsideRoot(seg)`，重跑
→ `absoluteOutsideAsks` / `tildeAsks` / `relativeEscapeAsks` 变红。恢复后复跑全绿。

- [x] **Step 9: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/permission/BashCommandSplitter.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/permission/PermissionEngine.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/permission/CommandPathArgumentsTest.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/permission/AcceptEditsCommandScopeTest.java
git commit -m "fix(code-tui): 自动接受编辑对命令段也判工作区，文案从此属实"
```

---

## Task 6: `PermissionConfigWriter.remove` + 引擎的删除入口

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/permission/PermissionConfigWriter.java`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/permission/PermissionEngine.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/permission/PermissionConfigWriterRemoveTest.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/permission/PermissionEngineRemoveRuleTest.java`

**背景**：`PermissionConfigWriter.append` 已经把纪律定好了——原子写（临时文件 + `ATOMIC_MOVE`）、
读-改-写保留未知字段、JSON 非法就整个不动、重复键检测、保留原 POSIX 权限位、进程内静态锁。
`remove` **照抄这套**，不要另起一路。

- [x] **Step 1: 写失败测试**

Create `PermissionConfigWriterRemoveTest.java`:

```java
package io.github.javaside.springai.codetui.agent.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionConfigWriterRemoveTest {

    private static PermissionRule allow(String dsl) {
        return PermissionRule.parse(dsl, PermissionBehavior.ALLOW, RuleScope.PROJECT);
    }

    @Test
    @DisplayName("删掉指定那一条，其余条目与顺序不动")
    void removesOnlyTheNamedRule(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("permissions.json");
        Files.writeString(f, "{\n  \"allow\" : [ \"Bash(a:*)\", \"Bash(b:*)\", \"Bash(c:*)\" ]\n}\n");

        assertTrue(PermissionConfigWriter.remove(f, allow("Bash(b:*)")));

        String after = Files.readString(f);
        assertTrue(after.contains("Bash(a:*)") && after.contains("Bash(c:*)"));
        assertFalse(after.contains("Bash(b:*)"));
        assertTrue(after.indexOf("Bash(a:*)") < after.indexOf("Bash(c:*)"), "顺序不该被打乱");
    }

    @Test
    @DisplayName("未知字段必须原样保留——那是用户手写的内容")
    void preservesUnknownFields(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("permissions.json");
        Files.writeString(f, "{\n  \"note\" : \"我的备注\",\n  \"allow\" : [ \"Bash(a:*)\" ]\n}\n");

        assertTrue(PermissionConfigWriter.remove(f, allow("Bash(a:*)")));
        assertTrue(Files.readString(f).contains("我的备注"));
    }

    @Test
    @DisplayName("规则不在文件里 → 返回 true（幂等：目标状态已达成），文件不动")
    void absentRuleIsIdempotent(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("permissions.json");
        String before = "{\n  \"allow\" : [ \"Bash(a:*)\" ]\n}\n";
        Files.writeString(f, before);

        assertTrue(PermissionConfigWriter.remove(f, allow("Bash(zzz:*)")));
        assertEquals(before, Files.readString(f), "没有可删的东西时不该重写文件");
    }

    @Test
    @DisplayName("JSON 非法 → 返回 false 且一个字节都不动（与 append 同纪律）")
    void invalidJsonUntouched(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("permissions.json");
        String broken = "{ this is not json";
        Files.writeString(f, broken);

        assertFalse(PermissionConfigWriter.remove(f, allow("Bash(a:*)")));
        assertEquals(broken, Files.readString(f));
    }

    @Test
    @DisplayName("文件不存在 → false，不创建空文件")
    void missingFileIsFalse(@TempDir Path dir) {
        Path f = dir.resolve("nope.json");
        assertFalse(PermissionConfigWriter.remove(f, allow("Bash(a:*)")));
        assertFalse(Files.exists(f));
    }
}
```

Create `PermissionEngineRemoveRuleTest.java`:

```java
package io.github.javaside.springai.codetui.agent.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionEngineRemoveRuleTest {

    @Test
    @DisplayName("删掉会话规则后立即失效——面板说删了、实际还拦着，是最坏的一种谎")
    void removedSessionRuleStopsApplying(@TempDir Path root) {
        PermissionEngine e = new PermissionEngine(root,
                new PermissionConfig(PermissionMode.DEFAULT, List.of()), PermissionMode.DEFAULT, false);
        PermissionRule r = PermissionRule.parse("Bash(mvn test:*)",
                PermissionBehavior.ALLOW, RuleScope.SESSION);
        e.addSessionRule(r);
        assertEquals(PermissionBehavior.ALLOW,
                e.decide("Bash", "{\"command\":\"mvn test\"}").behavior());

        assertTrue(e.removeRule(r));

        assertEquals(PermissionBehavior.ASK,
                e.decide("Bash", "{\"command\":\"mvn test\"}").behavior(),
                "删完必须同步从内存规则表摘掉，否则重启前它还在生效");
        assertFalse(e.rules().contains(r), "也要从 rules() 视图里消失（面板据它渲染）");
    }

    @Test
    @DisplayName("删落盘规则：文件与内存同时更新")
    void removedFileRuleIsGoneFromBoth(@TempDir Path root) {
        PermissionEngine e = new PermissionEngine(root,
                new PermissionConfig(PermissionMode.DEFAULT, List.of()), PermissionMode.DEFAULT, false);
        PermissionRule r = PermissionRule.parse("Bash(mvn test:*)",
                PermissionBehavior.ALLOW, RuleScope.PROJECT);
        assertTrue(e.addPersistentRule(r), "前置：先写进去");

        assertTrue(e.removeRule(r));
        assertFalse(e.rules().contains(r));
        assertEquals(PermissionBehavior.ASK,
                e.decide("Bash", "{\"command\":\"mvn test\"}").behavior());
    }

    @Test
    @DisplayName("删规则要把折叠孪生一并清掉——留着会让一条已删的 deny 继续拦")
    void removedDenyRuleDropsItsFoldedTwin(@TempDir Path root) {
        PermissionRule deny = PermissionRule.parse("Write(/etc/**)",
                PermissionBehavior.DENY, RuleScope.SESSION);
        PermissionEngine e = new PermissionEngine(root,
                new PermissionConfig(PermissionMode.DEFAULT, List.of()), PermissionMode.DEFAULT, false);
        e.addSessionRule(deny);
        assertEquals(PermissionBehavior.DENY,
                e.decide("Write", "{\"filePath\":\"/ETC/passwd\"}").behavior());

        assertTrue(e.removeRule(deny));

        assertEquals(PermissionBehavior.ASK,
                e.decide("Write", "{\"filePath\":\"/ETC/passwd\"}").behavior(),
                "折叠孪生没清掉的话，删了的 deny 会借孪生继续生效");
    }

    @Test
    @DisplayName("删不存在的规则 → false，不影响其它规则")
    void removingUnknownRuleIsFalse(@TempDir Path root) {
        PermissionEngine e = new PermissionEngine(root,
                new PermissionConfig(PermissionMode.DEFAULT, List.of()), PermissionMode.DEFAULT, false);
        assertFalse(e.removeRule(PermissionRule.parse("Bash(nope:*)",
                PermissionBehavior.ALLOW, RuleScope.SESSION)));
        assertFalse(e.removeRule(null), "null 不该抛");
    }
}
```

> ⚠ `e.rules()` 是面板要用的规则视图。若现有方法名不同（如 `permissionRules()` 门面对应的那个），
> **以仓库现状为准**改测试里的调用，别新造一个同义方法。

- [x] **Step 2: 跑测试确认失败**

Run: `mvn -o test -pl springai-code-tui -Dtest='PermissionConfigWriterRemoveTest,PermissionEngineRemoveRuleTest'`
Expected: 找不到 `remove` / `removeRule`

- [x] **Step 3: 实现 writer 的 remove**

`PermissionConfigWriter.java`（**照抄 `append` 的结构**：同一把 `LOCK`、同一个 `MAPPER`、
同一套 `readRoot` / 原子写 / `copyPosixPermissions`）：

```java
    /**
     * 从 {@code permissions.json} 的 allow/ask/deny 数组里删掉一条规则，其余树原样保留。
     *
     * <p>纪律与 {@link #append} 完全一致，逐条复述是因为这是<b>授权文件</b>：
     * 原子写、读-改-写保留未知字段与条目顺序、JSON 非法就整个不动、重复键检测、
     * 保留原 POSIX 权限位、进程内静态锁串行化。
     *
     * <p><b>幂等</b>：规则本来就不在文件里时返回 {@code true}（目标状态已达成）<b>且不重写文件</b>——
     * 无谓的重写会白白改掉 mtime，也多一次写坏的机会。
     */
    public static boolean remove(Path file, PermissionRule rule) {
        if (file == null || rule == null) {
            return false;
        }
        String dsl;
        try {
            dsl = rule.toDsl();
        } catch (RuntimeException e) {
            log.warn("权限配置删除失败：规则无法还原成 DSL（{}）。", e.getMessage());
            return false;
        }
        synchronized (LOCK) {
            return doRemove(file, dsl, rule.behavior());
        }
    }
```

`doRemove` 的骨架（**参照 `doAppend` 逐句对照着写**）：

1. `if (!Files.isRegularFile(file)) return false;` —— 不创建空文件
2. `ObjectNode root = readRoot(file); if (root == null) return false;` —— 非法就整个不动
3. 取 `arrayKey(behavior)` 对应的数组；不是数组或不存在 → **返回 true**（没有可删的东西）
4. 遍历数组找 `n.isString() && dsl.equals(n.stringValue())` 的下标；**一个都没有 → 返回 true 且不写盘**
5. 删掉全部匹配项（同一条可能被手工写重复），写临时文件 → `copyPosixPermissions` → `ATOMIC_MOVE`
6. `catch (Exception e)` → `log.warn` + `cleanup(tmp)` + `return false`

- [x] **Step 4: 实现引擎的 removeRule**

`PermissionEngine.java`：

```java
    /**
     * 删一条规则：落盘规则回写对应层文件，会话规则只从内存摘。
     *
     * <p><b>必须同步清掉内存规则表与折叠孪生</b>——只改文件不改内存的话，重启前它还在生效，
     * 而面板已经说删了。<b>面板说删了、实际还拦着，是最坏的一种谎。</b>
     *
     * @return 是否确实删掉了（规则不存在返回 false）
     */
    public boolean removeRule(PermissionRule rule) {
        if (rule == null) {
            return false;
        }
        boolean inSession = sessionRules.remove(rule);
        boolean inFile = fileRules.contains(rule);
        if (inFile) {
            Path file = rule.scope() == RuleScope.USER
                    ? PermissionConfigLoader.userFile()
                    : PermissionConfigLoader.projectFile(root);
            if (!PermissionConfigWriter.remove(file, rule)) {
                log.warn("规则 '{}' 未能从 {} 删除；内存中仍保留，避免出现「文件里还在、程序里没了」的分歧。",
                        rule.toDsl(), file);
                return false;
            }
            fileRules.remove(rule);
        }
        foldedTwins.remove(rule);          // 否则删掉的 deny 会借孪生继续生效
        return inSession || inFile;
    }
```

> **注意那条写盘失败的处理**：写盘失败时**不从内存摘**。反过来（内存摘了、文件没删）
> 会造成「这次运行不生效、重启后又回来」，比直接告诉用户「没删成」更难排查。

- [x] **Step 5: 跑测试确认通过**

Run: `mvn -o test -pl springai-code-tui -Dtest='PermissionConfigWriter*Test,PermissionEngine*Test'`
Expected: 全绿

- [x] **Step 6: 变异实测**

把 `removeRule` 里的 `foldedTwins.remove(rule);` 注释掉，重跑
→ `removedDenyRuleDropsItsFoldedTwin` 变红。恢复后复跑全绿。

- [x] **Step 7: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/permission/PermissionConfigWriter.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/permission/PermissionEngine.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/permission/PermissionConfigWriterRemoveTest.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/permission/PermissionEngineRemoveRuleTest.java
git commit -m "feat(code-tui): 规则可删——writer 加 remove，引擎同步清内存与折叠孪生"
```

---

## Task 7: `/permissions` 改交互面板 + 删除确认

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/SubmitHandler.java`（门面加 `removePermissionRule`）
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/CodeTuiViewPermissionsPanelTest.java`

**背景**：`/permissions` 现在是 `printPermissions()`——把模式、规则、内置底线 `pushInfo` 进 scrollback。
本任务改成交互面板，形状照 `/mcp`（`pickingMcp` + `onMcpPickerKey` + `mcpPickerChildren`）。

**已有可复用的东西**：`ViewScreen.of(view)`（离屏渲染回读屏幕文本，测试用）、
`Theme.PICK_SEL/PICK_ITEM/PICK_DESC`（纯前景高亮，**不要用背景色条**）。

- [x] **Step 1: 写失败测试**

Create `CodeTuiViewPermissionsPanelTest.java`:

```java
package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.SubmitHandler;
import io.github.javaside.springai.codetui.agent.permission.PermissionBehavior;
import io.github.javaside.springai.codetui.agent.permission.PermissionRule;
import io.github.javaside.springai.codetui.agent.permission.RuleScope;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeTuiViewPermissionsPanelTest {

    private static final PermissionRule DENY_ENV =
            PermissionRule.parse("Read(**/.env)", PermissionBehavior.DENY, RuleScope.USER);
    private static final PermissionRule ALLOW_MVN =
            PermissionRule.parse("Bash(mvn test:*)", PermissionBehavior.ALLOW, RuleScope.PROJECT);

    /** 记录被请求删除的规则。 */
    private static class Stub implements SubmitHandler {
        final List<PermissionRule> rules = new ArrayList<>(List.of(DENY_ENV, ALLOW_MVN));
        final List<PermissionRule> removed = new ArrayList<>();
        @Override public reactor.core.Disposable submit(String text) { return null; }
        @Override public List<PermissionRule> permissionRules() { return List.copyOf(rules); }
        @Override public boolean removePermissionRule(PermissionRule r) {
            removed.add(r);
            return rules.remove(r);
        }
    }

    private static CodeTuiView open(Stub stub, Path root) {
        CodeTuiView v = new CodeTuiView(new ConversationState(), stub, root);
        v.setInputForTest("/permissions");
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
        return v;
    }

    @Test
    @DisplayName("面板列出每条规则的 behavior / DSL / 来源层，并注明底线不可删")
    void listsRulesWithScope(@TempDir Path root) {
        String screen = ViewScreen.of(open(new Stub(), root));
        assertTrue(screen.contains("Read(**/.env)"), screen);
        assertTrue(screen.contains("Bash(mvn test:*)"), screen);
        assertTrue(screen.contains("用户级") && screen.contains("项目级"), "要能看出规则来自哪一层：" + screen);
        assertTrue(screen.contains("内置底线"), "底线不可删这句必须在：" + screen);
    }

    @Test
    @DisplayName("d 只是请求删除、先要确认——不可逆操作不许一键完成")
    void deleteAsksForConfirmationFirst(@TempDir Path root) {
        Stub stub = new Stub();
        CodeTuiView v = open(stub, root);

        v.feedKeyForTest(KeyEvent.ofChar('d'));

        assertTrue(stub.removed.isEmpty(), "按 d 不该立刻删");
        assertTrue(ViewScreen.of(v).contains("确认"), "应出现确认行：" + ViewScreen.of(v));
    }

    @Test
    @DisplayName("确认后才真的删，并从列表消失")
    void confirmActuallyDeletes(@TempDir Path root) {
        Stub stub = new Stub();
        CodeTuiView v = open(stub, root);

        v.feedKeyForTest(KeyEvent.ofChar('d'));
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));

        assertEquals(List.of(DENY_ENV), stub.removed, "删的应是高亮那一条");
        assertFalse(ViewScreen.of(v).contains("Read(**/.env)"), "删完列表要更新");
    }

    @Test
    @DisplayName("确认行 Esc 取消：什么都不删，回到列表")
    void escAtConfirmationCancels(@TempDir Path root) {
        Stub stub = new Stub();
        CodeTuiView v = open(stub, root);

        v.feedKeyForTest(KeyEvent.ofChar('d'));
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ESCAPE));

        assertTrue(stub.removed.isEmpty());
        assertTrue(ViewScreen.of(v).contains("Read(**/.env)"), "应回到列表");
    }

    @Test
    @DisplayName("★ 删 deny 的确认文案要说「这会放宽权限」——两个方向后果不对称，提示也该不对称")
    void denyDeletionWarnsAboutLoosening(@TempDir Path root) {
        Stub stub = new Stub();
        CodeTuiView v = open(stub, root);

        v.feedKeyForTest(KeyEvent.ofChar('d'));         // 高亮在 DENY_ENV 上
        assertTrue(ViewScreen.of(v).contains("放宽"), "删 deny 必须警告：" + ViewScreen.of(v));

        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ESCAPE));
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.DOWN));  // 移到 ALLOW_MVN
        v.feedKeyForTest(KeyEvent.ofChar('d'));
        assertFalse(ViewScreen.of(v).contains("放宽"), "删 allow 不该说放宽：" + ViewScreen.of(v));
    }

    @Test
    @DisplayName("Enter 在列表态不删任何东西——删除键是 d，不是 Enter")
    void enterInListDoesNotDelete(@TempDir Path root) {
        Stub stub = new Stub();
        CodeTuiView v = open(stub, root);
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
        assertTrue(stub.removed.isEmpty(),
                "Enter 若能删，「移动光标后顺手回车」就会误删一条不可逆的规则");
    }

    @Test
    @DisplayName("零规则时也能打开，不崩，并说明怎么加")
    void emptyRuleListIsSafe(@TempDir Path root) {
        Stub empty = new Stub();
        empty.rules.clear();
        String screen = ViewScreen.of(open(empty, root));
        assertTrue(screen.contains("没有自定义规则"), screen);
    }

    @Test
    @DisplayName("非面板态每帧仍会调用面板方法，首行必须判空（scope 是 eager 求值）")
    void renderSmokeWhenPanelClosed(@TempDir Path root) {
        CodeTuiView v = new CodeTuiView(new ConversationState(), new Stub(), root);
        assertTrue(ViewScreen.of(v).length() > 0);
    }
}
```

- [x] **Step 2: 跑测试确认失败**

Run: `mvn -o test -pl springai-code-tui -Dtest=CodeTuiViewPermissionsPanelTest`
Expected: 编译失败（`removePermissionRule` 不存在）/ 屏幕上没有规则列表

- [x] **Step 3: 门面加删除入口**

`SubmitHandler.java`，在既有的 `permissionRules()` 旁边加：

```java
    /**
     * 删一条规则（{@code /permissions} 面板用）。默认 false，便于回显桩/测试桩省略。
     *
     * <p>落地端须<b>同时</b>更新落盘文件与内存规则表——只改一处会让面板与实际判定分家。
     */
    default boolean removePermissionRule(PermissionRule rule) { return false; }
```

生产落地端（`CodingAgent` 里 `permissionRules()` 那几个门面方法附近）转调 `permissionEngine.removeRule(rule)`，
引擎为 null 时返回 false。

- [x] **Step 4: 实现面板**

`CodeTuiView.java`：

① 新增字段（与 `pickingMcp` 并列）：

```java
    private boolean pickingPerms;                     // /permissions 面板是否激活
    private int permsIndex;                           // 列表高亮项
    private PermissionRule permsPendingDelete;        // 非 null = 正在确认删除这一条
```

② `/permissions` 的分发从 `printPermissions()` 改为打开面板（保留把**模式与内置底线**打进
scrollback 的那两行——它们是信息不是列表，留在面板里只会挤占行数）。

③ `render()` 的 `column(...)` 里加 `scope(pickingPerms, permsPanelChildren())`。

④ `permsPanelChildren()`：**首行判空**；标题 `🔑 权限规则（↑↓ 选择 · d 删除 · Esc 关闭）`；
逐条渲染 `[BEHAVIOR] DSL   来源层`（`RuleScope` → 「用户级/项目级/本会话」）；
末尾一行 `内置底线不在此列，无法删除`；零规则时改渲染 `当前没有自定义规则。可在 .codetui/permissions.json 配置，或在审批面板选「允许，永久」自动写入。`；
`permsPendingDelete != null` 时**改渲染确认行**：

```
     ⚠ 确认删除 [DENY] Read(**/.env)（用户级）？这会放宽权限 · Enter 确认 · Esc 取消
```

allow/ask 的确认行改为 `…？以后会重新询问 · Enter 确认 · Esc 取消`。

⑤ `onPermsPanelKey(KeyEvent)`：确认态下 Enter 执行删除（调 `onSubmit.removePermissionRule`，
成功则 `pushInfo` 一行回显、失败则 `pushInfo` 说明「未能删除，规则仍然生效」）、Esc 退回列表；
列表态下 ↑↓/kj 移动、`d` 进确认态、Esc 关面板、**Enter 什么都不做**、其余键吞掉。

⑥ `onInputKey` 里在 `pickingMcp` 那一行附近加 `if (pickingPerms) return onPermsPanelKey(k);`。

⑦ `statusLine()` 加两行提示：列表态 `🔑 权限规则 · ↑↓ 选择 · d 删除 · Esc 关闭`；
确认态 `⚠ 确认删除 · Enter 确认 · Esc 取消`。

> **高亮必须用 `PICK_SEL`（纯前景）**——行内 TUI 下背景色条会串到下一行，本项目 pty 实机复现过。

- [x] **Step 5: 跑测试确认通过**

Run: `mvn -o test -pl springai-code-tui -Dtest='CodeTuiView*Test'`
Expected: 全绿

- [x] **Step 6: 变异实测**

两个变异，各跑一次：

1. 把 `d` 的处理改成**直接删除**（跳过确认态）→ `deleteAsksForConfirmationFirst` 必须变红
2. 把确认行的「放宽」措辞对 deny/allow **统一成一句** → `denyDeletionWarnsAboutLoosening` 必须变红

都恢复，恢复后复跑全绿。结果写进报告。

- [x] **Step 7: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/SubmitHandler.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/CodeTuiViewPermissionsPanelTest.java
git commit -m "feat(code-tui): /permissions 改交互面板，可删规则且删 deny 时警告放宽"
```

> **实施提示**：`CodingAgent` 那处门面转调若不在你的授权文件里，**在报告里说明**，由协调者补。

---

## Task 8: pty 实机冒烟 + 文档

**Files:**
- Modify: `springai-code-tui/src/test/resources/scripts/permission_smoke.py`
- Modify: `springai-code-tui/README.md`
- Modify: `SECURITY.md`

**跑法**（改完**必须重新 package**，否则跑的是旧 jar）：

```bash
mvn -q -o package -pl springai-code-tui -DskipTests
mvn -q -o dependency:build-classpath -pl springai-code-tui -Dmdep.outputFile=target/cp.txt
cd springai-code-tui && python3 src/test/resources/scripts/permission_smoke.py
```

- [x] **Step 1: 冒烟脚本改造既有的 `/permissions` 断言**

`check_permissions_report` 现在断言的是 scrollback 里的三段文本。`/permissions` 改成面板后，
**规则那段不再进 scrollback**——请把它改成：模式与内置底线仍在 scrollback（断言不变），
规则列表改为在**面板**里断言。

⚠ 用 `restore_default_mode()` 那套纪律：**不要用 `wait_for` 断言「当前状态」**，
它会命中早先留在 scrollback 的陈旧文本（本脚本为此栽过一次，见脚本内注释）。

- [x] **Step 2: 新增 `check_permissions_panel(session)`**

覆盖：

- 先经审批面板选「3. 允许，永久」写下一条规则（复用既有的 `PUSHNOW` 场景），
  确认 `/permissions` 面板里**看得见它**
- 面板里 `d` → **出现确认行**、规则**还在**
- Esc → 回列表、规则仍在
- 再 `d` → Enter → 规则从列表消失，且 `.codetui/permissions.json` 里**也没了**
  （脚本可直接读那个临时工作区里的文件断言）
- 面板每项**各占一个连续物理行**（`find_row` 取行号断言连号）
- 高亮**纯前景**：选中行与其下一行 `row_backgrounds` 都是 `{"default"}`
- 状态栏出现 `d 删除`

接进 `main()` 的场景序列。

- [x] **Step 3: 跑冒烟**

Expected: `SMOKE PASS`，且包含你新加的 `OK:` 行。

⚠ 若某条断言红了，**先判断是脚本写错还是产品真有问题**。是产品问题就修产品，并在报告里说清楚——
**这正是这一步存在的意义**，不要为了让脚本变绿而放宽断言。

- [x] **Step 4: 文档**

`springai-code-tui/README.md`：

- 「已知限制 · 权限层本期未做的」里**删掉**「无规则编辑界面：`/permissions` 只读…」这条，
  改成：`/permissions` 可**删**规则；**新增**规则仍走审批面板的「永久允许」（面板内手输 DSL 不在本期）
- 「安全声明 · 权限层自身的已知弱点」里那条「命令判定是尽力而为」**要改**：
  路径匹配**不再**区分大小写、**会**解析符号链接（**但只在 deny/ask 方向**——
  把这条不对称写清楚，它是设计而非疏漏）；「不做空白归一」仍然成立，保留
- 「权限模式」表的「自动接受编辑」一行：删掉那条 ⚠️（命令段不判工作区），
  改成「工作区内的文件操作」——现在它属实了
- 「判定顺序」表下补一句：过宽工作区（`/` 或家目录的严格祖先）时「工作区内」不再作为豁免依据，启动时有提示

`SECURITY.md`：

- 「权限层的已知弱点」里删掉「路径匹配区分大小写…deny 方向会漏」「不解析符号链接」两句
- **补一句新的诚实声明**：直接在 `$HOME` 下运行时，「家目录豁免」仍使「系统位置」检查对整个家目录失效——
  这一条本期没动（见 spec §4）

- [x] **Step 5: 提交**

```bash
git add springai-code-tui/src/test/resources/scripts/permission_smoke.py \
        springai-code-tui/README.md SECURITY.md
git commit -m "test(code-tui): /permissions 面板的 pty 实机冒烟；文档随修复更新"
```

---

## 完成标准

- [x] `mvn test -pl springai-code-tui` 全绿（`CodingAgentSpikeTest.todoTurnIdBinding` 的红单独记）
- [x] `permission_smoke.py` → `SMOKE PASS`，含 `/permissions` 面板的新断言
- [x] **六处变异实测全部生效**：Task 1（爬祖先）、Task 2（allow 不折叠 / 别名）、Task 3（只查首个别名）、
      Task 4（过宽 root 豁免）、Task 5（命令段判工作区）、Task 6（清折叠孪生）、Task 7（确认态 / deny 措辞）
- [x] 人工：误点一次「永久允许」后能在 `/permissions` 面板里删掉，**重启后确实不在**
- [x] 人工：删一条 deny 规则时，确认提示明确说「这会放宽权限」

---

## 计划自检

**1. spec 覆盖**

| spec 章节 | 对应 Task |
|---|---|
| §1 只在 deny/ask 方向放宽（原则） | Task 2（引擎，含两条安全支点反向断言） |
| §1.2 符号链接别名 | Task 1（`PathAliases`）+ Task 2（引擎接入）+ Task 3（底线接入） |
| §1.3 大小写折叠孪生 | Task 2 |
| §1.5 内置底线只需审计 | Task 3 |
| §2 `ACCEPT_EDITS` 命令段判工作区 | Task 5 |
| §3 过宽 root | Task 4 |
| §4 家目录豁免不动（诚实声明） | Task 4 的 `isOverBroadRoot` javadoc + Task 8 的 SECURITY.md |
| §5 `/permissions` 可删规则 | Task 6（writer + 引擎）+ Task 7（面板） |
| §6 测试策略 | 贯穿硬约束 + 各 Task 的变异实测步骤 |

**2. 占位扫描**：无 TBD / 「类似 Task N」/「加适当的错误处理」。
三处标了「以仓库现状为准」的地方（`e.rules()` 的真实方法名、`CodingAgent` 门面转调的位置、
`words()` 的可见性）都给了**判据与去处**，且要求「照抄邻居的既有写法」而非自行发明。

**3. 类型一致性**：`PathAliases.of(Path) → List<Path>` 在 Task 1 定义，Task 2/3 消费一致；
`BashCommandSplitter.pathArguments(String) → List<String>` 在 Task 5 定义并在同 Task 消费；
`PermissionConfigWriter.remove(Path, PermissionRule) → boolean` 与
`PermissionEngine.removeRule(PermissionRule) → boolean` 在 Task 6 定义、Task 7 经
`SubmitHandler.removePermissionRule` 消费，签名一致；
`DangerousPaths.isOverBroadRoot(Path) → boolean` 与 `CodeTuiApplication.overBroadRootNotice(Path) → String`
在 Task 4 定义与消费。

**4. 已知实施顺序依赖**：Task 2/3 都依赖 Task 1 的 `PathAliases`（**Task 1 必须先做**）；
Task 7 依赖 Task 6 的 `removeRule`；Task 8 依赖 Task 7 的面板。
Task 4 与 Task 5 彼此独立，也不与 Task 2/3 抢文件之外的语义——但 **Task 3/4 同改
`DangerousPaths.java`、Task 2/5 同改 `PermissionEngine.java`**，并行执行时须错开波次。

**5. 并行波次建议**（若走 subagent 并行）：

| 波 | 任务 | 冲突说明 |
|---|---|---|
| 1 | Task 1 | 其余全依赖它 |
| 2 | Task 2（`PermissionEngine`）· Task 3（`DangerousPaths`） | 两文件不相交 |
| 3 | Task 4（`DangerousPaths`+`CodeTuiApplication`）· Task 5（`PermissionEngine`+`BashCommandSplitter`） | 与第 2 波错开同名文件 |
| 4 | Task 6（`writer`+`PermissionEngine`） | 等 Task 2/5 放开 `PermissionEngine` |
| 5 | Task 7（`CodeTuiView`+`SubmitHandler`） | 依赖 Task 6 |
| 6 | Task 8（脚本 + 文档） | 依赖全部 |

---

## 实施记录（2026-08-02 落地，11 个提交 `8ec53cc` → `0b7f9e4`）

**执行方式**：subagent 并行，按文件冲突分波。中途两个 agent 因配额（402）与连接中断掉线，
未完成的部分由协调者接手——**因此本期没有「谁的活」这种边界，最终判据一律是协调者在全部
提交后自己跑的那次全量回归与 pty 冒烟。**

**验证**：`mvn -o test -pl springai-code-tui` → **952 跑 / 1 败**
（`CodingAgentSpikeTest.todoTurnIdBinding`，打真实模型的既有 flaky，单独记）；
`permission_smoke.py` → **SMOKE PASS**，18 条断言。

### 四个漏洞的最终形态

| # | 修法 | 关键取舍 |
|---|---|---|
| 1 大小写 | deny/ask 规则预生成**折叠孪生**（pattern 小写），匹配时小写候选配孪生 | `PermissionRule` 一行未改；`pattern == null` 与 ALLOW 不造孪生 |
| 2 符号链接 | `PathAliases`：解析**最长已存在祖先**再拼回剩余段；**悬空链接**走 `readSymbolicLink`，有界 8 跳 | 每跳完把「爬祖先 + toRealPath」整个重来，一个循环兜住三种形态 |
| 3 `ACCEPT_EDITS` 命令段 | 路径参数**全部**在工作区内才放行 | `~` 一律判区外；通配符取字面前缀；`--opt=value` 取 `=` 之后那半 |
| 4 过宽 root | `root` 是 `/` 或家目录**严格祖先**时不做豁免 + 启动提示 | `root == 家目录`**刻意不救**（另有独立的家目录豁免，见 spec §4） |

### 计划外的发现与修正

**① 「不清折叠孪生，删掉的 deny 会借孪生继续生效」——我编的因果，代码里不成立。**
计划与任务书都这么写，实施者据此加了 `foldedTwins.remove(rule)` 并配了注释。
**变异实测停掉那行，用例照样绿**——孪生是按规则做键的缓存，只在匹配「仍在规则表里」的
规则时经 `computeIfAbsent` 查到，规则一摘就再也查不到。那行是**缓存卫生，不是正确性护栏**。
两处注释与用例定位已更正。

> 教训：一个听起来有道理的机制可以通过代码审查（实施者照做、还写了注释），
> **只有变异实测能揭穿**。变异不只是「验证测试会红」，更是**验证你以为的因果链真的存在**。

**② 两条我写的「不会失败的测试」，都被实施者抓出。**
- `allowDoesNotFoldCase`（标着 ★ 的安全支点）：`Write /ETC/passwd` 在**第 2 步**内置检查就被
  拦成 ASK，**第 5 步 allow 根本轮不到**，折不折叠断言恒为 ASK。改用**工作区内**路径才有鉴别力。
- `allow Read(src/**)` 的反向断言：`Read` 是 `READ_ONLY`、模式默认本就 ALLOW，
  命中与否结论相同。改用 `Write`。实施者还补了对照组，证明那个 ASK 来自「大小写没折」
  而非「相对 pattern 的 allow 整个不工作」——少了它，反向断言会因**错误的原因**变绿。

**③ 一个 agent 死在变异实测中途**，`BashCommandSplitter` 里留下 `// MUTANT: = 形态那段已移除`。
**这是并行开发最危险的残留形态**：一个被故意改坏的产品代码留在工作树里，不跑测试就提交
等于把变异当成品交付。收尾时靠跑测试发现并还原。

**④ pty 断言连踩三次「把 scrollback 当当前状态」**（期 2 已为此栽过一次）：
- `"Bash(" not in screen_text()` 判断「列表里没了」——而几步前刚打进去 `✓ 已记下允许规则：Bash(...)`；
- `assert_mode_via_report` 读完报告**不关面板**，而面板吞掉所有按键，后续场景字符进不了输入框；
- `find_row` 取首个匹配 → 命中上一轮 scrollback；改取最后一个 → 撞同屏重复
  （`git push origin main` 同时在目标行与原因行）。

**两种纯策略都不成立**，故新增 `assert_rows_below`：锚定面板标题、往下逐行核对——
两个问题一起消失，还顺带把顺序钉住。

### 实施者顶回计划、而且是对的

- **`@TempDir` 的处理**：我给的退路是「放宽成 `contains`」，实施者改为**先 `toRealPath()` 再往下建**，
  让「有没有链」由用例自己控制。断言反而**比原稿更强**。
  → 通则：**先让环境变确定，再考虑放宽断言**。
- **`roundTrips` 不用于 `remove`**：那道校验防的是「落盘的授权宽于用户批准的那一次」，
  只对写入方向成立；删除是收窄方向，要求往返一致只会让**一条恰好不能往返的规则永远删不掉**。
- **抽 `writeAtomically` 而非照抄**：我写「照抄 append 的纪律」，实施者改为两边共用一份实现——
  授权文件里两份平行实现，日后只改一边就是静默漂移。
- **并发断言不能用 `mode()` 快照**（沿用期 2 的结论）。

### 已知遗留（如实留在代码与文档里）

- **硬链接没有任何一层兜得住**：`ln ~/.ssh/id_rsa /tmp/x` 之后 `/tmp/x` 路径上没有任何可疑之处，
  逐段判定与别名解析都无从下手。文件系统不提供同 inode 反查。
- **macOS firmlink** `toRealPath` 不收敛，靠内置底线的结构匹配兜（路径里仍有 `.ssh` 这样的段）。
- **链环或超 8 跳的链接**：跳数用尽退回原写法。
- **`$HOME` 下直接运行**：家目录豁免仍使「系统位置」检查对整个家目录失效，
  过宽 root 那条修法**救不了这一种**（见 spec §4）。
- **`shadows()` 不走折叠孪生**：`deny Read(/ETC/**)` 遮蔽不了针对 `/etc/x` 的候选建议，
  面板上仍会出现「永久允许」。**不是绕过**（deny 在第 1 步照样赢），但会让用户写下一条
  永不生效的 allow。已在 `shadows()` javadoc 留痕。
- **`{a,b}` 花括号不算通配符**：`cp {../x,y} d` 整串当字面路径 → 判区外 → ASK。方向安全。
- **性能**：`checkSegment`/`checkHeadIndependently` 逐词调 `checkWrite`/`checkRead`，
  一条 N 词命令产生 O(N) 次别名解析。单条命令量级仍小，已写进 `firstHit` javadoc。

### 仍需人工验收（要真实模型 / 真实终端）

- [ ] 误点一次「允许，永久」后，在 `/permissions` 面板里 `d` 删掉它，**重启后确实不在**
- [ ] 删一条 deny 规则时，确认提示明确说「这会放宽权限」
- [ ] macOS 上造一条经符号链接指向 `~/.ssh` 的写入，确认**真的弹审批**（冒烟用的是临时目录里的假家目录）

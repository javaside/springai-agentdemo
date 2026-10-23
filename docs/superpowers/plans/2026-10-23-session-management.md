# 会话管理增强 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 springai-code-tui 增加运行期会话管理：`/resume` 面板（恢复/分叉/删除）、`/fork` 命令、`--resume <id>` 启动参数。

**Architecture:** 全部复用既有积木——`/clear` 的切换序列模板、本地选择器四件套、`FileSessionRepository` 惰性仓库、MCP 后台连接模式。新增 `SessionCatalog`（扫描 + 流式摘要）与 `UserTextStripper`（从 `HistoryReplay` 提取的注入剥离）；`CodingAgent.clearContext` 重构为 `switchContext(String)` 特例；`SubmitHandler` 加 6 个 default 会话门面方法打通 UI 接线。

**Tech Stack:** Java 17（release 锁 17）、JUnit 5、Jackson 3（`tools.jackson`）、Spring AI 2.0 Session API。

**Spec:** `docs/superpowers/specs/2026-10-23-session-management-design.md`（含 12 条事实依据与评审修订，执行者须先读 spec 再动手）。

## Global Constraints

- 语言级别锁 17（`maven.compiler.release=17`），禁用更高版本语法。
- 注释/错误消息/提交正文中文；注释写**为什么**。
- 单类测试命令（缺一必挂）：`mvn -pl springai-code-tui -am test -Dtest='XxxTest' -Dsurefire.failIfNoSpecifiedTests=false`
- 工作分支 `feature/session-management`，不提交 main；提交 `type: 说明` 前缀，功能与测试同一提交。
- 所有新代码位于 `io.github.javaside.springai.codetui` 及其子包；`agent.session` 不得 import `ui` 包（依赖方向 ui → agent）。
- 面板纪律（对齐 `/tasks` 先例）：键处理器**始终返回 HANDLED**；`scope` 第二参数 eager 求值，children 首行必须判空；空列表防除零。

---

### Task 1: `UserTextStripper` 搬移 + `stripAll`

**Files:**
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/session/UserTextStripper.java`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/HistoryReplay.java`（`stripSkillInstruction`/`stripFileReferences` 及私有辅助 `summarise`/`field` 搬走，原位置改委托）
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/session/UserTextStripperTest.java`

**Interfaces:**
- Consumes: `agent.interjection.InterjectionText.unwrap/wrap`（public，已存在）、`agent.media.FileReference.OPEN/CLOSE`（已存在）。
- Produces: `UserTextStripper.stripAll(String stored) -> String`（Task 3 的 digest 依赖）；`stripSkillInstruction(String)`、`stripFileReferences(String)`（public static）。`HistoryReplay` 的两个同名包级方法保留为委托（`HistoryReplayTest` 零改动）。

- [ ] **Step 1: 写失败测试**

```java
package io.github.javaside.springai.codetui.agent.session;

import io.github.javaside.springai.codetui.agent.interjection.InterjectionText;
import io.github.javaside.springai.codetui.agent.media.FileReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** stripAll：会话里存的「注入后」用户文本 → 用户原文。三步剥离搬自 HistoryReplay（纯搬家），此处钉组合行为。 */
class UserTextStripperTest {

    @Test
    void stripAll_removesSkillInstructionPrefix() {
        String stored = "<skill_instruction>\n技能正文\n</skill_instruction>\n\n帮我改 pom";
        assertEquals("帮我改 pom", UserTextStripper.stripAll(stored));
    }

    @Test
    void stripAll_rewritesFileReferenceBlockToOneLine() {
        String block = FileReference.OPEN + "\nname: bug.png\ndimensions: 800x600\nkind: image\n"
                + "path: .codetui/artifacts/abc.png\n" + FileReference.CLOSE;
        String out = UserTextStripper.stripAll(block + "\n看看这个报错");
        // 引用块折叠成一行 📎（字段缺失少显示，不报错）；正文保留
        org.junit.jupiter.api.Assertions.assertTrue(out.startsWith("📎 bug.png"), "折叠行: " + out);
        org.junit.jupiter.api.Assertions.assertTrue(out.endsWith("看看这个报错"));
    }

    @Test
    void stripAll_unwrapsInterjectionAndCombinesAllThree() {
        String raw = "等一下，先看测试";
        String stored = InterjectionText.wrap("<skill_instruction>\nx\n</skill_instruction>\n\n"
                + FileReference.OPEN + "\nname: a.png\n" + FileReference.CLOSE + "\n" + raw);
        // 引用块是【折叠】为一行 📎 而非删除（与 HistoryReplay 回放同语义）——期望必须含折叠行
        assertEquals("📎 a.png\n" + raw, UserTextStripper.stripAll(stored), "包裹+技能前缀剥除、引用块折叠");
    }

    @Test
    void plainTextUntouched_andIncompleteMarkerKept() {
        assertEquals("普通文本", UserTextStripper.stripAll("普通文本"));
        String broken = "<skill_instruction>没闭合";
        assertEquals(broken, UserTextStripper.stripAll(broken), "残缺标记保底原样，不误删");
    }
}
```

- [ ] **Step 2: 跑测试确认红**

Run: `mvn -pl springai-code-tui -am test -Dtest='UserTextStripperTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败 `cannot find symbol: UserTextStripper`。

- [ ] **Step 3: 最小实现（纯搬家）**

新建 `UserTextStripper`：把 `HistoryReplay.java` 176-242 行的 `stripSkillInstruction`、`stripFileReferences`、`summarise`、`field` 四个方法**原样搬入**（含 javadoc；包声明改为 `agent.session`，方法升 `public static`），类 javadoc 写明「从 ui.HistoryReplay 搬移，HistoryReplay 委托本类——单一出处；agent.session 的会话摘要需要剥离而不能反向依赖 ui 包」。新增组合入口：

```java
/** 三步剥离的组合：插话包裹 → 技能前缀 → 引用块。顺序与 HistoryReplay.toReplayLines 一致（互不重叠，固定顺序便于推理）。 */
public static String stripAll(String stored) {
    return stripFileReferences(stripSkillInstruction(InterjectionText.unwrap(stored)));
}
```

`HistoryReplay` 原位置改为委托（方法体一行转发），删除已搬走的 `summarise`/`field`：

```java
static String stripSkillInstruction(String userText) { return UserTextStripper.stripSkillInstruction(userText); }
static String stripFileReferences(String text) { return UserTextStripper.stripFileReferences(text); }
```

- [ ] **Step 4: 跑新旧两个测试类确认绿**

Run: `mvn -pl springai-code-tui -am test -Dtest='UserTextStripperTest,HistoryReplayTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 全 PASS（`HistoryReplayTest` 不动即证明纯搬家零行为变化）。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/session/UserTextStripper.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/HistoryReplay.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/session/UserTextStripperTest.java
git commit -m "refactor: 剥离函数从 HistoryReplay 搬移到 agent 层 UserTextStripper"
```

---

### Task 2: `SessionCatalog.scan` + `SessionSummary`

**Files:**
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/session/SessionCatalog.java`（本任务先建 scan 部分；Task 3 再加 digest）
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/session/SessionCatalogTest.java`

**Interfaces:**
- Consumes: 无（纯 JDK）。
- Produces: `record SessionSummary(String id, long mtimeMillis, long sizeBytes, String digest)`（紧凑构造把 digest 缺省为 null）；`SessionCatalog.scan(Path baseDir) -> List<SessionSummary>`（mtime 降序；空/不存在目录返回空表，不抛）。Task 6（启动参数候选）与 Task 7（面板）依赖。

- [ ] **Step 1: 写失败测试**

```java
package io.github.javaside.springai.codetui.agent.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** scan：只扫文件名+mtime（192 个会话文件的目录必须瞬时），不解析内容。 */
class SessionCatalogTest {

    @Test
    void scan_sortsByMtimeDesc_andIgnoresNonJson(@TempDir Path dir) throws IOException {
        Path older = dir.resolve("20260101T000000-aaaaaa.json");
        Path newer = dir.resolve("20260202T000000-bbbbbb.json");
        Files.writeString(older, "{}");
        Files.writeString(newer, "{}");
        Files.writeString(dir.resolve("README.md"), "not a session");   // 非 .json 忽略
        Files.setLastModifiedTime(older, java.nio.file.attribute.FileTime.from(Instant.parse("2026-01-01T00:00:00Z")));
        Files.setLastModifiedTime(newer, java.nio.file.attribute.FileTime.from(Instant.parse("2026-02-02T00:00:00Z")));

        List<SessionSummary> out = SessionCatalog.scan(dir);

        assertEquals(2, out.size());
        assertEquals("20260202T000000-bbbbbb", out.get(0).id(), "mtime 新的在前");
        assertEquals("20260101T000000-aaaaaa", out.get(1).id());
        assertTrue(out.get(0).sizeBytes() > 0);
        assertEquals(null, out.get(0).digest(), "scan 不填 digest——后台任务（Task 3）才填");
    }

    @Test
    void scan_emptyOrMissingDir_returnsEmpty(@TempDir Path dir) {
        assertEquals(List.of(), SessionCatalog.scan(dir.resolve("nope")));
        assertEquals(List.of(), SessionCatalog.scan(dir));
    }
}
```

- [ ] **Step 2: 跑测试确认红**

Run: `mvn -pl springai-code-tui -am test -Dtest='SessionCatalogTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败 `cannot find symbol: SessionCatalog`。

- [ ] **Step 3: 最小实现**

```java
package io.github.javaside.springai.codetui.agent.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 会话目录的轻量索引：只扫文件名 + mtime（本机实测 192 个文件，必须瞬时），
 * 内容解析（首条用户消息摘要）是另一条后台路径 {@link #firstUserDigest}（Task 3）。
 */
public final class SessionCatalog {

    private static final Logger log = LoggerFactory.getLogger(SessionCatalog.class);

    private SessionCatalog() {}

    /** 列表项：digest 由后台任务补填（null = 未解析），故提供三参紧凑构造。 */
    public record SessionSummary(String id, long mtimeMillis, long sizeBytes, String digest) {
        public SessionSummary(String id, long mtimeMillis, long sizeBytes) {
            this(id, mtimeMillis, sizeBytes, null);
        }
    }

    /** mtime 降序（最近在前）。目录不存在/空/IO 失败 → 空表，不抛（面板与启动参数共用，都不该被目录问题打断）。 */
    public static List<SessionSummary> scan(Path baseDir) {
        if (!Files.isDirectory(baseDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(baseDir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .map(SessionCatalog::toSummary)
                    .sorted(Comparator.comparingLong(SessionSummary::mtimeMillis).reversed())
                    .toList();
        } catch (IOException | UncheckedIOException e) {
            log.warn("扫描会话目录失败 {}：{}", baseDir, e.toString());
            return List.of();
        }
    }

    private static SessionSummary toSummary(Path p) {
        String name = p.getFileName().toString();
        String id = name.substring(0, name.length() - ".json".length());
        try {
            return new SessionSummary(id,
                    Files.getLastModifiedTime(p).toMillis(), Files.size(p));
        } catch (IOException e) {
            return new SessionSummary(id, 0L, 0L);   // mtime/size 拿不到只是排序信息缺失，不值得失败
        }
    }
}
```

- [ ] **Step 4: 跑测试确认绿**

Run: `mvn -pl springai-code-tui -am test -Dtest='SessionCatalogTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/session/SessionCatalog.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/session/SessionCatalogTest.java
git commit -m "feat: SessionCatalog 扫描会话目录（文件名+mtime，瞬时）"
```

---

### Task 3: `SessionCatalog.firstUserDigest`（Jackson 流式）

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/session/SessionCatalog.java`（加 `firstUserDigest`）
- Test: `SessionCatalogTest.java`（补用例）

**Interfaces:**
- Consumes: Task 1 的 `UserTextStripper.stripAll`；Jackson 3（`tools.jackson.core.json.JsonFactory`、`tools.jackson.core.JsonParser/JsonToken`——注意包名，见 Step 3 的坑记录）。
- Produces: `SessionCatalog.firstUserDigest(Path file) -> String`（null = 无首条 user/损坏/空）。Task 7 的后台摘要线程依赖。
- **对 spec 的实现级微调（在此记录）**：spec 列表行的「（无法解析）」与「（无对话）」合并显示为「（无对话）」，且「（加载中…）」中间态一并取消（digest 为 null 一律显示「（无对话）」——区分三态需给返回值加哨兵语义，且后台线程通常毫秒级补齐，中间态价值低）。

- [ ] **Step 1: 补失败测试（追加到 `SessionCatalogTest`）**

```java
    // ── firstUserDigest（Jackson 流式，读到首条 USER 即停）────────────────────

    /** 会话文件的最小合法 JSON 形状（与 FileSessionRepository.PersistedSession 对齐的字段子集）。 */
    private static Path sessionFile(Path dir, String name, String json) throws IOException {
        Path f = dir.resolve(name + ".json");
        Files.writeString(f, json);
        return f;
    }

    @Test
    void digest_readsFirstUser_andStopsBeforeLaterCorruption(@TempDir Path dir) throws IOException {
        // 首条 USER 之后的内容是损坏 JSON：若实现全文解析必抛/必吞，读到首条即停则成功返回——「即停」的决定性证据。
        Path f = sessionFile(dir, "s1", "{\"events\":[{\"messageType\":\"USER\",\"content\":\"帮我修 NPE\"},<<<CORRUPT");
        assertEquals("帮我修 NPE", SessionCatalog.firstUserDigest(f));
    }

    @Test
    void digest_stripsInjections_foldsAndTruncates(@TempDir Path dir) throws IOException {
        String longText = "非".repeat(100);   // 100 个 CJK 字符，超 80 截断
        String stored = "<skill_instruction>\nx\n</skill_instruction>\n\n" + longText + "\n第二行";
        Path f = sessionFile(dir, "s2",
                "{\"events\":[{\"messageType\":\"ASSISTANT\",\"content\":\"跳过\"},{\"messageType\":\"USER\",\"content\":\""
                        + stored.replace("\n", "\\n").replace("\"", "\\\"") + "\"}]}");
        String out = SessionCatalog.firstUserDigest(f);
        org.junit.jupiter.api.Assertions.assertNotNull(out);
        org.junit.jupiter.api.Assertions.assertFalse(out.contains("skill_instruction"), "剥离技能前缀");
        org.junit.jupiter.api.Assertions.assertFalse(out.contains("\n"), "多行折叠为单行");
        org.junit.jupiter.api.Assertions.assertTrue(out.codePoints().count() <= 80, "截断到 80 字符: " + out);
    }

    @Test
    void digest_noUser_orCorrupt_orMissingFile_returnsNull(@TempDir Path dir) throws IOException {
        assertEquals(null, SessionCatalog.firstUserDigest(
                sessionFile(dir, "s3", "{\"events\":[{\"messageType\":\"ASSISTANT\",\"content\":\"x\"}]}")));
        assertEquals(null, SessionCatalog.firstUserDigest(sessionFile(dir, "s4", "{ 不是 JSON")));
        assertEquals(null, SessionCatalog.firstUserDigest(dir.resolve("nope.json")));
    }
```

- [ ] **Step 2: 跑测试确认红**

Run: `mvn -pl springai-code-tui -am test -Dtest='SessionCatalogTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败 `cannot find symbol: firstUserDigest`。

- [ ] **Step 3: 最小实现（加进 `SessionCatalog`）**

```java
    // Jackson 3 两个坑（javap 实测 jackson-core 3.1.4）：JsonFactory 在 tools.jackson.core.json 子包
    // （不在 tools.jackson.core）；字段名 token 是 PROPERTY_NAME（Jackson 2 的 FIELD_NAME 已改名）。
    // 且 Jackson 3 异常全为 unchecked（JacksonException extends RuntimeException），catch
    // RuntimeException 即覆盖损坏 JSON——不要 catch IOException（无来源，编译期 unreachable）。
    private static final tools.jackson.core.json.JsonFactory JSON = new tools.jackson.core.json.JsonFactory();
    private static final int DIGEST_MAX = 80;

    /**
     * 流式解析：逐 token 走 events[]，读到首条 USER 的 content 即返回（本机最大会话 773KB，
     * 全文解析在后台线程 × 192 个文件也是白烧 IO——首条 user 几乎总在文件头部）。
     * 返回 null = 无首条 user / 损坏 / 文件不存在（面板统一显示「（无对话）」，见 spec 微调记录）。
     */
    public static String firstUserDigest(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (JsonParser p = JSON.createParser(file.toFile())) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                return null;
            }
            while (p.nextToken() == JsonToken.PROPERTY_NAME) {
                String field = p.currentName();
                JsonToken valueToken = p.nextToken();
                if ("events".equals(field) && valueToken == JsonToken.START_ARRAY) {
                    // 无论找到与否，events 走完/首条命中都到此为止——后面的字段（version 等）不读
                    return firstUserInEvents(p);
                }
                p.skipChildren();   // 其它顶层字段整体跳过
            }
        } catch (RuntimeException e) {
            log.debug("会话摘要解析失败 {}：{}", file, e.toString());
            return null;
        }
        return null;
    }

    /** events 数组内：逐事件对象找 messageType=USER；命中返回折叠截断后的摘要，遍历完无则 null。 */
    private static String firstUserInEvents(JsonParser p) {
        while (p.nextToken() == JsonToken.START_OBJECT) {
            String type = null;
            String content = null;
            while (p.nextToken() == JsonToken.PROPERTY_NAME) {
                String f = p.currentName();
                JsonToken t = p.nextToken();
                if ("messageType".equals(f)) {
                    type = p.getText();
                } else if ("content".equals(f) && t == JsonToken.VALUE_STRING) {
                    content = p.getText();
                } else {
                    p.skipChildren();   // 数组/对象值跳过；标量值 skipChildren 是 no-op
                }
            }
            if ("USER".equals(type) && content != null && !content.isBlank()) {
                String stripped = UserTextStripper.stripAll(content)
                        .replaceAll("\\R+", " ").strip();
                if (stripped.isEmpty()) {
                    return null;   // 剥完只剩注入标记：没有可认的对话内容
                }
                return stripped.codePoints().count() <= DIGEST_MAX
                        ? stripped
                        : new String(stripped.codePoints().limit(DIGEST_MAX)
                                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                                .toString());
            }
        }
        return null;
    }
```

imports 追加：`tools.jackson.core.json.JsonFactory`、`tools.jackson.core.JsonParser`、`tools.jackson.core.JsonToken`。

- [ ] **Step 4: 跑测试确认绿**

Run: `mvn -pl springai-code-tui -am test -Dtest='SessionCatalogTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（重点看 `digest_readsFirstUser_andStopsBeforeLaterCorruption`）。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/session/SessionCatalog.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/session/SessionCatalogTest.java
git commit -m "feat: SessionCatalog 流式提取首条用户消息摘要（读到即停）"
```

---

### Task 4: `FileSessionRepository.fork`

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/session/FileSessionRepository.java`（加 `fork`）
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/session/FileSessionRepositoryTest.java`（补用例）

**Interfaces:**
- Consumes: 自有 `findEvents`/`findById`/`save`/`replaceEvents`；`SessionIds.newId()`。
- Produces: `public Optional<String> fork(String sourceId)`——事件复制到新 id 且**每条事件的 sessionId 字段重写为新 id**；源不存在/事件为空 → `Optional.empty()`。Task 5 的 seam 与 Task 8 的 `/fork` 依赖。

- [ ] **Step 1: 补失败测试（追加到 `FileSessionRepositoryTest`）**

```java
    // ── fork（/resume 面板 f 键与 /fork 命令的仓库侧实现）────────────────────

    @Test
    void forkCopiesEventsToNewId_andSourceUntouched(@TempDir Path dir) {
        FileSessionRepository repo = new FileSessionRepository(dir);
        repo.save(session(SID));
        repo.appendEvent(ev(SID, new UserMessage("原会话问题")));
        repo.appendEvent(ev(SID, new AssistantMessage("原会话回答")));
        Path sourceFile = dir.resolve(SID + ".json");
        String sourceBytesBefore;
        try {
            sourceBytesBefore = Files.readString(sourceFile);
        } catch (IOException e) { throw new RuntimeException(e); }

        Optional<String> forked = repo.fork(SID);

        assertTrue(forked.isPresent(), "有事件的会话可 fork");
        String newId = forked.get();
        assertNotEquals(SID, newId);
        List<SessionEvent> events = repo.findEvents(newId, EventFilter.all());
        assertEquals(2, events.size(), "事件逐条复制");
        assertEquals("原会话问题", events.get(0).getMessage().getText());
        assertEquals("原会话回答", events.get(1).getMessage().getText());
        assertEquals(newId, events.get(0).getSessionId(), "事件的 sessionId 字段必须重写——"
                + "不重写则下次加载 fork 会话时，事件仍自报源会话 id，排障时两条线对不上");
        try {
            assertEquals(sourceBytesBefore, Files.readString(sourceFile), "源文件字节不动");
        } catch (IOException e) { throw new RuntimeException(e); }
        assertEquals(2, repo.findEvents(SID, EventFilter.all()).size(), "源会话事件不动");
    }

    @Test
    void forkTwiceYieldsDistinctIds_emptyOrMissingSourceRejected(@TempDir Path dir) {
        FileSessionRepository repo = new FileSessionRepository(dir);
        repo.save(session(SID));
        assertEquals(Optional.empty(), repo.fork(SID), "空会话 fork 无意义，拒绝");
        assertEquals(Optional.empty(), repo.fork("no-such-session"), "源不存在拒绝");
        repo.appendEvent(ev(SID, new UserMessage("x")));
        String a = repo.fork(SID).orElseThrow();
        String b = repo.fork(SID).orElseThrow();
        assertNotEquals(a, b, "两次 fork 得两个独立新会话");
    }
```

- [ ] **Step 2: 跑测试确认红**

Run: `mvn -pl springai-code-tui -am test -Dtest='FileSessionRepositoryTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败 `cannot find symbol: fork`。

- [ ] **Step 3: 最小实现（加进 `FileSessionRepository`）**

```java
    // ── fork（/resume 面板 f 键与 /fork 命令；与 latestSessionId 一样属启动/面板侧扩展） ──

    /**
     * 分叉：把 sourceId 的已净化事件复制到一个新会话 id 下，返回新 id；源不存在或事件为空返回 empty。
     *
     * <p>刻意走本仓库 API 而非 {@code Files.copy}：直接复制文件会绕过 store，且每条事件的
     * {@code sessionId} 字段还指向源——必须逐条重写为新 id，否则 fork 出的会话加载后事件自报的
     * id 与所在文件对不上。userId/metadata 复制源会话（那是「谁在哪个项目」的事实，不随分叉变），
     * 仅换 id 与 createdAt。
     */
    public Optional<String> fork(String sourceId) {
        List<SessionEvent> events = findEvents(sourceId, EventFilter.all());
        if (events.isEmpty()) {
            return Optional.empty();
        }
        Session source = findById(sourceId).orElse(null);
        if (source == null) {
            return Optional.empty();
        }
        String newId = SessionIds.newId();
        Session forked = Session.builder()
                .id(newId)
                .userId(source.userId())
                .createdAt(Instant.now())
                .expiresAt(null)
                .metadata(source.metadata() == null ? Map.of() : source.metadata())
                .build();
        save(forked);
        replaceEvents(newId, events.stream().map(e -> retarget(e, newId)).toList());
        return Optional.of(newId);
    }

    /** 事件不可变，重写 sessionId 只能整体重建（其余字段原样保留）。 */
    private static SessionEvent retarget(SessionEvent e, String sessionId) {
        SessionEvent.Builder b = SessionEvent.builder()
                .id(e.getId()).sessionId(sessionId).timestamp(e.getTimestamp())
                .message(e.getMessage());
        if (e.getMetadata() != null) {
            b.metadata(e.getMetadata());
        }
        return b.branch(e.getBranch()).build();
    }
```

imports 追加：`java.time.Instant`、`java.util.Optional`（若未有）。

- [ ] **Step 4: 跑测试确认绿**

Run: `mvn -pl springai-code-tui -am test -Dtest='FileSessionRepositoryTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 全 PASS（含原有用例——fork 不许碰坏既有语义）。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/session/FileSessionRepository.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/session/FileSessionRepositoryTest.java
git commit -m "feat: FileSessionRepository.fork 分叉会话（事件 sessionId 重写，源不动）"
```

---

### Task 5: `CodingAgent.switchContext` + `SubmitHandler` 会话门面

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/CodingAgent.java`（`clearContext` 重构为 `switchContext(String)` 特例 + seam 方法落地）
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/seam/SubmitHandler.java`（6 个 default 方法）
- Test: Create `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/CodingAgentSwitchContextTest.java`

**Interfaces:**
- Consumes: Task 4 的 `FileSessionRepository.fork`；自有 `sessionRepository`（`SessionRepository` 可空字段，114 行）、`sessionService`（`SessionService`，107 行）。
- Produces（seam，Task 7/8 的 UI 接线全部经此）:
  - `default String currentSessionId()` → CodingAgent 返回 volatile `sessionId`
  - `default void switchContext(String targetSessionId)` → 即 CodingAgent 的 public 方法
  - `default boolean sessionExists(String sessionId)` → `sessionRepository.findById(...).isPresent()`
  - `default Optional<String> forkSession(String sourceId)` → `instanceof FileSessionRepository` 转型调 `fork`，否则 empty
  - `default void deleteSession(String sessionId)` → `sessionRepository.delete(id)`
  - `default List<org.springframework.ai.chat.messages.Message> sessionMessages(String sessionId)` → `sessionService.getMessages(id)`
- **测试取舍（在此记录）**：6 个切换动作中，sessionId / TodoStaleReminder / TokenUsageAccumulator 三项用真对象可观察断言（accumulator 经 23 参全参构造器注入——它无 bind 方法、是 final 字段）；PermissionEngine / Interjections / GoalManager 三项由「`clearContext()` 与 `switchContext(String)` 共享同一实现体」的结构保证（`clearContext` 一行委托），不为它们造重装配——与 `CodingAgentClearContextTest` 只装配一个依赖的既有分寸一致。

- [ ] **Step 1: 写失败测试**

```java
package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.session.TokenUsageAccumulator;
import io.github.javaside.springai.codetui.agent.tools.TodoStaleReminder;
import org.junit.jupiter.api.Test;
import org.springaicommunity.agent.tools.TodoWriteTool.Todos.Status;
import org.springaicommunity.agent.tools.TodoWriteTool.Todos.TodoItem;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * switchContext：/resume 与 /clear 共用的会话切换不变量。PermissionEngine/Interjections/GoalManager
 * 三项清理由「clearContext() 一行委托 switchContext(newId())」的结构保证（同体不漂移），此处断言可观察的三项。
 */
class CodingAgentSwitchContextTest {

    private static CodingAgent agentWithAccumulator(TokenUsageAccumulator acc) {
        // 23 参全参构造器（accumulator 是 final 字段、无 bind 方法，7 参桩路径硬编码 null）。
        // skills 必须传 List.of()——null 会在构造器 List.copyOf 处 NPE；其余依赖测试不触及，传 null。
        // 参数序以 CodingAgent 全参构造器签名为准（执行时对齐，断言语义不变）。
        return new CodingAgent(null, null, null, "old-session", new AtomicLong(), null, null, null,
                List.of(), null, null, null, null, null, null, null, null, null, null, null,
                acc, 0L, null);
    }

    @Test
    void switchContext_swapsToTargetSessionId() {
        CodingAgent agent = new CodingAgent(null, null, "old-session", new AtomicLong(), null, null, null);
        agent.switchContext("20261023T000000-target1");
        assertEquals("20261023T000000-target1", agent.sessionId(), "切到指定 id 而非新 id");
    }

    @Test
    void switchContext_resetsTodoReminder() {
        CodingAgent agent = new CodingAgent(null, null, "old-session", new AtomicLong(), null, null, null);
        TodoStaleReminder reminder = new TodoStaleReminder(1);
        reminder.onControllerTodoWritten(List.of(new TodoItem("旧任务", Status.in_progress, "旧任务")));
        agent.bindTodoReminder(reminder);

        agent.switchContext("t2");

        assertNull(reminder.reminderOrNull(9L, "Bash"), "旧会话清单不吓唬新会话");
    }

    @Test
    void switchContext_resetsUsageAccumulator() {
        TokenUsageAccumulator acc = new TokenUsageAccumulator();
        // Usage 接口无静态工厂；先例见 TokenUsageAccumulatorTest（new DefaultUsage(...)）
        acc.record(new org.springframework.ai.chat.metadata.DefaultUsage(100, 20, 120, null, 0L, 0L));
        CodingAgent agent = agentWithAccumulator(acc);
        org.junit.jupiter.api.Assertions.assertNotEquals(
                TokenUsageAccumulator.Snapshot.empty(), acc.snapshot(), "前置：已记一笔用量");

        agent.switchContext("t3");

        assertEquals(TokenUsageAccumulator.Snapshot.empty(), acc.snapshot(), "换会话后用量从 0 重新累计");
    }

    @Test
    void clearContext_delegatesToSwitchContext_newIdGenerated() {
        CodingAgent agent = new CodingAgent(null, null, "old-session", new AtomicLong(), null, null, null);
        agent.clearContext();
        String after = agent.sessionId();
        assertTrue(after != null && !after.equals("old-session"), "clear 语义不变：换新 id");
    }
}
```

（注：23 参构造器的参数序与 `DefaultUsage` 的构造签名以源码为准——`DefaultUsage` 先例见 `TokenUsageAccumulatorTest`；执行时对齐参数序，**断言语义不变**。若全参构造器装配过重，允许降级为只保留前三用例并在测试类 javadoc 注明 accumulator 覆盖缺口，但不得保留「看似断言实际没绑」的假覆盖。）

- [ ] **Step 2: 跑测试确认红**

Run: `mvn -pl springai-code-tui -am test -Dtest='CodingAgentSwitchContextTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败 `cannot find symbol: switchContext`。

- [ ] **Step 3: 最小实现**

`CodingAgent`（替换原 `clearContext` 方法体，1146-1168 行区域）：

```java
    @Override
    public void clearContext() {
        switchContext(SessionIds.newId());
    }

    /**
     * 切到指定会话（{@code /resume}）：与 {@code /clear} 共用同一实现体——会话级清理共 6 个动作，
     * 两处各写一份迟早漂移。目标 null/空白回退新 id（防误传）；调用方保证空闲时调用、目标已验证存在。
     */
    public void switchContext(String targetSessionId) {
        this.sessionId = (targetSessionId == null || targetSessionId.isBlank())
                ? SessionIds.newId() : targetSessionId;
        if (usageAccumulator != null) {
            usageAccumulator.reset();   // 换会话，缓存命中率从 0 重新累计
        }
        if (permissionEngine != null) {
            permissionEngine.clearSessionRules();   // 「允许，本会话不再问」的作用域是授权那一刻的会话
        }
        if (interjections != null) {
            interjections.drainForRefill();   // 那些话是冲着旧会话说的
        }
        if (goalManager != null) {
            goalManager.clear("clear-context");   // reason 沿用 /clear 同串：不引入无谓 diff，也不绑定测试
        }
        if (todoReminder != null) {
            todoReminder.reset();   // 旧清单不该吓唬新会话
        }
    }

    // ── SubmitHandler 会话门面落地（/resume 面板与 /fork 的 UI 接线；桩路径 null 依赖全降级） ──

    @Override
    public String currentSessionId() { return sessionId; }

    @Override
    public boolean sessionExists(String sessionId) {
        return sessionRepository != null && sessionId != null
                && sessionRepository.findById(sessionId).isPresent();
    }

    @Override
    public java.util.Optional<String> forkSession(String sourceId) {
        // DefaultSessionService 不暴露 fork（同 replaceEvents 的先例），仓库实现才有；测试可直接注入 FileSessionRepository
        return (sessionRepository instanceof FileSessionRepository fsr)
                ? fsr.fork(sourceId) : java.util.Optional.empty();
    }

    @Override
    public void deleteSession(String sessionId) {
        if (sessionRepository != null && sessionId != null) {
            sessionRepository.delete(sessionId);
        }
    }

    @Override
    public List<Message> sessionMessages(String sessionId) {
        return (sessionService == null || sessionId == null) ? List.of() : sessionService.getMessages(sessionId);
    }
```

`SubmitHandler` 追加（对齐既有 default 方法 + javadoc 风格）：

```java
    // ── 会话管理（/resume 面板与 /fork 用；默认空实现，便于回显桩/测试桩省略） ──

    /** 当前会话 id（★ 行标记与 /fork 源）。 */
    default String currentSessionId() { return ""; }

    /** 切到指定会话（与 {@link #clearContext()} 同一套会话级清理）。调用方保证空闲时调用。 */
    default void switchContext(String targetSessionId) { }

    /** 目标会话是否存在（含损坏文件视为不存在）。恢复前验证用。 */
    default boolean sessionExists(String sessionId) { return false; }

    /** 分叉：复制源会话事件到新 id 并返回之；源空/不存在返回 empty。 */
    default java.util.Optional<String> forkSession(String sourceId) { return java.util.Optional.empty(); }

    /** 删除一个会话（文件尽力删除；调用方以重新 scan 复核真实结果）。 */
    default void deleteSession(String sessionId) { }

    /** 指定会话的消息列表（恢复回放用；等价启动期 getMessages）。 */
    default List<org.springframework.ai.chat.messages.Message> sessionMessages(String sessionId) { return List.of(); }
```

（若 `SubmitHandler` 已 import `java.util.Optional` 与 `Message` 则去掉全限定名；`CodingAgent` 的 `switchContext` 加 `@Override`；`CodingAgent` 需补 import `agent.session.FileSessionRepository`——`instanceof` 模式匹配用到。）

- [ ] **Step 4: 跑新旧测试确认绿**

Run: `mvn -pl springai-code-tui -am test -Dtest='CodingAgentSwitchContextTest,CodingAgentClearContextTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 全 PASS（`CodingAgentClearContextTest` 不动 = clear 语义未变）。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/CodingAgent.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/seam/SubmitHandler.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/CodingAgentSwitchContextTest.java
git commit -m "feat: CodingAgent.switchContext 与 SubmitHandler 会话门面（/clear 同体重构）"
```

---

### Task 6: `--resume <id>` 启动参数

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/CodeTuiApplication.java`（解析纯函数 + main 接线 88-94 区域 + 帮助文本 364-377 区域）
- Test: Create `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ResumeStartupTest.java`（对齐 `PermissionStartupTest` 同包先例）

**Interfaces:**
- Consumes: Task 2 的 `SessionCatalog.scan` + `SessionSummary.id()`；既有 `hasContinueFlag`、`latestSessionId`。
- Produces: 包级 static 纯函数 `resumeFlagValue(String[] args) -> String`（null = 未给或缺值）、`resolveResumeTarget(List<String> candidateIds, String input) -> String`（null = 零/多命中）。

- [ ] **Step 1: 写失败测试**

```java
package io.github.javaside.springai.codetui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** --resume 解析与前缀匹配（纯函数；main 只做接线与报错退出）。 */
class ResumeStartupTest {

    @Test
    void resumeFlagValue_readsBothSpellings_andTreatsDashAsMissingValue() {
        assertEquals("abc123", CodeTuiApplication.resumeFlagValue(new String[]{"--resume", "abc123"}));
        assertEquals("abc123", CodeTuiApplication.resumeFlagValue(new String[]{"--resume=abc123"}));
        assertNull(CodeTuiApplication.resumeFlagValue(new String[]{"--resume", "--permission-mode"}),
                "下一个参数以 - 开头 → 当缺值（比 permission-mode 先例的 -- 前缀更宽，防 --resume -c 把 -c 当 id）");
        assertNull(CodeTuiApplication.resumeFlagValue(new String[]{"--resume"}), "尾部缺值");
        assertNull(CodeTuiApplication.resumeFlagValue(new String[]{"-c", "--continue"}), "无 flag");
    }

    @Test
    void resolveResumeTarget_exactBeatsPrefix_uniquePrefixOk_ambiguousOrNoneNull() {
        List<String> ids = List.of("20260101T000000-aaaaaa", "20260101T000000-bbbbbb", "20260202T000000-aaaaaa");
        assertEquals("20260202T000000-aaaaaa",
                CodeTuiApplication.resolveResumeTarget(ids, "20260202T000000-aaaaaa"), "精确命中");
        assertEquals("20260101T000000-aaaaaa",
                CodeTuiApplication.resolveResumeTarget(ids, "20260101T000000-a"), "唯一前缀");
        assertNull(CodeTuiApplication.resolveResumeTarget(ids, "20260101T000000"), "多命中 → null（不猜，让用户补长）");
        assertNull(CodeTuiApplication.resolveResumeTarget(ids, "2099"), "零命中 → null");
        assertNull(CodeTuiApplication.resolveResumeTarget(List.of(), "x"), "空候选");
    }
}
```

- [ ] **Step 2: 跑测试确认红**

Run: `mvn -pl springai-code-tui -am test -Dtest='ResumeStartupTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败 `cannot find symbol: resumeFlagValue`。

- [ ] **Step 3: 最小实现 + main 接线**

纯函数（放 `hasContinueFlag` 旁，对齐 `permissionModeValue` 的注释纪律）：

```java
    /** 提取 {@code --resume <id>} / {@code --resume=<id>} 的值；未给或下一个参数以 - 开头（视为选项、缺值）返回 null。 */
    static String resumeFlagValue(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--resume")) {
                if (i + 1 >= args.length) {
                    log.warn("--resume 缺少会话 id 参数。");
                    return null;
                }
                if (args[i + 1].startsWith("-")) {
                    // 比 --permission-mode 的 -- 前缀更宽：单横线 -c 也是选项，否则「--resume -c」会把 -c 当 id、
                    // 再被冲突分支兜住，报错误导（用户以为 -c 是个会话名）。
                    log.warn("--resume 后面跟的是另一个选项（{}），当作没给值处理。", args[i + 1]);
                    return null;
                }
                return args[i + 1];
            }
            if (a.startsWith("--resume=")) {
                return a.substring("--resume=".length());
            }
        }
        return null;
    }

    /** 在合法 stem 集合内解析目标：精确命中优先，其次唯一前缀；零/多命中返回 null（调用方报错，不猜）。 */
    static String resolveResumeTarget(List<String> candidateIds, String input) {
        if (input == null || input.isBlank() || candidateIds.isEmpty()) {
            return null;
        }
        if (candidateIds.contains(input)) {
            return input;
        }
        List<String> matches = candidateIds.stream().filter(id -> id.startsWith(input)).toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }
```

main 接线（替换 88-94 的会话选择块；`latest` 计算保留）：

```java
         Path sessionsDir = root.resolve(".codetui").resolve("sessions");
         boolean wantContinue = hasContinueFlag(args);
         String resumeValue = resumeFlagValue(args);
         if (resumeValue != null && wantContinue) {
             System.err.println("错误：--resume 与 -c/--continue 不能同时使用（两个都是恢复，请只给一个）。");
             System.exit(1);
         }
         List<SessionCatalog.SessionSummary> catalog = SessionCatalog.scan(sessionsDir);
         String resumeTarget = null;
         if (resumeValue != null) {
             resumeTarget = resolveResumeTarget(
                     catalog.stream().map(SessionCatalog.SessionSummary::id).toList(), resumeValue);
             if (resumeTarget == null) {
                 List<String> hits = catalog.stream().map(SessionCatalog.SessionSummary::id)
                         .filter(id -> id.startsWith(resumeValue)).toList();
                 if (hits.isEmpty()) {
                     System.err.println("错误：没有匹配 \"" + resumeValue + "\" 的会话。最近 10 个：");
                     catalog.stream().limit(10).forEach(s -> System.err.println("  " + s.id()));
                 } else {
                     System.err.println("错误：\"" + resumeValue + "\" 匹配多个会话，请补长前缀：");
                     hits.forEach(id -> System.err.println("  " + id));
                 }
                 System.exit(1);
             }
         }
         Optional<String> latest = FileSessionRepository.latestSessionId(sessionsDir);
         boolean resumed = resumeTarget != null || (wantContinue && latest.isPresent());
         String sessionId = resumeTarget != null ? resumeTarget
                 : (resumed ? latest.get() : SessionIds.newId());
```

帮助文本（usage 区 `-c` 行后）追加：

```text
   --resume <会话id前缀>            恢复指定会话（id 前缀唯一即可；与 -c 互斥）
```

imports：`SessionCatalog`、`java.util.List`（若未有）。

- [ ] **Step 4: 跑测试确认绿 + 全类回归**

Run: `mvn -pl springai-code-tui -am test -Dtest='ResumeStartupTest,PermissionStartupTest,StartupHelpTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 全 PASS（`PermissionStartupTest` 解析区未被碰坏；`StartupHelpTest.usageCoversAllFlags` 是「帮助不撒谎」的守门测试，新增 `--resume` 行必须过它）。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/CodeTuiApplication.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ResumeStartupTest.java
git commit -m "feat: --resume <id> 启动参数（前缀匹配，与 -c 互斥报错）"
```

---

### Task 7: `/resume` 面板骨架（打开/导航/后台摘要）

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java`（字段区 ~320、COMMANDS 表 ~373-390、scope 区 ~648、命令链 ~2472 后、键分派 ~2109、statusLine ~4498、面板方法区文件尾部 /tasks 面板后）
- Test: Create `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/CodeTuiViewSessionsPanelTest.java`

**Interfaces:**
- Consumes: Task 2/3 的 `SessionCatalog`；Task 5 的 seam `currentSessionId()`；既有 `clampIndex`、`PICK_TITLE/PICK_SEL/PICK_DESC`、`publishLocalViewChange`、`KeyEvent.ofKey`。
- Produces: `pickingSessions` 面板态 + `openSessionsPanel()` + `onSessionsPanelKey`（本任务只处理 ↑↓/Esc/吞键，**Enter/f/d 留 Task 8**）+ `sessionsPanelChildren()` + `sessionsForTest()/sessionIndexForTest()/pickingSessionsForTest()` 测试钩子。

- [ ] **Step 1: 写失败测试**

```java
package io.github.javaside.springai.codetui.ui;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import io.github.javaside.springai.codetui.agent.seam.SubmitHandler;
import io.github.javaside.springai.codetui.agent.session.SessionCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * /resume 会话面板：打开即列会话（id+时间），摘要后台补齐；↑↓ 导航、Esc 关闭。
 * 断言落在 ViewScreen 回读的屏幕文本上（同 CodeTuiViewTasksPanelTest 的纪律）。
 */
class CodeTuiViewSessionsPanelTest {

    /** 记录型桩：currentSessionId 可编程（★ 行判定用）；root 在 Task 8 扩展 deleteSession 真删时用。 */
    private static final class SessionsStub implements SubmitHandler {
        final Path root;
        String currentId = "20260102T000000-cur999";
        final java.util.List<String> switchedTo = new ArrayList<>();

        SessionsStub(Path root) { this.root = root; }

        @Override public reactor.core.Disposable submit(String text) { return null; }
        @Override public String currentSessionId() { return currentId; }
        @Override public void switchContext(String targetSessionId) { switchedTo.add(targetSessionId); }
    }

    private static Path writeSession(Path root, String id, String firstUserJson) throws IOException {
        Path dir = root.resolve(".codetui").resolve("sessions");
        Files.createDirectories(dir);
        Path f = dir.resolve(id + ".json");
        Files.writeString(f,
                "{\"events\":[{\"messageType\":\"USER\",\"content\":\"" + firstUserJson + "\"}]}");
        return f;   // 返回文件路径：删除用例（Task 8）要拿它断言真删
    }

    private static CodeTuiView openPanel(ConversationState state, SessionsStub stub, Path root) {
        CodeTuiView v = new CodeTuiView(state, stub, root);
        v.setInputForTest("/resume");
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
        return v;
    }

    @Test
    @DisplayName("/resume 秒开会话列表，★ 标当前会话；忙碌时拒绝打开")
    void opensPanelInstantly_withCurrentMarked_busyRejected(@TempDir Path root) throws IOException {
        writeSession(root, "20260101T000000-aaaaaa", "修 NPE");
        writeSession(root, "20260102T000000-cur999", "当前这个");
        ConversationState state = new ConversationState();
        CodeTuiView v = openPanel(state, new SessionsStub(root), root);

        assertTrue(v.pickingSessionsForTest(), "面板应已打开");
        String screen = ViewScreen.of(v);
        // 时间行渲染的是本地时区的 MM-dd HH:mm（sessionTime 换算），不是 id 原文——
        // 期望串用与实现同口径的 UTC→本地换算生成，勿硬编码（时区在西半球会差一天）
        String expectedTime = java.time.LocalDateTime.parse("20260101T000000",
                        java.time.format.DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss"))
                .atZone(java.time.ZoneOffset.UTC).withZoneSameInstant(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"));
        assertTrue(screen.contains(expectedTime), "时间来自 id 前缀换算，实际：\n" + screen);
        assertTrue(screen.contains("★"), "当前会话有 ★ 标记");

        // 忙碌闸门：回合中 /resume 拒绝（同 /clear——动 sessionId 不能与回合并发）
        ConversationState busy = new ConversationState();
        busy.onTurnStarted(1L);
        CodeTuiView v2 = new CodeTuiView(busy, new SessionsStub(root), root);
        v2.setInputForTest("/resume");
        v2.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
        assertFalse(v2.pickingSessionsForTest(), "忙碌不开面板");
        assertTrue(busy.notice().contains("忙碌"), "拒绝要说出来：" + busy.notice());
    }

    @Test
    @DisplayName("摘要由后台线程补齐（digest null → 非 null），面板已关则丢弃")
    void digestsFilledInBackground(@TempDir Path root) throws IOException {
        writeSession(root, "20260103T000000-dddddd", "后台补摘要测试");
        CodeTuiView v = openPanel(new ConversationState(), new SessionsStub(root), root);
        // 不对「打开瞬间 digest==null」断言：单文件解析毫秒级，daemon 线程可能在断言前就完成（设计自带竞态，两态都合法）。
        // 决定性断言在轮询后的最终值。

        long deadline = System.currentTimeMillis() + 2000;
        while (v.sessionsForTest().isEmpty() || v.sessionsForTest().get(0).digest() == null) {
            if (System.currentTimeMillis() > deadline) {
                break;
            }
            Thread.onSpinWait();
        }
        assertEquals("后台补摘要测试", v.sessionsForTest().get(0).digest(), "2s 内后台补齐");
    }

    @Test
    @DisplayName("↑↓ 循环移动、Esc 关闭；空目录也照常打开（面板说明比不弹有用）")
    void navigatesAndCloses_emptyDirStillOpens(@TempDir Path root) throws IOException {
        writeSession(root, "20260101T000000-aaaaaa", "one");
        writeSession(root, "20260102T000000-bbbbbb", "two");
        CodeTuiView v = openPanel(new ConversationState(), new SessionsStub(root), root);
        int n = v.sessionsForTest().size();
        assertTrue(n >= 2);
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.UP));
        int afterUp = v.sessionIndexForTest();
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.DOWN));
        assertEquals((afterUp + 1) % n, v.sessionIndexForTest(), "DOWN 在 UP 之后循环回来");
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ESCAPE));
        assertFalse(v.pickingSessionsForTest(), "Esc 关闭");

        CodeTuiView empty = openPanel(new ConversationState(), new SessionsStub(root), root.getParent());
        assertTrue(empty.pickingSessionsForTest(), "空目录照常打开");
        assertTrue(ViewScreen.of(empty).contains("暂无历史会话"));
        // 空列表下按 ↑↓ 不崩（防除零），Esc 可关
        empty.feedKeyForTest(KeyEvent.ofKey(KeyCode.UP));
        empty.feedKeyForTest(KeyEvent.ofKey(KeyCode.ESCAPE));
        assertFalse(empty.pickingSessionsForTest());
    }
}
```

- [ ] **Step 2: 跑测试确认红**

Run: `mvn -pl springai-code-tui -am test -Dtest='CodeTuiViewSessionsPanelTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败 `cannot find symbol: pickingSessionsForTest`。

- [ ] **Step 3: 最小实现（CodeTuiView 五处接线 + 面板方法）**

字段区（`pickingTasks` 旁）：

```java
    private boolean pickingSessions;                   // /resume 会话面板是否激活
    private int sessionIndex;                          // 会话面板高亮项
    /** 会话列表快照：打开时 scan 灌入，后台摘要线程逐条补 digest 后 volatile 整体替换（面板关则丢弃迟到结果）。 */
    private volatile List<SessionCatalog.SessionSummary> sessionList = List.of();
```

COMMANDS 表（`/permissions` 行后，`/resume` 与 `/reload` 互非前缀、无排序约束）：

```java
            new SlashCommand("/resume",  "恢复 / 分叉 / 删除历史会话"),
```

命令链（`/clear` 分支后）：

```java
        if (cmd.equals("/resume")) {                 // 会话管理面板：恢复/分叉/删除
            clearInput();
            if (state.isBusy()) {                    // 闸门同 /clear：动 sessionId 不能与回合/压缩/模态并发
                state.setNotice("忙碌中，无法切换会话");
                return;
            }
            openSessionsPanel();
            return;
        }
```

scope 区（`/tasks` 面板行后）：

```java
                scope(pickingSessions, sessionsPanelChildren()),   // /resume 会话面板
```

键分派（`pickingTasks` 分派后）：

```java
        if (pickingSessions) return onSessionsPanelKey(k); // 会话面板同理
```

面板方法（文件尾部 `/tasks` 面板方法后）：

```java
    // ── /resume 会话面板 ────────────────────────────────────────────────

    /** 打开面板：scan（瞬时）立即显示，摘要后台补齐（192 个文件的流式解析不占按键线程，模式同 MCP 后台连接）。 */
    private void openSessionsPanel() {
        Path sessionsDir = root.resolve(".codetui").resolve("sessions");
        List<SessionCatalog.SessionSummary> scanned = SessionCatalog.scan(sessionsDir);
        sessionList = scanned;
        sessionIndex = 0;
        pickingSessions = true;
        if (scanned.isEmpty()) {
            return;   // 空列表照常打开（同 /tasks：说明从哪来比什么都不弹有用）
        }
        Thread t = new Thread(() -> fillSessionDigests(sessionsDir, scanned), "session-digests");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 逐文件补摘要。每解析一条就整体替换快照并请求重绘（摘要渐进出现，渲染由既有合并机制兜住）；
     * {@code !pickingSessions} 即停——面板已关，迟到结果回写只会复活一个看不见的列表。
     */
    private void fillSessionDigests(Path sessionsDir, List<SessionCatalog.SessionSummary> scanned) {
        List<SessionCatalog.SessionSummary> filled = new ArrayList<>(scanned.size());
        for (SessionCatalog.SessionSummary s : scanned) {
            if (!pickingSessions) {
                return;
            }
            String digest = SessionCatalog.firstUserDigest(sessionsDir.resolve(s.id() + ".json"));
            filled.add(new SessionCatalog.SessionSummary(s.id(), s.mtimeMillis(), s.sizeBytes(), digest));
            sessionList = List.copyOf(filled);
            publishLocalViewChange();
        }
    }

    /**
     * 面板按键：本任务只处理 ↑↓/Esc；Enter（恢复）/f（分叉）/d（删除）在下一任务补。
     * 始终 HANDLED（屏蔽文本编辑），空列表防除零——同 /tasks 纪律。
     */
    private EventResult onSessionsPanelKey(KeyEvent k) {
        if (k.isCancel()) { pickingSessions = false; return EventResult.HANDLED; }
        int n = sessionList.size();
        if (n == 0) return EventResult.HANDLED;
        sessionIndex = clampIndex(sessionIndex, n);
        if (k.code() == KeyCode.UP)   { sessionIndex = (sessionIndex - 1 + n) % n; return EventResult.HANDLED; }
        if (k.code() == KeyCode.DOWN) { sessionIndex = (sessionIndex + 1) % n; return EventResult.HANDLED; }
        return EventResult.HANDLED;               // 其余键一律吞掉（Enter/f/d 到来前也不落进输入框）
    }

    /** 面板渲染：标题 + 每会话一行（★ 当前 · 本地时间 · 摘要）。模态前置时收起（同 /tasks）。 */
    private Element[] sessionsPanelChildren() {
        if (!pickingSessions) return new Element[0];
        if (activePermission != null || activePlan != null || activeAsk != null) return new Element[0];
        List<SessionCatalog.SessionSummary> sessions = sessionList;
        List<Element> els = new ArrayList<>();
        els.add(text("  ↺ 会话（↑↓ 选择 · Esc 关闭）").style(PICK_TITLE));
        if (sessions.isEmpty()) {
            els.add(text("    （暂无历史会话）开始对话后会保存在 .codetui/sessions/。").style(PICK_DESC));
            return els.toArray(new Element[0]);
        }
        String current = onSubmit.currentSessionId();
        int sel = clampIndex(sessionIndex, sessions.size());
        for (int i = 0; i < sessions.size(); i++) {
            SessionCatalog.SessionSummary s = sessions.get(i);
            String digest = (s.digest() == null) ? "（无对话）" : s.digest();
            String line = (s.id().equals(current) ? "★ " : "  ") + sessionTime(s.id()) + " · " + digest;
            els.add(text(line).style(i == sel ? PICK_SEL : PICK_DESC));
        }
        return els.toArray(new Element[0]);
    }

    /** id 前 15 字符是 UTC `uuuuMMdd'T'HHmmss`（SessionIds 约定）；显示转本地时区。解析失败回退原样（外部改名过的 id）。 */
    private static String sessionTime(String id) {
        if (id.length() < 15) return id;
        try {
            return java.time.LocalDateTime.parse(id.substring(0, 15),
                            DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss"))
                    .atZone(ZoneOffset.UTC).withZoneSameInstant(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"));
        } catch (RuntimeException e) {
            return id;
        }
    }

    /** 面板态状态行（自己回显 notice——statusLine 的面板分支早于通用 notice 分支 return，同 /permissions 的老账）。 */
    String sessionsStatusText() {
        String notice = state.notice();
        if (notice != null && !notice.isEmpty()) return notice + " · Esc 关闭";
        return "↺ 会话 · ↑↓ 选择 · Esc 关闭";
    }

    // 测试钩子
    boolean pickingSessionsForTest() { return pickingSessions; }
    List<SessionCatalog.SessionSummary> sessionsForTest() { return sessionList; }
    int sessionIndexForTest() { return sessionIndex; }
```

statusLine 的 `pickingTasks` 分支（~4498）后追加：

```java
        if (pickingSessions) return text(sessionsStatusText()).style(THINK);
```

imports：`SessionCatalog`、`java.time.LocalDateTime`、`java.time.ZoneId`、`java.time.ZoneOffset`、`java.time.format.DateTimeFormatter`（以文件现有 import 为准，缺则补）。

- [ ] **Step 4: 跑测试确认绿 + 面板类回归**

Run: `mvn -pl springai-code-tui -am test -Dtest='CodeTuiViewSessionsPanelTest,CodeTuiViewTasksPanelTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 全 PASS。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/CodeTuiViewSessionsPanelTest.java
git commit -m "feat: /resume 会话面板骨架（秒开列表 + 后台摘要 + 导航）"
```

---

### Task 8: 恢复 / 分叉 / 删除动作 + `/fork` 命令

**Files:**
- Modify: `ui/ConversationState.java`（`replayHistory` 加 header 重载）
- Modify: `ui/CodeTuiView.java`（`onSessionsPanelKey` 补 Enter/f/d + 确认态、`switchToSession` 序列、`/fork` 命令与 COMMANDS 行、`sessionPendingDelete` 字段）
- Test: `CodeTuiViewSessionsPanelTest.java`（补用例 + 桩扩展）

**Interfaces:**
- Consumes: Task 5 seam 全套（`switchContext/sessionExists/forkSession/deleteSession/sessionMessages/currentSessionId/killAllBackgroundTasks/currentModel`）；Task 7 面板态。
- Produces: `ConversationState.replayHistory(List<Message>, String header)`（单参重载保留默认文案，既有调用方零改动）；`switchToSession(String targetId, String sourceIdForNotice)`（私有，恢复/分叉共用序列）。

- [ ] **Step 1: 补失败测试（桩扩展 + 三个用例追加到 `CodeTuiViewSessionsPanelTest`）**

桩扩展（替换 Task 7 的 `SessionsStub`；**deleteSession 真删文件**——`confirmDeleteSession` 以真实目录重扫复核，桩只记录不删会让「删除失败（文件仍在）」路径永远走到、列表断言反着写）：

```java
    private static final class SessionsStub implements SubmitHandler {
        String currentId = "20260102T000000-cur999";
        boolean exists = true;
        java.util.Optional<String> forkResult = java.util.Optional.of("20260999T000000-forked1");
        final Path root;   // 持有 root：deleteSession 真删文件
        final java.util.List<String> switchedTo = new ArrayList<>();
        final java.util.List<String> deleted = new ArrayList<>();
        final java.util.concurrent.atomic.AtomicInteger killAlls = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.List<org.springframework.ai.chat.messages.Message> messages =
                List.of(new org.springframework.ai.chat.messages.UserMessage("历史第一句"));

        SessionsStub(Path root) { this.root = root; }

        @Override public reactor.core.Disposable submit(String text) { return null; }
        @Override public String currentSessionId() { return currentId; }
        @Override public void switchContext(String t) { switchedTo.add(t); }
        @Override public boolean sessionExists(String id) { return exists; }
        @Override public java.util.Optional<String> forkSession(String sourceId) { return forkResult; }
        @Override public void deleteSession(String id) {
            deleted.add(id);
            try {
                java.nio.file.Files.deleteIfExists(
                        root.resolve(".codetui/sessions/" + id + ".json"));
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        }
        @Override public java.util.List<org.springframework.ai.chat.messages.Message> sessionMessages(String id) { return messages; }
        @Override public void killAllBackgroundTasks() { killAlls.incrementAndGet(); }
        @Override public String currentModel() { return "test-model"; }
    }
```

（桩构造签名从无参变为 `(Path root)`：Task 7 既有用例里的 `new SessionsStub(root)` **同步改为** `new SessionsStub(root)`——各用例的 `@TempDir Path root` 就在手边；`openPanel` 辅助方法已经透传 root，不受影响。）

用例：

```java
    @Test
    @DisplayName("Enter 恢复：验证存在→杀后台任务→切换→回放；目标不存在则不切；执行时变忙则拒绝且面板保持")
    void enterRestores_withRecheckAndValidation(@TempDir Path root) throws IOException {
        writeSession(root, "20260101T000000-aaaaaa", "修 NPE");
        SessionsStub stub = new SessionsStub(root);
        CodeTuiView v = openPanel(new ConversationState(), stub, root);
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));

        assertTrue(stub.switchedTo.contains("20260101T000000-aaaaaa"), "切到选中会话");
        assertTrue(stub.killAlls.get() >= 1, "切换前杀后台任务（同 /clear）");
        assertFalse(v.pickingSessionsForTest(), "恢复后面板关闭");
        // 回放断言：replayHistory 把消息下沉输出队列——从 state 排水回读（不依赖渲染线程）。
        // 恒真写法（`|| switchedTo.size()==1`）杀不死「删掉 replayHistory 调用」的变异，禁用。
        assertTrue(ViewScreen.of(v).contains("历史第一句"),
                "回放把 sessionMessages 的历史渲出来，实际：\n" + ViewScreen.of(v));

        // 目标不存在：不切换、有提示（先验证后动手——防半切状态）
        SessionsStub missing = new SessionsStub(root);
        missing.exists = false;
        CodeTuiView v2 = openPanel(new ConversationState(), missing, root);
        v2.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
        assertTrue(missing.switchedTo.isEmpty(), "没验证过就不切");
        assertTrue(missing.killAlls.get() == 0, "都没换会话，凭什么杀任务");

        // 面板开着、按 Enter 时变忙（goal 自动轮/排队出队可自发启动回合）：拒绝且面板保持
        ConversationState busyLater = new ConversationState();
        CodeTuiView v3 = openPanel(busyLater, new SessionsStub(root), root);
        busyLater.onTurnStarted(1L);   // 打开面板【之后】起回合
        v3.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
        assertTrue(v3.pickingSessionsForTest(), "拒绝时面板保持");
    }

    @Test
    @DisplayName("f 分叉选中项 / /fork 分叉当前：切到新 id，提示源自原会话")
    void forkFromPanelAndCommand(@TempDir Path root) throws IOException {
        writeSession(root, "20260101T000000-aaaaaa", "修 NPE");
        SessionsStub stub = new SessionsStub(root);
        CodeTuiView v = openPanel(new ConversationState(), stub, root);
        v.feedKeyForTest(KeyEvent.ofChar('f'));   // 字符键用 ofChar（KeyCode 无单字母常量；先例 CodeTuiViewTasksPanelTest 的 'k' 键）
        assertTrue(stub.switchedTo.contains("20260999T000000-forked1"), "切到 fork 出的新会话");
        assertFalse(v.pickingSessionsForTest());

        // /fork：源是当前会话；空会话 fork 被拒（提示而非静默）
        SessionsStub emptyFork = new SessionsStub(root);
        emptyFork.forkResult = java.util.Optional.empty();
        CodeTuiView v2 = new CodeTuiView(new ConversationState(), emptyFork, root);
        v2.setInputForTest("/fork");
        v2.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
        assertTrue(emptyFork.switchedTo.isEmpty(), "空会话不切换");
    }

    @Test
    @DisplayName("d 删除两段式：当前会话不可删；确认后真删文件并以重新 scan 复核")
    void deleteTwoPhase_currentRejected(@TempDir Path root) throws IOException {
        Path a = writeSession(root, "20260101T000000-aaaaaa", "修 NPE");
        writeSession(root, "20260102T000000-cur999", "当前");
        // mtime 定序：细粒度文件系统上两文件可能同毫秒，scan 的降序结果不稳定——显式钉死
        Files.setLastModifiedTime(a, java.nio.file.attribute.FileTime.from(java.time.Instant.parse("2026-01-01T00:00:00Z")));
        Files.setLastModifiedTime(root.resolve(".codetui/sessions/20260102T000000-cur999.json"),
                java.nio.file.attribute.FileTime.from(java.time.Instant.parse("2026-01-02T00:00:00Z")));
        SessionsStub stub = new SessionsStub(root);   // 桩持有 root：deleteSession 真删文件（复核走真实目录）
        ConversationState state = new ConversationState();
        CodeTuiView v = openPanel(state, stub, root);

        // index=0 是较新的 cur999（★ 当前会话）——按 d：拒绝且说出来
        v.feedKeyForTest(KeyEvent.ofChar('d'));
        assertTrue(stub.deleted.isEmpty(), "当前会话不可删");
        assertTrue(state.notice().contains("不能删除当前会话"), "拒绝要有 notice：" + state.notice());

        // 下移到 aaaaaa 按 d：进确认态；Esc 取消
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.DOWN));
        v.feedKeyForTest(KeyEvent.ofChar('d'));
        assertTrue(ViewScreen.of(v).contains("确认删除"), "确认态替换列表");
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ESCAPE));
        assertTrue(stub.deleted.isEmpty(), "Esc 取消不删");

        // 再 d + Enter：真删——文件被删，重新 scan 后列表不含该项
        v.feedKeyForTest(KeyEvent.ofChar('d'));
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
        assertEquals(java.util.List.of("20260101T000000-aaaaaa"), stub.deleted);
        assertFalse(Files.exists(a), "桩已真删文件");
        assertFalse(v.sessionsForTest().stream().anyMatch(s -> s.id().equals("20260101T000000-aaaaaa")),
                "删除后以重新 scan 的真实结果刷新列表");
    }
}
```

（`writeSession` 的返回值改为 `Path`：Task 7 定义时返回文件路径，本用例要用。）
（注：桩扩展为 `new SessionsStub(root)`——见 Step 1 桩定义中 `deleteSession` 的真删实现；字符键一律 `KeyEvent.ofChar('d'/'f')`，先例 `CodeTuiViewTasksPanelTest` 的 `k` 键。）

- [ ] **Step 2: 跑测试确认红**

Run: `mvn -pl springai-code-tui -am test -Dtest='CodeTuiViewSessionsPanelTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 新用例 FAIL（Enter/f/d 被吞、无切换调用）。

- [ ] **Step 3: 最小实现**

`ConversationState.replayHistory` 改双入口（原方法体不动，只参数化头行）：

```java
    public void replayHistory(List<Message> messages) {
        replayHistory(messages, "已恢复上次会话");
    }

    /** header 参数化：/resume 面板恢复任意会话时「上次会话」措辞不准（启动 -c 与运行期 /resume /fork 分歧）。 */
    public void replayHistory(List<Message> messages, String header) {
        // ……原方法体，唯一改动：pending.add(new OutputLine("↺ " + header + "（" + HistoryReplay.userTurns(messages) + " 轮对话）", OutputLine.Kind.INFO));
    }
```

`CodeTuiView`：

字段区补一行：`private String sessionPendingDelete;   // d 键删除确认态（null=非确认）`

`onSessionsPanelKey` 扩展（完整替换 Task 7 版本）：

```java
    /**
     * 面板按键：↑↓ 移动、Enter 恢复、f 分叉、d 删除（两段式）、Esc 关闭/取消确认。
     * 始终 HANDLED；空列表防除零。删除确认态只认 Enter/Esc（同 /permissions 的 d 键两段式）。
     */
    private EventResult onSessionsPanelKey(KeyEvent k) {
        if (sessionPendingDelete != null) {
            if (k.isCancel()) { sessionPendingDelete = null; return EventResult.HANDLED; }
            if (k.code() == KeyCode.ENTER || k.isChar('\r') || k.isChar('\n')) {
                confirmDeleteSession();
                return EventResult.HANDLED;
            }
            return EventResult.HANDLED;
        }
        if (k.isCancel()) { pickingSessions = false; return EventResult.HANDLED; }
        int n = sessionList.size();
        if (n == 0) return EventResult.HANDLED;
        sessionIndex = clampIndex(sessionIndex, n);
        if (k.code() == KeyCode.UP)   { sessionIndex = (sessionIndex - 1 + n) % n; return EventResult.HANDLED; }
        if (k.code() == KeyCode.DOWN) { sessionIndex = (sessionIndex + 1) % n; return EventResult.HANDLED; }
        if (k.code() == KeyCode.ENTER || k.isChar('\r') || k.isChar('\n')) {
            String id = sessionList.get(sessionIndex).id();
            // 恢复序列步骤 1（先验证后动手）：验证都没过、会话都没换，凭什么杀任务（对齐 /clear 注释原则）
            if (!onSubmit.sessionExists(id)) {
                state.setNotice("会话不存在或已损坏：" + id);
                return EventResult.HANDLED;
            }
            switchToSession(id, null);
            return EventResult.HANDLED;
        }
        if (k.isChar('f') || k.isChar('F')) {
            String sourceId = sessionList.get(sessionIndex).id();
            java.util.Optional<String> forked = onSubmit.forkSession(sourceId);
            if (forked.isEmpty()) {
                state.setNotice("无法分叉（会话为空或不存在）");
                return EventResult.HANDLED;
            }
            switchToSession(forked.get(), sourceId);
            return EventResult.HANDLED;
        }
        if (k.isChar('d') || k.isChar('D')) {
            String id = sessionList.get(sessionIndex).id();
            if (id.equals(onSubmit.currentSessionId())) {
                // 删掉当前 id 后下一回合 SessionMemoryAdvisor 会按 id 重建空文件——行为诡异且不可解
                state.setNotice("不能删除当前会话");
                return EventResult.HANDLED;
            }
            sessionPendingDelete = id;   // 只是「请求」，真删在 confirmDeleteSession
            return EventResult.HANDLED;
        }
        return EventResult.HANDLED;
    }

    /**
     * 确认删除后：删除、重新 scan 复核、如实回报。复核以 scan 为准——deleteFile 吞掉的 IO 失败
     * （文件仍在）装成功是撒谎；列表反映磁盘真实状态。
     */
    private void confirmDeleteSession() {
        String id = sessionPendingDelete;
        sessionPendingDelete = null;
        if (id == null) return;
        onSubmit.deleteSession(id);
        List<SessionCatalog.SessionSummary> re =
                SessionCatalog.scan(root.resolve(".codetui").resolve("sessions"));
        boolean gone = re.stream().noneMatch(s -> s.id().equals(id));
        sessionList = re;
        sessionIndex = clampIndex(sessionIndex, re.size());
        state.setNotice(gone ? "已删除会话 " + id : "删除失败（文件仍在）：" + id);
    }

    /**
     * 恢复/分叉共用的切换序列（spec 恢复序列步骤 0-7，严格对齐 /clear 完整模板）。
     * sourceIdForNotice 非 null = 分叉路径（头行说明源自哪个会话）。
     */
    private void switchToSession(String targetId, String sourceIdForNotice) {
        // 步骤 0：复检。面板「打开」到「按 Enter」之间，goal 自动轮与排队消息出队都能无按键地
        // 自发启动回合——此时切 volatile sessionId 正是 implementation-map §7 警告的竞态。
        if (state.isBusy()) {
            state.setNotice("忙碌中，无法切换会话");
            return;
        }
        pickingSessions = false;
        onSubmit.killAllBackgroundTasks();      // 步骤 2：切走前杀旧会话的后台任务
        onSubmit.switchContext(targetId);       // 步骤 3：换 id + 会话级清理（与 /clear 同体）
        state.resetForNewSession();             // 步骤 4
        outputQueue.clear();                    // 步骤 5
        printer.resetMarkdown();
        lastShownModel = "";                    // 恢复的会话首回合重打 ⚙ 行
        pendingSkill = null;                    // 未发送的技能挂载不得注入别的会话
        var r = runner();                       // 步骤 6：清屏（runner 非空时）
        if (r != null) {
            r.runOnRenderThread(() -> {
                boolean ok = ScreenCleaner.clear(r);
                if (ok) {
                    scrollTail.clear();          // resize 重放不该复活上一个会话的画面
                    resetTailDedup();
                    printer.welcome(onSubmit.currentModel(),
                            io.github.javaside.springai.codetui.AppInfo.versionLabel());
                } else {
                    pushClearDegradedNotice();
                }
            });
        } else {
            pushClearDegradedNotice();
        }
        // 步骤 7：回放（空历史 no-op）；头行区分恢复/分叉（分叉只报原 id 前 8 位，spec 定案——整串太长刷屏）
        String header = (sourceIdForNotice == null) ? "已恢复会话"
                : "已分叉会话（源自 " + sourceIdForNotice.substring(0, Math.min(8, sourceIdForNotice.length()))
                        + "…，原会话保留）";
        state.replayHistory(onSubmit.sessionMessages(targetId), header);
    }
```

`sessionsPanelChildren()` 顶部（空列表判断前）补确认态：

```java
        if (sessionPendingDelete != null) {
            els.add(text("  ⚠ 确认删除会话 " + sessionPendingDelete + "？此操作不可恢复 · Enter 确认 · Esc 取消")
                    .style(PICK_TITLE));
            return els.toArray(new Element[0]);
        }
```

`/fork` 命令链（`/resume` 分支后）+ COMMANDS 表行：

```java
        if (cmd.equals("/fork")) {                   // 分叉当前会话：历史复制到新 id 并切过去，原会话保留
            clearInput();
            if (state.isBusy()) {
                state.setNotice("忙碌中，无法分叉");
                return;
            }
            String src = onSubmit.currentSessionId();
            java.util.Optional<String> forked = onSubmit.forkSession(src);
            if (forked.isEmpty()) {
                state.setNotice("当前会话为空，无需分叉");
                return;
            }
            switchToSession(forked.get(), src);
            return;
        }
```

```java
            new SlashCommand("/fork",    "分叉当前会话（历史复制到新会话，原会话保留）"),
```

- [ ] **Step 4: 跑测试确认绿 + 全 View 测试回归**

Run: `mvn -pl springai-code-tui -am test -Dtest='CodeTuiViewSessionsPanelTest,CodeTuiViewTasksPanelTest,CodeTuiViewClearTest' -Dsurefire.failIfNoSpecifiedTests=false`
（若 `CodeTuiViewClearTest` 不存在，替换为 `CodeTuiView*Test` 通配：`-Dtest='CodeTuiView*'`。）
Expected: 全 PASS。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/CodeTuiView.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/ui/ConversationState.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/ui/CodeTuiViewSessionsPanelTest.java
git commit -m "feat: /resume 恢复/分叉/删除动作与 /fork 命令（含 Enter 时 busy 复检）"
```

---

### Task 9: 文档更新 + 全量回归

**Files:**
- Modify: `springai-code-tui/docs/implementation-map.md`（速查表加一行 + §7 补「会话管理」小节）
- Modify: `springai-code-tui/README.md`（命令表补 `/resume` `/fork`；启动参数说明补 `--resume`——执行时 `grep -n '/tasks' README.md` 定位表区）

**Interfaces:** Consumes: Task 1-8 全部成品。

- [ ] **Step 1: implementation-map 速查表（§速查表 `/context` 行附近）加行**

```markdown
| 会话管理 /resume /fork | `CodeTuiView.openSessionsPanel` | `SessionCatalog`、`CodingAgent.switchContext`、`FileSessionRepository.fork` | [7](#7-会话持久化与恢复) |
```

§7 末尾补小节（要点：面板四件套 + 后台摘要；恢复序列 0-7 与 /clear 同构（busy 复检/先验证后杀/lastShownModel+pendingSkill/scrollTail 清理）；switchContext 与 clearContext 同体；fork 事件 sessionId 重写；--resume 前缀匹配与 -c 互斥；SubmitHandler 会话门面 6 方法）。写成 8-12 行的紧凑小节，风格对齐 §13「后台任务」。

- [ ] **Step 2: README 命令表补三行**（`/resume`、`/fork`、`--resume` 启动参数，措辞对齐既有表格）。

- [ ] **Step 3: 全量回归**

Run: `mvn -pl springai-code-tui -am test`
Expected: 全 PASS（无新失败；已知 flaky `CodingAgentSpikeTest.todoTurnIdBinding` 若红按 CONTRIBUTING 单跑复核）。

- [ ] **Step 4: 真机冒烟（手动，可选但推荐）**

```bash
mvn -pl springai-code-tui -am package -DskipTests -Pdist
# 解包 dist 包后：无 key 也可验证 UI——
# 1) ./bin/codetui --help 应含 --resume 行
# 2) ./bin/codetui --resume 2099 → 应报「没有匹配」并列候选、退出码 1
# 3) 配 key 启动 → /resume 开面板 → ↑↓/Enter/f/d 全链路
```

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/docs/implementation-map.md springai-code-tui/README.md
git commit -m "docs: 会话管理（/resume /fork --resume）补 implementation-map 与 README"
```

---

## Plan Self-Review（已执行；2026-10-23 评审修订后更新）

1. **Spec 覆盖**：面板（Task 7+8）、`/fork`（8）、`--resume`（6）、`SessionCatalog`（2+3）、`switchContext` 与 seam（5）、`fork`（4）、`UserTextStripper`（1）、文档（9）——spec 六组件与恢复/分叉/删除序列全部有任务承接；三处实现级微调（digest 占位符合并且加载中态取消、`switchContext` 的 null 防御、分叉提示取前 8 位已按 spec 定案落实）均在对应任务注明。
2. **占位符扫描**：无 TBD/TODO；「以真实 API 为准」的注记（23 参构造器参数序、`DefaultUsage` 构造签名）均给出先例出处与降级路径，断言语义不变。
3. **类型一致性**：`SessionSummary(id, mtimeMillis, sizeBytes, digest)` 四参在 Task 2/7/8 一致；`forkSession -> Optional<String>` 在 Task 5/8 一致；`replayHistory(List, String)` 双参只在 Task 8 定义一次；seam 六方法签名在 Task 5 定义、Task 7/8 消费处逐一对应。
4. **评审修订记录（subagent 评审，2026-10-23）**：已修复 6 处硬伤——Jackson 3 包名（`tools.jackson.core.json.JsonFactory`）与 `PROPERTY_NAME` 枚举（javap 实测 jackson-core 3.1.4）、`KeyCode.ESCAPE` 与 `KeyEvent.ofChar`（KeyCode 无单字母常量）、`stripAll` 组合期望含 📎 折叠行、时间断言改同口径 UTC→本地换算（防时区 flaky）、删除用例桩真删文件 + mtime 显式定序 + notice 断言、accumulator 经 23 参构造器真断言（`DefaultUsage` 先例）替换假覆盖；另修恒真回放断言、Task 7 标题不宣传未实现键位、`goalManager.clear` 沿用 "clear-context" 串、回归清单纳入 `StartupHelpTest`、import 补齐提示若干。

# DeepSeek 视觉模型支持 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 `deepseek-v4-flash-vision-exp`（DeepSeek 唯一视觉模型，2026-08-21 上线）真正收到图片——支持 base64 内联与 Files API 双通道，复用既有兑现/预算/UI 全链路。

**Architecture:** spring-ai-deepseek 2.0.0 序列化消息时只用 `getText()`、`UserMessage` 的 `Media` 被静默丢弃、`ChatCompletionMessage.content` 又是 `String` 装不下数组——故视觉必须走「HTTP 层改写序列化 JSON」：`DeepSeekThinkingChatModel` 把当轮 `UserMessage` 的 Media 按「消息序号:media 序号」注册进进程内注册表，既有 `DeepSeekThinkingBodyCodec`（阻塞）与 `DeepSeekThinkingClientHttpConnector`（流式）在改写请求体时按序号取图，把 user 消息的 `content` 从 string 改写成 `[text 块 + image_url/file 块]`。Files 通道经 `DeepSeekFileStore` sha 幂等上传，失败自动降级内联。

**Tech Stack:** Java 17（`maven.compiler.release=17`，无类型模式 switch、无 record pattern）、Spring AI 2.0、spring-ai-deepseek 2.0.0（反编译核实序列化行为）、tools.jackson（项目已有）、JDK ImageIO / `java.net.http` / `MessageDigest`（零新依赖）、JUnit 5。

**Spec:** [2026-08-21 DeepSeek 视觉设计](../specs/2026-08-21-deepseek-vision-design.md)

---

## 全局纪律（每个任务都适用）

- **验证命令必须模块作用域**：`mvn test -pl springai-code-tui -Dtest=XxxTest`。
  绝不加 `-DfailIfNoSpecifiedTests=false`——整仓会被 3 个空模块打挂。
- **Java 17**：不写 `case String s ->` 类型模式 switch、不写 record pattern；`instanceof` 模式匹配（Java 16+）可用但本计划统一用传统写法，避免任何误判。
- **断言一律用 JUnit `org.junit.jupiter.api.Assertions`，不用 AssertJ**（本模块无 assertj 依赖）。
  常用换算：`assertTrue/assertFalse(x)`、`assertEquals(期望, 实际)`（**期望在前**）、
  `assertArrayEquals(期望, 实际)`（byte[] 必须用它）、`assertSame`（引用相同）、
  `assertNull/assertNotNull`。
- **消息构造用 builder**：`UserMessage.builder().text(...).media(...).build()`；`Media` 数据一律
  `byte[]`（`Media.builder().mimeType(...).data(bytes).build()`），绝不放 `InputStream`/`File`。
- **提交信息中文**，首行 `feat(vision): …` / `test(vision): …` / `docs(vision): …`。
- **每个任务结束前跑该任务涉及的测试类，绿了才提交**。
- `tools.jackson.databind.ObjectMapper` 是项目实际用的 JSON 库（`DeepSeekThinkingBodyCodec` 同款），
  测试解析一律用它。

---

## 文件结构

**新建**（全部 `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/media/`）：

| 文件 | 职责 |
|---|---|
| `DeepSeekVisionMediaRegistry.java` | 对象层 → JSON 改写层的图片字节通道；`Entry`（inline/files 判别）+ `key(msgIdx, mediaIdx)` |
| `DeepSeekFileStore.java` | Files API 上传客户端：sha256 幂等 + `file_id` 缓存 + 失败返回 empty；`Uploader` 接口注入点 |

**修改**：

| 文件 | 改动 |
|---|---|
| `agent/media/VisionModels.java` | `VISION_PREFIXES` 加 `"deepseek-v4-flash-vision"` |
| `agent/DeepSeekProvider.java` | `MODELS` 加模型项；建 registry / FileStore / fileUploader；`buildDelegate` 与 `DeepSeekThinkingChatModel` 接线；`visionTransportFor` 纯函数 |
| `agent/DeepSeekThinkingBodyCodec.java` | `decorate`/`decorateStreaming` 加 registry 三参重载；新增 `decorateVision` |
| `agent/DeepSeekThinkingClientHttpConnector.java` | 加 registry 字段，透传给 codec |
| `agent/DeepSeekThinkingChatModel.java` | 加 registry（及 fileUploader）字段；`call`/`stream` 注册当轮 Media、请求结束清理 |

**新建测试**（`src/test/.../agent/`）：

| 测试 | 覆盖 |
|---|---|
| `DeepSeekProviderVisionTest.java` | 名单判定、内置清单、capabilities、transport 纯函数 |
| `DeepSeekVisionRegistryTest.java` | 注册/消费/清理/key |
| `DeepSeekVisionBodyCodecTest.java` | **核心**：content 改写、无命中零变化、非 user 不碰、流式入口 |
| `DeepSeekThinkingChatModelVisionTest.java` | call/stream 注册与清理（桩 delegate） |
| `DeepSeekFileStoreTest.java` | 上传表单、sha 幂等、失败降级、`parseFileId` |
| `DeepSeekVisionSmokeTest.java` | 真机探针（双 gate，默认跳过） |

---

### Task 1: 模型名单 + 内置清单 + capabilities

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/media/VisionModels.java:23-28`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/DeepSeekProvider.java:25-27`
- Test: Create `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/DeepSeekProviderVisionTest.java`

**Interfaces:**
- Consumes: `ModelOption(id, label, desc)` record（现有）、`VisionModels.supportsImage(String)`（现有）、`ModelCapabilities(boolean, boolean)`（现有）。
- Produces: `DeepSeekProvider.visionTransportFor(String envValue)` 静态纯函数——Task 5 复用。

- [ ] **Step 1: 写失败测试**

```java
package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.media.VisionModels;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepSeekProviderVisionTest {

    // ---- VisionModels 名单：只认视觉模型，不误伤纯文本 flash ----

    @Test
    void visionModelId_isSupported() {
        assertTrue(VisionModels.supportsImage("deepseek-v4-flash-vision-exp"),
                "deepseek-v4-flash-vision-exp 应判为支持视觉");
    }

    @Test
    void plainFlash_and_pro_areNotSupported() {
        assertFalse(VisionModels.supportsImage("deepseek-v4-flash"), "纯文本 flash 不得误判为视觉");
        assertFalse(VisionModels.supportsImage("deepseek-v4-pro"), "pro 不得误判为视觉");
        assertFalse(VisionModels.supportsImage("deepseek-v4-flash-vision2"), "前缀必须精确：不在名单则不支持");
    }

    @Test
    void globalKillSwitch_stillApplies() {
        assertFalse(VisionModels.enabledFor("off"), "CODETUI_VISION=off 时全关");
        assertTrue(VisionModels.enabledFor(null), "未配置默认开启");
    }

    // ---- 内置清单：视觉模型可选项存在，默认模型不变 ----

    @Test
    void builtinModels_includeVision_exp_butDefaultStaysPro() {
        DeepSeekProvider provider = new DeepSeekProvider("sk-test");
        List<ModelOption> models = provider.models();
        assertEquals("deepseek-v4-pro", provider.defaultModel(), "默认模型必须仍是 deepseek-v4-pro");
        assertTrue(models.stream().anyMatch(m -> m.id().equals("deepseek-v4-flash-vision-exp")),
                "内置清单应含 deepseek-v4-flash-vision-exp");
    }

    @Test
    void capabilities_followModelId() {
        DeepSeekProvider provider = new DeepSeekProvider("sk-test");
        assertTrue(provider.capabilities("deepseek-v4-flash-vision-exp").supportsImageInput());
        assertFalse(provider.capabilities("deepseek-v4-pro").supportsImageInput());
        assertFalse(provider.capabilities("deepseek-v4-flash").supportsImageInput());
    }

    // ---- 传输开关纯函数（Task 5 复用）----

    @Test
    void transport_parsing() {
        assertEquals(DeepSeekProvider.VisionTransport.FILES,
                DeepSeekProvider.visionTransportFor("files"));
        assertEquals(DeepSeekProvider.VisionTransport.INLINE,
                DeepSeekProvider.visionTransportFor("inline"));
        assertEquals(DeepSeekProvider.VisionTransport.INLINE,
                DeepSeekProvider.visionTransportFor(null));
        assertEquals(DeepSeekProvider.VisionTransport.INLINE,
                DeepSeekProvider.visionTransportFor("  FILES  "));
        assertEquals(DeepSeekProvider.VisionTransport.INLINE,
                DeepSeekProvider.visionTransportFor("garbage"));
    }
}
```

> 注：`visionTransportFor("  FILES  ")` 期望 INLINE 是**有意为之**（严格等于 `files` 才走 files；
> 大小写不敏感但**不去空格**——环境变量值不该被静默修剪，宁可默认内联）。若你认为该去空格，
> 实现与测试同步调整即可，二选一必须一致。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -pl springai-code-tui -Dtest=DeepSeekProviderVisionTest`
Expected: 编译失败（`VisionTransport` 不存在）。

- [ ] **Step 3: 实现**

`VisionModels.java`（只改名单）：

```java
    private static final List<String> VISION_PREFIXES = List.of(
            "gpt-5.", "gpt-4o", "o4-",
            "claude-",
            "qwen-vl", "qwen2-vl", "qwen2.5-vl", "qwen3-vl",
            "glm-4v", "glm-4.1v", "glm-4.5v",
            "deepseek-v4-flash-vision"        // ★ DeepSeek 视觉实验模型（2026-08-21 上线）
    );
```

> **为什么只加 `deepseek-v4-flash-vision` 前缀**：加 `deepseek-v4-flash` 会误伤纯文本 flash。
> 该前缀恰好唯一命中 `deepseek-v4-flash-vision-exp`，且未来正式版若改名 `deepseek-v4-flash-vision`
> 仍命中。

`DeepSeekProvider.java`（改 MODELS + 加枚举与纯函数）：

```java
    // 首项即默认模型（*_MODELS 未配置时的回退清单，约定第一项为默认）。
    private static final List<ModelOption> MODELS = List.of(
            new ModelOption("deepseek-v4-pro",   "deepseek-v4-pro",   "强推理 · 1.6T · 更慢更贵"),
            new ModelOption("deepseek-v4-flash", "deepseek-v4-flash", "非思考 · 快 · 便宜"),
            new ModelOption("deepseek-v4-flash-vision-exp", "deepseek-v4-flash-vision-exp",
                    "视觉 · 实验 · 快（图最多 384 token/张）"));
```

在类内新增（放 `id()` 附近）：

```java
    /** 视觉传输通道：inline=base64 内联（默认），files=Files API file_id。 */
    enum VisionTransport { INLINE, FILES }

    /** 严格等于 files（忽略大小写、不去空格）才走 Files API；其余一律内联。纯函数供单测。 */
    static VisionTransport visionTransportFor(String envValue) {
        return envValue != null && envValue.trim().equalsIgnoreCase("files")
                ? VisionTransport.FILES : VisionTransport.INLINE;
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn test -pl springai-code-tui -Dtest=DeepSeekProviderVisionTest`
Expected: PASS（全部绿）。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/media/VisionModels.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/DeepSeekProvider.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/DeepSeekProviderVisionTest.java
git commit -m "$(cat <<'EOF'
feat(vision): DeepSeek 视觉模型名单与内置清单

- VisionModels 加 deepseek-v4-flash-vision 前缀（不误伤纯文本 flash）
- DeepSeekProvider 内置清单加 deepseek-v4-flash-vision-exp，默认模型仍为 v4-pro
- 新增 VisionTransport 枚举与 visionTransportFor 纯函数（Task 5 复用）
EOF
)"
```

---

### Task 2: DeepSeekVisionMediaRegistry（对象层 → JSON 层通道）

**Files:**
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/media/DeepSeekVisionMediaRegistry.java`
- Test: Create `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/media/DeepSeekVisionRegistryTest.java`

**Interfaces:**
- Consumes: 无（纯新类）。
- Produces（后续任务依赖的精确签名）：
  - `enum Transport { INLINE, FILES }`
  - `record Entry(Transport transport, byte[] bytes, String mimeType, String fileId)`
    + `static Entry inline(byte[] bytes, String mimeType)` + `static Entry file(String fileId)`
  - `void put(int messageIndex, int mediaIndex, Entry entry)`
  - `Entry take(String key)`（**消费即删**）
  - `boolean isEmpty()` / `void clear()`
  - `static String key(int messageIndex, int mediaIndex)` → `"i:j"`

- [ ] **Step 1: 写失败测试**

```java
package io.github.javaside.springai.codetui.agent.media;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepSeekVisionRegistryTest {

    @Test
    void put_take_roundTrip() {
        DeepSeekVisionMediaRegistry r = new DeepSeekVisionMediaRegistry();
        r.put(0, 0, DeepSeekVisionMediaRegistry.Entry.inline(new byte[]{1, 2}, "image/png"));
        r.put(2, 1, DeepSeekVisionMediaRegistry.Entry.file("file-api-abc"));
        DeepSeekVisionMediaRegistry.Entry e0 = r.take(DeepSeekVisionMediaRegistry.key(0, 0));
        DeepSeekVisionMediaRegistry.Entry e1 = r.take(DeepSeekVisionMediaRegistry.key(2, 1));
        assertEquals(DeepSeekVisionMediaRegistry.Transport.INLINE, e0.transport());
        assertEquals("image/png", e0.mimeType());
        assertEquals(2, e0.bytes().length);
        assertEquals(DeepSeekVisionMediaRegistry.Transport.FILES, e1.transport());
        assertEquals("file-api-abc", e1.fileId());
        assertNull(e1.bytes());
    }

    @Test
    void take_consumes() {
        DeepSeekVisionMediaRegistry r = new DeepSeekVisionMediaRegistry();
        r.put(0, 0, DeepSeekVisionMediaRegistry.Entry.inline(new byte[]{1}, "image/png"));
        assertTrue(r.take(DeepSeekVisionMediaRegistry.key(0, 0)) != null);
        assertNull(r.take(DeepSeekVisionMediaRegistry.key(0, 0)), "take 消费即删，二次取应为 null");
    }

    @Test
    void unknownKey_returnsNull() {
        DeepSeekVisionMediaRegistry r = new DeepSeekVisionMediaRegistry();
        assertNull(r.take(DeepSeekVisionMediaRegistry.key(9, 9)));
    }

    @Test
    void clear_empties() {
        DeepSeekVisionMediaRegistry r = new DeepSeekVisionMediaRegistry();
        r.put(0, 0, DeepSeekVisionMediaRegistry.Entry.inline(new byte[]{1}, "image/png"));
        r.clear();
        assertTrue(r.isEmpty());
        assertNull(r.take(DeepSeekVisionMediaRegistry.key(0, 0)));
    }

    @Test
    void key_format() {
        assertEquals("3:7", DeepSeekVisionMediaRegistry.key(3, 7));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -pl springai-code-tui -Dtest=DeepSeekVisionRegistryTest`
Expected: 编译失败（类不存在）。

- [ ] **Step 3: 实现**

```java
package io.github.javaside.springai.codetui.agent.media;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对象层 → HTTP 改写层的图片字节通道（DeepSeek 专属）。
 *
 * <p><b>为什么需要它</b>：spring-ai-deepseek 2.0.0 序列化消息时只用 {@code getText()}，
 * {@code UserMessage} 上的 {@code Media} 被静默丢弃，且 {@code ChatCompletionMessage.content}
 * 是 {@code String} 装不下 content 数组。因此图片字节必须经本注册表从「对象层」传到
 * 「JSON 改写层」：{@code DeepSeekThinkingChatModel} 按「消息在 instructions 里的下标:该消息
 * 内 media 序号」注册，改写器（{@code DeepSeekThinkingBodyCodec}）按同一 key 消费。
 *
 * <p><b>并发</b>：有图请求在本项目里只有主 agent 当前回合（串行）；无图请求（子 agent 等）
 * 不注册也不清理，与有图请求并发互不干扰。key 消费即删（{@link #take}）+ 请求结束清理
 * （调用方）保证无跨请求残留。
 */
public final class DeepSeekVisionMediaRegistry {

    /** 图片在请求体里的呈现通道。 */
    public enum Transport { INLINE, FILES }

    /** INLINE：base64 内联（bytes+mimeType）；FILES：file_id 引用（fileId）。 */
    public record Entry(Transport transport, byte[] bytes, String mimeType, String fileId) {
        public static Entry inline(byte[] bytes, String mimeType) {
            return new Entry(Transport.INLINE, bytes, mimeType, null);
        }
        public static Entry file(String fileId) {
            return new Entry(Transport.FILES, null, null, fileId);
        }
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public void put(int messageIndex, int mediaIndex, Entry entry) {
        entries.put(key(messageIndex, mediaIndex), entry);
    }

    /** 消费即删：改写器每取一张图，key 立即失效，天然防止同 key 二次误用。 */
    public Entry take(String key) {
        return entries.remove(key);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public void clear() {
        entries.clear();
    }

    public static String key(int messageIndex, int mediaIndex) {
        return messageIndex + ":" + mediaIndex;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn test -pl springai-code-tui -Dtest=DeepSeekVisionRegistryTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/media/DeepSeekVisionMediaRegistry.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/media/DeepSeekVisionRegistryTest.java
git commit -m "$(cat <<'EOF'
feat(vision): DeepSeek 视觉注册表（对象层→JSON 改写层字节通道）

- Entry 判别 inline/files 两种呈现；key=消息序号:media 序号
- take 消费即删 + 请求末清理，配合串行有图请求保证无跨请求残留
EOF
)"
```

---

### Task 3: DeepSeekThinkingBodyCodec 视觉改写（核心）

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/DeepSeekThinkingBodyCodec.java`
- Test: Create `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/DeepSeekVisionBodyCodecTest.java`

**Interfaces:**
- Consumes: `DeepSeekVisionMediaRegistry`（Task 2）、`ThinkingConfig`（现有）。
- Produces（后续任务依赖）：
  - `static byte[] decorate(byte[] body, ThinkingConfig config, DeepSeekVisionMediaRegistry registry)`
  - `static byte[] decorateStreaming(byte[] body, ThinkingConfig config, DeepSeekVisionMediaRegistry registry)`
  - 保留既有两参重载（registry=null），**既有 `DeepSeekThinkingBodyCodecTest` 不改也能编译**。

- [ ] **Step 1: 写失败测试**

```java
package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.media.DeepSeekVisionMediaRegistry;
import io.github.javaside.springai.codetui.agent.thinking.ThinkingConfig;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepSeekVisionBodyCodecTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final byte[] PNG = new byte[]{-119, 80, 78, 71, 1, 2, 3};
    private static final String B64 = Base64.getEncoder().encodeToString(PNG);

    private static byte[] userBody(String content) {
        return ("{\"model\":\"deepseek-v4-flash-vision-exp\",\"messages\":["
                + "{\"role\":\"system\",\"content\":\"sys\"},"
                + "{\"role\":\"user\",\"content\":\"" + content + "\"}]}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static DeepSeekVisionMediaRegistry withInline(int msgIdx, int mediaIdx) {
        DeepSeekVisionMediaRegistry r = new DeepSeekVisionMediaRegistry();
        r.put(msgIdx, mediaIdx, DeepSeekVisionMediaRegistry.Entry.inline(PNG, "image/png"));
        return r;
    }

    @Test
    void userContent_becomesArray_withTextBlockAndImageBlock() throws Exception {
        DeepSeekVisionMediaRegistry r = withInline(1, 0);
        byte[] out = DeepSeekThinkingBodyCodec.decorate(
                userBody("这张图是什么"), ThinkingConfig.defaults(), r);
        JsonNode root = MAPPER.readTree(out);
        JsonNode content = root.path("messages").get(1).path("content");
        assertTrue(content.isArray(), "命中注册表的 user 消息 content 应改写为数组");
        assertEquals(2, content.size());
        assertEquals("text", content.get(0).path("type").asText());
        assertEquals("这张图是什么", content.get(0).path("text").asText(), "文本块必须逐字保留");
        assertEquals("image_url", content.get(1).path("type").asText());
        assertEquals("data:image/png;base64," + B64,
                content.get(1).path("image_url").path("url").asText(), "必须是带 MIME 前缀的 data URI");
    }

    @Test
    void fileEntry_writesFileBlock() throws Exception {
        DeepSeekVisionMediaRegistry r = new DeepSeekVisionMediaRegistry();
        r.put(1, 0, DeepSeekVisionMediaRegistry.Entry.file("file-api-abc"));
        byte[] out = DeepSeekThinkingBodyCodec.decorate(
                userBody("看图"), ThinkingConfig.defaults(), r);
        JsonNode content = MAPPER.readTree(out).path("messages").get(1).path("content");
        assertEquals("file", content.get(1).path("type").asText());
        assertEquals("file-api-abc", content.get(1).path("file_id").asText());
    }

    @Test
    void noHit_returnsBodyUnchanged() {
        byte[] body = userBody("没有图");
        assertArrayEquals(body, DeepSeekThinkingBodyCodec.decorate(body, ThinkingConfig.defaults(),
                new DeepSeekVisionMediaRegistry()), "无注册命中必须逐字节不变");
    }

    @Test
    void nullRegistry_returnsBodyUnchanged() {
        byte[] body = userBody("没有图");
        assertArrayEquals(body, DeepSeekThinkingBodyCodec.decorate(body, ThinkingConfig.defaults(), null));
    }

    @Test
    void nonUserMessages_untouched() throws Exception {
        DeepSeekVisionMediaRegistry r = withInline(0, 0);   // 序号 0 是 system，不该被改写
        byte[] out = DeepSeekThinkingBodyCodec.decorate(userBody("看图"), ThinkingConfig.defaults(), r);
        JsonNode sys = MAPPER.readTree(out).path("messages").get(0);
        assertTrue(sys.path("content").isTextual(), "system 消息不得被改写");
        assertEquals("sys", sys.path("content").asText());
        assertTrue(r.isEmpty() || r.take(DeepSeekVisionMediaRegistry.key(0, 0)) != null,
                "未被消费的 key 可残留（由请求末清理），不得被误写进 system");
    }

    @Test
    void multipleImages_sequentialOrder() throws Exception {
        DeepSeekVisionMediaRegistry r = new DeepSeekVisionMediaRegistry();
        r.put(1, 0, DeepSeekVisionMediaRegistry.Entry.inline(PNG, "image/png"));
        r.put(1, 1, DeepSeekVisionMediaRegistry.Entry.file("file-api-x"));
        byte[] out = DeepSeekThinkingBodyCodec.decorate(userBody("两张"), ThinkingConfig.defaults(), r);
        JsonNode content = MAPPER.readTree(out).path("messages").get(1).path("content");
        assertEquals(3, content.size(), "text + 2 图");
        assertEquals("image_url", content.get(1).path("type").asText());
        assertEquals("file", content.get(2).path("type").asText());
    }

    @Test
    void streaming_decoratesAndStillInjectsUsage() throws Exception {
        DeepSeekVisionMediaRegistry r = withInline(1, 0);
        byte[] out = DeepSeekThinkingBodyCodec.decorateStreaming(
                userBody("看图"), ThinkingConfig.defaults(), r);
        JsonNode root = MAPPER.readTree(out);
        assertEquals(true, root.path("stream_options").path("include_usage").asBoolean(),
                "流式必须仍注入 stream_options.include_usage");
        assertEquals("image_url", root.path("messages").get(1).path("content").get(1).path("type").asText());
    }

    @Test
    void thinkingAndVision_compose() throws Exception {
        DeepSeekVisionMediaRegistry r = withInline(1, 0);
        byte[] out = DeepSeekThinkingBodyCodec.decorate(
                userBody("看图"), ThinkingConfig.enabledEffort("max"), r);
        JsonNode root = MAPPER.readTree(out);
        assertEquals("enabled", root.path("thinking").path("type").asText());
        assertEquals("image_url", root.path("messages").get(1).path("content").get(1).path("type").asText());
    }

    @Test
    void malformedBody_throws() {
        byte[] bad = "{not-json".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> DeepSeekThinkingBodyCodec.decorate(bad, ThinkingConfig.defaults(), withInline(1, 0)));
    }
}
```

> 注：`userBody` 里 user 消息下标是 1（0 是 system）——注册表 key 用**真实下标**，测试与实现
> 都以「JSON 数组下标」为准，与 `DeepSeekChatModel` 转换消息时「List 顺序 → messages 数组顺序」
> 的映射一致（反编译核实不重排不合并）。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -pl springai-code-tui -Dtest=DeepSeekVisionBodyCodecTest`
Expected: 编译失败（三参重载不存在）。

- [ ] **Step 3: 实现**

`DeepSeekThinkingBodyCodec.java` 重构（**保留既有两参方法签名与行为**）：

```java
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DeepSeekThinkingBodyCodec() {
    }

    /** 既有两参入口：无视觉注册表 → 纯思考改写（行为与之前完全一致）。 */
    static byte[] decorate(byte[] body, ThinkingConfig config) {
        return decorate(body, config, null);
    }

    /** 思考 + 视觉：先按配置注入 thinking 字段，再把注册表命中的图片写进 user 消息。 */
    static byte[] decorate(byte[] body, ThinkingConfig config,
                           io.github.javaside.springai.codetui.agent.media.DeepSeekVisionMediaRegistry registry) {
        return decorateVision(decorateThinking(body, config), registry);
    }

    private static byte[] decorateThinking(byte[] body, ThinkingConfig config) {
        if (config.mode() == io.github.javaside.springai.codetui.agent.thinking.ThinkingMode.DEFAULT) {
            return body;
        }
        try {
            JsonNode parsed = MAPPER.readTree(body);
            if (!(parsed instanceof ObjectNode root)) {
                return body;
            }
            ObjectNode thinking = root.putObject("thinking");
            thinking.put("type", config.mode() == io.github.javaside.springai.codetui.agent.thinking.ThinkingMode.ENABLED
                    ? "enabled" : "disabled");
            if (config.effort() != null) {
                root.put("reasoning_effort", config.effort());
            }
            return MAPPER.writeValueAsBytes(root);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("DeepSeek 请求不是合法 JSON，无法加入思考配置", e);
        }
    }

    /** 流式：先注入 stream_options，再走 decorate（思考 + 视觉）。 */
    static byte[] decorateStreaming(byte[] body, ThinkingConfig config) {
        return decorateStreaming(body, config, null);
    }

    static byte[] decorateStreaming(byte[] body, ThinkingConfig config,
                                    io.github.javaside.springai.codetui.agent.media.DeepSeekVisionMediaRegistry registry) {
        return decorate(injectStreamOptions(body), config, registry);
    }

    /**
     * 视觉改写：把注册表里命中的图片块插进对应 user 消息的 content 数组。
     *
     * <p><b>只动 role=user 的消息</b>（图片只出现在 user 消息上）；system/assistant/tool 一概不碰。
     * <b>无任何命中 → 原样返回同一 body</b>（纯文本请求零行为变化）。文本块与原 content
     * 逐字一致（引用块、delivery 行等原样保留，模型照常能读「这是哪张图」）。
     *
     * <p><b>查不到 key 即 break 继续</b>（fail-open）：key 是按序号递增的，首个空洞之后的
     * 序号不可能再命中（注册是连续的）；改写失败绝不连累请求。
     */
    static byte[] decorateVision(byte[] body,
                                 io.github.javaside.springai.codetui.agent.media.DeepSeekVisionMediaRegistry registry) {
        if (registry == null) {
            return body;
        }
        try {
            JsonNode parsed = MAPPER.readTree(body);
            if (!(parsed instanceof ObjectNode root)) {
                return body;
            }
            JsonNode messages = root.get("messages");
            if (messages == null || !messages.isArray()) {
                return body;
            }
            boolean changed = false;
            for (int i = 0; i < messages.size(); i++) {
                JsonNode msgNode = messages.get(i);
                if (!(msgNode instanceof ObjectNode msg)) {
                    continue;
                }
                if (!"user".equals(msg.path("role").asText())) {
                    continue;
                }
                JsonNode content = msg.get("content");
                if (content == null || content.isNull()) {
                    continue;
                }
                java.util.List<JsonNode> blocks = new java.util.ArrayList<>();
                if (content.isTextual()) {
                    blocks.add(MAPPER.createObjectNode().put("type", "text").put("text", content.textValue()));
                } else if (content.isArray()) {
                    content.forEach(blocks::add);   // 防御分支：序列化层只产 string，真数组则保留原块
                } else {
                    continue;
                }
                int j = 0;
                while (true) {
                    io.github.javaside.springai.codetui.agent.media.DeepSeekVisionMediaRegistry.Entry e =
                            registry.take(io.github.javaside.springai.codetui.agent.media.DeepSeekVisionMediaRegistry.key(i, j));
                    if (e == null) {
                        break;
                    }
                    blocks.add(entryToNode(e));
                    changed = true;
                    j++;
                }
                if (j > 0) {
                    msg.set("content", MAPPER.valueToTree(blocks));
                }
            }
            return changed ? MAPPER.writeValueAsBytes(root) : body;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("DeepSeek 请求不是合法 JSON，无法注入图片", e);
        }
    }

    private static JsonNode entryToNode(io.github.javaside.springai.codetui.agent.media.DeepSeekVisionMediaRegistry.Entry e) {
        if (e.transport() == io.github.javaside.springai.codetui.agent.media.DeepSeekVisionMediaRegistry.Transport.FILES) {
            return MAPPER.createObjectNode().put("type", "file").put("file_id", e.fileId());
        }
        String b64 = java.util.Base64.getEncoder().encodeToString(e.bytes());
        ObjectNode imageUrl = MAPPER.createObjectNode()
                .put("url", "data:" + e.mimeType() + ";base64," + b64);
        return MAPPER.createObjectNode().put("type", "image_url").set("image_url", imageUrl);
    }
```

> 原 `decorate` 的正文整体挪进 `decorateThinking`（DEFAULT 直接返回 body 的逻辑保留）；
> `injectStreamOptions` 原样不动。两参 `decorate`/`decorateStreaming` 委托三参（registry=null），
> 既有 `DeepSeekThinkingBodyCodecTest` 五个测试不改即绿。

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn test -pl springai-code-tui -Dtest=DeepSeekVisionBodyCodecTest,DeepSeekThinkingBodyCodecTest`
Expected: 全绿（新 9 个 + 既有 5 个）。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/DeepSeekThinkingBodyCodec.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/DeepSeekVisionBodyCodecTest.java
git commit -m "$(cat <<'EOF'
feat(vision): DeepSeek 请求体视觉改写（content → text+image_url/file 块）

- decorate/decorateStreaming 加 registry 三参重载，既有两参行为不变
- decorateVision 只改写 user 消息，无命中逐字节不变；data URI 带 MIME 前缀
EOF
)"
```

---

### Task 4: DeepSeekThinkingChatModel 注册接线 + Connector/Provider 接线

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/DeepSeekThinkingChatModel.java`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/DeepSeekThinkingClientHttpConnector.java`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/DeepSeekProvider.java`
- Test: Create `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/DeepSeekThinkingChatModelVisionTest.java`

**Interfaces:**
- Consumes: `DeepSeekVisionMediaRegistry`（Task 2）、codec 三参重载（Task 3）。
- Produces:
  - `DeepSeekThinkingChatModel(ChatModel defaultDelegate, Function<ThinkingConfig, ChatModel> delegateFactory, DeepSeekVisionMediaRegistry registry)`（**Task 5 会加第四参 fileUploader**）
  - `DeepSeekThinkingClientHttpConnector(ClientHttpConnector delegate, ThinkingConfig config, DeepSeekVisionMediaRegistry registry)`

- [ ] **Step 1: 写失败测试**

```java
package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.media.DeepSeekVisionMediaRegistry;
import io.github.javaside.springai.codetui.agent.thinking.ThinkingConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatOptions;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.util.MimeTypeUtils;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepSeekThinkingChatModelVisionTest {

    /** 桩 delegate：call 时消费 key(0,0)，记录是否命中；返回空响应。 */
    static final class StubDelegate implements ChatModel {
        boolean sawImage;

        @Override
        public ChatResponse call(Prompt prompt) {
            sawImage = registry().take(DeepSeekVisionMediaRegistry.key(0, 0)) != null;
            return ChatResponse.builder().generations(List.of(new Generation("ok"))).build();
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            sawImage = registry().take(DeepSeekVisionMediaRegistry.key(0, 0)) != null;
            return Flux.empty();
        }

        @Override
        public ChatOptions getOptions() { return null; }

        @Override
        public ChatOptions getDefaultOptions() { return null; }

        private DeepSeekVisionMediaRegistry registry() { return STUB_REGISTRY; }
        private static final DeepSeekVisionMediaRegistry STUB_REGISTRY = new DeepSeekVisionMediaRegistry();
    }

    private static UserMessage imageMessage() {
        return UserMessage.builder()
                .text("看这张图")
                .media(List.of(Media.builder()
                        .mimeType(MimeTypeUtils.parseMimeType("image/png"))
                        .data(new byte[]{1, 2, 3}).build()))
                .build();
    }

    @Test
    void call_registersMedia_beforeDelegate_andCleansAfter() {
        StubDelegate d = new StubDelegate();
        DeepSeekVisionMediaRegistry registry = new DeepSeekVisionMediaRegistry();
        DeepSeekThinkingChatModel model = new DeepSeekThinkingChatModel(d, c -> d, registry);

        model.call(new Prompt(imageMessage(), DeepSeekChatOptions.builder().model("deepseek-v4-pro").build()));

        assertTrue(d.sawImage, "delegate.call 执行时注册表应已含 key(0,0)（注册必须先于序列化）");
        assertTrue(registry.isEmpty(), "请求结束后注册表应被清理");
    }

    @Test
    void stream_registersMedia_andCleansOnTermination() {
        StubDelegate d = new StubDelegate();
        DeepSeekVisionMediaRegistry registry = new DeepSeekVisionMediaRegistry();
        DeepSeekThinkingChatModel model = new DeepSeekThinkingChatModel(d, c -> d, registry);

        model.stream(new Prompt(imageMessage(), DeepSeekChatOptions.builder().model("deepseek-v4-pro").build()))
                .blockLast();

        assertTrue(d.sawImage, "delegate.stream 执行时注册表应已含 key(0,0)");
        assertTrue(registry.isEmpty(), "流式终止后注册表应被清理（doFinally）");
    }

    @Test
    void textOnlyPrompt_registersNothing() {
        StubDelegate d = new StubDelegate();
        DeepSeekVisionMediaRegistry registry = new DeepSeekVisionMediaRegistry();
        DeepSeekThinkingChatModel model = new DeepSeekThinkingChatModel(d, c -> d, registry);

        model.call(new Prompt("纯文本", DeepSeekChatOptions.builder().model("deepseek-v4-pro").build()));

        assertFalse(d.sawImage, "纯文本请求不得注册任何图片");
        assertTrue(registry.isEmpty());
    }
}
```

> ⚠️ 上面 `StubDelegate` 里 `sawImage = registry().take(...) != null` 用的是**静态共享** registry，
> 桩自己在 call 里消费——这测的是「注册发生在 delegate 调用之前」。但注意桩里
> `STUB_REGISTRY` 是类级共享，多个测试并发会有干扰；该测试类默认串行执行，可接受。
> 若你实现时觉得别扭，可改成构造注入 registry（`new StubDelegate(registry)`），断言逻辑不变：
> 桩在 call 时 take 同一个 registry，`sawImage` 为 true 且请求结束后 `registry.isEmpty()`。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -pl springai-code-tui -Dtest=DeepSeekThinkingChatModelVisionTest`
Expected: 编译失败（三参构造不存在）。

- [ ] **Step 3: 实现**

`DeepSeekThinkingChatModel.java`（加字段 + 构造 + 注册/清理）：

```java
    private final ChatModel defaultDelegate;
    private final Function<ThinkingConfig, ChatModel> delegateFactory;
    private final Map<ThinkingConfig, ChatModel> delegates = new ConcurrentHashMap<>();
    private final io.github.javaside.springai.codetui.agent.media.DeepSeekVisionMediaRegistry visionRegistry;

    DeepSeekThinkingChatModel(ChatModel defaultDelegate,
                              Function<ThinkingConfig, ChatModel> delegateFactory,
                              io.github.javaside.springai.codetui.agent.media.DeepSeekVisionMediaRegistry visionRegistry) {
        this.defaultDelegate = defaultDelegate;
        this.delegateFactory = delegateFactory;
        this.visionRegistry = visionRegistry;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        ThinkingConfig config = configOf(prompt.getOptions());
        ChatModel delegate = delegate(config);
        Prompt nativePrompt = withNativeOptions(prompt);
        java.util.List<String> registered = registerMedia(nativePrompt);
        try {
            return delegate.call(nativePrompt);
        } finally {
            registered.forEach(visionRegistry::take);   // 清理本请求注册、未被改写器消费的 key
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        ThinkingConfig config = configOf(prompt.getOptions());
        ChatModel delegate = delegate(config);
        Prompt nativePrompt = withNativeOptions(prompt);
        java.util.List<String> registered = registerMedia(nativePrompt);
        return delegate.stream(nativePrompt)
                .doFinally(sig -> registered.forEach(visionRegistry::take));
    }

    /**
     * 把当轮 {@code UserMessage} 上的 {@code Media} 按「消息下标:media 序号」注册进
     * {@link DeepSeekVisionMediaRegistry}，供 HTTP 层改写器（{@code DeepSeekThinkingBodyCodec}）
     * 消费。<b>用户图与工具图（合成消息）在这里一视同仁</b>——两者对改写器都是
     * 「某条 user 消息 + media 列表」。
     *
     * <p><b>并发论证</b>：有图请求在本项目只有主 agent 当前回合（串行）；无图请求
     * （子 agent、摘要等）{@code registerMedia} 返回空列表，finally/doFinally 的清理是 no-op，
     * <b>不碰注册表</b>——与并发有图请求互不干扰。注册前防御性清空：若上一次有图请求
     * 的 Flux 从未被订阅（理论不发生，ChatClient 总是立即订阅），残留不会污染本次。
     */
    private java.util.List<String> registerMedia(Prompt prompt) {
        java.util.List<String> keys = new java.util.ArrayList<>();
        java.util.List<org.springframework.ai.chat.messages.Message> msgs = prompt.getInstructions();
        if (msgs == null || msgs.isEmpty()) {
            return keys;
        }
        if (!visionRegistry.isEmpty()) {
            visionRegistry.clear();
        }
        for (int i = 0; i < msgs.size(); i++) {
            org.springframework.ai.chat.messages.Message m = msgs.get(i);
            if (!(m instanceof UserMessage user)) {
                continue;
            }
            java.util.List<Media> media = user.getMedia();
            if (media == null || media.isEmpty()) {
                continue;
            }
            for (int j = 0; j < media.size(); j++) {
                Media md = media.get(j);
                byte[] bytes = md.getDataAsByteArray();
                if (bytes == null || bytes.length == 0) {
                    continue;
                }
                String mime = md.getMimeType() == null ? "image/png" : md.getMimeType().toString();
                visionRegistry.put(i, j,
                        io.github.javaside.springai.codetui.agent.media.DeepSeekVisionMediaRegistry.Entry.inline(bytes, mime));
                keys.add(io.github.javaside.springai.codetui.agent.media.DeepSeekVisionMediaRegistry.key(i, j));
            }
        }
        return keys;
    }
```

`DeepSeekThinkingClientHttpConnector.java`（加 registry 字段并透传）：

```java
    private final ClientHttpConnector delegate;
    private final ThinkingConfig config;
    private final io.github.javaside.springai.codetui.agent.media.DeepSeekVisionMediaRegistry visionRegistry;

    DeepSeekThinkingClientHttpConnector(ClientHttpConnector delegate, ThinkingConfig config,
                                        io.github.javaside.springai.codetui.agent.media.DeepSeekVisionMediaRegistry visionRegistry) {
        this.delegate = delegate;
        this.config = config;
        this.visionRegistry = visionRegistry;
    }
```

`writeWith` 里的调用改为：

```java
                    byte[] decorated = DeepSeekThinkingBodyCodec.decorateStreaming(bytes, config, visionRegistry);
```

`DeepSeekProvider.java`（接线：建 registry 字段，注入 buildDelegate 与 ChatModel）：

```java
    private final java.util.Map<String, Object> unused = null;   // 不需要——直接加字段
```
（不加多余字段，直接：

```java
    private final io.github.javaside.springai.codetui.agent.media.DeepSeekVisionMediaRegistry visionRegistry =
            new io.github.javaside.springai.codetui.agent.media.DeepSeekVisionMediaRegistry();
```

`chatModel()` 里构造改为：

```java
            ChatModel defaultDelegate = buildDelegate(restBuilder, webBuilder, ThinkingConfig.defaults());
            m = new DeepSeekThinkingChatModel(defaultDelegate,
                    config -> buildDelegate(restBuilder, webBuilder, config), visionRegistry);
```

`buildDelegate` 里两处传入 registry：

```java
        if (config.mode() != ThinkingMode.DEFAULT) {
            rest.requestInterceptor((request, body, execution) ->
                    execution.execute(request, DeepSeekThinkingBodyCodec.decorate(body, config, visionRegistry)));
        }
        ...
        web.clientConnector(new DeepSeekThinkingClientHttpConnector(nativeConnector, config, visionRegistry));
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn test -pl springai-code-tui -Dtest=DeepSeekThinkingChatModelVisionTest,DeepSeekThinkingBodyCodecTest,DeepSeekVisionBodyCodecTest,DeepSeekProviderVisionTest`
Expected: 全绿。

再跑一次全模块编译（接线改动影响 DeepSeekProvider 构造调用）：

Run: `mvn -pl springai-code-tui compile`
Expected: BUILD SUCCESS。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/DeepSeekThinkingChatModel.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/DeepSeekThinkingClientHttpConnector.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/DeepSeekProvider.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/DeepSeekThinkingChatModelVisionTest.java
git commit -m "$(cat <<'EOF'
feat(vision): DeepSeek ChatModel 注册当轮 Media 并接线改写器

- call/stream 注册 UserMessage 的 Media（按消息下标:media 序号），请求结束清理
- 无图请求不碰注册表（并发安全）；connector 透传 registry 给流式改写
- DeepSeekProvider 建 registry 并注入 buildDelegate 与 ChatModel
EOF
)"
```

---

### Task 5: DeepSeekFileStore + Files 通道 + 降级

**Files:**
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/media/DeepSeekFileStore.java`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/DeepSeekThinkingChatModel.java`（构造加 fileUploader 第四参）
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/DeepSeekProvider.java`（读 `DEEPSEEK_VISION_TRANSPORT`，files 时构造 uploader）
- Test: Create `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/media/DeepSeekFileStoreTest.java`

**Interfaces:**
- Consumes: `DeepSeekProvider.VisionTransport`（Task 1）、`DeepSeekVisionMediaRegistry.Entry`（Task 2）。
- Produces:
  - `DeepSeekFileStore(Uploader uploader)`，`interface Uploader { String upload(MultiValueMap<String, Object> form); }`
  - `Optional<String> DeepSeekFileStore.fileIdFor(byte[] bytes, String filename)`（sha 幂等）
  - `DeepSeekThinkingChatModel` 四参构造：`(ChatModel defaultDelegate, Function<ThinkingConfig, ChatModel> delegateFactory, DeepSeekVisionMediaRegistry registry, BiFunction<byte[], String, Optional<String>> fileUploader)`——**Task 4 的三参构造与测试调用点要同步更新（fileUploader 传 null）**。

- [ ] **Step 1: 写失败测试**

```java
package io.github.javaside.springai.codetui.agent.media;

import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepSeekFileStoreTest {

    /** 记录每次上传的表单 + 返回可控响应。 */
    static final class RecordingUploader implements DeepSeekFileStore.Uploader {
        final List<MultiValueMap<String, Object>> calls = new ArrayList<>();
        final AtomicInteger failOn = new AtomicInteger(-1);   // 第 N 次抛异常（-1 不抛）
        int callCount() { return calls.size(); }

        @Override
        public String upload(MultiValueMap<String, Object> form) {
            calls.add(new LinkedMultiValueMap<>(form));
            if (failOn.get() == calls.size()) {
                throw new IllegalStateException("upload failed");
            }
            return "{\"id\":\"file-api-abc\",\"object\":\"file\"}";
        }
    }

    @Test
    void upload_form_containsFileAndPurpose() {
        RecordingUploader u = new RecordingUploader();
        DeepSeekFileStore store = new DeepSeekFileStore(u);
        Optional<String> id = store.fileIdFor(new byte[]{1, 2}, "shot.png");

        assertTrue(id.isPresent());
        assertEquals("file-api-abc", id.get());
        assertEquals(1, u.callCount());
        MultiValueMap<String, Object> form = u.calls.get(0);
        assertEquals("user_data", form.getFirst("purpose"), "purpose 必须为 user_data");
        Object file = form.getFirst("file");
        assertTrue(file instanceof DeepSeekFileStore.NamedByteArrayResource, "file 必须是带文件名的 Resource");
        assertEquals("shot.png", ((DeepSeekFileStore.NamedByteArrayResource) file).getFilename());
    }

    @Test
    void sameBytes_uploadedOnce() {
        RecordingUploader u = new RecordingUploader();
        DeepSeekFileStore store = new DeepSeekFileStore(u);
        store.fileIdFor(new byte[]{1, 2}, "a.png");
        store.fileIdFor(new byte[]{1, 2}, "b.png");
        assertEquals(1, u.callCount(), "同字节幂等：只上传一次，文件名差异不影响 sha 寻址");
    }

    @Test
    void uploadFailure_returnsEmpty() {
        RecordingUploader u = new RecordingUploader();
        u.failOn.set(1);
        DeepSeekFileStore store = new DeepSeekFileStore(u);
        assertTrue(store.fileIdFor(new byte[]{1}, "a.png").isEmpty(), "上传失败必须返回 empty（调用方降级内联）");
    }

    @Test
    void parseFileId_variants() {
        assertEquals("file-api-x", DeepSeekFileStore.parseFileId("{\"id\":\"file-api-x\",\"object\":\"file\"}"));
        assertNull(DeepSeekFileStore.parseFileId("{}"), "缺 id 返回 null");
        assertNull(DeepSeekFileStore.parseFileId("{not-json"), "畸形返回 null");
        assertNull(DeepSeekFileStore.parseFileId("{\"id\":\"\"}"), "空白 id 返回 null");
    }

    @Test
    void namedResource_keepsFilename() {
        DeepSeekFileStore.NamedByteArrayResource r =
                new DeepSeekFileStore.NamedByteArrayResource(new byte[]{1}, "图.png");
        assertEquals("图.png", r.getFilename());
        assertEquals(1, r.getInputStream() == null ? -1 : r.contentLength());
    }
}
```

> ⚠️ 上面 `namedResource_keepsFilename` 里 `getInputStream()` 的写法别扭——直接
> `assertEquals(1, r.contentLength())` 即可（`ByteArrayResource.contentLength()` 返回字节数）。
> 实现时删掉 `getInputStream` 那段。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn test -pl springai-code-tui -Dtest=DeepSeekFileStoreTest`
Expected: 编译失败（类不存在）。

- [ ] **Step 3: 实现**

`DeepSeekFileStore.java`：

```java
package io.github.javaside.springai.codetui.agent.media;

import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DeepSeek Files API 上传客户端（OpenAI 兼容端点 {@code POST /files}）。
 *
 * <p><b>sha256 幂等</b>：同一字节只上传一次，file_id 进程内缓存——同一张图在回合内
 * 会被多次组装（无状态请求的固有代价），每次都上传是白花钱。
 *
 * <p><b>失败一律返回 empty</b>：Files 通道是<b>增强</b>不是依赖，调用方（DeepSeekThinkingChatModel）
 * 拿不到 file_id 就降级内联 base64。上传接口经 {@link Uploader} 注入：生产用 RestClient 实现，
 * 单测注入假实现（零网络）。
 */
public final class DeepSeekFileStore {

    /** HTTP 上传注入点。form 是 multipart 字段表，返回服务端响应体（JSON 字符串）。 */
    public interface Uploader {
        String upload(MultiValueMap<String, Object> form);
    }

    private final Uploader uploader;
    private final Map<String, String> bySha = new ConcurrentHashMap<>();

    public DeepSeekFileStore(Uploader uploader) {
        this.uploader = uploader;
    }

    /** 按 sha256 幂等取 file_id；上传失败/响应无 id → empty。 */
    public Optional<String> fileIdFor(byte[] bytes, String filename) {
        String sha = sha256(bytes);
        String cached = bySha.get(sha);
        if (cached != null) {
            return Optional.of(cached);
        }
        try {
            MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
            form.add("file", new NamedByteArrayResource(bytes, filename));
            form.add("purpose", "user_data");
            String body = uploader.upload(form);
            String id = parseFileId(body);
            if (id == null) {
                return Optional.empty();
            }
            bySha.put(sha, id);
            return Optional.of(id);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** 解析上传响应取 file_id。纯函数，供单测。 */
    static String parseFileId(String body) {
        try {
            tools.jackson.databind.JsonNode root = new tools.jackson.databind.ObjectMapper().readTree(body);
            String id = root.path("id").asText(null);
            return id == null || id.isBlank() ? null : id;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * multipart 文件名：{@link org.springframework.core.io.ByteArrayResource} 不带文件名，
     * Spring 序列化 multipart 时取 {@code getFilename()} 拿到 null，文件名会缺失——故自定义。
     */
    static final class NamedByteArrayResource extends org.springframework.core.io.ByteArrayResource {
        private final String filename;

        NamedByteArrayResource(byte[] bytes, String filename) {
            super(bytes);
            this.filename = filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }
}
```

`DeepSeekThinkingChatModel.java`（构造加第四参 + registerMedia 支持 files 降级）：

```java
    private final io.github.javaside.springai.codetui.agent.media.DeepSeekVisionMediaRegistry visionRegistry;
    /** 可空：files 通道上传器（bytes+filename → file_id）；null = 纯内联。 */
    private final java.util.function.BiFunction<byte[], String, java.util.Optional<String>> fileUploader;

    DeepSeekThinkingChatModel(ChatModel defaultDelegate,
                              Function<ThinkingConfig, ChatModel> delegateFactory,
                              io.github.javaside.springai.codetui.agent.media.DeepSeekVisionMediaRegistry visionRegistry,
                              java.util.function.BiFunction<byte[], String, java.util.Optional<String>> fileUploader) {
        this.defaultDelegate = defaultDelegate;
        this.delegateFactory = delegateFactory;
        this.visionRegistry = visionRegistry;
        this.fileUploader = fileUploader;
    }
```

`registerMedia` 内注册处改为（其余不动）：

```java
                String mime = md.getMimeType() == null ? "image/png" : md.getMimeType().toString();
                visionRegistry.put(i, j, entryFor(bytes, mime, md));
                keys.add(io.github.javaside.springai.codetui.agent.media.DeepSeekVisionMediaRegistry.key(i, j));
```

新增私有方法：

```java
    /** files 通道优先（上传成功用 file_id），失败/未配置降级内联。 */
    private io.github.javaside.springai.codetui.agent.media.DeepSeekVisionMediaRegistry.Entry entryFor(
            byte[] bytes, String mime, Media md) {
        if (fileUploader == null) {
            return io.github.javaside.springai.codetui.agent.media.DeepSeekVisionMediaRegistry.Entry.inline(bytes, mime);
        }
        String filename = md.getName() == null || md.getName().isBlank() ? "image.png" : md.getName();
        java.util.Optional<String> fileId = fileUploader.apply(bytes, filename);
        return fileId
                .map(io.github.javaside.springai.codetui.agent.media.DeepSeekVisionMediaRegistry.Entry::file)
                .orElseGet(() -> io.github.javaside.springai.codetui.agent.media.DeepSeekVisionMediaRegistry.Entry.inline(bytes, mime));
    }
```

**更新 Task 4 的调用点**（构造加第四参，`DeepSeekThinkingChatModelVisionTest` 三处 `new DeepSeekThinkingChatModel(d, c -> d, registry)` 改为 `new DeepSeekThinkingChatModel(d, c -> d, registry, null)`）。

`DeepSeekProvider.java`（chatModel() 里按 transport 组装 uploader）：

```java
        // 视觉传输通道：files 走 Files API（sha 幂等上传 → file_id），失败自动降级内联；默认内联。
        java.util.function.BiFunction<byte[], String, java.util.Optional<String>> fileUploader = null;
        if (visionTransportFor(System.getenv("DEEPSEEK_VISION_TRANSPORT")) == VisionTransport.FILES) {
            io.github.javaside.springai.codetui.agent.media.DeepSeekFileStore store =
                    new io.github.javaside.springai.codetui.agent.media.DeepSeekFileStore(form -> {
                        org.springframework.web.client.RestClient rc = org.springframework.web.client.RestClient.builder()
                                .baseUrl(baseUrl)
                                .defaultHeader("Authorization", "Bearer " + apiKey)
                                .requestFactory(rf)
                                .build();
                        return rc.post().uri("/files")
                                .contentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA)
                                .body(form)
                                .retrieve()
                                .body(String.class);
                    });
            fileUploader = (bytes, filename) -> store.fileIdFor(bytes, filename);
        }

        ChatModel defaultDelegate = buildDelegate(restBuilder, webBuilder, ThinkingConfig.defaults());
        m = new DeepSeekThinkingChatModel(defaultDelegate,
                config -> buildDelegate(restBuilder, webBuilder, config), visionRegistry, fileUploader);
```

> `rf`（`HttpComponentsClientHttpRequestFactory`）与 `read`/`connect` 在 `chatModel()` 方法体里已有局部变量，
> uploader 闭包直接引用即可。上传 URL：`baseUrl + "/files"`（DeepSeek baseUrl 无 `/v1` 路径，`/files` 是
> 根路径资源）。

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn test -pl springai-code-tui -Dtest=DeepSeekFileStoreTest,DeepSeekThinkingChatModelVisionTest,DeepSeekProviderVisionTest`
Expected: 全绿（含更新后的三参→四参构造调用）。

再跑全模块编译确认接线无遗漏：

Run: `mvn -pl springai-code-tui compile`
Expected: BUILD SUCCESS。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/media/DeepSeekFileStore.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/DeepSeekThinkingChatModel.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/DeepSeekProvider.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/media/DeepSeekFileStoreTest.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/DeepSeekThinkingChatModelVisionTest.java
git commit -m "$(cat <<'EOF'
feat(vision): DeepSeek Files API 通道（sha 幂等 + 失败降级内联）

- DeepSeekFileStore：Uploader 注入点、sha256 幂等缓存、parseFileId 纯函数
- DEEPSEEK_VISION_TRANSPORT=files 走 file_id 引用，上传失败自动降级 base64 内联
EOF
)"
```

---

### Task 6: 真机探针 + 文档

**Files:**
- Create: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/DeepSeekVisionSmokeTest.java`
- Modify: `springai-code-tui/docs/guide/vision.md`
- Modify: `springai-code-tui/README.md`

**Interfaces:**
- Consumes: `DeepSeekProvider`（全链路：registry → ChatModel 注册 → codec 改写）。

- [ ] **Step 1: 写真机探针**

```java
package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.util.MimeTypeUtils;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DeepSeek 视觉真机探针：走完整链路（DeepSeekProvider.chatModel() → DeepSeekThinkingChatModel
 * 注册 → HTTP 层改写 → 真实 API）。验证内联 base64 通道与视觉模型回答。
 *
 * <p><b>门控</b>：需要联网 + 花钱，双 gate 默认跳过（同 CodingAgentSpikeTest 模式）：
 * {@code CODETUI_LIVE_TESTS=1} 且配置了 {@code DEEPSEEK_API_KEY} 才会跑。
 */
@EnabledIfEnvironmentVariable(named = "CODETUI_LIVE_TESTS", matches = "1")
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
class DeepSeekVisionSmokeTest {

    @Test
    void visionModelSeesInlineRedImage() throws Exception {
        String key = System.getenv("DEEPSEEK_API_KEY");
        ChatModel model = new DeepSeekProvider(key).chatModel();

        byte[] png = solidColorPng(Color.RED);
        UserMessage msg = UserMessage.builder()
                .text("这张纯色图片是什么颜色？只答颜色名，不解释。")
                .media(List.of(Media.builder()
                        .mimeType(MimeTypeUtils.parseMimeType("image/png"))
                        .data(png).build()))
                .build();
        ChatResponse resp = model.call(new Prompt(msg,
                DeepSeekChatOptions.builder().model("deepseek-v4-flash-vision-exp").build()));

        String text = resp.getResult().getOutput().getText();
        assertTrue(text != null && !text.isBlank(), "视觉模型应返回非空回答");
        System.out.println("DeepSeek 视觉回答: " + text);
        assertFalse(text.toLowerCase().contains("error") && text.toLowerCase().contains("support"),
                "不应出现 'does not support image' 类错误");
    }

    private static byte[] solidColorPng(Color c) throws Exception {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(c);
        g.fillRect(0, 0, 64, 64);
        g.dispose();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", bo);
        return bo.toByteArray();
    }
}
```

- [ ] **Step 2: 本地真机跑一次（有 key 时）**

Run: `CODETUI_LIVE_TESTS=1 DEEPSEEK_API_KEY=<key> mvn test -pl springai-code-tui -Dtest=DeepSeekVisionSmokeTest`
Expected: PASS，控制台打印视觉回答（如「red / 红色」）；若返回 400 或回答异常，记录并回查（
优先怀疑：模型名拼写、content 改写格式、`thinking` 字段对该模型的行为——后者本期默认不注入）。

无 key 环境跳过即可，不视为失败。

- [ ] **Step 3: 更新 `docs/guide/vision.md`**

按现有文件行号定位，做四处修改：

1. **「哪些模型能看见」表格**（约 L52-58）——DeepSeek 行替换为：

```markdown
| DeepSeek | `deepseek-v4-flash-vision` |
```

2. **内置清单可用性说明**（约 L62-64）——在引语块末尾追加一段：

```markdown
> DeepSeek 的内置清单**已含视觉模型**（`deepseek-v4-flash-vision-exp`，`/model` 直接可切），
> 是除 OpenAI / Anthropic 外第三家内置可用视觉的 provider。
```

3. **「验证范围」节**（约 L109-113）——追加：

```markdown
**DeepSeek**：`deepseek-v4-flash-vision-exp` 的<b>内联 base64 通道</b>已真机验证（纯红图 →
模型答对颜色）。Files API 通道（`DEEPSEEK_VISION_TRANSPORT=files`）已做单测覆盖，未真机验证。
```

4. **「逃生口」节**（约 L103-107）——追加传输开关：

````markdown
DeepSeek 专属传输开关（默认内联；`files` 走 Files API 的 `file_id` 引用，单图上限 64 MiB，
上传失败自动降级内联）：

```bash
export DEEPSEEK_VISION_TRANSPORT=files
```

DeepSeek 每张图按服务端自动缩放后计费，**单张最多 384 token**（远低于本工具按
`宽×高/750` 的估算，预算只会更宽松）。
````

- [ ] **Step 4: 更新 `README.md`**

L106 模型说明句更新为：

```markdown
DeepSeek 现役内置模型为 `deepseek-v4-flash`（非思考）、`deepseek-v4-pro`（强推理）与
`deepseek-v4-flash-vision-exp`（视觉 · 实验，图最多 384 token/张）；旧模型名 `deepseek-chat` /
`deepseek-reasoner` 已停用。
```

- [ ] **Step 5: 全模块测试回归 + 提交**

Run: `mvn test -pl springai-code-tui`（无 key 环境 spike 自动跳过）
Expected: 全绿（或仅有既有 flaky `CodingAgentSpikeTest.todoTurnIdBinding` 的红——与本期无关）。

```bash
git add springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/DeepSeekVisionSmokeTest.java \
        springai-code-tui/docs/guide/vision.md \
        springai-code-tui/README.md
git commit -m "$(cat <<'EOF'
docs(vision): DeepSeek 视觉支持说明 + 真机探针

- vision.md：模型表、内置清单、验证范围、DEEPSEEK_VISION_TRANSPORT 开关与 384 token 计费
- README：内置模型句加入 deepseek-v4-flash-vision-exp
- 新增 DeepSeekVisionSmokeTest 真机探针（CODETUI_LIVE_TESTS=1 才跑）
EOF
)"
```

---

## 自检

**Spec 覆盖**：
- §4.1 名单/清单/capabilities → Task 1 ✓
- §4.2 内联通道（registry → codec → ChatModel → connector/Provider）→ Task 2/3/4 ✓
- §4.3 Files 通道 + env 开关 + 降级 → Task 5 ✓
- §4.4 预算校准（384 token 计费说明，代码不改估算）→ Task 6 文档 ✓
- §4.5 开关与降级（`CODETUI_VISION` 沿用、`DEEPSEEK_VISION_TRANSPORT` 新增、失败降级内联）→ Task 5/6 ✓
- §6 测试策略（名单/registry/codec/chatmodel/fileStore/探针）→ Task 1/2/3/4/5/6 ✓
- §5 文件清单 → 全部覆盖 ✓

**占位符扫描**：所有测试与实现代码均为完整可编译片段；唯一「留白」是 Task 1 Step 2 前对
`visionTransportFor("  FILES  ")` 语义的说明（实现与测试已一致：不去空格、默认内联）。

**类型一致性**：
- `DeepSeekVisionMediaRegistry.Entry.inline(bytes, mime)` / `.file(fileId)` —— 全计划一致。
- `key(i, j)` 静态方法在 registry/chatmodel/codec 三处同签名。
- `decorate(body, config, registry)` / `decorateStreaming(body, config, registry)` 三参重载一致。
- `DeepSeekThinkingChatModel` 构造：Task 4 三参 → Task 5 四参（**已列明需更新的调用点**：
  `DeepSeekProvider.chatModel()` + `DeepSeekThinkingChatModelVisionTest` 三处）。
- `DeepSeekThinkingClientHttpConnector(delegate, config, registry)` 构造一致。

---

## 执行交接

计划已保存到 `docs/superpowers/plans/2026-08-21-deepseek-vision.md`。两种执行方式：

**1. Subagent-Driven（推荐）** — 每个任务派发独立 subagent，任务间人工 review，迭代快。

**2. Inline Execution** — 本会话内用 executing-plans 批量执行，带检查点。

选哪种？

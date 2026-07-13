# 文件内容不入会话记忆（媒体即时外置 + 文本回合间外置）Implementation Plan

> **⚠️ 本计划已实现并上线，随后据真实 session 修了 8 处缺陷。** 下面 13 步是**首版计划原文**，其中若干 Task 的示例代码有 bug（假 MCP 契约、32KB 阈值、`existing-`+hashCode 伪 sha、手写魔数表、normalize 路径判断等）。**不要照抄本计划重新实现**——真实实现见代码，修正清单见 spec 顶部「⚠️ 实现修正」表与长期记忆 `file-content-not-in-session-memory`。本文保留作历史。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让任何文件的内容（图片/视频/二进制、大文本）都不再驻留会话记忆——会话历史只存紧凑引用，非视觉模型永不收到媒体字节。

**Architecture:** 两条外置路径共用一套 artifact/引用设施。①**媒体即时外置**：`MediaExternalizingCallback` 装饰每个工具（嵌在 `ToolEventCallback` 内层），检测 MCP 图像块/Read 二进制/通用二进制，当场换引用（字节从不进模型）。②**文本回合间外置**：`SessionFileExternalizer` 在 `CodingAgent.submit()` 开头（搭现有 sanitize 那趟）把过往回合携带文件全文的 `ToolResponseMessage` 换引用；本回合的读全程保留全文。能力开关 `ModelCapabilities`（现恒 `TEXT_ONLY`）+ 策略 `ToolResultMediaHandler` 是接视觉模型的两个扩展位。

**Tech Stack:** Java 21、Spring AI 2.0（`ToolCallback`/`SessionEvent`/`ToolResponseMessage`）、Jackson 3（`tools.jackson.databind.ObjectMapper`）、JUnit 5（手写桩、无 mock 框架）、Maven 模块 `springai-code-tui`。

**Spec:** `docs/superpowers/specs/2026-07-13-capability-aware-media-externalization-design.md`

**关键前置结论（S0 已核实，字节码）：**
- MCP 工具结果串 = `JsonHelper.toJson(CallToolResult.content())` = **内容块 List 的 JSON 顶层数组**；每块有权威判别符 **`type`** ∈ `text|image|audio|resource`；`ImageContent` = `{"type":"image","data":"<base64>","mimeType":"image/png"}`（驼峰）。检测以 `type` 为准。
- 工具循环每迭代重进 `SessionMemoryAdvisor.before()` 重读会话存储 → 文本外置**只能回合间做**（`submit()` 开头、动过往事件），不能落库处。
- 数据结构：`ToolResponseMessage.getResponses()` → `List<ToolResponse(id,name,responseData)>`（全 String）；发起调用在同 batch 之前的 `AssistantMessage.getToolCalls()` → `List<ToolCall(id,type,name,arguments)>`，文件路径在 `arguments`（JSON）。
- 内置 Read：`@Tool(name="Read")`，入参 JSON 键为 `filePath`（描述文档写作 `file_path`，两者都试）。

**约定：**
- 包：`io.github.javaside.springai.codetui.agent`，新增子包 `agent.media`（媒体设施集中，边界清晰）。
- 测试命令一律模块作用域 + 屏蔽网络门控 spike：`env -u DEEPSEEK_API_KEY -u OPENAI_API_KEY -u ANTHROPIC_API_KEY mvn -q -pl springai-code-tui test -Dtest=<Class>`（整仓会被空模块打挂，见项目记忆）。
- 每个 Task 末尾提交；提交信息用中文、结尾带 `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`。

---

## File Structure

**新建（`src/main/java/io/github/javaside/springai/codetui/agent/media/`）：**
- `MediaKind.java` — 枚举 `IMAGE|VIDEO|BINARY|TEXT`。
- `ArtifactSource.java` — 枚举 `EXISTING_FILE|MATERIALIZED`。
- `MediaArtifact.java` — record，一份外置产物的全部元信息。
- `MagicSniffer.java` — 魔数嗅探 → `(MediaKind, mime, ext)`。
- `ImageDimensions.java` — 解 PNG/JPEG 头拿宽高。
- `BinarySniff.java` — 判定一段 String 是否疑似二进制。
- `MediaArtifactStore.java` — 内容寻址存储：`put(bytes, declaredMime) → MediaArtifact`。
- `FileReference.java` — 结构化引用块的构造与识别。
- `McpMediaParser.java` — 解析 MCP 结果串（顶层内容块数组）→ 媒体块/文本块。
- `ModelCapabilities.java` — 能力快照 record。
- `ToolResultMediaHandler.java` — 策略接口。
- `TextReferenceMediaHandler.java` — 本期唯一实现（恒引用）。
- `MediaExternalizingCallback.java` — 路径①装饰器。
- `SessionFileExternalizer.java` — 路径②回合间外置器。

**修改：**
- `LlmProvider.java` — 加 `default ModelCapabilities capabilities(String modelId)`。
- `AgentTools.java:216-221` — 装饰循环里把 `MediaExternalizingCallback` 嵌进 `ToolEventCallback`。
- `CodingAgent.java` — `submit()` 开头调 `SessionFileExternalizer`；`toolContext` 加能力快照。

**测试（`src/test/java/.../agent/media/` 与 `.../agent/`）：** 每个新建类一枚对应 `*Test`。

---

## Task 1: ModelCapabilities + LlmProvider 扩展位

**Files:**
- Create: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/media/ModelCapabilities.java`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/LlmProvider.java`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/media/ModelCapabilitiesTest.java`

- [ ] **Step 1: 写失败测试**

```java
// ModelCapabilitiesTest.java
package io.github.javaside.springai.codetui.agent.media;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModelCapabilitiesTest {
    @Test
    void textOnly_bothFalse() {
        ModelCapabilities c = ModelCapabilities.TEXT_ONLY;
        assertFalse(c.supportsImageInput());
        assertFalse(c.supportsVideoInput());
    }

    @Test
    void carriesFlags() {
        ModelCapabilities c = new ModelCapabilities(true, false);
        assertTrue(c.supportsImageInput());
        assertFalse(c.supportsVideoInput());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `env -u DEEPSEEK_API_KEY -u OPENAI_API_KEY -u ANTHROPIC_API_KEY mvn -q -pl springai-code-tui test -Dtest=ModelCapabilitiesTest`
Expected: FAIL 编译错误 `cannot find symbol ModelCapabilities`。

- [ ] **Step 3: 写实现**

```java
// ModelCapabilities.java
package io.github.javaside.springai.codetui.agent.media;

/** 模型能力快照（按模型判定）。随请求冻结进 ToolContext，规避工具执行期间切模型的时序错配。
 *  图与视频分离：支持图不代表支持视频。 */
public record ModelCapabilities(boolean supportsImageInput, boolean supportsVideoInput) {
    /** 纯文本模型（本期全部）。 */
    public static final ModelCapabilities TEXT_ONLY = new ModelCapabilities(false, false);
}
```

在 `LlmProvider.java` 接口体末尾（最后一个方法后）加默认方法，并在文件顶部 import 区加 `import io.github.javaside.springai.codetui.agent.media.ModelCapabilities;`：

```java
    /** 该模型的能力（视觉等）。现全部返回 TEXT_ONLY（零行为变化）；接视觉模型时对应 provider 覆写此方法。 */
    default ModelCapabilities capabilities(String modelId) {
        return ModelCapabilities.TEXT_ONLY;
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `env -u DEEPSEEK_API_KEY -u OPENAI_API_KEY -u ANTHROPIC_API_KEY mvn -q -pl springai-code-tui test -Dtest=ModelCapabilitiesTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/media/ModelCapabilities.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/LlmProvider.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/media/ModelCapabilitiesTest.java
git commit -m "feat(media): ModelCapabilities 能力快照 + LlmProvider.capabilities 扩展位(恒 TEXT_ONLY)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: 枚举 + MediaArtifact record

**Files:**
- Create: `.../agent/media/MediaKind.java`, `.../agent/media/ArtifactSource.java`, `.../agent/media/MediaArtifact.java`
- Test: `.../agent/media/MediaArtifactTest.java`

- [ ] **Step 1: 写失败测试**

```java
// MediaArtifactTest.java
package io.github.javaside.springai.codetui.agent.media;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class MediaArtifactTest {
    @Test
    void shortIdIsFirst16OfSha() {
        MediaArtifact a = new MediaArtifact(
                "a".repeat(64), Path.of("/x/.codetui/artifacts/" + "a".repeat(64) + ".png"),
                ".codetui/artifacts/" + "a".repeat(64) + ".png",
                "image/png", "image/png", MediaKind.IMAGE, 100L, 12, 34, null,
                ArtifactSource.MATERIALIZED, true);
        assertEquals("a".repeat(16), a.shortId());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `env -u DEEPSEEK_API_KEY -u OPENAI_API_KEY -u ANTHROPIC_API_KEY mvn -q -pl springai-code-tui test -Dtest=MediaArtifactTest`
Expected: FAIL 编译错误（类不存在）。

- [ ] **Step 3: 写实现**

```java
// MediaKind.java
package io.github.javaside.springai.codetui.agent.media;
public enum MediaKind { IMAGE, VIDEO, BINARY, TEXT }
```

```java
// ArtifactSource.java
package io.github.javaside.springai.codetui.agent.media;
/** EXISTING_FILE：项目内原文件，不复制、引用指原路径；MATERIALIZED：无磁盘原件，字节存进 artifact store。 */
public enum ArtifactSource { EXISTING_FILE, MATERIALIZED }
```

```java
// MediaArtifact.java
package io.github.javaside.springai.codetui.agent.media;

import java.nio.file.Path;

/** 一份外置产物的元信息。会话里只存它派生的引用块（见 FileReference），不存字节。
 *  @param sha            完整 SHA-256 十六进制（内容寻址）
 *  @param path           磁盘绝对路径（EXISTING_FILE=原文件；MATERIALIZED=artifact 文件）
 *  @param relativePath   相对项目 root 的短路径（写进引用给模型看/可 Read）
 *  @param mimeType       实际类型（magic 嗅探；未知用 application/octet-stream）
 *  @param declaredMimeType 声明类型（MCP 的 mimeType；可空）
 *  @param width/height   仅图片、且能解析时非空
 *  @param lineCount      仅文本、可空
 *  @param ownedByStore   true=artifact store 拥有该文件；false=指向项目内既有文件 */
public record MediaArtifact(
        String sha, Path path, String relativePath,
        String mimeType, String declaredMimeType, MediaKind kind,
        long size, Integer width, Integer height, Integer lineCount,
        ArtifactSource source, boolean ownedByStore) {

    /** 显示用短 id = 完整 sha 前 16 位。 */
    public String shortId() { return sha.substring(0, 16); }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `env -u DEEPSEEK_API_KEY -u OPENAI_API_KEY -u ANTHROPIC_API_KEY mvn -q -pl springai-code-tui test -Dtest=MediaArtifactTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/media/MediaKind.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/media/ArtifactSource.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/media/MediaArtifact.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/media/MediaArtifactTest.java
git commit -m "feat(media): MediaKind/ArtifactSource 枚举 + MediaArtifact record

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: MagicSniffer（魔数嗅探，MIME 不可信）

**Files:**
- Create: `.../agent/media/MagicSniffer.java`
- Test: `.../agent/media/MagicSnifferTest.java`

- [ ] **Step 1: 写失败测试**

```java
// MagicSnifferTest.java
package io.github.javaside.springai.codetui.agent.media;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MagicSnifferTest {
    private static byte[] bytes(int... b) {
        byte[] out = new byte[b.length];
        for (int i = 0; i < b.length; i++) out[i] = (byte) b[i];
        return out;
    }

    @Test
    void png() {
        MagicSniffer.Sniffed s = MagicSniffer.sniff(bytes(0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A));
        assertEquals(MediaKind.IMAGE, s.kind());
        assertEquals("image/png", s.mimeType());
        assertEquals("png", s.ext());
    }

    @Test
    void jpeg() {
        assertEquals("image/jpeg", MagicSniffer.sniff(bytes(0xFF,0xD8,0xFF,0xE0)).mimeType());
    }

    @Test
    void pdf() {
        assertEquals("application/pdf", MagicSniffer.sniff(bytes(0x25,0x50,0x44,0x46,0x2D)).mimeType());
    }

    @Test
    void mp4_ftypAtOffset4() {
        assertEquals(MediaKind.VIDEO,
                MagicSniffer.sniff(bytes(0,0,0,0x18,0x66,0x74,0x79,0x70,0x69,0x73,0x6F,0x6D)).kind());
    }

    @Test
    void unknown_isBinaryOctetStreamBin() {
        MagicSniffer.Sniffed s = MagicSniffer.sniff(bytes(0x01,0x02,0x03,0x04));
        assertEquals(MediaKind.BINARY, s.kind());
        assertEquals("application/octet-stream", s.mimeType());
        assertEquals("bin", s.ext());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `env -u DEEPSEEK_API_KEY -u OPENAI_API_KEY -u ANTHROPIC_API_KEY mvn -q -pl springai-code-tui test -Dtest=MagicSnifferTest`
Expected: FAIL 编译错误。

- [ ] **Step 3: 写实现**

```java
// MagicSniffer.java
package io.github.javaside.springai.codetui.agent.media;

/** 按文件头魔数判类型——MCP 的 mimeType 是外部输入不可信，一律以实际魔数为准。 */
public final class MagicSniffer {
    private MagicSniffer() {}

    public record Sniffed(MediaKind kind, String mimeType, String ext) {}

    private static final Sniffed UNKNOWN =
            new Sniffed(MediaKind.BINARY, "application/octet-stream", "bin");

    public static Sniffed sniff(byte[] b) {
        if (starts(b, 0x89,0x50,0x4E,0x47)) return new Sniffed(MediaKind.IMAGE, "image/png", "png");
        if (starts(b, 0xFF,0xD8,0xFF))      return new Sniffed(MediaKind.IMAGE, "image/jpeg", "jpg");
        if (starts(b, 0x47,0x49,0x46,0x38)) return new Sniffed(MediaKind.IMAGE, "image/gif", "gif");
        if (starts(b, 0x52,0x49,0x46,0x46) && at(b,8,0x57,0x45,0x42,0x50))
            return new Sniffed(MediaKind.IMAGE, "image/webp", "webp");
        if (starts(b, 0x25,0x50,0x44,0x46)) return new Sniffed(MediaKind.BINARY, "application/pdf", "pdf");
        if (starts(b, 0x50,0x4B,0x03,0x04)) return new Sniffed(MediaKind.BINARY, "application/zip", "zip");
        if (starts(b, 0x1A,0x45,0xDF,0xA3)) return new Sniffed(MediaKind.VIDEO, "video/webm", "webm");
        if (at(b,4,0x66,0x74,0x79,0x70))    return new Sniffed(MediaKind.VIDEO, "video/mp4", "mp4");
        return UNKNOWN;
    }

    private static boolean starts(byte[] b, int... sig) { return at(b, 0, sig); }

    private static boolean at(byte[] b, int off, int... sig) {
        if (b == null || b.length < off + sig.length) return false;
        for (int i = 0; i < sig.length; i++) if ((b[off + i] & 0xFF) != sig[i]) return false;
        return true;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `env -u DEEPSEEK_API_KEY -u OPENAI_API_KEY -u ANTHROPIC_API_KEY mvn -q -pl springai-code-tui test -Dtest=MagicSnifferTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/media/MagicSniffer.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/media/MagicSnifferTest.java
git commit -m "feat(media): MagicSniffer 魔数嗅探(magic 优先于声明 MIME)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: ImageDimensions（PNG/JPEG 宽高）

**Files:**
- Create: `.../agent/media/ImageDimensions.java`
- Test: `.../agent/media/ImageDimensionsTest.java`

- [ ] **Step 1: 写失败测试**

```java
// ImageDimensionsTest.java
package io.github.javaside.springai.codetui.agent.media;

import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class ImageDimensionsTest {
    /** 构造最小 PNG 头：8 字节签名 + IHDR 长度/类型 + width(4)/height(4)。 */
    private static byte[] png(int w, int h) {
        byte[] b = new byte[33];
        int[] sig = {0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A};
        for (int i = 0; i < 8; i++) b[i] = (byte) sig[i];
        // b[8..15] = IHDR chunk length(4) + "IHDR"(4)，值无关紧要
        putInt(b, 16, w);
        putInt(b, 20, h);
        return b;
    }
    private static void putInt(byte[] b, int off, int v) {
        b[off] = (byte)(v>>>24); b[off+1] = (byte)(v>>>16); b[off+2] = (byte)(v>>>8); b[off+3] = (byte) v;
    }

    @Test
    void pngWidthHeight() {
        Optional<int[]> d = ImageDimensions.of(png(2400, 1632));
        assertTrue(d.isPresent());
        assertArrayEquals(new int[]{2400, 1632}, d.get());
    }

    @Test
    void tooShort_empty() {
        assertTrue(ImageDimensions.of(new byte[]{1,2,3}).isEmpty());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `env -u DEEPSEEK_API_KEY -u OPENAI_API_KEY -u ANTHROPIC_API_KEY mvn -q -pl springai-code-tui test -Dtest=ImageDimensionsTest`
Expected: FAIL 编译错误。

- [ ] **Step 3: 写实现**

```java
// ImageDimensions.java
package io.github.javaside.springai.codetui.agent.media;

import java.util.Optional;

/** 只解 PNG/JPEG 文件头拿宽高（不解码像素）。越界/未知/损坏一律返回空。 */
public final class ImageDimensions {
    private ImageDimensions() {}

    /** @return {width, height}，无法确定时 empty。 */
    public static Optional<int[]> of(byte[] b) {
        if (b == null) return Optional.empty();
        if (isPng(b)) return png(b);
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) return jpeg(b);
        return Optional.empty();
    }

    private static boolean isPng(byte[] b) {
        return b.length >= 24 && (b[0] & 0xFF) == 0x89 && (b[1] & 0xFF) == 0x50
                && (b[2] & 0xFF) == 0x4E && (b[3] & 0xFF) == 0x47;
    }

    private static Optional<int[]> png(byte[] b) {
        // PNG IHDR：宽在 offset 16、高在 offset 20，各 4 字节大端。
        int w = int32(b, 16), h = int32(b, 20);
        return (w > 0 && h > 0) ? Optional.of(new int[]{w, h}) : Optional.empty();
    }

    private static Optional<int[]> jpeg(byte[] b) {
        // 扫 SOF0..SOF3/SOF5..SOF7/SOF9..SOF11 段：0xFF 后跟 SOF marker，段内 offset+5 起为 height(2)/width(2)。
        int i = 2;
        while (i + 9 < b.length) {
            if ((b[i] & 0xFF) != 0xFF) { i++; continue; }
            int marker = b[i + 1] & 0xFF;
            if (isSof(marker)) {
                int h = int16(b, i + 5), w = int16(b, i + 7);
                return (w > 0 && h > 0) ? Optional.of(new int[]{w, h}) : Optional.empty();
            }
            int len = int16(b, i + 2);
            if (len < 2) return Optional.empty();
            i += 2 + len;
        }
        return Optional.empty();
    }

    private static boolean isSof(int m) {
        return (m >= 0xC0 && m <= 0xC3) || (m >= 0xC5 && m <= 0xC7) || (m >= 0xC9 && m <= 0xCB);
    }

    private static int int32(byte[] b, int o) {
        return ((b[o] & 0xFF) << 24) | ((b[o+1] & 0xFF) << 16) | ((b[o+2] & 0xFF) << 8) | (b[o+3] & 0xFF);
    }
    private static int int16(byte[] b, int o) { return ((b[o] & 0xFF) << 8) | (b[o+1] & 0xFF); }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `env -u DEEPSEEK_API_KEY -u OPENAI_API_KEY -u ANTHROPIC_API_KEY mvn -q -pl springai-code-tui test -Dtest=ImageDimensionsTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/media/ImageDimensions.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/media/ImageDimensionsTest.java
git commit -m "feat(media): ImageDimensions 解 PNG/JPEG 头拿宽高

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: MediaArtifactStore（内容寻址、原子写、magic 优先）

**Files:**
- Create: `.../agent/media/MediaArtifactStore.java`
- Test: `.../agent/media/MediaArtifactStoreTest.java`

- [ ] **Step 1: 写失败测试**

```java
// MediaArtifactStoreTest.java
package io.github.javaside.springai.codetui.agent.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class MediaArtifactStoreTest {
    private static byte[] png() {
        byte[] b = new byte[33];
        int[] sig = {0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A};
        for (int i = 0; i < 8; i++) b[i] = (byte) sig[i];
        b[16]=0;b[17]=0;b[18]=0;b[19]=10; b[20]=0;b[21]=0;b[22]=0;b[23]=20; // 10x20
        return b;
    }

    @Test
    void put_writesContentAddressedFile_magicOverridesDeclared(@TempDir Path root) throws Exception {
        MediaArtifactStore store = new MediaArtifactStore(root.resolve(".codetui/artifacts"), root);
        // 声明成 jpeg，但字节是 png → 以 magic 为准
        MediaArtifact a = store.put(png(), "image/jpeg");

        assertEquals("image/png", a.mimeType());
        assertEquals("image/jpeg", a.declaredMimeType());
        assertEquals(MediaKind.IMAGE, a.kind());
        assertEquals(10, a.width());
        assertEquals(20, a.height());
        assertTrue(a.ownedByStore());
        assertEquals(ArtifactSource.MATERIALIZED, a.source());
        assertTrue(Files.exists(a.path()), "artifact 文件应落盘");
        assertTrue(a.path().getFileName().toString().equals(a.sha() + ".png"), "文件名=完整 sha.ext");
        assertTrue(a.relativePath().startsWith(".codetui/artifacts/"));
    }

    @Test
    void put_idempotent_sameContentSameFile(@TempDir Path root) {
        MediaArtifactStore store = new MediaArtifactStore(root.resolve(".codetui/artifacts"), root);
        MediaArtifact a = store.put(png(), "image/png");
        MediaArtifact b = store.put(png(), "image/png");
        assertEquals(a.sha(), b.sha());
        assertEquals(a.path(), b.path());
    }

    @Test
    void put_lazilyCreatesDir(@TempDir Path root) {
        Path dir = root.resolve(".codetui/artifacts");
        assertFalse(Files.exists(dir));
        new MediaArtifactStore(dir, root).put(png(), "image/png");
        assertTrue(Files.exists(dir));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `env -u DEEPSEEK_API_KEY -u OPENAI_API_KEY -u ANTHROPIC_API_KEY mvn -q -pl springai-code-tui test -Dtest=MediaArtifactStoreTest`
Expected: FAIL 编译错误。

- [ ] **Step 3: 写实现**

```java
// MediaArtifactStore.java
package io.github.javaside.springai.codetui.agent.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

/** 内容寻址存储：把字节落到 &lt;artifactsDir&gt;/&lt;完整sha&gt;.&lt;ext&gt;。幂等去重、原子写、惰性建目录。
 *  一切 IO 失败走 slf4j（日志绝不含字节内容），失败时抛 RuntimeException 由上层降级为占位（绝不退回原始字节）。 */
public final class MediaArtifactStore {
    private static final Logger log = LoggerFactory.getLogger(MediaArtifactStore.class);

    private final Path artifactsDir;
    private final Path root;

    public MediaArtifactStore(Path artifactsDir, Path root) {
        this.artifactsDir = artifactsDir;
        this.root = root;
    }

    /** 存字节 → 产物（source=MATERIALIZED, ownedByStore=true）。 */
    public MediaArtifact put(byte[] bytes, String declaredMimeType) {
        MagicSniffer.Sniffed sniffed = MagicSniffer.sniff(bytes);
        String sha = sha256Hex(bytes);
        String fileName = sha + "." + sniffed.ext();
        Path target = artifactsDir.resolve(fileName);
        try {
            Files.createDirectories(artifactsDir);
            if (!Files.exists(target)) {
                writeAtomic(target, bytes);
            }
        } catch (IOException e) {
            log.warn("写 artifact 失败 {}：{}", fileName, e.toString());   // 不打印 bytes
            throw new IllegalStateException("artifact 写入失败", e);
        }
        Optional<int[]> dim = ImageDimensions.of(bytes);
        return new MediaArtifact(
                sha, target, root.relativize(target).toString(),
                sniffed.mimeType(), declaredMimeType, sniffed.kind(),
                bytes.length,
                dim.map(d -> d[0]).orElse(null), dim.map(d -> d[1]).orElse(null), null,
                ArtifactSource.MATERIALIZED, true);
    }

    private void writeAtomic(Path target, byte[] bytes) throws IOException {
        Path tmp = Files.createTempFile(artifactsDir, ".art-", ".tmp");
        try {
            Files.write(tmp, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (FileAlreadyExistsException | AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `env -u DEEPSEEK_API_KEY -u OPENAI_API_KEY -u ANTHROPIC_API_KEY mvn -q -pl springai-code-tui test -Dtest=MediaArtifactStoreTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/media/MediaArtifactStore.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/media/MediaArtifactStoreTest.java
git commit -m "feat(media): MediaArtifactStore 内容寻址+原子写+magic优先+惰性建目录

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: FileReference（结构化引用块）

**Files:**
- Create: `.../agent/media/FileReference.java`
- Test: `.../agent/media/FileReferenceTest.java`

- [ ] **Step 1: 写失败测试**

```java
// FileReferenceTest.java
package io.github.javaside.springai.codetui.agent.media;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class FileReferenceTest {
    private static MediaArtifact img() {
        return new MediaArtifact("a".repeat(64), Path.of("/x/a.png"),
                ".codetui/artifacts/" + "a".repeat(64) + ".png",
                "image/png", "image/png", MediaKind.IMAGE, 519531L, 2400, 1632, null,
                ArtifactSource.MATERIALIZED, true);
    }

    @Test
    void renders_hasMarkersAndFields_noBase64() {
        String ref = FileReference.render(img(), "reference_only", "当前模型无视觉能力，未发送图像内容");
        assertTrue(ref.startsWith("[file reference]"));
        assertTrue(ref.contains("[/file reference]"));
        assertTrue(ref.contains("kind: image"));
        assertTrue(ref.contains("mime_type: image/png"));
        assertTrue(ref.contains("size_bytes: 519531"));
        assertTrue(ref.contains("dimensions: 2400x1632"));
        assertTrue(ref.contains(".codetui/artifacts/"));
        assertTrue(ref.contains("delivery: reference_only"));
    }

    @Test
    void isReference_detectsMarker() {
        assertTrue(FileReference.isReference("blah\n[file reference]\n...\n[/file reference]"));
        assertFalse(FileReference.isReference("just some normal tool output"));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `env -u DEEPSEEK_API_KEY -u OPENAI_API_KEY -u ANTHROPIC_API_KEY mvn -q -pl springai-code-tui test -Dtest=FileReferenceTest`
Expected: FAIL 编译错误。

- [ ] **Step 3: 写实现**

```java
// FileReference.java
package io.github.javaside.springai.codetui.agent.media;

/** 会话里替换文件内容的结构化引用块：稳定、可再解析、语言无关。UI 可再渲成中文短句。 */
public final class FileReference {
    private FileReference() {}

    public static final String OPEN = "[file reference]";
    public static final String CLOSE = "[/file reference]";

    /** 幂等判定：一段工具结果里是否已含引用块（含则不再重复外置）。 */
    public static boolean isReference(String s) {
        return s != null && s.contains(OPEN);
    }

    /** 渲染引用块。dimensions/lines 仅在有值时出现。 */
    public static String render(MediaArtifact a, String delivery, String reason) {
        StringBuilder b = new StringBuilder();
        b.append(OPEN).append('\n');
        b.append("id: sha256:").append(a.shortId()).append('\n');
        b.append("kind: ").append(a.kind().name().toLowerCase()).append('\n');
        b.append("mime_type: ").append(a.mimeType()).append('\n');
        if (a.declaredMimeType() != null && !a.declaredMimeType().equals(a.mimeType())) {
            b.append("declared_mime_type: ").append(a.declaredMimeType()).append('\n');
        }
        b.append("size_bytes: ").append(a.size()).append('\n');
        if (a.width() != null && a.height() != null) {
            b.append("dimensions: ").append(a.width()).append('x').append(a.height()).append('\n');
        }
        if (a.lineCount() != null) {
            b.append("lines: ").append(a.lineCount()).append('\n');
        }
        b.append("path: ").append(a.relativePath()).append('\n');
        b.append("delivery: ").append(delivery).append('\n');
        b.append("reason: ").append(reason).append('\n');
        b.append(CLOSE);
        return b.toString();
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `env -u DEEPSEEK_API_KEY -u OPENAI_API_KEY -u ANTHROPIC_API_KEY mvn -q -pl springai-code-tui test -Dtest=FileReferenceTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/media/FileReference.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/media/FileReferenceTest.java
git commit -m "feat(media): FileReference 结构化引用块(render + isReference 幂等判定)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: BinarySniff（String 是否疑似二进制）

**Files:**
- Create: `.../agent/media/BinarySniff.java`
- Test: `.../agent/media/BinarySniffTest.java`

- [ ] **Step 1: 写失败测试**

```java
// BinarySniffTest.java
package io.github.javaside.springai.codetui.agent.media;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BinarySniffTest {
    @Test
    void nulByte_isBinary() {
        assertTrue(BinarySniff.looksBinary("abc\u0000def"));
    }

    @Test
    void manyReplacementChars_isBinary() {
        assertTrue(BinarySniff.looksBinary("\uFFFD\uFFFD\uFFFD\uFFFDx"));
    }

    @Test
    void plainText_isNotBinary() {
        assertFalse(BinarySniff.looksBinary("normal source code\nline 2\n{json:1}"));
    }

    @Test
    void empty_isNotBinary() {
        assertFalse(BinarySniff.looksBinary(""));
        assertFalse(BinarySniff.looksBinary(null));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `env -u DEEPSEEK_API_KEY -u OPENAI_API_KEY -u ANTHROPIC_API_KEY mvn -q -pl springai-code-tui test -Dtest=BinarySniffTest`
Expected: FAIL 编译错误。

- [ ] **Step 3: 写实现**

```java
// BinarySniff.java
package io.github.javaside.springai.codetui.agent.media;

/** 判定一段 String 是否疑似二进制（工具把二进制文件解码成文本后的特征）：
 *  含 NUL、或替换符/控制字符占比过高。只看前若干字符，成本恒定。 */
public final class BinarySniff {
    private BinarySniff() {}

    private static final int SAMPLE = 4096;
    private static final double RATIO = 0.30;

    public static boolean looksBinary(String s) {
        if (s == null || s.isEmpty()) return false;
        int n = Math.min(s.length(), SAMPLE);
        int suspicious = 0;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c == '\u0000') return true;                       // NUL：铁证
            if (c == '\uFFFD') { suspicious++; continue; }        // 解码失败替换符
            boolean allowedControl = (c == '\n' || c == '\r' || c == '\t');
            if (c < 0x20 && !allowedControl) suspicious++;        // 其它控制字符
        }
        return (double) suspicious / n > RATIO;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `env -u DEEPSEEK_API_KEY -u OPENAI_API_KEY -u ANTHROPIC_API_KEY mvn -q -pl springai-code-tui test -Dtest=BinarySniffTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/media/BinarySniff.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/media/BinarySniffTest.java
git commit -m "feat(media): BinarySniff 判定 String 是否疑似二进制

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: McpMediaParser（解析 MCP 内容块数组）

**Files:**
- Create: `.../agent/media/McpMediaParser.java`
- Test: `.../agent/media/McpMediaParserTest.java`

> fixture 依据 S0 核实的真实线格式（顶层数组 + `type` 判别符 + 驼峰 `mimeType`）。

- [ ] **Step 1: 写失败测试**

```java
// McpMediaParserTest.java
package io.github.javaside.springai.codetui.agent.media;

import org.junit.jupiter.api.Test;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class McpMediaParserTest {
    private static String pngB64() {
        byte[] b = new byte[]{(byte)0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A};
        return Base64.getEncoder().encodeToString(b);
    }

    @Test
    void parsesTopLevelArray_imageBlockDetected_textKept() {
        String result = "[{\"type\":\"text\",\"text\":\"Took a screenshot\"},"
                + "{\"type\":\"image\",\"data\":\"" + pngB64() + "\",\"mimeType\":\"image/png\"}]";
        McpMediaParser.Parsed p = McpMediaParser.parse(result);
        assertTrue(p.isMcpArray());
        assertEquals(1, p.mediaBlocks().size());
        assertEquals("image/png", p.mediaBlocks().get(0).declaredMimeType());
        assertArrayEquals(new byte[]{(byte)0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A},
                p.mediaBlocks().get(0).bytes());
        assertEquals("Took a screenshot", p.textBlocks().get(0));
    }

    @Test
    void plainText_notMcpArray() {
        assertFalse(McpMediaParser.parse("just a normal string").isMcpArray());
    }

    @Test
    void jsonArrayOfScalars_notMisdetected() {
        McpMediaParser.Parsed p = McpMediaParser.parse("[1,2,3]");
        assertTrue(p.isMcpArray());          // 是数组
        assertTrue(p.mediaBlocks().isEmpty()); // 但无媒体块
    }

    @Test
    void incidentalDataMimeType_butTypeNotImage_notMedia() {
        // 一个恰好有 data/mimeType 字段但 type=text 的块 → 不误判为媒体
        String result = "[{\"type\":\"text\",\"text\":\"x\",\"data\":\"aaa\",\"mimeType\":\"image/png\"}]";
        assertTrue(McpMediaParser.parse(result).mediaBlocks().isEmpty());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `env -u DEEPSEEK_API_KEY -u OPENAI_API_KEY -u ANTHROPIC_API_KEY mvn -q -pl springai-code-tui test -Dtest=McpMediaParserTest`
Expected: FAIL 编译错误。

- [ ] **Step 3: 写实现**

```java
// McpMediaParser.java
package io.github.javaside.springai.codetui.agent.media;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** 解析 MCP 工具结果串（S0 核实：内容块 List 的 JSON 顶层数组，每块靠 type 判别符）。
 *  检测以 type 为准（image/audio/resource-blob = 媒体），不靠「碰巧含 data/mimeType」启发式。 */
public final class McpMediaParser {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 一个媒体块的原始字节 + 声明 MIME。 */
    public record MediaBlock(byte[] bytes, String declaredMimeType) {}

    public record Parsed(boolean isMcpArray, List<MediaBlock> mediaBlocks, List<String> textBlocks) {}

    private static final Parsed NOT_ARRAY = new Parsed(false, List.of(), List.of());

    private McpMediaParser() {}

    public static Parsed parse(String result) {
        if (result == null || result.isBlank()) return NOT_ARRAY;
        String t = result.stripLeading();
        if (!t.startsWith("[")) return NOT_ARRAY;   // 顶层数组才可能是 MCP 内容块
        JsonNode root;
        try {
            root = MAPPER.readTree(result);
        } catch (RuntimeException e) {
            return NOT_ARRAY;   // 畸形 JSON：不误判、不崩
        }
        if (!root.isArray()) return NOT_ARRAY;

        List<MediaBlock> media = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (JsonNode block : root) {
            if (!block.isObject()) continue;              // 标量元素（[1,2,3]）跳过
            JsonNode typeNode = block.get("type");
            String type = typeNode != null ? typeNode.asString() : null;
            if ("text".equals(type)) {
                JsonNode txt = block.get("text");
                if (txt != null) texts.add(txt.asString());
            } else if ("image".equals(type) || "audio".equals(type)) {
                addMedia(media, block.get("data"), mime(block));
            } else if ("resource".equals(type)) {
                JsonNode res = block.get("resource");
                if (res != null && res.isObject()) addMedia(media, res.get("blob"), mime(res));
            }
            // 未知 type：原样忽略（既不当媒体也不当文本）
        }
        return new Parsed(true, media, texts);
    }

    private static void addMedia(List<MediaBlock> out, JsonNode dataNode, String declaredMime) {
        if (dataNode == null || !dataNode.isString()) return;
        try {
            byte[] bytes = Base64.getDecoder().decode(dataNode.asString());
            out.add(new MediaBlock(bytes, declaredMime));
        } catch (IllegalArgumentException e) {
            // 单块 base64 解码失败：只丢该块，不影响其它 text/块
        }
    }

    /** 容驼峰 mimeType 与蛇形 mime_type（后者作他家 server 兼容）。 */
    private static String mime(JsonNode block) {
        JsonNode m = block.get("mimeType");
        if (m == null) m = block.get("mime_type");
        return m != null ? m.asString() : null;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `env -u DEEPSEEK_API_KEY -u OPENAI_API_KEY -u ANTHROPIC_API_KEY mvn -q -pl springai-code-tui test -Dtest=McpMediaParserTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/media/McpMediaParser.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/media/McpMediaParserTest.java
git commit -m "feat(media): McpMediaParser 解析顶层内容块数组(type 判别、驼峰/蛇形兼容)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 9: ToolResultMediaHandler + TextReferenceMediaHandler

**Files:**
- Create: `.../agent/media/ToolResultMediaHandler.java`, `.../agent/media/TextReferenceMediaHandler.java`
- Test: `.../agent/media/TextReferenceMediaHandlerTest.java`

- [ ] **Step 1: 写失败测试**

```java
// TextReferenceMediaHandlerTest.java
package io.github.javaside.springai.codetui.agent.media;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class TextReferenceMediaHandlerTest {
    private static MediaArtifact img() {
        return new MediaArtifact("b".repeat(64), Path.of("/x/b.png"),
                ".codetui/artifacts/b.png", "image/png", "image/png",
                MediaKind.IMAGE, 100L, 8, 8, null, ArtifactSource.MATERIALIZED, true);
    }

    @Test
    void canDeliver_alwaysFalse_thisIteration() {
        ToolResultMediaHandler h = new TextReferenceMediaHandler();
        assertFalse(h.canDeliver(MediaKind.IMAGE, new ModelCapabilities(true, true)));  // 无注入器 → 恒 false
        assertFalse(h.canDeliver(MediaKind.IMAGE, ModelCapabilities.TEXT_ONLY));
    }

    @Test
    void represent_returnsReferenceOnly() {
        String out = new TextReferenceMediaHandler().represent(img(), ModelCapabilities.TEXT_ONLY);
        assertTrue(FileReference.isReference(out));
        assertTrue(out.contains("delivery: reference_only"));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `env -u DEEPSEEK_API_KEY -u OPENAI_API_KEY -u ANTHROPIC_API_KEY mvn -q -pl springai-code-tui test -Dtest=TextReferenceMediaHandlerTest`
Expected: FAIL 编译错误。

- [ ] **Step 3: 写实现**

```java
// ToolResultMediaHandler.java
package io.github.javaside.springai.codetui.agent.media;

/** 表示策略扩展位：给定媒体产物与能力，决定「能否真投递」与「在工具结果里的表示」。 */
public interface ToolResultMediaHandler {
    /** 当前能力下能否把该类媒体真投递给模型（= 模型支持该类输入 && 本链路已接注入器）。 */
    boolean canDeliver(MediaKind kind, ModelCapabilities caps);

    /** 产出该媒体在工具结果里的表示（本期恒引用；视觉分支属 Path B）。 */
    String represent(MediaArtifact media, ModelCapabilities caps);
}
```

```java
// TextReferenceMediaHandler.java
package io.github.javaside.springai.codetui.agent.media;

/** 本期唯一实现：无注入器 → canDeliver 恒 false → 一律输出结构化文本引用。
 *  视觉真注入（canDeliver=true 时的原生 image 块）属 Path B，此处留桩注释。 */
public final class TextReferenceMediaHandler implements ToolResultMediaHandler {

    @Override
    public boolean canDeliver(MediaKind kind, ModelCapabilities caps) {
        // Path B：当接上原生 image 注入器后，这里改为
        //   return kind == MediaKind.IMAGE && caps.supportsImageInput();
        return false;
    }

    @Override
    public String represent(MediaArtifact media, ModelCapabilities caps) {
        // canDeliver=false → reference_only；字节永不进模型。
        return FileReference.render(media, "reference_only",
                "content externalized from session memory; re-read to view");
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `env -u DEEPSEEK_API_KEY -u OPENAI_API_KEY -u ANTHROPIC_API_KEY mvn -q -pl springai-code-tui test -Dtest=TextReferenceMediaHandlerTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/media/ToolResultMediaHandler.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/media/TextReferenceMediaHandler.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/media/TextReferenceMediaHandlerTest.java
git commit -m "feat(media): ToolResultMediaHandler 策略 + TextReferenceMediaHandler(canDeliver 恒 false)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 10: MediaExternalizingCallback（路径①装饰器）

**Files:**
- Create: `.../agent/media/MediaExternalizingCallback.java`
- Test: `.../agent/media/MediaExternalizingCallbackTest.java`

**能力读取键约定：** `ToolContext` 里放 `ModelCapabilities` 用键 `"capabilities"`（Task 11 由 `CodingAgent.submit` 写入）；缺失回退 `TEXT_ONLY`。

- [ ] **Step 1: 写失败测试**

```java
// MediaExternalizingCallbackTest.java
package io.github.javaside.springai.codetui.agent.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.nio.file.*;
import java.util.Base64;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class MediaExternalizingCallbackTest {
    private static byte[] png() {
        return new byte[]{(byte)0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A,0,0,0,0,0,0,0,0,
                0,0,0,10, 0,0,0,20};
    }
    private static ToolCallback delegate(String name, String out) {
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description("d").inputSchema("{}").build();
            }
            @Override public String call(String in) { return call(in, null); }
            @Override public String call(String in, ToolContext ctx) { return out; }
        };
    }
    private static MediaExternalizingCallback wrap(ToolCallback d, Path root) {
        MediaArtifactStore store = new MediaArtifactStore(root.resolve(".codetui/artifacts"), root);
        return new MediaExternalizingCallback(d, store, new TextReferenceMediaHandler(), root);
    }

    @Test
    void mcpImageBlock_externalized_noBase64_textKept(@TempDir Path root) {
        String b64 = Base64.getEncoder().encodeToString(png());
        String mcpOut = "[{\"type\":\"text\",\"text\":\"Took a screenshot\"},"
                + "{\"type\":\"image\",\"data\":\"" + b64 + "\",\"mimeType\":\"image/png\"}]";
        String result = wrap(delegate("browser_screenshot", mcpOut), root).call("{}", null);
        assertFalse(result.contains(b64), "返回串不得含 base64");
        assertTrue(result.contains("Took a screenshot"), "text 块保留");
        assertTrue(FileReference.isReference(result));
        assertTrue(result.contains("kind: image"));
    }

    @Test
    void plainText_passThrough(@TempDir Path root) {
        String out = "normal tool output\nline2";
        assertEquals(out, wrap(delegate("grep", out), root).call("q", null));
    }

    @Test
    void malformedJsonArray_notCrash_passThrough(@TempDir Path root) {
        String out = "[not valid json";
        assertEquals(out, wrap(delegate("x", out), root).call("i", null));
    }

    @Test
    void readBinaryFile_referencesOriginalPath_noCopy(@TempDir Path root) throws Exception {
        Path img = root.resolve("shot.png");
        Files.write(img, png());
        String toolInput = "{\"filePath\":\"" + img.toAbsolutePath() + "\"}";
        // delegate 模拟 Read 返回乱码二进制串
        String garbled = "\uFFFD\uFFFDPNG\u0000\u0000rubbish";
        String result = wrap(delegate("Read", garbled), root).call(toolInput, null);
        assertTrue(FileReference.isReference(result));
        assertTrue(result.contains("shot.png"), "引用应指原文件路径");
        assertFalse(result.contains("rubbish"), "乱码内容不得留在会话");
    }

    @Test
    void delegateThrows_propagates(@TempDir Path root) {
        ToolCallback boom = new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("x").description("d").inputSchema("{}").build();
            }
            @Override public String call(String in) { return call(in, null); }
            @Override public String call(String in, ToolContext ctx) { throw new RuntimeException("boom"); }
        };
        assertThrows(RuntimeException.class, () -> wrap(boom, root).call("i", null));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `env -u DEEPSEEK_API_KEY -u OPENAI_API_KEY -u ANTHROPIC_API_KEY mvn -q -pl springai-code-tui test -Dtest=MediaExternalizingCallbackTest`
Expected: FAIL 编译错误。

- [ ] **Step 3: 写实现**

```java
// MediaExternalizingCallback.java
package io.github.javaside.springai.codetui.agent.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 路径①：装饰每个工具，把非文本内容（MCP 图像块/Read 二进制/通用二进制）当场换成引用，字节永不进模型。
 *  装在 ToolEventCallback 内层（保 CURRENT_TURN 与 reloadableSkill 身份判断不变）。
 *  delegate.call 在 guard 外（工具自身异常照常传播）；仅「检测+外置+represent」被 guard，抛错降级为占位（绝不退回原始字节）。 */
public final class MediaExternalizingCallback implements ToolCallback {
    private static final Logger log = LoggerFactory.getLogger(MediaExternalizingCallback.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** ToolContext 里能力快照的键（CodingAgent.submit 写入，本装饰器读取）。public 供跨包引用。 */
    public static final String CAPABILITIES_KEY = "capabilities";

    private final ToolCallback delegate;
    private final MediaArtifactStore store;
    private final ToolResultMediaHandler handler;
    private final Path root;

    public MediaExternalizingCallback(ToolCallback delegate, MediaArtifactStore store,
                                      ToolResultMediaHandler handler, Path root) {
        this.delegate = delegate;
        this.store = store;
        this.handler = handler;
        this.root = root;
    }

    @Override public ToolDefinition getToolDefinition() { return delegate.getToolDefinition(); }
    @Override public String call(String toolInput) { return call(toolInput, null); }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String raw = (toolContext == null) ? delegate.call(toolInput) : delegate.call(toolInput, toolContext);
        try {
            return externalize(raw, toolInput, capsOf(toolContext));
        } catch (RuntimeException e) {
            log.warn("媒体外置失败 tool={}：{}", delegate.getToolDefinition().name(), e.toString());  // 不打印内容
            return raw;   // 保守：外置失败则原样返回（媒体检测阶段的失败不该丢工具结果）
        }
    }

    private String externalize(String raw, String toolInput, ModelCapabilities caps) {
        if (raw == null || raw.isEmpty() || FileReference.isReference(raw)) return raw;

        // 1) MCP：顶层内容块数组
        McpMediaParser.Parsed p = McpMediaParser.parse(raw);
        if (p.isMcpArray() && !p.mediaBlocks().isEmpty()) {
            StringBuilder out = new StringBuilder();
            for (String t : p.textBlocks()) out.append(t).append('\n');
            for (McpMediaParser.MediaBlock mb : p.mediaBlocks()) {
                MediaArtifact a = store.put(mb.bytes(), mb.declaredMimeType());
                out.append(handler.represent(a, caps)).append('\n');
            }
            return out.toString().stripTrailing();
        }

        // 2) 二进制工具结果（Read/Bash/其它）
        if (BinarySniff.looksBinary(raw)) {
            Path original = resolveReadPath(toolInput);
            if (original != null) {
                MediaArtifact a = referenceExistingFile(original);
                if (a != null) return handler.represent(a, caps);
            }
            // 无磁盘原件 / 路径不可解：绝不造伪文件，只留告示
            return "[Read 返回疑似二进制内容，已从会话移除；无法恢复原始字节]";
        }

        // 3) 普通文本：原样放行（大文本由路径②在回合间处理）
        return raw;
    }

    /** Read 的 toolInput（JSON）里取文件路径，安全解析进 root。 */
    private Path resolveReadPath(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) return null;
        String raw;
        try {
            JsonNode n = MAPPER.readTree(toolInput);
            JsonNode f = n.get("filePath");
            if (f == null) f = n.get("file_path");
            if (f == null) f = n.get("path");
            if (f == null || !f.isString()) return null;
            raw = f.asString();
        } catch (RuntimeException e) {
            return null;
        }
        try {
            Path p = Path.of(raw).toAbsolutePath().normalize();
            Path rootNorm = root.toAbsolutePath().normalize();
            if (!p.startsWith(rootNorm)) return null;   // 越界不处理
            return Files.isRegularFile(p) ? p : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 项目内既有文件 → EXISTING_FILE 引用（不复制、指原路径，sniff 真文件拿元信息）。 */
    private MediaArtifact referenceExistingFile(Path file) {
        try {
            byte[] head = readHead(file, 64);
            MagicSniffer.Sniffed s = MagicSniffer.sniff(head);
            long size = Files.size(file);
            var dim = ImageDimensions.of(head);
            return new MediaArtifact(
                    "existing-" + Integer.toHexString(file.hashCode()), file,
                    root.toAbsolutePath().normalize().relativize(file).toString(),
                    s.mimeType(), null, s.kind(), size,
                    dim.map(d -> d[0]).orElse(null), dim.map(d -> d[1]).orElse(null), null,
                    ArtifactSource.EXISTING_FILE, false);
        } catch (RuntimeException | java.io.IOException e) {
            return null;
        }
    }

    private static byte[] readHead(Path file, int n) throws java.io.IOException {
        byte[] all = Files.readAllBytes(file);
        if (all.length <= n) return all;
        byte[] head = new byte[n];
        System.arraycopy(all, 0, head, 0, n);
        return head;
    }

    private static ModelCapabilities capsOf(ToolContext ctx) {
        if (ctx == null) return ModelCapabilities.TEXT_ONLY;
        Object v = ctx.getContext().get(CAPABILITIES_KEY);
        return (v instanceof ModelCapabilities mc) ? mc : ModelCapabilities.TEXT_ONLY;
    }
}
```

> 注：`referenceExistingFile` 的 sha 用文件标识占位（本期引用不参与 artifact 去重）；`readHead` 读全量再截头是为拿 `Files.size` 与维度，文件已在磁盘、成本可接受。

- [ ] **Step 4: 跑测试确认通过**

Run: `env -u DEEPSEEK_API_KEY -u OPENAI_API_KEY -u ANTHROPIC_API_KEY mvn -q -pl springai-code-tui test -Dtest=MediaExternalizingCallbackTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/media/MediaExternalizingCallback.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/media/MediaExternalizingCallbackTest.java
git commit -m "feat(media): MediaExternalizingCallback 路径①装饰器(MCP图像块/Read二进制/通用二进制即时外置)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 11: 能力快照写进 CodingAgent.submit 的 toolContext

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/CodingAgent.java:204`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/media/MediaExternalizingCallbackTest.java`（加一枚断言 `true` 能力时仍走引用——证 canDeliver 收敛）

- [ ] **Step 1: 写失败测试（补进 MediaExternalizingCallbackTest）**

```java
    @Test
    void capabilitiesInContext_imageTrue_stillReferenceOnly_noInjector(@TempDir Path root) {
        String b64 = java.util.Base64.getEncoder().encodeToString(png());
        String mcpOut = "[{\"type\":\"image\",\"data\":\"" + b64 + "\",\"mimeType\":\"image/png\"}]";
        ToolContext ctx = new ToolContext(Map.of(
                MediaExternalizingCallback.CAPABILITIES_KEY, new ModelCapabilities(true, true)));
        String result = wrap(delegate("shot", mcpOut), root).call("{}", ctx);
        assertTrue(FileReference.isReference(result));
        assertTrue(result.contains("delivery: reference_only"), "无注入器 → 即便能力开也只引用");
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `env -u DEEPSEEK_API_KEY -u OPENAI_API_KEY -u ANTHROPIC_API_KEY mvn -q -pl springai-code-tui test -Dtest=MediaExternalizingCallbackTest`
Expected: PASS（此测试实际此时应已通过，因 handler 恒 false）——若通过则跳到 Step 3 直接接线；此步用于锁定「能力开也引用」的收敛行为。

- [ ] **Step 3: 修改 CodingAgent.submit 注入能力快照**

在 `CodingAgent.java` 顶部 import 区加：

```java
import io.github.javaside.springai.codetui.agent.media.MediaExternalizingCallback;
import io.github.javaside.springai.codetui.agent.media.ModelCapabilities;
```

把 `submit()` 里的（`CodingAgent.java:204`）：

```java
                    .toolContext(Map.of("turnId", turnId))
```

改为：

```java
                    .toolContext(Map.of(
                            "turnId", turnId,
                            MediaExternalizingCallback.CAPABILITIES_KEY, capabilitiesSnapshot()))   // 冻结「发起本回合的模型」能力
```

并在类中新增私有方法（放在 `foldTrailingUserIntoOutbound` 附近）：

```java
    /** 冻结当前激活模型的能力快照进 toolContext（规避工具执行期间切模型的时序错配）。
     *  registry 缺失（旧单-client 测试路径）→ TEXT_ONLY。 */
    private ModelCapabilities capabilitiesSnapshot() {
        if (registry == null) return ModelCapabilities.TEXT_ONLY;
        return registry.active().capabilities(registry.activeModelId());
    }
```

- [ ] **Step 4: 跑测试确认通过（含既有回归）**

Run: `env -u DEEPSEEK_API_KEY -u OPENAI_API_KEY -u ANTHROPIC_API_KEY mvn -q -pl springai-code-tui test -Dtest=MediaExternalizingCallbackTest,CodingAgentContextTest,CodingAgentModelSwitchTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/CodingAgent.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/media/MediaExternalizingCallbackTest.java
git commit -m "feat(media): submit 把 ModelCapabilities 快照注入 toolContext(绑定发起回合的模型)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 12: 把 MediaExternalizingCallback 织入 AgentTools 装饰循环

**Files:**
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java:213-221`
- Test: `springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/AgentToolsMediaWiringTest.java`

- [ ] **Step 1: 写失败测试**

```java
// AgentToolsMediaWiringTest.java
package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.ui.ConversationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 冒烟：媒体装饰器织入后，build 仍能离线装配出完整 runtime（用假 key，不发网络）。
 *  模式照抄 AgentRuntimeTest.dummyRegistry()。 */
class AgentToolsMediaWiringTest {
    @Test
    void build_withMediaDecorator_ok(@TempDir Path root) {
        ProviderRegistry reg = new ProviderRegistry(List.of(new DeepSeekProvider("fake-key")));
        AgentTools.AgentRuntime rt = AgentTools.build(reg, root, new ConversationState());
        assertNotNull(rt);
        assertNotNull(rt.client());
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `env -u DEEPSEEK_API_KEY -u OPENAI_API_KEY -u ANTHROPIC_API_KEY mvn -q -pl springai-code-tui test -Dtest=AgentToolsMediaWiringTest`
Expected: FAIL（编译错误或断言）——确认测试可跑。

- [ ] **Step 3: 修改装饰循环**

在 `AgentTools.java` 顶部 import 区加：

```java
import io.github.javaside.springai.codetui.agent.media.MediaArtifactStore;
import io.github.javaside.springai.codetui.agent.media.MediaExternalizingCallback;
import io.github.javaside.springai.codetui.agent.media.TextReferenceMediaHandler;
import io.github.javaside.springai.codetui.agent.media.ToolResultMediaHandler;
```

在装饰循环前（`AgentTools.java:214` 的 `ToolCallback[] decorated = ...` 之前）构造一次共享设施：

```java
        // 媒体外置（路径①）：装饰循环前构造一次 store + handler，供每个工具的装饰器共享。
        MediaArtifactStore mediaStore =
                new MediaArtifactStore(root.resolve(".codetui").resolve("artifacts"), root);
        ToolResultMediaHandler mediaHandler = new TextReferenceMediaHandler();
```

把循环体（`AgentTools.java:217`）：

```java
            decorated[i] = new ToolEventCallback(all.get(i), listener);
```

改为（媒体装饰器嵌在 ToolEventCallback 内层）：

```java
            decorated[i] = new ToolEventCallback(
                    new MediaExternalizingCallback(all.get(i), mediaStore, mediaHandler, root), listener);
```

> `reloadableSkill` 身份判断用的是 `all.get(i) == reloadableSkill`（比较未装饰实例），不受影响，保持原样。

- [ ] **Step 4: 跑测试确认通过（含 MCP 装配回归）**

Run: `env -u DEEPSEEK_API_KEY -u OPENAI_API_KEY -u ANTHROPIC_API_KEY mvn -q -pl springai-code-tui test -Dtest=AgentToolsMediaWiringTest,AgentToolsMcpWiringTest,AgentRuntimeTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/AgentToolsMediaWiringTest.java
git commit -m "feat(media): AgentTools 装饰循环织入 MediaExternalizingCallback(嵌 ToolEventCallback 内层)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 13: SessionFileExternalizer（路径②）+ 织入 submit

**Files:**
- Create: `.../agent/media/SessionFileExternalizer.java`
- Modify: `springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/CodingAgent.java`（`submit` 开头调用 + 构造传入 store/root）
- Test: `.../agent/media/SessionFileExternalizerTest.java`

**阈值常量：** `THRESHOLD = 32 * 1024`（字符）。
**MVP 决策（YAGNI）：** 不引入水位线字段；靠 `FileReference.isReference` 幂等 + 阈值，每次 `submit` 搭 sanitize 那趟扫全历史（内存对象、判断廉价）。水位线优化列 Path B。

- [ ] **Step 1: 写失败测试**

```java
// SessionFileExternalizerTest.java
package io.github.javaside.springai.codetui.agent.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.session.SessionEvent;

import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SessionFileExternalizerTest {
    private static SessionEvent ev(String sid, org.springframework.ai.chat.messages.Message m) {
        return SessionEvent.builder().id(java.util.UUID.randomUUID().toString())
                .sessionId(sid).timestamp(java.time.Instant.now()).message(m).build();
    }
    private static SessionEvent readCall(String sid, String callId, String path) {
        return ev(sid, AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(callId, "function", "Read",
                        "{\"filePath\":\"" + path + "\"}")))
                .build());
    }
    private static SessionEvent readResult(String sid, String callId, String body) {
        return ev(sid, ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(callId, "Read", body))).build());
    }

    @Test
    void largeReadResult_replacedByReference_pointingOriginalPath(@TempDir Path root) throws Exception {
        Path file = root.resolve("Big.java");
        Files.writeString(file, "x".repeat(40_000));
        String sid = "s1";
        String big = "y".repeat(40_000);
        List<SessionEvent> events = new java.util.ArrayList<>(List.of(
                ev(sid, new UserMessage("read it")),
                readCall(sid, "c1", file.toAbsolutePath().toString()),
                readResult(sid, "c1", big)));

        SessionFileExternalizer ext = new SessionFileExternalizer(
                new MediaArtifactStore(root.resolve(".codetui/artifacts"), root), root);
        List<SessionEvent> out = ext.externalize(events);

        assertNotSame(events, out, "有改动应返回新列表");
        String body = ((ToolResponseMessage) out.get(2).getMessage()).getResponses().get(0).responseData();
        assertTrue(FileReference.isReference(body));
        assertTrue(body.contains("Big.java"), "引用指原文件路径");
        assertFalse(body.contains("y".repeat(100)), "全文不得留在会话");
    }

    @Test
    void smallResult_untouched(@TempDir Path root) {
        String sid = "s1";
        List<SessionEvent> events = List.of(
                readCall(sid, "c1", root.resolve("a.txt").toString()),
                readResult(sid, "c1", "tiny output"));
        SessionFileExternalizer ext = new SessionFileExternalizer(
                new MediaArtifactStore(root.resolve(".codetui/artifacts"), root), root);
        assertSame(events, ext.externalize(events), "无改动返回同一引用");
    }

    @Test
    void idempotent_secondRunNoOp(@TempDir Path root) throws Exception {
        Path file = root.resolve("Big.java");
        Files.writeString(file, "x".repeat(40_000));
        String sid = "s1";
        List<SessionEvent> events = List.of(
                readCall(sid, "c1", file.toAbsolutePath().toString()),
                readResult(sid, "c1", "y".repeat(40_000)));
        SessionFileExternalizer ext = new SessionFileExternalizer(
                new MediaArtifactStore(root.resolve(".codetui/artifacts"), root), root);
        List<SessionEvent> once = ext.externalize(events);
        assertSame(once, ext.externalize(once), "已是引用 → 二次 no-op");
    }
}
```

> 类型已核实：`AssistantMessage.ToolCall(id,type,name,arguments)` 与 `ToolResponseMessage.ToolResponse(id,name,responseData)` 构造签名均 public；带 tool_calls 的 `AssistantMessage` 其四参构造是 protected，测试须走 `AssistantMessage.builder().content(..).toolCalls(..).build()`（如上 `readCall`）。

- [ ] **Step 2: 跑测试确认失败**

Run: `env -u DEEPSEEK_API_KEY -u OPENAI_API_KEY -u ANTHROPIC_API_KEY mvn -q -pl springai-code-tui test -Dtest=SessionFileExternalizerTest`
Expected: FAIL 编译错误。

- [ ] **Step 3: 写实现**

```java
// SessionFileExternalizer.java
package io.github.javaside.springai.codetui.agent.media;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.session.SessionEvent;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 路径②：回合之间（submit 开头）把过往事件里「携带文件全文的 ToolResponse」换成引用。
 *  只碰过往事件——本回合尚无工具结果，天然不动本回合的读。无改动返回<b>同一引用</b>（同 SessionEvents 纪律）。 */
public final class SessionFileExternalizer {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final int THRESHOLD = 32 * 1024;

    private final MediaArtifactStore store;
    private final Path root;

    public SessionFileExternalizer(MediaArtifactStore store, Path root) {
        this.store = store;
        this.root = root;
    }

    public List<SessionEvent> externalize(List<SessionEvent> events) {
        Map<String, String> idToArgs = collectToolCallArgs(events);   // callId → arguments(JSON)
        List<SessionEvent> out = new ArrayList<>(events.size());
        boolean changed = false;

        for (SessionEvent ev : events) {
            Message m = ev.getMessage();
            if (!(m instanceof ToolResponseMessage trm)) { out.add(ev); continue; }

            List<ToolResponseMessage.ToolResponse> rebuilt = new ArrayList<>();
            boolean evChanged = false;
            for (ToolResponseMessage.ToolResponse tr : trm.getResponses()) {
                String replaced = maybeExternalize(tr, idToArgs.get(tr.id()));
                if (replaced == null) {
                    rebuilt.add(tr);
                } else {
                    rebuilt.add(new ToolResponseMessage.ToolResponse(tr.id(), tr.name(), replaced));
                    evChanged = true;
                }
            }
            if (evChanged) { out.add(withResponses(ev, rebuilt)); changed = true; }
            else out.add(ev);
        }
        return changed ? out : events;
    }

    /** @return 新的 responseData（引用），或 null 表示不改。 */
    private String maybeExternalize(ToolResponseMessage.ToolResponse tr, String argsJson) {
        String data = tr.responseData();
        if (data == null || data.length() < THRESHOLD) return null;   // 小结果不动
        if (FileReference.isReference(data)) return null;              // 幂等：已是引用

        Path original = resolvePath(argsJson);
        if (original != null) {
            MediaArtifact a = referenceExistingText(original, data);
            if (a != null) {
                return FileReference.render(a, "reference_only",
                        "content externalized from session memory; re-read to view");
            }
        }
        // 无源（Bash 长输出 / 路径不可解）→ 文本存 artifact
        MediaArtifact a = store.put(data.getBytes(java.nio.charset.StandardCharsets.UTF_8), "text/plain");
        return FileReference.render(a, "reference_only",
                "content externalized from session memory; re-read to view");
    }

    private Map<String, String> collectToolCallArgs(List<SessionEvent> events) {
        Map<String, String> map = new HashMap<>();
        for (SessionEvent ev : events) {
            if (ev.getMessage() instanceof AssistantMessage am && am.hasToolCalls()) {
                for (AssistantMessage.ToolCall tc : am.getToolCalls()) map.put(tc.id(), tc.arguments());
            }
        }
        return map;
    }

    private Path resolvePath(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) return null;
        try {
            JsonNode n = MAPPER.readTree(argsJson);
            JsonNode f = n.get("filePath");
            if (f == null) f = n.get("file_path");
            if (f == null) f = n.get("path");
            if (f == null || !f.isString()) return null;
            Path p = Path.of(f.asString()).toAbsolutePath().normalize();
            Path rootNorm = root.toAbsolutePath().normalize();
            if (!p.startsWith(rootNorm)) return null;
            return Files.isRegularFile(p) ? p : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private MediaArtifact referenceExistingText(Path file, String data) {
        try {
            long size = Files.size(file);
            int lines = (int) data.chars().filter(c -> c == '\n').count() + 1;
            return new MediaArtifact(
                    "existing-" + Integer.toHexString(file.hashCode()), file,
                    root.toAbsolutePath().normalize().relativize(file).toString(),
                    "text/plain", null, MediaKind.TEXT, size, null, null, lines,
                    ArtifactSource.EXISTING_FILE, false);
        } catch (Exception e) {
            return null;
        }
    }

    private static SessionEvent withResponses(SessionEvent ev, List<ToolResponseMessage.ToolResponse> kept) {
        SessionEvent.Builder b = SessionEvent.builder()
                .id(ev.getId()).sessionId(ev.getSessionId()).timestamp(ev.getTimestamp())
                .message(ToolResponseMessage.builder().responses(kept).build())
                .branch(ev.getBranch());
        if (ev.getMetadata() != null) b.metadata(ev.getMetadata());
        return b.build();
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `env -u DEEPSEEK_API_KEY -u OPENAI_API_KEY -u ANTHROPIC_API_KEY mvn -q -pl springai-code-tui test -Dtest=SessionFileExternalizerTest`
Expected: PASS。

- [ ] **Step 5: 织入 CodingAgent.submit + 提交**

在 `AgentTools.build` 里构造 `CodingAgent`/`AgentRuntime` 处，把已建的 `mediaStore` 与 `root` 传下去（沿 `AgentRuntime` 构造链到 `CodingAgent`，与 `sessionRepository` 同法）。在 `CodingAgent` 加字段 `private final SessionFileExternalizer fileExternalizer;`（可空，测试桩为 null），并在 `submit()` 里 `trimDanglingToolCalls();`（`CodingAgent.java:166`）之后加：

```java
        externalizeSessionFiles();   // 路径②：回合间把过往文件全文换引用（搭 sanitize 那趟）
```

新增方法：

```java
    /** 路径②：submit 开头把过往回合里携带文件全文的 tool 结果外置为引用。无改动 no-op。 */
    private void externalizeSessionFiles() {
        if (fileExternalizer == null || sessionService == null || sessionRepository == null) return;
        String sid = sessionId;
        List<SessionEvent> events = sessionService.getEvents(sid);
        List<SessionEvent> ext = fileExternalizer.externalize(events);
        if (ext != events) {
            sessionRepository.replaceEvents(sid, List.copyOf(ext));
        }
    }
```

Run（回归）：`env -u DEEPSEEK_API_KEY -u OPENAI_API_KEY -u ANTHROPIC_API_KEY mvn -q -pl springai-code-tui test`
Expected: 全绿（约 340+ 测试，0 失败；网络门控 spike 因清了 env 被跳过/不触发）。

```bash
git add springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/media/SessionFileExternalizer.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/CodingAgent.java \
        springai-code-tui/src/main/java/io/github/javaside/springai/codetui/agent/AgentTools.java \
        springai-code-tui/src/test/java/io/github/javaside/springai/codetui/agent/media/SessionFileExternalizerTest.java
git commit -m "feat(media): SessionFileExternalizer 路径②回合间外置文本 + 织入 submit(搭 sanitize 那趟)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## 收尾：全套回归 + 更新记忆/文档

- [ ] 跑整模块测试：`env -u DEEPSEEK_API_KEY -u OPENAI_API_KEY -u ANTHROPIC_API_KEY mvn -q -pl springai-code-tui test`，确认全绿。
- [ ] `mvn -pl springai-code-tui -DskipTests package`，确认能打包（TUI 改动须 pty 实机验证的部分本特性不涉及渲染，略）。
- [ ] 更新长期记忆：新增一条「文件内容不入会话记忆：媒体装饰器即时 + 文本回合间(submit)外置，靠 id 反查 tool_call 参数定位」，并在 MEMORY.md 加指针；关联 [[springai2-tool-loop-reloads-session-each-iteration]] [[mcp-tool-result-serialization-format]]。

---

## Deferred（Path B，不在本计划）

- 视觉真注入：`canDeliver=true` 时 `represent` 登记原生 `Media`/image 块，由消息装配处注入本回合 user 消息。
- 水位线增量扫描（本计划 MVP 用幂等全扫代替）。
- 旧坏会话 `LegacySessionMediaSanitizer`、artifact GC、单回合内 token 预算、全局大文本兜底上限。
- Read 图片经视觉模型的原生投递（本期非视觉一律引用）。

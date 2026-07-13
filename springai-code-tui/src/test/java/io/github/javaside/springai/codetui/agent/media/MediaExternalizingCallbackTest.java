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
import java.util.regex.Pattern;
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
    void readBinaryFile_shortIdWellFormed_noRawBytesLeak(@TempDir Path root) throws Exception {
        Path img = root.resolve("shot2.png");
        Files.write(img, png());
        String toolInput = "{\"filePath\":\"" + img.toAbsolutePath() + "\"}";
        String garbled = "\uFFFD\uFFFDPNG\u0000\u0000rubbish2";
        String result = wrap(delegate("Read", garbled), root).call(toolInput, null);
        assertTrue(FileReference.isReference(result));
        assertTrue(result.contains("shot2.png"), "引用应指原文件路径");
        assertFalse(result.contains("rubbish"), "乱码内容不得留在会话");
        assertTrue(Pattern.compile("id: sha256:[0-9a-f]{16}").matcher(result).find(),
                "shortId 必须是合法的 16 位十六进制，不得因越界异常回退");
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

    @Test
    void mcpImageBlock_storePutFails_degradesToPlaceholder_noBase64Leak(@TempDir Path root) throws Exception {
        // artifactsDir 的父段是个普通文件 -> Files.createDirectories 必炸 IOException -> store.put 抛 IllegalStateException
        Path blocker = root.resolve("blocker.txt");
        Files.writeString(blocker, "not a directory");
        Path unwritableArtifactsDir = blocker.resolve("artifacts");
        MediaArtifactStore brokenStore = new MediaArtifactStore(unwritableArtifactsDir, root);

        String b64 = Base64.getEncoder().encodeToString(png());
        String mcpOut = "[{\"type\":\"text\",\"text\":\"Took a screenshot\"},"
                + "{\"type\":\"image\",\"data\":\"" + b64 + "\",\"mimeType\":\"image/png\"}]";
        ToolCallback d = new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("browser_screenshot").description("d").inputSchema("{}").build();
            }
            @Override public String call(String in) { return call(in, null); }
            @Override public String call(String in, ToolContext ctx) { return mcpOut; }
        };
        String result = new MediaExternalizingCallback(d, brokenStore, new TextReferenceMediaHandler(), root)
                .call("{}", null);

        assertFalse(result.contains(b64), "store.put 失败也绝不得泄露 base64");
        assertTrue(result.contains("Took a screenshot"), "text 块仍保留");
        assertTrue(result.contains("failed"), "失败块须降级为占位文案");
    }

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
}

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

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

    /** 真实 read_media_file 线格式：无 type，只有 data+mimeType（session 20260713T060836 抓到的形态）。
     *  修此前 parser 认 type → mediaBlocks=0 → base64 当回合原样进模型。这是唯一真正复现线上 bug 的用例。 */
    @Test
    void realReadMediaFile_noType_externalized_noBase64Leak(@TempDir Path root) {
        String b64 = Base64.getEncoder().encodeToString(png());
        String mcpOut = "[{\"data\":\"" + b64 + "\",\"mimeType\":\"image/png\"}]";   // 无 type
        String result = wrap(delegate("mcp__filesystem__read_media_file", mcpOut), root).call("{}", null);
        assertFalse(result.contains(b64), "返回串绝不得含 base64——非视觉模型永不收到媒体字节");
        assertTrue(FileReference.isReference(result));
        assertTrue(result.contains("kind: image"), "应标 image，实际:\n" + result);
        assertTrue(result.contains("mime_type: image/png"));
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

    /** 修复核心：Read 把 PNG 读成带行号的 hexdump 文本（替换符仅 ~21%，BinarySniff 判不出二进制），
     *  但磁盘文件是 PNG。判据须看「磁盘文件按魔数是不是文本」，非文本 → 引用。这是线上真实漏检
     *  （session 20260713T070248 的 75835 字符 Read 结果未被外置）。 */
    @Test
    void readPngAsHexdumpText_stillExternalizedAsImage(@TempDir Path root) throws Exception {
        Path img = root.resolve("15_user_developer.png");
        Files.write(img, png());
        String toolInput = "{\"filePath\":\"" + img.toAbsolutePath() + "\"}";
        // 模拟内置 Read 的 hexdump 文本：大部分可打印，替换符占比远低于 30%（BinarySniff 判不出）
        StringBuilder hex = new StringBuilder("File: 15_user_developer.png\nShowing lines 1-3\n");
        for (int i = 0; i < 500; i++) hex.append("     ").append(i).append("\t").append("IHDR data bytes here ").append('\n');
        hex.append("�PNG marker\n");   // 个别替换符，占比 <1%
        String hexdump = hex.toString();
        assertFalse(BinarySniff.looksBinary(hexdump), "前提：这段 hexdump 文本 BinarySniff 判不出二进制");

        String result = wrap(delegate("Read", hexdump), root).call(toolInput, null);
        assertTrue(FileReference.isReference(result), "PNG 的 hexdump 必须被外置为引用");
        assertTrue(result.contains("kind: image"), "应按磁盘魔数标 image，实际:\n" + result);
        assertTrue(result.contains("mime_type: image/png"));
        assertFalse(result.contains("IHDR data bytes here"), "hexdump 正文不得留在会话");
    }

    /** 修复核心：文本文件读取（磁盘文件无魔数）→ 这一回合原样放行（交路径②回合间处理），不被误当媒体。 */
    @Test
    void readTextFile_passThrough_notMislabeledMedia(@TempDir Path root) throws Exception {
        Path src = root.resolve("Main.java");
        Files.writeString(src, "public class Main {}\n");
        String toolInput = "{\"filePath\":\"" + src.toAbsolutePath() + "\"}";
        String body = "File: Main.java\npublic class Main {}\n";
        String result = wrap(delegate("Read", body), root).call(toolInput, null);
        assertEquals(body, result, "文本文件读取这一回合原样放行");
    }

    /**
     * ★ 回归：Read <b>项目外</b> PNG 时不得放行 hexdump 字节进模型。
     *
     * <p>线上事故（session 20260823T142540）：模型 Read 了 {@code ~/Downloads/xxx.png}
     * （越界路径），{@code resolveReadPath} 返回 null → 引用分支被跳过 → BinarySniff 判不出
     * hexdump 文本 → 分支 4 把 204KB 的 PNG 原始字节当文本放行进模型。修复：越界但真实存在的
     * 非文本文件 → 复制进 artifacts 外置成引用（与用户附件同策略），字节绝不进模型。
     */
    @Test
    void readOutsideRootPng_stillExternalizedAsImage_neverLeaksBytes(@TempDir Path root) throws Exception {
        Path outside = root.resolveSibling("outside-" + System.nanoTime() + ".png");
        Files.write(outside, png());
        try {
            String toolInput = "{\"filePath\":\"" + outside.toAbsolutePath() + "\"}";
            StringBuilder hex = new StringBuilder("File: outside.png\nShowing lines 1-3\n");
            for (int i = 0; i < 200; i++) hex.append("     ").append(i).append("\t").append("IDAT bytes ").append('\n');
            hex.append("�PNG marker\n");
            String hexdump = hex.toString();
            assertFalse(BinarySniff.looksBinary(hexdump), "前提：这段 hexdump 文本 BinarySniff 判不出二进制");

            String result = wrap(delegate("Read", hexdump), root).call(toolInput, null);
            assertTrue(FileReference.isReference(result), "项目外 PNG 的 hexdump 也必须外置为引用，实际:\n" + result);
            assertTrue(result.contains("kind: image"), "应按磁盘魔数标 image");
            assertFalse(result.contains("IDAT bytes"), "hexdump 正文不得留在会话——字节泄漏进模型的 bug 必须堵死");
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    /**
     * ★ 回归：Read artifacts 副本时引用 id 必须与用户附件一致（都是<b>内容哈希</b>）。
     *
     * <p>线上事故（session 20260823T142540）：用户贴 {@code ~/Downloads/QQ.png}（项目外）→
     * {@code copyIntoArtifacts} 复制进 artifacts，文件名/引用 id 是内容哈希
     * （{@code sha256:f15c71fead633c1b}）；模型随后 Read 该副本 → {@code referenceExistingFile}
     * 用<b>路径哈希</b>（{@code sha256:db2b095ceab70ea9}）→ 同一张图两个 id，模型当两张。
     * 修复：artifacts 内是内容寻址产物（文件名=内容哈希），Read 时须沿用内容哈希。
     */
    @Test
    void readArtifactsCopy_keepsContentHashId_matchingUserAttachment(@TempDir Path root) throws Exception {
        MediaArtifactStore store = new MediaArtifactStore(root.resolve(".codetui/artifacts"), root);
        MediaArtifact a = store.put(png(), null, "QQ.png");   // 附件侧：内容哈希命名
        String toolInput = "{\"filePath\":\"" + a.path().toAbsolutePath() + "\"}";
        String garbled = "\uFFFD\uFFFD PNG bytes here";
        String result = wrap(delegate("Read", garbled), root).call(toolInput, null);
        assertTrue(FileReference.isReference(result), "Read artifacts 副本应外置为引用");
        assertTrue(result.contains("id: sha256:" + a.shortId()),
                "Read artifacts 副本的 id 必须与附件一致（内容哈希），实际:\n" + result);
        assertTrue(result.contains("path: .codetui/artifacts/"), "引用 path 应仍是 artifacts 相对路径");
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

    /**
     * 已确认项目外目标为非文本时，artifacts 外置失败必须 fail-closed，不能回退放行 hexdump。
     * 这是 session 20260823T142540 的 PNG 字节泄漏在磁盘满/目录不可写时的安全回归。
     */
    @Test
    void readOutsideRootPng_storeFailure_omitsHexdumpInsteadOfReturningRaw(@TempDir Path root) throws Exception {
        Path outside = root.resolveSibling("outside-fail-" + System.nanoTime() + ".png");
        Files.write(outside, png());
        Path blocker = root.resolve("blocker.txt");
        Files.writeString(blocker, "not a directory");
        MediaArtifactStore brokenStore = new MediaArtifactStore(blocker.resolve("artifacts"), root);
        String hexdump = "File: outside.png\n     1\tIDAT raw bytes that must not leak\n�PNG marker\n";
        assertFalse(BinarySniff.looksBinary(hexdump), "前提：hexdump 不能依赖 BinarySniff 拦住");

        try {
            String toolInput = "{\"filePath\":\"" + outside.toAbsolutePath() + "\"}";
            String result = new MediaExternalizingCallback(delegate("Read", hexdump), brokenStore,
                    new TextReferenceMediaHandler(), root).call(toolInput, null);

            assertEquals("[工具返回二进制文件，外置失败后内容已从会话移除]", result);
            assertFalse(result.contains("IDAT raw bytes"), "外置失败时也绝不得放行 hexdump");
        } finally {
            Files.deleteIfExists(outside);
        }
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

    /** 能力开 → 引用写 not_in_view（「Read 一次就能看」）；字节仍不进会话，真投递由出站侧另做。 */
    @Test
    void capabilitiesInContext_imageTrue_marksNotInView(@TempDir Path root) {
        String b64 = java.util.Base64.getEncoder().encodeToString(png());
        String mcpOut = "[{\"type\":\"image\",\"data\":\"" + b64 + "\",\"mimeType\":\"image/png\"}]";
        ToolContext ctx = new ToolContext(Map.of(
                MediaExternalizingCallback.CAPABILITIES_KEY, new ModelCapabilities(true, true)));
        String result = wrap(delegate("shot", mcpOut), root).call("{}", ctx);
        assertTrue(FileReference.isReference(result));
        assertTrue(result.contains("delivery: not_in_view"), "能力开 → not_in_view，而非 reference_only");
        assertFalse(result.contains(b64), "无论能力如何，base64 都不得回到会话文本");
    }

    /** MCP 内联字节没有文件名，须合成 <工具名>-<序号>-<sha8>——否则同一页面的多次截图无法区分。 */
    @Test
    void mcpImageGetsSynthesizedName(@TempDir Path root) {
        String b64 = java.util.Base64.getEncoder().encodeToString(png());
        String mcpOut = "[{\"type\":\"image\",\"data\":\"" + b64 + "\",\"mimeType\":\"image/png\"}]";
        String result = wrap(delegate("take_screenshot", mcpOut), root).call("{}", null);
        assertTrue(result.contains("name: take_screenshot-01-"), "缺少合成文件名：\n" + result);
        assertTrue(result.contains(".png"), "合成名应带扩展名：\n" + result);
    }
}

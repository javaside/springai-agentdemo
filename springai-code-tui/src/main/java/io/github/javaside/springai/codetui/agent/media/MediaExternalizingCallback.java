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

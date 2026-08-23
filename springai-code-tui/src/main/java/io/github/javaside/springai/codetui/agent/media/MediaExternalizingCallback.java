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
import java.security.MessageDigest;
import java.util.HexFormat;

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
    /** MCP 图片的序号，仅用于合成可读文件名——同一页面的多次截图靠它区分。 */
    private int mcpImageSeq = 0;

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
                try {
                    MediaArtifact a = store.put(mb.bytes(), mb.declaredMimeType(), synthesizeName(mb.bytes()));
                    out.append(handler.represent(a, caps)).append('\n');
                } catch (RuntimeException e) {
                    log.warn("媒体块外置失败，已降级为占位（未泄露字节）：{}", e.toString());  // 不打印内容
                    out.append("[media externalization failed; content omitted]").append('\n');
                }
            }
            return out.toString().stripTrailing();
        }

        // 2) 文件读取：能反查到磁盘真实文件，且文件本身非文本（按魔数）→ 引用。
        //    判据看「磁盘文件是不是文本」，不看返回串像不像二进制——Read 把 PNG 读成
        //    带行号的 hexdump 文本（替换符仅 ~21%），BinarySniff 判不出，但它读的就是图片。
        Path original = resolveReadPath(toolInput);
        if (original != null && !MagicSniffer.isTextFile(original)) {
            MediaArtifact a = referenceExistingFile(original);
            if (a != null) return handler.represent(a, caps);
        }

        // 2b) 越界但真实存在的非文本文件（项目外路径，如 ~/Downloads/x.png）：Read 越界时
        //    resolveReadPath 返回 null，上面的引用分支被跳过；若此时直接把 raw 放行，
        //    PNG 的 hexdump 文本（BinarySniff 判不出）就会整段进模型上下文——线上事故
        //    （session 20260823T142540 的 204KB 字节泄漏）。修复：反查原始路径、校验「存在且
        //    非文本」后复制进 artifacts 外置成引用（与用户附件对项目外文件同一策略：快照进
        //    artifacts，path 落回 root 内，解析器才认）。字节绝不原样放行。
        if (original == null) {
            Path outside = resolveOutsideReadPath(toolInput);
            if (outside != null && !MagicSniffer.isTextFile(outside)) {
                MediaArtifact a = copyIntoStore(outside);
                if (a != null) return handler.represent(a, caps);
                // 已确认是项目外二进制：外置失败必须 fail-closed，绝不能掉到 return raw。
                // Read 的 PNG/PDF hexdump 往往通不过 BinarySniff，放行会重新泄漏原始字节。
                return "[工具返回二进制文件，外置失败后内容已从会话移除]";
            }
        }

        // 3) 无源的疑似二进制串（如某些 Bash 二进制输出，无可反查文件）→ 兜底告示，绝不造伪文件。
        if (original == null && BinarySniff.looksBinary(raw)) {
            return "[工具返回疑似二进制内容，已从会话移除；无法恢复原始字节]";
        }

        // 4) 普通文本 / 文本文件读取：原样放行（文本文件由路径②在回合间换引用）。
        return raw;
    }

    /** 项目外真实文件的快照引用：复制进 artifacts（内容寻址 + 原子写 + 去重由 store 负责），
     *  与 {@code CodeTuiView.copyIntoArtifacts} 对用户附件的策略一致。失败返回 null（不抛）。 */
    private MediaArtifact copyIntoStore(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            return store.put(bytes, null, file.getFileName().toString());
        } catch (RuntimeException | java.io.IOException e) {
            log.warn("越界文件外置失败 {}：{}", file.getFileName(), e.toString());   // 不打印内容
            return null;
        }
    }

    /** 反查 Read 的<b>原始</b>路径（不做包含校验）：越界路径只用于「读真实文件拿魔数/复制」，
     *  引用块里 path 永远落在 root 内（artifacts 快照），绝不写原始绝对路径。 */
    private Path resolveOutsideReadPath(String toolInput) {
        String raw = readPathArg(toolInput);
        if (raw == null) return null;
        try {
            Path p = Path.of(raw);
            return Files.isRegularFile(p) ? p.toAbsolutePath().normalize() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String readPathArg(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) return null;
        try {
            JsonNode n = MAPPER.readTree(toolInput);
            JsonNode f = n.get("filePath");
            if (f == null) f = n.get("file_path");
            if (f == null) f = n.get("path");
            if (f == null || !f.isString()) return null;
            return f.asString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 给无名的 MCP 内联字节合成可读文件名。sha 算失败也不能让整块图丢掉，故回退到序号。 */
    private String synthesizeName(byte[] bytes) {
        String ext = MagicSniffer.sniff(bytes).ext();
        String tool = delegate.getToolDefinition().name();
        String seq = String.format("%02d", ++mcpImageSeq);
        try {
            String hex = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            return tool + "-" + seq + "-" + hex.substring(0, 8) + "." + ext;
        } catch (Exception e) {
            return tool + "-" + seq + "." + ext;
        }
    }

    /** Read 的 toolInput（JSON）里取文件路径，安全解析进 root。 */
    private Path resolveReadPath(String toolInput) {
        String raw = readPathArg(toolInput);
        if (raw == null) return null;
        return PathContainment.resolveInRoot(raw, root);
    }

    /**
     * 项目内既有文件 → EXISTING_FILE 引用（不复制、指原路径，sniff 真文件拿元信息）。
     *
     * <p><b>id 口径：artifacts 内沿用内容哈希，其余用路径哈希</b>。artifacts 是内容寻址存储
     * （{@link MediaArtifactStore#put} 以「内容 SHA-256」命名文件），用户附件复制进来时
     * 引用 id 就是内容哈希；若 Read 这里改算路径哈希，同一张图就会出现两个 id（线上事故
     * session 20260823T142540：附件 id {@code sha256:f15c71fead633c1b}、Read id
     * {@code sha256:db2b095ceab70ea9}）。故 artifacts 内的文件按文件名反解内容哈希，
     * 与附件侧逐字一致；项目内普通文件（非 artifacts）仍按路径哈希——那是「源文件可被改写，
     * id 要跟着路径稳定」的原始设计。
     */
    private MediaArtifact referenceExistingFile(Path file) {
        try {
            byte[] head = readHead(file, 64);
            MagicSniffer.Sniffed s = MagicSniffer.sniff(head);
            long size = Files.size(file);
            var dim = ImageDimensions.of(head);
            String sha = contentHashIfInStore(file);
            return new MediaArtifact(
                    sha, file,
                    PathContainment.relativeToRoot(file, root),
                    s.mimeType(), null, s.kind(), size,
                    dim.map(d -> d[0]).orElse(null), dim.map(d -> d[1]).orElse(null), null,
                    ArtifactSource.EXISTING_FILE, false,
                    file.getFileName().toString());
        } catch (RuntimeException | java.io.IOException e) {
            return null;
        }
    }

    /**
     * artifacts 内内容寻址文件 → 从文件名反解内容哈希；否则按路径哈希。
     * 判据：父目录是 {@code .codetui/artifacts} 且文件名匹配 {@code <64位hex>.<ext>}。
     * 目录比较用 {@code toRealPath()}（解符号链接）——{@code resolveInRoot} 返回的就是
     * 解链后的路径，macOS {@code /tmp → /private/tmp} 场景下不解链会误判。
     */
    private String contentHashIfInStore(Path file) {
        Path artifactsDir;
        try {
            artifactsDir = root.resolve(".codetui").resolve("artifacts").toRealPath();
        } catch (java.io.IOException e) {
            return sha256Hex(file.toAbsolutePath().normalize().toString());
        }
        Path f = file.toAbsolutePath().normalize();
        if (f.getParent() != null && f.getParent().equals(artifactsDir)) {
            String name = file.getFileName().toString();
            int dot = name.lastIndexOf('.');
            if (dot > 0) {
                String stem = name.substring(0, dot);
                if (stem.length() == 64 && stem.matches("[0-9a-f]{64}")) {
                    return stem;   // 内容哈希：与 MediaArtifactStore.put 的命名一致
                }
            }
        }
        return sha256Hex(file.toAbsolutePath().normalize().toString());
    }

    private static String sha256Hex(String s) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
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

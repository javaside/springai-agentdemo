// SessionFileExternalizer.java
package io.github.javaside.springai.codetui.agent.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.session.SessionEvent;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** 路径②：回合之间（submit 开头）的兜底——把过往事件里「非文本文件的读取结果」换成引用。
 *  <b>文本文件不外置</b>：文本是模型的工作材料，原文留在会话里（Edit 精确匹配、跨回合续用都靠它）；
 *  只有图片/视频/二进制这类模型读不懂的文件才引用。非文件（Bash 输出等）一律不动。
 *  只碰过往事件——本回合尚无工具结果，天然不动本回合的读。无改动返回<b>同一引用</b>（同 SessionEvents 纪律）。 */
public final class SessionFileExternalizer {
    private static final Logger log = LoggerFactory.getLogger(SessionFileExternalizer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path root;

    public SessionFileExternalizer(Path root) {
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

    /** @return 新的 responseData（引用），或 null 表示不改。
     *  规则：反查到 in-root 文件且<b>非文本</b> → 引用；文本文件、非文件一律放行（return null）。 */
    private String maybeExternalize(ToolResponseMessage.ToolResponse tr, String argsJson) {
        String data = tr.responseData();
        if (data == null || data.isEmpty()) return null;
        if (FileReference.isReference(data)) return null;              // 幂等：已是引用

        Path original = resolvePath(argsJson);
        if (original == null) return null;                            // 非文件（Bash 输出等）：不动
        if (MagicSniffer.isTextFile(original)) return null;           // 文本文件：原文留会话，不外置

        // 图片/视频/二进制文件：换引用（模型读不懂原字节，引用零损失）。
        MediaArtifact a = referenceExistingFile(original);
        if (a == null) return null;                                   // 取元信息失败：保守放行，绝不臆造引用
        return FileReference.render(a, FileReference.DELIVERY_REFERENCE_ONLY,
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
            return PathContainment.resolveInRoot(f.asString(), root);   // 解符号链接后判包含
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 引用项目内既有<b>非文本</b>文件（不复制、指原路径）。按文件魔数标真实类型：
     *  PNG/JPEG/... → IMAGE/image_mime（附宽高）；MP4/WebM → VIDEO；PDF/ZIP/未知魔数 → BINARY。
     *  文本文件不会走到这里（maybeExternalize 已放行）。 */
    private MediaArtifact referenceExistingFile(Path file) {
        try {
            byte[] head = readHead(file, 512);
            MagicSniffer.Sniffed s = MagicSniffer.sniff(head);
            long size = Files.size(file);
            String sha = sha256Hex(file.toAbsolutePath().normalize().toString());
            String rel = PathContainment.relativeToRoot(file, root);
            var dim = ImageDimensions.of(head);
            return new MediaArtifact(sha, file, rel, s.mimeType(), null, s.kind(), size,
                    dim.map(d -> d[0]).orElse(null), dim.map(d -> d[1]).orElse(null), null,
                    ArtifactSource.EXISTING_FILE, false,
                    file.getFileName().toString());
        } catch (RuntimeException | java.io.IOException e) {
            return null;
        }
    }

    private static byte[] readHead(Path file, int n) throws java.io.IOException {
        try (var in = Files.newInputStream(file)) {
            byte[] buf = new byte[n];
            int read = in.readNBytes(buf, 0, n);
            if (read == n) return buf;
            byte[] head = new byte[read];
            System.arraycopy(buf, 0, head, 0, read);
            return head;
        }
    }

    private static String sha256Hex(String s) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
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

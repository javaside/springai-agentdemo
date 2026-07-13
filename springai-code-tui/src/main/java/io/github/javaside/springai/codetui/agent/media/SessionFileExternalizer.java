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

/** 路径②：回合之间（submit 开头）把过往事件里「携带文件全文的 ToolResponse」换成引用。
 *  只碰过往事件——本回合尚无工具结果，天然不动本回合的读。无改动返回<b>同一引用</b>（同 SessionEvents 纪律）。 */
public final class SessionFileExternalizer {
    private static final Logger log = LoggerFactory.getLogger(SessionFileExternalizer.class);
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
        if (data == null || data.isEmpty()) return null;
        if (FileReference.isReference(data)) return null;              // 幂等：已是引用

        // 能反查到 in-root 文件 → 这是一次「文件读取」，内容一律外置，<b>不看大小</b>
        // （用户约束：任何文件内容都不驻留会话记忆）。引用按文件魔数标真实类型。
        Path original = resolvePath(argsJson);
        if (original != null) {
            MediaArtifact a = referenceExistingFile(original, data);
            if (a != null) {
                return FileReference.render(a, "reference_only",
                        "content externalized from session memory; re-read to view");
            }
            // 文件在、但读头/取元信息失败：落 artifact 兜底（仍不看大小，绝不把文件正文留在会话）
            return materialize(data);
        }

        // 非文件（Bash 长输出 / 路径不可解）：非文件输出保留大小门槛，避免把每条短命令结果也搬走
        if (data.length() < THRESHOLD) return null;
        return materialize(data);
    }

    /** 无源/兜底：把内容存进 artifact store（MATERIALIZED）。store.put IO 失败时保守降级，不炸掉整回合。 */
    private String materialize(String data) {
        try {
            MediaArtifact a = store.put(data.getBytes(java.nio.charset.StandardCharsets.UTF_8), "text/plain");
            return FileReference.render(a, "reference_only",
                    "content externalized from session memory; re-read to view");
        } catch (RuntimeException e) {
            log.warn("externalize fallback (MATERIALIZED) failed, leaving response unchanged: {}", e.toString());
            return null;
        }
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

    /** 引用项目内既有文件（不复制、指原路径）。<b>按文件魔数标真实类型</b>：
     *  PNG/JPEG/... → IMAGE/image_mime（附宽高）；MP4/WebM → VIDEO；PDF/ZIP/未知魔数 → BINARY；
     *  无魔数（源码/纯文本）→ TEXT/text/plain（附行数）。修此前一律强标 text/plain 致 PNG 误标。 */
    private MediaArtifact referenceExistingFile(Path file, String data) {
        try {
            byte[] head = readHead(file, 64);
            MagicSniffer.Sniffed s = MagicSniffer.sniff(head);
            long size = Files.size(file);
            String sha = sha256Hex(file.toAbsolutePath().normalize().toString());
            String rel = PathContainment.relativeToRoot(file, root);

            if (s.kind() == MediaKind.BINARY && "application/octet-stream".equals(s.mimeType())) {
                // 无已知魔数：当文本文件，标 text/plain + 行数。
                int lines = (int) data.chars().filter(c -> c == '\n').count() + 1;
                return new MediaArtifact(sha, file, rel, "text/plain", null, MediaKind.TEXT,
                        size, null, null, lines, ArtifactSource.EXISTING_FILE, false);
            }
            var dim = ImageDimensions.of(head);
            return new MediaArtifact(sha, file, rel, s.mimeType(), null, s.kind(), size,
                    dim.map(d -> d[0]).orElse(null), dim.map(d -> d[1]).orElse(null), null,
                    ArtifactSource.EXISTING_FILE, false);
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

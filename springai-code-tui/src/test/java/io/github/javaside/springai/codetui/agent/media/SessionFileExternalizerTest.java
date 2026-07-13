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
    private static SessionEvent bashCall(String sid, String callId, String command) {
        return ev(sid, AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(callId, "function", "Bash",
                        "{\"command\":\"" + command + "\"}")))
                .build());
    }
    private static SessionEvent bashResult(String sid, String callId, String body) {
        return ev(sid, ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(callId, "Bash", body))).build());
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
    void noResolvableSource_materializedIntoArtifactStore(@TempDir Path root) {
        String sid = "s1";
        String big = "z".repeat(40_000);
        List<SessionEvent> events = new java.util.ArrayList<>(List.of(
                ev(sid, new UserMessage("run it")),
                bashCall(sid, "c1", "find / -name '*.log'"),
                bashResult(sid, "c1", big)));

        SessionFileExternalizer ext = new SessionFileExternalizer(
                new MediaArtifactStore(root.resolve(".codetui/artifacts"), root), root);
        List<SessionEvent> out = ext.externalize(events);

        assertNotSame(events, out, "有改动应返回新列表");
        String body = ((ToolResponseMessage) out.get(2).getMessage()).getResponses().get(0).responseData();
        assertTrue(FileReference.isReference(body), "应变为引用");
        assertFalse(body.contains("z".repeat(100)), "全文不得留在会话");
        assertFalse(body.contains("find / -name"), "不得指向 Bash 命令这个伪路径");
        assertTrue(body.contains(".codetui/artifacts") || body.contains(".codetui\\artifacts"),
                "MATERIALIZED 应指向 artifact store 路径而非不存在的原路径");
    }

    /** 修复核心①：能反查到 in-root 文件的读取结果，即便远小于 32KB 也必须外置
     *  （用户约束「任何文件内容都不驻留会话记忆」）。修此前 8737 字符的 cat markdown 长留会话。 */
    @Test
    void smallFileRead_stillExternalized_noThreshold(@TempDir Path root) throws Exception {
        Path file = root.resolve("notes.md");
        String body = "# 标题\n" + "小正文行\n".repeat(50);   // 远小于 32KB
        Files.writeString(file, body);
        String sid = "s1";
        List<SessionEvent> events = new java.util.ArrayList<>(List.of(
                readCall(sid, "c1", file.toAbsolutePath().toString()),
                readResult(sid, "c1", body)));

        SessionFileExternalizer ext = new SessionFileExternalizer(
                new MediaArtifactStore(root.resolve(".codetui/artifacts"), root), root);
        List<SessionEvent> out = ext.externalize(events);

        assertNotSame(events, out, "小文件读取也应被外置");
        String ref = ((ToolResponseMessage) out.get(1).getMessage()).getResponses().get(0).responseData();
        assertTrue(FileReference.isReference(ref));
        assertTrue(ref.contains("notes.md"), "引用指原文件");
        assertFalse(ref.contains("小正文行"), "文件正文不得留在会话");
    }

    /** 修复核心②：读取的是 PNG 文件 → 引用必须标 kind: image / image/png，不能一律 text/plain。
     *  修此前 referenceExistingText 强标 text，正是 session 里两条 PNG 引用误标 kind:text 的主因。 */
    @Test
    void pngFileRead_referenceLabeledImage_notText(@TempDir Path root) throws Exception {
        // 最小 PNG 头（含 IHDR 宽高 10x20），足够 MagicSniffer + ImageDimensions 识别
        byte[] png = new byte[33];
        int[] sig = {0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A};
        for (int i = 0; i < 8; i++) png[i] = (byte) sig[i];
        png[16]=0;png[17]=0;png[18]=0;png[19]=10; png[20]=0;png[21]=0;png[22]=0;png[23]=20;
        Path file = root.resolve("shot.png");
        Files.write(file, png);
        // 模拟工具把二进制读成一段大文本（本测直接给一段 >0 的正文即可触发外置；关键看引用类型）
        String sid = "s1";
        String body = "PNG binary read as text placeholder body";
        List<SessionEvent> events = new java.util.ArrayList<>(List.of(
                readCall(sid, "c1", file.toAbsolutePath().toString()),
                readResult(sid, "c1", body)));

        SessionFileExternalizer ext = new SessionFileExternalizer(
                new MediaArtifactStore(root.resolve(".codetui/artifacts"), root), root);
        List<SessionEvent> out = ext.externalize(events);

        String ref = ((ToolResponseMessage) out.get(1).getMessage()).getResponses().get(0).responseData();
        assertTrue(FileReference.isReference(ref));
        assertTrue(ref.contains("kind: image"), "PNG 应标 kind: image，实际:\n" + ref);
        assertTrue(ref.contains("mime_type: image/png"), "PNG 应标 image/png，实际:\n" + ref);
        assertTrue(ref.contains("dimensions: 10x20"), "应带宽高");
        assertFalse(ref.contains("kind: text"), "不得误标 text");
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

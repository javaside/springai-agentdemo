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

/** 路径②纪律：<b>文本文件不外置</b>（原文留会话，供 Edit 精确匹配/跨回合续用）；
 *  只有非文本文件（图片/二进制）才换引用；非文件（Bash 输出）一律不动。 */
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

    /** 核心：读取<b>文本文件</b>（哪怕很大）→ 原文原样保留在会话，不外置。 */
    @Test
    void largeTextFileRead_keptInSession_notReferenced(@TempDir Path root) throws Exception {
        Path file = root.resolve("Big.java");
        String body = "class Big {}\n" + "int x;\n".repeat(8_000);   // 远大于任何旧阈值
        Files.writeString(file, body);
        String sid = "s1";
        List<SessionEvent> events = new java.util.ArrayList<>(List.of(
                ev(sid, new UserMessage("read it")),
                readCall(sid, "c1", file.toAbsolutePath().toString()),
                readResult(sid, "c1", body)));

        SessionFileExternalizer ext = new SessionFileExternalizer(root);
        List<SessionEvent> out = ext.externalize(events);

        assertSame(events, out, "文本文件不外置 → 无改动应返回同一引用");
        String kept = ((ToolResponseMessage) out.get(2).getMessage()).getResponses().get(0).responseData();
        assertFalse(FileReference.isReference(kept), "文本文件正文必须留在会话，不得变引用");
        assertTrue(kept.contains("int x;"), "原文完整保留");
    }

    /** 小文本文件同样保留（无阈值概念）。 */
    @Test
    void smallTextFileRead_kept(@TempDir Path root) throws Exception {
        Path file = root.resolve("notes.md");
        String body = "# 标题\n" + "小正文行\n".repeat(50);
        Files.writeString(file, body);
        String sid = "s1";
        List<SessionEvent> events = List.of(
                readCall(sid, "c1", file.toAbsolutePath().toString()),
                readResult(sid, "c1", body));
        SessionFileExternalizer ext = new SessionFileExternalizer(root);
        assertSame(events, ext.externalize(events), "文本文件读取不动");
    }

    /** 非文件（Bash 长输出，路径不可反查）→ 不动，原样保留。 */
    @Test
    void nonFileBashOutput_untouched(@TempDir Path root) {
        String sid = "s1";
        String big = "z".repeat(40_000);
        List<SessionEvent> events = List.of(
                bashCall(sid, "c1", "find / -name '*.log'"),
                bashResult(sid, "c1", big));
        SessionFileExternalizer ext = new SessionFileExternalizer(root);
        assertSame(events, ext.externalize(events), "非文件输出一律不外置");
    }

    /** 读取的是 <b>PNG 文件</b> → 换引用，标 kind: image / image/png（模型读不懂二进制，引用零损失）。 */
    @Test
    void pngFileRead_replacedByImageReference(@TempDir Path root) throws Exception {
        byte[] png = new byte[33];
        int[] sig = {0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A};
        for (int i = 0; i < 8; i++) png[i] = (byte) sig[i];
        png[16]=0;png[17]=0;png[18]=0;png[19]=10; png[20]=0;png[21]=0;png[22]=0;png[23]=20;
        Path file = root.resolve("shot.png");
        Files.write(file, png);
        String sid = "s1";
        String body = "PNG binary read as text placeholder body";
        List<SessionEvent> events = new java.util.ArrayList<>(List.of(
                readCall(sid, "c1", file.toAbsolutePath().toString()),
                readResult(sid, "c1", body)));

        SessionFileExternalizer ext = new SessionFileExternalizer(root);
        List<SessionEvent> out = ext.externalize(events);

        assertNotSame(events, out, "非文本文件应被外置");
        String ref = ((ToolResponseMessage) out.get(1).getMessage()).getResponses().get(0).responseData();
        assertTrue(FileReference.isReference(ref));
        assertTrue(ref.contains("kind: image"), "PNG 应标 kind: image，实际:\n" + ref);
        assertTrue(ref.contains("mime_type: image/png"), "PNG 应标 image/png，实际:\n" + ref);
        assertTrue(ref.contains("dimensions: 10x20"), "应带宽高");
        assertFalse(ref.contains("kind: text"), "不得误标 text");
    }

    @Test
    void idempotent_secondRunNoOp(@TempDir Path root) throws Exception {
        byte[] png = new byte[33];
        int[] sig = {0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A};
        for (int i = 0; i < 8; i++) png[i] = (byte) sig[i];
        Path file = root.resolve("shot.png");
        Files.write(file, png);
        String sid = "s1";
        List<SessionEvent> events = List.of(
                readCall(sid, "c1", file.toAbsolutePath().toString()),
                readResult(sid, "c1", "binary body"));
        SessionFileExternalizer ext = new SessionFileExternalizer(root);
        List<SessionEvent> once = ext.externalize(events);
        assertSame(once, ext.externalize(once), "已是引用 → 二次 no-op");
    }
}

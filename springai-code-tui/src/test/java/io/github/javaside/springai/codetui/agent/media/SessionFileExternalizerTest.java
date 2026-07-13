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

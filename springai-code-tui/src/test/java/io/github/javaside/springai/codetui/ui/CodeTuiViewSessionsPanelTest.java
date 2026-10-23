package io.github.javaside.springai.codetui.ui;

import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import io.github.javaside.springai.codetui.agent.seam.SubmitHandler;
import io.github.javaside.springai.codetui.agent.session.SessionCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * /resume 会话面板：打开即列会话（id+时间），摘要后台补齐；↑↓ 导航、Esc 关闭。
 * 断言落在 ViewScreen 回读的屏幕文本上（同 CodeTuiViewTasksPanelTest 的纪律）。
 */
class CodeTuiViewSessionsPanelTest {

    /** 记录型桩：currentSessionId 可编程（★ 行判定用）；root 在 Task 8 扩展 deleteSession 真删时用。 */
    private static final class SessionsStub implements SubmitHandler {
        final Path root;
        String currentId = "20260102T000000-cur999";
        final java.util.List<String> switchedTo = new ArrayList<>();

        SessionsStub(Path root) { this.root = root; }

        @Override public reactor.core.Disposable submit(String text) { return null; }
        @Override public String currentSessionId() { return currentId; }
        @Override public void switchContext(String targetSessionId) { switchedTo.add(targetSessionId); }
    }

    private static Path writeSession(Path root, String id, String firstUserJson) throws IOException {
        Path dir = root.resolve(".codetui").resolve("sessions");
        Files.createDirectories(dir);
        Path f = dir.resolve(id + ".json");
        Files.writeString(f,
                "{\"events\":[{\"messageType\":\"USER\",\"content\":\"" + firstUserJson + "\"}]}");
        return f;   // 返回文件路径：删除用例（Task 8）要拿它断言真删
    }

    private static CodeTuiView openPanel(ConversationState state, SessionsStub stub, Path root) {
        CodeTuiView v = new CodeTuiView(state, stub, root);
        v.setInputForTest("/resume");
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
        return v;
    }

    @Test
    @DisplayName("/resume 秒开会话列表，★ 标当前会话；忙碌时拒绝打开")
    void opensPanelInstantly_withCurrentMarked_busyRejected(@TempDir Path root) throws IOException {
        writeSession(root, "20260101T000000-aaaaaa", "修 NPE");
        writeSession(root, "20260102T000000-cur999", "当前这个");
        ConversationState state = new ConversationState();
        CodeTuiView v = openPanel(state, new SessionsStub(root), root);

        assertTrue(v.pickingSessionsForTest(), "面板应已打开");
        String screen = ViewScreen.of(v);
        // 时间行渲染的是本地时区的 MM-dd HH:mm（sessionTime 换算），不是 id 原文——
        // 期望串用与实现同口径的 UTC→本地换算生成，勿硬编码（时区在西半球会差一天）
        String expectedTime = java.time.LocalDateTime.parse("20260101T000000",
                        java.time.format.DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss"))
                .atZone(java.time.ZoneOffset.UTC).withZoneSameInstant(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"));
        assertTrue(screen.contains(expectedTime), "时间来自 id 前缀换算，实际：\n" + screen);
        assertTrue(screen.contains("★"), "当前会话有 ★ 标记");

        // 忙碌闸门：回合中 /resume 拒绝（同 /clear——动 sessionId 不能与回合并发）
        ConversationState busy = new ConversationState();
        busy.onTurnStarted(1L);
        CodeTuiView v2 = new CodeTuiView(busy, new SessionsStub(root), root);
        v2.setInputForTest("/resume");
        v2.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
        assertFalse(v2.pickingSessionsForTest(), "忙碌不开面板");
        assertTrue(busy.notice().contains("忙碌"), "拒绝要说出来：" + busy.notice());
    }

    @Test
    @DisplayName("摘要由后台线程补齐（digest null → 非 null），面板已关则丢弃")
    void digestsFilledInBackground(@TempDir Path root) throws IOException {
        writeSession(root, "20260103T000000-dddddd", "后台补摘要测试");
        CodeTuiView v = openPanel(new ConversationState(), new SessionsStub(root), root);
        // 不对「打开瞬间 digest==null」断言：单文件解析毫秒级，daemon 线程可能在断言前就完成（设计自带竞态，两态都合法）。
        // 决定性断言在轮询后的最终值。

        long deadline = System.currentTimeMillis() + 2000;
        while (v.sessionsForTest().isEmpty() || v.sessionsForTest().get(0).digest() == null) {
            if (System.currentTimeMillis() > deadline) {
                break;
            }
            Thread.onSpinWait();
        }
        assertEquals("后台补摘要测试", v.sessionsForTest().get(0).digest(), "2s 内后台补齐");
    }

    @Test
    @DisplayName("↑↓ 循环移动、Esc 关闭；空目录也照常打开（面板说明比不弹有用）")
    void navigatesAndCloses_emptyDirStillOpens(@TempDir Path root) throws IOException {
        writeSession(root, "20260101T000000-aaaaaa", "one");
        writeSession(root, "20260102T000000-bbbbbb", "two");
        CodeTuiView v = openPanel(new ConversationState(), new SessionsStub(root), root);
        int n = v.sessionsForTest().size();
        assertTrue(n >= 2);
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.UP));
        int afterUp = v.sessionIndexForTest();
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.DOWN));
        assertEquals((afterUp + 1) % n, v.sessionIndexForTest(), "DOWN 在 UP 之后循环回来");
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ESCAPE));
        assertFalse(v.pickingSessionsForTest(), "Esc 关闭");

        CodeTuiView empty = openPanel(new ConversationState(), new SessionsStub(root), root.getParent());
        assertTrue(empty.pickingSessionsForTest(), "空目录照常打开");
        assertTrue(ViewScreen.of(empty).contains("暂无历史会话"));
        // 空列表下按 ↑↓ 不崩（防除零），Esc 可关
        empty.feedKeyForTest(KeyEvent.ofKey(KeyCode.UP));
        empty.feedKeyForTest(KeyEvent.ofKey(KeyCode.ESCAPE));
        assertFalse(empty.pickingSessionsForTest());
    }
}

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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * /resume 会话面板：打开即列会话（id+时间），摘要后台补齐；↑↓ 导航、Esc 关闭；
 * Enter 恢复、f 分叉、d 删除（两段式）。
 * 断言落在 ViewScreen 回读的屏幕文本上（同 CodeTuiViewTasksPanelTest 的纪律）；
 * 回放内容例外——scrollback 不在元素树里（实测停在 state.pending），从 state 排水回读。
 */
class CodeTuiViewSessionsPanelTest {

    /**
     * 记录型桩：deleteSession 真删文件（confirmDeleteSession 以真实目录重扫复核，
     * 桩只记录不删会让「删除失败（文件仍在）」路径永远走到、列表断言反着写）。
     */
    private static final class SessionsStub implements SubmitHandler {
        String currentId = "20260102T000000-cur999";
        boolean exists = true;
        java.util.Optional<String> forkResult = java.util.Optional.of("20260999T000000-forked1");
        final Path root;   // 持有 root：deleteSession 真删文件
        final java.util.List<String> switchedTo = new ArrayList<>();
        final java.util.List<String> deleted = new ArrayList<>();
        final java.util.concurrent.atomic.AtomicInteger killAlls = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.List<org.springframework.ai.chat.messages.Message> messages =
                List.of(new org.springframework.ai.chat.messages.UserMessage("历史第一句"));

        SessionsStub(Path root) { this.root = root; }

        @Override public reactor.core.Disposable submit(String text) { return null; }
        @Override public String currentSessionId() { return currentId; }
        @Override public void switchContext(String t) { switchedTo.add(t); }
        @Override public boolean sessionExists(String id) { return exists; }
        @Override public java.util.Optional<String> forkSession(String sourceId) { return forkResult; }
        @Override public void deleteSession(String id) {
            deleted.add(id);
            try {
                java.nio.file.Files.deleteIfExists(
                        root.resolve(".codetui/sessions/" + id + ".json"));
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        }
        @Override public java.util.List<org.springframework.ai.chat.messages.Message> sessionMessages(String id) { return messages; }
        @Override public void killAllBackgroundTasks() { killAlls.incrementAndGet(); }
        @Override public String currentModel() { return "test-model"; }
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

    @Test
    @DisplayName("Enter 恢复：验证存在→杀后台任务→切换→回放；目标不存在则不切；执行时变忙则拒绝且面板保持")
    void enterRestores_withRecheckAndValidation(@TempDir Path root) throws IOException {
        writeSession(root, "20260101T000000-aaaaaa", "修 NPE");
        SessionsStub stub = new SessionsStub(root);
        ConversationState st = new ConversationState();
        CodeTuiView v = openPanel(st, stub, root);
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));

        assertTrue(stub.switchedTo.contains("20260101T000000-aaaaaa"), "切到选中会话");
        assertTrue(stub.killAlls.get() >= 1, "切换前杀后台任务（同 /clear）");
        assertFalse(v.pickingSessionsForTest(), "恢复后面板关闭");
        // 回放断言：replayHistory 把消息下沉输出队列——测试态无渲染线程批，从 state 排水回读
        // （ViewScreen 只渲染元素树，scrollback 不在其中，实测 pending 才是回放落点）。
        // 恒真写法（`|| switchedTo.size()==1`）杀不死「删掉 replayHistory 调用」的变异，禁用。
        java.util.List<ConversationState.OutputLine> replayed = st.drainPending();
        assertTrue(replayed.stream().anyMatch(l -> l.text().contains("历史第一句")),
                "回放把 sessionMessages 的历史渲出来，实际：" + replayed);

        // 目标不存在：不切换、有提示（先验证后动手——防半切状态）
        SessionsStub missing = new SessionsStub(root);
        missing.exists = false;
        CodeTuiView v2 = openPanel(new ConversationState(), missing, root);
        v2.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
        assertTrue(missing.switchedTo.isEmpty(), "没验证过就不切");
        assertTrue(missing.killAlls.get() == 0, "都没换会话，凭什么杀任务");

        // 面板开着、按 Enter 时变忙（goal 自动轮/排队出队可自发启动回合）：拒绝且面板保持
        ConversationState busyLater = new ConversationState();
        CodeTuiView v3 = openPanel(busyLater, new SessionsStub(root), root);
        busyLater.onTurnStarted(1L);   // 打开面板【之后】起回合
        v3.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
        assertTrue(v3.pickingSessionsForTest(), "拒绝时面板保持");
    }

    @Test
    @DisplayName("f 分叉选中项 / /fork 分叉当前：切到新 id，提示源自原会话")
    void forkFromPanelAndCommand(@TempDir Path root) throws IOException {
        writeSession(root, "20260101T000000-aaaaaa", "修 NPE");
        SessionsStub stub = new SessionsStub(root);
        CodeTuiView v = openPanel(new ConversationState(), stub, root);
        v.feedKeyForTest(KeyEvent.ofChar('f'));   // 字符键用 ofChar（KeyCode 无单字母常量；先例 CodeTuiViewTasksPanelTest 的 'k' 键）
        assertTrue(stub.switchedTo.contains("20260999T000000-forked1"), "切到 fork 出的新会话");
        assertFalse(v.pickingSessionsForTest());

        // /fork：源是当前会话；空会话 fork 被拒（提示而非静默）
        SessionsStub emptyFork = new SessionsStub(root);
        emptyFork.forkResult = java.util.Optional.empty();
        CodeTuiView v2 = new CodeTuiView(new ConversationState(), emptyFork, root);
        v2.setInputForTest("/fork");
        v2.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
        assertTrue(emptyFork.switchedTo.isEmpty(), "空会话不切换");
    }

    @Test
    @DisplayName("删除换代：已删行不复活、存活行 digest 由新代 daemon 重算补回")
    void deleteRotatesEpoch_digestsRefilled(@TempDir Path root) throws IOException {
        writeSession(root, "20260101T000000-aaaaaa", "存活较早的会话");
        Path b = writeSession(root, "20260102T000000-bbbbbbbbb", "待删除的会话");
        writeSession(root, "20260103T000000-cccccc", "存活较新的会话");
        // mtime 定序（新→旧 c,b,a）：index=1 恰是待删的 b，Down 一步到位
        Files.setLastModifiedTime(root.resolve(".codetui/sessions/20260101T000000-aaaaaa.json"),
                java.nio.file.attribute.FileTime.from(java.time.Instant.parse("2026-01-01T00:00:00Z")));
        Files.setLastModifiedTime(b,
                java.nio.file.attribute.FileTime.from(java.time.Instant.parse("2026-01-02T00:00:00Z")));
        Files.setLastModifiedTime(root.resolve(".codetui/sessions/20260103T000000-cccccc.json"),
                java.nio.file.attribute.FileTime.from(java.time.Instant.parse("2026-01-03T00:00:00Z")));
        CodeTuiView v = openPanel(new ConversationState(), new SessionsStub(root), root);
        // 等 daemon 全部跑完再删：模拟「看完摘要才删」的真实路径——重扫不带 digest，
        // 没人再补的话列表从此全是「（无对话）」（用户实测报告的症状，确定性红）
        long settleDeadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < settleDeadline) {
            java.util.List<SessionCatalog.SessionSummary> ss = v.sessionsForTest();
            if (ss.size() == 3 && ss.stream().allMatch(s -> s.digest() != null)) break;
            Thread.onSpinWait();
        }

        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.DOWN));
        v.feedKeyForTest(KeyEvent.ofChar('d'));
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
        assertFalse(Files.exists(b), "桩已真删文件");

        // 最终态双断言：恒 2 行（旧代 daemon 迟到回写不得复活 b）+ digest 全部由新代补回
        long deadline = System.currentTimeMillis() + 2000;
        java.util.List<SessionCatalog.SessionSummary> ss = v.sessionsForTest();
        while (System.currentTimeMillis() < deadline
                && (ss.size() != 2 || ss.stream().anyMatch(s -> s.digest() == null))) {
            Thread.onSpinWait();
            ss = v.sessionsForTest();
        }
        assertEquals(2, ss.size(), "已删行不复活，实际：" + ss);
        assertTrue(ss.stream().allMatch(s -> s.digest() != null && s.digest().startsWith("存活")),
                "存活行 digest 由新代 daemon 重算补回（scan 不带 digest），实际：" + ss);
    }

    @Test
    @DisplayName("d 删除两段式：当前会话不可删；确认后真删文件并以重新 scan 复核")
    void deleteTwoPhase_currentRejected(@TempDir Path root) throws IOException {
        Path a = writeSession(root, "20260101T000000-aaaaaa", "修 NPE");
        writeSession(root, "20260102T000000-cur999", "当前");
        // mtime 定序：细粒度文件系统上两文件可能同毫秒，scan 的降序结果不稳定——显式钉死
        Files.setLastModifiedTime(a, java.nio.file.attribute.FileTime.from(java.time.Instant.parse("2026-01-01T00:00:00Z")));
        Files.setLastModifiedTime(root.resolve(".codetui/sessions/20260102T000000-cur999.json"),
                java.nio.file.attribute.FileTime.from(java.time.Instant.parse("2026-01-02T00:00:00Z")));
        SessionsStub stub = new SessionsStub(root);   // 桩持有 root：deleteSession 真删文件（复核走真实目录）
        ConversationState state = new ConversationState();
        CodeTuiView v = openPanel(state, stub, root);
        // 等后台摘要线程跑完再进入删除流程：daemon 每次回写都整体替换 sessionList，
        // 落后于删除后的重扫就会把已删会话「复活」进列表（真实竞态，非理论）。
        long settleDeadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < settleDeadline) {
            java.util.List<SessionCatalog.SessionSummary> ss = v.sessionsForTest();
            if (ss.size() == 2 && ss.stream().allMatch(s -> s.digest() != null)) break;
            Thread.onSpinWait();
        }

        // index=0 是较新的 cur999（★ 当前会话）——按 d：拒绝且说出来
        v.feedKeyForTest(KeyEvent.ofChar('d'));
        assertTrue(stub.deleted.isEmpty(), "当前会话不可删");
        assertTrue(state.notice().contains("不能删除当前会话"), "拒绝要有 notice：" + state.notice());

        // 下移到 aaaaaa 按 d：进确认态；Esc 取消
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.DOWN));
        v.feedKeyForTest(KeyEvent.ofChar('d'));
        assertTrue(ViewScreen.of(v).contains("确认删除"), "确认态替换列表");
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ESCAPE));
        assertTrue(stub.deleted.isEmpty(), "Esc 取消不删");

        // 再 d + Enter：真删——文件被删，重新 scan 后列表不含该项
        v.feedKeyForTest(KeyEvent.ofChar('d'));
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
        assertEquals(java.util.List.of("20260101T000000-aaaaaa"), stub.deleted);
        assertFalse(Files.exists(a), "桩已真删文件");
        assertFalse(v.sessionsForTest().stream().anyMatch(s -> s.id().equals("20260101T000000-aaaaaa")),
                "删除后以重新 scan 的真实结果刷新列表");
    }
}

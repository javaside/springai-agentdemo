package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.seam.AskRequest;
import io.github.javaside.springai.codetui.agent.seam.AskResponder;
import io.github.javaside.springai.codetui.agent.seam.OptionSpec;
import io.github.javaside.springai.codetui.agent.seam.QuestionSpec;
import io.github.javaside.springai.codetui.agent.seam.SubmitHandler;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 作答面板（单选）纯状态断言：不起真实 TUI，直接喂 KeyEvent 给 View 的按键入口。 */
class CodeTuiViewAskTest {

    private static AskRequest ask(AtomicReference<Map<String, String>> got, AtomicBoolean cancelled, QuestionSpec... q) {
        AskResponder r = new AskResponder() {
            @Override public void answer(Map<String, String> a) { got.set(a); }
            @Override public void cancel() { cancelled.set(true); }
        };
        return new AskRequest(1, List.of(q), r);
    }

    private static QuestionSpec single(String qn, String... labels) {
        java.util.List<OptionSpec> os = new java.util.ArrayList<>();
        for (String l : labels) os.add(new OptionSpec(l, l + " 说明"));
        return new QuestionSpec(qn, "选择", os, false);
    }

    private static QuestionSpec multi(String qn, String... labels) {
        java.util.List<OptionSpec> os = new java.util.ArrayList<>();
        for (String l : labels) os.add(new OptionSpec(l, l + " 说明"));
        return new QuestionSpec(qn, "特性", os, true);
    }

    private static CodeTuiView view(ConversationState s) {
        return new CodeTuiView(s, (SubmitHandler) t -> null, Path.of("."));
    }

    @Test
    void singleSelect_enterRecordsLabelAndFinishes() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1);
        AtomicReference<Map<String, String>> got = new AtomicReference<>();
        s.onQuestionAsked(1, ask(got, new AtomicBoolean(), single("选哪个?", "A", "B", "C")));
        CodeTuiView v = view(s);
        v.tickForTest();                                    // drain 侦测进入作答态
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.DOWN));     // 高亮 A→B
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));    // 选 B
        assertEquals(Map.of("选哪个?", "B"), got.get());
        assertNull(s.peekModal(), "答完应从模态队列摘除");
    }

    @Test
    void multiQuestion_advancesThenFinishes() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1);
        AtomicReference<Map<String, String>> got = new AtomicReference<>();
        s.onQuestionAsked(1, ask(got, new AtomicBoolean(),
                single("Q1?", "A", "B"), single("Q2?", "X", "Y")));
        CodeTuiView v = view(s);
        v.tickForTest();
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));    // Q1 → A
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.DOWN));     // Q2 高亮 X→Y
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));    // Q2 → Y
        assertEquals(Map.of("Q1?", "A", "Q2?", "Y"), got.get());
    }

    @Test
    void esc_cancelsAndClears() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1);
        AtomicBoolean cancelled = new AtomicBoolean();
        s.onQuestionAsked(1, ask(new AtomicReference<>(), cancelled, single("选?", "A", "B")));
        CodeTuiView v = view(s);
        v.tickForTest();
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ESCAPE));
        assertTrue(cancelled.get(), "Esc 应触发 responder.cancel");
        assertNull(s.peekModal());
    }

    @Test
    void emptyOptions_autoCancelsWithoutEnteringModal() {
        // 上游 Java 校验不强制选项数；空选项问询若进模态，首个 ↑↓ 会 `% 0` 除零崩线程。drain 应优雅降级为取消。
        ConversationState s = new ConversationState();
        s.onTurnStarted(1);
        AtomicBoolean cancelled = new AtomicBoolean();
        QuestionSpec noOpts = new QuestionSpec("坏问题?", "坏", List.of(), false);
        s.onQuestionAsked(1, ask(new AtomicReference<>(), cancelled, noOpts));
        CodeTuiView v = view(s);
        v.tickForTest();                                   // 侦测到畸形问询
        assertTrue(cancelled.get(), "空选项问询应被自动取消");
        assertNull(s.peekModal(), "畸形问询应从 state 摘除，避免反复重入");
        // 必须走完整回合取消（与 Esc 同路径）：清空排队 + notice，使 doOnCancel 回滚会话、不残留 tool_calls。
        assertEquals("问询格式无效，已取消当前回合", s.notice(), "畸形降级应给出取消提示（证明走了 cancelTurnFor）");
        // 进模态后若误入，下面这次 ↑ 会除零抛异常；不抛即证明未入模态。
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.UP));
    }

    @Test
    void multiSelect_spaceTogglesCommaJoined() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1);
        AtomicReference<Map<String, String>> got = new AtomicReference<>();
        s.onQuestionAsked(1, ask(got, new AtomicBoolean(), multi("要哪些?", "认证", "数据库", "缓存")));
        CodeTuiView v = view(s);
        v.tickForTest();
        v.feedKeyForTest(KeyEvent.ofChar(' '));            // 勾选「认证」(高亮在 0)
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.DOWN));    // 高亮→数据库
        v.feedKeyForTest(KeyEvent.ofChar(' '));            // 勾选「数据库」
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));   // 确认
        assertEquals(Map.of("要哪些?", "认证, 数据库"), got.get());
    }

    @Test
    void multiSelect_enterWithNoneShowsNoticeAndStays() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1);
        AtomicReference<Map<String, String>> got = new AtomicReference<>();
        s.onQuestionAsked(1, ask(got, new AtomicBoolean(), multi("要哪些?", "A", "B")));
        CodeTuiView v = view(s);
        v.tickForTest();
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));   // 未勾选
        assertNull(got.get(), "未勾选不应提交");
        // 提示必须真正出现在作答态状态行（statusLine 早于通用 notice 分支 return，故须自带回显）。
        assertTrue(v.askStatusText().startsWith("至少选择一项"), "空选确认应在状态行显示提示，实际：" + v.askStatusText());
        // 勾选一项后提示应消失，且能正常提交。
        v.feedKeyForTest(KeyEvent.ofChar(' '));
        assertEquals("↑↓ 移动 · 空格勾选 · Enter 确认 · Esc 取消", v.askStatusText(), "勾选后应清掉提示、恢复操作指引");
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
        assertEquals(Map.of("要哪些?", "A"), got.get());
    }

    @Test
    void multiSelect_untoggleRemovesFromAnswer() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1);
        AtomicReference<Map<String, String>> got = new AtomicReference<>();
        s.onQuestionAsked(1, ask(got, new AtomicBoolean(), multi("要哪些?", "认证", "数据库", "缓存")));
        CodeTuiView v = view(s);
        v.tickForTest();
        v.feedKeyForTest(KeyEvent.ofChar(' '));            // 勾选「认证」(idx0)
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.DOWN));    // →数据库
        v.feedKeyForTest(KeyEvent.ofChar(' '));            // 勾选「数据库」(idx1)
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.UP));      // ←认证
        v.feedKeyForTest(KeyEvent.ofChar(' '));            // 取消勾选「认证」
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));   // 确认：只剩「数据库」
        assertEquals(Map.of("要哪些?", "数据库"), got.get());
    }

    @Test
    void other_entersFreeTextAndSubmitsTyped() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1);
        AtomicReference<Map<String, String>> got = new AtomicReference<>();
        s.onQuestionAsked(1, ask(got, new AtomicBoolean(), single("选?", "A", "B")));
        CodeTuiView v = view(s);
        v.tickForTest();
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.DOWN));    // A→B
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.DOWN));    // B→「其他」(idx2)
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));   // 进自由文本
        v.feedKeyForTest(KeyEvent.ofChar('C'));
        v.feedKeyForTest(KeyEvent.ofChar('+'));
        v.feedKeyForTest(KeyEvent.ofChar('+'));
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.BACKSPACE));  // 删一个 '+'
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));   // 确认 "C+"
        assertEquals(Map.of("选?", "C+"), got.get());
    }

    @Test
    void other_blankTextShowsNoticeAndStays() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1);
        AtomicReference<Map<String, String>> got = new AtomicReference<>();
        s.onQuestionAsked(1, ask(got, new AtomicBoolean(), single("选?", "A", "B")));
        CodeTuiView v = view(s);
        v.tickForTest();
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.DOWN));
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.DOWN));    // →「其他」
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));   // 进自由文本（空）
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));   // 空文本确认
        assertNull(got.get(), "空文本不应提交");
        assertTrue(v.askStatusText().startsWith("请输入内容"), "空文本应提示，实际：" + v.askStatusText());
    }

    @Test
    void other_escCancelsTurnFromFreeText() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1);
        AtomicBoolean cancelled = new AtomicBoolean();
        s.onQuestionAsked(1, ask(new AtomicReference<>(), cancelled, single("选?", "A", "B")));
        CodeTuiView v = view(s);
        v.tickForTest();
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.DOWN));
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.DOWN));    // →「其他」
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));   // 进自由文本
        v.feedKeyForTest(KeyEvent.ofChar('x'));
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ESCAPE));  // 子模式里 Esc 仍取消整回合
        assertTrue(cancelled.get(), "自由文本里 Esc 应取消整回合");
        assertNull(s.peekModal());
    }

    @Test
    void render_isNpeSafe_withAndWithoutActiveAsk() {
        // 回归：scope(activeAsk!=null, askChildren()) 会每帧 eager 构造 askChildren()，
        // 非作答态若不 null 判空则每帧崩渲染线程（单测只驱动按键、不 render，故此前漏掉）。
        ConversationState s = new ConversationState();
        CodeTuiView v = view(s);
        assertNotNull(v.renderForTest(), "非作答态 render 不应抛 NPE");   // activeAsk == null
        s.onTurnStarted(1);
        s.onQuestionAsked(1, ask(new AtomicReference<>(), new AtomicBoolean(), single("选?", "A", "B")));
        v.tickForTest();                                                // 进入作答态
        assertNotNull(v.renderForTest(), "作答态 render 应正常构造面板");
    }

    @Test
    void other_highlightThenBackToRealOption_recordsLabel() {
        // 高亮移到合成「其他」行再退回真实选项，Enter 应记该选项 label（合成 sentinel 不误触发自由文本）。
        ConversationState s = new ConversationState();
        s.onTurnStarted(1);
        AtomicReference<Map<String, String>> got = new AtomicReference<>();
        s.onQuestionAsked(1, ask(got, new AtomicBoolean(), single("选?", "A", "B")));
        CodeTuiView v = view(s);
        v.tickForTest();
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.DOWN));    // A→B
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.DOWN));    // B→「其他」(idx2)
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.UP));      // 「其他」→B
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));   // 选 B（不进自由文本）
        assertEquals(Map.of("选?", "B"), got.get());
    }

    @Test
    void other_blankNoticeDoesNotLeakToNextQuestion() {
        // 多问：Q1「其他」空确认留下提示 → 输入有效值提交 → Q2 状态行不应残留「请输入内容」。
        ConversationState s = new ConversationState();
        s.onTurnStarted(1);
        AtomicReference<Map<String, String>> got = new AtomicReference<>();
        s.onQuestionAsked(1, ask(got, new AtomicBoolean(),
                single("Q1?", "A", "B"), single("Q2?", "X", "Y")));
        CodeTuiView v = view(s);
        v.tickForTest();
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.DOWN));
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.DOWN));    // Q1→「其他」
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));   // 进自由文本
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));   // 空确认 → 提示
        v.feedKeyForTest(KeyEvent.ofChar('z'));
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));   // 提交 "z"，进 Q2
        assertTrue(v.askStatusText().startsWith("↑↓/kj 选择"), "Q2 状态行不应残留提示，实际：" + v.askStatusText());
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));   // Q2 → X
        assertEquals(Map.of("Q1?", "z", "Q2?", "X"), got.get());
    }

    // ── 长内容软折行（实报：AskUserQuestion 问题/选项一长，面板右边显示不全） ──────────
    // 面板 text() 是定宽渲染，超终端宽右截断——scrollback 有 TextWrap、tasks 面板有 clipToWidth、
    // 权限面板有 summarizeOneLine，作答面板此前一处都没有。修复后必须折行而非截断：尾部标记上屏即证明。

    @Test
    void longQuestion_wrapsWithIndentInsteadOfTruncating() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1);
        AtomicReference<Map<String, String>> got = new AtomicReference<>();
        String longQuestion = "这是一个足够长的问题正文用来超过四十列终端宽度".repeat(3) + "问题结尾标记";
        QuestionSpec q = new QuestionSpec(longQuestion, "口径",
                List.of(new OptionSpec("A", "A 说明")), false);
        s.onQuestionAsked(1, ask(got, new AtomicBoolean(), q));
        CodeTuiView v = view(s);
        v.terminalWidthForTest(40);
        v.tickForTest();
        String screen = ViewScreen.of(v, 40);
        assertTrue(screen.contains("问题结尾标记"), "长问题应折行完整显示而非右截断，实际屏幕：\n" + screen);
        assertTrue(screen.lines().anyMatch(l -> l.startsWith("    ") && l.contains("问题结尾标记")),
                "问题续行应带 4 空格缩进（首行是「  ❓ 」2 空格前缀），实际屏幕：\n" + screen);
    }

    @Test
    void longOptionText_wrapsWithIndentInsteadOfTruncating() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1);
        AtomicReference<Map<String, String>> got = new AtomicReference<>();
        String longDesc = "这段选项说明文字非常长同样超过四十列终端宽度".repeat(3) + "说明结尾标记";
        QuestionSpec q = new QuestionSpec("选哪个?", "选择",
                List.of(new OptionSpec("选项甲", longDesc), new OptionSpec("选项乙", "乙 说明")), false);
        s.onQuestionAsked(1, ask(got, new AtomicBoolean(), q));
        CodeTuiView v = view(s);
        v.terminalWidthForTest(40);
        v.tickForTest();
        String screen = ViewScreen.of(v, 40);
        assertTrue(screen.contains("说明结尾标记"), "长选项说明应折行完整显示而非右截断，实际屏幕：\n" + screen);
        assertTrue(screen.lines().anyMatch(l -> l.startsWith("      ") && l.contains("说明结尾标记")),
                "选项续行应带 6 空格缩进，实际屏幕：\n" + screen);
    }

    @Test
    void questionWithEmbeddedNewline_keepsBothParagraphs() {
        // text() 会把含 \n 的整块字符串塌成一行再截断（resultRows 注释记录过同款坑），折行前必须先拆段。
        ConversationState s = new ConversationState();
        s.onTurnStarted(1);
        AtomicReference<Map<String, String>> got = new AtomicReference<>();
        String firstPara = "第一段问题正文长到把第二段挤出四十列终端宽度".repeat(2);
        QuestionSpec q = new QuestionSpec(firstPara + "\n第二段：换行后的尾部标记", "口径",
                List.of(new OptionSpec("A", "A 说明")), false);
        s.onQuestionAsked(1, ask(got, new AtomicBoolean(), q));
        CodeTuiView v = view(s);
        v.terminalWidthForTest(40);
        v.tickForTest();
        String screen = ViewScreen.of(v, 40);
        assertTrue(screen.contains("第二段：换行后的尾部标记"),
                "内嵌换行的问题两段都应可见（先拆段再折行），实际屏幕：\n" + screen);
    }

    @Test
    void questionWithEmbeddedNewline_secondParagraphCarriesIndent() {
        // 实机反馈：含 \n 的第二段首行顶格，与续行缩进不对齐、看起来散架。
        // 根因：wrapPanelLine 的「本段首行」状态每段重置——第二段首行按首行处理（顶格 + 吃满整行宽）。
        ConversationState s = new ConversationState();
        s.onTurnStarted(1);
        AtomicReference<Map<String, String>> got = new AtomicReference<>();
        QuestionSpec q = new QuestionSpec("第一段短正文\n这是换行后的第二段", "口径",
                List.of(new OptionSpec("A", "A 说明")), false);
        s.onQuestionAsked(1, ask(got, new AtomicBoolean(), q));
        CodeTuiView v = view(s);
        v.terminalWidthForTest(40);
        v.tickForTest();
        String screen = ViewScreen.of(v, 40);
        assertTrue(screen.lines().anyMatch(l -> l.startsWith("    这是换行后的第二段")),
                "换行后的段首行应与续行同缩进（4 空格），实际屏幕：\n" + screen);
        assertTrue(screen.lines().noneMatch(l -> l.startsWith("这是换行后的第二段")),
                "第二段首行不应顶格（顶格 = 首行状态未跨段），实际屏幕：\n" + screen);
    }

    @Test
    void freeTextEcho_wrapsLongTypedInput() {
        ConversationState s = new ConversationState();
        s.onTurnStarted(1);
        AtomicReference<Map<String, String>> got = new AtomicReference<>();
        s.onQuestionAsked(1, ask(got, new AtomicBoolean(), single("选?", "A", "B")));
        CodeTuiView v = view(s);
        v.terminalWidthForTest(40);
        v.tickForTest();
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.DOWN));    // A→B
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.DOWN));    // B→「其他」
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));   // 进自由文本
        String longTyped = "自定义输入内容足够长超过四十列".repeat(3) + "输入结尾标记";
        for (char c : longTyped.toCharArray()) v.feedKeyForTest(KeyEvent.ofChar(c));
        String screen = ViewScreen.of(v, 40);
        // CJK 无空格可断、按显示宽度硬折，标记串可能跨折点——去空白后整段比对，钉住「一字不丢」。
        String squashed = screen.replace(" ", "").replace("\n", "");
        assertTrue(squashed.contains(longTyped),
                "自由文本回显应折行完整显示而非右截断，实际屏幕：\n" + screen);
    }
}

package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.seam.SubmitHandler;
import dev.tamboui.text.Text;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.PasteEvent;
import dev.tamboui.toolkit.element.Element;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 长文本粘贴折叠（{@code [TEXTn]}）：输入框只放占位符、原文进映射、提交展开。
 *
 * <p><b>动机</b>：贴几百 KB 日志时输入框随行数无限增高、把整个界面顶飞；与图片
 * {@code [IMAGEn]} 同套路——输入框显示短标记，真实内容在提交时还原给模型
 * （类似 Claude Code 的 pasted text 折叠）。
 *
 * <p><b>证据边界</b>：本类钉「折叠登记 / 展开 / 附件行提示 / Esc 回填再折叠」；
 * 粘贴<b>事件</b>本身被拆分导致自动发送的问题在 patch 模块
 * {@code EventParserPasteTest}（那是解析器层的根因，UI 层修不了）。
 */
class TextPasteCollapseTest {

    // ── 纯函数：折叠判定 / 展开 / 附件行 ─────────────────────

    /** 阈值口径：>400 字符<b>或</b> >12 行才折叠——贴常规代码片段（≤12 行）必须还能直接编辑。 */
    @Test
    void collapseThresholdIsCharsOrLines() {
        assertFalse(CodeTuiView.shouldCollapsePaste("x".repeat(400)), "恰好 400 字符不折叠（边界值放行）");
        assertTrue(CodeTuiView.shouldCollapsePaste("x".repeat(401)), "超过 400 字符折叠");
        assertFalse(CodeTuiView.shouldCollapsePaste("l\n".repeat(11) + "l"), "恰好 12 行不折叠");
        assertTrue(CodeTuiView.shouldCollapsePaste("l\n".repeat(12) + "l"), "超过 12 行折叠");
        assertFalse(CodeTuiView.shouldCollapsePaste(""));
        assertFalse(CodeTuiView.shouldCollapsePaste(null));
    }

    @Test
    void placeholderExpandsToRegisteredTextVerbatim() {
        // 与 [IMAGEn] 不同：文本展开不加引号——它不进识别器，直接进正文
        String full = "line1\nline2\nline3";
        assertEquals("看这个 " + full,
                CodeTuiView.expandTextPlaceholders("看这个 [TEXT1]", Map.of(1, full)));
    }

    /** 未登记编号 / 残缺标记 = 用户手打的普通文本，原样发出（同 [IMAGEn] 语义）。 */
    @Test
    void unregisteredOrPartialMarkersStayLiteral() {
        Map<Integer, String> map = Map.of(1, "full");
        assertEquals("参考 [TEXT9]", CodeTuiView.expandTextPlaceholders("参考 [TEXT9]", map));
        assertEquals("[TEXT1", CodeTuiView.expandTextPlaceholders("[TEXT1", map));
        assertEquals("看 [TEXT 和 TEXT1]", CodeTuiView.expandTextPlaceholders("看 [TEXT 和 TEXT1]", map));
    }

    /** 附件行提示：说清段数与总量——用户必须知道将发出去的是多大一段。 */
    @Test
    void foldHintReportsSegmentsAndTotalSize() {
        assertTrue(CodeTuiView.textFoldLine("看 [TEXT1] 和 [TEXT2]",
                Map.of(1, "a".repeat(3000), 2, "b".repeat(200))).contains("2 段"));
        assertTrue(CodeTuiView.textFoldLine("看 [TEXT1] 和 [TEXT2]",
                Map.of(1, "a".repeat(3000), 2, "b".repeat(200))).contains("3.2K"));
        assertTrue(CodeTuiView.textFoldLine("[TEXT1]", Map.of(1, "x".repeat(512))).contains("512"));
        assertEquals("", CodeTuiView.textFoldLine("普通文本", Map.of(1, "x".repeat(999))), "没有折叠段不出行");
    }

    /** 附件行铁律：单行（一个 OutputLine = 一个物理行，多行会被 println 塌掉）。 */
    @Test
    void foldHintIsAlwaysASingleLine() {
        assertFalse(CodeTuiView.textFoldLine("[TEXT1]", Map.of(1, "x\ny".repeat(50))).contains("\n"));
    }

    // ── 粘贴接线：折叠登记 / 归一 / 编号 ─────────────────────

    private static final ScrollbackPrinter.Sink NULL_SINK = new ScrollbackPrinter.Sink() {
        @Override public void println(Text line)   { }
        @Override public void println(String line) { }
    };

    private record CapturingHandler(List<String> submitted, List<String> takenBack) implements SubmitHandler {
        CapturingHandler() { this(new ArrayList<>(), new ArrayList<>()); }
        @Override public reactor.core.Disposable submit(String text) { submitted.add(text); return null; }
        @Override public List<String> takeBackInterjections() { return takenBack; }
    }

    private static CodeTuiView view(Path root, SubmitHandler handler) {
        return new CodeTuiView(new ConversationState(), handler, root, NULL_SINK);
    }

    /** 走真实粘贴处理器；反射仅用于取得私有输入元素（同 AttachmentLineTest）。 */
    private static void paste(CodeTuiView view, String text) throws Exception {
        var type = Class.forName(CodeTuiView.class.getName() + "$InputBox");
        var constructor = type.getDeclaredConstructor(CodeTuiView.class);
        constructor.setAccessible(true);
        Element input = (Element) constructor.newInstance(view);
        assertTrue(input.handlePasteEvent(new PasteEvent(text)).isHandled());
    }

    private static String log(int lines, int charsPerLine) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < lines; i++) {
            if (i > 0) b.append('\n');
            b.append(String.format("%04d ", i)).append("E".repeat(charsPerLine));
        }
        return b.toString();
    }

    @Test
    @DisplayName("超长粘贴折叠成 [TEXT1]，附件行如实提示")
    void longPasteCollapsesToPlaceholderWithHint(@TempDir Path root) throws Exception {
        CodeTuiView v = view(root, new CapturingHandler());
        paste(v, log(60, 80));
        assertEquals("[TEXT1]", v.inputTextForTest(), "超长日志必须折叠，输入框不能被顶飞");
        assertTrue(ViewScreen.of(v).contains("已折叠 1 段"), "折叠了却不提示，用户不知道会发出什么");
    }

    @Test
    @DisplayName("短于阈值的粘贴原样进输入框（可编辑）")
    void shortPasteStaysEditable(@TempDir Path root) throws Exception {
        CodeTuiView v = view(root, new CapturingHandler());
        String snippet = "try {\n  doWork();\n} catch (Exception e) {\n  log.error(e);\n}";
        paste(v, snippet);
        assertEquals(snippet, v.inputTextForTest());
    }

    /**
     * CRLF / 裸 CR 归一成 LF：TextAreaState 只认 {@code \n} 作换行，{@code \r} 会以
     * 不可见字面字符落进输入框——既毁显示也污染发给模型的文本（「不可见字符」的另一半来源）。
     */
    @Test
    void crlfAndBareCrAreNormalizedToLf(@TempDir Path root) throws Exception {
        CapturingHandler handler = new CapturingHandler();
        CodeTuiView v = view(root, handler);
        paste(v, "a\r\nb\rc\n" + "x".repeat(500));
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
        assertEquals(1, handler.submitted.size());
        assertFalse(handler.submitted.get(0).contains("\r"), "CR 必须被归一成 LF");
    }

    @Test
    void multipleLongPastesGetSequentialNumbers(@TempDir Path root) throws Exception {
        CodeTuiView v = view(root, new CapturingHandler());
        paste(v, log(20, 60));
        paste(v, log(20, 60));
        assertEquals("[TEXT1] [TEXT2]", v.inputTextForTest());
    }

    // ── 提交链路：展开 + 复位 ───────────────────────────────

    @Test
    @DisplayName("提交时 [TEXTn] 展开成全文（模型必须拿到原文）")
    void submitExpandsPlaceholderToFullText(@TempDir Path root) throws Exception {
        CapturingHandler handler = new CapturingHandler();
        CodeTuiView v = view(root, handler);
        String full = log(30, 40);
        paste(v, full);
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));

        assertEquals(1, handler.submitted.size(), "提交没发生");
        assertEquals(full, handler.submitted.get(0), "占位符没展开，模型只看到字面 [TEXT1]");
    }

    /** /queue 也是提交：排队的消息同样要展开，否则出队后发出去的就是字面占位符。 */
    @Test
    void queuedSubmitAlsoExpandsPlaceholders(@TempDir Path root) throws Exception {
        CapturingHandler handler = new CapturingHandler();
        CodeTuiView v = view(root, handler);
        String full = log(30, 40);
        paste(v, full);
        v.setInputForTest("/queue 看看这段 [TEXT1]");
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));
        assertEquals("看看这段 " + full, handler.submitted.get(0));
    }

    @Test
    void numberingResetsAfterSubmit(@TempDir Path root) throws Exception {
        CodeTuiView v = view(root, new CapturingHandler());
        paste(v, log(20, 60));
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER));   // 提交，clearInput 复位
        paste(v, log(20, 60));
        assertEquals("[TEXT1]", v.inputTextForTest(), "提交后编号没复位");
    }

    // ── Esc 回填：插话是展开后的全文，回填必须重新折叠 ────────

    /**
     * 插话随取消被取回时是<b>展开后</b>的全文（几 KB 日志直接 setText 会把输入框顶飞）——
     * 回填路径必须对超长条目重新折叠登记。
     */
    @Test
    void escRefillReFoldsLongInterjection(@TempDir Path root) throws Exception {
        CapturingHandler handler = new CapturingHandler(List.of(), List.of(log(40, 50)));
        CodeTuiView v = view(root, handler);
        v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ESCAPE));
        assertEquals("[TEXT1]", v.inputTextForTest(), "回填的全文必须重新折叠");
    }
}

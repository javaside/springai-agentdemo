package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.SubmitHandler;
import dev.tamboui.text.Text;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.KeyModifiers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 输入框附件行 + Ctrl+G 撤销。
 *
 * <p><b>本类的证据边界</b>：纯函数用例只证「文本内容对不对」，
 * {@link #attachmentLineIsRenderedOnScreen} 证「有没有真的画到屏幕上」（渲染分支顺序类缺陷）。
 * 「Ctrl+G 这个键能否真的到达应用」<b>本类证明不了</b>——{@code feedKeyForTest} 走的是
 * {@code InputBox.handleKeyEvent}，绕过了 TamboUI 的按键路由器；{@code Tab} 就是这么被
 * {@code FOCUS_NEXT/PREVIOUS} 悄悄吃掉、单测全绿实机死键的。那条证据只能由 pty 冒烟产生。
 */
class AttachmentLineTest {

    @Test
    void oneImageShowsNameAndCancelHint() {
        String line = CodeTuiView.attachmentLine(1, 0, "bug.png");
        assertTrue(line.contains("1 张"), line);
        assertTrue(line.contains("bug.png"), line);
        assertTrue(line.contains("Ctrl+G"), "没告诉用户怎么取消：" + line);
    }

    /** 多张时不逐个列名（会撑爆一行），只给数量。 */
    @Test
    void multipleImagesShowCountWithoutListingAll() {
        String line = CodeTuiView.attachmentLine(3, 0, "a.png");
        assertTrue(line.contains("3 张"), line);
    }

    /** 超上限必须如实说出来——静默截断会让用户以为都附上了。 */
    @Test
    void overflowIsReportedNotSilentlyDropped() {
        String line = CodeTuiView.attachmentLine(3, 2, "a.png");
        assertTrue(line.contains("2"), "被丢弃的张数没说：" + line);
    }

    /** 没有图时不出这一行——常态纯文本输入多一行恒定噪音会稀释真正要看的东西。 */
    @Test
    void noImagesMeansNoLine() {
        assertTrue(CodeTuiView.attachmentLine(0, 0, null).isEmpty());
    }

    /** 取消后改为「已取消」，且不再提示 Ctrl+G——已经取消了，再提示是噪音。 */
    @Test
    void cancelledStateIsVisuallyDistinct() {
        String line = CodeTuiView.attachmentLineCancelled();
        assertTrue(line.contains("已取消"), line);
        assertFalse(line.contains("Ctrl+G"), "已取消还提示 Ctrl+G 是噪音：" + line);
    }

    /** 附件行必须是单行——本项目铁律：一个 OutputLine = 一个物理行，多行会被 println 塌成一行截断。 */
    @Test
    void attachmentLineIsAlwaysASingleLine() {
        assertFalse(CodeTuiView.attachmentLine(3, 2, "a.png").contains("\n"));
        assertFalse(CodeTuiView.attachmentLineCancelled().contains("\n"));
    }

    // ── 渲染级 + 状态机 ──────────────────────────────────────
    // 上面的纯函数用例就算「忘了把附件行画到屏幕上」也照样全绿，下面这些才钉住接线。

    /** 丢弃型 scrollback 接缝：本类只断言面板/输入区，scrollback 内容不参与。 */
    private static final ScrollbackPrinter.Sink NULL_SINK = new ScrollbackPrinter.Sink() {
        @Override public void println(Text line)   { }
        @Override public void println(String line) { }
    };

    private static CodeTuiView view(Path root) {
        return new CodeTuiView(new ConversationState(), new SubmitHandler() {
            @Override public reactor.core.Disposable submit(String text) { return null; }
        }, root, NULL_SINK);
    }

    private static Path png(Path dir, String rel) throws Exception {
        Path p = dir.resolve(rel);
        Files.createDirectories(p.getParent() == null ? dir : p.getParent());
        ImageIO.write(new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB), "png", p.toFile());
        return p;
    }

    /** Ctrl+G。构造写法照抄 {@code CodeTuiViewEditShortcutTest.ctrl(char)}。 */
    private static KeyEvent ctrlG() { return KeyEvent.ofChar('g', KeyModifiers.CTRL); }

    /** 走真实提交路径（Enter），而不是另开一个只给测试用的后门方法。 */
    private static void submit(CodeTuiView v) { v.feedKeyForTest(KeyEvent.ofKey(KeyCode.ENTER)); }

    @Test
    @DisplayName("输入框里写了图片路径 → 附件行真的出现在屏幕上")
    void attachmentLineIsRenderedOnScreen(@TempDir Path root) throws Exception {
        png(root, "docs/bug.png");
        CodeTuiView v = view(root);
        v.setInputForTest("看下 docs/bug.png 这个报错");

        // ⚠ 断言不能只找 "bug.png"：输入框里画着的就是那段文本，找得到不代表附件行画了。
        // 必须找只有附件行才有的字样。
        String screen = ViewScreen.of(v);
        assertTrue(screen.contains("已附带 1 张图片（bug.png）"),
                "附件行没画到屏幕上（纯函数对了不等于画了）：\n" + screen);
        assertTrue(screen.contains("Ctrl+G"), "屏幕上没有取消提示：\n" + screen);
    }

    @Test
    @DisplayName("Ctrl+G 后屏幕改显「已取消附件」")
    void ctrlGCancelsAttachmentsOnScreen(@TempDir Path root) throws Exception {
        png(root, "docs/bug.png");
        CodeTuiView v = view(root);
        v.setInputForTest("看下 docs/bug.png 这个报错");
        v.feedKeyForTest(ctrlG());

        String screen = ViewScreen.of(v);
        assertTrue(screen.contains("已取消附件"), "Ctrl+G 没让附件行进入已取消态：\n" + screen);
        assertFalse(screen.contains("Ctrl+G"), "已取消还提示 Ctrl+G 是噪音：\n" + screen);
    }

    /**
     * Ctrl+G 必须在转交 textArea <b>之前</b>被本视图拦下（HANDLED），文本原样不动。
     *
     * <p>⚠ 只断言「文本没变」是<b>不会失败的测试</b>：变异实测把 Ctrl+G 分支整个删掉，文本照样不变
     * ——底层 textArea 本就不插入控制字符。故必须同时断言 HANDLED，那才是「被我们拦下了」的证据。
     */
    @Test
    void ctrlGIsInterceptedNotPassedToEditor(@TempDir Path root) throws Exception {
        png(root, "docs/bug.png");
        CodeTuiView v = view(root);
        v.setInputForTest("看下 docs/bug.png");
        assertTrue(v.feedKeyForTest(ctrlG()).isHandled(), "Ctrl+G 没被拦下，漏给了编辑器");
        assertTrue(v.inputTextForTest().equals("看下 docs/bug.png"),
                "Ctrl+G 被当普通字符吃进了输入框：" + v.inputTextForTest());
    }

    /**
     * 取消态必须在提交后复位——否则取消一次之后<b>这个会话里再也附不上图</b>，
     * 而且用户完全不知道为什么。
     */
    @Test
    @DisplayName("提交后取消态复位：下一条消息里的图又能附上")
    void cancelResetsAfterSubmit(@TempDir Path root) throws Exception {
        png(root, "docs/bug.png");
        CodeTuiView v = view(root);
        v.setInputForTest("看下 docs/bug.png");
        v.feedKeyForTest(ctrlG());
        submit(v);                                    // 提交（清空输入框 + 复位取消态）

        v.setInputForTest("再看下 docs/bug.png");
        String screen = ViewScreen.of(v);
        assertTrue(screen.contains("Ctrl+G"),
                "取消态没在提交后复位，之后的消息永远附不上图：\n" + screen);
        assertFalse(screen.contains("已取消附件"), "提交后还停在已取消态：\n" + screen);
    }

    /** 斜杠命令分支也会 {@code inputState.clear()}，同样得复位——它们和提交走的是不同的 return 路径。 */
    @Test
    @DisplayName("斜杠命令清空输入框后取消态也复位")
    void cancelResetsAfterSlashCommand(@TempDir Path root) throws Exception {
        png(root, "docs/bug.png");
        CodeTuiView v = view(root);
        v.setInputForTest("看下 docs/bug.png");
        v.feedKeyForTest(ctrlG());
        v.setInputForTest("/help");
        submit(v);                                    // /help：走 inputState.clear() 后 return 的分支

        v.setInputForTest("再看下 docs/bug.png");
        String screen = ViewScreen.of(v);
        assertTrue(screen.contains("Ctrl+G"), "斜杠命令路径漏了复位取消态：\n" + screen);
    }
}

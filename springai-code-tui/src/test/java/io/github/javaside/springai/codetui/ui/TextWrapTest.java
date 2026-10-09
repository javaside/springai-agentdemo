package io.github.javaside.springai.codetui.ui;

import dev.tamboui.style.Style;
import dev.tamboui.text.CharWidth;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TextWrap}：样式感知的显示宽度折行。契约：①每段宽度 ≤ width（唯一例外：单个宽字符
 * 都放不下时硬吃 1 个防死循环）；②所有段的纯文本连起来恰好等于原文（不丢不加）；③样式跨
 * 拆分点保留。丢内容 = 用户实报的「模型回复被截断」。
 */
class TextWrapTest {

    private static final Style A = Style.create().bold();
    private static final Style B = Style.create().italic();

    private static String joined(List<Text> pieces) {
        StringBuilder sb = new StringBuilder();
        for (Text t : pieces) sb.append(t.rawContent());
        return sb.toString();
    }

    private static void assertNoOverflow(List<Text> pieces, int width) {
        for (Text t : pieces) {
            assertTrue(CharWidth.of(t.rawContent()) <= width,
                    "段宽 %d 超出 %d：%s".formatted(CharWidth.of(t.rawContent()), width, t.rawContent()));
        }
    }

    @Test
    void fitsUnchanged() {
        List<Text> out = TextWrap.wrap(Text.styled("abc", A), 10);
        assertEquals(1, out.size());
        assertEquals("abc", out.get(0).rawContent());
        assertEquals(A, out.get(0).lines().get(0).spans().get(0).style(), "样式应原样保留");
    }

    @Test
    void asciiSplitsAtWidth() {
        List<Text> out = TextWrap.wrap(Text.styled("abcdefghij", A), 4);
        assertEquals(List.of("abcd", "efgh", "ij"),
                out.stream().map(Text::rawContent).toList());
        assertNoOverflow(out, 4);
    }

    @Test
    void cjkNeverSplitsAWideCharAcrossLines() {
        // 宽 5 放不下第 3 个汉字的右半：第 2 段从整字起，不切半
        List<Text> out = TextWrap.wrap(Text.styled("你好世界", A), 5);
        assertEquals(List.of("你好", "世界"), out.stream().map(Text::rawContent).toList());
        assertNoOverflow(out, 5);
        assertEquals("你好世界", joined(out), "折行不得丢字——丢字就是「回复被截断」");
    }

    @Test
    void styleSurvivesTheSplitPoint() {
        Text mixed = Text.from(Line.from(Span.styled("ab", A), Span.styled("cdef", B)));
        List<Text> out = TextWrap.wrap(mixed, 3);
        assertEquals(List.of("abc", "def"), out.stream().map(Text::rawContent).toList());
        List<Span> first = out.get(0).lines().get(0).spans();
        assertEquals(A, first.get(0).style(), "拆分点前的 span 样式不变");
        assertEquals(B, first.get(1).style(), "同一行内的第二段样式不变");
        assertEquals(B, out.get(1).lines().get(0).spans().get(0).style(), "续行继承被拆 span 的样式");
    }

    @Test
    void emptyTextYieldsSingleEmptyLine() {
        List<Text> out = TextWrap.wrap(Text.from(""), 10);
        assertEquals(1, out.size());
        assertEquals("", out.get(0).rawContent());
    }

    @Test
    void wideCharThatCannotFitIsStillEmitted() {
        // 宽 1 连一个汉字都放不下：硬吃 1 个保证前进，绝不死循环、绝不丢字
        List<Text> out = TextWrap.wrap(Text.styled("你好", A), 1);
        assertEquals("你好", joined(out));
        assertEquals(2, out.size());
    }

    @Test
    void longMixedContentLosesNothing() {
        String s = "模型回复 abc 中英夹杂，还有 URL https://api.deepseek.com/chat/completions 这种长串。";
        List<Text> out = TextWrap.wrap(Text.styled(s, A), 20);
        assertEquals(s, joined(out), "全部段拼回去必须与原文一致");
        assertNoOverflow(out, 20);
    }

    /**
     * 行内控制字符兜底换空格——「不丢不加」契约的唯一例外。
     *
     * <p>为什么必须在打印层做：scrollback 的行若带着控制字符到达终端会破坏
     * 「一个 println = 一个物理行」纪律：U+0007 响铃、U+009B 单字符 CSI 吞后续
     * 字符、{@code \r} 把光标拉回行首（2026-10-09 事故「发送后内容重复/看不见」
     * 的形态）。来源不止粘贴一途（存量会话回放、历史回溯、模型输出本身都可能带），
     * 输入层规范化堵不完，出口必须兜底。换空格而非删除：宽度可预期（1 列）、
     * 不粘连两侧词语——与 shadow Buffer.setString 对 C0/C1/DEL 的替换同一纪律，
     * 两层互为纵深。
     *
     * <p>构造必须走 {@code Text.from(Line.from(Span.styled(...)))}：{@code Text.styled}
     * 在构造层用 {@code BufferedReader.lines()} 拆行，{\@code \r} 会被当行终止符
     * 静默吃掉、到不了本类——那条路径的 CR 兜底事实上由拆行承担；本类兜底的是
     * <b>不走 Text 字符串构造</b>的 Span 直构路径（ScrollbackPrinter 拼装正文用的
     * 正是它）与 BEL/CSI/DEL 等不被拆行消化的控制字符。
     */
    @Test
    void inLineControlCharsBecomeSpaces() {
        Text input = Text.from(Line.from(Span.styled("前半\r中段\u0007后段\u009b尾段\u007f", A)));
        assertEquals("前半\r中段\u0007后段\u009b尾段\u007f", input.rawContent(),
                "前置：Span 直构必须原样保留全部控制字符（否则测的不是 TextWrap）");
        List<Text> out = TextWrap.wrap(input, 40);
        // 源串含 4 个控制字符（\r BEL CSI DEL）各换 1 个空格；rawContent 保留尾空格
        assertEquals("前半 中段 后段 尾段 ", joined(out), "CR/BEL/CSI/DEL 一律换空格");
        for (Text t : out) {
            for (char c : t.rawContent().toCharArray()) {
                assertTrue(c >= 0x20 && c != 0x7f || c == '\n',
                        "打印行不得残留控制字符，仍有 U+%04X".formatted((int) c));
            }
        }
    }
}

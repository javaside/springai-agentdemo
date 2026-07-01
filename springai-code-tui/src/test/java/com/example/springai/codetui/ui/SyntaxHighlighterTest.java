package com.example.springai.codetui.ui;

import dev.tamboui.style.Color;
import dev.tamboui.style.Modifier;
import dev.tamboui.text.Span;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SyntaxHighlighter 测试。
 *
 * <p>Span 暴露可读 getter（content()/style()），Style 暴露 fg()（Optional&lt;Color&gt;）与
 * effectiveModifiers()（EnumSet&lt;Modifier&gt;），因此除文本分段外也断言样式。</p>
 */
class SyntaxHighlighterTest {

    // ---- helpers ----

    private static String concat(List<Span> spans) {
        StringBuilder sb = new StringBuilder();
        for (Span s : spans) {
            sb.append(s.content());
        }
        return sb.toString();
    }

    private static Span spanWithText(List<Span> spans, String text) {
        return spans.stream().filter(s -> s.content().equals(text)).findFirst().orElse(null);
    }

    private static Span spanStartingWith(List<Span> spans, String prefix) {
        return spans.stream().filter(s -> s.content().startsWith(prefix)).findFirst().orElse(null);
    }

    private static boolean hasFg(Span s, Color color) {
        return s != null && s.style().fg().map(c -> c.equals(color)).orElse(false);
    }

    // ---- lossless-ness ----

    @Test
    void concatenationEqualsInput_noCharactersLost() {
        String line = "int x = \"hi\"; // c";
        List<Span> spans = SyntaxHighlighter.highlight(line, "java", false).spans();
        assertEquals(line, concat(spans));
    }

    @Test
    void emptyLineReturnsSingleRawEmptySpan() {
        SyntaxHighlighter.Result r = SyntaxHighlighter.highlight("", "java", false);
        assertEquals(1, r.spans().size());
        assertEquals("", r.spans().get(0).content());
        assertFalse(r.stillInBlockComment());
    }

    // ---- segmentation ----

    @Test
    void keywordStringCommentAreDistinctSpans() {
        String line = "int x = \"hi\"; // c";
        List<Span> spans = SyntaxHighlighter.highlight(line, "java", false).spans();

        Span kw = spanWithText(spans, "int");
        Span str = spanWithText(spans, "\"hi\"");
        Span cmt = spanStartingWith(spans, "//");

        assertTrue(kw != null, "keyword span 'int' should exist");
        assertTrue(str != null, "string span '\"hi\"' should exist");
        assertTrue(cmt != null, "comment span starting with // should exist");

        // 样式断言
        assertTrue(hasFg(kw, Color.MAGENTA), "keyword should be MAGENTA");
        assertTrue(hasFg(str, Color.GREEN), "string should be GREEN");
        assertTrue(hasFg(cmt, Color.DARK_GRAY), "comment should be DARK_GRAY");
    }

    @Test
    void numberIsHighlightedCyan() {
        List<Span> spans = SyntaxHighlighter.highlight("x = 42", "java", false).spans();
        Span num = spanWithText(spans, "42");
        assertTrue(num != null, "number span '42' should exist");
        assertTrue(hasFg(num, Color.CYAN));
    }

    @Test
    void identifierGetsNoStyle() {
        List<Span> spans = SyntaxHighlighter.highlight("foo", "java", false).spans();
        Span id = spanWithText(spans, "foo");
        assertTrue(id != null);
        // 默认标识符：无前景色。
        assertTrue(id.style().fg().isEmpty(), "plain identifier should have no fg color");
    }

    // ---- comments/strings win over keywords ----

    @Test
    void keywordInsideCommentIsNotHighlighted() {
        // "// int" —— int 是注释的一部分，不应有单独的 keyword span。
        List<Span> spans = SyntaxHighlighter.highlight("// int", "java", false).spans();
        assertEquals("// int", concat(spans));
        Span standaloneKeyword = spanWithText(spans, "int");
        assertTrue(standaloneKeyword == null, "'int' inside comment must not be a separate keyword span");
        // 整行是一个注释 span。
        Span cmt = spanStartingWith(spans, "//");
        assertTrue(hasFg(cmt, Color.DARK_GRAY));
    }

    @Test
    void keywordInsideStringIsNotHighlighted() {
        List<Span> spans = SyntaxHighlighter.highlight("s = \"int for\"", "java", false).spans();
        assertEquals("s = \"int for\"", concat(spans));
        assertTrue(spanWithText(spans, "int") == null, "keyword inside string must not be separate span");
        assertTrue(spanWithText(spans, "for") == null);
        Span str = spanWithText(spans, "\"int for\"");
        assertTrue(hasFg(str, Color.GREEN));
    }

    @Test
    void escapedQuoteInsideStringDoesNotTerminate() {
        String line = "\"a\\\"b\"";
        List<Span> spans = SyntaxHighlighter.highlight(line, "java", false).spans();
        assertEquals(line, concat(spans));
        Span str = spanWithText(spans, line);
        assertTrue(str != null, "whole string incl. escaped quote should be one span");
        assertTrue(hasFg(str, Color.GREEN));
    }

    // ---- block comment across lines ----

    @Test
    void blockCommentOpensAndStaysOpen() {
        SyntaxHighlighter.Result r = SyntaxHighlighter.highlight("/* a", "java", false);
        assertTrue(r.stillInBlockComment(), "unterminated /* should keep block comment open");
        assertEquals("/* a", concat(r.spans()));
        assertTrue(hasFg(r.spans().get(0), Color.DARK_GRAY));
    }

    @Test
    void blockCommentClosesMidLineAndResumesCode() {
        SyntaxHighlighter.Result r = SyntaxHighlighter.highlight("b */ int c", "java", true);
        assertFalse(r.stillInBlockComment(), "block comment closed by */");
        assertEquals("b */ int c", concat(r.spans()));

        // "b */" 是注释；此后 int 应当作关键字。
        Span cmt = spanStartingWith(r.spans(), "b */");
        assertTrue(cmt != null, "leading 'b */' should be a comment span");
        assertTrue(hasFg(cmt, Color.DARK_GRAY));

        Span kw = spanWithText(r.spans(), "int");
        assertTrue(kw != null, "'int' after */ should be a keyword span");
        assertTrue(hasFg(kw, Color.MAGENTA));
    }

    @Test
    void blockCommentEntireLineWhenAlreadyInside() {
        SyntaxHighlighter.Result r = SyntaxHighlighter.highlight("still comment", "java", true);
        assertTrue(r.stillInBlockComment());
        assertEquals(1, r.spans().size());
        assertTrue(hasFg(r.spans().get(0), Color.DARK_GRAY));
    }

    // ---- language handling ----

    @Test
    void pythonUsesHashComment() {
        List<Span> spans = SyntaxHighlighter.highlight("x = 1  # note", "python", false).spans();
        assertEquals("x = 1  # note", concat(spans));
        Span cmt = spanStartingWith(spans, "#");
        assertTrue(cmt != null, "python # comment span should exist");
        assertTrue(hasFg(cmt, Color.DARK_GRAY));
    }

    @Test
    void pythonKeywordHighlighted() {
        List<Span> spans = SyntaxHighlighter.highlight("def foo():", "py", false).spans();
        Span kw = spanWithText(spans, "def");
        assertTrue(kw != null);
        assertTrue(hasFg(kw, Color.MAGENTA));
    }

    @Test
    void javaHashIsNotComment() {
        // java 不支持 # 行注释，# 应作为普通字符。
        List<Span> spans = SyntaxHighlighter.highlight("a # b", "java", false).spans();
        assertEquals("a # b", concat(spans));
        assertTrue(spanStartingWith(spans, "#") == null, "# should not start a comment in java");
    }

    @Test
    void unknownLangSupportsBothCommentStyles() {
        assertTrue(spanStartingWith(
                SyntaxHighlighter.highlight("a // b", "weird", false).spans(), "//") != null);
        assertTrue(spanStartingWith(
                SyntaxHighlighter.highlight("a # b", "weird", false).spans(), "#") != null);
    }

    @Test
    void backtickStringHighlighted() {
        List<Span> spans = SyntaxHighlighter.highlight("`tpl`", "js", false).spans();
        Span str = spanWithText(spans, "`tpl`");
        assertTrue(str != null);
        assertTrue(hasFg(str, Color.GREEN));
    }

    @Test
    void nullAndWeirdInputDoNotThrow() {
        assertEquals("", concat(SyntaxHighlighter.highlight(null, "java", false).spans()));
        // 一堆奇怪字符不应抛异常，且无字符丢失。
        String weird = "@#$%^&*()<>?/\\|~`\"'";
        assertEquals(weird, concat(SyntaxHighlighter.highlight(weird, "java", false).spans()));
    }

    @Test
    void boldModifierNotUsedForKeywords_sanityOnModifierApi() {
        // 校验 Modifier API 使用方式：keyword 只有颜色、没有 BOLD。
        List<Span> spans = SyntaxHighlighter.highlight("int", "java", false).spans();
        Span kw = spanWithText(spans, "int");
        assertTrue(kw != null);
        assertFalse(kw.style().effectiveModifiers().contains(Modifier.BOLD));
    }
}

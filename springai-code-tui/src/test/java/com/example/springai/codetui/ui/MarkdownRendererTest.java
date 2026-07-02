package com.example.springai.codetui.ui;

import dev.tamboui.style.Color;
import dev.tamboui.style.Modifier;
import dev.tamboui.text.Span;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** MarkdownRenderer 测试。断言可见文本、分段以及可读的 span 样式。 */
class MarkdownRendererTest {

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

    private static boolean hasFg(Span s, Color color) {
        return s != null && s.style().fg().map(c -> c.equals(color)).orElse(false);
    }

    private static boolean isBold(Span s) {
        return s != null && s.style().effectiveModifiers().contains(Modifier.BOLD);
    }

    private static boolean isItalic(Span s) {
        return s != null && s.style().effectiveModifiers().contains(Modifier.ITALIC);
    }

    // ---- inline styling ----

    @Test
    void boldInlineStripsMarkersAndBolds() {
        List<Span> spans = new MarkdownRenderer().renderFinalized("say **hi** now");
        assertEquals("say hi now", concat(spans));
        Span bold = spanWithText(spans, "hi");
        assertTrue(bold != null, "bold span 'hi' should exist (markers stripped)");
        assertTrue(isBold(bold));
    }

    @Test
    void inlineCodeStripsBackticksAndGreens() {
        List<Span> spans = new MarkdownRenderer().renderFinalized("use `x` here");
        assertEquals("use x here", concat(spans));
        Span code = spanWithText(spans, "x");
        assertTrue(code != null);
        assertTrue(hasFg(code, Color.LIGHT_GREEN));
    }

    @Test
    void italicInlineStripsAndItalicizes() {
        List<Span> spans = new MarkdownRenderer().renderFinalized("an *em* word");
        assertEquals("an em word", concat(spans));
        Span em = spanWithText(spans, "em");
        assertTrue(em != null);
        assertTrue(isItalic(em));
    }

    @Test
    void multipleInlineSpansPerLine() {
        List<Span> spans = new MarkdownRenderer().renderFinalized("**a** and `b`");
        assertEquals("a and b", concat(spans));
        assertTrue(isBold(spanWithText(spans, "a")));
        assertTrue(hasFg(spanWithText(spans, "b"), Color.LIGHT_GREEN));
    }

    @Test
    void unmatchedMarkersTreatedLiterally() {
        List<Span> spans = new MarkdownRenderer().renderFinalized("a ** b");
        assertEquals("a ** b", concat(spans));
    }

    // ---- block styling ----

    @Test
    void headerStrippedBoldCyan() {
        List<Span> spans = new MarkdownRenderer().renderFinalized("## Title");
        assertEquals("Title", concat(spans));
        Span h = spanWithText(spans, "Title");
        assertTrue(h != null, "header text 'Title' should exist");
        assertTrue(isBold(h));
        assertTrue(hasFg(h, Color.LIGHT_CYAN));
    }

    @Test
    void unorderedListMarkerBecomesBullet() {
        List<Span> spans = new MarkdownRenderer().renderFinalized("- item");
        assertTrue(concat(spans).startsWith("•"), "list line should start with bullet");
        assertEquals("• item", concat(spans));
    }

    @Test
    void asteriskAndPlusBulletsAlsoWork() {
        assertEquals("• a", concat(new MarkdownRenderer().renderFinalized("* a")));
        assertEquals("• b", concat(new MarkdownRenderer().renderFinalized("+ b")));
    }

    @Test
    void unorderedListKeepsIndentAndInlineStyling() {
        List<Span> spans = new MarkdownRenderer().renderFinalized("  - **bold** x");
        assertEquals("  • bold x", concat(spans));
        assertTrue(isBold(spanWithText(spans, "bold")));
    }

    @Test
    void orderedListKeepsNumber() {
        List<Span> spans = new MarkdownRenderer().renderFinalized("1. first");
        assertEquals("1. first", concat(spans));
    }

    @Test
    void blockquoteDimmed() {
        List<Span> spans = new MarkdownRenderer().renderFinalized("> quoted");
        assertEquals("> quoted", concat(spans));
        assertTrue(hasFg(spans.get(0), Color.DARK_GRAY));
    }

    @Test
    void emptyLineReturnsRawEmpty() {
        List<Span> spans = new MarkdownRenderer().renderFinalized("");
        assertEquals(1, spans.size());
        assertEquals("", spans.get(0).content());
    }

    // ---- code fence state machine ----

    @Test
    void fenceEntersCodeThenHighlightsKeywordsThenExitsToMarkdown() {
        MarkdownRenderer r = new MarkdownRenderer();

        // 进入代码块：``` 行渲染为左栏 + 语言标签。
        List<Span> fenceOpen = r.renderFinalized("```java");
        assertEquals("▎ java", concat(fenceOpen));

        // 代码块内：左栏 + class 高亮为关键字（MAGENTA）。
        List<Span> code = r.renderFinalized("class X {}");
        assertEquals("▎ class X {}", concat(code));
        Span kw = spanWithText(code, "class");
        assertTrue(kw != null, "'class' should be a keyword span inside code block");
        assertTrue(hasFg(kw, Color.MAGENTA));

        // 关闭围栏：延续左栏。
        List<Span> fenceClose = r.renderFinalized("```");
        assertEquals("▎", concat(fenceClose));

        // 回到 markdown：**b** 作为粗体渲染，而非代码高亮。
        List<Span> md = r.renderFinalized("**b**");
        assertEquals("b", concat(md));
        assertTrue(isBold(spanWithText(md, "b")));
    }

    @Test
    void blockCommentThreadedAcrossCodeLines() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.renderFinalized("```java");
        // 打开跨行块注释（第 0 span 是左栏，注释在其后）。
        List<Span> l1 = r.renderFinalized("/* start");
        assertTrue(hasFg(l1.get(1), Color.DARK_GRAY), "open block comment line should be dim/comment");
        // 下一行仍在块注释里：整行为注释，keyword 不应被单独高亮。
        List<Span> l2 = r.renderFinalized("int still comment */");
        assertEquals("▎ int still comment */", concat(l2));
        assertTrue(spanWithText(l2, "int") == null, "'int' inside block comment must not be keyword span");
        assertTrue(hasFg(l2.get(1), Color.DARK_GRAY));
    }

    @Test
    void resetClearsCodeBlockState() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.renderFinalized("```java");
        r.reset();
        // reset 后应视为 markdown，不再高亮代码关键字。
        List<Span> spans = r.renderFinalized("class X");
        assertEquals("class X", concat(spans));
        assertTrue(spanWithText(spans, "class") == null, "after reset, 'class' should not be a keyword span");
    }

    // ---- preview does NOT mutate state ----

    @Test
    void previewDoesNotToggleFenceState() {
        MarkdownRenderer r = new MarkdownRenderer();
        // 预览一条 ``` 行：只显示左栏、不进入代码块。
        List<Span> preview = r.renderPreview("```java");
        assertEquals("▎ ", concat(preview));

        // 由于预览未改变状态，下一条定稿的 markdown 行仍作 markdown 处理。
        List<Span> md = r.renderFinalized("**b**");
        assertEquals("b", concat(md));
        assertTrue(isBold(spanWithText(md, "b")));
    }

    @Test
    void previewInsideCodeBlockDoesNotAdvanceBlockComment() {
        MarkdownRenderer r = new MarkdownRenderer();
        r.renderFinalized("```java");
        // 预览一条打开块注释的行 —— 不应改变 inBlockComment。
        r.renderPreview("/* opening");
        // 定稿一条普通代码行：应仍处于「非块注释」，int 被当作关键字。
        List<Span> code = r.renderFinalized("int y");
        Span kw = spanWithText(code, "int");
        assertTrue(kw != null, "preview must not have advanced block-comment state");
        assertTrue(hasFg(kw, Color.MAGENTA));
    }

    @Test
    void previewOfMarkdownMatchesFinalizedStyling() {
        MarkdownRenderer r = new MarkdownRenderer();
        List<Span> spans = r.renderPreview("## Hi");
        assertEquals("Hi", concat(spans));
        assertTrue(hasFg(spanWithText(spans, "Hi"), Color.LIGHT_CYAN));
    }

    @Test
    void nullLineDoesNotThrow() {
        MarkdownRenderer r = new MarkdownRenderer();
        assertEquals("", concat(r.renderFinalized(null)));
        assertEquals("", concat(r.renderPreview(null)));
    }
}

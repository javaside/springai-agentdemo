package io.github.javaside.springai.codetui.ui;

import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.Span;

import java.util.ArrayList;
import java.util.List;

/**
 * 有状态的轻量 Markdown 渲染器，按顺序处理 AI 助手回复的每一行。
 *
 * <p>维护代码围栏（```）开合、围栏内语言、以及围栏内跨行块注释状态。
 * {@link #renderFinalized(String)} 处理「定稿」行并更新状态；
 * {@link #renderPreview(String)} 用当前状态给「在建预览」行上样式但不改变状态；
 * {@link #reset()} 在新回合开始时复位。</p>
 *
 * <p>空行统一返回 {@code List.of(Span.raw(""))}。对任意输入均不抛异常。</p>
 */
public final class MarkdownRenderer {

    // ---- 样式常量 ----
    private static final Style DIM = Style.create().fg(Color.DARK_GRAY);
    private static final Style HEADER = Style.create().fg(Color.LIGHT_CYAN).bold();
    private static final Style BOLD = Style.create().bold();
    private static final Style ITALIC = Style.create().italic();
    private static final Style INLINE_CODE = Style.create().fg(Color.LIGHT_GREEN);
    private static final Style QUOTE = Style.create().fg(Color.DARK_GRAY).italic();
    private static final Style GUTTER = Style.create().fg(Color.rgb(120, 150, 200)); // 代码块左边栏
    private static final String BAR = "▎ ";

    /** 给代码行加左边栏，视觉上成块。 */
    private static List<Span> withGutter(List<Span> content) {
        List<Span> out = new ArrayList<>(content.size() + 1);
        out.add(Span.styled(BAR, GUTTER));
        out.addAll(content);
        return out;
    }

    // ---- 内部状态 ----
    private boolean inCodeBlock = false;
    private String codeLang = "";
    private boolean inBlockComment = false;

    /** 新回合开始时复位所有状态。 */
    public void reset() {
        inCodeBlock = false;
        codeLang = "";
        inBlockComment = false;
    }

    /** 处理一条「定稿」行：更新内部状态并返回带样式 span。 */
    public List<Span> renderFinalized(String line) {
        if (line == null) {
            return List.of(Span.raw(""));
        }
        if (isFence(line)) {
            if (inCodeBlock) {
                inCodeBlock = false;
                codeLang = "";
                inBlockComment = false;
                return List.of(Span.styled("▎", GUTTER));                    // 代码块结束：延续左栏
            }
            inCodeBlock = true;
            codeLang = fenceLang(line);
            inBlockComment = false;
            return List.of(Span.styled(BAR, GUTTER), Span.styled(codeLang, DIM)); // 顶部标注语言
        }
        if (inCodeBlock) {
            SyntaxHighlighter.Result r = SyntaxHighlighter.highlight(line, codeLang, inBlockComment);
            inBlockComment = r.stillInBlockComment();
            return withGutter(r.spans());
        }
        return renderMarkdownLine(line);
    }

    /** 用当前状态给「在建预览」行上样式，但【不】改变状态。 */
    public List<Span> renderPreview(String line) {
        if (line == null) {
            return List.of(Span.raw(""));
        }
        if (isFence(line)) {
            return List.of(Span.styled(BAR, GUTTER));       // 预览完整 ``` 行：只显示左栏，不切换状态
        }
        if (inCodeBlock) {
            SyntaxHighlighter.Result r = SyntaxHighlighter.highlight(line, codeLang, inBlockComment);
            return withGutter(r.spans());                   // 不回写 inBlockComment
        }
        return renderMarkdownLine(line);
    }

    // ---- Markdown 文本行渲染 ----

    private List<Span> renderMarkdownLine(String line) {
        if (line.isEmpty()) {
            return List.of(Span.raw(""));
        }
        String trimmed = line.stripLeading();
        int indentLen = line.length() - trimmed.length();
        String indent = line.substring(0, indentLen);

        // 标题：# / ## / ###
        if (trimmed.startsWith("#")) {
            int h = 0;
            while (h < trimmed.length() && trimmed.charAt(h) == '#') {
                h++;
            }
            if (h <= 6 && (h >= trimmed.length() || trimmed.charAt(h) == ' ')) {
                String text = trimmed.substring(h).stripLeading();
                return List.of(Span.styled(text, HEADER));
            }
        }

        // 引用块：> ...
        if (trimmed.startsWith(">")) {
            return List.of(Span.styled(line, QUOTE));
        }

        // 无序列表：- * +
        if (trimmed.length() >= 2 && isBullet(trimmed.charAt(0)) && trimmed.charAt(1) == ' ') {
            String item = trimmed.substring(2);
            List<Span> spans = new ArrayList<>();
            spans.add(Span.raw(indent + "• "));
            spans.addAll(renderInline(item));
            return spans;
        }

        // 有序列表：1. 2. ...
        int dot = orderedMarkerLen(trimmed);
        if (dot > 0) {
            String marker = trimmed.substring(0, dot);
            String item = trimmed.substring(dot);
            List<Span> spans = new ArrayList<>();
            spans.add(Span.raw(indent + marker));
            spans.addAll(renderInline(item));
            return spans;
        }

        // 普通行：仅内联样式。
        return renderInline(line);
    }

    /** 解析一段文本中的内联样式：**bold**、`code`、*italic*，其余为默认。 */
    private List<Span> renderInline(String text) {
        List<Span> spans = new ArrayList<>();
        StringBuilder plain = new StringBuilder();
        int i = 0;
        int n = text.length();

        while (i < n) {
            char c = text.charAt(i);

            // **bold**
            if (c == '*' && i + 1 < n && text.charAt(i + 1) == '*') {
                int end = text.indexOf("**", i + 2);
                if (end > i + 1) {
                    flush(spans, plain);
                    spans.add(Span.styled(text.substring(i + 2, end), BOLD));
                    i = end + 2;
                    continue;
                }
            }

            // `code`
            if (c == '`') {
                int end = text.indexOf('`', i + 1);
                if (end > i) {
                    flush(spans, plain);
                    spans.add(Span.styled(text.substring(i + 1, end), INLINE_CODE));
                    i = end + 1;
                    continue;
                }
            }

            // *italic*（避免误吃 **，上面已优先处理）
            if (c == '*') {
                int end = text.indexOf('*', i + 1);
                if (end > i && (end + 1 >= n || text.charAt(end + 1) != '*')
                        && (i + 1 >= n || text.charAt(i + 1) != '*')) {
                    flush(spans, plain);
                    spans.add(Span.styled(text.substring(i + 1, end), ITALIC));
                    i = end + 1;
                    continue;
                }
            }

            plain.append(c);
            i++;
        }

        flush(spans, plain);
        if (spans.isEmpty()) {
            spans.add(Span.raw(text));
        }
        return spans;
    }

    private static void flush(List<Span> spans, StringBuilder plain) {
        if (plain.length() > 0) {
            spans.add(Span.raw(plain.toString()));
            plain.setLength(0);
        }
    }

    private static boolean isFence(String line) {
        return line.stripLeading().startsWith("```");
    }

    private static String fenceLang(String line) {
        String t = line.stripLeading();
        String after = t.substring(3).trim();
        // 取第一个 token 作为语言（忽略 ```java {.numberLines} 之类的额外信息）。
        int sp = 0;
        while (sp < after.length() && !Character.isWhitespace(after.charAt(sp))) {
            sp++;
        }
        return after.substring(0, sp);
    }

    private static boolean isBullet(char c) {
        return c == '-' || c == '*' || c == '+';
    }

    /** 若 trimmed 以 "N. " 形式的有序列表标记开头，返回标记长度（含尾随空格），否则 0。 */
    private static int orderedMarkerLen(String trimmed) {
        int i = 0;
        int n = trimmed.length();
        while (i < n && Character.isDigit(trimmed.charAt(i))) {
            i++;
        }
        if (i == 0) {
            return 0;
        }
        if (i < n && trimmed.charAt(i) == '.' && i + 1 < n && trimmed.charAt(i + 1) == ' ') {
            return i + 2;
        }
        return 0;
    }
}

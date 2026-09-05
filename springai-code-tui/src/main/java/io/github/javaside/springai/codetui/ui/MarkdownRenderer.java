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
    static final Style DIM = Style.create().fg(Theme.GRAY_MUTED);
    private static final Style HEADER = Style.create().fg(Color.LIGHT_CYAN).bold();
    static final Style BOLD = Style.create().bold();
    private static final Style ITALIC = Style.create().italic();
    private static final Style INLINE_CODE = Style.create().fg(Color.LIGHT_GREEN);
    // 引用块是模型回复的<b>正文内容</b>，不是界面装饰：与信息行同级（GRAY_INFO），
    // 与正文的区分交给斜体与行首标记，不靠压暗。
    private static final Style QUOTE = Style.create().fg(Theme.GRAY_INFO).italic();
    // 代码块左边栏。⚠ 不能用 Color.rgb()：目标终端（Apple Terminal，COLORTERM 为空）不支持
    // truecolor，38;2;r;g;b 被静默忽略、左边栏退回默认前景色。110 = #87afd7，是 256 色区里
    // 最接近原 rgb(120,150,200) 的一档。
    private static final Style GUTTER = Style.create().fg(Color.indexed(110));
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

    // ---- 表格状态机 ----
    /**
     * 缓冲上限（规范 §3.5）：行数 > 200 或<b>原文</b>字符数 > 64 K 即转降级。
     * 判定在每次入缓冲之后做。目的有两个：内存有界，以及「憋」的时长有界
     * （几千行的表格不该让界面停半秒以上）。
     */
    private static final int MAX_BUFFERED_ROWS = 200;
    private static final int MAX_BUFFERED_CHARS = 64 * 1024;

    /** 降级是正式状态、不是补丁：越上限后该块剩余的 {@code |} 行原样输出直到块结束。 */
    private enum TableState { IDLE, CANDIDATE, IN_BLOCK, DEGRADED }
    private TableState tableState = TableState.IDLE;
    private final List<String> tableBuffer = new ArrayList<>();
    private int bufferedCharCount = 0;

    /** 新回合开始时复位所有状态。 */
    public void reset() {
        inCodeBlock = false;
        codeLang = "";
        inBlockComment = false;
        tableState = TableState.IDLE;
        clearBuffer();
    }

    /**
     * 缓冲里是否压着表格（<b>候选态也算</b>，否则那一行会永久消失）。
     * 降级态为 false——缓冲已空，此时第 4 条触发点不该再碰它。
     */
    public boolean hasBuffered() {
        return !tableBuffer.isEmpty();
    }

    /**
     * 喂入一条<b>定稿</b>逻辑行，返回零到多行排好的 spans。
     *
     * <p><b>返回空列表 ≠ 无输出</b>：表格块要攒够整块才能算列宽（scrollback 只能追加），
     * 所以缓冲期间返回空。调用方（{@code MdLineCursor}）必须内部循环，不能把空列表当游标耗尽。
     */
    public List<List<Span>> feed(String line, int inner) {
        if (line == null) {
            return List.of();
        }

        List<List<Span>> out = new ArrayList<>();
        String current = line;

        // 「当前行重新投喂」写成<b>循环</b>而不是真递归：被重投喂的行只会落到空闲态的两条分支
        // （要么渲染、要么成为新候选），深度恒为 1，写成循环免得以后被改出无界递归。
        while (current != null) {
            String reFeed = null;

            switch (tableState) {
                case IDLE -> {
                    // 围栏内的 `|` 不能当表格——状态机放在本类而不是外面，就是因为围栏开合只有它有
                    if (!inCodeBlock && MarkdownTable.looksLikeRow(current)) {
                        tableState = TableState.CANDIDATE;
                        buffer(current);
                        out.addAll(degradeIfOverLimit());
                    } else {
                        out.add(renderFinalized(current));
                    }
                }
                case CANDIDATE -> {
                    if (MarkdownTable.isSeparator(current)) {
                        tableState = TableState.IN_BLOCK;
                        buffer(current);
                        out.addAll(degradeIfOverLimit());
                    } else {
                        // 吐候选行 → 回空闲 → 把当前行重新投喂一遍（少了这步，`|` 开头的正文
                        // 会把紧跟其后的真表格吃掉，整张表拆成原样输出）
                        String candidate = tableBuffer.get(0);
                        clearBuffer();
                        tableState = TableState.IDLE;
                        out.add(renderFinalized(candidate));
                        reFeed = current;
                    }
                }
                case IN_BLOCK -> {
                    if (MarkdownTable.looksLikeRow(current)) {
                        buffer(current);
                        out.addAll(degradeIfOverLimit());
                    } else {
                        out.addAll(renderBufferedBlock(inner));   // 非表格行（含空行）= 块结束
                        tableState = TableState.IDLE;
                        reFeed = current;
                    }
                }
                case DEGRADED -> {
                    if (MarkdownTable.looksLikeRow(current)) {
                        out.add(renderFinalized(current));
                    } else {
                        tableState = TableState.IDLE;
                        reFeed = current;
                    }
                }
            }

            current = reFeed;
        }

        return out;
    }

    /**
     * 把缓冲排出来；空缓冲返回空列表（幂等）。
     *
     * <p>按状态分：候选 → 该行按普通行输出（一句「{@code |} 表示管道」不能被印成加粗表头 +
     * 通栏 {@code ─}）；块内 → 对齐排出；降级 → 回空闲、返回空。
     */
    public List<List<Span>> flush(int inner) {
        switch (tableState) {
            case CANDIDATE -> {
                List<List<Span>> out = new ArrayList<>(tableBuffer.size());
                for (String line : tableBuffer) {
                    out.add(renderFinalized(line));
                }
                clearBuffer();
                tableState = TableState.IDLE;
                return out;
            }
            case IN_BLOCK -> {
                List<List<Span>> out = renderBufferedBlock(inner);
                tableState = TableState.IDLE;
                return out;
            }
            case DEGRADED -> {
                // 缓冲已空，但状态必须复位：降级态活过回合边界会让下一回合的第一张表
                // 在遇到第一个非 `|` 行之前全部原样输出
                tableState = TableState.IDLE;
                return List.of();
            }
            default -> {
                return List.of();
            }
        }
    }

    /** 入缓冲：行数与<b>原文</b>字符数一起记账（不含样式，{@code length()} 近似即可）。 */
    private void buffer(String line) {
        tableBuffer.add(line);
        bufferedCharCount += line.length();
    }

    private void clearBuffer() {
        tableBuffer.clear();
        bufferedCharCount = 0;
    }

    /**
     * 每次入缓冲之后判上限：越限则把已攒行<b>原样</b>吐出、转降级态。
     *
     * <p>「原样」= 走 {@link #renderFinalized}，只是不重排列——降级行照旧要有
     * {@code **粗**} / {@code `代码`} 的内联样式，否则就是把今天已有的行为改坏了。
     */
    private List<List<Span>> degradeIfOverLimit() {
        if (tableBuffer.size() <= MAX_BUFFERED_ROWS && bufferedCharCount <= MAX_BUFFERED_CHARS) {
            return List.of();
        }
        List<List<Span>> out = new ArrayList<>(tableBuffer.size());
        for (String buffered : tableBuffer) {
            out.add(renderFinalized(buffered));
        }
        clearBuffer();
        tableState = TableState.DEGRADED;
        return out;
    }

    /**
     * 对齐排出整块并清缓冲。{@code render} 万一抛了就把该块原样输出——自兜一层的理由见
     * 规范 §3.2：调用方 {@code MdLineCursor.next()} 的 catch 返回 null，而 null 在队列语义里
     * 是「游标耗尽」，一次异常会丢掉整块 + 同游标里剩余的逻辑行。
     */
    private List<List<Span>> renderBufferedBlock(int inner) {
        List<String> block = List.copyOf(tableBuffer);
        clearBuffer();
        try {
            return MarkdownTable.render(block, inner);
        } catch (RuntimeException e) {
            List<List<Span>> out = new ArrayList<>(block.size());
            for (String raw : block) {
                out.add(renderFinalized(raw));
            }
            return out;
        }
    }

    /** 处理一条「定稿」行：更新内部状态并返回带样式 span。 */
    List<Span> renderFinalized(String line) {
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

    /**
     * 解析一段文本中的内联样式：**bold**、`code`、*italic*，其余为默认。
     *
     * <p>{@code null} 视为空行——{@link MarkdownTable} 的「对任意输入不抛」契约要靠这里兜住
     * （退回原样时块里可能有 null 行，抛出去会让游标丢掉整块 + 剩余逻辑行）。
     */
    static List<Span> renderInline(String text) {
        if (text == null) {
            return List.of(Span.raw(""));
        }
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

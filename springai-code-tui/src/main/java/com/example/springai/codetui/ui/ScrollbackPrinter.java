package com.example.springai.codetui.ui;

import com.example.springai.codetui.ui.ConversationState.OutputLine;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.CharWidth;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.IntSupplier;

import static com.example.springai.codetui.ui.Theme.*;

/**
 * scrollback 打印器：把一条 agent/系统输出（欢迎横幅 / 用户回显 / 助手 markdown / 工具 diff）
 * 渲染成带样式的 {@link Text} 并 {@code println} 进 scrollback。从 {@link CodeTuiView} 抽出，使视图只管
 * 布局 + 输入 + 状态行，且让 markdown/diff 渲染可脱离 {@code InlineApp} 单测。
 *
 * <p><b>输出接缝 {@link Sink}</b>：printer 不认识 tamboui 的 {@code InlineToolkitRunner}，只依赖一个两方法的
 * {@code Sink}。既让渲染逻辑可脱离 {@code InlineApp} 单测（测试传记录型 Sink），又解决时序：视图用匿名 Sink
 * 把 {@code runner()} 惰性桥接进来——构造时不解引用，所有 println 都在 {@code onStart} 之后才触发。除
 * {@link #preview} 只用 {@code md} 外，无 println 早于 runner 就绪。故无需 null 守卫。
 *
 * <p><b>{@code md} 归属</b>：markdown 渲染是单一职责，finalized（scrollback 行）与 preview（未完成残行的
 * 实时预览）同源、共享代码围栏状态，故整体归本类；视图 render() 的预览改走 {@link #preview}。
 */
final class ScrollbackPrinter {

    /** 输出接缝：真实=终端 scrollback（视图桥接 runner().println），测试=内存列表。 */
    interface Sink {
        void println(Text line);
        void println(String line);
    }

    private static final String INDENT = "  ";   // 对话内容缩进；工具/计划行自带前缀
    private static final int GUTTER = 4;         // 行号列宽（右对齐到 4 位，够 9999 行）

    private final Sink sink;                                        // 输出接缝（视图惰性桥接 runner）
    private final Path root;                                        // 工作区根（欢迎横幅 cwd + diff 构造）
    private final IntSupplier terminalWidth;                        // 终端列宽
    private final BiFunction<String, Integer, List<String>> wrap;  // 软折行（与视图共用同一实现，注入）
    private final MarkdownRenderer md = new MarkdownRenderer();     // AI 正文 markdown + 代码语法高亮
    private final DiffRenderer diff;                                // edit/write → 带真实行号的 diff 行

    ScrollbackPrinter(Sink sink, Path root,
                      IntSupplier terminalWidth, BiFunction<String, Integer, List<String>> wrap) {
        this.sink = sink;
        this.root = root;
        this.terminalWidth = terminalWidth;
        this.wrap = wrap;
        this.diff = new DiffRenderer(root);
    }

    // ── 公开 API ─────────────────────────────────────────────────────────

    /** 流式当前残行的预览（带缩进）；供视图 render() 直接塞进 richText。只用 md、不碰 runner。 */
    Text preview(String tail) {
        return indented(md.renderPreview(tail));
    }

    /** 一条快捷键：键名 + 说明。欢迎横幅按显示宽度排成两列对齐。 */
    private record Shortcut(String keyName, String desc) {}

    private static final Shortcut[][] SHORTCUTS = {
            { new Shortcut("Enter", "发送"),   new Shortcut("\\+Enter", "换行") },
            { new Shortcut("Esc", "取消"),     new Shortcut("/model", "切换模型") },
            { new Shortcut("/help", "帮助"),   new Shortcut("Ctrl+C", "退出") },
    };

    /**
     * 圆角欢迎横幅（仿 Claude Code），下沉到 scrollback 顶部。标题与版本号嵌入顶部边框，
     * 正文为对齐的「标签 值」元信息 + 两列对齐的快捷键。model / version 由调用方传入（printer 不依赖 SubmitHandler）。
     */
    void welcome(String model, String version) {
        Sink r = sink;
        int w = Math.min(Math.max(terminalWidth.getAsInt() - 1, 52), 76);

        r.println(topBorder(w, version));                       // ╭─ ✻ 标题 ──… v1.0.0 ─╮
        welcomeRow(w);
        welcomeRow(w, Span.styled("  终端编码智能体 · 多模型可切换", WELCOME_BODY));
        metaRow(w, "模型", model, WELCOME_ACCENT);              // 模型名薄荷强调
        metaRow(w, "目录", abbreviateHome(String.valueOf(root)), WELCOME_BODY);
        welcomeRow(w);
        shortcutRows(w);                                        // 三行 × 两列，按显示宽度对齐
        r.println(Text.styled("╰" + "─".repeat(Math.max(0, w - 2)) + "╯", WELCOME_BORDER));
        r.println("");   // 与后续对话留白
    }

    /** 顶部边框，标题嵌左、版本号嵌右：{@code ╭─ ✻ Spring AI Code TUI ──…── v1.0.0 ─╮}。过窄放不下时回退成纯边框。 */
    private static Text topBorder(int w, String version) {
        String title = "Spring AI Code TUI";
        String ver = version == null || version.isBlank() ? "" : version;
        // 左段 = ╭─ + ✻  + 标题 + 空格；右段 = 空格 + 版本 + 空格 ─╮
        int leftW = displayWidth("╭─ ") + displayWidth("✻ ") + displayWidth(title) + 1;
        int rightW = 1 + displayWidth(ver) + displayWidth(" ─╮");
        int fill = w - leftW - rightW;
        if (fill < 1) {                                         // 极窄终端：退回不嵌标题的纯边框
            return Text.styled("╭" + "─".repeat(Math.max(0, w - 2)) + "╮", WELCOME_BORDER);
        }
        List<Span> spans = new ArrayList<>();
        spans.add(Span.styled("╭─ ", WELCOME_BORDER));
        spans.add(Span.styled("✻ ", WELCOME_STAR));
        spans.add(Span.styled(title + " ", WELCOME_TITLE));
        spans.add(Span.styled("─".repeat(fill), WELCOME_BORDER));
        spans.add(Span.styled(" ", WELCOME_BORDER));
        spans.add(Span.styled(ver, WELCOME_HINT));
        spans.add(Span.styled(" ─╮", WELCOME_BORDER));
        return Text.from(Line.from(spans));
    }

    /** 元信息行：{@code   标签   值}（标签灰、按 4 列宽对齐，值 3 空格后起）。 */
    private void metaRow(int w, String label, String value, Style valueStyle) {
        int gap = Math.max(1, 4 - displayWidth(label)) + 3;    // 标签列宽 4 + 3 空格
        welcomeRow(w, Span.styled("  " + label + " ".repeat(gap), WELCOME_HINT),
                Span.styled(value, valueStyle));
    }

    /** 三行快捷键，两列对齐：列宽按各列键名/说明的最大显示宽度算，右列起点全表统一。 */
    private void shortcutRows(int w) {
        int leftKeyW = 0, leftDescW = 0, rightKeyW = 0;
        for (Shortcut[] row : SHORTCUTS) {
            leftKeyW  = Math.max(leftKeyW,  displayWidth(row[0].keyName()));
            leftDescW = Math.max(leftDescW, displayWidth(row[0].desc()));
            rightKeyW = Math.max(rightKeyW, displayWidth(row[1].keyName()));
        }
        int col1KeyAt  = 2 + leftKeyW + 2 + leftDescW + 4;     // 左列块 + 4 空格间距
        int col1DescAt = col1KeyAt + rightKeyW + 2;
        for (Shortcut[] row : SHORTCUTS) {
            List<Span> spans = new ArrayList<>();
            int used = padTo(spans, 0, 2);                     // 前导缩进
            spans.add(key(row[0].keyName())); used += displayWidth(row[0].keyName());
            used = padTo(spans, used, 2 + leftKeyW + 2);
            spans.add(hint(row[0].desc())); used += displayWidth(row[0].desc());
            used = padTo(spans, used, col1KeyAt);
            spans.add(key(row[1].keyName())); used += displayWidth(row[1].keyName());
            used = padTo(spans, used, col1DescAt);
            spans.add(hint(row[1].desc()));
            welcomeRow(w, spans.toArray(new Span[0]));
        }
    }

    /** 追加空格 span 使已用显示宽度补到 {@code target}；返回补齐后的宽度。 */
    private static int padTo(List<Span> spans, int used, int target) {
        int pad = Math.max(0, target - used);
        if (pad > 0) spans.add(Span.raw(" ".repeat(pad)));
        return used + pad;
    }

    /** 把 $HOME 前缀缩写成 {@code ~}，缩短工作目录展示。 */
    private static String abbreviateHome(String path) {
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank() && path.startsWith(home)) {
            return "~" + path.substring(home.length());
        }
        return path;
    }

    private static Span key(String s)  { return Span.styled(s, WELCOME_KEY); }
    private static Span hint(String s) { return Span.styled(s, WELCOME_HINT); }

    /** 用户消息：灰底白字块，仿 Claude Code。按终端宽度软折行，每行右侧补白使灰底铺满整行。新回合先重置 md 围栏态。 */
    void userBlock(String text) {
        md.reset();   // 新回合：清 markdown 代码围栏状态（原在 drain 的 USER 分支）
        Sink r = sink;
        int width = terminalWidth.getAsInt();
        int inner = Math.max(1, width - displayWidth(INDENT));
        for (String logical : text.split("\n", -1)) {
            for (String seg : wrap.apply(logical, inner)) {
                int pad = Math.max(0, inner - displayWidth(seg));
                r.println(Text.from(Line.from(
                        Span.styled(INDENT, USER_BLOCK),
                        Span.styled(seg, USER_BLOCK),
                        Span.styled(" ".repeat(pad), USER_BLOCK))));
            }
        }
    }

    /** AI 正文：markdown/语法高亮 + 缩进，下沉 scrollback。 */
    void assistant(String text) {
        sink.println(indented(md.renderFinalized(text)));
    }

    /** 流式完整行：同 assistant。 */
    void streamingLine(String row) {
        sink.println(indented(md.renderFinalized(row)));
    }

    /** 工具开始：edit/write 展开成 diff 块（真实行号、语法高亮 + 增删底色）；其余工具单行摘要。 */
    void toolStart(OutputLine ol) {
        List<DiffRenderer.DiffLine> lines =
                (ol.raw() == null) ? List.of() : diff.render(ol.toolName(), ol.raw());
        if (lines.isEmpty()) {                                      // 非文件写入 / 无法解析：回退单行摘要
            sink.println(Text.styled(ol.text(), TOOL));
            return;
        }
        int width = terminalWidth.getAsInt();
        String lang = langOf(lines);        // 从 header 的路径推断语言
        boolean inBlock = false;            // 跨行块注释状态，按 body 顺序推进
        for (DiffRenderer.DiffLine dl : lines) {
            List<Span> hl = null;
            if (dl.type() != DiffRenderer.Type.HEADER && dl.type() != DiffRenderer.Type.TRUNCATED) {
                SyntaxHighlighter.Result rr = SyntaxHighlighter.highlight(dl.text(), lang, inBlock);
                inBlock = rr.stillInBlockComment();
                hl = rr.spans();
            }
            sink.println(diffLine(dl, hl, width));
        }
    }

    /** drain 的 default 分支：工具/Todo/错误等单色贴左；{@code styleFor} 返回 null（ASSISTANT）则原样。 */
    void line(OutputLine ol) {
        Style st = styleFor(ol.kind());
        if (st == null) sink.println(ol.text());
        else sink.println(Text.styled(ol.text(), st));
    }

    // ── 私有渲染细节（原视图私有方法，逐字搬运） ─────────────────────────

    /**
     * 组一行欢迎框内容：{@code │ + 内容(多 span，按显示宽度截断/右补白到内宽) + │}。
     * 各 span 自带样式（键名/模型名/说明分色）；总宽超内宽时截断末段并补 …。无参即空行。
     */
    private void welcomeRow(int w, Span... content) {
        int inner = Math.max(1, w - 2);
        List<Span> spans = new ArrayList<>();
        spans.add(Span.styled("│", WELCOME_BORDER));
        int used = 0;
        for (Span sp : content) {
            if (used >= inner) break;
            String t = sp.content();
            int cw = displayWidth(t);
            if (used + cw > inner) {                               // 末段过长（如深路径）：按剩余宽截断 + …
                t = CharWidth.substringByWidth(t, Math.max(0, inner - used - 1)) + "…";
                cw = displayWidth(t);
                spans.add(Span.styled(t, WELCOME_HINT));
                used += cw;
                break;
            }
            spans.add(sp);
            used += cw;
        }
        int pad = Math.max(0, inner - used);
        if (pad > 0) spans.add(Span.raw(" ".repeat(pad)));
        spans.add(Span.styled("│", WELCOME_BORDER));
        sink.println(Text.from(Line.from(spans)));
    }

    /** 把一行 DiffLine 渲染成整行 Text；ADD/DEL 底色铺满整行（含行号列），上下文行只高亮不上底色。 */
    private static Text diffLine(DiffRenderer.DiffLine dl, List<Span> hl, int width) {
        return switch (dl.type()) {
            case HEADER -> Text.from(Line.from(Span.raw(INDENT), Span.styled(dl.text(), DIFF_HEADER)));
            case TRUNCATED -> Text.from(Line.from(
                    Span.raw(INDENT), Span.styled(gutter(null) + "  " + dl.text(), DIFF_TRUNC)));
            case CONTEXT -> bodyLine(gutter(dl.newNo() != null ? dl.newNo() : dl.oldNo()), " ",
                    hl, DIFF_NO_CTX, null, width);
            case ADD -> bodyLine(gutter(dl.newNo()), "+", hl, DIFF_NO_ADD, ADD_BG, width);
            case DEL -> bodyLine(gutter(dl.oldNo()), "-", hl, DIFF_NO_DEL, DEL_BG, width);
        };
    }

    /**
     * 组装一行 diff 主体：{@code 行号 + 符号 + 高亮内容}。bg 非 null（ADD/DEL）时左缩进/行号/符号/内容/右侧补白
     * 全部叠加底色，形成从左到右铺满整行的色带；bg 为 null（CONTEXT）时无底色，仅语法高亮。
     */
    private static Text bodyLine(String num, String sign, List<Span> content,
                                 Style numStyle, Color bg, int width) {
        List<Span> spans = new ArrayList<>();
        spans.add(bg == null ? Span.raw(INDENT) : Span.styled(INDENT, Style.create().bg(bg)));
        spans.add(Span.styled(num, numStyle));
        spans.add(Span.styled(" " + sign + " ", numStyle));
        int used = displayWidth(INDENT) + displayWidth(num) + 3;   // 3 = " " + sign + " "
        for (Span s : content) {
            spans.add(bg == null ? s : s.bg(bg));                  // 高亮前景上叠加底色
            used += s.width();
        }
        if (bg != null) {                                          // 右侧补白到终端宽度，让色带铺满整行
            int pad = Math.max(0, width - used);
            if (pad > 0) spans.add(Span.styled(" ".repeat(pad), Style.create().bg(bg)));
        }
        return Text.from(Line.from(spans));
    }

    /** 从 diff 的 header（{@code Update(path)}）提取路径后缀，映射成 SyntaxHighlighter 的语言标识。 */
    private static String langOf(List<DiffRenderer.DiffLine> lines) {
        if (lines.isEmpty()) return "";
        String h = lines.get(0).text();               // 形如 Update(src/.../App.java)
        int lp = h.indexOf('(');
        int dot = h.lastIndexOf('.');
        if (lp < 0 || dot <= lp) return "";
        return h.substring(dot + 1).replace(")", "").trim();
    }

    /** 行号右对齐到 {@link #GUTTER} 列；null（新增/删除的对侧）用空白占位保持列对齐。 */
    private static String gutter(Integer no) {
        String s = (no == null) ? "" : String.valueOf(no);
        return " ".repeat(Math.max(0, GUTTER - s.length())) + s;
    }

    /** 给渲染出的 span 列表加左缩进，组成一行 Text。 */
    private static Text indented(List<Span> spans) {
        List<Span> all = new ArrayList<>(spans.size() + 1);
        all.add(Span.raw(INDENT));
        all.addAll(spans);
        return Text.from(Line.from(all));
    }

    /** 内容的显示宽度（中文占 2 列），用于底色补齐计算。printer 私有，非跨类共享。 */
    private static int displayWidth(String s) {
        return CharWidth.of(s);
    }
}

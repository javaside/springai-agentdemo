package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.ui.ConversationState.OutputLine;
import io.github.javaside.springai.codetui.ui.output.OutputCursor;
import io.github.javaside.springai.codetui.ui.output.PhysicalOutputQueue.PhysicalLine;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.CharWidth;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

import static io.github.javaside.springai.codetui.ui.Theme.*;

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
 *
 * <p><b>cursor 工厂（严格分批，设计 §9.1）</b>：本类除「一次打完」的旧方法（欢迎横幅等小输出仍走它们）
 * 外，另提供把逻辑输出展开成 {@link OutputCursor} 的工厂（{@link #assistantCursor(String)}、
 * {@link #userBlockCursor(String)}、{@link #toolStartCursor(OutputLine)}、{@link #lineCursor(OutputLine)}、
 * {@link #streamingLinesCursor(List)}）。工厂<b>保留既有渲染状态与样式</b>——markdown 代码围栏/围栏内
 * 语言/跨行块注释状态存于本类 {@link #md}（游标推进它，跨批次不回退），diff 的 header/hunk/行样式与
 * 真实行号随 {@link DiffRenderer} 的结果行推进。cursor 每次 {@code next()} 只物化<b>一个物理段</b>
 * （fix round I-1：段级推进，见 {@link SegmentedWrap}；60k 无换行长行也不再整行物化），并把该段所属
 * 逻辑行的<b>原文</b>挂在 {@code PhysicalLine.raw} 上供留底（fix round I-2）——消费方逐行预算提交，
 * 单个大输出不再能独占 UI 线程。
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
    private final MarkdownRenderer md = new MarkdownRenderer();     // AI 正文 markdown + 代码语法高亮
    private final DiffRenderer diff;                                // edit/write → 带真实行号的 diff 行

    ScrollbackPrinter(Sink sink, Path root, IntSupplier terminalWidth) {
        this.sink = sink;
        this.root = root;
        this.terminalWidth = terminalWidth;
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

    /**
     * 用户消息：灰底白字块，仿 Claude Code。按终端宽度软折行，每行右侧补白使灰底铺满整行。新回合先重置 md 围栏态。
     *
     * <p>严格分批版：见 {@link #userBlockCursor(String)}（本方法 = 一次性跑完该游标，小输出用）。
     */
    void userBlock(String text) {
        run(userBlockCursor(text));
    }

    /** AI 正文：markdown/语法高亮 + 缩进 + 按终端宽折行，下沉 scrollback。严格分批版见 {@link #assistantCursor(String)}。 */
    void assistant(String text) {
        printWrapped(md.renderFinalized(text));
    }

    /** 流式完整行：同 assistant。严格分批版见 {@link #streamingLinesCursor(List)}。 */
    void streamingLine(String row) {
        printWrapped(md.renderFinalized(row));
    }

    /**
     * 渲染出的一行 spans 按「终端宽 − 缩进」折行、逐段带缩进下沉（<b>wrap-then-indent</b>：
     * 先按内宽折好，再给每一段——含续段——前置缩进，即悬挂缩进）。{@link MdLineCursor} 与本方法
     * 逐字同源（同一折行源、同一缩进时机），等价性由 {@code ScrollbackPrinterTest} 钉住。
     *
     * <p>必须在 println 前折好：{@code InlineDisplay.println(Text)} 定宽渲染、超宽<b>截断</b>
     * ——不折行，超过终端宽度的正文右边直接消失（用户实报「回复文字没显示全」）；
     * 又不能任由终端自己折（「一个 println = 一个物理行」纪律，折了推歪显示区记账）。
     */
    private void printWrapped(List<Span> spans) {
        for (Text piece : TextWrap.wrap(Text.from(Line.from(spans)), innerWidth())) {
            sink.println(indented(piece.lines().get(0).spans()));
        }
    }

    /**
     * 工具开始：edit/write 展开成 diff 块（真实行号、语法高亮 + 增删底色）；其余工具单行摘要。
     * 严格分批版见 {@link #toolStartCursor(OutputLine)}（本方法 = 一次性跑完该游标）。
     */
    void toolStart(OutputLine ol) {
        run(toolStartCursor(ol));
    }

    /** drain 的 default 分支：工具/Todo/错误等单色贴左；{@code styleFor} 返回 null（ASSISTANT）则原样。 */
    void line(OutputLine ol) {
        run(lineCursor(ol));
    }

    // ── cursor 工厂（严格分批；渲染语义与上面的一次性方法逐字同源——
    //     assistant/流式的「wrap-then-indent + 悬挂缩进」由 ScrollbackPrinterTest
    //     的逐段等价断言钉住，其余路径与对应一次性方法共享同一套私有渲染方法） ────────

    /**
     * 用户消息 cursor：灰底白字块。每条逻辑行按内宽<b>逐段</b>折行、右侧补白铺满灰底。
     * 围栏复位（{@code md.reset()}）在<b>工厂调用时</b>发生（drain 轮到这条 USER 输出时），
     * 与旧实现「drain 的 USER 分支」时机一致。留底原文 = 折行前的整行用户块（含缩进/底色/补白，
     * Text；重放按新宽度重新折行时灰底语义不丢）。
     */
    OutputCursor userBlockCursor(String text) {
        md.reset();   // 新回合：清 markdown 代码围栏状态（原在 drain 的 USER 分支）
        return new PlainLineCursor(text.split("\n", -1)) {
            @Override SegmentedWrap.Plain newSegments(String logical) {
                return SegmentedWrap.plain(logical, innerWidth());
            }

            @Override Object rawOf(String logical) {
                // 留底原文：折行前的整行用户块（缩进 + 内容 + 铺满内宽的灰底），与旧实现打印的形态一致
                int inner = innerWidth();
                int pad = Math.max(0, inner - displayWidth(logical));
                return Text.from(Line.from(
                        Span.styled(INDENT, USER_BLOCK),
                        Span.styled(logical, USER_BLOCK),
                        Span.styled(" ".repeat(pad), USER_BLOCK)));
            }

            @Override PhysicalLine toPhysical(String seg, Object raw) {
                int inner = innerWidth();
                int pad = Math.max(0, inner - displayWidth(seg));
                return PhysicalLine.of(null, Text.from(Line.from(
                        Span.styled(INDENT, USER_BLOCK),
                        Span.styled(seg, USER_BLOCK),
                        Span.styled(" ".repeat(pad), USER_BLOCK))), raw);
            }
        };
    }

    /**
     * AI 正文 cursor：一条逻辑行 → {@code md.renderFinalized}（推进围栏/高亮状态）→ 按内宽折行、
     * 每段前置缩进（悬挂缩进，wrap-then-indent，见 {@link MdLineCursor}）。<b>状态跨批次保持</b>：
     * {@link #md} 是 printer 级单例，游标每次 {@code next()} 渲染下一条逻辑行时从上一条结束时的状态
     * 继续——把渲染拆进游标而不复制状态，正是为了保证这一点。留底原文 = 折行前的整行渲染结果
     * （Text，含样式与缩进；重放按新宽度重新折行）。
     */
    OutputCursor assistantCursor(String text) {
        return new MdLineCursor(text.split("\n", -1));
    }

    /** 流式完整行 cursor：一批逻辑行逐条走 assistant 语义（同一条 md 围栏/高亮状态链）。 */
    OutputCursor streamingLinesCursor(List<String> rows) {
        return new MdLineCursor(rows.toArray(new String[0]));
    }

    /**
     * 工具开始 cursor。diff 展开（读文件 + LCS + 真实行号定位）在<b>工厂调用时</b>做一次——
     * header/上下文/行号依赖整体 diff，属于「O(一个工具入参)」的一次性工作，不随批次重复
     * （也不在入队时做：队列里堆着的项不占渲染开销；⚠ 这笔成本在 drain 时间预算之外，
     * 见 {@code PhysicalOutputQueue} 类注释的如实声明）。之后逐行（含逐行语法高亮 + 跨行块注释
     * 推进）惰性产出；每条 diff 行按终端宽<b>逐段</b>折行（ADD/DEL 底色带铺满每段）；非文件写入 /
     * 无法解析 → 单行摘要回退（同样逐段折行），与旧实现一致。留底原文 = 整条 diff 行的渲染
     * 结果（Text，含行号列与底色；重放按新宽度重新折行）。
     */
    OutputCursor toolStartCursor(OutputLine ol) {
        return new OutputCursor() {
            private final List<DiffRenderer.DiffLine> lines =
                    (ol.raw() == null) ? List.of() : diff.render(ol.toolName(), ol.raw());
            private final boolean fallback = lines.isEmpty();   // 非文件写入 / 无法解析：单行摘要
            private final String lang = langOf(lines);          // header 路径推断语言（不变）
            private SegmentedWrap.Plain fallbackSegs;           // 摘要行的逐段推进（首段时才建）
            private Text fallbackRaw;                            // 摘要行的留底原文（带样式整行）
            private SegmentedWrap.Styled segs;                  // 当前 diff 行的逐段推进（null=无在途行）
            private Text raw;                                   // 当前 diff 行的整行渲染（留底原文）
            private int at;
            private boolean inBlock;                             // diff body 跨行块注释状态：留在游标里跨批次保持

            @Override public boolean hasNext() {
                if (fallback) {
                    return fallbackSegs == null || fallbackSegs.hasNextSegment();
                }
                return (segs != null && segs.hasNextSegment()) || at < lines.size();
            }

            @Override public PhysicalLine next() {
                try {
                    if (fallback) {                              // 摘要行同样按终端宽逐段折行（同 lineCursor）
                        if (fallbackSegs == null) {
                            fallbackSegs = SegmentedWrap.plain(ol.text(), terminalWidth.getAsInt());
                            fallbackRaw = Text.styled(ol.text(), TOOL);   // 留底原文：带样式整行
                        }
                        String seg = fallbackSegs.nextSegment();
                        return seg == null ? null
                                : PhysicalLine.of(null, Text.styled(seg, TOOL), fallbackRaw);
                    }
                    // 先吐完上一条 diff 行的折行段，再渲染下一条——顺序才不乱、段不丢
                    if (segs != null && segs.hasNextSegment()) {
                        return PhysicalLine.of(null, Text.from(Line.from(segs.nextSegment())), raw);
                    }
                    if (at >= lines.size()) return null;
                    DiffRenderer.DiffLine dl = lines.get(at++);
                    List<Span> hl = null;
                    if (dl.type() != DiffRenderer.Type.HEADER && dl.type() != DiffRenderer.Type.TRUNCATED) {
                        SyntaxHighlighter.Result rr = SyntaxHighlighter.highlight(dl.text(), lang, inBlock);
                        inBlock = rr.stillInBlockComment();
                        hl = rr.spans();
                    }
                    // diff 主体行按终端宽折行（ADD/DEL 底色带铺满每段）。逐段推进（fix round I-1）：
                    // 一条 400 列的 ADD 行在 80 列终端折 ~6 段——一次建全 6 段虽不及 60k 正文行夸张，
                    // 但段级推进已具备，diff 行同样不再整行物化；留底原文是整行渲染（raw）。
                    int width = terminalWidth.getAsInt();
                    raw = diffLine(dl, hl, width);
                    segs = SegmentedWrap.styled(raw.lines().get(0).spans(), width);
                    return PhysicalLine.of(null, Text.from(Line.from(segs.nextSegment())), raw);
                } catch (RuntimeException e) {
                    return null;   // 渲染降级：单行失败不打断批次（diffLine/高亮对任何输入不抛，此处为契约兜底）
                }
            }
        };
    }

    /**
     * 单色行 cursor（TOOL_OK/TODO/ERROR/INFO/SUBAGENT_* 及 styleFor==null 的 ASSISTANT）。
     * 超宽行按终端宽<b>逐段</b>折行（旧实现在视图出口兜底折；折行移进游标后这里接手，每段沿用
     * 同一样式），守住「一个 println = 一个物理行」——定宽截断会把右边文字直接吃掉。
     * 留底原文 = 带样式的整条逻辑行（Text；60k 无换行长 INFO 折出的 ~751 段只占一条留底配额，
     * resize 重放按新宽度重新折行、样式不丢——与 Task 5 之前「留底存整行 Text」的行为一致）。
     */
    OutputCursor lineCursor(OutputLine ol) {
        return new PlainLineCursor(new String[]{ol.text()}) {
            private Style st;   // newSegments 时取一次（styleFor 是纯 switch，取两次也无害）

            @Override SegmentedWrap.Plain newSegments(String logical) {
                st = styleFor(ol.kind());
                return SegmentedWrap.plain(logical, terminalWidth.getAsInt());
            }

            @Override Object rawOf(String logical) {
                // 留底原文：带样式的整条逻辑行；styleFor==null（ASSISTANT）与打出去的形态一致，纯文本
                Style s = styleFor(ol.kind());
                return s == null ? (Object) logical : (Object) Text.styled(logical, s);
            }

            @Override PhysicalLine toPhysical(String seg, Object raw) {
                return st == null
                        ? PhysicalLine.of(seg, null, raw)
                        : PhysicalLine.of(null, Text.styled(seg, st), raw);
            }
        };
    }

    /**
     * 逻辑行（纯字符串）→ 物理段 的通用游标骨架（fix round I-1 重写为<b>段级推进</b>）：每次
     * {@code next()} 只产出<b>一个</b>物理段——从当前逻辑行的 {@link SegmentedWrap.Plain} 取下一段
     * （O(一段)），取完换下一条逻辑行。staging 有界：任一时刻物化的物理行 = 1（正在产出的段），
     * 与逻辑行长度、整条输出的总行数都无关——60k 无换行长行第一次 next() 也只折 80 列的一段。
     *
     * <p>三条抽象：{@link #newSegments} 建当前逻辑行的段推进器（宽度由实现定）、{@link #rawOf}
     * 给出留底原文（同一条逻辑行的所有段共享同一引用，留底方据此记录原文而非折后段）、
     * {@link #toPhysical} 把一段包装成物理行（加样式/缩进/补白）。
     */
    private abstract static class PlainLineCursor implements OutputCursor {
        private final String[] logicals;
        private int at;                     // 下一条待渲染的逻辑行
        private SegmentedWrap.Plain segs;   // 当前逻辑行的段推进器（null=尚未开始）
        private Object raw;                 // 当前逻辑行的留底原文

        PlainLineCursor(String[] logicals) { this.logicals = logicals; }

        /** 建当前逻辑行的段推进器（宽度由实现定：终端宽 / 终端宽−缩进）。 */
        abstract SegmentedWrap.Plain newSegments(String logical);

        /** 当前逻辑行的留底原文（其所有段共享同一引用）。 */
        abstract Object rawOf(String logical);

        /** 把一段包装成物理行（加样式/缩进/补白）。 */
        abstract PhysicalLine toPhysical(String seg, Object raw);

        @Override public final boolean hasNext() {
            return (segs != null && segs.hasNextSegment()) || at < logicals.length;
        }

        @Override public final PhysicalLine next() {
            try {
                if (segs == null || !segs.hasNextSegment()) {
                    if (at >= logicals.length) return null;
                    String logical = logicals[at++];
                    segs = newSegments(logical);
                    raw = rawOf(logical);
                }
                String seg = segs.nextSegment();
                return seg == null ? null : toPhysical(seg, raw);
            } catch (RuntimeException e) {
                return null;   // 渲染降级：一条逻辑行失败不打断批次（渲染器自身「不抛」契约的兜底）
            }
        }
    }

    /**
     * markdown 正文行游标（assistant / 流式完整行共享）：一条逻辑行 = {@code md.renderFinalized}
     * （推进围栏/高亮状态）→ 折行 → 悬挂缩进。留底原文 = 折行前的整行渲染（含缩进；Text）。
     *
     * <p><b>折行源是未缩进的渲染结果</b>（fix round 2 / 复审 N-1）：按内宽（终端宽−缩进）对
     * {@code renderFinalized} 的 spans 折行，<b>每一段</b>（含续段）产出时前置
     * {@link Span#raw(String) Span.raw(INDENT)}——与一次性方法 {@link #printWrapped} 的
     * wrap-then-indent 语义逐字一致（同一折行源、同一缩进时机；由 {@code ScrollbackPrinterTest}
     * 钉住：同一输入下两条路径产出的物理行序列逐一相等）。此前曾把「已缩进的整行」当折行源，
     * 两个后果：①缩进吃掉首段 2 列内容预算，续段从第 0 列起——中文正文在 80 列终端几乎必然
     * 折行，续段顶格是排版回归；②段宽上限从终端宽（80）错位成 inner（78），折行预算里混入了
     * 排版缩进。折行算法本身（宽字符不切半、样式跨拆分点保留、逐段推进）与
     * {@link SegmentedWrap.Styled} / {@link TextWrap} 一致（由 {@code SegmentedWrapTest} 钉住）。
     * 留底 {@code raw} 仍是 {@code indented(...)} 整行（折行前原文，宽度信息无损——
     * resize 重放按新宽度重折的语义不受本修复影响）。
     */
    private final class MdLineCursor implements OutputCursor {
        private final String[] logicals;
        private int at;
        private SegmentedWrap.Styled segs;   // 当前逻辑行的段推进器（折行源 = 未缩进渲染）
        private Text raw;                    // 当前逻辑行的整行渲染（含缩进；留底原文）

        MdLineCursor(String[] logicals) { this.logicals = logicals; }

        @Override public boolean hasNext() {
            return (segs != null && segs.hasNextSegment()) || at < logicals.length;
        }

        @Override public PhysicalLine next() {
            try {
                if (segs == null || !segs.hasNextSegment()) {
                    if (at >= logicals.length) return null;
                    List<Span> rendered = md.renderFinalized(logicals[at++]);   // 状态推进在渲染时发生
                    raw = indented(rendered);                                  // 留底原文（折行前整行，含缩进）
                    segs = SegmentedWrap.styled(rendered, innerWidth());        // 折行源 = 未缩进渲染
                }
                List<Span> piece = segs.nextSegment();
                if (piece == null) return null;
                // wrap-then-indent：每一段（含续段）前置缩进——续段对齐首段内容，即悬挂缩进
                List<Span> indentedSeg = new ArrayList<>(piece.size() + 1);
                indentedSeg.add(Span.raw(INDENT));
                indentedSeg.addAll(piece);
                return PhysicalLine.of(null, Text.from(Line.from(indentedSeg)), raw);
            } catch (RuntimeException e) {
                return null;   // 渲染降级：一条逻辑行失败不打断批次（md 渲染器自身「不抛」契约的兜底）
            }
        }
    }

    /** 用户块/正文行的内宽：终端宽 − 缩进（同 {@link #printWrapped}）。 */
    private int innerWidth() {
        return Math.max(1, terminalWidth.getAsInt() - displayWidth(INDENT));
    }

    /** 一次性跑完一个游标（旧「打完整条」语义；欢迎横幅/计划正文等小输出与既有测试路径用）。 */
    private void run(OutputCursor c) {
        while (c.hasNext()) {
            PhysicalLine l = c.next();
            if (l == null) break;
            if (l.styled() != null) sink.println(l.styled());
            else sink.println(l.plain() == null ? "" : l.plain());
        }
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

package io.github.javaside.springai.tamboui.demo;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.buffer.Cell;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.tui.EventHandler;
import dev.tamboui.tui.Renderer;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.Event;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.paragraph.Paragraph;
import io.github.javaside.springai.tamboui.TambouiDemo;

/**
 * 第 2 课 网格实验室——Buffer / Cell / Frame —— 基于 TamboUI 0.4.0 实测 API。
 *
 * <p>本课示例的任务：<b>三个核心概念各有自己的演示位</b>——</p>
 * <ul>
 *   <li><b>Cell（一格）</b>：◆ 与右上角 A/B 角标都是 {@code grid.set(x, y, cell)} 手写的一格——
 *       按空格后代码只翻转一个布尔位，但这两个 Cell 的内容随之换掉；</li>
 *   <li><b>Buffer（整屏数据）</b>：下半屏的「观察窗」把当前帧左上 30 格 × 5 行<b>实时</b>
 *       以网格形式再显示一遍（画中画）——上面动，下面同步动，因为它们是同一张表格；</li>
 *   <li><b>Frame（渲染入口）</b>：上面的一切都经它完成——{@code area()} 给画布、
 *       {@code renderWidget(组件, 区域)} 画说明文字、{@code buffer()} 拿网格本体读写。</li>
 * </ul>
 *
 * <p>退出后打印全帧网格与 diff 视图（■=框架实际发给终端的格子）——立即模式与差分的证据。</p>
 *
 * <p>示例纪律：只用本课及第 1 课教过的 API（纯字符串 + {@code Paragraph.from}；
 * Cell 的样式参数给 {@code Style.create()} 空样式即可，样式链是第 3 课的内容）。</p>
 */
public final class HelloTuiDemo implements TambouiDemo {

    /** 状态只有一个位：显示帧 A 还是帧 B（第 6 课会把「状态驱动 UI」展开成完整范式）。 */
    private boolean showFrameB;

    /** 退出后打印教具用的两帧快照（渲染线程写、run() 返回后主线程读，先后有序）。 */
    private Buffer lastFrameA;
    private Buffer lastFrameB;

    /** 观察窗参数：显示当前帧左上 30 格 × 5 行。 */
    static final int OBS_WIDTH = 30;
    static final int OBS_HEIGHT = 5;

    @Override
    public int order() {
        return 2;
    }

    @Override
    public String title() {
        return "网格实验室——Buffer / Cell / Frame";
    }

    @Override
    public void run() throws Exception {
        try (TuiRunner runner = TuiRunner.create()) {
            Renderer renderer = this::paint;
            EventHandler handler = (Event event, TuiRunner r) -> {
                if (event instanceof KeyEvent key && key.isQuit()) {
                    r.quit();
                    return false;
                }
                if (event instanceof KeyEvent key && key.isChar(' ')) {
                    showFrameB = !showFrameB;
                    return true;   // 状态变了 → 请求重绘（下一帧自然反映新状态）
                }
                return false;
            };
            runner.run(handler, renderer);
        }

        printTakeaway();
    }

    /** 仅供测试构造指定帧（离屏渲染用例）。 */
    void setFrameBForTest(boolean frameB) {
        this.showFrameB = frameB;
    }

    /**
     * 画一帧（示例只出现已教 API）。
     * 包内可见供离屏测试。
     */
    void paint(Frame frame) {
        int width = frame.area().width();
        int height = frame.area().height();
        int half = height / 2;

        // ── Frame 工具一/二：area() 画布 + renderWidget 画组件 ──
        // 主区（上半）：说明文字用纯字符串渲染（样式是第 3 课的内容）
        String which = showFrameB ? "帧 B" : "帧 A";
        String main = "第 2 课 · 网格实验室——Buffer / Cell / Frame\n"
                + "\n"
                + "现在是 " + which + "。下面观察窗里的 ◆ 和右上角的 " + (showFrameB ? "B" : "A")
                + " 都是代码用 grid.set(...) 手写的一格（Cell）。\n"
                + "按【空格】切换帧——只翻转一个布尔变量，整帧重画，观察窗同步变化。\n"
                + "按 q 退出：退出后打印全帧网格与 diff（■=框架实际发给终端的格子）。";
        frame.renderWidget(Paragraph.from(main), new Rect(1, 1, width - 2, half - 2));

        // ── Frame 工具三：buffer() 拿网格本体，直接读写「一格」（Cell 的直接演示）──
        Buffer grid = frame.buffer();
        // ◆：帧 A 在左、帧 B 在右（位置刻意落在观察窗的取景范围内）
        int dotX = showFrameB ? 22 : 6;
        grid.set(dotX, 3, new Cell("◆", Style.create()));   // 空样式：样式链是第 3 课内容
        // 右上角手写角标 A/B——同样是一格
        grid.set(OBS_WIDTH - 4, 0, new Cell(showFrameB ? "B" : "A", Style.create()));

        // ── Buffer 的直接演示：观察窗（画中画）──
        // 读当前帧左上 OBS_WIDTH×OBS_HEIGHT 的格子，实时以网格文本回放到下半屏——
        // 上下两块动的是同一张表，这就是「Buffer = 整屏内容的数据版」。
        int obsY = half;
        frame.renderWidget(Paragraph.from("观察窗：同一帧的 Buffer，左上 " + OBS_WIDTH + " 格 × "
                + OBS_HEIGHT + " 行（实时同步）"), new Rect(1, obsY, width - 2, 2));
        for (int y = 0; y < OBS_HEIGHT && obsY + 2 + y < height; y++) {
            StringBuilder sb = new StringBuilder("y=" + y + " |");
            for (int x = 0; x < OBS_WIDTH; x++) {
                Cell cell = grid.get(x, y);
                // 续格（宽字符占的第二格）用 · 呈现，让「一格一字符」肉眼可数
                sb.append(cell.isContinuation() ? "·" : (cell.symbol() == null ? " " : cell.symbol()));
            }
            sb.append('|');
            grid.setString(1, obsY + 2 + y, sb.toString(), Style.create());
        }

        // 留两帧快照：退出后的教学打印用
        if (showFrameB) {
            lastFrameB = grid.copy();
        } else {
            lastFrameA = grid.copy();
        }
    }

    /** 退出全屏后的教学打印：全帧网格 + diff 视图。 */
    private void printTakeaway() {
        System.out.println("\n—— 运行时看到的两屏 vs 下面三张图，对应关系 ——");
        System.out.println("· 运行时的整屏画面 = Buffer 这张字符网格照着画到终端的结果：");
        System.out.println("  网格第 y 行 = 屏幕第 y 行，一格 = 屏幕一个字符位（· 为宽字符续格）；");
        System.out.println("· 帧 A 网格 = 按空格之前那屏的底稿；帧 B 网格 = 按空格之后那屏的底稿；");
        System.out.println("· diff 视图不是画面——是两张底稿的差异清单（■），框架每次只发这些格子。");
        if (lastFrameA != null) {
            System.out.println("\n帧 A（那屏的底稿）：");
            FrameDump.dump(lastFrameA).forEach(l -> System.out.println("  " + l));
        }
        if (lastFrameB != null) {
            System.out.println("帧 B（那屏的底稿）：");
            FrameDump.dump(lastFrameB).forEach(l -> System.out.println("  " + l));
        }
        if (lastFrameA != null && lastFrameB != null) {
            System.out.println("\n—— 两帧的 diff（■ = 框架实际发给终端的格子，其余一个字节都不发）——");
            FrameDiffView.between(lastFrameA, lastFrameB).forEach(l -> System.out.println("  " + l));
        }
        System.out.println("""

                本课核心认知（后面六课都踩在这上面）：
                1. 画面由 Buffer 表示（Cell 字符网格，一格一个字符）；Frame 不是画面，
                   是渲染入口（renderWidget 把组件画进 Buffer）；
                2. render 只管「整屏描述当前状态」——翻转一个布尔位，就是重画整屏的理由；
                3. 框架做 diff，只把 ■ 的格子发给终端。
                第 5 课组件的 render(Rect, Buffer) 是「往网格的一块区域画」；第 6 课的
                「改状态→请求重绘」之所以成立，正因为重绘 = 重新描述整屏。
                """);
    }
}

package io.github.javaside.springai.tamboui.demo;

import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.toolkit.InlineToolkit;
import dev.tamboui.toolkit.Toolkit;
import dev.tamboui.toolkit.app.InlineApp;
import dev.tamboui.toolkit.app.InlineToolkitRunner;
import dev.tamboui.toolkit.app.ToolkitRunner;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.InlineTuiConfig;
import dev.tamboui.tui.bindings.Actions;
import dev.tamboui.tui.bindings.KeyTrigger;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.input.TextArea;
import dev.tamboui.widgets.input.TextAreaState;
import io.github.javaside.springai.tamboui.TambouiDemo;

import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.richText;
import static dev.tamboui.toolkit.Toolkit.text;

/**
 * 示例④ 迷你版 CodeTuiView —— 基于 TamboUI 0.4.0 实测 API。
 *
 * <p>{@code CodeTuiView extends InlineApp}（约 2000 行）的 ~150 行缩影，对应关系：</p>
 * <table>
 *   <tr><td>钩子</td><td>本示例</td><td>code-tui</td></tr>
 *   <tr><td>{@link #height()}</td><td>4（空态）</td><td>4（空态）</td></tr>
 *   <tr><td>{@link #render()}</td><td>column(scope·残行, scope·面板, 输入框, 状态行)</td><td>同构，面板更多</td></tr>
 *   <tr><td>{@link #configure(int)}</td><td>bracketedPaste + Ctrl+C 才退出</td><td>同款（另加 noTick——那是 patch 提供 requestUiUpdate 之后的事）</td></tr>
 *   <tr><td>双通道</td><td>残行 live 区原地刷新；定稿行 {@code runner().println} 下沉</td><td>同构（ScrollbackPrinter + UiUpdateCoordinator）</td></tr>
 * </table>
 *
 * <p>本步核心认知：<b>live 区差分重绘 + scrollback println 下沉，两条通道各司其职</b>——
 * live 区只放「还会变的东西」（残行/面板/输入框/状态行），定稿内容立刻沉入 scrollback
 * 不再重绘。{@code scope(条件, 子元素)} 隐藏时 preferredSize=0 → 视口收缩 → 行被回收，
 * 这就是 code-tui「面板用完即收起」的全部机制。</p>
 */
public final class ToolkitChatDemo extends InlineApp implements TambouiDemo {

    /** 假流式的固定回复（多行，让「定稿下沉」有戏可看）。 */
    private static final String REPLY =
            "好的，我在演示流式输出：\n这一行定稿了，会沉入 scrollback 不再变化；\n"
                    + "而正在生成的残行留在输入框上方的 live 区原地刷新，\n"
                    + "回合结束、残段定稿后，「生成中」面板随之收起。";

    private final TextAreaState inputState = new TextAreaState();
    /** 只借键处理、从不渲染的 textArea 元素（code-tui InputBox 同款做法，理由见 ChatInputBox）。 */
    private final Element inputKeys = Toolkit.textArea(inputState);
    private final Block inputBlock = Block.builder()
            .borders(Borders.ALL).borderType(BorderType.ROUNDED).build();
    private final TextArea textArea = TextArea.builder().block(inputBlock).build();

    /** 当前流式会话；null = 空闲。只在渲染线程读写（UiRunnable/按键/tick 都在渲染线程）。 */
    private FakeStreamer streamer;
    private ToolkitRunner.ScheduledAction streamingTick;

    @Override
    public int order() {
        return 6;
    }

    @Override
    public String title() {
        return "Toolkit DSL 迷你聊天（live 区 + scrollback 双通道）";
    }

    @Override
    public void run() throws Exception {
        super.run();   // InlineApp 自举：create runner → onStart → run(this::render) → close
    }

    /** 空态高度：输入框（边框+1 行）+ 状态行，对照 CodeTuiView 的 4。 */
    @Override
    protected int height() {
        return 4;
    }

    /**
     * 覆盖默认键位：退出只绑 Ctrl+C（默认还绑 q/Q——输入框会被一个 q 字符整个关掉）；
     * 解绑焦点导航让 Tab 落到输入框。做法与理由照搬 code-tui 的 configure（含 bracketedPaste）。
     *
     * <p>与 code-tui 的差异：这里<b>保留默认 tick</b>——上游 0.4.0 的 UiRunnable 执行后不触发
     * 重绘（tick 事件才触发），「关 tick + 事件驱动重绘」依赖 patch 增补的
     * {@code requestUiUpdate}，教学口径只用上游原版，故 tick 留着（默认 100ms）。</p>
     */
    @Override
    protected InlineTuiConfig configure(int height) {
        InlineTuiConfig base = super.configure(height);
        return base.toBuilder()
                .bracketedPaste(true)
                .bindings(base.bindings().toBuilder()
                        .rebind(KeyTrigger.ctrl('c'), Actions.QUIT)
                        .unbind(Actions.FOCUS_NEXT)
                        .unbind(Actions.FOCUS_PREVIOUS)
                        .build())
                .build();
    }

    @Override
    protected void onStart() {
        runner().println("（迷你聊天：在输入框打字，Enter 发送，Ctrl+C 退出。中文输入 OK。）");
    }

    /**
     * 每次重绘重建整棵 UI 树。scope 第二参数 eager 求值——子树首行必须判空
     * （code-tui 同款纪律：xxxChildren() 首行 return null）。
     */
    @Override
    protected Element render() {
        FakeStreamer s = streamer;
        return column(
                scope(s != null && !s.view().liveTail().isEmpty(), richText(preview(s))),
                scope(s != null, text("⟐ 生成中…")),
                inputElement(),
                text(statusLine()));
    }

    private Element scope(boolean visible, Element child) {
        return InlineToolkit.scope(visible, child);
    }

    /** 残行预览：灰色斜体一行（code-tui 的流式残行同款位置：输入框正上方）。 */
    private Text preview(FakeStreamer s) {
        String tail = s == null ? "" : s.view().liveTail();
        return Text.from(Line.from(Span.styled("▌" + tail, Style.create().fg(Color.GRAY))));
    }

    private String statusLine() {
        String mode = streamer == null ? "空闲" : "生成中";
        return "TamboUI 迷你聊天 · " + mode + " · Enter 发送 · Ctrl+C 退出";
    }

    /** 输入框：自绘 Element（唯一焦点目标），Enter=发送、Ctrl+C=退出先拦，编辑键转交 textArea 键处理。 */
    private Element inputElement() {
        return new ChatInputBox();
    }

    private void submit() {
        String userText = inputState.text();
        if (userText.isBlank() || streamer != null) {
            return;   // 空输入或上一回合未结束时忽略（演示从简：不排队）
        }
        inputState.clear();
        runner().println("❯ " + userText);   // 用户行：定稿即下沉 scrollback
        streamer = new FakeStreamer(REPLY);
        streamingTick = runner().scheduleRepeating(() -> runner().runOnRenderThread(this::onStreamTick),
                java.time.Duration.ofMillis(50));
    }

    /** 流式每 tick：推进一字；新定稿行 println 下沉；结束时收面板、停定时器。渲染线程执行。 */
    private void onStreamTick() {
        FakeStreamer s = streamer;
        if (s == null) {
            return;
        }
        s.advance();
        for (String line : s.drainNewlySettled()) {
            runner().println(line);
        }
        if (s.done()) {
            streamer = null;
            if (streamingTick != null) {
                streamingTick.cancel();
                streamingTick = null;
            }
        }
    }

    /**
     * 输入框 Element。为何不直接把 {@code Toolkit.textArea(state)} 放进树里：
     * TextAreaElement 恒 focusable 且会自注册焦点——路由器先调它的内建 handleKeyEvent
     * （把 Enter 当换行插入并返回 HANDLED），发送逻辑轮不到。做法照搬 code-tui 的 InputBox：
     * 本元素亲自持焦点，按键第一手拦 Enter/Ctrl+C，其余编辑键转交给从不渲染的
     * {@link #inputKeys}（复用完整键处理，但不渲染故不抢焦点）；渲染走底层 TextArea 控件。
     */
    private final class ChatInputBox implements Element {
        @Override
        public boolean isFocusable() {
            return true;
        }

        @Override
        public String id() {
            return "mini-chat-input";
        }

        @Override
        public EventResult handleKeyEvent(KeyEvent event, boolean focused) {
            if (event.hasCtrl() && event.isCharIgnoreCase('c')) {
                quit();
                return EventResult.HANDLED;
            }
            if (event.isKey(dev.tamboui.tui.event.KeyCode.ENTER)) {
                submit();
                return EventResult.HANDLED;
            }
            return inputKeys.handleKeyEvent(event, true);   // 编辑键：交给 textArea 键处理
        }

        @Override
        public void render(Frame frame, Rect area, RenderContext context) {
            // 底层 TextArea 控件自绘（带圆角 Block 边框），renderWithCursor 补硬件光标（中文 IME 定位）
            textArea.renderWithCursor(area, frame.buffer(), inputState, frame);
        }

        /** 首选尺寸：行数 + 上下边框——输入框随行数自动增高（code-tui 同款）。 */
        @Override
        public dev.tamboui.toolkit.element.Size preferredSize(int availableWidth, int availableHeight,
                RenderContext context) {
            return new dev.tamboui.toolkit.element.Size(availableWidth, inputState.lineCount() + 2);
        }
    }
}

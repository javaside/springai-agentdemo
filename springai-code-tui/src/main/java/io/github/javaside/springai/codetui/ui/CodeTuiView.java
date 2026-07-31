package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.AskRequest;
import io.github.javaside.springai.codetui.agent.McpConfigLoader;
import io.github.javaside.springai.codetui.agent.McpRegistry;
import io.github.javaside.springai.codetui.agent.ModalRequest;
import io.github.javaside.springai.codetui.agent.ModelOption;
import io.github.javaside.springai.codetui.agent.OptionSpec;
import io.github.javaside.springai.codetui.agent.QuestionSpec;
import io.github.javaside.springai.codetui.agent.SkillInfo;
import io.github.javaside.springai.codetui.agent.SubmitHandler;
import io.github.javaside.springai.codetui.ui.ConversationState.OutputLine;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.buffer.Cell;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Text;
import dev.tamboui.toolkit.app.InlineApp;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.element.RenderContext;
import dev.tamboui.toolkit.element.Size;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.InlineTuiConfig;
import dev.tamboui.tui.bindings.Actions;
import dev.tamboui.tui.bindings.KeyTrigger;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.tui.event.PasteEvent;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.input.TextAreaState;
import reactor.core.Disposable;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.javaside.springai.codetui.ui.Theme.*;   // 配色 / 样式（DIM/HINT/PICK_SEL/… + styleFor），定义见 Theme
import static dev.tamboui.toolkit.InlineToolkit.scope;
import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.richText;
import static dev.tamboui.toolkit.Toolkit.text;
import static dev.tamboui.toolkit.Toolkit.textArea;

/**
 * Claude Code 式行内视图，改用官方 <b>Toolkit 声明式 DSL</b>（{@link InlineApp} + {@code InlineToolkitRunner}）。
 *
 * <p><b>为什么是它</b>：把「固定在输入框上方、原地更新、用完即收起」的计划面板做对，关键在于
 * <em>每帧都按 {@code preferredSize} 调用 {@code setContentHeight}（可增可减）并且每个 tick 都重绘</em>。
 * Toolkit 的运行器正是这么做的：{@code InlineScopeElement} 隐藏时 {@code preferredSize=0}，
 * 于是 {@code column} 收缩 → 视口高度收缩 → 腾出的行被回收；而「每 tick 必重绘」让底层
 * {@code InlineDisplay} 的相对光标记账始终与终端实际一致，从根上规避了此前手写渲染里「跳帧 + 收缩
 * (deleteLines)」导致的光标漂移、面板消失。
 *
 * <p><b>布局（自底向上钉在终端底部，其上是 println 出来的 scrollback）</b>：
 * <pre>
 *   [流式预览]        —— AI 生成中的当前残行（未换行段），{@code scope} 空则收起
 *   [📋 计划/todo 面板] —— 主 agent（控制器）的 todo，✓/▶/○ 分色，{@code scope} 无计划则收起
 *   [⟐ 任务面板]      —— 本回合派出的子 agent 状态（▶/✓/✗ + 当前工具），{@code scope} 无子 agent 则收起
 *   [圆角输入框]      —— 原生 {@code textArea}，多行/自动增高，自带光标/编辑/中文输入
 *   [状态行]
 * </pre>
 *
 * <p><b>定稿行下沉</b>：{@code pending} 与流式完整行在渲染线程（经
 * {@code scheduleRepeating → runOnRenderThread}，在两帧之间、非绘制中途执行）经 {@code drain} 交给
 * {@link ScrollbackPrinter} 用 {@code println} 推进 scrollback（欢迎横幅 / 用户块 / 工具 diff / markdown 正文均在其中）。
 */
public final class CodeTuiView extends InlineApp {

    private static final int TODO_CAP = 10;      // 计划面板（主 agent todo）最多显示几条
    private static final int SUBTASK_CAP = 6;    // 任务面板（子 agent 状态）最多显示几条
    private static final String INDENT = "  ";  // 对话内容缩进；工具/计划行自带前缀
    // 配色 / 样式集中在 {@link Theme}，本类经 import static Theme.* 引入（DIM/HINT/PICK_SEL/… 写法不变）。

    private final ConversationState state;
    private final SubmitHandler onSubmit;
    private final TextAreaState inputState = new TextAreaState();    // 输入源（多行编辑模型）
    // 仅用于复用 textArea 的完整编辑键处理（退格/方向/Home/End/字符/中文…）。⚠ 从不渲染它——
    // 一旦渲染，TextAreaElement 会以自增 id 自注册进焦点链、抢走焦点，导致外层拦不到 Enter。
    private final Element inputKeys = textArea(inputState);
    private final StatusBar statusBar = new StatusBar();             // 状态行动画内容（波光/压缩条）渲染
    private final ScrollbackPrinter printer;                        // scrollback 打印（欢迎/用户块/工具 diff/助手正文）
    private final ContextUsage ctxUsage;                             // 上下文用量追踪/报告（/context 报告 + 状态栏后缀）
    private final Path root;                                         // 工作区根目录（欢迎页展示）
    private Disposable current;
    private boolean pickingModel;                                    // /model 选择器是否激活
    private boolean pickingSkill;                                    // /skill 选择器是否激活
    private boolean pickingMcp;                                      // /mcp 管理面板是否激活
    private boolean mcpExpanded;                                     // Tab 展开选中项工具清单
    private volatile String mcpConnecting;                           // 非 null = 正在后台连接的 server 名（渲染线程读）
    private String pendingSkill;                                     // 已选技能名（可空）：显示为输入框上方标签，发送时随本条消息加载并清除
    private AskRequest activeAsk;                                     // 当前正在作答的问询（null=非作答态）
    private int askQ;                                                 // 当前问题下标
    private int askOpt;                                               // 当前问题内高亮的选项下标
    private final Map<String, String> askAnswers = new HashMap<>();   // 已答问题→答案
    private final Set<Integer> askChecked = new LinkedHashSet<>();     // 当前多选问题已勾选的选项下标（保序）
    private boolean askFreeText;                                      // 自由文本子模式（单选选了「其他」）
    private final TextAreaState askInput = new TextAreaState();       // 「其他」的自定义输入缓冲（单行直存直取）
    private int pickIndex;                                           // 选择器当前高亮项
    private int slashIndex;                                          // 斜杠命令补全菜单高亮项
    private boolean slashDismissed;                                  // Esc 关闭补全菜单（文本再变化前保持关闭）
    private final List<String> history = new ArrayList<>();          // 已提交消息历史（↑↓ 回溯）
    private int histIndex;                                           // 回溯指针；== history.size() 表示未回溯（草稿态）
    private String histDraft = "";                                   // 开始回溯前的输入草稿（Down 越过最新时恢复）
    private String lastShownModel = "";                              // 上次已提示的模型：仅在变化时再打 ⚙ 行
    private long animTick;                                           // 动画帧计数（drain 每 ~33ms 自增），驱动状态栏波光

    /** 斜杠命令（自动补全 + 分发）。 */
    private record SlashCommand(String name, String desc) {}
    private static final List<SlashCommand> COMMANDS = List.of(
            new SlashCommand("/model",   "切换 AI 模型"),
            new SlashCommand("/compact", "压缩会话历史（手动）"),
            new SlashCommand("/clear",   "清空上下文，开新会话"),
            new SlashCommand("/context", "查看上下文用量（事件数 / token）"),
            // /skill 必须排在 /skills 之前：二者中 /skill 是 /skills 的前缀，补全菜单默认高亮首个匹配；
            // 若 /skills 在前，输入 "/skill" 回车会误选到 /skills（只读清单）而进不了选择器。
            new SlashCommand("/skill",   "为本条消息指定技能"),
            new SlashCommand("/skills",  "查看可用技能（模型按需自动调用）"),
            new SlashCommand("/reload",  "重新扫描技能目录（新增/删除的 SKILL.md 生效）"),
            new SlashCommand("/mcp",     "管理 MCP 服务器（启用/禁用）"),
            new SlashCommand("/continue", "继续执行上一批未完成的计划"),
            new SlashCommand("/help",    "显示可用命令与快捷键"),
            new SlashCommand("/exit",    "退出"));

    public CodeTuiView(ConversationState state, SubmitHandler onSubmit, Path root) {
        this.state = state;
        this.onSubmit = onSubmit;
        this.root = root;
        ScrollbackPrinter.Sink sink = new ScrollbackPrinter.Sink() {
            @Override public void println(Text t)   { runner().println(t); }
            @Override public void println(String s) { runner().println(s); }
        };
        this.printer = new ScrollbackPrinter(sink, root, this::terminalWidth, CodeTuiView::wrapSegments);
        this.ctxUsage = new ContextUsage(onSubmit::contextStats, state::pushInfo);
    }

    /** 初始高度：圆角输入框(空态 3=边框2+1 行) + 状态行(1)；输入换行后 textArea 的 preferredSize 随行数增高，运行器逐帧跟随。 */
    @Override
    protected int height() {
        return 4;
    }

    /** 每帧构造 UI。scrollback 的 println 放在 {@link #drain} 里另行推进。 */
    @Override
    protected Element render() {
        List<String> todos = state.todoSnapshot();
        List<ConversationState.SubtaskView> subs = state.subtaskSnapshot();
        List<String> queued = state.queuedSnapshot();
        String tail = lastLine(state.streaming());   // 流式当前残行（未换行段）
        return column(
                scope(!tail.isEmpty(), richText(printer.preview(tail)).ellipsisStart()),
                scope(!todos.isEmpty(), todoChildren(todos)),
                scope(!subs.isEmpty(), subtaskChildren(subs)),
                scope(!queued.isEmpty(), queuedChildren(queued)),   // 排队消息面板：固定显示在输入框上方
                scope(pickingModel, modelPickerChildren()),         // /model 选择器面板
                scope(pickingSkill, skillPickerChildren()),         // /skill 选择器面板
                scope(pickingMcp, mcpPickerChildren()),             // /mcp 管理面板
                scope(activeAsk != null, askChildren()),            // AskUserQuestion 作答面板
                scope(slashMenuActive(), slashMenuChildren()),      // 斜杠命令补全菜单
                scope(pendingSkill != null, skillTag()),            // 已挂载技能标签：固定在输入框正上方，发送时随消息带走
                inputElement(),
                statusLine());
    }

    /**
     * 覆盖默认键位绑定：把 {@code quit} 只绑到 <b>Ctrl+C</b>。
     *
     * <p><b>为何必须改</b>：TamboUI 的默认（{@code standard.properties}）把
     * {@code quit = q, Q, Ctrl+c}——即裸按 {@code q}/{@code Q} 就退出。但本应用的输入框是<b>唯一焦点</b>，
     * 每个按键先过 {@link #onInputKey}，其顶部 {@code isQuit()} 会把输入/粘贴文本里的 {@code q}/{@code Q}
     * 当成退出键，导致「往输入框里打或粘贴含 q 的文本（如某些技能名）就整个退出」。
     * 输入框需要能输入任意字符，故退出只保留 Ctrl+C（与 Claude Code 一致）。{@link #onInputKey} 里也已
     * 不再依赖 {@code isQuit()}，双保险。
     */
    @Override
    protected InlineTuiConfig configure(int height) {
        InlineTuiConfig base = super.configure(height);
        return base.toBuilder()
                .bindings(base.bindings().toBuilder()
                        .rebind(KeyTrigger.ctrl('c'), Actions.QUIT)   // 整组替换：只剩 Ctrl+C，去掉 q/Q
                        .build())
                .build();
    }

    @Override
    protected void onStart() {
        runner().runOnRenderThread(() -> printer.welcome(onSubmit.currentModel(),
                io.github.javaside.springai.codetui.AppInfo.versionLabel()));   // 启动欢迎横幅（一次性下沉 scrollback）
        // 在渲染线程、两帧之间安全推进 scrollback（println 会移动光标/插行，绝不能在绘制中途调用）。
        runner().scheduleRepeating(() -> runner().runOnRenderThread(this::drain), Duration.ofMillis(33));
    }

    // ── scrollback 下沉（渲染线程） ──────────────────────────────────────
    private void drain() {
        animTick++;                                            // 推进状态栏波光动画帧（~33ms/帧）
        if (animTick % 30 == 0) ctxUsage.refresh();            // ~1s 刷一次状态栏上下文用量（节流：重算需遍历全部消息 + 估算 token）
        for (OutputLine ol : state.drainPending()) {
            switch (ol.kind()) {
                case USER       -> printer.userBlock(ol.text());   // 灰底白字块，仿 Claude Code
                case ASSISTANT  -> printer.assistant(ol.text());   // AI 正文：markdown/语法高亮 + 缩进
                case TOOL_START -> printer.toolStart(ol);          // edit/write：展开成 diff 块；其余单行摘要
                default         -> printer.line(ol);               // 工具/Todo/错误：单色贴左
            }
        }
        for (String row : state.takeCompleteStreamingLines()) {    // 流式完整行：markdown/语法高亮 + 缩进
            printer.streamingLine(row);
        }
        // 侦测到新问询（身份不同）→ 进入作答态并复位到第一问。
        // 队首若是 PermissionRequest 则本 Task 暂不处理（审批面板见 Task 14）。
        ModalRequest head = state.peekModal();
        if (head instanceof AskRequest pa && pa != activeAsk) {
            if (isAnswerable(pa)) {
                activeAsk = pa;
                askQ = 0; askOpt = 0; askAnswers.clear(); askChecked.clear();
                askFreeText = false; askInput.clear();   // 与其它复位点一致，防上一问询的自由文本残留
            } else {
                // 畸形问询（无问题 / 某问无选项）：不进模态。上游 Java 校验只 null-check 问题、不校验选项数，
                // 空选项会让 onAskKey 的 `% n` 除零、崩掉事件线程；这里优雅降级为取消整回合。
                // 必须与 Esc 走同一 cancelTurnFor：只 responder.cancel、不 dispose 回滚，会残留悬空 tool_calls → 下条 400。
                state.removeModal(pa);
                cancelTurnFor(pa, "问询格式无效，已取消当前回合");
            }
        }
        // 回合结束后自动出队下一条排队消息。submit() 同步置 THINKING，故本 tick 只会出队一条，无重复提交竞态。
        if (!busy()) {   // 空闲、非压缩中、且无在飞子 agent 才出队（见 busy()）
            ConversationState.Queued next = state.pollQueued();
            if (next != null) dispatch(next.text(), next.skill());
        }
    }

    /** 测试专用：跑一次 drain（侦测 pendingAsk 并进入作答态）。 */
    void tickForTest() { drain(); }

    /**
     * 测试专用：把一个按键喂给输入框按键入口（等价真实按键路由）。
     *
     * <p>必须复用 {@link InputBox#handleKeyEvent}（而非只调 {@code onInputKey}）：普通字符键
     * {@code onInputKey} 自己并不插入文本——它只拦截 Ctrl+C/Esc/Enter/方向键等特例，未拦截时
     * 返回 {@code UNHANDLED}，真正的字符插入落在 {@code InputBox.handleKeyEvent} 的兜底分支
     * （转交不渲染的 {@link #inputKeys}）。只调 {@code onInputKey} 会让「打字」在测试里静默丢失。
     */
    EventResult feedKeyForTest(KeyEvent k) { return new InputBox().handleKeyEvent(k, true); }

    /** 测试专用：构造一帧 UI 树（等价渲染线程每帧调用的 render）。用于回归「每帧构造子面板」类空指针。 */
    Element renderForTest() { return render(); }

    /** 测试专用：读取输入框当前文本 / 光标（行、列），断言编辑快捷键的落点。 */
    String inputTextForTest() { return inputState.text(); }
    int cursorRowForTest() { return inputState.cursorRow(); }
    int cursorColForTest() { return inputState.cursorCol(); }
    /** 测试专用：预置输入文本（光标落到文末），免逐字符敲入。 */
    void setInputForTest(String text) { inputState.setText(text); inputState.moveCursorToEnd(); }

    /** 终端列数；拿不到时退化为 80。 */
    private int terminalWidth() {
        try {
            int w = runner().tuiRunner().width();
            return w > 0 ? w : 80;
        } catch (Exception e) {
            return 80;
        }
    }

    /** 内容的显示宽度（中文占 2 列），用于底色补齐计算。 */
    private static int displayWidth(String s) {
        return dev.tamboui.text.CharWidth.of(s);
    }

    // ── 输入 ────────────────────────────────────────────────────────────
    private Element inputElement() {
        return new InputBox();
    }

    /**
     * 圆角多行输入框（<b>唯一焦点目标</b>，自绘）。
     *
     * <p><b>为何不直接用 {@code textArea} 元素</b>：{@code TextAreaElement.isFocusable()} 恒为 true，
     * 未显式给 id 时会以自增 id 自注册进焦点链并<em>抢占焦点</em>；而路由器对焦点元素<em>先调内建
     * handleKeyEvent</em>（把 Enter 当换行插入并返回 HANDLED），我们挂的发送逻辑根本轮不到 → Enter 发不出去。
     *
     * <p><b>做法</b>：本元素亲自持有焦点（固定 id + focusable），按键第一手先给 {@link #onInputKey}
     * 拦下 Enter=发送 / Ctrl+C / Esc；其余编辑键（退格/方向/Home/End/字符/中文…）转交给<em>从不渲染</em>的
     * {@link #inputKeys}（复用其完整键处理，但因不渲染故不自注册、不抢焦点）。渲染走底层 {@code TextArea}
     * 控件（不经过会自注册的 element），并补硬件光标供中文 IME 定位。高度 = 行数 + 边框，随行数自动增高。
     */
    private final class InputBox implements Element {
        @Override public boolean isFocusable() { return true; }
        @Override public String id() { return "code-tui-input"; }

        @Override
        public EventResult handleKeyEvent(KeyEvent event, boolean focused) {
            EventResult r = onInputKey(event);             // Ctrl+C / Esc / Enter(发送) 在此拦下
            if (r.isHandled()) return r;
            return inputKeys.handleKeyEvent(event, focused);   // 其余编辑键交给（不渲染的）textArea 键处理
        }

        @Override
        public EventResult handlePasteEvent(PasteEvent event) {
            return inputKeys.handlePasteEvent(event);      // 多行粘贴
        }

        @Override
        public Size preferredSize(int maxW, int maxH, RenderContext ctx) {
            int w = maxW > 0 ? maxW : 80;
            return Size.of(w, visualRowCount(w - 2) + 2); // 自动增高：软折行后的可视行数 + 上下边框
        }

        @Override
        public void render(Frame frame, Rect rect, RenderContext ctx) {
            Buffer buf = frame.buffer();
            Block block = Block.builder().borders(Borders.ALL).borderType(BorderType.ROUNDED).build();
            block.render(rect, buf);
            Rect inner = block.inner(rect);
            int ix = inner.x(), iy = inner.y(), iw = Math.max(1, inner.width()), ih = inner.height();

            if (inputState.text().isEmpty()) {              // 空态：只画反显块光标，不画框内占位符
                // 不放框内占位符：中文输入法拼字（候选未上屏）时 inputState 仍为空，占位符会与拼音并存、
                // 显得「打字时占位符还在」。输入引导已在下方状态行常驻，框内保持干净只留可见光标即可。
                // 只 setCursorPosition 时硬件光标常被行内 runner 隐藏 → 给人「没光标/没聚焦」错觉，故画反显块。
                buf.set(ix, iy, buf.get(ix, iy).patchStyle(Style.EMPTY.reversed()));
                frame.setCursorPosition(ix, iy);
                return;
            }

            int cr = inputState.cursorRow(), cc = inputState.cursorCol();
            int vis = 0, curRow = 0, curCol = 0;
            int n = inputState.lineCount();
            for (int li = 0; li < n; li++) {                // 逐条逻辑行按框宽软折行，画到连续可视行
                String logical = inputState.getLine(li);
                List<String> segs = wrapSegments(logical, iw);
                int base = 0;
                for (int si = 0; si < segs.size(); si++) {
                    String seg = segs.get(si);
                    if (vis < ih) buf.setString(ix, iy + vis, seg, Style.EMPTY);
                    if (li == cr) {                         // 定位光标所在的可视行/列
                        int segStart = base, segEnd = base + seg.length();
                        boolean last = si == segs.size() - 1;
                        if (cc >= segStart && (cc < segEnd || (last && cc <= segEnd))) {
                            curRow = vis;
                            int within = Math.min(cc - segStart, seg.length());
                            curCol = dev.tamboui.text.CharWidth.of(seg.substring(0, within));
                        }
                    }
                    base += seg.length();
                    vis++;
                }
            }
            // 反显格 + 硬件光标（中文 IME 定位），夹到可视区内
            int cx = ix + Math.min(curCol, iw - 1);
            int cy = iy + Math.min(curRow, Math.max(0, ih - 1));
            Cell cell = buf.get(cx, cy);
            buf.set(cx, cy, cell.patchStyle(Style.EMPTY.reversed()));
            frame.setCursorPosition(cx, cy);
        }
    }

    /** 逻辑行按显示宽度（中文 2 列）软折行成若干可视段；空行返回单个空段。 */
    private static List<String> wrapSegments(String line, int width) {
        int w = Math.max(1, width);
        List<String> segs = new ArrayList<>();
        if (line.isEmpty()) { segs.add(""); return segs; }
        String rest = line;
        while (!rest.isEmpty()) {
            String seg = dev.tamboui.text.CharWidth.substringByWidth(rest, w);
            if (seg.isEmpty()) seg = rest.substring(0, 1);   // 兜底：框窄到放不下 1 个宽字符时也吃 1 个
            segs.add(seg);
            rest = rest.substring(seg.length());
        }
        return segs;
    }

    /** 当前文本在给定内宽下软折行后的总可视行数（≥1）。 */
    private int visualRowCount(int innerWidth) {
        int rows = 0, n = inputState.lineCount();
        for (int i = 0; i < n; i++) rows += wrapSegments(inputState.getLine(i), innerWidth).size();
        return Math.max(1, rows);
    }

    /**
     * 输入框按键（本处理器早于 textArea 内建处理触发，返回 HANDLED 即可拦截）：
     * <ul>
     *   <li>Ctrl+C / quit → 退出；</li>
     *   <li>Esc / cancel → 取消当前回合（返回 HANDLED 以免 Esc 被路由器用去清焦点）；</li>
     *   <li>Enter（无 Shift/Alt）→ 提交并拦截，textArea 不再插入换行；</li>
     *   <li>Shift/Alt+Enter → 放行（UNHANDLED），交给 textArea 插入换行（能否区分取决于终端）；</li>
     *   <li>readline 编辑键（{@link #onEditShortcut}）：Ctrl+A/E 行首尾、Ctrl/Alt+←→ 与 Alt+B/F 按词跳、
     *       Ctrl+W / Alt+Backspace 删上一词、Ctrl+U/K 删至行首/行尾。</li>
     * </ul>
     */
    private EventResult onInputKey(KeyEvent k) {
        // 只认 Ctrl+C 退出。绝不用 isQuit()——默认绑定里 quit 含裸 q/Q，会把输入/粘贴到输入框的
        // 含 q 文本误判为退出（本类是唯一焦点，每个按键都先过这里）。绑定已在 configure() 收敛，此处再兜一层。
        if (k.isCtrlC()) {
            quit();
            return EventResult.HANDLED;
        }
        // 任意按键消费掉上一条 sticky notice（如「已取消当前回合」），恢复状态栏常态行。
        // 本次按键若要显示新 notice，会在下方各分支重新 setNotice（晚于此处），故当次提示不受影响。
        // 修复：真实输入走 inputState 编辑器、不再触发旧 typeChar 清 notice，导致取消长回合（如子 agent）
        // 后 notice 永久占据状态栏；这里补回「下次按键即清」的既定行为。
        if (!state.notice().isEmpty()) state.setNotice("");
        if (activeAsk != null) return onAskKey(k);      // 作答模态：全部按键交给它，屏蔽文本编辑
        if (pickingModel) return onModelPickerKey(k);   // 选择器激活：按键全部交给它，屏蔽文本编辑
        if (pickingSkill) return onSkillPickerKey(k);   // 技能选择器同理
        if (pickingMcp) return onMcpPickerKey(k);       // MCP 管理面板同理
        // 正在浏览历史（histIndex<size）时 ↑↓ 始终翻历史——即使翻到的是一条 /命令、补全菜单也弹出来了，
        // 也不让菜单抢走 ↑↓（否则一遇到 /model 就卡住翻不动）。菜单的 Tab/Enter/Esc 仍照常处理。
        if (histIndex < history.size()) {
            if (k.code() == KeyCode.UP && inputState.cursorRow() == 0) {
                EventResult r = recallPrev();
                if (r.isHandled()) return r;
            }
            if (k.code() == KeyCode.DOWN && inputState.cursorRow() == inputState.lineCount() - 1) {
                EventResult r = recallNext();
                if (r.isHandled()) return r;
            }
        }
        if (slashMenuActive()) {                         // 斜杠命令补全菜单：拦截 ↑↓/Tab/Enter/Esc
            EventResult r = onSlashMenuKey(k);
            if (r.isHandled()) return r;
        }
        if (k.isCancel() && pendingSkill != null && state.isIdle()) {
            pendingSkill = null;                     // Esc 移除输入框上方的技能标签（空闲态；忙碌态 Esc 仍走取消回合）
            state.setNotice("已移除技能");
            return EventResult.HANDLED;
        }
        if (k.isCancel()) {
            boolean running = !state.isIdle();
            int dropped = state.queuedCount();
            if (current != null) { current.dispose(); current = null; }
            state.cancelCurrent();
            state.clearQueued();                         // 取消时一并清空排队消息
            state.setNotice(running || dropped > 0
                    ? "已取消当前回合" + (dropped > 0 ? "，丢弃 " + dropped + " 条排队" : "")
                    : "");
            return EventResult.HANDLED;
        }
        boolean isEnter = k.code() == KeyCode.ENTER || k.isChar('\r') || k.isChar('\n');
        if (isEnter) {
            // Shift/Alt+Enter → 换行（仅当终端能区分修饰键；Apple Terminal 等区分不了，见下反斜杠续行）
            if (k.hasShift() || k.hasAlt()) return EventResult.UNHANDLED;   // 交给 inputKeys 插入 '\n'
            // 反斜杠续行（终端无关的可靠换行，同 Claude Code）：行尾 \ + Enter → 删掉 \ 再换行
            if (cursorAfterBackslash()) {
                inputState.deleteBackward();
                inputState.insert('\n');
                return EventResult.HANDLED;
            }
            submitInput();
            return EventResult.HANDLED;   // 普通 Enter → 发送（不让编辑器当作换行）
        }
        // readline 式编辑快捷键（长文本内快速移动/删除）。命中即拦截，并与「其余按键」同样复位
        // 补全/回溯状态（HANDLED 提前返回走不到方法尾部的公共复位，这里补上）。
        EventResult edit = onEditShortcut(k);
        if (edit.isHandled()) {
            slashDismissed = false;
            slashIndex = 0;
            histIndex = history.size();
            return edit;
        }
        // ↑ 在首行 → 回溯更早的历史；↓ 在末行 → 回溯更晚（越过最新恢复草稿）。非边界行则放行给编辑器移动光标。
        if (k.code() == KeyCode.UP && inputState.cursorRow() == 0) {
            EventResult r = recallPrev();
            if (r.isHandled()) return r;
        }
        if (k.code() == KeyCode.DOWN && inputState.cursorRow() == inputState.lineCount() - 1) {
            EventResult r = recallNext();
            if (r.isHandled()) return r;
        }
        slashDismissed = false;          // 其余键将落到编辑器改动文本 → 让补全菜单随新前缀重新出现
        slashIndex = 0;
        histIndex = history.size();       // 非 ↑↓ 的按键（含左右移动/编辑）退出回溯态
        return EventResult.UNHANDLED;
    }

    /**
     * readline 式编辑快捷键（bash/zsh 肌肉记忆，长文本内不必按住 ←→ 一格格挪）：
     * <ul>
     *   <li>Ctrl+A / Ctrl+E → 行首 / 行尾（终端吃掉了 Home/End 时的替身）；</li>
     *   <li>Ctrl+← / Alt+← / Alt+B → 上一词词首；Ctrl+→ / Alt+→ / Alt+F → 下一词词尾；</li>
     *   <li>Ctrl+W / Alt+Backspace → 删除光标前一个词；</li>
     *   <li>Ctrl+U / Ctrl+K → 删到行首 / 删到行尾。</li>
     * </ul>
     * 词边界基于逻辑行 + Java code point（中文每字一个词元，见 {@link #prevWordStart}），软折行不影响语义。
     * ⚠ 控制字节歧义：终端把 Ctrl+A..Z 发成字节 1..26，解析器映射为 CTRL+字母——其中 Ctrl+H/I/J/M
     * 与 Backspace/Tab/Enter 字节相同、已被映射为独立 KeyCode，故本表只用不冲突的字母。未命中返回
     * UNHANDLED，交回 {@link #onInputKey} 的后续分支与 textArea 兜底。
     */
    private EventResult onEditShortcut(KeyEvent k) {
        boolean ctrl = k.hasCtrl(), alt = k.hasAlt();
        if (ctrl && k.isChar('a')) { inputState.moveCursorToLineStart(); return EventResult.HANDLED; }
        if (ctrl && k.isChar('e')) { inputState.moveCursorToLineEnd();   return EventResult.HANDLED; }
        if ((ctrl || alt) && k.code() == KeyCode.LEFT)  { moveWordLeft();  return EventResult.HANDLED; }
        if ((ctrl || alt) && k.code() == KeyCode.RIGHT) { moveWordRight(); return EventResult.HANDLED; }
        if (alt && (k.isChar('b') || k.isChar('B'))) { moveWordLeft();  return EventResult.HANDLED; }
        if (alt && (k.isChar('f') || k.isChar('F'))) { moveWordRight(); return EventResult.HANDLED; }
        // Alt+Backspace 的两种到达形态都认：ESC+DEL 可能被解析成 ALT+char(127)，而非 ALT+BACKSPACE
        if ((ctrl && k.isChar('w')) || (alt && (k.code() == KeyCode.BACKSPACE || k.isChar(127)))) { deleteWordBackward(); return EventResult.HANDLED; }
        if (ctrl && k.isChar('u')) { deleteToLineStart(); return EventResult.HANDLED; }
        if (ctrl && k.isChar('k')) { deleteToLineEnd();   return EventResult.HANDLED; }
        return EventResult.UNHANDLED;
    }

    /** 光标移到上一词词首；已在行首则跨到上一行行尾（TextAreaState 无按词/整段删改 API，全部经既有单步原语组合实现）。 */
    private void moveWordLeft() {
        int cr = inputState.cursorRow(), cc = inputState.cursorCol();
        if (cc == 0) { inputState.moveCursorLeft(); return; }         // 行首：借单步左移跨行到上一行行尾
        int target = prevWordStart(inputState.getLine(cr), cc);
        while (inputState.cursorCol() > target) inputState.moveCursorLeft();
    }

    /** 光标移到下一词词尾；已在行尾则跨到下一行行首。 */
    private void moveWordRight() {
        int cr = inputState.cursorRow(), cc = inputState.cursorCol();
        String line = inputState.getLine(cr);
        if (cc >= line.length()) { inputState.moveCursorRight(); return; }   // 行尾：单步右移跨行
        int target = nextWordEnd(line, cc);
        while (inputState.cursorCol() < target) inputState.moveCursorRight();
    }

    /** 删除光标前一个词（Ctrl+W，readline unix-word-rubout：空白为界，"-m" 这类带标点的词元整个删）；行首则退格并行。 */
    private void deleteWordBackward() {
        int cr = inputState.cursorRow(), cc = inputState.cursorCol();
        if (cc == 0) { inputState.deleteBackward(); return; }
        int target = prevWordStartForDelete(inputState.getLine(cr), cc);
        while (inputState.cursorCol() > target) inputState.deleteBackward();
    }

    /** 删到行首（Ctrl+U）。行首无操作（不并行，同 readline unix-line-discard 语义按行为界）。 */
    private void deleteToLineStart() {
        while (inputState.cursorCol() > 0) inputState.deleteBackward();
    }

    /** 删到行尾（Ctrl+K）。行尾无操作（不吞换行，删完当前行内容为止）。 */
    private void deleteToLineEnd() {
        int cr = inputState.cursorRow();
        while (inputState.cursorCol() < inputState.getLine(cr).length()) inputState.deleteForward();
    }

    /**
     * 上一词词首（返回目标列，均为 char 下标）：先吃掉紧邻的空白/标点，再吃一段同类词元。
     * 词元分两类：{@code 字母数字下划线} 连成一词；CJK 每个字符自成一词（中文无空格，
     * 整段吞掉会退化成 Ctrl+U，按单字跳与主流终端/IDE 的 CJK 处理一致）。纯函数便于单测。
     */
    static int prevWordStart(String line, int col) {
        int i = Math.min(col, line.length());
        while (i > 0 && !isWordChar(line.codePointBefore(i))) i -= Character.charCount(line.codePointBefore(i));
        if (i > 0 && isCjk(line.codePointBefore(i))) return i - Character.charCount(line.codePointBefore(i));
        while (i > 0 && isWordChar(line.codePointBefore(i)) && !isCjk(line.codePointBefore(i)))
            i -= Character.charCount(line.codePointBefore(i));
        return i;
    }

    /** 下一词词尾（返回目标列）：先吃掉紧邻的空白/标点，再吃一段同类词元。CJK 按单字跳。 */
    static int nextWordEnd(String line, int col) {
        int i = Math.max(0, Math.min(col, line.length()));
        while (i < line.length() && !isWordChar(line.codePointAt(i))) i += Character.charCount(line.codePointAt(i));
        if (i < line.length() && isCjk(line.codePointAt(i))) return i + Character.charCount(line.codePointAt(i));
        while (i < line.length() && isWordChar(line.codePointAt(i)) && !isCjk(line.codePointAt(i)))
            i += Character.charCount(line.codePointAt(i));
        return i;
    }

    /**
     * Ctrl+W 的删除边界（readline unix-word-rubout 语义）：以<b>空白</b>为界——先吃空白、
     * 再吃一段非空白（{@code "-m"}、{@code "a.b"} 这类带标点的词元整个删）。CJK 仍按单字删，
     * 中文长句一记 Ctrl+W 清光太危险。与移动用的 {@link #prevWordStart}（字母数字为词）刻意不同。
     */
    static int prevWordStartForDelete(String line, int col) {
        int i = Math.min(col, line.length());
        while (i > 0 && Character.isWhitespace(line.codePointBefore(i))) i -= Character.charCount(line.codePointBefore(i));
        if (i > 0 && isCjk(line.codePointBefore(i))) return i - Character.charCount(line.codePointBefore(i));
        while (i > 0 && !Character.isWhitespace(line.codePointBefore(i)) && !isCjk(line.codePointBefore(i)))
            i -= Character.charCount(line.codePointBefore(i));
        return i;
    }

    private static boolean isWordChar(int cp) { return Character.isLetterOrDigit(cp) || cp == '_'; }

    private static boolean isCjk(int cp) {
        Character.UnicodeScript s = Character.UnicodeScript.of(cp);
        return s == Character.UnicodeScript.HAN || s == Character.UnicodeScript.HIRAGANA
                || s == Character.UnicodeScript.KATAKANA || s == Character.UnicodeScript.HANGUL;
    }

    /** ↑：回溯到更早的一条历史。历史为空则不拦截（UNHANDLED）。 */
    private EventResult recallPrev() {
        if (history.isEmpty()) return EventResult.UNHANDLED;
        if (histIndex >= history.size()) histDraft = inputState.text();   // 首次回溯：存草稿
        if (histIndex > 0) {
            histIndex--;
            inputState.setText(history.get(histIndex));
            inputState.moveCursorToEnd();
        }
        return EventResult.HANDLED;       // 已到最早也吞掉，避免光标乱跳
    }

    /** ↓：回溯到更晚的一条历史；越过最新则恢复草稿。未在回溯态则不拦截。 */
    private EventResult recallNext() {
        if (histIndex >= history.size()) return EventResult.UNHANDLED;    // 未回溯：放行
        histIndex++;
        inputState.setText(histIndex >= history.size() ? histDraft : history.get(histIndex));
        inputState.moveCursorToEnd();
        return EventResult.HANDLED;
    }

    /** 记录一条已提交消息（跳过与上一条重复的），并复位回溯指针。 */
    private void addHistory(String text) {
        if (history.isEmpty() || !history.get(history.size() - 1).equals(text)) history.add(text);
        histIndex = history.size();
    }

    // ── 斜杠命令自动补全（仿 Claude Code） ───────────────────────────────
    /** 正在输入命令 token：以 / 开头、单行、尚未出现空格（空格后视为在敲参数，不再补全）。 */
    private boolean typingSlashToken() {
        String t = inputState.text();
        return t.startsWith("/") && t.indexOf('\n') < 0 && t.indexOf(' ') < 0;
    }

    /** 当前前缀匹配到的命令。 */
    private List<SlashCommand> slashMatches() {
        if (!typingSlashToken()) return List.of();
        String t = inputState.text().toLowerCase();
        List<SlashCommand> out = new ArrayList<>();
        for (SlashCommand c : COMMANDS) if (c.name().startsWith(t)) out.add(c);
        return out;
    }

    private boolean slashMenuActive() { return !slashDismissed && !slashMatches().isEmpty(); }

    private static int clampIndex(int i, int n) { return n <= 0 ? 0 : Math.max(0, Math.min(i, n - 1)); }

    /** 菜单激活时的按键：↑↓/kj 移动、Tab 补全、Enter 运行、Esc 关闭；其余放行给编辑器过滤前缀。 */
    private EventResult onSlashMenuKey(KeyEvent k) {
        List<SlashCommand> m = slashMatches();
        int n = m.size();
        slashIndex = clampIndex(slashIndex, n);
        if (k.isCancel()) { slashDismissed = true; return EventResult.HANDLED; }
        if (k.code() == KeyCode.UP)   { slashIndex = (slashIndex - 1 + n) % n; return EventResult.HANDLED; }
        if (k.code() == KeyCode.DOWN) { slashIndex = (slashIndex + 1) % n;     return EventResult.HANDLED; }
        if (k.code() == KeyCode.TAB || k.isChar('\t')) { inputState.setText(m.get(slashIndex).name()); return EventResult.HANDLED; }
        if (k.code() == KeyCode.ENTER || k.isChar('\r') || k.isChar('\n')) {
            inputState.setText(m.get(slashIndex).name());
            submitInput();                        // 复用分发：/model→选择器，/help→帮助
            return EventResult.HANDLED;
        }
        return EventResult.UNHANDLED;             // 字母/退格 → 交给编辑器改前缀，菜单随之过滤
    }

    /** 光标紧跟在一个反斜杠之后（行尾 {@code \} + Enter 用作换行的判定）。 */
    private boolean cursorAfterBackslash() {
        int cr = inputState.cursorRow(), cc = inputState.cursorCol();
        String line = cr >= 0 && cr < inputState.lineCount() ? inputState.getLine(cr) : "";
        return cc > 0 && cc <= line.length() && line.charAt(cc - 1) == '\\';
    }

    /** 提交：忙时把消息入队（回合结束由 {@link #drain} 自动出队提交），空闲时立即提交。均清空输入框。 */
    private void submitInput() {
        String text = inputState.text();
        if (text == null || text.isBlank()) return;
        addHistory(text);                            // 记入历史（含斜杠命令），供 ↑↓ 回溯
        String cmd = text.strip();
        if (cmd.equals("/model")) {                  // 斜杠命令：打开模型选择器（仿 Claude Code）
            inputState.clear();
            openModelPicker();
            return;
        }
        if (cmd.equals("/compact")) {
            inputState.clear();
            if (state.isCompacting()) {
                return;   // 已在压缩：动画条已表明状态，不再叠加 notice（否则会在压缩结束后残留一帧）
            }
            if (!state.isIdle()) {
                state.setNotice("忙碌中，无法压缩");   // 回合进行中：拒绝并提示
                return;
            }
            onSubmit.compact();
            return;
        }
        if (cmd.equals("/clear")) {                  // 换新空会话：旧会话留盘可 -c 恢复
            inputState.clear();
            if (state.isBusy()) {                    // 回合中 / 压缩中 / 有待处理模态：拒绝（见 isBusy）
                state.setNotice("忙碌中，无法清空");
                return;
            }
            onSubmit.clearContext();                 // (A) 换 sessionId
            state.resetForNewSession();              // 复位面板/排队/提示
            lastShownModel = "";                     // 新会话首个回合重新打「⚙ 使用模型 X」
            pendingSkill = null;                      // 清掉未发送的技能挂载：新会话不继承
            var r = runner();
            if (r != null) {                         // (B) 真清屏只在运行态做（测试态 runner==null 跳过）
                r.runOnRenderThread(() -> {
                    boolean ok = ScreenCleaner.clear(r);
                    if (ok) {
                        printer.welcome(onSubmit.currentModel(),
                                io.github.javaside.springai.codetui.AppInfo.versionLabel());
                    } else {
                        state.pushInfo("─── 新会话（上下文已清空）───");   // 反射失败降级；pushInfo 经 drain 下沉 scrollback
                    }
                });
            } else {
                state.pushInfo("─── 新会话（上下文已清空）───");
            }
            return;
        }
        if (cmd.equals("/context")) {          // 只读快照：任何时刻都可查（含回合进行中），不打断
            inputState.clear();
            ctxUsage.report();
            return;
        }
        if (cmd.equals("/skills")) {           // 只读清单：任何时刻都可查，不打断
            inputState.clear();
            printSkills();
            return;
        }
        if (cmd.equals("/skill")) {                  // 打开技能选择器（选中后显示为输入框上方标签，发送时加载）
            inputState.clear();
            openSkillPicker();
            return;
        }
        if (cmd.equals("/reload")) {                 // 重扫技能目录：运行中新增/删除的 SKILL.md 就此对模型与 /skills 生效
            inputState.clear();
            reloadSkills();
            return;
        }
        if (cmd.equals("/mcp")) {                    // MCP 管理面板：仅空闲可开（回合中摘工具/关连接会撞在飞调用）
            inputState.clear();
            if (busy()) { state.setNotice("忙碌中，无法管理 MCP"); return; }
            openMcpPicker();
            return;
        }
        if (cmd.equals("/continue")) {               // 续跑：上一批计划被 Esc/报错中断后，据会话里保留的 todo 从首个未完成项接着做
            inputState.clear();
            // 工具中立：别硬点 Task/串行——上一批若是 ParallelTasks 并行跑的，"逐个用 Task" 会把独立任务逼回串行、丢掉并行。
            // 让模型按任务独立性自选，并与先前采用的方式保持一致。
            String prompt = "继续执行上一批未完成的计划。请先回顾你的 todo 列表，从第一个尚未完成的任务开始委派子 agent 继续："
                    + "相互独立、无共享状态的子任务用 ParallelTasks 并行委派，有依赖或需共享上下文的用 Task 串行委派"
                    + "（与你先前采用的方式保持一致）；已完成的任务不要重做。若没有未完成的计划，直接说明即可。";
            if (busy()) state.enqueue(prompt, null);   // 忙/压缩中/有在飞子 agent：排队，清空后自动出队（同普通消息）
            else dispatch(prompt, null);
            return;
        }
        if (cmd.equals("/help")) {
            inputState.clear();
            printHelp();
            return;
        }
        if (cmd.equals("/exit") || cmd.equals("/quit")) {
            inputState.clear();
            quit();
            return;
        }
        inputState.clear();
        String skill = pendingSkill;                 // 一次性：本条消息取走挂载
        pendingSkill = null;
        if (busy()) {                                // 忙/压缩中/有在飞子 agent：排队，挂载随消息入队
            state.enqueue(text, skill);              // 反馈靠状态行的实时「已排队 N 条」，不用 sticky notice
            return;
        }
        dispatch(text, skill);
    }

    /**
     * 是否不应立即起新回合：回合中 / 压缩中（{@link ConversationState#isBusy()}），
     * <b>或</b>仍有在飞子 agent（上一回合被 Esc 取消后并行子 agent 可能仍在跑，其迟到写入会污染会话）。
     * 后者让 /continue 等新回合排队到旧子 agent 清空后再起，杜绝两回合并发写同一会话。
     */
    private boolean busy() {
        return state.isBusy() || onSubmit.hasInFlightSubagents();
    }

    /**
     * 空闲态但仍有已取消子 agent 在收尾时的状态行提示标签；否则 {@code null}（走常态行/思考·工具指示）。
     * Esc 取消并行子 agent 后 {@code state} 立即回 IDLE，但若子 agent 的 HTTP 不响应 interrupt 会继续收尾，
     * 期间 {@link #busy()} 仍 true、新消息静默入队——给一句提示避免误判卡死。纯函数，便于单测（见 statusLine 调用）。
     */
    static String drainingSubagentsHint(boolean idle, boolean hasInFlightSubagents) {
        return (idle && hasInFlightSubagents) ? "⟳ 等待已取消的子 agent 收尾…" : null;
    }

    /** 真正发起一个回合：提交给 agent（skill 可空——挂载技能则发送前注入正文）。仅当模型与上次不同时才打一行「⚙ 使用模型 X」（首个回合也会打）。 */
    private void dispatch(String text, String skill) {
        current = onSubmit.submit(text, skill);
        String m = onSubmit.currentModel();
        if (!m.equals(lastShownModel)) {
            state.pushInfo("⚙ 使用模型 " + m);
            lastShownModel = m;
        }
    }

    // ── /model 模型选择器 ───────────────────────────────────────────────
    /** 打开选择器：高亮定位到当前所选模型。 */
    private void openModelPicker() {
        List<ModelOption> models = onSubmit.models();
        if (models.isEmpty()) { state.setNotice("当前没有可选模型"); return; }
        pickIndex = 0;
        String cur = onSubmit.currentModel();
        for (int i = 0; i < models.size(); i++) {
            if (models.get(i).id().equals(cur)) { pickIndex = i; break; }
        }
        pickingModel = true;
    }

    /** 选择器按键：↑↓/kj 移动、数字快选、Enter 确认、Esc 取消。始终 HANDLED（屏蔽文本编辑）。 */
    private EventResult onModelPickerKey(KeyEvent k) {
        List<ModelOption> models = onSubmit.models();
        int n = models.size();
        if (k.isCancel()) { pickingModel = false; return EventResult.HANDLED; }
        if (k.code() == KeyCode.UP || k.isChar('k'))   { pickIndex = (pickIndex - 1 + n) % n; return EventResult.HANDLED; }
        if (k.code() == KeyCode.DOWN || k.isChar('j')) { pickIndex = (pickIndex + 1) % n;     return EventResult.HANDLED; }
        for (int i = 0; i < n && i < 9; i++) {           // 数字 1..n 快选
            if (k.isChar((char) ('1' + i))) { pickIndex = i; return EventResult.HANDLED; }
        }
        if (k.code() == KeyCode.ENTER || k.isChar('\r') || k.isChar('\n')) {
            ModelOption chosen = models.get(pickIndex);
            onSubmit.selectModel(chosen.id());
            pickingModel = false;
            // 不用 sticky notice：notice 会一直占据状态栏、遮蔽常态行（模型名 + 上下文%）直到下次按键，
            // 造成「切换模型后状态栏信息就没了」。改为下沉一行 scrollback 确认，状态栏立刻回到常态。
            state.pushInfo("⚙ 已切换模型 · " + chosen.label());
            lastShownModel = chosen.id();   // 避免下个回合 dispatch 再重复打「⚙ 使用模型」
            return EventResult.HANDLED;
        }
        return EventResult.HANDLED;                       // 其余按键一律吞掉，不落进输入框
    }

    /** 选择器面板：标题 + 每个模型一行（❯ 高亮当前、✓ 标记在用、右侧暗色说明）。 */
    private Element[] modelPickerChildren() {
        List<ModelOption> models = onSubmit.models();
        String cur = onSubmit.currentModel();
        List<Element> els = new ArrayList<>();
        els.add(text("  选择模型（↑↓ 选择 · Enter 确认 · Esc 取消）").style(PICK_TITLE));
        for (int i = 0; i < models.size(); i++) {
            ModelOption m = models.get(i);
            boolean sel = i == pickIndex;
            boolean active = m.id().equals(cur);
            String marker = (sel ? "❯ " : "  ") + (active ? "✓ " : "  ");
            els.add(text("  " + marker + (i + 1) + ". " + m.label() + "   " + m.desc())
                    .style(sel ? PICK_SEL : (active ? PICK_ITEM : PICK_DESC)));
        }
        return els.toArray(new Element[0]);
    }

    // ── /skill 技能选择器 ───────────────────────────────────────────────
    /** 打开技能选择器；无技能则提示不弹。默认高亮第一项。 */
    private void openSkillPicker() {
        List<SkillInfo> list = onSubmit.skills();
        if (list.isEmpty()) { state.setNotice("当前没有可用技能"); return; }
        pickIndex = 0;   // 挂载是一次性的，不像 /model 那样回定位到「当前项」
        pickingSkill = true;
    }

    /** 技能选择器按键：↑↓/kj 移动、数字快选、Enter 挂载、Esc 取消。始终 HANDLED。 */
    private EventResult onSkillPickerKey(KeyEvent k) {
        List<SkillInfo> list = onSubmit.skills();
        int n = list.size();
        if (k.isCancel()) { pickingSkill = false; return EventResult.HANDLED; }
        if (k.code() == KeyCode.UP || k.isChar('k'))   { pickIndex = (pickIndex - 1 + n) % n; return EventResult.HANDLED; }
        if (k.code() == KeyCode.DOWN || k.isChar('j')) { pickIndex = (pickIndex + 1) % n;     return EventResult.HANDLED; }
        for (int i = 0; i < n && i < 9; i++) {
            if (k.isChar((char) ('1' + i))) { pickIndex = i; return EventResult.HANDLED; }
        }
        if (k.code() == KeyCode.ENTER || k.isChar('\r') || k.isChar('\n')) {
            SkillInfo chosen = list.get(pickIndex);
            pendingSkill = chosen.name();   // 选中即在输入框上方显示技能标签（skillTag）作为反馈，不再单独打 notice
            pickingSkill = false;
            return EventResult.HANDLED;
        }
        return EventResult.HANDLED;
    }

    /** 技能选择器面板：标题 + 每个技能一行（❯ 高亮、名字 + 来源层 + 暗色描述）。 */
    private Element[] skillPickerChildren() {
        List<SkillInfo> list = onSubmit.skills();
        List<Element> els = new ArrayList<>();
        els.add(text("  选择技能（↑↓ 选择 · Enter 挂载 · Esc 取消）").style(PICK_TITLE));
        for (int i = 0; i < list.size(); i++) {
            SkillInfo s = list.get(i);
            boolean sel = i == pickIndex;
            String marker = sel ? "❯ " : "  ";
            els.add(text("  " + marker + (i + 1) + ". " + s.name() + "  [" + s.source() + "]   " + s.description())
                    .style(sel ? PICK_SEL : PICK_DESC));
        }
        return els.toArray(new Element[0]);
    }

    /** 已挂载技能标签：固定在输入框正上方。发送时随本条消息带走并自动清除；Esc 也可移除。 */
    private Element skillTag() {
        return text("  🎯 " + pendingSkill + "   （发送时自动加载 · Esc 移除）").style(PICK_TITLE);
    }

    // ── /mcp MCP 管理面板 ───────────────────────────────────────────────
    /** 打开 MCP 面板；无 server 声明则提示不弹。 */
    private void openMcpPicker() {
        List<McpRegistry.ServerView> list = onSubmit.mcpServers();
        if (list.isEmpty()) { state.setNotice("未配置 MCP server（.codetui/mcp.json）"); return; }
        pickIndex = 0;
        mcpExpanded = false;
        pickingMcp = true;
    }

    /** MCP 面板按键：↑↓/kj 移动、数字快选、Enter/Space 切换、Tab 展开工具清单、Esc 关闭。始终 HANDLED。 */
    private EventResult onMcpPickerKey(KeyEvent k) {
        List<McpRegistry.ServerView> list = onSubmit.mcpServers();
        int n = list.size();
        if (n == 0) { pickingMcp = false; return EventResult.HANDLED; }
        pickIndex = clampIndex(pickIndex, n);
        if (k.isCancel()) { pickingMcp = false; return EventResult.HANDLED; }
        if (k.code() == KeyCode.UP || k.isChar('k'))   { pickIndex = (pickIndex - 1 + n) % n; mcpExpanded = false; return EventResult.HANDLED; }
        if (k.code() == KeyCode.DOWN || k.isChar('j')) { pickIndex = (pickIndex + 1) % n;     mcpExpanded = false; return EventResult.HANDLED; }
        for (int i = 0; i < n && i < 9; i++) {
            if (k.isChar((char) ('1' + i))) { pickIndex = i; mcpExpanded = false; return EventResult.HANDLED; }
        }
        if (k.code() == KeyCode.TAB || k.isChar('\t')) { mcpExpanded = !mcpExpanded; return EventResult.HANDLED; }
        if (k.code() == KeyCode.ENTER || k.isChar('\r') || k.isChar('\n') || k.isChar(' ')) {
            toggleMcp(list.get(pickIndex));
            return EventResult.HANDLED;
        }
        return EventResult.HANDLED;
    }

    /** 切换一项：CONNECTED→同步禁用；DISABLED/FAILED→后台线程启用（连接秒级，不冻结渲染循环）。 */
    private void toggleMcp(McpRegistry.ServerView v) {
        if (mcpConnecting != null) return;                       // 已有连接在飞：忽略（一次一个）
        if (v.status() == McpRegistry.Status.CONNECTED) {
            var r = onSubmit.disableMcp(v.name());
            if (r != null && !r.persisted()) state.setNotice("已禁用（仅本次运行，写回配置失败）");
            return;
        }
        mcpConnecting = v.name();
        Thread t = new Thread(() -> {
            try {
                var r = onSubmit.enableMcp(v.name());
                if (r == null) return;
                if (!r.applied()) state.setNotice("MCP " + v.name() + " 连接失败：" + brief(r.error()));
                else if (!r.persisted()) state.setNotice("已启用（仅本次运行，写回配置失败）");
            } finally {
                mcpConnecting = null;                            // 渲染线程下一帧即看到
            }
        }, "mcp-enable");
        t.setDaemon(true);
        t.start();
    }

    /** 错误摘要截断（面板/notice 单行显示）。 */
    private static String brief(String s) {
        if (s == null) return "未知错误";
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }

    /** MCP 面板：标题 + 每 server 一行（状态标记/来源层/工具数/错误摘要），Tab 展开工具短名清单。
     *  高亮走纯前景 PICK_SEL（底色条会串到下一项，见 Theme.PICK_SEL 注释）。 */
    private Element[] mcpPickerChildren() {
        List<McpRegistry.ServerView> list = onSubmit.mcpServers();
        if (list.isEmpty()) return new Element[0];               // scope 每帧 eager 求值：首行判空
        int sel = clampIndex(pickIndex, list.size());
        List<Element> els = new ArrayList<>();
        els.add(text("  MCP 服务器（↑↓ 选择 · Enter 启用/禁用 · Tab 查看工具 · Esc 关闭）").style(PICK_TITLE));
        for (int i = 0; i < list.size(); i++) {
            McpRegistry.ServerView v = list.get(i);
            boolean isSel = i == sel;
            boolean connecting = v.name().equals(mcpConnecting);
            String mark = connecting ? "⟳" : switch (v.status()) {
                case CONNECTED -> "✓";
                case DISABLED -> "○";
                case FAILED -> "✗";
            };
            String layer = v.source() == McpConfigLoader.ConfigSource.PROJECT ? "[项目级]" : "[用户级]";
            String detail = connecting ? "连接中…" : switch (v.status()) {
                case CONNECTED -> "已连接 · " + v.toolCount() + " 工具";
                case DISABLED -> "已禁用";
                case FAILED -> "连接失败：" + brief(v.error());
            };
            els.add(text("  " + (isSel ? "❯ " : "  ") + mark + " " + (i + 1) + ". " + v.name()
                    + "  " + layer + " " + detail)
                    .style(isSel ? PICK_SEL : PICK_ITEM));
            if (isSel && mcpExpanded) {
                if (v.toolNames().isEmpty()) {
                    els.add(text("        （未连接，无工具信息）").style(PICK_DESC));
                } else {
                    for (String tn : v.toolNames()) {
                        els.add(text("        · " + tn).style(PICK_DESC));
                    }
                }
            }
        }
        return els.toArray(new Element[0]);
    }

    // 测试钩子
    boolean pickingMcpForTest() { return pickingMcp; }

    // ── AskUserQuestion 作答面板 ─────────────────────────────────────────
    /** 可作答性：至少 1 问、且每问至少 1 个选项（否则 onAskKey 的 `% n` 会除零崩线程，见 drain 的降级）。 */
    private static boolean isAnswerable(AskRequest ask) {
        List<QuestionSpec> qs = ask.questions();
        if (qs.isEmpty()) return false;
        for (QuestionSpec q : qs) {
            if (q.options().isEmpty()) return false;
        }
        return true;
    }

    /** 作答按键（单选）：↑↓/kj 移动高亮、1–9 移到第 n 项（不隐式确认）、Enter 选中进下一问、Esc 取消整回合。 */
    private EventResult onAskKey(KeyEvent k) {
        List<QuestionSpec> qs = activeAsk.questions();
        QuestionSpec q = qs.get(askQ);
        if (k.isCancel()) { cancelAsk(); return EventResult.HANDLED; }   // Esc 优先：子模式内也取消整回合
        if (askFreeText) {   // 自由文本子模式：可打印键输入、Backspace 删、Enter 确认非空、其余吞掉
            if (k.code() == KeyCode.ENTER || k.isChar('\r') || k.isChar('\n')) {
                String txt = askInput.text();
                if (txt.isBlank()) { state.setNotice("请输入内容"); return EventResult.HANDLED; }   // 状态行已自带「· Esc 取消」后缀
                askAnswers.put(q.question(), txt);
                askFreeText = false; askInput.clear();
                state.setNotice("");   // 清掉可能残留的「请输入内容」提示，避免带进下一问
                advanceOrFinish();
                return EventResult.HANDLED;
            }
            if (k.code() == KeyCode.BACKSPACE) { askInput.deleteBackward(); return EventResult.HANDLED; }
            // insert(String)：用 k.string()（Character.toChars）而非 char，兼容 U+FFFF 以上的星平面码点（emoji 等）
            if (k.code() == KeyCode.CHAR) { askInput.insert(k.string()); return EventResult.HANDLED; }
            return EventResult.HANDLED;   // 子模式吞掉其余键
        }
        int n = q.options().size() + (q.multiSelect() ? 0 : 1);   // 单选多一条合成「其他」；多选无
        if (k.code() == KeyCode.UP || k.isChar('k'))   { askOpt = (askOpt - 1 + n) % n; return EventResult.HANDLED; }
        if (k.code() == KeyCode.DOWN || k.isChar('j')) { askOpt = (askOpt + 1) % n;     return EventResult.HANDLED; }
        for (int i = 0; i < q.options().size() && i < 9; i++) {
            if (k.isChar((char) ('1' + i))) { askOpt = i; return EventResult.HANDLED; }   // 仅移动高亮（不跳合成「其他」行）
        }
        // 多选：空格切换当前高亮项勾选
        if (q.multiSelect() && k.isChar(' ')) {
            if (!askChecked.remove(askOpt)) askChecked.add(askOpt);
            state.setNotice("");   // 勾选后清掉「至少选择一项」的残留提示
            return EventResult.HANDLED;
        }
        if (k.code() == KeyCode.ENTER || k.isChar('\r') || k.isChar('\n')) {
            if (q.multiSelect()) {
                if (askChecked.isEmpty()) { state.setNotice("至少选择一项"); return EventResult.HANDLED; }
                List<String> picked = new ArrayList<>();
                for (int i : askChecked) picked.add(q.options().get(i).label());
                askAnswers.put(q.question(), String.join(", ", picked));   // 多选=逗号分隔（同 Claude Code）
            } else {
                if (askOpt == q.options().size()) {   // 高亮在合成的「其他」行 → 进自由文本子模式
                    askFreeText = true; askInput.clear();
                    return EventResult.HANDLED;        // 不记 label、不 advance
                }
                askAnswers.put(q.question(), q.options().get(askOpt).label());   // 记本问答案（单选=label）
            }
            advanceOrFinish();
            return EventResult.HANDLED;
        }
        return EventResult.HANDLED;   // 其余键一律吞掉，不落进输入框
    }

    /**
     * 作答态状态行文本：有 notice（如「至少选择一项」）时优先显示，否则按单/多选给操作提示。
     * 作答时 statusLine 会早于通用 notice 分支 return，故必须在这里自己回显 notice——否则空选确认的提示永远看不见。
     */
    String askStatusText() {
        if (askFreeText) {   // 自由文本子模式：单独提示（Esc 仍取消整回合）
            String fn = state.notice();
            if (fn != null && !fn.isEmpty()) return fn + " · Esc 取消";
            return "输入自定义内容 · Enter 确认 · Esc 取消";
        }
        String notice = state.notice();
        if (notice != null && !notice.isEmpty()) return notice + " · Esc 取消";
        boolean multi = activeAsk.questions().get(askQ).multiSelect();
        return multi ? "↑↓ 移动 · 空格勾选 · Enter 确认 · Esc 取消"
                     : "↑↓/kj 选择 · 1-9 快选 · Enter 确认 · Esc 取消";
    }

    /** 本问答完：还有下一问则前进（复位高亮），否则提交全部答案唤醒工具线程。 */
    private void advanceOrFinish() {
        if (askQ + 1 < activeAsk.questions().size()) {
            askQ++; askOpt = 0; askChecked.clear();
            askFreeText = false; askInput.clear();
            return;
        }
        AskRequest req = activeAsk;
        Map<String, String> answers = new HashMap<>(askAnswers);
        clearAskState();
        req.responder().answer(answers);   // 唤醒阻塞的工具线程
    }

    /** Esc 取消：唤醒工具线程 + 取消整回合（复用既有回合取消 → doOnCancel 回滚会话，不 400）。 */
    private void cancelAsk() {
        AskRequest req = activeAsk;
        clearAskState();
        cancelTurnFor(req, "已取消当前回合");
    }

    /**
     * 取消一个由问询发起的回合：先唤醒阻塞的工具线程（responder.cancel），再 dispose + cancelCurrent
     * 让 {@code doOnCancel} 回滚会话——否则半截的 {@code assistant(tool_calls)} 会残留、下条消息 400。
     * Esc 取消与「畸形问询降级取消」共用此路径，保证二者都走同一套已验证的回滚（见记忆
     * cancel-tool-turn-leaves-dangling-toolcalls）。
     */
    private void cancelTurnFor(AskRequest req, String notice) {
        req.responder().cancel();
        if (current != null) { current.dispose(); current = null; }
        state.cancelCurrent();
        state.clearQueued();
        state.setNotice(notice);
    }

    /** 清作答态并从 state 的模态队列摘除该问询（避免 drain 再次进入）。 */
    private void clearAskState() {
        AskRequest done = activeAsk;                 // 先取引用再置 null：否则 removeModal(null) 摘不掉，drain 会反复重入
        activeAsk = null; askQ = 0; askOpt = 0; askAnswers.clear(); askChecked.clear();
        askFreeText = false; askInput.clear();
        state.removeModal(done);
    }

    /** 作答面板：进度 + header + 问题文本 + 逐项选项（单选 ❯ 高亮）。 */
    private Element[] askChildren() {
        // scope(cond, el) 会「先构造 el 再按 cond 决定是否显示」——即本方法每帧都被调用（含非作答态），
        // 故必须先 null 判空，否则 activeAsk==null 时解引用会每帧崩渲染线程（单测只驱动按键、不跑 render，漏掉此路径）。
        if (activeAsk == null) return new Element[0];
        List<QuestionSpec> qs = activeAsk.questions();
        QuestionSpec q = qs.get(askQ);
        List<Element> els = new ArrayList<>();
        String progress = qs.size() > 1 ? "（第 " + (askQ + 1) + "/" + qs.size() + " 问）" : "";
        els.add(text("  ❓ [" + q.header() + "] " + q.question() + progress).style(PICK_TITLE));
        for (int i = 0; i < q.options().size(); i++) {
            OptionSpec o = q.options().get(i);
            boolean sel = i == askOpt;
            String box = q.multiSelect() ? (askChecked.contains(i) ? "[✓] " : "[ ] ") : "";
            els.add(text("  " + (sel ? "❯ " : "  ") + box + (i + 1) + ". " + o.label() + "   " + o.description())
                    .style(sel ? PICK_SEL : PICK_DESC));
        }
        if (!q.multiSelect()) {   // 单选追加合成「其他」行；进子模式时再回显输入
            int otherIdx = q.options().size();
            boolean sel = askOpt == otherIdx;
            els.add(text("  " + (sel ? "❯ " : "  ") + "✎ 其他（自定义输入）").style(sel ? PICK_SEL : PICK_DESC));
            if (askFreeText) {
                els.add(text("     ▏" + askInput.text()).style(PICK_TITLE));   // 输入回显
            }
        }
        return els.toArray(new Element[0]);
    }

    /** /help：把可用命令与快捷键打进 scrollback（灰色信息行）。 */
    private void printHelp() {
        state.pushInfo("可用命令：");
        for (SlashCommand c : COMMANDS) state.pushInfo("  " + c.name() + "   " + c.desc());
        state.pushInfo("快捷键：Enter 发送 · \\+Enter 换行 · Esc 取消 · Ctrl+C 退出");
        state.pushInfo("编辑：Ctrl+A/E 行首尾 · Ctrl/Alt+←→ 按词跳 · Ctrl+W 删前词 · Ctrl+U/K 删至行首/尾");
    }

    /** /reload：重扫两层技能目录后打一行结果 + 复用 {@link #printSkills} 展示最新清单（运行中增删 SKILL.md 即时生效，无需重启）。 */
    private void reloadSkills() {
        onSubmit.reloadSkills();
        int n = onSubmit.skills().size();
        state.pushInfo("↻ 已重新扫描技能目录：当前 " + n + " 个技能");
        printSkills();
    }

    /** /skills：把可用技能清单（名字 · 来源层 · 描述）打进 scrollback（灰色信息行）。 */
    private void printSkills() {
        List<SkillInfo> list = onSubmit.skills();
        if (list.isEmpty()) {
            state.pushInfo("当前没有可用技能。可在 .codetui/skills/<名字>/SKILL.md 添加后用 /reload 重新加载生效。");
            return;
        }
        state.pushInfo("可用技能（模型会按需自动调用）：");
        for (SkillInfo s : list) {
            state.pushInfo("  • " + s.name() + "  [" + s.source() + "]");
            state.pushInfo("      " + s.description());
        }
    }

    /**
     * 斜杠命令补全菜单：每个匹配命令一行（❯ 高亮、命令名 + 暗色说明），固定在输入框上方。
     * 高亮走<b>纯前景</b>（{@link Theme#PICK_SEL} 暖橙加粗，无底色）——本 TUI 的 InlineDisplay 下带底色的高亮条
     * 会「后半段串到下一项」（已用 pty 实机复现，见 {@link Theme#PICK_SEL} 注释），故不用底色条。
     */
    private Element[] slashMenuChildren() {
        List<SlashCommand> m = slashMatches();
        int sel = clampIndex(slashIndex, m.size());
        List<Element> els = new ArrayList<>();
        for (int i = 0; i < m.size(); i++) {
            SlashCommand c = m.get(i);
            String name = c.name();
            String pad = " ".repeat(Math.max(1, 10 - displayWidth(name)));   // 命令名对齐
            els.add(text("  " + (i == sel ? "❯ " : "  ") + name + pad + c.desc())
                    .style(i == sel ? PICK_SEL : PICK_ITEM));
        }
        return els.toArray(new Element[0]);
    }

    // ── 计划面板 / 状态行 ────────────────────────────────────────────────
    private Element[] todoChildren(List<String> todos) {
        List<Element> els = new ArrayList<>();
        els.add(text("📋 计划").style(TODO_TITLE));   // 主 agent（控制器）的 todo
        int shown = Math.min(todos.size(), TODO_CAP);
        for (int i = 0; i < shown; i++) els.add(todoRow(todos.get(i)));
        if (todos.size() > TODO_CAP) {
            els.add(text("  … 还有 " + (todos.size() - TODO_CAP) + " 项").style(DIM));
        }
        return els.toArray(new Element[0]);
    }

    /**
     * 任务面板：本回合派出的子 agent 状态。计数标题 + 可见子任务各一行（✓/▶/✗ 分色，纯前景无底色）；
     * 超 SUBTASK_CAP 时折叠靠前的、"当前运行"那条恒可见（串行下运行行总是最后一条）。
     */
    private Element[] subtaskChildren(List<ConversationState.SubtaskView> subs) {
        if (subs == null || subs.isEmpty()) return new Element[0];   // scope eager 求值：首行判空
        List<Element> els = new ArrayList<>();
        els.add(text(subtaskHeaderText(subs)).style(TODO_TITLE));
        List<ConversationState.SubtaskView> vis = visibleSubtasks(subs);
        int hidden = subs.size() - vis.size();
        if (hidden > 0) {                       // 折叠靠前的已完成条，注记在顶部
            els.add(text("  … 前 " + hidden + " 项已折叠").style(DIM));
        }
        for (ConversationState.SubtaskView s : vis) els.add(subtaskRow(s));
        return els.toArray(new Element[0]);
    }

    /** 面板可见子任务：末尾 SUBTASK_CAP 条。串行执行下"运行中"总是最后一条，取末尾保证它恒可见（否则大回合会把运行行折叠掉）。 */
    static List<ConversationState.SubtaskView> visibleSubtasks(List<ConversationState.SubtaskView> subs) {
        int from = Math.max(0, subs.size() - SUBTASK_CAP);
        return subs.subList(from, subs.size());
    }

    /** 一条子任务：✓完成=绿 / ▶运行=亮黄加粗 / ✗失败=红（纯前景）。 */
    private static Element subtaskRow(ConversationState.SubtaskView s) {
        Style st = switch (s.status()) {
            case DONE -> OK;
            case FAILED -> ERROR;
            case RUNNING -> TODO_RUN;
        };
        return text(subtaskRowText(s)).style(st);
    }

    /** 任务面板标题文本："⟐ 任务  ✓N 完成 · ▶M 运行[ · ✗K 失败]"。 */
    static String subtaskHeaderText(List<ConversationState.SubtaskView> subs) {
        long done = subs.stream().filter(s -> s.status() == ConversationState.SubtaskStatus.DONE).count();
        long running = subs.stream().filter(s -> s.status() == ConversationState.SubtaskStatus.RUNNING).count();
        long failed = subs.stream().filter(s -> s.status() == ConversationState.SubtaskStatus.FAILED).count();
        StringBuilder h = new StringBuilder("⟐ 任务  ✓" + done + " 完成 · ▶" + running + " 运行");
        if (failed > 0) h.append(" · ✗").append(failed).append(" 失败");
        return h.toString();
    }

    /** 一条子任务的行文本："  <图标> <agent>  <描述>[ · <当前工具>]"（运行态且有当前工具才附尾巴）。 */
    static String subtaskRowText(ConversationState.SubtaskView s) {
        String icon = switch (s.status()) {
            case DONE -> "✓";
            case FAILED -> "✗";
            case RUNNING -> "▶";
        };
        String tail = (s.status() == ConversationState.SubtaskStatus.RUNNING
                && s.currentTool() != null && !s.currentTool().isEmpty())
                ? " · " + s.currentTool() : "";
        return "  " + icon + " " + s.agentName() + "  " + s.description() + tail;
    }

    /** 排队消息面板：固定在输入框上方，每条一行（暗灰底、› 前缀、超宽截断），仿 Claude Code。 */
    private Element[] queuedChildren(List<String> queued) {
        List<Element> els = new ArrayList<>();
        int inner = Math.max(8, terminalWidth() - displayWidth(INDENT) - 2);   // 减缩进与 "› "
        for (String q : queued) {
            String oneLine = q.replaceAll("\\s+", " ").trim();
            if (displayWidth(oneLine) > inner) oneLine = dev.tamboui.text.CharWidth.substringByWidth(oneLine, inner - 1) + "…";
            els.add(text(INDENT + "› " + oneLine).style(QUEUED));
        }
        return els.toArray(new Element[0]);
    }

    /** 一条计划：✓完成=绿 / ▶进行中=亮黄加粗 / ○待办=暗。 */
    private static Element todoRow(String s) {
        Style st = s.startsWith("✓") ? OK : s.startsWith("▶") ? TODO_RUN : DIM;
        return text("  " + s).style(st);
    }

    private Element statusLine() {
        if (activeAsk != null) return text(askStatusText()).style(THINK);
        if (pickingModel) return text("↑↓/kj 选择 · 1-9 快选 · Enter 确认 · Esc 取消").style(THINK);
        if (pickingSkill) return text("↑↓/kj 选择 · 1-9 快选 · Enter 挂载 · Esc 取消").style(THINK);
        if (slashMenuActive()) return text("↑↓ 选择 · Tab 补全 · Enter 运行 · Esc 关闭").style(THINK);
        if (state.isCompacting()) return richText(statusBar.compacting(state.compactElapsedNanos(), animTick));   // 压缩指示器优先于普通思考/工具状态
        // 已挂载技能不再占状态栏——改由输入框正上方的技能标签（skillTag）常驻显示，见 render()。
        int q = state.queuedCount();
        String qs = q > 0 ? " · 已排队 " + q + " 条" : "";
        String notice = state.notice();
        if (!notice.isEmpty()) return text(notice + " · Ctrl+C 退出").style(THINK);
        // 空闲但仍有已取消子 agent 在收尾：给提示，避免「消息静默入队、无转轮」被误判为卡死（见 busy()/drainingSubagentsHint）。
        String draining = drainingSubagentsHint(state.isIdle(), onSubmit.hasInFlightSubagents());
        if (draining != null) return richText(statusBar.shimmer(draining, qs + " · Ctrl+C 退出", THINK, animTick));
        return switch (state.status()) {
            case IDLE -> text("Enter 发送 · /model 切换模型 · Esc 取消 · Ctrl+C 退出 · " + onSubmit.currentModel() + ctxUsage.suffix()).style(HINT);
            case THINKING -> richText(statusBar.shimmer("● 思考中…", qs + " · Esc 取消 · Ctrl+C 退出", THINK, animTick));
            case RUNNING_TOOL -> {
                String s = state.activeToolSummary();
                yield richText(statusBar.shimmer("⏺ 运行 " + state.activeTool() + (s.isEmpty() ? "" : ": " + s) + "…",
                        qs + " · Esc 取消", RUNNING, animTick));
            }
        };
    }

    // ── 内部工具 ─────────────────────────────────────────────────────────
    /** 取最后一个真实换行之后的残段（流式预览用；complete 行由 drain 下沉 scrollback）。 */
    private static String lastLine(String s) {
        int i = s.lastIndexOf('\n');
        return i < 0 ? s : s.substring(i + 1);
    }
}

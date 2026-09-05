package io.github.javaside.springai.codetui.ui;

import io.github.javaside.springai.codetui.agent.seam.AskRequest;
import io.github.javaside.springai.codetui.agent.seam.ModalRequest;
import io.github.javaside.springai.codetui.agent.llm.ProviderModel;
import io.github.javaside.springai.codetui.agent.llm.ModelPreference;
import io.github.javaside.springai.codetui.agent.seam.OptionSpec;
import io.github.javaside.springai.codetui.agent.seam.PermissionOutcome;
import io.github.javaside.springai.codetui.agent.seam.PermissionRequest;
import io.github.javaside.springai.codetui.agent.seam.PlanOutcome;
import io.github.javaside.springai.codetui.agent.seam.PlanRequest;
import io.github.javaside.springai.codetui.agent.seam.QuestionSpec;
import io.github.javaside.springai.codetui.agent.seam.SubmitHandler;
import io.github.javaside.springai.codetui.agent.mcp.McpConfigLoader;
import io.github.javaside.springai.codetui.agent.mcp.McpRegistry;
import io.github.javaside.springai.codetui.agent.permission.PermissionBehavior;
import io.github.javaside.springai.codetui.agent.permission.PermissionConfigLoader;
import io.github.javaside.springai.codetui.agent.permission.PermissionMode;
import io.github.javaside.springai.codetui.agent.permission.PermissionRule;
import io.github.javaside.springai.codetui.agent.permission.RuleScope;
import io.github.javaside.springai.codetui.agent.skill.SkillInfo;
import io.github.javaside.springai.codetui.agent.background.BackgroundNotifier;
import io.github.javaside.springai.codetui.agent.media.ArtifactSource;
import io.github.javaside.springai.codetui.agent.media.FileReference;
import io.github.javaside.springai.codetui.agent.media.MagicSniffer;
import io.github.javaside.springai.codetui.agent.media.MediaArtifact;
import io.github.javaside.springai.codetui.agent.media.MediaArtifactStore;
import io.github.javaside.springai.codetui.agent.media.PathContainment;
import io.github.javaside.springai.codetui.agent.thinking.ModelThinkingSettings;
import io.github.javaside.springai.codetui.agent.thinking.ThinkingCapabilities;
import io.github.javaside.springai.codetui.agent.thinking.ThinkingConfig;
import io.github.javaside.springai.codetui.agent.thinking.ThinkingMode;
import io.github.javaside.springai.codetui.agent.thinking.ThinkingStrengthKind;
import io.github.javaside.springai.codetui.ui.ConversationState.OutputLine;
import io.github.javaside.springai.codetui.ui.output.OutputCursor;
import io.github.javaside.springai.codetui.ui.output.PhysicalOutputQueue;
import io.github.javaside.springai.codetui.ui.output.PhysicalOutputQueue.PhysicalLine;
import io.github.javaside.springai.codetui.ui.update.ContextUsageRefreshController;
import io.github.javaside.springai.codetui.ui.update.UiDirty;
import io.github.javaside.springai.codetui.ui.update.UiUpdateCoordinator;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.buffer.Cell;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Frame;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
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
import dev.tamboui.tui.event.ResizeEvent;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.input.TextAreaState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
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
 * <em>每次重绘都按 {@code preferredSize} 调用 {@code setContentHeight}（可增可减）</em>。
 * Toolkit 的运行器正是这么做的：{@code InlineScopeElement} 隐藏时 {@code preferredSize=0}，
 * 于是 {@code column} 收缩 → 视口高度收缩 → 腾出的行被回收；而「重绘必与终端实际一致」让底层
 * {@code InlineDisplay} 的相对光标记账始终与终端实际对齐，从根上规避了此前手写渲染里「跳帧 + 收缩
 * (deleteLines)」导致的光标漂移、面板消失。事件驱动后没有常驻 tick：重绘只在
 * {@code requestUiUpdate / requestRender / 按键 / resize} 时发生（见 {@link #coordinator}）。
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
 * <p><b>定稿行下沉</b>：{@code pending} 与流式完整行在 UI 线程（事件驱动的一批
 * {@code processUpdates}，经 {@code UiUpdateCoordinator} 的合并投递，在两帧之间、非绘制中途执行）
 * 经物理输出队列交给 {@link ScrollbackPrinter} 用 {@code println} 推进 scrollback
 * （欢迎横幅 / 用户块 / 工具 diff / markdown 正文均在其中）。
 */
public final class CodeTuiView extends InlineApp {

    private static final Logger log = LoggerFactory.getLogger(CodeTuiView.class);

    /**
     * 单个 UI 批 drain 最多向 pty 写入的<b>物理行</b>数上限（burst 限速）。
     *
     * <p><b>为什么必须按物理行计</b>：一条 {@link ConversationState.OutputLine} 经折行 / diff 展开
     * 可以变成十几到上百个物理行，按「条数」限速等于没限——实测限 300 条长正文实际写出 4500 行。
     * 计数点是 sink 出口（见构造里的 {@code recording}），那里是所有 println 的唯一必经之路，
     * 数到的就是真实写进终端的行数。预算用尽即收手，剩下的留到下一批（内容不丢，只是渐进显示）。
     *
     * <p><b>为什么要限</b>：两条独立的后果。① 渲染线程在批里做 markdown/高亮/折行/println，
     * 一批几千行会把它占住数百 ms，期间<b>按键事件全部排队</b>——用户感知就是「输出的时候打字卡死」。
     * ② macOS Terminal.app 在短时间收到大量 pty 数据时会踩到自身的 use-after-free
     * （EXC_BAD_ACCESS / SIGSEGV，整个 Terminal 崩溃关闭）；输入法预编辑（中文打字）期间屏幕被高速
     * 滚动尤其危险，崩溃栈落在 {@code setMarkedText:} → {@code selectedRange}。工具结果
     * （尤其 BashOutput）与模型吐出的大代码块都能一次产生几千行，是最常见的触发场景。
     *
     * <p>限速到每批 300 行（~10KB/批，~4500 行/秒）：对正常大小的输出（≤300 行）无任何影响。
     */
    private static final int MAX_ROWS_PER_DRAIN = 300;
    private static final int TODO_CAP = 10;      // 计划面板（主 agent todo）最多显示几条
    private static final int SUBTASK_CAP = 6;    // 任务面板（子 agent 状态）最多显示几条
    private static final int SKILL_PICKER_CAP = 10; // 技能选择器可见行上限；避免大量技能撑高 InlineDisplay、触发终端反复重排
    // ⏱ 面板（后台任务）最多显示几条。这份列表<b>跨回合累积、只有 /clear 清</b>，已完成的永不移除：
    // 不封顶的话，一个会话派 20 个后台任务就常驻 21 行，把输入框一路顶下去。全量看 /tasks 面板。
    static final int BACKGROUND_CAP = 6;
    private static final String INDENT = "  ";  // 对话内容缩进；工具/计划行自带前缀
    // 配色 / 样式集中在 {@link Theme}，本类经 import static Theme.* 引入（DIM/HINT/PICK_SEL/… 写法不变）。

    private final ConversationState state;
    private final SubmitHandler onSubmit;
    /**
     * 「需要你看一眼」的边沿检测（BEL + tab 标题），见 {@link AttentionTracker}。
     * 与 {@link #bgPending} 一样只在 UI 线程（更新批 / 按键事件）读写。
     * 构造需 {@link #root}（项目名取目录最后一段），故在构造器体内赋值而非字段初始化器。
     */
    private final AttentionTracker attention;
    /**
     * 上一批到本批之间用户是否主动按 Esc 取消了回合（抑制「完成」铃声——他刚按过键，必然在场）。
     * 按键线程置位、UI 批消费后复位；volatile：按键与 UI 批可能不在同一线程。
     */
    private volatile boolean userCancelledSinceLastTick;
    private final TextAreaState inputState = new TextAreaState();    // 输入源（多行编辑模型）
    // 仅用于复用 textArea 的完整编辑键处理（退格/方向/Home/End/字符/中文…）。⚠ 从不渲染它——
    // 一旦渲染，TextAreaElement 会以自增 id 自注册进焦点链、抢走焦点，导致外层拦不到 Enter。
    private final Element inputKeys = textArea(inputState);
    private final StatusBar statusBar = new StatusBar();             // 状态行动画内容（波光/压缩条）渲染
    private final ScrollbackPrinter printer;                        // scrollback 打印（欢迎/用户块/工具 diff/助手正文）
    private final ContextUsage ctxUsage;                             // 上下文用量追踪/报告（/context 报告 + 状态栏后缀）
    // 上下文用量刷新的独立线程：token 估算在大会话下可达数百 ms（1MB+ 历史实测 ~300ms），
    // 绝不能占渲染线程——否则每 ~1s 一次的 refresh 会让输入/渲染周期性冻结（用户感知为"code-tui 卡死"）。
    // ContextUsage.cached 是 volatile，渲染线程读快照安全；单线程 + 1s 周期，任务不会积压。
    private final ExecutorService contextUsageExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "context-usage-refresh");
                t.setDaemon(true);
                return t;
            });
    /** 后台任务结果的自动送达判定与失控刹车（纯状态机，见其类注释）。只在 UI 线程（更新批）读写。 */
    private final BackgroundNotifier notifier = new BackgroundNotifier();
    /**
     * 上一次见到的终端列宽，用来从 ResizeEvent 里滤出「宽度真的变了」（拖高度不重排）。
     * 只在渲染线程读写（onStart 种基线、全局 handler 更新）。
     */
    private int lastSeenWidth;
    /** 包私有测试覆盖；生产保持 null，terminalWidth() 读取真实终端宽度。 */
    private Integer terminalWidthOverride;
    /**
     * 残行预览节流：流式输出中残行连续变化，若每次重绘都重画预览行，Terminal.app 的
     * 输入法合成（中文打字）会被高频 ANSI 打断而崩溃（EXC_BAD_ACCESS，崩溃栈在
     * NSTextInputContext/IMKInputSession）。预览行只允许每 ~150ms 更新一次，
     * 输出中的终端写入频率随之大降。渲染线程单线程访问，无需同步。
     */
    private static final long PREVIEW_THROTTLE_NANOS = 150_000_000L;
    private String lastPreviewedTail = "";
    private long lastPreviewAtNanos = 0L;
    // （fix round M-2）原 ResizeSettle 帧驱动停稳判定器已删除：事件驱动后没有每帧 tick 可
    // 喂拍，「等 4 帧无变化」无从谈起；停稳窗口由 coordinator.scheduleResizeSettle 的
    // 132ms 一次性任务 + generation 替换承担（onWidthChanged），类与字段一并移除。
    /**
     * resize 进行中（首个宽度变化事件 → 停稳重放完成）临时把硬件光标钉到显示区第 0 行。
     *
     * <p>硬件光标是 IME 预编辑串的锚点：Terminal.app 把拼音画在硬件光标处，<b>永久</b>钉第 0 行
     * （上一版做法）= 中文用户每次拼字，拼音浮在框顶边框上（用户实报「打字错位」）。平时停回
     * 文本行，只在 resize 窗口内钉 0 行，让终端 reflow 时相对光标记账不被上方折行推低——
     * 拖拽中通常也不会输入。停稳重放后立即恢复文本行锚点。
     *
     * <p>包私有：单测（同包）直接置位断言两种停放。生产只在渲染线程读写。
     */
    boolean parkCursorAtTop;
    /**
     * scrollback 输出留底（{@link Text} 或纯 {@link String}，与 Sink 两个重载一一对应）：
     * <b>存折行前的原始逻辑行</b>（fix round I-2 恢复的语义——严格分批第一版曾误存折行后物理段，
     * 变宽重放无法回流合并、配额被物理段稀释）。resize 停稳后清可见屏、从这里按新宽度重放最近
     * 一屏，救回被终端重排顶走的信息流。上限见 {@link #SCROLL_TAIL_CAP}（重放最多用到一屏行数，
     * 400 是宽裕量，防长会话无界增长；按<b>逻辑行</b>计——长逻辑行折出几百段也只占一条配额）。
     * 只在 UI 线程读写（sink 打印在输出批、resize 重放在 UI 线程的一次性任务里）。
     */
    private final ArrayDeque<Object> scrollTail = new ArrayDeque<>();
    private static final int SCROLL_TAIL_CAP = 400;

    /**
     * 严格分批的物理行输出队列（设计 §9.1/§9.2）：pending / 流式完整行 / 超预算计划正文都先进它，
     * {@link #processUpdatesInsideBatch} 每批按「物理行数 + 时间」双预算从队头游标逐行消费。
     *
     * <p><b>为什么换掉旧的 {@code rowsThisFrame} 软上限</b>：旧实现按「条」取，一条 {@link OutputLine}
     * 经 markdown/diff/折行展开是原子的——单个大输出（长正文 / 大 diff / 无换行超长行）一帧可写
     * 几百上千行，预算形同虚设（{@code DrainBurstCapTest} 曾靠 SLACK=200 容忍）。队列把逻辑输出
     * 变成可续消费的 {@link OutputCursor}，在取第 {@code MAX_ROWS_PER_DRAIN+1} 行之前停下，
     * 上限成为硬上限。活跃游标在队头，耗尽才移除——staging 任意时刻只物化一个逻辑项的当前逻辑行。
     */
    private final PhysicalOutputQueue outputQueue;

    /**
     * 单个 UI 批的时间预算（纳秒）——<b>全批共享一个 deadline</b>（fix round I-3）。
     * 行数预算（{@link #MAX_ROWS_PER_DRAIN}）之外的第二道闸：一行渲染即使很便宜，几千行叠起来
     * 也占 UI 线程数十 ms——期间按键全部排队。deadline 在批开始时计算一次，贯穿该批的
     * <b>所有</b>输出段（输出段 + 计划正文段），段与段之间不重开窗口——否则单批最坏 2×预算。
     *
     * <p>⚠ <b>12ms 未做实测标定</b>：取值依据是「输出 continuation 批间隔内留足按键/渲染余量」的
     * 工程判断，待 Terminal.app 实机验收（设计 §16）时以「输出期间按键延迟」为准回标。
     */
    private static final long MAX_DRAIN_NANOS = 12_000_000L;

    /**
     * 单个 UI 批从 {@code state.pending} 转入输出队列的 entry 数上限（fix round I-3）。
     *
     * <p><b>为什么要限</b>：极端积压（如 20 000 条 INFO 未消费）下，旧实现一批把全部
     * pending 转成 entry lambda——内存虽是 O(1)/条，但 20 000 次 {@code state.pollPending()}
     * 是一坨完全在时间预算之外的同步循环（渲染线程被白占，且不受任何 drain 预算约束）。
     * 有界转入把这笔开销摊到多个批；超出部分留在 {@code state.pending} 里，顺序不变、不丢内容
     * （消费完队头自然轮到它们）。
     *
     * <p><b>量级依据</b>：入队 entry 本身零渲染（cursor 工厂惰性），上限只防「积压瞬间转移」的
     * 突刺；600 ≈ 2× 物理行预算，正常体量（几十条）一批转完、无感。
     */
    private static final int MAX_PENDING_INTAKE_PER_TICK = 600;


    /**
     * 刹车期间探明的「确有结果被扣住」。状态栏那句提示读它，<b>不</b>每批去取列表。
     *
     * <p>为什么不能每批取：取列表会顺手做结果限幅 + 落盘（见
     * {@code CodingAgent.completedBackgroundTasks}——限幅刻意放在那个唯一入口上）。
     * 刹车踩着时每批取一次，就是对一份可能上百 KB 的报告反复做同步文件写，
     * <b>而且跑在渲染线程上</b>：TUI 会肉眼可见地卡，模型此刻去 Read 那个 artifact
     * 还可能读到正在被重写的中间态。
     */
    private boolean bgPending;
    /**
     * 本次刹车期间是否已经探过一次。踩下刹车后只探一次，放行时复位（见 {@link #releaseBrake()}）。
     *
     * <p><b>已知的陈旧窗口</b>：探明之后若又有后台任务完成，这句提示不会立刻更新，要等下一次
     * 用户输入。可以接受——放行刹车靠的正是用户输入，而"每批重探"恰恰是这里要消除的东西。
     */
    private boolean bgProbedWhileBraked;
    private final Path root;                                         // 工作区根目录（欢迎页展示）
    private Disposable current;
    private boolean pickingModel;                                    // /model 选择器是否激活
    private boolean configuringThinking;                             // /model 的二级思考设置是否激活
    private String thinkingTarget;                                   // 正在设置的模型 id
    private String thinkingTargetProvider;                           // 正在设置的模型所属 provider id
    private ThinkingConfig thinkingDraft;                            // 未保存的草稿
    private int thinkingRow;                                         // 二级面板当前行（0=模式，1=强度）
    private boolean editingBudget;                                   // 预算数值输入子模式
    private final TextAreaState budgetInput = new TextAreaState();   // 预算数值缓冲
    private boolean pickingSkill;                                    // /skill 选择器是否激活
    private boolean pickingMcp;                                      // /mcp 管理面板是否激活
    private boolean mcpExpanded;                                     // Tab 展开选中项工具清单
    private boolean pickingPerms;                                    // /permissions 规则面板是否激活
    private int permsIndex;                                          // 权限面板高亮项下标
    private PermissionRule permsPendingDelete;                       // 非 null = 删除确认态（待确认的那条规则）
    private boolean pickingTasks;                                    // /tasks 后台任务面板是否激活
    private int taskIndex;                                           // 任务面板高亮项下标
    private boolean taskExpanded;                                    // Enter 展开选中任务的结果正文
    private String taskPendingKill;                                  // 非 null = 终止确认态（待确认的 taskId）
    private volatile String mcpConnecting;                           // 非 null = 正在后台连接的 server 名（渲染线程读）
    private String pendingSkill;                                     // 已选技能名（可空）：显示为输入框上方标签，发送时随本条消息加载并清除
    private AskRequest activeAsk;                                     // 当前正在作答的问询（null=非作答态）
    private int askQ;                                                 // 当前问题下标
    private int askOpt;                                               // 当前问题内高亮的选项下标
    private final Map<String, String> askAnswers = new HashMap<>();   // 已答问题→答案
    private final Set<Integer> askChecked = new LinkedHashSet<>();     // 当前多选问题已勾选的选项下标（保序）
    private boolean askFreeText;                                      // 自由文本子模式（单选选了「其他」）
    private final TextAreaState askInput = new TextAreaState();       // 「其他」的自定义输入缓冲（单行直存直取）
    private PermissionRequest activePermission;                        // 当前正在审批的请求（null=非审批态）
    private int permOpt;                                              // 审批面板高亮项下标（选项数随 suggested() 变，见 permOptions）
    private PlanRequest activePlan;                                    // 当前正在审批的计划（null=非计划态）
    private int planOpt;                                              // 计划面板高亮项下标
    private boolean planFeedback;                                     // 自由文本子模式（选了「继续完善计划」）
    private final TextAreaState planInput = new TextAreaState();      // 反馈文本缓冲（单行直存直取，同 askInput）
    private int pickIndex;                                           // 选择器当前高亮项
    private int slashIndex;                                          // 斜杠命令补全菜单高亮项
    private boolean slashDismissed;                                  // Esc 关闭补全菜单（文本再变化前保持关闭）
    private final List<String> history = new ArrayList<>();          // 已提交消息历史（↑↓ 回溯）
    private int histIndex;                                           // 回溯指针；== history.size() 表示未回溯（草稿态）
    private String histDraft = "";                                   // 开始回溯前的输入草稿（Down 越过最新时恢复）
    private String lastShownModel = "";                              // 上次已提示的模型：仅在变化时再打 ⚙ 行
    private long animTick;                                           // 动画帧计数（Task 8：每个动画帧批自增，忙态 ~66ms 一批），驱动状态栏波光
    private final ImageAttachmentDetector imageDetector = new ImageAttachmentDetector();
    /** 本次输入是否已按 Ctrl+X 取消附件。清空输入框时复位（见 {@link #clearInput}）——否则取消一次就永久失效。 */
    private boolean attachmentsCancelled;
    // 识别结果的「按文本」记忆：render 每次重绘都跑，而 detectWithOverflow 要切词 +
    // 遍历每个词做路径解析。detector 自己按「路径+mtime」缓存了读盘嗅探，但切词/解析仍是每次重绘的
    // 开销，故这里再记一层：文本没变就直接复用。代价是「文本已打好、之后才把文件拷进那个路径」要等下一次
    // 击键才认出来——比每次重绘扫一遍文本划算得多。
    private String attachCacheText;
    private ImageAttachmentDetector.Result attachCache = ImageAttachmentDetector.Result.EMPTY;

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
            new SlashCommand("/permissions", "查看权限模式与生效规则"),
            new SlashCommand("/tasks",   "查看后台任务（可展开结果 / 终止）"),
            new SlashCommand("/continue", "继续执行上一批未完成的计划"),
            new SlashCommand("/queue",   "排到下一回合再发（默认 Enter 是立即插话）"),
            new SlashCommand("/help",    "显示可用命令与快捷键"),
            new SlashCommand("/exit",    "退出"));

    public CodeTuiView(ConversationState state, SubmitHandler onSubmit, Path root) {
        this(state, onSubmit, root, null);
    }

    /**
     * 测试专用构造：注入记录型 {@link ScrollbackPrinter.Sink}（见其接缝注释「测试=内存列表」）。
     *
     * <p>{@code null} = 生产用法，桥接 {@code runner()}。需要这个接缝是因为「计划正文进 scrollback」
     * 发生在 {@link #processUpdates} 里（非渲染态 {@code runner()} 为 null），只断言面板看不见它。
     */
    CodeTuiView(ConversationState state, SubmitHandler onSubmit, Path root, ScrollbackPrinter.Sink testSink) {
        this.state = state;
        this.onSubmit = onSubmit;
        this.root = root;
        // tab 标题的项目名：工作目录最后一段（如 springai-agentdemo）。root 是根路径（"/"）或
        // getFileName() 为 null 时退化为无项目名形式（AttentionTracker 内兜底）。多开 code-tui
        // 时 tab 靠它区分「哪个窗口在跑哪个项目」——这正是本参数存在的理由。
        this.attention = new AttentionTracker(
                root != null && root.getFileName() != null ? root.getFileName().toString() : "");
        // 惰性桥接 runner()：构造时不解引用。判空与 InlineApp.println 自身一致——UI 批里的
        // 计划正文下沉在测试态（未 start，runner()==null）也会跑到，不判空就是每个用例一发 NPE。
        ScrollbackPrinter.Sink sink = testSink != null ? testSink : new ScrollbackPrinter.Sink() {
            @Override public void println(Text t)   { var r = runner(); if (r != null) r.println(t); }
            @Override public void println(String s) { var r = runner(); if (r != null) r.println(s); }
        };
        // 包一层留底：每行进 scrollback 的同时进 scrollTail 环形缓冲（resize 停稳重放的素材，
        // 见字段注释）。⚠ 留底存<b>折行前</b>的原始逻辑行（fix round I-2，恢复 Task 5 之前的语义）：
        // 队列出口的每条物理行带 raw（其所属逻辑行原文），record(raw) 优先——变宽重放按新宽度
        // 重新折行时折行段能回流合并、400 条配额按逻辑行而不是物理段计。raw 为 null（欢迎横幅等
        // 自包含行）退回记录物理行本身。
        ScrollbackPrinter.Sink inner = sink;
        ScrollbackPrinter.Sink recording = new ScrollbackPrinter.Sink() {
            @Override public void println(Text t)   { record(t); inner.println(t); }
            @Override public void println(String s) { record(s); inner.println(s); }
        };
        this.printer = new ScrollbackPrinter(recording, root, this::terminalWidth);
        this.outputQueue = new PhysicalOutputQueue(printer::streamingLinesCursor);
        this.queueSink = new PhysicalOutputQueue.PhysicalSink() {
            @Override public void printlnPlain(String line, Object raw) {
                record(raw != null ? raw : line);
                inner.println(line);
            }
            @Override public void printlnStyled(Text line, Object raw) {
                record(raw != null ? raw : line);
                inner.println(line);
            }
        };
        this.ctxUsage = new ContextUsage(onSubmit::contextStats, state::pushInfo);
        // ── 事件驱动接线（设计 §6/§13.1）───────────────────────────────────
        // scheduler：coordinator 的按需一次性任务（continuation/preview/resize/animation）与
        // context-usage 防抖共用一个单线程 daemon 池。生产在 run() 后复用 runner().scheduler()
        // 是不对的——onStop 时 runner 先关、coordinator 还要排空，必须自己持有生命周期。
        // 构造即建：View 构造后立刻绑定变化源（见 bindChangeSources）。⚠ 早于 coordinator.start()
        // 的通知（如 MCP「connecting 进入」）会被 coordinator 直接丢弃（NEW 态 no-op）——
        // 接住启动前<b>状态</b>的不是这些通知，而是 onStart 的初始 ALL 同步 + render 每次重绘活读
        // 真相源（fix round M-1：绑定早只为 start 之后的通知不丢）。
        this.updateScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ui-update-scheduler");
            t.setDaemon(true);
            return t;
        });
        // UI update 投递口：生产桥 InlineTuiRunner.requestUiUpdate（Task 1；合并 + 完成后一次重绘）。
        // 构造时 runner()==null（run() 才有），未运行的 View（单测）投进测试队列受控执行；
        // 一旦 run()，后续投递全部走真实 runner。两条路都满足「每时刻至多一个已调度 update」
        // 的 coordinator 契约（CAS 赢家投一个）。
        Consumer<Runnable> uiSink = action -> {
            var r = runner();
            if (r != null) {
                r.tuiRunner().requestUiUpdate(action);
            } else {
                pendingUiUpdatesForTest.offer(action);
            }
        };
        this.coordinator = new UiUpdateCoordinator(uiSink, updateScheduler, this::processUpdates);
        this.ctxUsageController = new ContextUsageRefreshController(
                ctxUsage, contextUsageExecutor, updateScheduler,
                CONTEXT_USAGE_DEBOUNCE,
                () -> coordinator.onUiChanged(UiDirty.VIEW));
        bindChangeSources();
    }

    /**
     * 绑定两路变化源到 coordinator：{@code state.setUiChangeListener}（View 直绑，
     * CodingAgent 刻意不代绑——绑两次 = 同一通知进 coordinator 两次、批次翻倍）与
     * {@code onSubmit.setUiChangeListener}（CodingAgent fan-out 到 Interjections /
     * SubagentRunner / BackgroundTaskRegistry / McpRegistry）。
     *
     * <p>只在<b>构造期调用一次</b>（fix round M-1：onStart 不再调它——「onStart 幂等再绑定」
     * 的描述与实现不符，实现里 onStart 只做 coordinator.start()）。停止时以 {@code null} 解绑。
     */
    private void bindChangeSources() {
        state.setUiChangeListener(coordinator);
        onSubmit.setUiChangeListener(coordinator);
    }

    /** 队列出口 → 留底 sink + 终端（构造时固定；物理行已折好；留底记录折行前原文，见构造器注释）。 */
    private final PhysicalOutputQueue.PhysicalSink queueSink;

    // ── 事件驱动 UI（设计 §6/§8/§10/§13）────────────────────────────────
    /**
     * 合并与一次性调度中心：任意并发生产者的 {@code onUiChanged(bits)} 在这里合并为
     * <b>有限数量</b>的 UI update，每批调 {@link #processUpdates(int)}。不再有常驻 tick /
     * 66ms drain——空闲时零周期任务、零 ANSI 输出。
     */
    private final UiUpdateCoordinator coordinator;

    /** coordinator 按需一次性任务 + context-usage 防抖共用 scheduler（生命周期归本 View）。 */
    private final ScheduledExecutorService updateScheduler;

    /** 上下文用量的按需刷新（Task 6）：事件标脏 + 防抖 + 单飞，取代旧的 animTick % 30 周期刷。 */
    private final ContextUsageRefreshController ctxUsageController;

    /** context-usage 防抖窗口：突发标脏合并成一次刷新（旧刷新周期 ~1s，这里保持同量级）。 */
    private static final Duration CONTEXT_USAGE_DEBOUNCE = Duration.ofMillis(500);

    /**
     * resize 静默窗口：宽度停稳后一次性 settle + 全量重放（旧为 33ms×4 帧计数 ≈132ms，
     * 现由 coordinator 的一次性延迟任务直接表达同一时长；fix round M-2）。
     */
    private static final Duration RESIZE_SETTLE_DELAY = Duration.ofMillis(132);

    /**
     * 动画帧间隔（§10.3）。Task 8 已接线：批尾经
     * {@code coordinator.updateAnimationDemand(animationDemandActive(), ANIMATION_FRAME_DELAY)}
     * 接通——忙态保持至多一个在飞的 66ms 一次性帧任务（到期 publish VIEW、下一批按
     * {@code animationActive} 续排），状态消失立即取消，空闲无 timer。
     * 与旧 drain 周期（66ms）同值，波光/压缩条的视觉节奏不变。
     */
    private static final Duration ANIMATION_FRAME_DELAY = Duration.ofMillis(66);

    /**
     * 测试态 UI update 队列：生产把 {@code runBatch} 投给
     * {@code InlineTuiRunner.requestUiUpdate}；未 run 的 View（单测）投进这里，由
     * {@code runPendingUiUpdatesForTest()} 受控执行——等价事件循环跑一批。
     */
    private final java.util.concurrent.ConcurrentLinkedQueue<Runnable> pendingUiUpdatesForTest =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** 测试观测：onStart 是否已完成一次 UiDirty.ALL 初始全量同步。 */
    private volatile boolean initialAllSyncDone;

    /** 测试观测：欢迎横幅是否已打印（启动路径一次性）。 */
    private volatile boolean welcomePrinted;

    /** 本批 drain 已写出的物理行数（processUpdatesInsideBatch 内跨段共享 MAX_ROWS_PER_DRAIN 预算）。 */
    private int batchRowsUsed;

    /**
     * 本批的共享 drain deadline（绝对 nanoTime，{@code System.nanoTime()} 域；
     * {@code PhysicalOutputQueue.drain} 收到 ≤0 视为不限时）。
     * 在 processUpdatesInsideBatch 开头计算一次，本批的所有输出段共用（见 MAX_DRAIN_NANOS 注释）。
     */
    private long batchDeadlineNanos;

    /**
     * 消费一批输出队列：行预算 {@code budget}（≤0 直接 no-op）+ 本批的共享时间预算。
     *
     * @return 本段实际写出的物理行数（供后续段从同一预算里扣）
     */
    private int drainQueuedOutput(int budget) {
        if (budget <= 0 || outputQueue.isEmpty()) return 0;
        drainDeadlinesObserved.add(batchDeadlineNanos);   // 测试观测点：所有段必须是同一个绝对时刻
        return outputQueue.drain(budget, batchDeadlineNanos, queueSink).rowsWritten();
    }

    /**
     * 一条定稿 {@link OutputLine} 入输出队列：按 kind 选 printer 的 cursor 工厂（保持既有
     * markdown/diff/用户块样式语义），工厂惰性调用（drain 轮到它时才展开，diff 的读文件+LCS
     * 只做一次）。drain 输出段的 kind 分派与旧实现逐字对应。
     */
    private void enqueueOutputLine(OutputLine ol) {
        // ── 第 2 条 flush 触发点（设计 §3.4）：模型流水线上的行入队前，先把缓冲里的表格排出来 ──
        // 判据：这一块不会再有行进来。流里文本与工具调用串行，工具行之后模型不会回来补同一张表的下半截。
        // · SUBAGENT_* 由子 agent 线程异步发出、不满足「流里串行」，它安全的真实理由是父 TOOL_START
        //   （Task 工具）已经先 flush 过，此刻主模型阻塞在工具调用上、不可能在攒表格
        // · TODO 全仓零生产者（onTodoUpdated 只更新面板），列在这里纯属防御，是死分支
        // · ERROR 归这里而不是豁免：两个来源都意味着正文块已结束（turn 级 onError 发生在
        //   flushStreaming() 之后；模态队满 ERROR 由最外层 PermissionCallback 在工具调用时发出）。
        //   放进豁免的后果是「⚠ 出错」排在它要解释的那张表<b>之前</b>
        // · 豁免只有 INFO：UI 异步注入（/context、MCP 就绪、⏱ 后台任务、⚙ 使用模型 X），相对正文的
        //   位置本来就不确定；在这里 flush 会拼出「按只看过两行算出的列宽排好的半张表 + 原样的下半张」
        // ⚠ 必须<b>无条件</b>入队，不能写成 if (printer.hasBufferedTable())：入队时刻它前面那些
        //   ASSISTANT 行还压在队列里没喂给渲染器，此刻 hasBufferedTable() 必为 false，
        //   条件化就让整条触发点静默失效。它在 drain 时刻若发现缓冲是空的，自然就是个空游标。
        // ⚠ 必须走队列、不能直接 println：enqueueOutputLine 执行在<b>入队</b>时刻，直接打会让表格
        //   插到自己前面那些正文行<b>上面</b>去（方向是往前插，不是掉到后面）。
        switch (ol.kind()) {
            case USER, TOOL_START, TOOL_OK, TOOL_FAIL, ERROR,
                 SUBAGENT_START, SUBAGENT_TOOL, SUBAGENT_END, TODO ->
                    outputQueue.enqueue(v -> printer.tableFlushCursor());
            default -> { /* ASSISTANT 是正文本身、INFO 是豁免：都不 flush */ }
        }

        switch (ol.kind()) {
            case USER       -> outputQueue.enqueue(v -> printer.userBlockCursor(ol.text()));   // 灰底白字块
            case ASSISTANT  -> outputQueue.enqueue(v -> printer.assistantCursor(ol.text()));   // markdown + 缩进
            case TOOL_START -> outputQueue.enqueue(v -> printer.toolStartCursor(ol));          // edit/write diff
            default         -> outputQueue.enqueue(v -> printer.lineCursor(ol));               // 单色贴左
        }
    }

    /** 初始高度：圆角输入框(空态 3=边框2+1 行) + 状态行(1)；输入换行后 textArea 的 preferredSize 随行数增高，运行器每次重绘跟随。 */
    @Override
    protected int height() {
        return 4;
    }

    /** 构造一帧 UI 树（仅在被请求的重绘时调用）。scrollback 的 println 放在 UI 批（{@link #processUpdates}）里另行推进。 */
    @Override
    protected Element render() {
        List<String> todos = state.todoSnapshot();
        List<ConversationState.SubtaskView> subs = state.subtaskSnapshot();
        List<ConversationState.BackgroundView> bgTasks = state.backgroundTasks();
        List<String> queued = state.queuedSnapshot();
        // ⚠ 必须是非破坏性快照。接到 takePendingInterjections() 上的话，一次 render 就把队列清空，
        // 而面板看上去还很正常（它读的就是刚被自己清掉的那份）——插话再也送不到模型手里。
        List<String> interjections = onSubmit.pendingInterjectionTexts();
        // 流式当前残行（未换行段）。节流：内容变化且距上次预览 ≥150ms 才更新——
        // 输出中残行连续变化，预览行不节流就会每次重绘都重写（高频 ANSI → Terminal.app 输入法崩溃，
        // 见字段注释）。tail 为空（无流式）时立即清空，保证回合结束预览行马上消失。
        // （Task 8）这里是节流的<b>最终采纳点</b>：唤醒侧由 computeFollowUpFlags 的
        // schedulePreview(剩余窗口) 按需安排，到期批到达时本判定决定是否真换内容。
        String tail;
        String curTail = lastLine(state.streaming());
        long nowNanos = System.nanoTime();
        if (curTail.isEmpty()
                || !curTail.equals(lastPreviewedTail) && (nowNanos - lastPreviewAtNanos >= PREVIEW_THROTTLE_NANOS)) {
            lastPreviewedTail = curTail;
            lastPreviewAtNanos = nowNanos;
        }
        tail = lastPreviewedTail;
        return column(
                scope(!tail.isEmpty(), richText(printer.preview(tail)).ellipsisStart()),
                scope(!todos.isEmpty(), todoChildren(todos)),
                scope(!subs.isEmpty(), subtaskChildren(subs)),
                // ⏱ 后台任务面板（零任务时不占行）。/tasks 打开时收起：那个面板列的是同一批任务，
                // 两份并排只会把输入框往上顶，且用户分不清该按哪一份。
                scope(!bgTasks.isEmpty() && !pickingTasks, backgroundChildren(bgTasks)),
                // 未送达插话面板：排在排队面板<b>上方</b>，因为它先走（消费插话的段排在 pollQueued 之前）。
                // 两个面板的上下顺序即送达先后，看一眼就知道自己那句话什么时候会被听见。
                scope(!interjections.isEmpty(), interjectionChildren(interjections)),
                scope(!queued.isEmpty(), queuedChildren(queued)),   // 排队消息面板：固定显示在输入框上方
                scope(pickingModel && !configuringThinking, modelPickerChildren()),   // /model 选择器面板
                scope(configuringThinking, thinkingSettingsChildren()),   // /model 二级思考设置面板
                scope(pickingSkill, skillPickerChildren()),         // /skill 选择器面板
                scope(pickingMcp, mcpPickerChildren()),             // /mcp 管理面板
                scope(pickingPerms, permsPanelChildren()),          // /permissions 规则面板（可删）
                scope(pickingTasks, tasksPanelChildren()),          // /tasks 后台任务面板（可展开结果 / 终止）
                scope(activeAsk != null, askChildren()),            // AskUserQuestion 作答面板
                scope(activePermission != null, permissionChildren()),   // 权限审批面板
                scope(activePlan != null, planChildren()),          // 计划审批面板（正文在 scrollback，面板只放选项）
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
     *
     * <p><b>同时解绑焦点导航（Tab / Shift+Tab）</b>——不解绑则这两个键<b>根本到不了输入框</b>。
     * pty 实机抓到的现实：{@code EventRouter.routeKeyEvent} 把焦点导航排在最前，
     * {@code isFocusPrevious()/isFocusNext()} 命中后即便 {@code focusPrevious()} 失败
     * （本应用只有输入框一个可聚焦元素，next/prev 都回到自己 → 返回 false），它也<b>直接 return
     * UNHANDLED</b>，不会继续下发给焦点元素。后果有两条，都是实机才看得见的：
     * ① {@code Shift+Tab} 切不了权限模式；② 斜杠菜单的 Tab 补全与 MCP 面板的 Tab 展开
     * <b>一直是死的</b>（此前无人实机验过 Tab，单测走 {@code InputBox.handleKeyEvent} 绕过了路由器）。
     * 本应用没有多元素焦点链，焦点导航本就无意义，整组解绑即可，两个键随之落到 {@link #onInputKey}。
     */
    @Override
    protected InlineTuiConfig configure(int height) {
        InlineTuiConfig base = super.configure(height);
        return base.toBuilder()
                // 开启 bracketed paste：终端把整段粘贴用 ESC[200~ / ESC[201~ 包成单个 PasteEvent，
                // 多行文本里的 \n 不再被 EventParser 逐字节解析成 Enter → submitInput()。
                // 否则往输入框粘多行文本时，第一个换行处就会「提前提交」，后面的内容被拆成若干段
                // 依次插话/排队（用户看到的就是「提交了部分文本」）。见 InlineTuiRunner 对
                // config.bracketedPaste() 的处理（enableBracketedPaste / disableBracketedPaste）。
                //
                // ── 事件驱动切换（Task 7）：关掉常驻 tick ──
                // 旧 tickRate(100ms) 是「每 100ms 无条件全量重绘」的周期任务，正是本次重构删除的
                // 两个常驻周期之一（另一个是 onStart 里的 66ms drain）。此后重绘只由
                // requestUiUpdate / requestRender / 按键 / resize 触发（Task 1）；动画帧由
                // coordinator 按需一次性续排（ANIMATION_FRAME_DELAY），状态消失即停。
                // noTick() 置 tickRate=null，InlineTuiRunner 构造里 ticksEnabled=false →
                // 不再 scheduleAtFixedRate，scheduler 线程空闲。
                .noTick()
                .bracketedPaste(true)
                .bindings(base.bindings().toBuilder()
                        .rebind(KeyTrigger.ctrl('c'), Actions.QUIT)   // 整组替换：只剩 Ctrl+C，去掉 q/Q
                        .unbind(Actions.FOCUS_NEXT)                   // 让裸 Tab 落到输入框（补全/展开）
                        .unbind(Actions.FOCUS_PREVIOUS)               // 让 Shift+Tab 落到输入框（权限模式循环）
                        .build())
                .build();
    }

    @Override
    protected void onStop() {
        // ── 停止顺序（设计 §13.2 / brief Step 5）──
        // 1) 先停合并/调度中心：不再接受外部调度，取消 continuation/preview/animation/resize
        //    全部一次性 timer——之后任何迟到通知都是 no-op；
        coordinator.stop();
        ctxUsageController.stop();
        // 2) 解绑变化源：state 与 onSubmit fan-out 都指向 no-op，Agent 线程的迟到写入不再投递；
        //    解绑不等于丢数据——业务数据仍在 state/队列里，只是没有 UI 在听了。
        state.setUiChangeListener(null);
        onSubmit.setUiChangeListener(null);
        // 3) 关闭本 View 持有的执行设施（context-usage 单飞池 + 按需任务 scheduler）。
        contextUsageExecutor.shutdownNow();
        updateScheduler.shutdownNow();
        // 4) 至此真正的终端恢复（raw mode 复位、光标恢复等）已在<b>更早</b>的
        //    InlineTuiRunner.close() 里完成——InlineApp.run() 的 try-with-resources 先
        //    close runner 再调本 onStop（上游 InlineApp.java:121-133）。super.onStop()
        //    只是空基类钩子。/clear 与退出的后台语义、MCP 关闭归属不变
        //    （shutdownAndQuit 仍只调 shutdownBackground；MCP 由 CodeTuiApplication 的
        //    try/finally 关闭）。
        super.onStop();
    }

    @Override
    protected void onStart() {
        // 终端改宽度时只登记最新宽度；不在每个事件里清屏，避免 Windows Terminal 显示
        // 「清空后尚未重画」的中间帧。InlineTuiRunner 仍处理事件并按新宽度构造当前 live 帧，
        // 约 132ms 无新事件后（coordinator 一次性 settle，fix round M-2）统一清屏、按新宽度
        // 重放 scrollback。
        lastSeenWidth = terminalWidth();
        runner().eventRouter().addGlobalHandler(event -> {
            if (event instanceof ResizeEvent re && re.width() > 0 && re.width() != lastSeenWidth) {
                lastSeenWidth = re.width();
                onWidthChanged();                 // 合并连续事件，停稳后只清屏重放一次
            }
            return EventResult.UNHANDLED;   // 只旁观，别拦事件：库还要靠它触发重画
        });
        // 变化源绑定已在构造期完成（一次性；start 之后的通知——含 MCP 后台连接的
        // 迟到状态——因此一条不丢）。⚠ 早于此刻 coordinator.start() 的通知已被 NEW 态
        // no-op 丢弃：接住启动前<b>状态</b>的是随后的初始 ALL 同步 + render 活读，不是通知。
        // 此刻 runner 已就位，uiSink 从测试队列切到真实 requestUiUpdate。
        coordinator.start();
        // 启动期显式 markDirty 一次：空会话到首条消息之间也要有首刷（否则状态栏上下文用量
        // 一片空白，直到第一次 turn 结束才出现）。防抖窗口内合并，只产生一次后台刷新。
        ctxUsageController.markDirty();
        // UI 线程打印欢迎横幅（一次性下沉 scrollback）。onStart 在 run() 之前、同一线程执行，
        // 但事件循环尚未起步（RenderThread 标记还没打）——runOnRenderThread 因此走「入队」
        // 分支，让它在循环内、两帧之间安全打印（println 会移动光标/插行，不能抢在首帧绘制前
        // 直接写屏）。与旧实现同一投递路径，行为不变。
        runner().runOnRenderThread(() -> {
            printer.welcome(onSubmit.currentModel(),
                    io.github.javaside.springai.codetui.AppInfo.versionLabel());
            welcomePrinted = true;
            // 初始 tab 标题（启动即设，不等第一次 alert）：多开 code-tui 时 tab 一打开就能区分
            // 哪个窗口在跑哪个项目。复用 restore()——它就是「写标题但不响 BEL」，语义正好；
            // 失败静默降级（TerminalAttention 契约）。退出不恢复：退出后残留的项目名反而有信息量
            // （回顾这个 tab 刚跑过什么），也省一条 OSC。
            TerminalAttention.restore(runner(), attention.defaultTitle());
        });
        // ── 初始全量同步（设计 §13.1 第 5 步）──
        // MCP / 恢复历史 / 权限提示可能在 View 运行前写入 state（绑定虽在构造期，但生产路径
        // CodeTuiApplication 在 view.run() 之前就 pushInfo/replayHistory）。一次 ALL 批把
        // 这些「既有存量」全部消费掉：pending 下沉、模态侦测、面板首绘、首次 render。
        // 之后完全由事件驱动。
        publishInitialAllSync();
    }

    /** 宽度变化（ResizeEvent）：钉光标 + 安排一次性 settle（132ms 静默窗口，generation 替换）。 */
    private void onWidthChanged() {
        parkCursorAtTop = true;
        // 按需一次性 settle（设计 §10.2）：132ms 静默后经 requestUiUpdate 在 UI 线程重放一次。
        // 新调用整体替换旧的（coordinator generation 语义）——连续拖拽只重放最后一次。
        // （fix round M-2）停稳判定不再用帧计数器（ResizeSettle 已删）：静默窗口本身就是
        // generation 替换语义——窗口内新事件不断重排，静默满 132ms 才执行最后一次。
        coordinator.scheduleResizeSettle(RESIZE_SETTLE_DELAY, this::settleResizeOnUiThread);
    }

    /**
     * 测试专用（Task 8）：等价一次宽度变化事件（钉光标 + 安排一次性 settle）。
     * 生产由 onStart 挂的 ResizeEvent global handler 触发；测试态没有事件循环，
     * 直接调用同一方法体。
     */
    void onWidthChangedForTest() { onWidthChanged(); }

    /** resize 停稳后的 UI 线程动作：全量重放 + 恢复 IME 光标锚点。 */
    private void settleResizeOnUiThread() {
        try {
            replayAfterResize();               // 抹整屏含回滚缓冲、按新宽度全量重放留底
        } finally {
            parkCursorAtTop = false;           // 即使重放降级或异常，也必须把 IME 锚点放回文本行
        }
        requestRenderOnce();
    }

    // ── 事件驱动的一批 UI 更新（UI 线程；设计 §8）─────────────────────────
    /**
     * 一个有界 UI 批：在物理行与时间预算内消费 pending / 流式完整行，随后同步模态、推进
     * attention、空闲时自动送达，最后返回 demand-driven follow-up 需求。
     *
     * <p><b>严格保持旧 {@code drainInsideBatch} 的顺序</b>（设计 §8 / brief Step 4）：
     * <ol>
     *   <li>stage/consume pending 与流式完整行；</li>
     *   <li>drain 一个严格物理批；</li>
     *   <li>同步模态身份 / 畸形问询降级 / 进入新模态（计划正文同批下沉）；</li>
     *   <li>推进 attention 边沿；</li>
     *   <li>重读 busy（自动出队执行点完整重读闸门）；</li>
     *   <li>未送达插话，否则 queued，否则后台结果；</li>
     *   <li>返回 coordinator flags：outputRemaining / animationActive；preview 由 View 在批尾直调调度。</li>
     * </ol>
     *
     * <p><b>绝不循环到空</b>：输出存量未清空由 coordinator 的 continuation 排空（§9.2），
     * 动画帧由 animationActive 续排（§10.3），本方法每批只做一段有界工作。
     *
     * <p>{@code dirtyBits} 只用于「本批要不要做某段」的粗闸（如纯 VIEW 批不做输出消费的
     * 大转移也仍要保证 modal/attention 同步——CONTROL 位），语义上是提示而非真相；真相
     * 永远从 state 重读。
     */
    private UiUpdateCoordinator.UpdateResult processUpdates(int dirtyBits) {
        // InlineRenderBatch.open 失败自降级为 NOOP、其 closeable 的 close 失败自记 debug
        // （均在该类内部兜住、不上抛）——因此这个 catch 只可能来自 processUpdatesInsideBatch
        // 的业务异常（fix round I-1），close 仍由 try-with-resources 正常执行（收尾不丢）。
        try (AutoCloseable ignored = InlineRenderBatch.open(runner())) {
            UiUpdateCoordinator.UpdateResult result = processUpdatesInsideBatch(dirtyBits);
            consecutiveBatchFailures.set(0);   // 整批成功跑完（含各段早退路径）：连续失败序列结束
            return result;
        } catch (Exception e) {
            return handleBatchFailure(e, dirtyBits);
        }
    }

    /**
     * 批处理业务异常的自我恢复（fix round I-1）。
     *
     * <p><b>为什么必须在 View 侧处理而不是上抛</b>：coordinator 的 {@code runBatch} 在调
     * processor 之前已把 dirty bits <b>取走</b>（getAndSet(0)）——异常上抛给 InlineTuiRunner
     * 的 Throwable 防护只会记一条日志，本批该消费的 pending / 模态同步 / 自动出队全部丢失，
     * 且空队列路径上没有任何兜底重排（旧世界 66ms tick 会自动重试；事件驱动后只能等下一个
     * 无关事件）。故这里 warn 记录 + 补发一次 {@code UiDirty.ALL} 让下一批重跑。
     *
     * <p><b>防异常风暴</b>：同一连续失败序列最多补发 {@link #MAX_BATCH_FAILURE_RETRIES} 次
     * （连续失败计数，任一成功批清零）。持续失败时停止补发、pending 原样保留——下一个真实
     * 生产者事件仍会触发新批（计数不清零，直到成功才恢复补发资格）。
     *
     * <p>返回 {@link UiUpdateCoordinator.UpdateResult#idle()}：批没跑完，follow-up 需求
     * 不可知；声明 remaining 会让 continuation 盲目重跑（若异常在输出段之前，队列本就是空的）。
     * {@code InlineRenderBatch} 自身的 open/close 失败不在此列——那是无害降级，在该类内部
     * 保持 debug 级记录。
     */
    private UiUpdateCoordinator.UpdateResult handleBatchFailure(Exception e, int dirtyBits) {
        int failures = consecutiveBatchFailures.incrementAndGet();
        if (failures <= MAX_BATCH_FAILURE_RETRIES) {
            log.warn("UI 批处理失败（第 {}/{} 次连续失败），补发一次全量重试批 dirtyBits={}",
                    failures, MAX_BATCH_FAILURE_RETRIES, dirtyBits, e);
            coordinator.onUiChanged(UiDirty.ALL);
        } else {
            log.warn("UI 批处理连续失败 {} 次，停止补发重试（等待下一个真实事件）；未消费状态保留",
                    failures, e);
        }
        return UiUpdateCoordinator.UpdateResult.idle();
    }

    /** 连续批处理失败计数（任一成功批清零）。补发重试的防风暴闸。 */
    private final java.util.concurrent.atomic.AtomicInteger consecutiveBatchFailures =
            new java.util.concurrent.atomic.AtomicInteger();

    // ── pty 写背压门控（「输出时打字卡死」根治的应用层配套，见 OutputBackpressureGateTest） ──
    /**
     * pty writer 饱和闸（volatile：测试/生产 UI 线程置位，批处理读）。
     *
     * <p>库层（springai-tamboui-inline-patch）已把 pty 写剥离到 AsyncPtyWriter 守护线程，
     * 渲染线程不再睡死在 write(2) 上；代价是 display 侧出现「延迟批」队列。应用层的
     * 配套纪律：<b>闸关闭（writer 饱和）时本批不产出新输出</b>——pending / 流式完整行
     * 原样留在 state，等库层「排空唤醒」（onDrained → requestRender → 下一批）接续。
     * 否则模型持续吐 token 会无限往延迟队列攒批，内存无界。
     *
     * <p>生产每批从 {@link #ptyBackpressured()} 读真实 writer 状态；测试经
     * {@link #setOutputBackpressuredForTest(boolean)} 直控。
     */
    private volatile boolean outputBackpressuredForTest;

    /** 测试控制：直控背压闸（生产每批活读 pty writer 饱和态）。 */
    void setOutputBackpressuredForTest(boolean saturated) {
        this.outputBackpressuredForTest = saturated;
    }

    /**
     * pty writer 当前是否饱和：直接问 runner 的公开查询口（patch 模块是 compile
     * 依赖，无需反射——首版反射链 {@code getMethod("display")} 对包私有方法必抛
     * NoSuchMethodException，闸从未生效，审核 B1）。无 runner（测试态）时恒 false。
     * 测试态（{@link #outputBackpressuredForTest} 置位）优先。
     */
    private boolean ptyBackpressured() {
        if (outputBackpressuredForTest) {
            return true;
        }
        var r = runner();
        return r != null && r.tuiRunner().isPtyWriteSaturated();
    }

    /** 设备死亡一次性处理标记（见 computeFollowUpFlags 的死亡检测）。 */
    private volatile boolean ptyDeathHandled;

    /** pty writer 是否已死（设备死亡检测，见 computeFollowUpFlags；无 runner 恒 false）。 */
    private boolean ptyWriterDead() {
        var r = runner();
        try {
            return r != null && r.tuiRunner().isPtyWriterDead();
        } catch (RuntimeException | LinkageError e) {
            // LinkageError：patch jar 与 tamboui-tui 版本错配（方法缺失）——TerminalAttentionTest
            // 的结构钉在测试期就该红灯；线上按「未死」降级（终审 minor）。
            return false;
        }
    }

    /**
     * pty 写背压退避间隔：outputRemaining 的<b>唯一</b>成因是 writer 饱和时
     * （应用闸已关、queue/pending/streaming 全空），continuation 不用 ZERO——
     * ZERO 会与「每圈一次 render」形成双线程满载空转（终审 e），最坏在终端
     * 长时间停摆期间持续数分钟。退避期间链不断：writer 排空的武装唤醒
     * （armWakeup → requestRender → 下一批）保证及时接续。
     */
    private static final java.time.Duration PTY_BACKPRESSURE_BACKOFF = java.time.Duration.ofMillis(30);

    /** 同一连续失败序列最多补发的重试批数（fix round I-1：有界重试，防「失败→补发→再失败」循环）。 */
    private static final int MAX_BATCH_FAILURE_RETRIES = 2;

    private UiUpdateCoordinator.UpdateResult processUpdatesInsideBatch(int dirtyBits) {
        lastDirtyBitsForFlag = dirtyBits;   // computeFollowUpFlags 的 context-usage 判据
        processedBatches.incrementAndGet();
        // 动画帧计数：每批自增一次。忙态下批由动画帧 timer 驱动（每 ~66ms 一批，Task 8），
        // 波光/压缩条恢复动态；空闲时没有批，计数静止——与「无周期任务」不冲突。
        animTick++;
        // ── 本批共享预算（fix round I-3 语义保留）：deadline 只算一次，贯穿下方所有输出段 ──
        batchDeadlineNanos = System.nanoTime() + MAX_DRAIN_NANOS;
        batchRowsUsed = 0;
        drainDeadlinesObserved.clear();
        pendingIntakeCount = 0;
        // ── 输出段（无 OUTPUT 位闸门：每批无条件做转入——存量清不清空由队列/pending 的
        // 真实状态决定，纯 VIEW 批跑一遍空循环无副作用。fix round M-1：删除原「只在
        // OUTPUT 位置位或队列未清空时做转入」的不存在闸门描述）──
        // pty 写背压闸（「输出时打字卡死」根治配套）：writer 饱和时本批不产出新输出——
        // pending / 流式完整行原样留在 state，等库层「排空唤醒」驱动的后续批接续。
        // 否则模型持续吐 token 会无限往 display 延迟队列攒批（内存无界）。
        boolean ptySaturated = ptyBackpressured();
        // pending 转入有界（fix round I-3）：每批最多 MAX_PENDING_INTAKE_PER_TICK 条，
        // 剩余留在 state.pending 等后续批（顺序不变、不丢内容）——continuation 会接续消费。
        for (OutputLine ol; !ptySaturated && pendingIntakeCount < MAX_PENDING_INTAKE_PER_TICK
                && (ol = state.pollPending()) != null; ) {
            enqueueOutputLine(ol);
            pendingIntakeCount++;
        }
        // 流式完整行按「批预算条数」取（渲染仍是逐行惰性）；只在本批输出队列已排空时才取——
        // 上一批没打完的输出在时序上更早，不该被新流式行插队。
        if (!ptySaturated && outputQueue.isEmpty()) {
            List<String> rows = state.takeCompleteStreamingLines(MAX_ROWS_PER_DRAIN);
            if (!rows.isEmpty()) {
                outputQueue.enqueueStreamingLines(rows);
            }
        }
        batchRowsUsed = drainQueuedOutput(MAX_ROWS_PER_DRAIN);   // 与计划正文段共用本批预算
        ModalRequest head = state.peekModal();
        // 正在审批的请求已不在队首 → 它被别的线程摘走了（cancelCurrent/clearModals 已给它的线程投过 CANCEL，
        // 线程醒了、回合也结束了）。面板不能继续挂着一个没人在等的请求，就地退出模态；本批可直接接上新队首。
        // ⚠ 认引用不认相等：两个请求都是 record，分量相同的另一个请求 equals 为真，认相等会把面板留在错误的请求上。
        if (activePermission != null && head != activePermission) {
            activePermission = null;
            permOpt = 0;
        }
        if (activePlan != null && head != activePlan) {   // 同上，计划模态一份（认引用不认相等）
            resetPlanUi();
        }
        // 侦测到新模态（身份不同）→ 进入对应模态并复位状态。
        // sealed 的穷尽性在 release=17 由人保证：按类型 switch 要 21，故用 instanceof 链（见 ModalRequest 类注释）。
        // ⚠ 新增 ModalRequest 子类型时必须在这里补一条分支：漏了既不是编译错误也没有测试会自动报警，
        //   而后果是面板永不弹出、工具线程持着回合永久 park（agent 静默挂死）。每条分支都有渲染断言钉着。
        if (head != null && head != activeAsk && head != activePermission && head != activePlan) {
            if (head instanceof AskRequest pa) {
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
            } else if (head instanceof PermissionRequest pr) {
                activePermission = pr;
                permOpt = 0;
            } else if (head instanceof PlanRequest pl) {
                activePlan = pl;
                planOpt = 0;
                planFeedback = false;
                planInput.clear();
                printPlan(pl.plan());   // 正文进 scrollback（几十行塞进行内面板会把输入框顶出屏幕）
                // 计划正文在侦测到模态的<b>同一批</b>里开始下沉（旧语义保留）；行预算与本批前段
                // 共用（MAX_ROWS_PER_DRAIN），时间预算也共用同一个 deadline——剩余行留在队列里
                // 等下一批（continuation），单批上限仍是硬上限。
                batchRowsUsed += drainQueuedOutput(MAX_ROWS_PER_DRAIN - batchRowsUsed);
            }
        }
        // ── 终端注意提示（tab 标题 + BEL，仿 Claude Code）──
        // 放在出队之前：出队路径可能提前返回，跳过它这一拍就不完整（下一拍边沿已经错过）。
        advanceAttention(head != null || activeAsk != null || activePermission != null || activePlan != null);
        // ── 自动出队（自动出队执行点完整重读 busy 闸门）──
        // submit() 同步置 THINKING，故本批只会出队一条，无重复提交竞态。
        if (!busy()) {   // 空闲、非压缩中、且无在飞子 agent 才出队（见 busy()）
            // 兜底：用户在<b>收尾流</b>期间插的话没赶上 inject()（最后一次模型调用早已发出），
            // 不接住的话它会一直躺在队列里，直到下次发消息才被捎走、且那时 anchor 语义已不对。
            // ⚠ 只取未送达的：已送达那条归 handleComplete 补历史，抢走会让同一句话发两遍。
            // ⚠ 排在 pollQueued 之前——插话在时序上更早。
            List<String> leftover = onSubmit.takePendingInterjections();
            if (!leftover.isEmpty()) {
                releaseBrake();
                dispatch(String.join("\n", leftover), null);
                return computeFollowUpFlags();   // 本批已提交：后台送达让到下一批（用户排的队优先）
            }
            ConversationState.Queued next = state.pollQueued();
            if (next != null) {
                releaseBrake();              // 用户的真实输入：重置自动回合刹车
                dispatch(next.text(), next.skill());
                return computeFollowUpFlags();   // 本批已提交：后台送达让到下一批
            }
            deliverBackgroundResults();
        }
        return computeFollowUpFlags();
    }

    /**
     * 批尾 follow-up 需求（brief Step 4 第 7 步）。
     *
     * <p><b>continuation 单一调度方（fix round I-3）</b>：本方法只<b>声明</b>
     * {@code outputRemaining}，不直接调 {@code coordinator.scheduleOutputContinuation}——
     * 生产路径唯一调度方是 coordinator 的 {@code runBatch}（收到 true 后自己排 ZERO 延迟
     * 一次性任务）。View 侧再排一份等于双重所有权：两条链并行、批次翻倍、封顶语义失真。
     * {@code coordinator.scheduleOutputContinuation} 仍保留为测试直连 seam
     * （{@code UiUpdateCoordinatorTest} 用它钉 coordinator 侧的排空契约）。
     *
     * <ul>
     *   <li><b>outputRemaining</b>：队列非空或 state 仍有 pending / 流式完整行 → runBatch
     *       安排一次性 continuation（§9.2），无新生产者事件也最终排空；</li>
     *   <li><b>preview demand</b>：流式残行非空且与最近已采纳内容不同 → View 直调
     *       {@code schedulePreview} 按需排一次到期；已采纳的静止残行不续排，下一 token 的
     *       OUTPUT 事件会重新启动（§10.1）；</li>
     *   <li><b>animationActive</b>：仍在动态状态（忙 / 压缩 / 后台任务运行中）→ 下方接通
     *       {@code updateAnimationDemand(true, ANIMATION_FRAME_DELAY)}（§10.3，Task 8 接线）；
     *       静止时立即 false，无任何周期任务。</li>
     * </ul>
     *
     * <p>context-usage 标脏不经 UpdateResult（其 {@code contextUsageDirty} 字段已删，
     * fix round I-3：runBatch 从不消费它，路由过去只是死参数）：真实接线在下方直接调
     * {@code ctxUsageController.markDirty()}（Task 6），保持 View 私有。
     */
    private UiUpdateCoordinator.UpdateResult computeFollowUpFlags() {
        // 「输入已排空」= 输出队列空 + 没有待转入的 pending + 没有已成行的流式行。
        // 第 4 条 flush 触发点与 continuation 退避判定共用这三项（见下）。
        boolean localWorkRemaining = !outputQueue.isEmpty()
                || state.hasPendingOutput()
                || state.hasCompleteStreamingLine();
        // ── 第 4 条 flush 触发点（设计 §3.4）：回合结束，或 UI 为用户暂停 ──
        // 检查点必须在本批<b>主 drain 之后</b>：放在取流式行那一带（drain 之前）时，最后一批的时序是
        // 「pending 刚把表格尾行转入 → 队列非空 → 闸门 false → drain 把它喂进缓冲」，而批尾
        // outputRemaining 的四项全 false、IDLE 且无后台任务/在飞子 agent 时 animationDemandActive()
        // 也 false，于是<b>不再排下一批</b>：表格要么靠 ctxUsage 的 500ms 防抖偶然救回（晚半秒），
        // 要么一直不出、直到用户按键。
        // 「输入已排空」这一半不能省：drain 有 300 行 + 12ms 双预算、pending 转入还有 600 条/批上限，
        // 一批完全可能停在表格中间（-c 回放全程 IDLE、onTurnComplete 同锁窗口置 IDLE 后大段残行
        // 跨批消费、计划面板期间 hasModal() 恒真而正文按 300 行分批）。少了它就是把第 3 条豁免
        // 要避免的「半张对齐 + 半张原样」从这里重新放进来。
        // hasModal() 这一半是给权限/问询/计划用的：PermissionCallback 是最外层装饰器，审批请求早于
        // onToolStarted → 早于 flushStreaming()，面板弹出时表格还压在缓冲里，而此刻 status 不是 IDLE。
        if (!localWorkRemaining && (state.isIdle() || state.hasModal()) && printer.hasBufferedTable()) {
            outputQueue.enqueue(v -> printer.tableFlushCursor());
            // 紧跟一次 drain 在<b>同一批</b>里打完（照计划正文那段的写法，与本批前段共用行/时间预算）
            batchRowsUsed += drainQueuedOutput(Math.max(0, MAX_ROWS_PER_DRAIN - batchRowsUsed));
            localWorkRemaining = !outputQueue.isEmpty();   // 预算用完没打完的行留给下一批
        }
        boolean outputRemaining = localWorkRemaining
                // pty 写背压（「输出时打字卡死」根治配套）：writer 饱和 ⇒ display 侧可能有
                // 延迟批在途——保持 continuation 链，下一批（排空唤醒后）重投/接续。
                // 不依赖 state/queue 的非空（它们可能已空而延迟批仍在），防断链滞留。
                || ptyBackpressured();
        // 设备死亡检测（审核 M-2 UI 层）：pty 写失败（IOException / checkError 探针）后
        // writer 一切提交 no-op——模型还在跑但屏幕永远不更新，静默流失不可接受。
        // 终端没了：提示（若还能显示）+ 有界自动退出（会话事件已原子落盘，退出最诚实）。
        if (!ptyDeathHandled && ptyWriterDead()) {
            ptyDeathHandled = true;   // 只处理一次：后续批不再重复触发
            state.setNotice("⚠ 终端写入失败，即将退出（会话已保存）");
            log.warn("pty 写设备死亡（写线程转入 no-op），自动退出");
            updateScheduler.schedule(this::shutdownAndQuit, 1, java.util.concurrent.TimeUnit.SECONDS);
        }
        String curTail = lastLine(state.streaming());
        boolean previewPending = !curTail.isEmpty() && !curTail.equals(lastPreviewedTail);
        // ── 动画按需帧（§10.3，Task 8 接线 fix round M-5 预留点）──
        // 忙态（THINKING/RUNNING_TOOL/compacting/运行中后台任务/在飞子 agent）→ 接通 66ms 帧；
        // 状态消失立即 false（coordinator 取消在飞帧）；空闲静态无 timer。每帧到期发一次
        // VIEW → 下一批 animTick++ → render 的波光/压缩条拿到新帧号（恢复动态，文案不变）。
        boolean animationActive = animationDemandActive();
        coordinator.updateAnimationDemand(animationActive, ANIMATION_FRAME_DELAY);
        // ── preview 节流（§10.1，Task 8 接管）──
        // 只有残行包含尚未被 render 采纳的内容时，才安排一次「剩余节流窗口」后的 VIEW；
        // 窗口内重复 token 只更新状态（coordinator 每类至多一个在飞，保持首个到期）。
        // 已采纳的静止残行不续排，避免窗口到期后 remaining=ZERO 形成 immediate 热循环；
        // 下一 token 的 OUTPUT 事件会重新进入本批并启动 preview。残行清空则由 render 当帧清空。
        if (previewPending) {
            coordinator.schedulePreview(previewRemainingDelay());
        }
        if ((lastDirtyBitsForFlag & (UiDirty.OUTPUT | UiDirty.CONTROL)) != 0) {
            // 上下文统计可能变了（新消息/新事件）：按需防抖刷新（旧的 animTick % 30 周期已删）。
            ctxUsageController.markDirty();
        }
        // 背压退避（终审 e）：outputRemaining 的唯一成因是 pty 饱和（应用闸已关、
        // 本地 queue/pending/streaming 全空）时，continuation 用退避而非 ZERO——
        // ZERO 与每圈 render 形成双线程满载空转。真实存量（本地非空）仍用 ZERO 接续。
        java.time.Duration continuationDelay = (!localWorkRemaining && ptyBackpressured())
                ? PTY_BACKPRESSURE_BACKOFF : java.time.Duration.ZERO;
        return new UiUpdateCoordinator.UpdateResult(outputRemaining, animationActive, continuationDelay);
    }

    /**
     * preview 到期的剩余节流窗口（§10.1）。
     *
     * <p>render 的 150ms 判定（{@link #PREVIEW_THROTTLE_NANOS}）仍是<b>最终采纳点</b>——
     * 到期批只是「现在可以采纳新残行了」的唤醒；此处把窗口剩余量换算成调度延迟，
     * 使「首段立即可见 + 窗口到期恰一次 VIEW」与旧 tick 世界的节奏一致。
     * 已过窗口（含首段）返回 {@link Duration#ZERO}：下一帧即可采纳。调用方仅在残行与
     * {@code lastPreviewedTail} 不同时调用本方法；已采纳的静止残行不会以 ZERO 延迟自续排。
     */
    private Duration previewRemainingDelay() {
        long elapsed = System.nanoTime() - lastPreviewAtNanos;
        long remaining = PREVIEW_THROTTLE_NANOS - elapsed;
        return remaining <= 0 ? Duration.ZERO
                : Duration.ofNanos(Math.min(remaining, PREVIEW_THROTTLE_NANOS));
    }

    /**
     * 是否仍有「动着的状态」需要动画帧：忙（回合/压缩）或后台任务在跑。
     *
     * <p>Task 8 已接线（fix round M-5 预留点）：{@code computeFollowUpFlags} 每批尾调用
     * {@code coordinator.updateAnimationDemand(animationDemandActive(), ANIMATION_FRAME_DELAY)}
     * ——active 时保持至多一个在飞的 66ms 一次性帧任务（到期 publish VIEW，下一批续排），
     * 静止时立即取消。空闲静态界面没有动画 timer。
     */
    private boolean animationDemandActive() {
        return !state.isIdle() || state.isCompacting()
                || state.backgroundRunningCount() > 0
                || onSubmit.hasInFlightSubagents();
    }

    /** computeFollowUpFlags 读到的本批 dirty bits（context-usage 判据用）。 */
    private int lastDirtyBitsForFlag;

    /**
     * UI 线程请求一次差分重绘（本地 UI 状态变化 / resize settle 后）。
     * 生产桥 {@code InlineTuiRunner.requestRender()}（合并、无事件对象）；测试态 no-op
     * （ViewScreen 直接调 render）。
     */
    private void requestRenderOnce() {
        var r = runner();
        if (r != null) {
            r.tuiRunner().requestRender();
        }
    }

    /**
     * 本地 UI 状态变化的主动发布（brief：这些不在 Agent 源里）。
     *
     * <p>按键路径已在 UI 线程：改完状态直接 {@code coordinator.onUiChanged(VIEW)}，coordinator
     * 把它合并进下一批（或当下就调度一批），批次结尾的 requestUiUpdate 触发差分重绘。
     * 纯本地状态（输入框文本、选择器高亮、模态选项、notice、权限模式标签、MCP 面板）
     * 一律走这里——绝不能等 Agent 事件（它们不来）。
     *
     * <p><b>输入框文本也经此路</b>（fix round M-1：原「文本不经此路」与实现不符——
     * {@code InputBox.handleKeyEvent} 的 HANDLED 与编辑器两个分支逐键都调它）：token 级
     * 风暴由 coordinator 的合并吸收（同类 burst 只产生一个已调度 update），每次按键的
     * 开销是一次原子 OR + 幂等的 requestRender。文本之外的可见性仍由按键自身的重绘携带。
     */
    private void publishLocalViewChange() {
        localViewPublished = true;
        coordinator.onUiChanged(UiDirty.VIEW);
        requestRenderOnce();   // 本地变化不等批：按键路径已在 UI 线程，直接请求一次合并重绘
    }

    /**
     * 后台任务结果的自动送达：空闲 + 输入框为空 + 有已完成未消费任务时，起一个新回合交给模型。
     *
     * <p><b>判定与提交在同一 UI 批内完成</b>：批跑在 UI 线程（单线程），「读输入框是否为空」
     * 与「调 submit」之间不会被用户按键插入，故不存在「判定时为空、提交时用户已开始打字」的竞态。
     *
     * <p><b>{@code shouldNotifyResults} 有副作用</b>（判定为该送达时消耗一次刹车额度），故只调一次、
     * 且调了就必须真的用返回值去起回合。状态栏想显示刹车状态请读 {@code brakeEngaged()}，别再试探一次。
     *
     * <p><b>submit 抛异常时不标记已消费</b>：一次提交失败不能把结果丢掉，下一批会再试。代价是重试也各
     * 消耗一次额度——连续失败三次就会踩下刹车、等用户回车放行。这是刹车该有的样子：与其对着炸掉的
     * 网关每批重投一次，不如停下来告诉用户。
     */
    private void deliverBackgroundResults() {
        // ⚠ 输入框的真相在 inputState，不在 state——ConversationState.currentInput() 是输入迁移后留下的
        // 死代码（见 typeChar 那一串），读它永远得到空串，等于「用户正在打字」这道闸门形同虚设。
        String typed = inputState.text();
        boolean inputEmpty = typed == null || typed.isEmpty();   // 连空格都算在打字：抢跑一次比晚送一帧讨厌得多
        // ⚠ <b>先判闸门，再取列表</b>。取列表会顺手做结果限幅 + 落盘（那是设计上的「唯一入口」，
        // 位置没错），但闸门关着还照取，就是每个空闲批一次同步文件写。
        if (busy() || !inputEmpty) return;

        if (notifier.brakeEngaged()) {
            // 刹车已踩下：状态栏仍要如实告诉用户「确有结果被扣住」，但探明一次就够——
            // 之后每批再取只是白白重写落盘文件，而屏幕上那句话一个字都不会变。
            if (!bgProbedWhileBraked) {
                bgProbedWhileBraked = true;
                bgPending = !onSubmit.completedBackgroundTasks().isEmpty();
            }
            return;
        }

        List<SubmitHandler.BackgroundResult> done = onSubmit.completedBackgroundTasks();
        bgPending = !done.isEmpty();
        if (done.isEmpty()) return;
        // 闸门（空闲 + 输入框为空）上面已判过，这里直接传 true——shouldNotifyResults 只在
        // 判定为「该送达」时才消耗刹车额度，重复判一次不会多扣，但会让"谁负责判闸门"变成两处。
        var text = notifier.shouldNotifyResults(done, true, true);
        if (text.isEmpty()) return;
        try {
            dispatch(text.get(), null);
        } catch (RuntimeException e) {
            log.warn("后台任务结果自动送达失败，保持未消费、下一帧重试", e);
            return;                         // ⚠ 不标记已消费：标记了就等于把结果丢了
        }
        for (SubmitHandler.BackgroundResult r : done) {
            onSubmit.markBackgroundConsumed(r.taskId());
        }
        bgPending = false;                  // 已全部送达并消费：提示不该再挂着
    }

    /**
     * 放行自动回合刹车：用户有真实输入了。
     *
     * <p>连同 {@link #bgProbedWhileBraked} 一起复位——下一次踩下刹车要重新探一次，
     * 否则会拿着上一轮的 {@link #bgPending} 显示一句陈旧的提示。
     */
    private void releaseBrake() {
        notifier.onUserInput();
        bgProbedWhileBraked = false;
    }

    /**
     * 推进终端注意提示一拍（渲染线程）：按 {@link AttentionTracker} 的边沿动作写 tab 标题 / 响 BEL。
     *
     * <p><b>「忙」的定义与 {@link #busy()} 对齐但减去模态一份</b>：模态在场时（modalWaiting）
     * 状态机优先走 WAITING_USER，故 busy 只需覆盖「回合在跑 / 压缩中 / 在飞子 agent 收尾」。
     * {@code state.isBusy()} 含「有模态」，直接用它会把 WAITING_USER 拍成 BUSY，故这里用
     * {@code state.isIdle() && !onSubmit.hasInFlightSubagents() && !state.isCompacting()} 取反。
     *
     * <p><b>标题文案</b>：三种标题由 {@link AttentionTracker} 按项目名拼出（见其类注释），
     * 状态符号与项目名都在最前——macOS 会把 tab 标题截短，靠前的字符才保得住。
     * 写入失败（反射 / IO）静默降级——提示是锦上添花，绝不拖垮主流程（见 TerminalAttention 契约）。
     */
    private void advanceAttention(boolean modalWaiting) {
        boolean busy = !(state.isIdle() && !onSubmit.hasInFlightSubagents()) || state.isCompacting();
        boolean cancelled = userCancelledSinceLastTick;
        userCancelledSinceLastTick = false;
        switch (attention.advance(modalWaiting, busy, cancelled)) {
            case ALERT_WAITING -> TerminalAttention.alert(runner(), attention.waitingTitle());
            case ALERT_DONE -> TerminalAttention.alert(runner(), attention.doneTitle());
            case RESTORE -> TerminalAttention.restore(runner(), attention.defaultTitle());
            case NONE -> { /* 平态 */ }
        }
    }

    /** 测试专用：跑一批 ALL（侦测队首模态并进入作答/审批态）。兼容别名——不启动任何周期任务。 */
    void tickForTest() { processUpdates(UiDirty.ALL); }

    // ── 事件驱动 test seam（brief「Produces test seams」）──
    /** 测试专用：View 持有的合并/调度中心（断言绑定目标 / 生命周期 / dirty bits）。 */
    UiUpdateCoordinator coordinatorForTest() { return coordinator; }

    /** 测试专用：直接跑一批 {@code processUpdates(dirtyBits)}（不经 coordinator 合并）。 */
    UiUpdateCoordinator.UpdateResult processUpdatesForTest(int dirtyBits) {
        return processUpdates(dirtyBits);
    }

    /** 测试专用：执行已投递（未 run 的 View 落在测试队列里）的 UI update，直到队列清空。 */
    void runPendingUiUpdatesForTest() {
        Runnable action;
        while ((action = pendingUiUpdatesForTest.poll()) != null) {
            action.run();
        }
    }

    /**
     * 测试专用：逐个执行已投递 UI update，并在每个 action 后构造一帧，等价生产 runner 的
     * 「UI action → coalesced draw」。用于确定性观察 coordinator 批实际触发的 render 采纳。
     */
    void runPendingUiUpdatesAndRenderForTest() {
        Runnable action;
        while ((action = pendingUiUpdatesForTest.poll()) != null) {
            action.run();
            render();
        }
    }

    /** 测试专用：等价 onStart 的关键步骤（不真正起 runner）：coordinator start + 欢迎横幅 + 初始 ALL 同步。 */
    void startForTest() {
        coordinator.start();
        ctxUsageController.markDirty();
        printer.welcome(onSubmit.currentModel(),
                io.github.javaside.springai.codetui.AppInfo.versionLabel());
        welcomePrinted = true;
        publishInitialAllSync();
    }

    /**
     * 测试专用：等价 onStop 的停止序列（coordinator/controller 停 + 解绑 + 设施关闭）。
     *
     * <p><b>与生产 onStop 的唯一差异</b>（fix round M-6 写明）：不调 {@code super.onStop()}——
     * 超类清理走 terminal 恢复等真实 IO，测试态没有起过 runner，调它只会引入「未运行态收尾」
     * 的未定义行为。其余步骤（coordinator/controller 停止、双源解绑、两池关闭）与生产逐字一致。
     * 真实的停止顺序（含 super.onStop）由 CodeTuiViewEventWiringTest.stop_unbindsAndStopsBeforeSuperCleanup
     * 在 coordinator 生命周期层面钉住。
     */
    void stopForTest() {
        coordinator.stop();
        ctxUsageController.stop();
        state.setUiChangeListener(null);
        onSubmit.setUiChangeListener(null);
        contextUsageExecutor.shutdownNow();
        updateScheduler.shutdownNow();
    }

    /**
     * 是否有 continuation 一次性任务在飞（诊断/测试：空闲必须为 false）。
     *
     * <p>fix round I-3 后 View 不再自排 continuation（单一调度方是 coordinator.runBatch），
     * 在飞判定也直接读 coordinator 的诊断口，View 侧不再镜像任何调度状态。
     */
    boolean hasContinuationScheduledForTest() { return coordinator.hasPendingContinuation(); }

    /**
     * 测试观测（Task 8）：是否有 preview 一次性任务在飞。
     * 节流窗口到期消费后、回合结束（streaming 空）后必须为 false。
     */
    boolean hasPendingPreviewScheduledForTest() { return coordinator.hasPendingPreview(); }

    /**
     * 测试观测（Task 8）：是否有动画帧一次性任务在飞。
     * 空闲静态必须为 false（动画 timer demand 驱动、状态消失即停）。
     */
    boolean hasPendingAnimationFrameForTest() { return coordinator.hasPendingAnimation(); }

    /** 测试观测（Task 8）：动画帧计数（每个动画帧批自增一次；波光/压缩条的驱动量）。 */
    long animTickForTest() { return animTick; }

    /** 测试观测：初始全量同步是否完成。 */
    boolean initialAllSyncDoneForTest() { return initialAllSyncDone; }

    /** 测试观测：欢迎横幅是否打印。 */
    boolean welcomePrintedForTest() { return welcomePrinted; }

    /** 测试观测：本批是否消费掉全部 pending（控制顺序断言用）。 */
    boolean pendingOutputConsumedForTest() { return !state.hasPendingOutput(); }

    /** 测试观测：累计执行的批数（接线表断言「变化驱动了至少一批」）。 */
    private final java.util.concurrent.atomic.AtomicInteger processedBatches =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 测试观测：输出队列是否为空（背压门控断言「饱和批不产出新内容」）。 */
    boolean outputQueueEmptyForTest() { return outputQueue.isEmpty(); }

    /** 测试观测：本 View 持有的会话状态（背压门控测试直接往 state 攒 pending）。 */
    ConversationState stateForTest() { return state; }

    int processedBatchesForTest() { return processedBatches.get(); }

    /** 测试观测：render 最后采纳的流式残行，只读生产字段。 */
    String lastPreviewedTailForTest() { return lastPreviewedTail; }

    /** 测试控制：把 preview 节流时钟移到足够早，使下一次未采纳残行使用 ZERO delay。 */
    void makePreviewImmediatelyDueForTest() {
        lastPreviewAtNanos = System.nanoTime() - PREVIEW_THROTTLE_NANOS - 1L;
    }

    /** 测试观测：本地 UI 状态变化是否主动发布过 VIEW。 */
    private volatile boolean localViewPublished;
    boolean localViewPublishedForTest() { return localViewPublished; }

    /**
     * 启动期初始全量同步：publish(UiDirty.ALL) 一次，由 coordinator 调度一批。
     * 生产里投给 requestUiUpdate（onStart 已在 UI 线程，请求一次合并重绘）；测试态落队列。
     *
     * <p>{@code initialAllSyncDone} 在 publish（测试态含同步排空）<b>之后</b>置位——标记的是
     * 「启动期那次 ALL 已经投递/执行完」，不是「批内的某个时刻」；首批批内的实际消费由
     * {@code processUpdates} 自己负责（fix round M-1：原注释「标记时机在批内」与实现不符）。
     */
    private void publishInitialAllSync() {
        coordinator.onUiChanged(UiDirty.ALL);
        // 测试态立即执行这一批（生产由 requestUiUpdate 在事件循环里跑）。
        if (runner() == null) {
            runPendingUiUpdatesForTest();
        }
        initialAllSyncDone = true;
    }

    // ── 测试专用观测点（fix round I-3：单 tick 共享预算 / 有界入队的回归钉） ──
    /** 最近一次 tick 内各输出段实际使用的 deadline（纳秒绝对时刻）；每 tick 开始时清空。 */
    private final List<Long> drainDeadlinesObserved = new ArrayList<>();
    /** 最近一次 tick 从 state.pending 转入输出队列的 entry 数。 */
    private int pendingIntakeCount;

    List<Long> drainDeadlinesObservedForTest() { return drainDeadlinesObserved; }
    int pendingIntakeCountForTest() { return pendingIntakeCount; }
    int pendingIntakeCapForTest() { return MAX_PENDING_INTAKE_PER_TICK; }


    /** 测试专用：终端注意提示状态机（断言 UI 批接线的边沿落点；IO 已静默降级）。 */
    AttentionTracker attentionForTest() { return attention; }
    PermissionRequest activePermissionForTest() { return activePermission; }

    /** 测试专用：当前正在作答的问询（null=非作答态）。 */
    AskRequest activeAskForTest() { return activeAsk; }

    /** 测试专用：直接驱动上下文用量刷新（测试里没有事件循环，refresh 经 markDirty 防抖异步调度，测试需要同步结果）。 */
    /** 测试专用：View 内部的 scrollback printer（表格缓冲状态、游标工厂的观测点）。 */
    ScrollbackPrinter printerForTest() { return printer; }

    ContextUsage ctxUsageForTest() { return ctxUsage; }

    /** 测试专用：当前正在审批的计划（null=非计划态）。 */
    PlanRequest activePlanForTest() { return activePlan; }

    /**
     * 测试专用：把一个按键喂给输入框按键入口（等价真实按键路由）。
     *
     * <p>必须复用 {@link InputBox#handleKeyEvent}（而非只调 {@code onInputKey}）：普通字符键
     * {@code onInputKey} 自己并不插入文本——它只拦截 Ctrl+C/Esc/Enter/方向键等特例，未拦截时
     * 返回 {@code UNHANDLED}，真正的字符插入落在 {@code InputBox.handleKeyEvent} 的兜底分支
     * （转交不渲染的 {@link #inputKeys}）。只调 {@code onInputKey} 会让「打字」在测试里静默丢失。
     */
    EventResult feedKeyForTest(KeyEvent k) { return new InputBox().handleKeyEvent(k, true); }

    /** 测试专用：构造一帧 UI 树（等价渲染线程被请求时调用的 render）。用于回归「render 构造子面板」类空指针。 */
    Element renderForTest() { return render(); }

    /** 测试专用：读取输入框当前文本 / 光标（行、列），断言编辑快捷键的落点。 */
    String inputTextForTest() { return inputState.text(); }
    int cursorRowForTest() { return inputState.cursorRow(); }
    int cursorColForTest() { return inputState.cursorCol(); }
    /** 测试专用：预置输入文本（光标落到文末），免逐字符敲入。 */
    void setInputForTest(String text) { inputState.setText(text); inputState.moveCursorToEnd(); }

    /** 测试专用：直接挂载技能，绕开 /skill 选择器（那条路径要一份真实技能清单才走得通）。 */
    void mountSkillForTest(String skill) { this.pendingSkill = skill; }
    String pendingSkillForTest() { return pendingSkill; }

    /** 测试专用：二级思考设置面板是否激活。 */
    boolean configuringThinkingForTest() { return configuringThinking; }
    /** 测试专用：当前正在设置的模型 id。 */
    String thinkingTargetForTest() { return thinkingTarget; }

    /**
     * scrollback 留底（见 {@link #scrollTail} 字段注释）。只在渲染线程调用。
     *
     * <p><b>按 raw 引用去重（fix round I-2）</b>：严格分批下一条逻辑行折出的多个物理段会
     * <b>逐段</b>经过这里（同一批或跨批），各段携带同一 raw 引用——不去重的话一条 60k 长行
     * 会记 751 条重复原文，留底配额（{@value #SCROLL_TAIL_CAP}）被立刻吃穿、语义照样缩水。
     * 故：与上一条已记录的 raw 是<b>同一引用</b>（{@code ==}，非 equals——只合并同一逻辑行的段，
     * 两条内容相同的独立行仍各占一条）则跳过；留底因此恢复「一条逻辑行一条配额」的旧语义。
     */
    private void record(Object line) {
        if (line == lastRecordedRaw) return;                 // 同一逻辑行的后续折行段：不重复记
        lastRecordedRaw = line;
        scrollTail.addLast(line);
        if (scrollTail.size() > SCROLL_TAIL_CAP) {
            scrollTail.removeFirst();
        }
    }

    /** 上一次已进留底的对象引用（折行段去重用，见 {@link #record}）。 */
    private Object lastRecordedRaw;

    /** 复位折行段去重指针（/clear 清空留底时一并复位）。 */
    private void resetTailDedup() { lastRecordedRaw = null; }

    /**
     * /clear 降级提示（真清屏失败：反射失败或清屏屏障不成立——writer 积压排不空）。
     * 说明行文案只对「屏障不成立」成因准确（反射失败时无积压、无旧内容晚到），
     * 但两个成因共用一行是刻意取舍：调用方拿不到成因区分，多打一行无害、少打
     * 一行会在真实需要时缺席。屏障降级时 writer 在飞旧批仍会晚到——旧内容出现
     * 在分割线之后，说明行消除「清屏了旧内容又冒出来」的困惑（规格 §7/§10）。
     * 运行态与测试态两处降级分支共用本 helper，防文案漂移。
     */
    private void pushClearDegradedNotice() {
        state.pushInfo("─── 新会话（上下文已清空）───");
        state.pushInfo("终端输出积压未排空：上方若浮现旧内容，属上一会话残留");
    }

    /**
     * resize 停稳后的全量重建：整屏<b>连回滚缓冲一起</b>抹掉（与 /clear 同一条 {@link ScreenCleaner#clear}
     * 路径），再把 {@link #scrollTail} 留底全量按新宽度重放，输入框随下一帧落回对话正下方——
     * 可见屏是对话尾部，「往上翻」是干净重排的最近 {@value #SCROLL_TAIL_CAP} 行。
     *
     * <p><b>为什么需要它</b>（单靠 live 区差分管不到的两类伤害，tmux 实测）：
     * ①变窄时旧帧整宽行被终端折行撑高，把真实对话一截截顶进回滚缓冲——可见屏上信息流越来越少，
     * 拖一次窗口后只剩输入框和空白；②多路复用器合并连发 resize 的盲窗里按旧宽度画的帧走位成鬼影，
     * 事后清扫够不着。重放不跟终端的重排行为搏斗：应用自己就是内容的主人，照着留底重印一遍就是了。
     *
     * <p><b>为什么回滚缓冲必须抹、不能留</b>（Terminal.app AppleScript 拖拽实测，2026-08）：
     * 真 reflow 终端每次拖窄都把旧帧折行撑出的行推进 scrollback——ESC[J] 清扫只够得着可见屏，
     * 每拖一次 scrollback 就永久存档一份界面尸体（横幅+输入框+状态栏+整屏空白，快拖时还有撕裂的
     * 半截框），用户「往前翻」全是残骸。上一版有意保留 scrollback（想让旧历史翻得到）恰恰错在这：
     * 保留下来的就是垃圾。抹掉换全量重放后，翻页上限从「无限但不可读」变成「{@value #SCROLL_TAIL_CAP}
     * 行且干净」；代价是更早的历史与应用启动前的 shell 残行消失——/clear 早已是同样语义。
     *
     * <p><b>宽度安全</b>：内联 println 一行折成两行会把显示区记账推歪（「一个 OutputLine =
     * 一个物理行」同一条纪律），所以 {@link Text} 过 {@link TextWrap}、纯字符串过
     * {@link #wrapSegments} 先按<b>当前</b>宽度拆好——重放的每一次 println 都恰好一物理行。
     * 留底存的是折行前的原始行（见构造器的 recording sink），拖窄重放是重新折行而不是截断，
     * 内容不丢（截断版用户实报过「回复文字没显示全」）。
     *
     * <p>清屏失败（反射降级）就什么都不做：维持第一级清扫后的现状，不会更糟。
     */
    private void replayAfterResize() {
        var r = runner();
        if (r == null || !ScreenCleaner.clear(r)) {
            return;
        }
        int width = sinkWidth();
        for (Object line : scrollTail) {
            if (line instanceof Text t) {
                for (Text piece : TextWrap.wrap(t, width)) {
                    r.println(piece);                  // 按当前宽度重新折行，恰一物理行、不丢内容
                }
            } else {
                for (String seg : wrapSegments((String) line, width)) {
                    r.println(seg);                    // 纯字符串同理
                }
            }
        }
    }

    /** 出口折行用的终端宽；拿不到时 {@code terminalWidth()} 已退化为 80，再兜个下限防极端值。 */
    private int sinkWidth() {
        return Math.max(8, terminalWidth());
    }

    /** 包私有宽度测试注入口；ViewScreen 的 buffer 宽度不会改变视图内部终端宽度。 */
    void terminalWidthForTest(int width) {
        terminalWidthOverride = width;
    }

    /** 终端列数；拿不到时退化为 80。 */
    private int terminalWidth() {
        if (terminalWidthOverride != null) return terminalWidthOverride;
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
            if (r.isHandled()) {
                // 事件驱动（Task 7）：按键可能改了纯本地 UI 状态（选择器开关/高亮、模态选项、
                // slash 菜单、技能标签、附件取消态、历史回溯…）——这些不在 Agent 源里，没人替我们
                // 发通知。此处已在 UI 线程，主动发布一次 VIEW（合并进下一批 + 请求合并重绘）。
                // TamboUI 对 HANDLED 的按键本会重绘，requestRender 只是幂等合并；对 UNHANDLED
                // 落到编辑器的键，编辑器自身路径不带我们的状态。
                publishLocalViewChange();
                return r;
            }
            EventResult edited = inputKeys.handleKeyEvent(event, focused);   // 其余编辑键交给（不渲染的）textArea 键处理
            // 文本编辑同样可能改变 live 区结构（附件行出现/消失、输入框高度变化、slash 菜单开合）。
            publishLocalViewChange();
            return edited;
        }

        @Override
        public EventResult handlePasteEvent(PasteEvent event) {
            EventResult r = inputKeys.handlePasteEvent(event);      // 多行粘贴
            publishLocalViewChange();   // 粘贴改文本：附件行/菜单结构可能变（本地状态，无 Agent 事件）
            return r;
        }

        @Override
        public Size preferredSize(int maxW, int maxH, RenderContext ctx) {
            int w = maxW > 0 ? maxW : 80;
            // 附件行占的那一行必须在这里算进去：多留不画会留白，画了没留会被裁掉（两边必须同一个判据）。
            int attach = attachmentLineText().isEmpty() ? 0 : 1;
            return Size.of(w, visualRowCount(w - 2) + 2 + attach); // 自动增高：软折行后的可视行数 + 上下边框
        }

        @Override
        public void render(Frame frame, Rect rect, RenderContext ctx) {
            Buffer buf = frame.buffer();
            Rect boxRect = rect;
            String attach = attachmentLineText();
            if (!attach.isEmpty() && rect.height() > 1) {
                // 画在输入框<b>下方</b>而非框内：框内那块是编辑区，塞进提示会与光标/软折行抢位置。
                // 超出终端宽度要先截断——buf.setString 越界写是静默丢弃，截断至少还能读出前半句。
                boxRect = new Rect(rect.x(), rect.y(), rect.width(), rect.height() - 1);
                buf.setString(rect.x(), rect.y() + rect.height() - 1,
                        dev.tamboui.text.CharWidth.substringByWidth(attach, Math.max(1, rect.width())), HINT);
            }
            Block block = Block.builder().borders(Borders.ALL).borderType(BorderType.ROUNDED).build();
            block.render(boxRect, buf);
            Rect inner = block.inner(boxRect);
            int ix = inner.x(), iy = inner.y(), iw = Math.max(1, inner.width()), ih = inner.height();

            if (inputState.text().isEmpty()) {              // 空态：只画反显块光标，不画框内占位符
                // 不放框内占位符：中文输入法拼字（候选未上屏）时 inputState 仍为空，占位符会与拼音并存、
                // 显得「打字时占位符还在」。输入引导已在下方状态行常驻，框内保持干净只留可见光标即可。
                // 只 setCursorPosition 时硬件光标常被行内 runner 隐藏 → 给人「没光标/没聚焦」错觉，故画反显块。
                buf.set(ix, iy, buf.get(ix, iy).patchStyle(Style.EMPTY.reversed()));
                frame.setCursorPosition(ix, parkCursorAtTop ? 0 : iy);   // 停放策略见非空分支注释
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
            // 硬件光标（隐藏的，记账 + IME 锚点）：平时停在文本行 (cx, cy)，resize 窗口内钉第 0 行。
            //
            // 为什么平时必须在文本行：Terminal.app 把 IME 预编辑串（拼音）画在硬件光标处，
            // 钉死第 0 行 = 拼字浮在框顶边框上，用户实报「打字错位」。可见的反显块是画在
            // Buffer 里的另一回事，救不了 IME。
            //
            // 为什么 resize 中要钉第 0 行：内联显示区的位置全靠「光标在显示区第几行」这条相对
            // 记账。终端变窄时把屏上内容重新折行，光标跟着**自己那行字符**走——它上方每一行
            // （整宽的框顶边框）被拆成几行，物理落点就比记账低几行；此后每次重画从偏低处开始，
            // 帧往下爬、旧帧顶部留残迹，拖一次窗口累积一片（tmux 实测连拖 10 档留 20+ 行）。
            // 钉在第 0 行上方无行可拆、位移恒 0（同一实测归零）。两头都要：状态相关停放，
            // parkCursorAtTop 的生命周期见字段注释。已知代价：每轮拖拽第一步光标还在文本行，
            // 清扫起点错位留 ≤1-2 行残迹，停稳重放收尾；输入列超过新终端宽度时光标自己那行
            // 也会拆（残留 1 行、不累积）。
            frame.setCursorPosition(cx, parkCursorAtTop ? 0 : cy);
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

    /** 测试专用：暴露 wrapSegments（{@code SegmentedWrapTest} 断言「可续折行与一次性折行一致」用）。 */
    static List<String> wrapSegmentsForTest(String line, int width) { return wrapSegments(line, width); }

    // ── 附件行（输入框下方那一行）────────────────────────────────────────
    /**
     * 附件行文本。<b>纯函数</b>，便于单测——渲染分支顺序类的缺陷本项目栽过，
     * 内容函数与渲染必须分开测。
     *
     * @param count     已识别（未取消）的图片张数
     * @param overflow  因超上限被丢弃的张数，<b>必须如实显示</b>：静默截断会让用户以为都附上了
     * @param firstName 第一张的文件名，只在 count==1 时用（多张时列名会撑爆一行）
     */
    static String attachmentLine(int count, int overflow, String firstName) {
        if (count <= 0) return "";
        StringBuilder b = new StringBuilder("  ⏎ 已附带 ").append(count).append(" 张图片");
        if (count == 1 && firstName != null) b.append("（").append(firstName).append("）");
        if (overflow > 0) b.append("，另有 ").append(overflow).append(" 张超出上限未附");
        b.append("  · Ctrl+X 取消");
        return b.toString();
    }

    /**
     * 取消后的附件行。
     *
     * <p><b>必须说清「接下来会怎样」，不能只说「取消了什么」</b>：取消附件<b>不会</b>删掉输入框里
     * 那段路径——最典型的误附场景（{@code 把 docs/bug.png 复制到 tmp/}）里，用户<b>就是要</b>跟模型
     * 说这个路径，删掉等于毁掉他的话。但路径明明还在屏幕上、行里却写着「已取消」，用户会以为
     * 没生效（实测反馈：「附件是取消了，输入框里的文件还在，歧义特别大」）。补一句结果即可消歧。
     *
     * <p>刻意不再提示 {@code Ctrl+X}——已经取消了，再提示是噪音。
     */
    static String attachmentLineCancelled() {
        return "  ⏎ 已取消附件 · 路径仅作普通文本发送";
    }

    /** 当前输入文本里识别到的图片（按文本记忆，见 {@link #attachCacheText}）。 */
    private ImageAttachmentDetector.Result attachments() {
        String text = inputState.text();
        if (!text.equals(attachCacheText)) {
            attachCache = imageDetector.detectWithOverflow(text, root);
            attachCacheText = text;
        }
        return attachCache;
    }

    /** 该画在输入框下方的那一行；空串=不画（也不占高度）。 */
    private String attachmentLineText() {
        ImageAttachmentDetector.Result r = attachments();
        if (r.images().isEmpty()) {
            // 没图可取消时顺手复位取消态：用户把路径删掉再重新写一条，理应重新附上。
            // 不复位的话，一次 Ctrl+X 会连累同一段草稿里之后写的所有路径，而用户看不出原因。
            attachmentsCancelled = false;
            return "";
        }
        return attachmentsCancelled
                ? attachmentLineCancelled()
                : attachmentLine(r.images().size(), r.overflow(), r.images().get(0).name());
    }

    // ── 附件兑现：识别结果 → [file reference] 块 ───────────────────────────

    /**
     * 把识别到的图片渲成 {@code [file reference]} 块追加进待发文本。
     *
     * <p><b>为什么走文本注入而不是新开 API</b>：期 1 的 {@code VisionMaterializer} 已经会处理
     * user 消息里的引用块，只是一直没有入口去产生这种消息。走这条则 {@code SubmitHandler} /
     * {@code CodingAgent} / 兑现器 / 预算一行都不用改。与斜杠技能注入同套路：会话持久化
     * 「注入后」的文本，实时 UI 只显示用户原文。
     *
     * <p><b>项目内不复制、项目外必须复制</b>：前者是为了「你更新了 design.png，模型该看到新版」
     * ——复制一份快照会让它永远照着旧稿做；后者是硬约束，按原路径写进引用块会被
     * {@code FileReferenceParser} 的越界防线整块丢弃，<b>且没有任何报错</b>，图就这么静默消失。
     * 复制进 {@code .codetui/artifacts/} 之后 path 落回 root 内，解析器才认。
     */
    static String injectAttachments(String text, List<DetectedImage> images, Path root) {
        if (images == null || images.isEmpty()) return text;
        StringBuilder b = new StringBuilder(text);
        for (DetectedImage img : images) {
            MediaArtifact a = img.insideRoot()
                    ? existingFileArtifact(img, root)
                    : copyIntoArtifacts(img, root);
            if (a == null) continue;   // 单张失败不连累其余，也不打断提交
            b.append('\n').append(FileReference.render(
                    a, FileReference.DELIVERY_NOT_IN_VIEW,
                    "user attachment; not currently in view"));
        }
        return b.toString();
    }

    /**
     * 项目内的图：引用<b>指原文件</b>，不复制。
     *
     * <p>sha 用「文件绝对路径的 SHA-256」而非内容哈希——照抄
     * {@code MediaExternalizingCallback#referenceExistingFile}。两边必须一致：同一个文件经
     * 「用户附件」与「Read 工具结果」两条路进来时算出同一个 id，模型才认得出是同一张；
     * 而且原文件随时会被改写，内容哈希会让 id 逐版本漂移（还得整份读盘，附件可能几百 MB）。
     */
    private static MediaArtifact existingFileArtifact(DetectedImage img, Path root) {
        try {
            Path file = img.file();
            MagicSniffer.Sniffed s = MagicSniffer.sniff(readHead(file));
            // 尺寸 0 = 识别器只读了前 64 KiB、没解析出来（见 ImageAttachmentDetector#HEAD_BYTES）。
            // 两个都传 null：render 只在两者非空时才写 dimensions 行，传 0 会渲出 dimensions: 0x0
            // 这种假信息——比不写更糟，模型会据此判断该不该看这张图。
            boolean dimKnown = img.width() > 0 && img.height() > 0;
            return new MediaArtifact(
                    sha256Hex(file.toAbsolutePath().normalize().toString()),
                    // 相对路径直接委托 PathContainment：口径必须与解析器的包含校验逐字一致，
                    // 各算一份的话解链逻辑一改就分家，图会在引用块里静默消失。
                    file, PathContainment.relativeToRoot(file, root),
                    s.mimeType(), null, s.kind(), Files.size(file),
                    dimKnown ? img.width() : null, dimKnown ? img.height() : null, null,
                    ArtifactSource.EXISTING_FILE, false,
                    img.name());
        } catch (RuntimeException | IOException e) {
            return null;
        }
    }

    /** 项目外的图：复制进 artifacts（内容寻址 + 原子写 + 去重都由 store 负责）。 */
    private static MediaArtifact copyIntoArtifacts(DetectedImage img, Path root) {
        try {
            byte[] bytes = Files.readAllBytes(img.file());
            MediaArtifact a = new MediaArtifactStore(
                    root.resolve(".codetui").resolve("artifacts"), root)
                    .put(bytes, null, img.name());
            // store 的宽高来自<b>完整</b>字节，比识别器只读前 64 KiB 的结果更可信，故这里不拿
            // img 的 0 去覆盖它；只兜底「万一算出非正数」，绝不让 dimensions: 0x0 流进引用块。
            boolean dimBogus = a.width() == null || a.height() == null
                    || a.width() <= 0 || a.height() <= 0;
            if (!dimBogus) return a;
            return new MediaArtifact(
                    a.sha(), a.path(), a.relativePath(),
                    a.mimeType(), a.declaredMimeType(), a.kind(), a.size(),
                    null, null, a.lineCount(),
                    a.source(), a.ownedByStore(), a.originalName());
        } catch (RuntimeException | IOException e) {
            return null;
        }
    }

    /** 只读文件头判魔数：附件可能是几百 MB 的原图，整份读盘会把提交卡住（magic 只看前几 KB）。 */
    private static byte[] readHead(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return in.readNBytes(8 * 1024);
        }
    }

    private static String sha256Hex(String s) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
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
            shutdownAndQuit();
            return EventResult.HANDLED;
        }
        // 任意用户按键 = 人在场：DONE 态的提示标题就此收场（下一个 UI 批恢复默认标题）。
        attention.userActed();
        // 任意按键消费掉上一条 sticky notice（如「已取消当前回合」），恢复状态栏常态行。
        // 本次按键若要显示新 notice，会在下方各分支重新 setNotice（晚于此处），故当次提示不受影响。
        // 修复：真实输入走 inputState 编辑器、不再触发旧 typeChar 清 notice，导致取消长回合（如子 agent）
        // 后 notice 永久占据状态栏；这里补回「下次按键即清」的既定行为。
        if (!state.notice().isEmpty()) state.setNotice("");
        // Shift+Tab 循环权限模式（期 0 已 pty 实测：ESC[Z → code=TAB + SHIFT，与裸 Tab 可区分）。
        // 放在最前：模态/菜单激活时也应能切模式（模式只影响后续判定，不动任何 pending 请求）；
        // 但必须晚于上面那句「按任意键清 notice」，否则本次设的 notice 当场被清掉、用户看不到反馈。
        if (k.code() == KeyCode.TAB && k.hasShift()) {
            // 「已切到」而不是「权限模式：」：这条 notice 现在会出现在忙时的后缀位置，
            // 那里读起来必须像一个刚发生的<b>事件</b>；「当前是哪一档」这个常驻状态由行首
            // 的 modeTag 负责，两者分工分开后就不再是同一件事说两遍。
            state.setNotice("已切到 " + onSubmit.cyclePermissionMode().label());
            return EventResult.HANDLED;
        }
        if (activePermission != null) return onPermissionKey(k);   // 审批模态优先于一切文本编辑（背后 park 着工具线程）
        if (activePlan != null) return onPlanKey(k);    // 计划审批模态同理（同一时刻只会有一个模态在前台）
        if (activeAsk != null) return onAskKey(k);      // 作答模态：全部按键交给它，屏蔽文本编辑
        if (configuringThinking) return onThinkingSettingsKey(k);   // 二级设置优先于模型列表
        if (pickingModel) return onModelPickerKey(k);   // 选择器激活：按键全部交给它，屏蔽文本编辑
        if (pickingSkill) return onSkillPickerKey(k);   // 技能选择器同理
        if (pickingMcp) return onMcpPickerKey(k);       // MCP 管理面板同理
        if (pickingPerms) return onPermsPanelKey(k);    // 权限规则面板同理
        if (pickingTasks) return onTasksPanelKey(k);    // 后台任务面板同理
        // 取消图片附件。⚠️ 不要改回 Ctrl+G：Chrome 的 Gemini 扩展把它注册成 OS 级全局热键，
        // 键在任何终端应用看到它之前就被抢走（用户实机撞车，按下去弹的是 Chrome 对话框）。
        // 这类冲突在代码里修不了。Ctrl+X 在 readline 里是前缀键、单按无动作，不撞肌肉记忆，
        // 且已在出问题的那台机器上（Chrome 开着）实测确认能到达。
        // 必须在转交 textArea 之前拦下，否则被当普通字符插进输入框：用户按了取消，附件没取消，
        // 反而多了个看不见的控制字符。判键写法同 onEditShortcut 的 Ctrl+字母。
        if (k.hasCtrl() && k.isChar('x')) {
            attachmentsCancelled = true;
            return EventResult.HANDLED;    // 无图时也吞掉：控制键漏进编辑器比无反馈更糟
        }
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
            // 用户主动取消：置位抑制本拍忙→闲下降沿的「已完成」提示（他刚按过键，必然在场）。
            if (running || dropped > 0) userCancelledSinceLastTick = true;
            // 未送达 + 已送达的插话一起要回来，<b>回填输入框而不是丢弃</b>：按 Esc 通常正是
            // 「别跑了，听我的」，那句话不该跟着一起没。已送达的那条尤其不能丢——取消走 doOnCancel，
            // handleComplete 不跑、没人补它进历史，不还给用户就是模型看过、历史没有、用户也拿不回来。
            List<String> refill = onSubmit.takeBackInterjections();
            if (current != null) { current.dispose(); current = null; }
            state.cancelCurrent();
            state.clearQueued();                         // 取消时一并清空排队消息
            if (!refill.isEmpty()) {
                inputState.setText(String.join("\n", refill));
                inputState.moveCursorToEnd();            // 光标落在文末：用户多半要接着改一改重发
            }
            state.setNotice(running || dropped > 0
                    ? "已取消当前回合" + (dropped > 0 ? "，丢弃 " + dropped + " 条排队" : "")
                            + (refill.isEmpty() ? "" : "，插话已放回输入框")
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
        // 没有菜单可补全时的裸 Tab：吞掉。configure() 解绑焦点导航后它才会走到这里，
        // 放行下去 textArea 会往输入框插一个制表符（对话框里没人想要）。也不复位补全/回溯态：它不是编辑键。
        if (k.code() == KeyCode.TAB || k.isChar('\t')) return EventResult.HANDLED;
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
        // !hasShift()：Shift+Tab 也是 code=TAB，不加守卫就会被补全吃掉、切不了权限模式
        if ((k.code() == KeyCode.TAB || k.isChar('\t')) && !k.hasShift()) { inputState.setText(m.get(slashIndex).name()); return EventResult.HANDLED; }
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

    /**
     * 清空输入框，<b>并</b>复位附件取消态——{@link #submitInput} 里十几条分支都要清空输入框
     * （/model、/clear、/compact… 各自 clear 后 return），逐处补复位必漏；漏掉的后果是
     * 「取消一次之后这个会话里再也附不上图」，而且用户完全不知道为什么。故收敛成一处。
     *
     * <p>⚠ 后续「把附件兑现成引用块」的逻辑必须写在调用本方法<b>之前</b>——本方法一跑，
     * {@code attachmentsCancelled} 就没了。
     */
    private void clearInput() {
        inputState.clear();
        attachmentsCancelled = false;
    }

    /**
     * 退出：先终止全部后台任务，再走 {@code quit()}。
     *
     * <p><b>两个退出入口共用一处</b>——{@code /exit} 与 Ctrl+C。计划只点名了 {@code /exit}，但 Ctrl+C 才是
     * 状态行上写着的那个退出键、也是实际用得最多的一个；只管一个入口等于这道清理有一半时间不生效。
     *
     * <p>清理是<b>有界</b>的（{@code SubagentRunner.shutdownBackground} 硬限 2s），与 MCP 子进程清理同一取舍：
     * 让在飞的工具调用有机会停手，但绝不为它卡住退出。
     */
    private void shutdownAndQuit() {
        // ⚠ <b>只调 shutdownBackground，绝不先调 killAllBackgroundTasks</b>。后者走
        // restartBackground：把在飞任务所在的池换成一个全新空池、再对旧池 shutdownNow 且不等。
        // 随后 shutdownBackground 的 awaitTermination(2s) 就作用在那个<b>空池</b>上——0ms 返回，
        // 真正在跑的子 agent 一秒宽限都没拿到，若正卡在 Write 中间会被切成半个文件。
        // （实测：空池 0ms 返回时，旧任务还要 800ms 才收尾。）
        // shutdownBackground 自己会先 registry.killAll 再关真池，终止任务这件事不会漏。
        onSubmit.shutdownBackground();
        quit();
    }

    /** 提交：忙时把消息入队（回合结束由 UI 批自动出队提交），空闲时立即提交。均清空输入框。 */
    private void submitInput() {
        String text = inputState.text();
        if (text == null || text.isBlank()) {
            // ⚠ 空回车也要放行刹车。状态栏在刹车时写的是「⏱ 有结果待处理 · 回车交给模型」——
            // 用户照做按下回车，若这里直接 return，那句提示就是<b>假的</b>：什么都不会发生，
            // 被扣住的结果再也送不出去，而屏幕上没有任何地方告诉他"其实得打一条真消息"。
            // 放在 addHistory 之前：空串不该进 ↑↓ 历史。
            releaseBrake();
            return;
        }
        addHistory(text);                            // 记入历史（含斜杠命令），供 ↑↓ 回溯
        String cmd = text.strip();
        if (cmd.equals("/model")) {                  // 斜杠命令：打开模型选择器（仿 Claude Code）
            clearInput();
            openModelPicker();
            return;
        }
        if (cmd.equals("/compact")) {
            clearInput();
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
            clearInput();
            if (state.isBusy()) {                    // 回合中 / 压缩中 / 有待处理模态：拒绝（见 isBusy）
                state.setNotice("忙碌中，无法清空");
                return;
            }
            // 必须在 resetForNewSession() 之前、且在上面的忙碌闸门之后：前者只清 ⏱ 面板这份 UI 镜像，
            // 任务本身还在池子里跑——不补这一刀就是「清完屏任务还在烧钱，而界面上已看不见」；
            // 后者保证被拒的 /clear 不会顺手杀掉任务（都没换会话，凭什么杀）。
            onSubmit.killAllBackgroundTasks();       // 新会话不该有旧会话的任务在跑
            onSubmit.clearContext();                 // (A) 换 sessionId
            state.resetForNewSession();              // 复位面板/排队/提示
            outputQueue.clear();                     // 严格分批后大输出可能还压在队列里没打完：与 pending 同语义一并丢弃
            // ── 第 6 条 flush 触发点（设计 §3.4）：/clear 丢表格缓冲 ──
            // 必须<b>同步</b>做在这里，不能塞进下面的 runOnRenderThread lambda——那段只在 runner
            // 非空时跑，测试态走不到。resetMarkdown 连状态机一起复位：只丢缓冲不复位，降级态会活过
            // 清屏，下一张表在遇到第一个非 `|` 行之前全部原样输出。
            printer.resetMarkdown();
            lastShownModel = "";                     // 新会话首个回合重新打「⚙ 使用模型 X」
            pendingSkill = null;                      // 清掉未发送的技能挂载：新会话不继承
            var r = runner();
            if (r != null) {                         // (B) 真清屏只在运行态做（测试态 runner==null 跳过）
                r.runOnRenderThread(() -> {
                    boolean ok = ScreenCleaner.clear(r);
                    if (ok) {
                        scrollTail.clear();   // 留底同步清空：resize 重放不该复活上一个会话的画面
                        resetTailDedup();      // 去重指针一并复位（防同引用对象跨会话误判，见 record）
                        printer.welcome(onSubmit.currentModel(),
                                io.github.javaside.springai.codetui.AppInfo.versionLabel());
                    } else {
                        pushClearDegradedNotice();
                    }
                });
            } else {
                pushClearDegradedNotice();
            }
            return;
        }
        if (cmd.equals("/context")) {          // 只读快照：任何时刻都可查（含回合进行中），不打断
            clearInput();
            ctxUsage.report();
            return;
        }
        if (cmd.equals("/skills")) {           // 只读清单：任何时刻都可查，不打断
            clearInput();
            printSkills();
            return;
        }
        if (cmd.equals("/skill")) {                  // 打开技能选择器（选中后显示为输入框上方标签，发送时加载）
            clearInput();
            openSkillPicker();
            return;
        }
        if (cmd.equals("/reload")) {                 // 重扫技能目录：运行中新增/删除的 SKILL.md 就此对模型与 /skills 生效
            clearInput();
            reloadSkills();
            return;
        }
        if (cmd.equals("/mcp")) {                    // MCP 管理面板：仅空闲可开（回合中摘工具/关连接会撞在飞调用）
            clearInput();
            if (busy()) { state.setNotice("忙碌中，无法管理 MCP"); return; }
            openMcpPicker();
            return;
        }
        if (cmd.equals("/permissions")) {       // 模式与底线进 scrollback，规则清单进面板（可删）
            clearInput();
            printPermissions();
            openPermsPanel();
            return;
        }
        if (cmd.equals("/tasks")) {             // 后台任务面板：<b>任何时候可开</b>（同 /permissions，不像 /mcp 要求空闲）
            // 不设忙碌闸门是有理由的：后台任务与当前回合跑在两套线程上，终止一个后台任务不会撞在飞的
            // 工具调用；而「回合正忙」恰恰是最想查看后台进度的时刻，此时拒绝等于在最需要时把功能关掉。
            clearInput();
            openTasksPanel();
            return;
        }
        if (cmd.equals("/continue")) {               // 续跑：上一批计划被 Esc/报错中断后，据会话里保留的 todo 从首个未完成项接着做
            clearInput();
            // 工具中立：别硬点 Task/串行——上一批若是 ParallelTasks 并行跑的，"逐个用 Task" 会把独立任务逼回串行、丢掉并行。
            // 让模型按任务独立性自选，并与先前采用的方式保持一致。
            String prompt = "继续执行上一批未完成的计划。请先回顾你的 todo 列表，从第一个尚未完成的任务开始委派子 agent 继续："
                    + "相互独立、无共享状态的子任务用 ParallelTasks 并行委派，有依赖或需共享上下文的用 Task 串行委派"
                    + "（与你先前采用的方式保持一致）；已完成的任务不要重做。若没有未完成的计划，直接说明即可。";
            // 后台任务不在 todo 的视野里：Esc 掐掉前台回合后它们照跑，而 todo 上仍是「进行中」。
            // 不说的话模型会把正在跑的活再派一遍（或把已经跑完、结果还没送出去的活重做一遍）。
            // ⚠ 空串时一个字都不能加——没用后台功能的人不该为此付噪声。
            String digest = onSubmit.backgroundDigestForContinue();
            if (!digest.isEmpty()) {
                prompt = prompt + "\n\n" + digest;
            }
            if (busy()) state.enqueue(prompt, null);   // 忙/压缩中/有在飞子 agent：排队，清空后自动出队（同普通消息）
            else dispatch(prompt, null);
            return;
        }
        // 显式排到下一回合。<b>刻意不用 Alt+Enter</b>：那个键已是输入框换行，且在区分不了修饰键的
        // 终端（Apple Terminal 等）上到达时就是裸 Enter——用户以为排了队实际走了插话，静默错路由。
        // 斜杠命令是纯文本判定，与终端无关。（`/queue 内容` 含空格，不会误开补全菜单，见 slashMenuActive）
        if (cmd.equals("/queue") || cmd.startsWith("/queue ")) {
            String body = cmd.substring("/queue".length()).strip();
            if (body.isEmpty()) {
                // 不 clearInput()：用户多半是想接着把内容打完。
                state.setNotice("用法：/queue <消息> — 排到下一回合再发");
                return;
            }
            clearInput();
            releaseBrake();
            String queuedSkill = pendingSkill;       // 一次性：同普通提交，取走挂载
            pendingSkill = null;
            if (busy()) state.enqueue(body, queuedSkill);
            else dispatch(body, queuedSkill);        // 空闲时「排队」等价于直接发，不必让用户再按一次
            return;
        }
        if (cmd.equals("/help")) {
            clearInput();
            printHelp();
            return;
        }
        if (cmd.equals("/exit") || cmd.equals("/quit")) {
            clearInput();
            shutdownAndQuit();
            return;
        }
        // ── 附件兑现（必须在 clearInput() 之前：那一跑 attachmentsCancelled 就被复位，Ctrl+X 会失效） ──
        // 位置也必须在全部斜杠命令分支<b>之后</b>：否则 "/help docs/bug.png" 这类文本也会被识别、
        // 甚至把图注进一条根本不会发给模型的命令里。
        List<DetectedImage> attached = attachmentsCancelled ? List.of() : attachments().images();
        if (!attached.isEmpty() && !onSubmit.currentModelCapabilities().supportsImageInput()) {
            // 拦住不发，且<b>绝不 clearInput()</b>：切完模型直接回车重发即可。不保留的话用户得把
            // 那段话连同路径重贴一遍，这功能不会有人用。
            // 能力必须由当前 provider 给出，不能只按裸 modelId 判定（聚合网关存在同名模型）。
            state.setNotice("当前模型 " + onSubmit.currentModel()
                    + " 不支持图片输入，用 /model 换一个（输入已保留）");
            return;
        }
        String effective = injectAttachments(text, attached, root);

        clearInput();
        String skill = pendingSkill;                 // 一次性：本条消息取走挂载
        pendingSkill = null;
        // 用户真实提交了一条消息（入队与立即提交都算）：重置自动回合刹车。刹车防的是「人不在电脑前时
        // 自动回合无限套娃」，人一开口就说明这个前提不成立了。必须挂在提交上而不是每次按键——
        // 挂按键则用户随手一个方向键就把刹车松开，等于没有刹车。
        releaseBrake();
        ConversationState.SubmissionSnapshot snapshot = state.submissionSnapshot();
        boolean subagentsInFlight = onSubmit.hasInFlightSubagents();
        SubmissionRoute route = submissionRoute(snapshot, subagentsInFlight, skill);
        if (route != SubmissionRoute.DISPATCH) {
            // 插话 vs 排队：只有「回合在飞」才会再有模型调用，插话才送得出去。压缩中、以及回合已被
            // Esc 取消只剩子 agent 收尾（两者 state 都已回 IDLE，但 busy 仍 true）都不会再调模型——
            // 插话进去会一直躺在队列里，直到用户下次发消息才被捎走。状态必须一次快照，不能先读 busy
            // 再读 isIdle：回合恰在两次读取之间结束时，会把同一次 Enter 错误路由成排队。
            // 带技能挂载的也不能走插话：插话是纯 UserMessage，带不了 submit 的第二参数，会静默丢技能。
            if (route == SubmissionRoute.INTERJECT) {
                onSubmit.interject(effective);
                // ⚠ 这里<b>刻意什么都不往 scrollback 打</b>。此刻它还没送达，而 scrollback 里的行
                // 改不了——打下去就永远停在「输入时」这个位置上，而它的真实位置在后面那条工具结果
                // 之后。未送达期间的可见性交给输入框上方的插话面板（interjectionChildren），
                // 送达时才由 CodingAgent 接的送达回调经 onUserMessage 打进信息流。
                // 这一段曾经是 pushInfo("› " + text)：位置错、样式也不是用户消息，且送达与否全靠
                // 状态栏一个数字去猜——而模型「消化了插话但不显式回应」恰恰是常态。
                return;
            }
            // 入队的同样是注入后的文本：出队时直接 dispatch，那时输入框早已换成别的内容，
            // 再想兑现附件已经无从谈起——排队的消息会静默丢图。
            state.enqueue(effective, skill);         // 反馈靠状态行的实时「已排队 N 条」，不用 sticky notice
            return;
        }
        dispatch(effective, skill);
    }

    enum SubmissionRoute { DISPATCH, INTERJECT, QUEUE }

    static SubmissionRoute submissionRoute(ConversationState.SubmissionSnapshot snapshot,
                                           boolean subagentsInFlight, String skill) {
        if (!snapshot.busy() && !subagentsInFlight) return SubmissionRoute.DISPATCH;
        if (snapshot.activeTurn() && skill == null) return SubmissionRoute.INTERJECT;
        return SubmissionRoute.QUEUE;
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
        List<ProviderModel> models = onSubmit.models();
        if (models.isEmpty()) { state.setNotice("当前没有可选模型"); return; }
        pickIndex = 0;
        String cur = onSubmit.currentModel();
        String curProvider = onSubmit.currentProviderId();
        for (int i = 0; i < models.size(); i++) {
            ProviderModel m = models.get(i);
            if (m.id().equals(cur) && m.providerId().equals(curProvider)) { pickIndex = i; break; }
        }
        pickingModel = true;
    }

    /** 选择器按键：↑↓/kj 移动、数字快选、→ 思考设置、Enter 确认、Esc 取消。始终 HANDLED（屏蔽文本编辑）。 */
    private EventResult onModelPickerKey(KeyEvent k) {
        List<ProviderModel> models = onSubmit.models();
        int n = models.size();
        if (k.isCancel()) { pickingModel = false; return EventResult.HANDLED; }
        if (k.code() == KeyCode.UP || k.isChar('k'))   { pickIndex = (pickIndex - 1 + n) % n; return EventResult.HANDLED; }
        if (k.code() == KeyCode.DOWN || k.isChar('j')) { pickIndex = (pickIndex + 1) % n;     return EventResult.HANDLED; }
        if (k.code() == KeyCode.RIGHT || k.isChar('l')) {
            ProviderModel m = models.get(pickIndex);
            ModelThinkingSettings settings = onSubmit.thinkingSettings(m.providerId(), m.id());
            if (settings == null || !settings.capabilities().configurable()) {
                state.setNotice("该模型不可配置思考模式");
                return EventResult.HANDLED;
            }
            openThinkingSettings(m.providerId(), m.id());
            return EventResult.HANDLED;
        }
        for (int i = 0; i < n && i < 9; i++) {           // 数字 1..n 快选
            if (k.isChar((char) ('1' + i))) { pickIndex = i; return EventResult.HANDLED; }
        }
        if (k.code() == KeyCode.ENTER || k.isChar('\r') || k.isChar('\n')) {
            if (busy() || state.queuedCount() > 0 || !onSubmit.pendingInterjectionTexts().isEmpty()) {
                state.setNotice("仍有消息待处理，处理完成后再切换模型");
                return EventResult.HANDLED;
            }
            ProviderModel chosen = models.get(pickIndex);
            onSubmit.selectModel(chosen.providerId(), chosen.id());
            pickingModel = false;
            // 不用 sticky notice：notice 会一直占据状态栏、遮蔽常态行（模型名 + 上下文%）直到下次按键，
            // 造成「切换模型后状态栏信息就没了」。改为下沉一行 scrollback 确认，状态栏立刻回到常态。
            state.pushInfo("⚙ 已切换模型 · " + labelWithThinking(chosen.providerId(), chosen.id(), chosen.label()));
            rememberModel(chosen.providerId(), chosen.id());     // 落盘，下次启动恢复
            lastShownModel = chosen.id();   // 避免下个回合 dispatch 再重复打「⚙ 使用模型」
            return EventResult.HANDLED;
        }
        return EventResult.HANDLED;                       // 其余按键一律吞掉，不落进输入框
    }

    /**
     * 把选中的模型记到 {@code <root>/.codetui/model.json}，下次启动自动恢复
     * （见 {@code CodeTuiApplication.restoreLastModel}）。
     *
     * <p><b>只在 selectModel 真的生效后才写</b>：{@code ProviderRegistry.select()} 对未知模型
     * 是静默忽略的，不判这一下就会把一个选不中的 id 落到盘上，下次启动再触发一次
     * 「上次用的模型现在不可用」——自己给自己制造失效记录。
     *
     * <p><b>写失败要说出来</b>：静默失败的话，下次启动还是老模型，用户只会觉得这功能坏了，
     * 而且不知道该去看什么。措辞与权限规则回写失败时的「仅本次运行生效」对齐。
     *
     * <p><b>这是持久化的唯一入口</b>：{@code selectModel} 在生产代码里当前只有本文件
     * 一个调用方（选择器的 Enter 分支）。日后若新增 {@code /model <id>} 这类直接命令、
     * 或任何其它切换模型的入口，<b>必须一并接上这里</b>，否则会出现「切了但没记住」。
     *
     * <p><b>生效探测依赖 {@code currentModel()} 讲真话</b>：{@code SubmitHandler.currentModel()}
     * 的接口默认实现返回空串，所以一个「实现了 {@code selectModel} 却没实现 {@code currentModel()}」
     * 的 handler 会让写盘<b>静默不发生</b>。生产的 {@code CodingAgent} 两个都实现了，
     * 今天咬不到人；写在这里是因为它失败时不报错、只是悄悄不记。
     */
    private void rememberModel(String providerId, String modelId) {
        if (!modelId.equals(onSubmit.currentModel())
                || !providerId.equals(onSubmit.currentProviderId())) {
            return;                          // 没生效，不留记录
        }
        if (!ModelPreference.write(root, providerId, modelId)) {
            state.pushInfo("⚠ 没能记住这个选择（仅本次运行生效）");
        }
    }

    /** 选择器面板：标题 + 每个模型一行（❯ 高亮当前、✓ 标记在用、右侧暗色说明）。 */
    private Element[] modelPickerChildren() {
        List<ProviderModel> models = onSubmit.models();
        String cur = onSubmit.currentModel();
        String curProvider = onSubmit.currentProviderId();
        Set<String> dupes = duplicateModelIds(models);
        List<Element> els = new ArrayList<>();
        els.add(text("  选择模型（↑↓ 选择 · Enter 切换 · → 思考设置 · Esc 取消）").style(PICK_TITLE));
        for (int i = 0; i < models.size(); i++) {
            ProviderModel m = models.get(i);
            boolean sel = i == pickIndex;
            boolean active = m.id().equals(cur) && m.providerId().equals(curProvider);
            String marker = (sel ? "❯ " : "  ") + (active ? "✓ " : "  ");
            String summary = thinkingSummary(m.providerId(), m.id());
            String label = dupes.contains(m.id()) ? m.label() + " · " + m.providerId() : m.label();
            els.add(text("  " + marker + (i + 1) + ". " + label + "   " + m.desc()
                    + "   " + summary)
                    .style(sel ? PICK_SEL : (active ? PICK_ITEM : PICK_DESC)));
        }
        return els.toArray(new Element[0]);
    }

    /** 跨 provider 重名的模型 id 集合：只有这些条目需要在展示名后标注来源（providerId），其余保持原样。 */
    private static Set<String> duplicateModelIds(List<ProviderModel> models) {
        Map<String, Integer> counts = new HashMap<>();
        for (ProviderModel m : models) {
            counts.merge(m.id(), 1, Integer::sum);
        }
        Set<String> dupes = new LinkedHashSet<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > 1) {
                dupes.add(e.getKey());
            }
        }
        return dupes;
    }

    /** 模型行的思考摘要；不可配置/桩 handler 返回「—」。 */
    private String thinkingSummary(String providerId, String modelId) {
        ModelThinkingSettings settings = onSubmit.thinkingSettings(providerId, modelId);
        if (settings == null) return "—";
        return settings.summary();
    }

    /**
     * 状态栏模型标签：思考配置非默认时，追加括号内原生强度（如 {@code deepseek-v4-pro（high）}）。
     */
    String statusModelLabel() {
        String providerId = onSubmit.currentProviderId();
        String model = onSubmit.currentModel();
        return labelWithThinking(providerId, model, model);
    }

    /**
     * 把「展示名 + 思考强度」拼成一行显示标签。思考配置为默认时不追加（官方决定、随模型演进），
     * 非默认时追加括号内原生强度（如 {@code deepseek-v4-pro（high）}）；配置因模型能力变化而失效时，
     * 只降级返回原展示名——显示逻辑绝不让界面崩溃。状态栏与「已切换模型」确认行共用此方法，保证一致。
     */
    private String labelWithThinking(String providerId, String modelId, String label) {
        try {
            ModelThinkingSettings settings = onSubmit.thinkingSettings(providerId, modelId);
            if (settings == null || settings.config().mode() == ThinkingMode.DEFAULT) {
                return label;
            }
            return label + "（" + settings.summary() + "）";
        } catch (RuntimeException e) {
            return label;
        }
    }

    private void openThinkingSettings(String providerId, String modelId) {
        ModelThinkingSettings settings = onSubmit.thinkingSettings(providerId, modelId);
        if (settings == null) return;
        thinkingTarget = modelId;
        thinkingTargetProvider = providerId;
        thinkingDraft = settings.config();
        thinkingRow = 0;
        editingBudget = false;
        configuringThinking = true;
    }

    /** 二级设置按键：↑↓ 选行、←→ 调整、Enter 保存、Esc 放弃。 */
    private EventResult onThinkingSettingsKey(KeyEvent k) {
        ModelThinkingSettings settings = onSubmit.thinkingSettings(thinkingTargetProvider, thinkingTarget);
        if (settings == null) { configuringThinking = false; return EventResult.HANDLED; }
        ThinkingCapabilities caps = settings.capabilities();

        if (editingBudget) {
            if (k.code() == KeyCode.ENTER || k.isChar('\r') || k.isChar('\n')) {
                commitBudgetEdit(caps);
                return EventResult.HANDLED;
            }
            if (k.isCancel()) {
                editingBudget = false;
                budgetInput.setText("");
                return EventResult.HANDLED;
            }
            if (k.code() == KeyCode.BACKSPACE) {
                String t = budgetInput.text();
                if (!t.isEmpty()) budgetInput.setText(t.substring(0, t.length() - 1));
                return EventResult.HANDLED;
            }
            if (k.code() == KeyCode.CHAR && Character.isDigit(k.string().charAt(0))) {
                budgetInput.setText(budgetInput.text() + k.string());
                return EventResult.HANDLED;
            }
            return EventResult.HANDLED;
        }

        if (k.isCancel()) {
            configuringThinking = false;
            editingBudget = false;
            budgetInput.setText("");
            return EventResult.HANDLED;
        }
        if (k.code() == KeyCode.UP || k.isChar('k')) {
            thinkingRow = strengthRowVisible(caps) ? 0 : 0;
            return EventResult.HANDLED;
        }
        if (k.code() == KeyCode.DOWN || k.isChar('j')) {
            if (strengthRowVisible(caps)) thinkingRow = 1;
            return EventResult.HANDLED;
        }
        if (k.code() == KeyCode.LEFT || k.isChar('h')) {
            cycleValue(caps, -1);
            return EventResult.HANDLED;
        }
        if (k.code() == KeyCode.RIGHT || k.isChar('l')) {
            cycleValue(caps, 1);
            return EventResult.HANDLED;
        }
        if (k.code() == KeyCode.ENTER || k.isChar('\r') || k.isChar('\n')) {
            // budget 行 Enter 进入数值编辑；否则保存
            if (thinkingRow == 1 && caps.strengthKind() == ThinkingStrengthKind.TOKEN_BUDGET) {
                editingBudget = true;
                budgetInput.setText(thinkingDraft.thinkingBudget() == null
                        ? "" : String.valueOf(thinkingDraft.thinkingBudget()));
                return EventResult.HANDLED;
            }
            saveThinking();
            return EventResult.HANDLED;
        }
        return EventResult.HANDLED;
    }

    private void saveThinking() {
        boolean saved = onSubmit.saveThinkingSettings(thinkingTargetProvider, thinkingTarget, thinkingDraft);
        configuringThinking = false;
        editingBudget = false;
        budgetInput.setText("");
        if (!saved) {
            state.pushInfo("⚠ 没能记住这个思考设置（仅本次运行生效）");
        }
    }

    private void commitBudgetEdit(ThinkingCapabilities caps) {
        String text = budgetInput.text().trim();
        if (text.isEmpty()) {
            editingBudget = false;
            budgetInput.setText("");
            return;
        }
        try {
            int value = Integer.parseInt(text);
            ThinkingConfig candidate = ThinkingConfig.enabledBudget(value);
            caps.validate(candidate);
            thinkingDraft = candidate;
        } catch (RuntimeException e) {
            state.setNotice("预算必须是正整数，且不超过模型上限");
        }
        editingBudget = false;
        budgetInput.setText("");
    }

    private boolean strengthRowVisible(ThinkingCapabilities caps) {
        return caps.strengthKind() != ThinkingStrengthKind.NONE;
    }

    private void cycleValue(ThinkingCapabilities caps, int direction) {
        if (thinkingRow == 0) {
            thinkingDraft = cycleMode(caps, direction);
            return;
        }
        if (thinkingRow == 1) {
            if (thinkingDraft.mode() != ThinkingMode.ENABLED) {
                return;   // 默认/关闭时强度不可编辑
            }
            if (caps.strengthKind() == ThinkingStrengthKind.EFFORT) {
                List<String> values = caps.effortValues();
                if (values.isEmpty()) return;
                String current = thinkingDraft.effort();
                int idx = current == null ? -1 : values.indexOf(current);
                int next = (idx + direction + values.size()) % values.size();
                thinkingDraft = ThinkingConfig.enabledEffort(values.get(next));
            }
        }
    }

    private ThinkingConfig cycleMode(ThinkingCapabilities caps, int direction) {
        List<ThinkingMode> modes = new ArrayList<>();
        modes.add(ThinkingMode.DEFAULT);
        modes.add(ThinkingMode.ENABLED);
        if (caps.supportsDisable()) modes.add(ThinkingMode.DISABLED);
        int idx = modes.indexOf(thinkingDraft.mode());
        int next = (idx + direction + modes.size()) % modes.size();
        ThinkingMode mode = modes.get(next);
        if (mode == ThinkingMode.DEFAULT) return ThinkingConfig.defaults();
        if (mode == ThinkingMode.DISABLED) return ThinkingConfig.disabled();
        if (caps.strengthKind() == ThinkingStrengthKind.EFFORT && !caps.effortValues().isEmpty()) {
            return ThinkingConfig.enabledEffort(caps.effortValues().get(0));
        }
        return ThinkingConfig.enabledWithoutStrength();
    }

    private Element[] thinkingSettingsChildren() {
        // scope(boolean, ...) 的第二个参数每次 render 都会立即求值（见 render 里所有 children 方法），
        // 未进入二级面板时 thinkingTarget 为 null，必须先在这里挡掉，否则 onSubmit.thinkingSettings(null, …)
        // 会一路抛到 ProviderRegistry。
        if (thinkingTarget == null) return new Element[0];
        ModelThinkingSettings settings = onSubmit.thinkingSettings(thinkingTargetProvider, thinkingTarget);
        if (settings == null) return new Element[0];
        ThinkingCapabilities caps = settings.capabilities();
        List<Element> els = new ArrayList<>();
        els.add(text("  " + settings.label() + " · 思考设置").style(PICK_TITLE));
        String mode = switch (thinkingDraft.mode()) {
            case DEFAULT -> "默认";
            case ENABLED -> "开启";
            case DISABLED -> "关闭";
        };
        els.add(text("  " + rowMarker(0) + "模式       " + mode).style(thinkingRow == 0 ? PICK_SEL : PICK_DESC));
        if (strengthRowVisible(caps)) {
            String strength = switch (thinkingDraft.mode()) {
                case DEFAULT -> "官方默认";
                case DISABLED -> "—";
                case ENABLED -> thinkingDraft.effort() != null
                        ? thinkingDraft.effort()
                        : thinkingDraft.thinkingBudget() != null
                                ? (editingBudget ? budgetInput.text() + "▏" : thinkingDraft.thinkingBudget() + " tokens")
                                : "开启";
            };
            els.add(text("  " + rowMarker(1) + "强度       " + strength)
                    .style(thinkingRow == 1 ? PICK_SEL : PICK_DESC));
        }
        els.add(text("  ↑↓ 选择 · ←→ 调整 · Enter 保存 · Esc 放弃").style(PICK_DESC));
        return els.toArray(new Element[0]);
    }

    private String rowMarker(int row) {
        return thinkingRow == row ? "❯ " : "  ";
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

    /**
     * 技能选择器面板：标题 + 以高亮项为中心的固定窗口。
     *
     * <p>不能把全部技能都塞进 InlineDisplay：面板高度超过终端后，运行器每次重绘按 preferredSize
     * 扩缩显示区，终端又会滚动/重排，二者互相追赶就表现为整屏不停闪动、上下晃动。固定可见行数后，
     * 面板高度稳定；高亮移出窗口时才平移内容，仍可遍历并选择全部技能。
     */
    private Element[] skillPickerChildren() {
        List<SkillInfo> list = onSubmit.skills();
        if (list.isEmpty()) return new Element[0];
        int sel = clampIndex(pickIndex, list.size());
        int visible = Math.min(SKILL_PICKER_CAP, list.size());
        int from = Math.max(0, Math.min(sel - visible / 2, list.size() - visible));
        int to = from + visible;
        List<Element> els = new ArrayList<>();
        els.add(text("  选择技能（↑↓ 选择 · Enter 挂载 · Esc 取消）").style(PICK_TITLE));
        for (int i = from; i < to; i++) {
            SkillInfo s = list.get(i);
            boolean selected = i == sel;
            String marker = selected ? "❯ " : "  ";
            els.add(text("  " + marker + (i + 1) + ". " + s.name() + "  [" + s.source() + "]   " + s.description())
                    .style(selected ? PICK_SEL : PICK_DESC));
        }
        if (list.size() > visible) {
            els.add(text("  显示 " + (from + 1) + "-" + to + " / 共 " + list.size() + " 个技能").style(DIM));
        }
        return els.toArray(new Element[0]);
    }

    /** 测试专用：读取技能选择器当前窗口。 */
    Element[] skillPickerChildrenForTest() { return skillPickerChildren(); }

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
        // !hasShift()：同 onSlashMenuKey，别把 Shift+Tab（权限模式循环）当成展开键
        if ((k.code() == KeyCode.TAB || k.isChar('\t')) && !k.hasShift()) { mcpExpanded = !mcpExpanded; return EventResult.HANDLED; }
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
                mcpConnecting = null;                            // 事件驱动：后台线程写本地 UI 状态，
                publishLocalViewChange();                        // 必须主动唤醒（旧的「下一帧即看到」没了）
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
        if (list.isEmpty()) return new Element[0];               // scope 每次 render eager 求值：首行判空
        int sel = clampIndex(pickIndex, list.size());
        List<Element> els = new ArrayList<>();
        els.add(text("  MCP 服务器（↑↓ 选择 · Enter 启用/禁用 · Tab 查看工具 · Esc 关闭）").style(PICK_TITLE));
        for (int i = 0; i < list.size(); i++) {
            McpRegistry.ServerView v = list.get(i);
            boolean isSel = i == sel;
            boolean connecting = v.name().equals(mcpConnecting);
            // connecting（本地变量）是 /mcp 面板里手动 enable 在飞；Status.CONNECTING 是启动期后台连接。
            // 两者显示一致，来源不同——前者只在本面板内为真，后者由 registry 报。
            String mark = connecting ? "⟳" : switch (v.status()) {
                case CONNECTED -> "✓";
                case DISABLED -> "○";
                case FAILED -> "✗";
                case CONNECTING -> "⟳";
            };
            String layer = v.source() == McpConfigLoader.ConfigSource.PROJECT ? "[项目级]" : "[用户级]";
            String detail = connecting ? "连接中…" : switch (v.status()) {
                case CONNECTED -> "已连接 · " + v.toolCount() + " 工具";
                case DISABLED -> "已禁用";
                case FAILED -> "连接失败：" + brief(v.error());
                case CONNECTING -> "连接中…";
            };
            els.add(text("  " + (isSel ? "❯ " : "  ") + mark + " " + (i + 1) + ". " + v.name()
                    + "  " + layer + " " + detail)
                    .style(isSel ? PICK_SEL : PICK_ITEM));
            if (isSel && mcpExpanded) {
                if (v.toolNames().isEmpty()) {
                    // 启动期还在连的别说「未连接」——那是在报一个还没发生的失败。
                    els.add(text(v.status() == McpRegistry.Status.CONNECTING
                            ? "        （连接中，工具尚未发现）" : "        （未连接，无工具信息）").style(PICK_DESC));
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
    /** 可作答性：至少 1 问、且每问至少 1 个选项（否则 onAskKey 的 `% n` 会除零崩线程，见 UI 批的降级）。 */
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
     * 取消一个由模态请求发起的回合：先唤醒阻塞的工具线程（{@link ModalRequest#cancel()}——问询转
     * {@code responder.cancel()}、审批投 {@code CANCEL}），再 dispose + cancelCurrent 让
     * {@code doOnCancel} 回滚会话——否则半截的 {@code assistant(tool_calls)} 会残留、下条消息 400。
     * 问询 Esc、畸形问询降级、审批「拒绝并中断本回合」/Esc 共用此路径，保证都走同一套已验证的回滚
     * （见记忆 cancel-tool-turn-leaves-dangling-toolcalls）。
     *
     * <p>{@code cancelCurrent()} 里的 {@code clearModals()} 会顺带唤醒<b>其余</b> pending 模态：
     * 回合结束了，它们的工具线程也必须醒（漏一个就是永久 park）。本请求已由调用方先摘出队列，
     * 不会收到第二个信号——即便收到也无害，应答口是一次性消费的。
     */
    private void cancelTurnFor(ModalRequest req, String notice) {
        req.cancel();
        if (current != null) { current.dispose(); current = null; }
        state.cancelCurrent();
        state.clearQueued();
        state.setNotice(notice);
    }

    /** 清作答态并从 state 的模态队列摘除该问询（避免 UI 批再次进入）。 */
    private void clearAskState() {
        AskRequest done = activeAsk;                 // 先取引用再置 null：否则 removeModal(null) 摘不掉，UI 批会反复重入
        activeAsk = null; askQ = 0; askOpt = 0; askAnswers.clear(); askChecked.clear();
        askFreeText = false; askInput.clear();
        state.removeModal(done);
    }

    /** 作答面板：进度 + header + 问题文本 + 逐项选项（单选 ❯ 高亮）。 */
    private Element[] askChildren() {
        // scope(cond, el) 会「先构造 el 再按 cond 决定是否显示」——即本方法每次 render 都被调用（含非作答态），
        // 故必须先 null 判空，否则 activeAsk==null 时解引用会在渲染线程崩（单测只驱动按键、不跑 render，漏掉此路径）。
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

    // ── 权限审批面板 ─────────────────────────────────────────────────────
    /** 审批面板的一个选项：文案 + 选中它喂回工具线程的结果。 */
    record PermOption(String label, PermissionOutcome outcome) {}

    /**
     * 一次审批请求的可选项（顺序即高亮下标，也即数字快选键 1..n）。
     *
     * <p><b>{@code suggested() == null} 时隐去「本会话不再问」「永久允许」两项</b>：内置危险检查与
     * ask 规则命中走的是 {@code PermissionDecision.askOnly}，没有建议规则——加任何 allow 规则都消不掉
     * 下次的询问，{@code PermissionCallback.remember} 会把这两个结果降级成「仅放行本次、什么都不记」。
     * 显示出来就是骗人：用户按下「不再询问」，下次照样被问。
     */
    static List<PermOption> permOptions(PermissionRequest r) {
        List<PermOption> out = new ArrayList<>();
        out.add(new PermOption("允许一次", PermissionOutcome.ALLOW_ONCE));
        if (r != null && r.suggested() != null) {
            out.add(new PermOption("允许，本会话不再问", PermissionOutcome.ALLOW_SESSION));
            out.add(new PermOption("允许，永久（写入项目 permissions.json）", PermissionOutcome.ALLOW_ALWAYS));
        }
        out.add(new PermOption("拒绝，让模型换个做法", PermissionOutcome.DENY));
        out.add(new PermOption("拒绝并中断本回合", PermissionOutcome.CANCEL));
        return out;
    }

    /**
     * 审批按键：↑↓/kj 移动、1–n 移动高亮（不隐式确认）、Enter 确认、Esc = 中断本回合。始终 HANDLED。
     *
     * <p><b>拒绝 ≠ 取消回合</b>：选「拒绝，让模型换个做法」只喂回 DENY、回合继续；「拒绝并中断本回合」/ Esc
     * 才走既有 {@link #cancelTurnFor}（dispose + doOnCancel 回滚会话，否则残留悬空 tool_calls、下轮 400）。
     *
     * <p><b>不得存在「既不应答也不取消」的出口</b>：本方法只有「移动高亮（面板留着，用户仍能应答）」与
     * 「应答并退出」两类结果——请求背后 park 着一个持有回合的工具线程，面板卡住就是 agent 静默挂死。
     */
    private EventResult onPermissionKey(KeyEvent k) {
        List<PermOption> opts = permOptions(activePermission);
        int n = opts.size();
        permOpt = clampIndex(permOpt, n);
        if (k.isCancel()) { finishPermission(PermissionOutcome.CANCEL); return EventResult.HANDLED; }
        if (k.code() == KeyCode.UP || k.isChar('k'))   { permOpt = (permOpt - 1 + n) % n; return EventResult.HANDLED; }
        if (k.code() == KeyCode.DOWN || k.isChar('j')) { permOpt = (permOpt + 1) % n;     return EventResult.HANDLED; }
        for (int i = 0; i < n && i < 9; i++) {           // 数字 1..n 快选（越界数字被下面兜底吞掉，不移动高亮）
            if (k.isChar((char) ('1' + i))) { permOpt = i; return EventResult.HANDLED; }
        }
        if (k.code() == KeyCode.ENTER || k.isChar('\r') || k.isChar('\n')) {
            finishPermission(opts.get(permOpt).outcome());
            return EventResult.HANDLED;
        }
        return EventResult.HANDLED;   // 其余键一律吞掉，不落进输入框
    }

    /**
     * 应答并退出审批模态；CANCEL 额外走既有回合取消路径。
     *
     * <p>先摘队列再应答：{@code removeModal} 按<b>身份</b>摘（两个请求都是 record，按 equals 会摘错人、
     * 让被误摘者的线程永久 park）。CANCEL 分支不自己 respond——交给 {@link #cancelTurnFor} 走
     * {@code ModalRequest.cancel()}，与问询共用同一套已验证的回滚。
     */
    private void finishPermission(PermissionOutcome outcome) {
        PermissionRequest req = activePermission;
        activePermission = null;
        permOpt = 0;
        state.removeModal(req);
        if (outcome == PermissionOutcome.CANCEL) {
            cancelTurnFor(req, "已取消当前回合");
            return;
        }
        req.responder().respond(outcome);
        // 记规则的结果<b>不在这里打</b>：应答只是唤醒工具线程，写盘在它醒来之后才做，
        // 此处打出的任何完成时描述都早于事实，且写盘失败也无从更正（PermissionOutcome 没有回传通道）。
        // 改由工具线程写完后经 AgentListener.onRuleRecorded 回报——那一侧才知道成败与落点。
    }

    /**
     * 审批面板：标题（工具名 + 来源）+ 目标 + 原因 + 建议规则 + 动态选项。
     * 高亮走纯前景 {@link Theme#PICK_SEL}（底色条会串到下一项，见 Theme.PICK_SEL 注释）。
     */
    private Element[] permissionChildren() {
        // scope 每次 render eager 求值：首行必须判空，否则非审批态在渲染线程崩
        if (activePermission == null) return new Element[0];
        PermissionRequest r = activePermission;
        List<PermOption> opts = permOptions(r);
        int sel = clampIndex(permOpt, opts.size());
        List<Element> els = new ArrayList<>();
        String from = r.taskId() == null ? "" : "（来自子 agent）";
        els.add(text("  ⚠ 需要授权：" + r.toolName() + from).style(PICK_TITLE));
        String target = summarizeOneLine(r.target());
        if (!target.isEmpty()) els.add(text("     " + target).style(PICK_ITEM));
        String reason = summarizeOneLine(r.reason());
        if (!reason.isEmpty()) els.add(text("     ↑ " + reason).style(DIM));
        if (r.suggested() != null) {
            els.add(text("     ↳ 允许后将记下规则：" + summarizeOneLine(r.suggested().toDsl())).style(DIM));
        } else {
            // 少了「本会话 / 永久」两项时说清楚，否则用户只会以为面板漏了选项（实地反馈来的）。
            // 措辞刻意<b>不点名</b>具体原因：suggested == null 有两类来源——① 内置底线 / ask 规则命中
            // （排在 allow 之前，加任何规则都消不掉这次询问）；② 引擎给不出一条「下次还能命中」的安全规则
            // （命令拆不动、目标解析不出、URL 取不到域名）。而 askOnly 与 ask(reason, null) 在
            // PermissionDecision 里是<b>同一个值</b>，面板无从分辨，点名就会在另一类上说错话。
            els.add(text("     ⓘ 这类调用不提供「本会话 / 永久」，每次都会问（原因见上一行）").style(DIM));
        }
        for (int i = 0; i < opts.size(); i++) {
            boolean isSel = i == sel;
            els.add(text("  " + (isSel ? "❯ " : "  ") + (i + 1) + ". " + opts.get(i).label())
                    .style(isSel ? PICK_SEL : PICK_DESC));
        }
        return els.toArray(new Element[0]);
    }

    /** 审批态状态行文本（包私以便单测断言）：选项数随 suggested 变，快选提示也随之变。 */
    String permStatusText() {
        if (activePermission == null) return "";
        int n = permOptions(activePermission).size();
        return "⏸ 等待授权 (" + activePermission.toolName() + ") · ↑↓ 选择 · 1-" + n
                + " 快选 · Enter 确认 · Esc 中断";
    }

    // ── 计划审批面板（ExitPlanMode） ─────────────────────────────────────
    /** 计划审批的一个选项：文案 + 选中它喂回工具线程的结果。 */
    private record PlanOption(String label, String desc, PlanOutcome outcome) {}

    /**
     * 三个固定选项（顺序即高亮下标，也即数字快选键 1–3）。
     *
     * <p>与审批面板不同，这里<b>没有</b>随请求变化的形态：批准后切哪一档、留在 PLAN 继续完善，
     * 三条路对任何计划都成立。CANCEL 不占选项位——它是 Esc（与「拒绝并中断本回合」同义），
     * 列出来会让「三选一」变成「四选一」，而计划本就没有「拒绝但回合继续」这种中间态。
     */
    private static final List<PlanOption> PLAN_OPTIONS = List.of(
            new PlanOption("批准，自动接受编辑", "工作区内的改动不再逐个问", PlanOutcome.APPROVE_ACCEPT_EDITS),
            new PlanOption("批准，逐个确认", "回到默认模式，写操作仍会询问", PlanOutcome.APPROVE_DEFAULT),
            new PlanOption("继续完善计划", "留在计划模式，把你的反馈带回给模型", PlanOutcome.KEEP_PLANNING));

    /**
     * 计划审批按键：↑↓/kj 移动、1–3 移动高亮（不隐式确认）、Enter 确认、Esc = 中断本回合。始终 HANDLED。
     *
     * <p>选中第三项进<b>自由文本子模式</b>先收一段反馈（照作答面板的「其他」子模式，见 {@link #onAskKey}）：
     * 子模式内 Esc 只退回选项态——面板仍在、仍能应答，不是死胡同；真要中断在选项态再按一次 Esc。
     *
     * <p><b>不得存在「既不应答也不取消」的出口</b>：请求背后 park 着一个持有回合的工具线程，
     * 面板卡住就是 agent 静默挂死。故本方法的每条路径只有三类结果——移动高亮 / 进出子模式（面板留着）、
     * 应答并退出、中断回合。
     */
    private EventResult onPlanKey(KeyEvent k) {
        int n = PLAN_OPTIONS.size();
        planOpt = clampIndex(planOpt, n);
        if (planFeedback) {   // 自由文本子模式：Esc 退回选项态、Enter 提交、可打印键输入、其余吞掉
            if (k.isCancel()) { planFeedback = false; planInput.clear(); return EventResult.HANDLED; }
            if (k.code() == KeyCode.ENTER || k.isChar('\r') || k.isChar('\n')) {
                finishPlan(PlanOutcome.KEEP_PLANNING, planInput.text());
                return EventResult.HANDLED;
            }
            if (k.code() == KeyCode.BACKSPACE) { planInput.deleteBackward(); return EventResult.HANDLED; }
            // insert(String)：用 k.string()（Character.toChars）而非 char，兼容星平面码点（emoji 等）。
            // ⚠ 不能转交 inputKeys：它绑的是主输入框的 inputState，反馈会打进输入框而 planInput 一直是空的。
            if (k.code() == KeyCode.CHAR) { planInput.insert(k.string()); return EventResult.HANDLED; }
            return EventResult.HANDLED;
        }
        if (k.isCancel()) { finishPlan(PlanOutcome.CANCEL, ""); return EventResult.HANDLED; }
        if (k.code() == KeyCode.UP || k.isChar('k'))   { planOpt = (planOpt - 1 + n) % n; return EventResult.HANDLED; }
        if (k.code() == KeyCode.DOWN || k.isChar('j')) { planOpt = (planOpt + 1) % n;     return EventResult.HANDLED; }
        for (int i = 0; i < n; i++) {                    // 1–3 快选（越界数字被下面兜底吞掉，不移动高亮）
            if (k.isChar((char) ('1' + i))) { planOpt = i; return EventResult.HANDLED; }
        }
        if (k.code() == KeyCode.ENTER || k.isChar('\r') || k.isChar('\n')) {
            PlanOption chosen = PLAN_OPTIONS.get(planOpt);
            if (chosen.outcome() == PlanOutcome.KEEP_PLANNING) {   // 先收反馈，不立刻应答
                planFeedback = true;
                planInput.clear();
                return EventResult.HANDLED;
            }
            finishPlan(chosen.outcome(), "");
            return EventResult.HANDLED;
        }
        return EventResult.HANDLED;   // 其余键一律吞掉，不落进输入框
    }

    /**
     * 应答并退出计划模态；CANCEL 额外走既有回合取消路径。
     *
     * <p>与 {@link #finishPermission} 同形：先摘队列（{@code removeModal} 按<b>身份</b>摘——都是 record，
     * 按 equals 会摘错人、让被误摘者的线程永久 park），CANCEL 分支不自己 respond，交给
     * {@link #cancelTurnFor} 走 {@code ModalRequest.cancel()}，与问询/审批共用同一套已验证的回滚。
     */
    private void finishPlan(PlanOutcome outcome, String feedback) {
        PlanRequest req = activePlan;
        resetPlanUi();
        state.removeModal(req);
        if (outcome == PlanOutcome.CANCEL) {
            cancelTurnFor(req, "已取消当前回合");
            return;
        }
        req.responder().respond(outcome, feedback == null ? "" : feedback);
        // 下沉一行确认（不用 sticky notice：它会一直占着状态栏、遮蔽常态行直到下次按键）。
        // 措辞不写「已切到 X 模式」：切模式由消费 outcome 的一侧做，这里无从确认它成功了（别臆造既成事实）。
        switch (outcome) {
            case APPROVE_ACCEPT_EDITS -> state.pushInfo("✓ 已批准计划 · 自动接受编辑");
            case APPROVE_DEFAULT      -> state.pushInfo("✓ 已批准计划 · 逐个确认");
            default                   -> state.pushInfo("✎ 继续完善计划"
                    + (feedback == null || feedback.isBlank() ? "" : "：" + summarizeOneLine(feedback)));
        }
    }

    /** 清计划态（不碰队列）：外部取消后 UI 批的「队首已不是它」分支与 {@link #finishPlan} 共用。 */
    private void resetPlanUi() {
        activePlan = null;
        planOpt = 0;
        planFeedback = false;
        planInput.clear();
    }

    /**
     * 计划正文下沉 scrollback：走<b>既有</b>助手 markdown 路径（{@link ScrollbackPrinter}）。
     *
     * <p><b>必须按 {@code \n} 逐行拆</b>：一个 println = 一个物理行，整块多行字符串会被塌成一行截断
     * （同 {@code ConversationState.flushStreaming}）。md 渲染器逐行推进代码围栏状态，正是按行喂的。
     *
     * <p><b>严格分批</b>：正文经 {@link #outputQueue} 逐行预算提交（计划可达几十行，与其它大输出
     * 一样不允许独占 UI 线程）；拆行后的逻辑行顺序入队，与本批其它输出依次消费。
     */
    private void printPlan(String plan) {
        outputQueue.enqueue(v -> printer.assistantCursor(""));   // 与上文留白分隔
        for (String line : plan.split("\n", -1)) {
            outputQueue.enqueue(v -> printer.assistantCursor(line));
        }
        // ── 第 5 条 flush 触发点（设计 §3.4）：整篇 ASSISTANT 文档灌完时收尾 flush ──
        // 这里<b>绕过</b> enqueueOutputLine，第 2 条一条都不经过；而 onPlanSubmitted 不改状态
        // （此刻是 RUNNING_TOOL）。计划正文以表格结尾很常见，不补这一条用户就要在<b>看不见那张表</b>
        // 的情况下批准计划。规则一般化：任何往队列灌整篇 ASSISTANT 文档的地方，收尾必须 flush。
        outputQueue.enqueue(v -> printer.tableFlushCursor());
    }

    /**
     * 计划面板：标题 + 三个选项；选了「继续完善计划」则改渲染输入提示 + 反馈回显。
     * 高亮走纯前景 {@link Theme#PICK_SEL}（底色条会串到下一项，见 Theme.PICK_SEL 注释）。
     */
    private Element[] planChildren() {
        // scope 每次 render eager 求值：首行必须判空，否则非计划态在渲染线程崩
        if (activePlan == null) return new Element[0];
        List<Element> els = new ArrayList<>();
        els.add(text("  📋 计划待批准（正文见上方）").style(PICK_TITLE));
        if (planFeedback) {
            els.add(text("     希望怎么改？Enter 提交 · Esc 返回选项").style(DIM));
            els.add(text("     ❯ " + planInput.text()).style(PICK_TITLE));
            return els.toArray(new Element[0]);
        }
        int sel = clampIndex(planOpt, PLAN_OPTIONS.size());
        for (int i = 0; i < PLAN_OPTIONS.size(); i++) {
            PlanOption o = PLAN_OPTIONS.get(i);
            boolean isSel = i == sel;
            els.add(text("  " + (isSel ? "❯ " : "  ") + (i + 1) + ". " + o.label() + "   " + o.desc())
                    .style(isSel ? PICK_SEL : PICK_DESC));
        }
        return els.toArray(new Element[0]);
    }

    /** 计划审批态状态行文本（包私以便单测断言）：区分选项态与自由文本子模式。 */
    String planStatusText() {
        if (activePlan == null) return "";
        if (planFeedback) return "输入反馈 · Enter 提交 · Esc 返回选项";
        return "⏸ 计划待批准 · ↑↓ 选择 · 1-3 快选 · Enter 确认 · Esc 中断";
    }

    /** 折叠空白到一行 + 按显示宽度截断（守住「一行内容一物理行」，长命令不撑爆面板）。 */
    private String summarizeOneLine(String s) {
        if (s == null) return "";
        String one = s.replaceAll("\\s+", " ").trim();
        int max = Math.max(20, terminalWidth() - 8);
        return displayWidth(one) <= max ? one
                : dev.tamboui.text.CharWidth.substringByWidth(one, max - 1) + "…";
    }

    /** /help：把可用命令与快捷键打进 scrollback（灰色信息行）。 */
    private void printHelp() {
        state.pushInfo("可用命令：");
        for (SlashCommand c : COMMANDS) state.pushInfo("  " + c.name() + "   " + c.desc());
        state.pushInfo("快捷键：Enter 发送 · \\+Enter 换行 · Esc 取消 · Ctrl+C 退出");
        state.pushInfo("编辑：Ctrl+A/E 行首尾 · Ctrl/Alt+←→ 按词跳 · Ctrl+W 删前词 · Ctrl+U/K 删至行首/尾");
        state.pushInfo("权限：Shift+Tab 切换模式 · /permissions 查看规则");
    }

    /**
     * {@code /permissions}：模式与内置底线进 scrollback，规则清单进{@link #permsPanelChildren 交互面板}。
     *
     * <p><b>为什么两处分开</b>：模式与内置底线是<b>信息</b>（读一遍就够，且底线不可删），
     * 规则是<b>可操作的列表</b>。把信息也塞进面板只会挤占本就有限的行数；
     * 把列表留在 scrollback 则没法选中、更没法删。
     */
    private void printPermissions() {
        state.pushInfo("权限模式：" + onSubmit.permissionMode().label() + "（Shift+Tab 循环切换）");
        // ⚠ 这行文案曾写着「任何 allow 规则与 BYPASS 都盖不住」——后半句是<b>假的</b>：
        // doDecide 的 BYPASS 分支排在内置检查之前直接返回，那一档只留痕不拦截。
        // 用户读着这句话去开 BYPASS，以为底线还在，这是最坏的一种文档错误。
        state.pushInfo("内置底线（任何 allow 规则都盖不住，命中即询问）：");
        state.pushInfo("  写 .ssh/.aws/.kube/.gnupg/.git/.codetui 配置、写 shell 启动文件、"
                + "读私钥与凭据、rm -rf / 或 ~ 或变量目标");
    }

    // ── /permissions 规则面板 ────────────────────────────────────────────
    /**
     * 打开权限规则面板。<b>零规则也照常打开</b>——面板会说明规则写在哪，比什么都不弹更有用。
     */
    private void openPermsPanel() {
        permsIndex = 0;
        permsPendingDelete = null;
        pickingPerms = true;
    }

    /**
     * 面板按键：↑↓/kj 移动、{@code d} 请求删除、Esc 关闭；确认态只认 Enter（确认）/ Esc（取消）。始终 HANDLED。
     *
     * <p><b>删除键是 {@code d} 而不是 Enter</b>（与 {@code /mcp} 面板刻意不同）：那边 Enter 切换启用状态，
     * 是可逆的；删规则不可逆——尤其删 deny 等于<b>放宽权限</b>——不该和「移动光标后顺手回车」共用一个键。
     */
    private EventResult onPermsPanelKey(KeyEvent k) {
        List<PermissionRule> rules = onSubmit.permissionRules();
        if (permsPendingDelete != null) {                 // 确认态：只认 Enter / Esc，别的键一概忽略
            if (k.isCancel()) { permsPendingDelete = null; return EventResult.HANDLED; }
            if (k.code() == KeyCode.ENTER || k.isChar('\r') || k.isChar('\n')) {
                confirmPermsDelete();
                return EventResult.HANDLED;
            }
            return EventResult.HANDLED;
        }
        if (k.isCancel()) { pickingPerms = false; return EventResult.HANDLED; }
        int n = rules.size();
        if (n == 0) return EventResult.HANDLED;          // 空列表：除 Esc 外无事可做（别让 % n 除零）
        permsIndex = clampIndex(permsIndex, n);
        if (k.code() == KeyCode.UP || k.isChar('k'))   { permsIndex = (permsIndex - 1 + n) % n; return EventResult.HANDLED; }
        if (k.code() == KeyCode.DOWN || k.isChar('j')) { permsIndex = (permsIndex + 1) % n;     return EventResult.HANDLED; }
        if (k.isChar('d') || k.isChar('D')) {
            permsPendingDelete = rules.get(permsIndex);  // 只是「请求」——真正的删除在 confirmPermsDelete
            return EventResult.HANDLED;
        }
        return EventResult.HANDLED;                       // Enter 等其余键刻意不做任何事
    }

    /** 确认后才真的删。落盘失败也把结果说清楚——「点了没反应」比报错更难排查。 */
    private void confirmPermsDelete() {
        PermissionRule r = permsPendingDelete;
        permsPendingDelete = null;
        if (r == null) return;
        boolean ok = onSubmit.removePermissionRule(r);
        state.setNotice(ok ? "已删除规则 " + r.toDsl()
                : "删除失败：" + r.toDsl() + "（规则可能已变化，或配置文件写不进去）");
        permsIndex = clampIndex(permsIndex, onSubmit.permissionRules().size());
    }

    /**
     * 权限面板：标题 + 每条规则一行（behavior / DSL / 来源层），末尾注明内置底线不在此列。
     * 确认态<b>替换</b>整个列表为一行确认提示——叠加显示会让「要确认什么」淹没在列表里。
     *
     * <p>高亮走纯前景 {@code PICK_SEL}（底色条会串到下一项，见 {@code Theme.PICK_SEL} 注释）。
     */
    private Element[] permsPanelChildren() {
        // scope 每次 render eager 求值（非面板态也会进来）：首行判空，否则渲染线程崩
        if (!pickingPerms) return new Element[0];
        // 审批/计划/作答模态在前台时按键归它们（见 onInputKey 的分支顺序），此时把面板收起来，
        // 免得屏幕上并排两个面板、而其中一个根本按不动。
        if (activePermission != null || activePlan != null || activeAsk != null) return new Element[0];
        List<PermissionRule> rules = onSubmit.permissionRules();
        List<Element> els = new ArrayList<>();
        if (permsPendingDelete != null) {
            els.add(text("  ⚠ 确认删除 " + permsRuleLabel(permsPendingDelete) + "？"
                    + deleteConsequence(permsPendingDelete) + " · Enter 确认 · Esc 取消").style(PICK_TITLE));
            return els.toArray(new Element[0]);
        }
        els.add(text("  🔑 权限规则（↑↓ 选择 · d 删除 · Esc 关闭）").style(PICK_TITLE));
        if (rules.isEmpty()) {
            els.add(text("    当前没有自定义规则。可在 .codetui/permissions.json 配置，"
                    + "或在审批面板选「允许，永久」自动写入。").style(PICK_DESC));
            return els.toArray(new Element[0]);
        }
        int sel = clampIndex(permsIndex, rules.size());
        for (int i = 0; i < rules.size(); i++) {
            PermissionRule r = rules.get(i);
            boolean isSel = i == sel;
            els.add(text("  " + (isSel ? "❯ " : "  ") + permsRuleLabel(r))
                    .style(isSel ? PICK_SEL : PICK_ITEM));
        }
        els.add(text("    ─────────────────────────────────────").style(PICK_DESC));
        els.add(text("    内置底线不在此列，无法删除").style(PICK_DESC));
        return els.toArray(new Element[0]);
    }

    /** 一条规则的显示文本：{@code [DENY] Read(**}{@code /.env)  用户级}。 */
    private static String permsRuleLabel(PermissionRule r) {
        return "[" + r.behavior() + "] " + r.toDsl() + "  " + scopeLabel(r.scope());
    }

    private static String scopeLabel(RuleScope s) {
        return switch (s) {
            case USER -> "用户级";
            case PROJECT -> "项目级";
            case SESSION -> "本会话";
        };
    }

    /**
     * 删除这条规则的后果，<b>按方向分开说</b>。
     *
     * <p>同一个操作在两个方向上的后果完全不对称：删 allow / ask 只是回到「以后再问一次」，
     * 删 <b>deny</b> 是<b>放宽权限</b>——那条禁令没了之后，原本被拒的调用可能直接被放行。
     * 提示语因此也不该对称，用户按下 Enter 之前得看见自己在往哪个方向走。
     */
    private static String deleteConsequence(PermissionRule r) {
        return r.behavior() == PermissionBehavior.DENY ? "这会放宽权限" : "以后会重新询问";
    }

    /**
     * 面板态的状态行。
     *
     * <p><b>必须自己回显 notice</b>：{@link #statusLine} 的面板分支早于通用 notice 分支 return，
     * 不在这里回显的话，「已删除规则 X」「删除失败」这类反馈<b>永远看不见</b>——而删除恰恰是
     * 最需要给出确认反馈的操作（同 {@link #askStatusText} 的老账）。
     */
    String permsStatusText() {
        String notice = state.notice();
        if (notice != null && !notice.isEmpty()) return notice + " · Esc 关闭";
        return permsPendingDelete != null
                ? "⚠ 确认删除 · Enter 确认 · Esc 取消"
                : "🔑 权限规则 · ↑↓ 选择 · d 删除 · Esc 关闭";
    }

    // 测试钩子
    boolean pickingPermsForTest() { return pickingPerms; }

    // ── /tasks 后台任务面板 ──────────────────────────────────────────────
    /** 展开结果时最多占几个物理行；再多会把输入框顶出屏幕（同 {@link #printPlan} 的取舍）。 */
    private static final int TASK_RESULT_LINES = 8;

    /** 打开后台任务面板。<b>零任务也照常打开</b>——面板会说明后台任务从哪来，比什么都不弹更有用。 */
    private void openTasksPanel() {
        taskIndex = 0;
        taskExpanded = false;
        taskPendingKill = null;
        pickingTasks = true;
    }

    /**
     * 面板按键：↑↓ 移动、Enter 展开/收起结果、{@code k} 请求终止、Esc 关闭；
     * 确认态只认 Enter（确认）/ Esc（取消）。始终 HANDLED。
     *
     * <p><b>刻意不提供 j/k 移动</b>（与本应用其余选择器不同）：{@code k} 在这里是<b>终止</b>键，
     * 两者撞车。「想上移却弹出终止确认」是最糟的形态，宁可少一组 vim 键位。
     *
     * <p><b>{@code k} 只对运行中的任务有效</b>：对已结束的按下去不弹确认——弹一个「确认终止一个已经
     * 结束的任务？」只会让人怀疑自己看错了状态。
     */
    private EventResult onTasksPanelKey(KeyEvent k) {
        List<ConversationState.BackgroundView> tasks = state.backgroundTasks();
        if (taskPendingKill != null) {                    // 确认态：只认 Enter / Esc，别的键一概忽略
            if (k.isCancel()) { taskPendingKill = null; return EventResult.HANDLED; }
            if (k.code() == KeyCode.ENTER || k.isChar('\r') || k.isChar('\n')) {
                confirmKillTask();
                return EventResult.HANDLED;
            }
            return EventResult.HANDLED;
        }
        if (k.isCancel()) { pickingTasks = false; return EventResult.HANDLED; }
        int n = tasks.size();
        if (n == 0) return EventResult.HANDLED;           // 空列表：除 Esc 外无事可做（别让 % n 除零）
        taskIndex = clampIndex(taskIndex, n);
        if (k.code() == KeyCode.UP)   { taskIndex = (taskIndex - 1 + n) % n; taskExpanded = false; return EventResult.HANDLED; }
        if (k.code() == KeyCode.DOWN) { taskIndex = (taskIndex + 1) % n;     taskExpanded = false; return EventResult.HANDLED; }
        if (k.code() == KeyCode.ENTER || k.isChar('\r') || k.isChar('\n')) {
            taskExpanded = !taskExpanded;
            return EventResult.HANDLED;
        }
        if (k.isChar('k') || k.isChar('K')) {
            ConversationState.BackgroundView t = tasks.get(taskIndex);
            if (t.status() != ConversationState.BackgroundStatus.RUNNING) {
                // 说一句而不是静默吞掉：「按了没反应」比报错更难排查（同 confirmPermsDelete 的老账）。
                state.setNotice("任务已结束，无需终止");
                return EventResult.HANDLED;
            }
            taskPendingKill = t.taskId();   // 只是「请求」——真正的终止在 confirmKillTask
            return EventResult.HANDLED;
        }
        return EventResult.HANDLED;                       // 其余键一律吞掉，不落进输入框
    }

    /**
     * 确认后才真的终止。<b>UI 镜像要自己补一笔 KILLED</b>：注册表的 kill 只改它自己那份状态、不发事件，
     * 也不打断那条线程——不补就是「按完 k 面板上那条一直转」。落空也把结果说清楚。
     */
    private void confirmKillTask() {
        String id = taskPendingKill;
        taskPendingKill = null;
        if (id == null) return;
        boolean ok = onSubmit.killBackgroundTask(id);
        if (ok) state.markBackgroundKilled(id);
        state.setNotice(ok ? "已终止后台任务 " + id
                : "终止失败：" + id + "（它可能刚好已经结束）");
        taskIndex = clampIndex(taskIndex, state.backgroundTasks().size());
    }

    /**
     * 后台任务面板：标题 + 每个任务一行（沿用 ⏱ 面板的行文本），选中项可 Enter 展开结果正文，
     * 末尾一行说明结果怎么进模型。确认态<b>替换</b>整个列表为一行确认提示——叠加显示会让
     * 「要确认什么」淹没在列表里（同 {@link #permsPanelChildren}）。
     *
     * <p>高亮走纯前景 {@link Theme#PICK_SEL}（底色条会串到下一项，见 {@code Theme.PICK_SEL} 注释）。
     */
    private Element[] tasksPanelChildren() {
        // scope 每次 render eager 求值（非面板态也会进来）：首行判空，否则渲染线程崩
        if (!pickingTasks) return new Element[0];
        // 模态在前台时按键归它们（见 onInputKey 的分支顺序），此时把面板收起来，
        // 免得屏幕上并排两个面板、而其中一个根本按不动。
        if (activePermission != null || activePlan != null || activeAsk != null) return new Element[0];
        List<ConversationState.BackgroundView> tasks = state.backgroundTasks();
        List<Element> els = new ArrayList<>();
        if (taskPendingKill != null) {
            els.add(text("  ⚠ 确认终止 " + taskPendingKill + "？已跑出的进度会丢失，结果不会再交给模型"
                    + " · Enter 确认 · Esc 取消").style(PICK_TITLE));
            return els.toArray(new Element[0]);
        }
        els.add(text("  ⏱ 后台任务（↑↓ 选择 · Enter 展开结果 · k 终止 · Esc 关闭）").style(PICK_TITLE));
        if (tasks.isEmpty()) {
            els.add(text("    （暂无后台任务）让模型用 Task 的 run_in_background 派活，"
                    + "跑起来的任务会出现在这里。").style(PICK_DESC));
            return els.toArray(new Element[0]);
        }
        int sel = clampIndex(taskIndex, tasks.size());
        long now = System.currentTimeMillis();
        int inner = Math.max(8, terminalWidth() - 2);
        for (int i = 0; i < tasks.size(); i++) {
            ConversationState.BackgroundView t = tasks.get(i);
            boolean isSel = i == sel;
            els.add(text(clipToWidth("  " + (isSel ? "❯ " : "  ") + backgroundRowText(t, now).strip(), inner))
                    .style(isSel ? PICK_SEL : PICK_ITEM));
            if (isSel && taskExpanded) els.addAll(resultRows(t, inner));
        }
        // 这一行是整个后台模式最容易被误解的地方：结果不会自己蹦到屏幕上，也不需要你去粘贴——
        // 空闲且输入框为空时它自动起一个回合交给模型。不说清楚，用户会以为任务白跑了。
        els.add(text("    完成的结果会自动送达：清空输入框后自动交给模型").style(PICK_DESC));
        return els.toArray(new Element[0]);
    }

    /**
     * 展开的结果正文，<b>按 {@code \n} 逐行拆</b>——一个 Element = 一个物理行，整块多行字符串会被塌成一行截断。
     * 超过 {@link #TASK_RESULT_LINES} 行只显示开头并注明还有多少：行内面板放不下几十行（会把输入框顶出屏幕）。
     */
    private List<Element> resultRows(ConversationState.BackgroundView t, int inner) {
        List<Element> els = new ArrayList<>();
        String result = t.result() == null ? "" : t.result();
        if (result.isBlank()) {
            els.add(text(t.status() == ConversationState.BackgroundStatus.RUNNING
                    ? "      （仍在运行，还没有结果）" : "      （没有结果正文）").style(PICK_DESC));
            return els;
        }
        String[] lines = result.split("\n", -1);
        int shown = Math.min(lines.length, TASK_RESULT_LINES);
        for (int i = 0; i < shown; i++) {
            els.add(text(clipToWidth("      " + lines[i], inner)).style(PICK_DESC));
        }
        if (lines.length > shown) {
            els.add(text("      … 还有 " + (lines.length - shown) + " 行（行内面板放不下，完整结果已交给模型）")
                    .style(DIM));
        }
        return els;
    }

    /** 按显示宽度截断（中文占 2 列），超出补省略号。守住「一行内容一物理行」，长行不撑爆面板。 */
    private static String clipToWidth(String s, int width) {
        if (displayWidth(s) <= width) return s;
        return dev.tamboui.text.CharWidth.substringByWidth(s, Math.max(1, width - 1)) + "…";
    }

    /**
     * 面板态的状态行。
     *
     * <p><b>必须自己回显 notice</b>：{@link #statusLine} 的面板分支早于通用 notice 分支 return，
     * 不在这里回显的话，「已终止 / 终止失败」这类反馈<b>永远看不见</b>（同 {@link #permsStatusText} 的老账）。
     */
    String tasksStatusText() {
        String notice = state.notice();
        if (notice != null && !notice.isEmpty()) return notice + " · Esc 关闭";
        return taskPendingKill != null
                ? "⚠ 确认终止 · Enter 确认 · Esc 取消"
                : "⏱ 后台任务 · ↑↓ 选择 · Enter 展开结果 · k 终止 · Esc 关闭";
    }

    // 测试钩子
    boolean pickingTasksForTest() { return pickingTasks; }
    String tasksStatusTextForTest() { return tasksStatusText(); }

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

    /**
     * ⏱ 后台任务面板：计数标题 + 每个任务一行（▶/✓/✗ 分色 + id + agent + 描述 + 耗时 + 当前工具）。
     *
     * <p><b>首行必须判空</b>——TamboUI 的 {@code scope(cond, children)} 每次 render <b>eager 求值</b>：
     * 即使 cond 为 false，children 也会被构造一次。不判空会在零任务时越界（同 {@link #subtaskChildren}）。
     *
     * <p><b>与 ⟐ 任务面板分开显示</b>：那个是本回合的前台子 agent（回合一结束就清），这个跨回合存活。
     * 混在一起用户分不清「关掉这个回合还在不在跑」。
     */
    private Element[] backgroundChildren(List<ConversationState.BackgroundView> tasks) {
        if (tasks == null || tasks.isEmpty()) return new Element[0];   // scope eager 求值：首行判空
        long running = tasks.stream()
                .filter(t -> t.status() == ConversationState.BackgroundStatus.RUNNING).count();
        long finished = tasks.size() - running;
        List<Element> els = new ArrayList<>();
        els.add(text("⏱ 后台任务 (" + running + " 运行 · " + finished + " 完成)").style(TODO_TITLE));
        List<ConversationState.BackgroundView> vis = visibleBackgroundTasks(tasks);
        int hidden = tasks.size() - vis.size();
        if (hidden > 0) {                       // 折叠靠前的（最老的），注记在顶部并指路 /tasks
            els.add(text("  … 前 " + hidden + " 项已折叠 · /tasks 看全部").style(DIM));
        }
        long now = System.currentTimeMillis();
        int inner = Math.max(8, terminalWidth() - 2);   // 超宽行截断，避免把输入框顶出屏幕
        for (ConversationState.BackgroundView t : vis) {
            Style st = switch (t.status()) {
                case DONE -> OK;
                case FAILED -> ERROR;
                case KILLED -> DIM;      // 用户自己终止的：不是错误，也没什么可看的，退到背景里
                case RUNNING -> TODO_RUN;
            };
            String row = backgroundRowText(t, now);
            if (displayWidth(row) > inner) {
                row = dev.tamboui.text.CharWidth.substringByWidth(row, inner - 1) + "…";
            }
            // RUNNING 行加波光，表示任务仍在活跃执行（1s 才跳一次的耗时计数器太静，看起来像卡死）。
            // 其他状态（DONE/FAILED/KILLED）是终态，静态样式即可。
            if (t.status() == ConversationState.BackgroundStatus.RUNNING) {
                els.add(richText(statusBar.shimmer(row, "", TODO_RUN, animTick)));
            } else {
                els.add(text(row).style(st));
            }
        }
        return els.toArray(new Element[0]);
    }

    /**
     * 面板可见的后台任务：末尾 {@link #BACKGROUND_CAP} 条。
     *
     * <p>取末尾（同 {@link #visibleSubtasks}）：列表按登记先后排，靠前的是最老的、多半早已完成
     * ——那是历史，值不了一行常驻面板；而正在跑的通常是最近派出的，必须留在屏幕上。
     * 万一被折叠掉的里面还有在跑的，标题里的「N 运行」仍如实计全部，用户不会以为它没了。
     */
    static List<ConversationState.BackgroundView> visibleBackgroundTasks(
            List<ConversationState.BackgroundView> tasks) {
        int from = Math.max(0, tasks.size() - BACKGROUND_CAP);
        return tasks.subList(from, tasks.size());
    }

    /** 一条后台任务的行文本："  <图标> <id> <agent>  <描述>  <耗时>[  <当前工具/已终止>]"。 */
    static String backgroundRowText(ConversationState.BackgroundView t, long now) {
        String icon = switch (t.status()) {
            case DONE -> "✓";
            case FAILED -> "✗";
            case KILLED -> "⊘";
            case RUNNING -> "▶";
        };
        // 已结束的任务用钉住的 finishedAt 算耗时，否则数字会一直涨、看着像还在跑。
        long end = t.finishedAt() > 0 ? t.finishedAt() : now;
        // 「已终止」要写成字，不能只靠 ⊘ 图标：这是用户自己按 k 干的，得能一眼确认那一下生效了。
        String tail = t.status() == ConversationState.BackgroundStatus.KILLED ? "  已终止"
                : (t.currentTool() != null && !t.currentTool().isEmpty()) ? "  " + t.currentTool() : "";
        return "  " + icon + " " + t.taskId() + " " + t.agentName() + "  " + t.description()
                + "  " + elapsedText(end - t.startedAt()) + tail;
    }

    /**
     * 耗时渲染：{@code 1m42s}。秒补零——{@code 2m3s} 与 {@code 2m30s} 扫一眼会看错。
     * 负数（时钟回拨 / NTP 校时）兜成 0，面板上出现负耗时只会让人怀疑整个面板。
     */
    static String elapsedText(long millis) {
        long sec = Math.max(0, millis) / 1000;
        return (sec / 60) + "m" + String.format("%02d", sec % 60) + "s";
    }

    /** 排队消息面板：固定在输入框上方，每条一行（暗灰底、› 前缀、超宽截断），仿 Claude Code。 */
    private Element[] queuedChildren(List<String> queued) {
        return pinnedMessages(queued, "› ", QUEUED);
    }

    /**
     * 未送达插话面板：形状同排队消息，换 {@code ⤷} 前缀与暖橙前景。
     *
     * <p><b>这是插话在未送达期间屏幕上的唯一存在</b>——输入那一刻不再往 scrollback 打行
     * （位置会是错的，且 scrollback 改不了），送达时才由 {@code onUserMessage} 打进信息流。
     * 少了这个面板，用户按完回车会看到什么都没发生。
     *
     * <p>与排队消息<b>必须看得出区别</b>：两者都钉在这儿、都是「还没走的话」，但插话随本回合
     * 下一次模型调用送达，排队要等整个回合跑完。区分靠行首符号 + 前景色相，<b>不靠底色</b>
     * （本 TUI 的 InlineDisplay 下底色会串行，见 {@code Theme.PICK_SEL}）。
     */
    private Element[] interjectionChildren(List<String> interjections) {
        return pinnedMessages(interjections, "⤷ ", INTERJECT);
    }

    /** 钉在输入框上方的「待发消息」行：折成单行、超宽按显示宽度截断。 */
    private Element[] pinnedMessages(List<String> messages, String prefix, Style style) {
        List<Element> els = new ArrayList<>();
        int inner = Math.max(8, terminalWidth() - displayWidth(INDENT) - displayWidth(prefix));
        for (String q : messages) {
            String oneLine = q.replaceAll("\\s+", " ").trim();
            if (displayWidth(oneLine) > inner) oneLine = dev.tamboui.text.CharWidth.substringByWidth(oneLine, inner - 1) + "…";
            els.add(text(INDENT + prefix + oneLine).style(style));
        }
        return els.toArray(new Element[0]);
    }

    /** 一条计划：✓完成=绿 / ▶进行中=亮黄加粗 / ○待办=暗。 */
    private static Element todoRow(String s) {
        Style st = s.startsWith("✓") ? OK : s.startsWith("▶") ? TODO_RUN : DIM;
        return text("  " + s).style(st);
    }

    private Element statusLine() {
        if (activePermission != null) return text(permStatusText()).style(THINK);   // 审批优先：此时别的提示都不该抢
        if (activePlan != null) return text(planStatusText()).style(THINK);
        if (activeAsk != null) return text(askStatusText()).style(THINK);
        if (pickingPerms) return text(permsStatusText()).style(THINK);
        if (pickingTasks) return text(tasksStatusText()).style(THINK);
        if (configuringThinking) return text("↑↓/kj 选择 · ←→/hl 调整 · Enter 保存 · Esc 放弃").style(THINK);
        if (pickingModel) {
            String notice = state.notice();
            return text(notice.isEmpty()
                    ? "↑↓/kj 选择 · 1-9 快选 · Enter 确认 · → 思考设置 · Esc 取消"
                    : notice + " · Esc 取消").style(THINK);
        }
        if (pickingSkill) return text("↑↓/kj 选择 · 1-9 快选 · Enter 挂载 · Esc 取消").style(THINK);
        if (slashMenuActive()) return text("↑↓ 选择 · Tab 补全 · Enter 运行 · Esc 关闭").style(THINK);
        if (state.isCompacting()) return richText(statusBar.compacting(state.compactElapsedNanos(), animTick));   // 压缩指示器优先于普通思考/工具状态
        // 已挂载技能不再占状态栏——改由输入框正上方的技能标签（skillTag）常驻显示，见 render()。
        int q = state.queuedCount();
        String qs = q > 0 ? " · 已排队 " + q + " 条" : "";
        // 尚未送达模型的插话条数。这是插话唯一的实时反馈——回显那行刻意不写送达状态（scrollback
        // 里的行改不了，会永远停在错的状态上），送没送出去全看这里。
        // ⚠ 空串必须判：不判会渲染出一段悬空的 " · "（同 qs 的既有纪律）。
        int ij = onSubmit.pendingInterjections();
        String ijs = ij > 0 ? " · 插话 " + ij + " 条" : "";
        String notice = state.notice();
        // 空闲但仍有已取消子 agent 在收尾：给提示，避免「消息静默入队、无转轮」被误判为卡死（见 busy()/drainingSubagentsHint）。
        // ⚠ 必须算在 notice 判定之前：它也是一条动态信息，不能被 notice 盖掉。
        String draining = drainingSubagentsHint(state.isIdle(), onSubmit.hasInFlightSubagents());
        // notice 独占整行只留给「真空闲」：那时本来就没有动态信息要保。
        // ⚠ 这一句以前无条件 return 且排在 status 开关之前，于是<b>任何</b>忙时 notice 都会把
        // 波光转轮整条盖掉——回合正跑着按一下 Shift+Tab，用户就看不出还在跑了，且 sticky，
        // 不按下一个键不还回来。修法是改结构而不是给 Shift+Tab 开小灶：忙时降级为后缀、与转轮共存。
        if (!notice.isEmpty() && state.isIdle() && draining == null) {
            return text(notice + " · Ctrl+C 退出").style(THINK);
        }
        // 忙时的 notice 后缀。<b>空串必须判</b>：不判会渲染出一段悬空的 " · "。
        String ns = notice.isEmpty() ? "" : " · " + notice;
        Span mode = modeTag(onSubmit.permissionMode());
        if (draining != null) return richText(statusBar.shimmer(draining, qs + ijs + ns + " · Ctrl+C 退出", THINK, animTick, mode));
        String cacheHit = ctxUsage.cacheHitSuffix();
        return switch (state.status()) {
            case IDLE -> {
                int modeWidth = mode == null ? 0 : displayWidth(mode.content());
                String hint = idleHint(statusModelLabel(),
                        ctxUsage.suffix() + backgroundStatusSuffix(),
                        terminalWidth() - modeWidth);
                yield mode == null
                        ? richText(Text.from(Line.from(StatusBar.cacheHitSpans(hint, HINT))))
                        : richText(Text.from(Line.from(withLeading(mode, StatusBar.cacheHitSpans(hint, HINT)))));
            }
            case THINKING -> richText(statusBar.shimmer("● 思考中…",
                    qs + ijs + ns + cacheHit + " · Esc 取消 · Ctrl+C 退出", THINK, animTick, mode));
            case RETRYING -> {
                String label = state.retryLabel() == null ? "↻ 重试中" : state.retryLabel();
                String backoff = state.retryBackoffText();
                String backoffTail = terminalWidth() >= 100 && backoff != null ? " · 退避 " + backoff : "";
                String suffix = qs + ijs + ns + backoffTail + " · Esc 取消";
                yield richText(statusBar.shimmer(label, suffix, THINK, animTick, mode));
            }
            case RUNNING_TOOL -> {
                String suffix = qs + ijs + ns + cacheHit + " · Esc 取消";
                String s = fitToolSummary(state.activeToolSummary(), state.activeTool(), suffix, mode);
                yield richText(statusBar.shimmer("⏺ 运行 " + state.activeTool() + (s.isEmpty() ? "" : ": " + s) + "…",
                        suffix, RUNNING, animTick, mode));
            }
        };
    }

    /**
     * 按可用宽度装填 IDLE 状态行。模型名与动态后缀是核心状态；{@code Enter 发送} 是主操作；
     * {@code /model}、{@code Esc}、{@code Ctrl+C} 是次要帮助组，空间不足时必须整组让位。
     *
     * <p>候选依次为「完整行」→「主操作 + 核心状态」→「核心状态」。最后一档即使仍超宽也不主动
     * 裁剪模型或动态数值；此时已没有更低优先级内容可隐藏，只能由终端执行最终裁切。
     * {@code availableWidth} 已由调用方扣除非默认权限模式标签的显示宽度。
     */
    static String idleHint(String modelLabel, String dynamicSuffix, int availableWidth) {
        String core = modelLabel + dynamicSuffix;
        String primaryAndCore = "Enter 发送 · " + core;
        String full = "Enter 发送 · /model 切换模型 · Esc 取消 · Ctrl+C 退出 · " + core;

        if (displayWidth(full) <= availableWidth) return full;
        if (displayWidth(primaryAndCore) <= availableWidth) return primaryAndCore;
        return core;
    }

    /**
     * 按终端宽度收窄工具入参摘要，给状态行尾部留出后缀的位置。
     *
     * <p><b>为什么必须收</b>：{@code ConversationState.summarize} 给的摘要最宽 80 列，而
     * {@code Task} 的入参是含完整 prompt 的 JSON，<b>必然</b>吃满。加上 {@code ⏺ 运行 Task: }
     * 前缀就是约 92 列——80 列终端上，尾部的「插话 N 条 · Esc 取消」<b>整段被截没</b>
     * （离屏渲染实测：80 列全无、100 列剩半个字、120 列才完整）。
     *
     * <p>而尾部那几段恰恰是「你现在能做什么」：还有几条话没送出去、按什么键能取消。
     * 工具入参是锦上添花，被截掉只是少看几个字符。同 {@link #backgroundStatusSuffix} 那条
     * 「先截不重要的」纪律，只是方向相反——那里靠<b>排在后面</b>被动挨截，这里主动让位。
     *
     * <p>留 {@code ≥12} 列给摘要：再窄就只剩省略号，不如不显示；此时尾部照旧会被终端截，
     * 但那是终端真的放不下，不是被我们自己挤掉的。
     */
    private String fitToolSummary(String summary, String toolName, String suffix, Span leading) {
        if (summary == null || summary.isEmpty()) {
            return "";
        }
        // 固定开销：「⏺ 运行 」+ 工具名 + 「: 」+ 结尾「…」+ 后缀 + 权限模式前导标签。
        int leadingWidth = leading == null ? 0 : displayWidth(leading.content());
        int overhead = displayWidth("⏺ 运行 : …") + displayWidth(toolName) + displayWidth(suffix) + leadingWidth;
        int room = terminalWidth() - overhead;
        if (room >= displayWidth(summary)) {
            return summary;
        }
        if (room < 12) {
            return "";
        }
        return dev.tamboui.text.CharWidth.substringByWidth(summary, room - 1) + "…";
    }

    /**
     * 空闲态状态行的后台任务后缀：还有几个在跑 + 刹车踩下时的手动放行提示。
     *
     * <p><b>挂在权限模式标识<em>之后</em>、绝不占行首</b>：状态行本就接近终端宽度、尾部先被截断，
     * 而「现在会不会问你」比「有几个后台任务」更不该被截掉。
     *
     * <p>刹车状态读 {@code brakeEngaged()} 而不是再调一次判定——那个方法有副作用（会消耗刹车额度），
     * 拿它来试探等于每次状态行重绘烧掉一次自动回合的名额。
     */
    private String backgroundStatusSuffix() {
        StringBuilder sb = new StringBuilder();
        // MCP 启动期后台连接：不说的话，头几秒模型看不到 MCP 工具而用户完全无从知晓
        // ——那正是「把连接挪到后台」换来的速度所欠下的唯一一笔账，用一个后缀还上。
        int mcp = onSubmit.connectingMcpCount();
        if (mcp > 0) sb.append(" · ⟳ MCP 连接中 ").append(mcp);
        int running = state.backgroundRunningCount();
        if (running > 0) sb.append(" · ⏱ ").append(running).append(" 个后台任务 · /tasks");
        // 刹车踩下时结果就停在那儿不动了，不说一句用户会以为任务被吃了。
        // 两个条件缺一不可：刹车踩下了，<b>而且</b>确实有结果被扣住（bgPending 由
        // deliverBackgroundResults 探明）。只看刹车的话，三次都顺利送完、什么都没剩下时，
        // 这句话仍会常驻——那是在指使用户去处理一个不存在的东西。
        if (notifier.brakeEngaged() && bgPending) sb.append(" · ⏱ 有结果待处理 · 回车交给模型");
        return sb.toString();
    }

    /**
     * 当前权限模式的<b>常驻</b>标识；{@code DEFAULT} 返回 {@code null}（常态不占位、不制造噪声）。
     *
     * <p><b>为什么需要它</b>：在此之前模式只出现在三个<b>会消失</b>的地方——Shift+Tab 后的 notice
     * （下一次按键即被 {@link #onInputKey} 顶部清掉）、{@code /permissions} 报告与 BYPASS 启动横幅
     * （都是 scrollback 里的一条历史，随对话滚走）。于是切到非 DEFAULT 后随便按个键，用户就<b>再也
     * 看不出自己在哪一档</b>，只能重新敲 {@code /permissions} 查。对「这次工具调用会不会问我」这种
     * 有安全后果的状态，不可见等同于不存在——BYPASS 尤甚：那是最该持续可见的一档，横幅却只出现一次。
     *
     * <p>只挂在常态三行（空闲 / 思考 / 跑工具）上，不挂菜单、模态与<b>空闲态</b>的 notice 独占行：
     * 那些是<b>临时接管</b>状态行的覆盖层，收起后标识自然回来。
     * <b>忙时的 notice 后缀是例外</b>——那一行本就是常态行，标识与后缀分工不同、刻意并存：
     * 行首的标识说「现在是哪一档」（常驻状态），后缀的「已切到 X」说「刚刚发生了什么」（一次性事件）。
     *
     * <p>纯函数（不读实例态），故可直接单测；真实屏幕上的可见性由 pty 冒烟钉。
     */
    static Span modeTag(PermissionMode mode) {
        return switch (mode) {
            case DEFAULT -> null;
            case ACCEPT_EDITS -> Span.styled("⏵⏵ " + mode.label() + " · ", MODE_ACCEPT);
            case PLAN -> Span.styled("⏸ " + mode.label() + " · ", MODE_PLAN);
            case BYPASS -> Span.styled("⚠ " + mode.label() + " · ", MODE_BYPASS);
        };
    }

    /** 在富文本状态行前插入权限模式标签，不修改调用方提供的 Span 列表。 */
    static List<Span> withLeading(Span leading, List<Span> rest) {
        List<Span> spans = new ArrayList<>(rest.size() + 1);
        spans.add(leading);
        spans.addAll(rest);
        return spans;
    }

    // ── 内部工具 ─────────────────────────────────────────────────────────
    /** 取最后一个真实换行之后的残段（流式预览用；complete 行由输出批下沉 scrollback）。 */
    private static String lastLine(String s) {
        int i = s.lastIndexOf('\n');
        return i < 0 ? s : s.substring(i + 1);
    }
}

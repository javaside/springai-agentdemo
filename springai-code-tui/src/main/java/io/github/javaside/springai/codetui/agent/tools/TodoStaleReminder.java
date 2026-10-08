package io.github.javaside.springai.codetui.agent.tools;

import org.springaicommunity.agent.tools.TodoWriteTool.Todos.Status;
import org.springaicommunity.agent.tools.TodoWriteTool.Todos.TodoItem;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * 任务面板过期提醒：控制器建过 TodoWrite 清单后，把一段「快照 + 更新指令」文本追加到工具返回值尾部喂回模型。
 *
 * <p><b>为什么需要它</b>：真机会话取证（见 spec 2026-10-07-todowrite-not-updated-root-cause）显示
 * 「建了清单后全程不更新」的失败形态——模型记账纪律本身在（SDD 台账文件 8 次追加全中），
 * 但清单对模型是单向的：面板在 UI 层、模型看不见，返回的是一次性文案，harness 没有任何
 * 机制把过期清单送回上下文，于是任何一次漏更新都不可逆（跨语料 322 会话复核；「上下文太远、
 * 注意力稀释」的旧解释已被同一份取证推翻）。唯一可靠的纠正方式是让环境把过期状态怼回模型眼前。
 *
 * <p><b>两条触发路径，分工不同</b>（跨语料度量：控制器做完成标记时顺手更新清单的比例，
 * 非 SDD 会话 0.63、SDD 会话 0.37——「完成时刻」是失守的主要位置）：
 * <ul>
 *   <li><b>事件对齐</b>（精准）：{@code Task}/{@code ParallelTasks} 返回、或 {@code Bash} 执行
 *       {@code git commit} 即完成事件。事件当刻<b>不</b>提醒——那一刻清单必然还没机会更新，
 *       提醒只会每次委派都响一遍；改为「武装」，等<b>下一个跳过更新的调用</b>再响，
 *       那才是「模型忘了」的证据。每回合至多响一次（防同一回合反复刷屏）。</li>
 *   <li><b>阈值计数</b>（兜底）：连续 {@code every} 次控制器级工具调用未更新且清单仍未完成时提醒。
 *       负责事件路径覆盖不到的长推进。</li>
 * </ul>
 * 两条路径共用同一份快照，任何一次 TodoWrite 都复位两者。
 *
 * <p><b>提醒带可照抄的入参</b>：TodoWrite 是整表替换（每项都要 content/activeForm/status），
 * 而竞争动作（写一行台账）成本极低——只写「快照 + 请更新」等于把重建整份清单的重活丢回给模型。
 * 因此提醒直接给出整份 JSON，模型只需照抄改 status。快照过大时（> {@link #MAX_JSON_CHARS}）
 * 退化为紧凑列表，避免每 N 次调用注入一份巨型文本。
 *
 * <p><b>只管控制器（taskId==null）</b>：子 agent 内部 todo 不进任何面板（AgentListener
 * 分流丢弃），提醒它没有意义；快照只在控制器分支（todoEventHandler 的 currentTaskId()==null）喂入。
 *
 * <p><b>线程模型</b>：工具线程并发调用是常态（并行工具调用），阈值计数用 AtomicInteger，
 * 快照/回合号/两个标志位 volatile；turnId 切换的「比较后清零」非原子，最坏多清一次计数，无害。
 *
 * <p><b>内部类型</b>：升 public 仅为跨包装配（AgentTools/CodingAgent 在 agent 包），勿在 agent 包外依赖。
 */
public final class TodoStaleReminder {

    /** 默认阈值：控制器级工具调用第 8 次仍未更新则提醒（事件对齐路径之外的第二道防线）。 */
    public static final int DEFAULT_EVERY = 8;
    /** 环境变量名：{@code CODETUI_TODO_REMIND_EVERY}，≤0 停用，非法回退默认。 */
    public static final String ENV_EVERY = "CODETUI_TODO_REMIND_EVERY";
    /** 快照 JSON 长度上限，超出退化为紧凑列表（防提醒文本无界膨胀）。 */
    private static final int MAX_JSON_CHARS = 3000;
    /** 紧凑列表（退化形态）未完成项逐行展示上限，超出折汇总行。 */
    private static final int MAX_LINES = 10;
    /** 紧凑列表单条 content 截断长度。 */
    private static final int MAX_CONTENT = 80;

    /**
     * 完成事件：{@code git commit} 出现在行首或命令分隔符之后。
     *
     * <p>与取证脚本 {@code dev/session-forensics/todo_staleness.py} 的 {@code COMMIT} 同一口径，
     * 改这里要同步改那里，否则「度量」与「干预」会漂移。
     *
     * <p><b>为什么用词边界而不是「行首/命令分隔符」锚定</b>：本方法的入参是 Bash 的
     * {@code toolInput}，即 {@code {"command":"…"}} 的 <b>JSON 原文</b>——命令行开头的
     * {@code git} 前面跟的是引号，不是行首也不是 {@code &&}。早期版本按 shell 语义锚定，
     * 于是「{@code git commit -m x}」这种最自然的写法<b>永远不匹配</b>，而
     * 「{@code cd X && git commit -m x}」恰好匹配——隐蔽到只有装配级用例才炸得出来
     * （{@code AgentToolsTodoReminderEndToEndTest} 就是因此才建的）。
     * 实测全语料有 30 次提交属于前一种写法，同理也一直被取证脚本漏计。
     *
     * <p><b>允许 git 与子命令之间夹全局选项</b>：{@code git -C <dir> commit}、
     * {@code git -c user.email=… commit}、{@code git --no-pager commit} 都是真实写法。
     * 代价是「命令里恰好写着 git commit 字面量」（如 {@code echo "git commit"}）会误判为完成事件——
     * 可接受：误判只是多提醒一次，不是漏提醒。
     */
    private static final Pattern COMMIT = Pattern.compile(
            "(?<![\\w-])git\\s+(?:(?:-[A-Za-z]|--[A-Za-z][A-Za-z-]*)(?:=\\S+|\\s+\\S+)?\\s+)*commit\\b");

    private final int every;                                   // 0=停用
    private volatile long turnId = -1L;                       // 计数归属回合（切换即全复位）
    private volatile List<TodoItem> todos = List.of();        // 控制器清单快照
    private final AtomicInteger stale = new AtomicInteger();  // 距上次 TodoWrite/提醒的调用数
    private volatile boolean armed;                           // 刚发生过完成事件，等下一次「跳过更新」再响
    private volatile String armedBy = "";                     // 武装它的完成事件名（提醒里要指名道姓）
    private volatile boolean eventFiredThisTurn;              // 本回合的事件提醒已响过（每回合至多一次）

    public TodoStaleReminder(int every) {
        this.every = Math.max(0, every);
    }

    /** 从环境变量构造（默认 8）。解析拆在 {@link #parseEvery} 供测试直测——env 本身没法在测试里设。 */
    public static TodoStaleReminder fromEnv() {
        return new TodoStaleReminder(parseEvery(System.getenv(ENV_EVERY)));
    }

    /** 包私有：null/空白/非法 → 默认 8；合法整数原样返回（含 0/负数，由状态机按停用处理）。 */
    static int parseEvery(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_EVERY;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            return DEFAULT_EVERY;   // 配错不崩启动：提醒是可选增强，回退默认比拒启动合理
        }
    }

    /** 是否启用（every&gt;0）。 */
    public boolean enabled() {
        return every > 0;
    }

    /** 控制器 TodoWrite 落地时刷新快照并复位两条触发路径（todoEventHandler 的 currentTaskId()==null 分支调用）。 */
    public void onControllerTodoWritten(List<TodoItem> items) {
        this.todos = (items == null) ? List.of() : List.copyOf(items);
        stale.set(0);
        armed = false;
        eventFiredThisTurn = false;
    }

    /** 全清（/clear 用）：旧会话的清单不该吓唬一个从没写过它的新会话。 */
    public void reset() {
        todos = List.of();
        stale.set(0);
        turnId = -1L;
        armed = false;
        eventFiredThisTurn = false;
    }

    /**
     * 不携带 toolInput 的重载：无法识别完成事件，只剩阈值计数路径。
     * 生产路径（{@link ToolEventCallback}）一律走三参版本。
     */
    public String reminderOrNull(long turnId, String toolName) {
        return reminderOrNull(turnId, toolName, null);
    }

    /**
     * 控制器级工具调用成功后询问是否提醒。返回 null=不提醒；否则返回追加到工具结果尾部的提醒文本。
     *
     * @param toolName  刚执行完的工具名
     * @param toolInput 该次调用的入参（识别 {@code git commit} 用；无则传 null）
     */
    public String reminderOrNull(long turnId, String toolName, String toolInput) {
        if (!enabled()) {
            return null;
        }
        if (turnId != this.turnId) {
            this.turnId = turnId;
            stale.set(0);
            armed = false;
            eventFiredThisTurn = false;
        }
        if ("TodoWrite".equals(toolName)) {
            // 它本身就是更新动作：两条路径都复位（快照由 onControllerTodoWritten 更新）
            armed = false;
            eventFiredThisTurn = false;
            stale.set(0);
            return null;
        }
        List<TodoItem> snapshot = todos;
        if (snapshot.isEmpty() || snapshot.stream().allMatch(i -> i.status() == Status.completed)) {
            armed = false;      // 没有「过期」可言，别让武装悬着
            return null;
        }
        if (isCompletionEvent(toolName, toolInput)) {
            // 完成事件当刻不提醒：此刻清单必然还没机会更新。武装起来，等下一个跳过更新的调用再响。
            armed = true;
            armedBy = toolName;
            return null;
        }
        int seen = stale.incrementAndGet();
        if (armed && !eventFiredThisTurn) {
            armed = false;
            eventFiredThisTurn = true;
            stale.set(0);
            // 点名武装它的那个完成事件（不是当前这个跳过更新的工具）——模型才知道「什么刚做完」
            return render(snapshot, (armedBy.isEmpty() ? toolName : armedBy)
                    + " 已返回，一个工作单元完成，但任务清单未更新");
        }
        if (seen < every) {
            return null;
        }
        stale.set(0);
        return render(snapshot, "距上次 TodoWrite 已连续 " + every + " 次工具调用未更新，任务清单可能已过期");
    }

    /**
     * 这个调用是不是「把某个工作单元记为完成」的事件。
     *
     * <p>只认两类<b>与流程无关</b>的信号：委派（子 agent 返回 = 一个任务做完）与提交。
     * 刻意不认「写了 ledger/progress 台账」——那是某个 skill 的约定，harness 不该内建；
     * 且 SDD 写台账后紧接着就派下一个 Task，由 Task 返回这条路径已覆盖。
     */
    static boolean isCompletionEvent(String toolName, String toolInput) {
        if ("Task".equals(toolName) || "ParallelTasks".equals(toolName)) {
            return true;
        }
        if (!"Bash".equals(toolName) || toolInput == null) {
            return false;
        }
        return COMMIT.matcher(toolInput).find();
    }

    /** 渲染提醒文本。前缀/锚点被 TodoStaleReminderTest 钉死，改动须同步测试。 */
    private static String render(List<TodoItem> items, String reason) {
        int total = items.size();
        long done = items.stream().filter(i -> i.status() == Status.completed).count();
        int unfinished = total - (int) done;
        StringBuilder sb = new StringBuilder("[任务面板提醒] ").append(reason)
                .append("（").append(unfinished).append('/').append(total).append(" 项未完成）。\n");
        String json = jsonSnapshot(items);
        if (json.length() <= MAX_JSON_CHARS) {
            return sb.append("请立即调用 TodoWrite 更新状态（任务面板是用户看到进度的唯一渠道），再继续当前工作。\n")
                    .append("可直接照抄下面这行入参、只改 status（已完成项改 completed、下一项改 in_progress）：\n")
                    .append(json)
                    .toString();
        }
        // 快照过大：不给 JSON，退化为紧凑列表——每 N 次调用注入一份巨型文本的代价比漏一次提醒更糟
        sb.append("✓ 已完成：").append(done).append(" 项\n");
        List<String> lines = new ArrayList<>();
        for (TodoItem i : items) {
            if (i.status() == Status.completed) {
                continue;
            }
            if (lines.size() < MAX_LINES) {
                lines.add((i.status() == Status.in_progress ? "▶ " : "○ ") + trunc(i.content()));
            }
        }
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        if (unfinished > lines.size()) {
            sb.append("…等共 ").append(unfinished).append(" 项未完成\n");
        }
        return sb.append("请按实际进度立即调用 TodoWrite 更新状态（任务面板是用户看到进度的唯一渠道），再继续当前工作。")
                .toString();
    }

    /**
     * 整份清单的 TodoWrite 入参 JSON（含全部条目，含已完成的——部分清单被照抄会静默丢项）。
     *
     * <p>手写而非 ObjectMapper：本类此前零依赖，且 JSON 形状被 {@code Todos}/{@code TodoItem}
     * 两个 record 定死（字段名即 record 组件名）；正确性由「喂给真实 TodoWriteTool 能解析」的
     * 用例兜底（{@code reminderJson_roundTripsThroughRealTodoWriteTool}）。
     */
    private static String jsonSnapshot(List<TodoItem> items) {
        StringBuilder sb = new StringBuilder(items.size() * 64 + 16).append("{\"todos\":[");
        for (int i = 0; i < items.size(); i++) {
            TodoItem it = items.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"content\":").append(jsonString(it.content()))
                    .append(",\"activeForm\":").append(jsonString(it.activeForm()))
                    .append(",\"status\":\"").append(statusName(it.status())).append("\"}");
        }
        return sb.append("]}").toString();
    }

    /** status 理论上有校验兜底，但快照损坏不该炸在工具返回路径上——按 pending 兜底。 */
    private static String statusName(Status status) {
        return status == null ? "pending" : status.name();
    }

    private static String jsonString(String s) {
        if (s == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    private static String trunc(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= MAX_CONTENT ? s : s.substring(0, MAX_CONTENT) + "…";
    }
}

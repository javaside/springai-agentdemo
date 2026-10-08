package io.github.javaside.springai.codetui.agent.tools;

import org.springaicommunity.agent.tools.TodoWriteTool.Todos.TodoItem;
import org.springaicommunity.agent.tools.TodoWriteTool.Todos.Status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 任务面板过期提醒：控制器建过 TodoWrite 清单后，若连续 {@code every} 次控制器级工具调用
 * 没再更新且清单仍有未完成项，生成一段「快照 + 更新指令」文本，由 {@link ToolEventCallback}
 * 追加到工具返回值尾部喂回模型。
 *
 * <p><b>为什么需要它</b>：真机会话取证（见 spec 2026-10-07-todowrite-not-updated-root-cause）显示
 * 「建了清单后全程不更新」的失败形态——模型记账纪律本身在（SDD 台账文件 8 次追加全中），
 * 但清单对模型是单向的：面板在 UI 层、模型看不见，返回的是一次性文案，harness 没有任何
 * 机制把过期清单送回上下文，于是任何一次漏更新都不可逆（跨语料 322 会话复核；「上下文太远、
 * 注意力稀释」的旧解释已被同一份取证推翻）。唯一可靠的纠正方式是让环境把过期状态定期怼回模型眼前。
 *
 * <p><b>只管控制器（taskId==null）</b>：子 agent 内部 todo 不进任何面板（AgentListener
 * 分流丢弃），提醒它没有意义；快照只在控制器分支（todoEventHandler 的 currentTaskId()==null）喂入。
 *
 * <p><b>线程模型</b>：工具线程并发调用是常态（并行工具调用），阈值计数用 AtomicInteger，
 * 快照与回合号 volatile；turnId 切换的「比较后清零」非原子，最坏多清一次计数，无害。
 *
 * <p><b>内部类型</b>：升 public 仅为跨包装配（AgentTools/CodingAgent 在 agent 包），勿在 agent 包外依赖。
 */
public final class TodoStaleReminder {

    /** 默认阈值：SDD 场景每任务约 2 次控制器调用（Task + 台账追加），8 次 ≈ 4 个任务未记账才提醒一次。 */
    public static final int DEFAULT_EVERY = 8;
    /** 环境变量名：{@code CODETUI_TODO_REMIND_EVERY}，≤0 停用，非法回退默认。 */
    public static final String ENV_EVERY = "CODETUI_TODO_REMIND_EVERY";
    /** 未完成项逐行展示上限，超出折汇总行（防长清单把工具结果撑爆）。 */
    private static final int MAX_LINES = 10;
    /** 单条 content 截断长度。 */
    private static final int MAX_CONTENT = 80;

    private final int every;                                  // 0=停用
    private volatile long turnId = -1L;                      // 计数归属回合（切换即归零）
    private volatile List<TodoItem> todos = List.of();       // 控制器清单快照
    private final AtomicInteger stale = new AtomicInteger(); // 距上次 TodoWrite/提醒的调用数

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

    /** 控制器 TodoWrite 落地时刷新快照并复位计数（todoEventHandler 的 currentTaskId()==null 分支调用）。 */
    public void onControllerTodoWritten(List<TodoItem> items) {
        this.todos = (items == null) ? List.of() : List.copyOf(items);
        stale.set(0);
    }

    /** 全清（/clear 用）：旧会话的清单不该吓唬一个从没写过它的新会话。 */
    public void reset() {
        todos = List.of();
        stale.set(0);
        turnId = -1L;
    }

    /**
     * 控制器级工具调用成功后询问是否提醒。返回 null=不提醒；到阈值返回提醒文本并复位计数
     * （此后每 every 次再提醒一轮，直到清单更新/清空/全部完成）。
     *
     * <p>工具名 {@code TodoWrite} 豁免且<b>不消耗计数</b>：它本身就是更新动作。
     */
    public String reminderOrNull(long turnId, String toolName) {
        if (!enabled() || "TodoWrite".equals(toolName)) {
            return null;
        }
        if (turnId != this.turnId) {
            this.turnId = turnId;
            stale.set(0);
        }
        List<TodoItem> snapshot = todos;
        if (snapshot.isEmpty() || snapshot.stream().allMatch(i -> i.status() == Status.completed)) {
            return null;
        }
        if (stale.incrementAndGet() < every) {
            return null;
        }
        stale.set(0);
        return render(snapshot, every);
    }

    /** 渲染提醒文本。前缀与锚点被 TodoStaleReminderTest 钉死，改动须同步测试。 */
    private static String render(List<TodoItem> items, int firedAt) {
        int total = items.size();
        long done = items.stream().filter(i -> i.status() == Status.completed).count();
        List<String> lines = new ArrayList<>();
        int unfinished = 0;
        for (TodoItem i : items) {
            if (i.status() == Status.completed) {
                continue;
            }
            unfinished++;
            if (lines.size() < MAX_LINES) {
                lines.add((i.status() == Status.in_progress ? "▶ " : "○ ") + trunc(i.content()));
            }
        }
        StringBuilder sb = new StringBuilder("[任务面板提醒] 距上次 TodoWrite 已连续 ")
                .append(firedAt).append(" 次工具调用未更新，任务清单可能已过期（")
                .append(unfinished).append('/').append(total)
                .append(" 项未完成）：\n✓ 已完成：").append(done).append(" 项\n");
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        if (unfinished > lines.size()) {
            sb.append("…等共 ").append(unfinished).append(" 项未完成\n");
        }
        sb.append("请按实际进度立即调用 TodoWrite 更新状态（任务面板是用户看到进度的唯一渠道），再继续当前工作。");
        return sb.toString();
    }

    private static String trunc(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= MAX_CONTENT ? s : s.substring(0, MAX_CONTENT) + "…";
    }
}

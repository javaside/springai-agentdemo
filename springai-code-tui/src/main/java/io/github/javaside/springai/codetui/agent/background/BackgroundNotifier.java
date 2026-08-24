package io.github.javaside.springai.codetui.agent.background;

import io.github.javaside.springai.codetui.agent.SubmitHandler;

import java.util.List;
import java.util.Optional;

/**
 * 判定「后台任务结果是否该现在自动交给模型」并合成通知文本。
 *
 * <p><b>刻意做成不认识 UI 的小状态机</b>：输入是三个布尔与一个任务列表，输出是
 * {@code Optional<String>}。UI 层只负责把答案落实成一次 {@code submit}。
 * 这样「最容易出错的判断」可以脱离 TUI 单测。
 *
 * <h2>失控刹车</h2>
 * 自动起的回合可能又派后台任务 → 又自动起回合 → 无限循环烧钱，而且是在你不在电脑前的时候。
 * 故记<b>连续</b>自动回合计数：中间没有任何用户输入时连续送达超过 {@code maxConsecutive} 次即停止，
 * 由状态栏改为提示「回车交给模型」。任何一次真实用户输入（{@link #onUserInput()}）都重置计数。
 *
 * <p><b>只有真正送达才消耗额度</b>：无任务、忙、输入框非空这三种「什么都没做」的调用不计数。
 * 否则 drain 每 33ms 调一次，额度几秒就被空转耗光，功能等于没有。
 *
 * <p>并发：只在渲染线程（drain）调用，故不加锁。
 */
public final class BackgroundNotifier {

    /** 默认连续自动回合上限。 */
    public static final int DEFAULT_MAX_CONSECUTIVE = 3;

    private final int maxConsecutive;
    private int consecutive;

    public BackgroundNotifier() {
        this(DEFAULT_MAX_CONSECUTIVE);
    }

    public BackgroundNotifier(int maxConsecutive) {
        this.maxConsecutive = Math.max(1, maxConsecutive);
    }

    /**
     * 该不该现在自动起一个回合把结果送给模型？是则返回通知文本。
     *
     * @param completedUnconsumed 已结束、可送达、未消费的任务（{@link BackgroundTaskRegistry#completedUnconsumed()}）
     * @param idle                当前是否空闲（无活跃回合、非压缩中、无待处理模态、无在飞<b>前台</b>子 agent）
     * @param inputEmpty          输入框是否为空
     */
    public Optional<String> shouldNotify(List<BackgroundTask> completedUnconsumed,
                                         boolean idle, boolean inputEmpty) {
        // 顺序要紧：三个前置条件都在 consecutive++ 之前，空转调用绝不能消耗额度
        if (completedUnconsumed == null || completedUnconsumed.isEmpty()) return Optional.empty();
        if (!idle || !inputEmpty) return Optional.empty();
        if (consecutive >= maxConsecutive) return Optional.empty();
        consecutive++;
        return Optional.of(compose(completedUnconsumed.stream().map(Row::of).toList()));
    }

    /**
     * 同上，但入参是 UI 层那份扁平结构（{@link SubmitHandler.BackgroundResult}）。
     *
     * <p><b>为什么要第二个入口而不是让 UI 转成 {@link BackgroundTask}</b>：{@code BackgroundTask} 是
     * 带可变状态的领域对象，UI 手工造一个只为了过判定，早晚会造出个状态自相矛盾的假货。
     * 两个入口都归到同一段 {@link #compose}——文本模板复制两份必然漂移。
     *
     * <p><b>名字不叫 {@code shouldNotify} 的唯一原因是泛型擦除</b>：{@code List<BackgroundTask>} 与
     * {@code List<BackgroundResult>} 擦除后同签名，重载编译不过。
     *
     * <p>⚠ <b>有副作用</b>：真正判定为「该送达」时会消耗一次刹车额度。调用方必须真的用返回值去起回合，
     * 调了却丢弃返回值等于白白烧掉额度。
     *
     * @param results    已完成待送达的结果
     * @param idle       当前是否空闲（有活跃回合时不送）
     * @param inputEmpty 输入框是否为空（用户正在打字时不送）
     * @return 该送达时的通知文本；否则 {@link Optional#empty()}
     */
    public Optional<String> shouldNotifyResults(List<SubmitHandler.BackgroundResult> results,
                                                boolean idle, boolean inputEmpty) {
        if (results == null || results.isEmpty()) return Optional.empty();
        if (!idle || !inputEmpty) return Optional.empty();
        if (consecutive >= maxConsecutive) return Optional.empty();
        consecutive++;
        return Optional.of(compose(results.stream().map(Row::of).toList()));
    }

    /** 用户有真实输入（提交了一条消息）：重置刹车。 */
    public void onUserInput() {
        consecutive = 0;
    }

    /** 刹车是否已踩下（供状态栏提示「回车交给模型」）。 */
    public boolean brakeEngaged() {
        return consecutive >= maxConsecutive;
    }

    /** 两个入口共用的行视图：只留合成文本真正要的四个字段，谁来的都先摊平成它。 */
    private record Row(String taskId, String agentName, String description, String result, boolean ok) {
        static Row of(BackgroundTask t) {
            return new Row(t.taskId(), t.agentName(), t.description(), t.result(),
                    t.status() == BackgroundTask.Status.DONE);
        }
        static Row of(SubmitHandler.BackgroundResult r) {
            return new Row(r.taskId(), r.agentName(), r.description(), r.result(), r.ok());
        }
    }

    /**
     * 合成通知文本。<b>多个任务合并成一条</b>——起 N 个回合会让模型在没读完第一条时就被第二条打断。
     */
    private String compose(List<Row> rows) {
        StringBuilder sb = new StringBuilder("[后台任务完成]\n");
        for (Row t : rows) {
            sb.append('\n')
              .append(t.taskId()).append(" · ").append(t.agentName())
              .append(" · ").append(t.description())
              .append(t.ok() ? " · ✓" : " · ✗")
              .append('\n')
              .append(t.result())
              .append('\n');
        }
        sb.append("\n以上是你先前派出的后台任务的结果。")
          .append("请检查你的 Todo 计划列表，找出下一项 pending 的任务并立即执行；")
          .append("如果所有任务已完成，向用户汇报最终结果。");
        return sb.toString();
    }
}

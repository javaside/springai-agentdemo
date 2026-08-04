package io.github.javaside.springai.codetui.agent.background;

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
        return Optional.of(compose(completedUnconsumed));
    }

    /** 用户有真实输入（提交了一条消息）：重置刹车。 */
    public void onUserInput() {
        consecutive = 0;
    }

    /** 刹车是否已踩下（供状态栏提示「回车交给模型」）。 */
    public boolean brakeEngaged() {
        return consecutive >= maxConsecutive;
    }

    /**
     * 合成通知文本。<b>多个任务合并成一条</b>——起 N 个回合会让模型在没读完第一条时就被第二条打断。
     */
    private String compose(List<BackgroundTask> tasks) {
        StringBuilder sb = new StringBuilder("[后台任务完成]\n");
        for (BackgroundTask t : tasks) {
            sb.append('\n')
              .append(t.taskId()).append(" · ").append(t.agentName())
              .append(" · ").append(t.description())
              .append(t.status() == BackgroundTask.Status.DONE ? " · ✓" : " · ✗")
              .append('\n')
              .append(t.result())
              .append('\n');
        }
        sb.append("\n以上是你先前派出的后台任务的结果，请据此继续。");
        return sb.toString();
    }
}

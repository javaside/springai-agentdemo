package io.github.javaside.springai.codetui.agent.background;

/**
 * 一个后台子 agent 任务的身份 + 可变状态。
 *
 * <p><b>刻意不是 record</b>：status / result 都要就地更新，而 taskId 等身份字段不变。
 * 用可变类比"每次改都造一个新 record"更贴合它的用法——注册表按 taskId 索引，
 * 换对象等于每次更新都要替换 map 里的值。
 *
 * <p>并发：所有读写都在 {@link BackgroundTaskRegistry} 的锁内完成，本类自身不加锁。
 */
public final class BackgroundTask {

    public enum Status {
        /** 在跑。 */ RUNNING,
        /** 正常结束。 */ DONE,
        /** 抛异常结束。 */ FAILED,
        /** 被用户经 /tasks 面板终止。 */ KILLED
    }

    private final String taskId;
    private final String agentName;
    private final String description;
    private final long startedAt;

    private Status status = Status.RUNNING;
    private long finishedAt;
    private String result = "";
    private boolean consumed;

    BackgroundTask(String taskId, String agentName, String description, long startedAt) {
        this.taskId = taskId;
        this.agentName = agentName;
        this.description = description == null ? "" : description;
        this.startedAt = startedAt;
    }

    public String taskId() { return taskId; }
    public String agentName() { return agentName; }
    public String description() { return description; }
    public long startedAt() { return startedAt; }
    public Status status() { return status; }
    public long finishedAt() { return finishedAt; }
    public String result() { return result; }
    public boolean consumed() { return consumed; }

    /** 是否已结束（不再运行）。KILLED 也算结束。 */
    public boolean finished() { return status != Status.RUNNING; }

    /** 是否"跑完且有结果值得送给模型"——KILLED 不算（用户主动杀的，不该再回灌）。 */
    public boolean deliverable() { return status == Status.DONE || status == Status.FAILED; }

    void finish(Status newStatus, String result, long at) {
        this.status = newStatus;
        this.result = result == null ? "" : result;
        this.finishedAt = at;
    }

    void setConsumed() { this.consumed = true; }

    /** 耗时毫秒：未结束则算到现在。 */
    public long elapsedMillis(long now) {
        return (finished() ? finishedAt : now) - startedAt;
    }
}

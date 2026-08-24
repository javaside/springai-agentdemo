package io.github.javaside.springai.codetui.agent.background;

import java.util.ArrayList;
import java.util.List;

/**
 * 把后台任务快照渲染成<b>给模型看</b>的文本。
 *
 * <p><b>唯一的措辞出处</b>：{@code /continue} 提示词注入、{@code ListTasks} 工具、
 * {@code TaskOutput} 查无此 id 三条路都走它，于是模型无论从哪儿看到后台任务，
 * 读到的都是同一套说法。三处各写一遍的话，改一处忘两处，模型就会在同一件事上
 * 收到互相矛盾的描述。
 *
 * <p><b>接快照而不接注册表</b>：注册表的方法都是 {@code synchronized}，调用方取一次
 * {@link BackgroundTaskRegistry#all()} 就够；本类若持有注册表，实现里就会忍不住取两次
 * （比如再调一次 {@code completedUnconsumed()}），而两次之间状态会变。
 * {@link BackgroundTask#consumed()} 是 public 的，一份快照足以筛出所有分组。
 */
public final class BackgroundDigest {

    private BackgroundDigest() {
    }

    /**
     * 给 {@code /continue} 用：只列会造成<b>重复劳动</b>的两格。
     *
     * <p><b>为什么 FAILED / KILLED 一个字都不提</b>：{@code /continue} 的本意是
     * 「把没做完的接着做完」，而它们就是普通的「没做完」——模型看 todo 自然会重派，
     * 重派正是要的结果。在这里给它们加任何指引（「先看看失败原因」之类），
     * 都是替用户做了他敲下 {@code /continue} 时已经做过的决定。
     *
     * @param snapshot 注册表快照（一次取好传进来，避免实现里取两次而两次之间状态已变）
     * @return 需要提醒时的文本；两格都空且注册表非空时返回<b>空串</b>（调用方据此不改提示词）
     */
    public static String forContinue(List<BackgroundTask> snapshot) {
        List<BackgroundTask> tasks = snapshot == null ? List.of() : snapshot;
        if (tasks.isEmpty()) {
            return CROSS_PROCESS_HINT;
        }
        List<String> rows = new ArrayList<>();
        for (BackgroundTask t : tasks) {
            if (t == null) {
                continue;
            }
            if (t.status() == BackgroundTask.Status.RUNNING) {
                rows.add(row(t) + "  运行中 → 正在做，不要重复委派");
            } else if (t.status() == BackgroundTask.Status.DONE && !t.consumed()) {
                rows.add(row(t) + "  已完成待取回 → 先用 TaskOutput(" + t.taskId()
                        + ") 取回结果，它对应的任务可能已经做完了");
            }
        }
        if (rows.isEmpty()) {
            return "";
        }
        return "当前进程仍有后台任务：\n" + String.join("\n", rows);
    }

    /** 给 {@code ListTasks} 用：列全部四种状态。空注册表返回一句话而非空串。
     *
     * @param snapshot 注册表快照
     * @return 全部任务的清单文本
     */
    public static String full(List<BackgroundTask> snapshot) {
        List<BackgroundTask> tasks = snapshot == null ? List.of() : snapshot;
        if (tasks.isEmpty()) {
            return "当前没有后台任务。";
        }
        List<String> rows = new ArrayList<>();
        for (BackgroundTask t : tasks) {
            if (t == null) {
                continue;
            }
            rows.add(row(t) + "  " + statusLabel(t));
        }
        return "当前后台任务 " + rows.size() + " 个：\n" + String.join("\n", rows);
    }

    /**
     * {@code -c} 恢复会话后注册表必然是空的，而会话历史里还留着
     * {@code 已在后台启动：task_xxx}。模型读到那些会以为它们还在跑，于是干等或跳过
     * ——而用户敲 {@code /continue} 恰恰是要它继续做那些活。
     *
     * <p><b>写成自条件句</b>：UI 无法便宜地知道「历史里提没提过后台任务」，
     * 但模型正在读那段历史，它自己判断得了。代价是一句话，收益是不必把
     * 「本进程是否恢复自旧会话」这个启动期事实一路穿到视图层。
     */
    private static final String CROSS_PROCESS_HINT =
            "若你在历史里看到过「已在后台启动：task_xxx」，注意后台任务不跨进程保存"
                    + "——恢复会话后它们一律已经结束，需要重新派发，不要等它们。";

    /** 一行的前半段：id + agent + 描述。<b>刻意不含结果正文</b>，见类注释与 forContinue。 */
    private static String row(BackgroundTask t) {
        return "  " + t.taskId() + "  " + t.agentName() + "  " + t.description();
    }

    private static String statusLabel(BackgroundTask t) {
        return switch (t.status()) {
            case RUNNING -> "运行中";
            case DONE -> t.consumed() ? "已完成（结果已取回）" : "已完成待取回";
            case FAILED -> "已失败";
            case KILLED -> "已终止";
        };
    }
}

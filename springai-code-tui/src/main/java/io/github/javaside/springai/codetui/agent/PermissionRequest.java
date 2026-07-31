package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.permission.PermissionRule;

import java.util.Objects;

/**
 * 一次审批请求：由 {@code PermissionCallback} 构造、经 {@link AgentListener#onPermissionRequested}
 * 交给 UI；UI 弹面板，用户选完经 {@link #responder} 唤醒阻塞的工具线程。
 *
 * @param turnId    发起回合（迟到过滤：{@code turnId != activeTurnId} 的请求直接 DENY，不弹面板）
 * @param taskId    子 agent 任务 id（非 null 时面板标题带来源，如「来自子 agent explore」）
 * @param toolName  工具注册名
 * @param target    判定目标（路径 / 命令 / 整串入参）——面板主体显示
 * @param rawInput  原始入参 JSON（面板可展开；{@code Write}/{@code Edit} 交给 DiffRenderer 渲染改动）
 * @param reason    为什么要问（引擎给的人话原因）
 * @param suggested 建议规则：面板的「本会话不再问」/「永久允许」据它生成；可为 null
 * @param responder UI 应答回调，<b>不可为 null</b>（见紧凑构造器）
 */
public record PermissionRequest(long turnId, String taskId, String toolName, String target,
                                String rawInput, String reason, PermissionRule suggested,
                                PermissionResponder responder) implements ModalRequest {

    /**
     * 拒绝 null responder：<b>在构造期失败，而不是在排空期</b>。
     *
     * <p>无保护直通的实测后果：模态队列 {@code [A, B]}，A 的 responder 为 null →
     * {@code cancelCurrent()} 的排空循环在 A 处抛 NPE 中断 → <b>B 的工具线程永不被唤醒</b>，
     * 且异常沿 {@code synchronized} 的 {@code cancelCurrent()} 传到 UI 线程的 Esc 处理器。
     * 一行下游 bug 就能制造出本任务专门要防的那种挂死。
     *
     * <p>在这里失败只发生在工具线程上、只影响那一次工具调用，代价小得多。
     */
    public PermissionRequest {
        Objects.requireNonNull(responder, "responder 不可为 null：null 会让取消期的排空循环中断，"
                + "队列里其后的工具线程永久 park");
    }

    /**
     * {@link ModalRequest} 统一取消入口：给工具线程投 CANCEL，它随后抛 {@link PermissionCancelledException}。
     *
     * <p>契约要求本方法不抛异常，故此处<b>无</b> try/catch：responder 非 null 由构造期保证，
     * 而「{@code respond} 实现自身不抛」由 {@link PermissionResponder} 契约保证。
     */
    @Override
    public void cancel() {
        responder.respond(PermissionOutcome.CANCEL);
    }
}

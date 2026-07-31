package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.permission.PermissionRule;

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
 * @param responder UI 应答回调
 */
public record PermissionRequest(long turnId, String taskId, String toolName, String target,
                                String rawInput, String reason, PermissionRule suggested,
                                PermissionResponder responder) implements ModalRequest {

    /** {@link ModalRequest} 统一取消入口：给工具线程投 CANCEL，它随后抛 {@link PermissionCancelledException}。 */
    @Override
    public void cancel() {
        responder.respond(PermissionOutcome.CANCEL);
    }
}

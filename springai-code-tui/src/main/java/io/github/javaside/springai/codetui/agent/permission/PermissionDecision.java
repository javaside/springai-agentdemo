package io.github.javaside.springai.codetui.agent.permission;

/**
 * 一次判定的结果。
 *
 * @param behavior  结论
 * @param reason    人话原因（直接显示在审批面板 / 拼进给模型的拒绝串），写成一句人能据以决定的话
 * @param suggested 建议规则：面板的「本会话不再问」/「永久允许」据它生成。
 *                  <b>仅当「加一条 allow 规则真能让下次不再问」时才非 null</b>——
 *                  内置危险检查（第 2 步）与 ask 规则（第 4 步）都排在 allow 规则（第 5 步）之前，
 *                  它们引发的 ASK 无论加什么 allow 规则都照样会问，给建议就是骗人
 *                  （与 Task 7R 第 6 条「别把已被 deny 的规则写成永久允许」是同一类错误）。
 *                  详见 {@link PermissionEngine#decide}
 */
public record PermissionDecision(PermissionBehavior behavior, String reason, PermissionRule suggested) {

    public static PermissionDecision allow(String reason) {
        return new PermissionDecision(PermissionBehavior.ALLOW, reason, null);
    }

    public static PermissionDecision deny(String reason) {
        return new PermissionDecision(PermissionBehavior.DENY, reason, null);
    }

    public static PermissionDecision ask(String reason, PermissionRule suggested) {
        return new PermissionDecision(PermissionBehavior.ASK, reason, suggested);
    }

    /** 无法靠加规则消除的 ASK（内置危险检查 / ask 规则命中）：面板只给「本次允许」与「拒绝」。 */
    public static PermissionDecision askOnly(String reason) {
        return new PermissionDecision(PermissionBehavior.ASK, reason, null);
    }
}

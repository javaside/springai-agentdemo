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
 * @param bypassedGuardrail 仅 BYPASS 放行时非 null：<b>被跳过的那条内置底线的理由串</b>
 *                  （形如「写入 .git/ 内部（…）：/p/.git/hooks/pre-commit」）。
 *                  <b>它不影响结论</b>，只是把「这次放行踩了底线」这个事实捎给
 *                  {@code PermissionCallback} 去留痕。
 *                  <p><b>为什么走判定结果而不是给引擎注入 listener</b>：引擎是个纯判定器
 *                  （{@code decide(toolName, toolInput) → PermissionDecision}），
 *                  塞进 UI 依赖会让它既难测又难在别的宿主里复用；而 {@code PermissionCallback}
 *                  本来就是判定与 UI 之间的那一层，它已经持有 listener 与 turnId。
 */
public record PermissionDecision(PermissionBehavior behavior, String reason, PermissionRule suggested,
                                 String bypassedGuardrail) {

    public static PermissionDecision allow(String reason) {
        return new PermissionDecision(PermissionBehavior.ALLOW, reason, null, null);
    }

    /**
     * BYPASS 放行，且这次操作命中了内置底线。
     *
     * @param bypassedGuardrail 被跳过的底线理由串；为 null 时与 {@link #allow} 等价（没踩底线）
     */
    public static PermissionDecision allowBypassingGuardrail(String reason, String bypassedGuardrail) {
        return new PermissionDecision(PermissionBehavior.ALLOW, reason, null, bypassedGuardrail);
    }

    public static PermissionDecision deny(String reason) {
        return new PermissionDecision(PermissionBehavior.DENY, reason, null, null);
    }

    public static PermissionDecision ask(String reason, PermissionRule suggested) {
        return new PermissionDecision(PermissionBehavior.ASK, reason, suggested, null);
    }

    /** 无法靠加规则消除的 ASK（内置危险检查 / ask 规则命中）：面板只给「本次允许」与「拒绝」。 */
    public static PermissionDecision askOnly(String reason) {
        return new PermissionDecision(PermissionBehavior.ASK, reason, null, null);
    }
}

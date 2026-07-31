package io.github.javaside.springai.codetui.agent.permission;

/**
 * 一次判定的结论。
 *
 * <p>刻意<b>不含</b> spec 里的 {@code PASSTHROUGH}：它只服务于被推迟到期 3 的「工具自审」
 * 钩子（{@code PermissionAware}），本期无生产者，加进来只会是死分支。
 */
public enum PermissionBehavior {
    /** 直接放行。 */
    ALLOW,
    /** 拒绝——拦截器返回拒绝字符串给模型，<b>不抛异常</b>、回合继续。 */
    DENY,
    /** 需要人工审批——拦截器阻塞工具线程，等 UI 喂回结果。 */
    ASK
}

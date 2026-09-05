package io.github.javaside.springai.codetui.agent.llm;

/**
 * L1 重试事件钩子：{@link RetryingStreamChatModel} 每次决定重试（退避等待之前）上报一次，
 * 装配层把它桥到 CodingAgent → {@code listener.onRetryScheduled(turnId, attempt, backoffMs, reason)}
 * 驱动 UI 的 ↻ 提示行（spec §3.2「L1 的 UI 可见性」）。无绑定为 no-op（传 null）。
 *
 * <p><b>attempt 口径（勿混，spec 第 8 轮 #8）</b>：即将进行的<b>第几次尝试</b>，取值 2..5——
 * 与 UI 文案 {@code (2/5·传输)} 的尝试序号一致；与 {@link RetryPolicy#backoffMsAfter} 的
 * 1 基「第 n 次尝试<b>失败后</b>」序号差 1。
 *
 * <p><b>时序纪律（R6-m3）</b>：经 {@code Retry.backoff(...).doBeforeRetry(...)} 触发——它在退避
 * delay 之前、错误信号线程上同步执行，故本回调严格早于新一轮的首个 chunk，UI 时序不可能乱序。
 *
 * <p><b>turnId 绑定纪律</b>：桥接闭包在 submit 时携带本回合 turnId，回调内比对「在飞回合 ==
 * 携带值」才发事件——旧链迟到的 ↻ 行不会记到新回合头上。装饰器只依赖本接口，桥接发生在装配层。
 *
 * <p><b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。
 */
@FunctionalInterface
public interface RetryReporter {

    /**
     * 一次 L1 重试已排定。
     *
     * @param attempt   即将进行的第几次尝试（2..5；首重试 = 2）
     * @param backoffMs 即将等待的退避毫秒数（与真实 delay 同公式现算，jitter(0) 下严格相等）
     * @param reason    根因摘要（{@link RetryingStreamChatModel#reasonOf} 规则：沿 cause 链取
     *                  首个非空 message，兜底类名，显示宽截 60 尾加 …）——直接流入 UI ↻ 行
     */
    void report(int attempt, long backoffMs, String reason);
}

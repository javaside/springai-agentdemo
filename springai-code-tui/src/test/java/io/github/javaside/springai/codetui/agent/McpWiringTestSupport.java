package io.github.javaside.springai.codetui.agent;

/**
 * 测试专用小工具：造一个不发网络请求的假 {@link ProviderRegistry}。
 *
 * <p>与 {@link AgentRuntimeTest#dummyRegistry()} 的构造方式完全一致（假 key 的 DeepSeekProvider），
 * 单一事实来源避免两处漂移——若其中一处以后改了假 key 构造方式，需同步改这里。
 *
 * <p><b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。
 */
public final class McpWiringTestSupport {

    private McpWiringTestSupport() {
    }

    /**
     * <p><b>内部类型</b>：升 public 仅为跨包装配，勿在 agent 包外依赖。
     */
    public static ProviderRegistry dummyRegistry() {
        return new ProviderRegistry(java.util.List.of(new DeepSeekProvider("fake-key")));
    }
}

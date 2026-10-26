package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.hooks.HookRegistry;
import io.github.javaside.springai.codetui.agent.llm.DeepSeekProvider;
import io.github.javaside.springai.codetui.agent.llm.ProviderRegistry;
import io.github.javaside.springai.codetui.agent.llm.StreamRetryConfig;
import io.github.javaside.springai.codetui.agent.seam.StubListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * build 第 8 参的 registry 必须进 AgentRuntime 并保持同一实例（装配漂移守卫）。
 * 桩段（provider/listener/引擎）整段照抄 AgentToolsTodoReminderWiringTest——模板已解决
 * 「最小可 build」问题并自身在仓内被测试；本测试唯一的【新】断言是 assertSame。
 */
class AgentToolsHooksWiringTest {

    @Test
    void runtimeCarriesSameRegistryInstance(@TempDir Path root) {
        HookRegistry hooks = HookRegistry.load(root,
                root.resolve("no-user.json"), root.resolve("no-proj.json"), root.resolve("no-trust.json"));

        // 桩段照抄 AgentToolsTodoReminderWiringTest（模板的 3 参 build 落到全参重载的委托链），本处改为
        // 显式 8 参调用。retryConfig 位与模板 3 参路径同为 fromEnv()——传 null 会在 provider 循环
        // NPE（build 内 retryConfig.l1Enabled() 直接解引用，fake-key provider 是 available 的）。
        AgentTools.AgentRuntime rt = AgentTools.build(
                new ProviderRegistry(List.of(new DeepSeekProvider("fake-key"))), root, new StubListener(), null,
                AgentTools.testEngine(root), StreamRetryConfig.fromEnv(), null, hooks);

        assertSame(hooks, rt.hookRegistry(), "runtime 暴露同一 registry 实例（7 处装饰链共用）");
    }
}

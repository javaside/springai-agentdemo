package io.github.javaside.springai.codetui.agent;
import io.github.javaside.springai.codetui.agent.llm.ProviderRegistry;
import io.github.javaside.springai.codetui.agent.llm.DeepSeekProvider;
import io.github.javaside.springai.codetui.agent.llm.ProviderModel;
import io.github.javaside.springai.codetui.agent.llm.AnthropicProvider;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** registry 注入后，/model 的三个 SubmitHandler 方法跨家生效。 */
class CodingAgentModelSwitchTest {

    private CodingAgent agentWithRegistry() {
        ProviderRegistry reg = new ProviderRegistry(List.of(
                new DeepSeekProvider("k"), new AnthropicProvider("k")));
        return new CodingAgent(reg, Map.of(), new StubListener(), "s", new AtomicLong(),
                null, null, null, List.of(), null, null, null);
    }

    @Test
    void modelsAggregateAcrossProviders() {
        List<String> ids = agentWithRegistry().models().stream().map(ProviderModel::modelId).toList();
        assertTrue(ids.contains("deepseek-v4-flash"));
        assertTrue(ids.contains("claude-sonnet-5"));
    }

    @Test
    void selectModelSwitchesProviderAndCurrentModel() {
        CodingAgent a = agentWithRegistry();
        assertEquals("deepseek-v4-pro", a.currentModel());
        a.selectModel("anthropic", "claude-opus-5");
        assertEquals("claude-opus-5", a.currentModel());
        assertEquals("anthropic", a.currentProviderId());
    }
}

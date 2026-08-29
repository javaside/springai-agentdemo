// AgentToolsMediaWiringTest.java
package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.llm.ProviderRegistry;
import io.github.javaside.springai.codetui.agent.llm.DeepSeekProvider;
import io.github.javaside.springai.codetui.ui.ConversationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 冒烟：媒体装饰器织入后，build 仍能离线装配出完整 runtime（用假 key，不发网络）。
 *  模式照抄 AgentRuntimeTest.dummyRegistry()。 */
class AgentToolsMediaWiringTest {
    @Test
    void build_withMediaDecorator_ok(@TempDir Path root) {
        ProviderRegistry reg = new ProviderRegistry(List.of(new DeepSeekProvider("fake-key")));
        AgentTools.AgentRuntime rt = AgentTools.build(reg, root, new ConversationState());
        assertNotNull(rt);
        assertNotNull(rt.client());
    }
}

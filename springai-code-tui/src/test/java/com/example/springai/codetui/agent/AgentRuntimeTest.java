package com.example.springai.codetui.agent;

import com.example.springai.codetui.ui.ConversationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/** AgentTools.build 只做装配、不发网络请求：用假 key 也能造出完整 AgentRuntime。 */
class AgentRuntimeTest {

    private static DeepSeekChatModel dummyModel() {
        DeepSeekApi api = DeepSeekApi.builder().apiKey("test-key")
                .baseUrl("https://api.deepseek.com").build();
        return DeepSeekChatModel.builder().deepSeekApi(api)
                .options(DeepSeekChatOptions.builder().model("deepseek-v4-flash").build()).build();
    }

    @Test
    void build_returnsRuntime_withAllHandles(@TempDir Path root) {
        AgentTools.AgentRuntime rt = AgentTools.build(dummyModel(), root, new ConversationState());
        assertNotNull(rt.client(), "ChatClient 必须装配出来");
        assertNotNull(rt.sessionService(), "SessionService 必须暴露（供手动 /compact）");
        assertNotNull(rt.manualStrategy(), "手动压缩策略必须暴露");
    }
}

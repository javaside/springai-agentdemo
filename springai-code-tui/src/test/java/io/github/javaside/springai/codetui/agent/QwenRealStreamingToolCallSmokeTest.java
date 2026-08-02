package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 千问真机冒烟（手动/验收用）：打真实百炼端点，走「流式 + 工具调用」——正是
 * ChunkMerger 空串 id 崩溃的复现路径（见 QwenSseNormalizingHttpClient）。
 *
 * <p>默认跳过；配了 DASHSCOPE_API_KEY 才跑（会产生真实计费调用）：
 * {@code DASHSCOPE_API_KEY=... mvn -pl springai-code-tui test -Dtest=QwenRealStreamingToolCallSmokeTest}
 */
@EnabledIfEnvironmentVariable(named = "CODETUI_LIVE_TESTS", matches = "1")   // 默认不跑：联网、花钱、且墙钟断言天生不稳
@EnabledIfEnvironmentVariable(named = "DASHSCOPE_API_KEY", matches = ".+")
class QwenRealStreamingToolCallSmokeTest {

    static class WeatherTool {
        final AtomicBoolean invoked = new AtomicBoolean();

        @Tool(description = "查询指定城市的当前天气")
        String getWeather(String city) {
            invoked.set(true);
            return city + "：晴，25℃";
        }
    }

    @Test
    void streamingToolCall_completesWithoutChunkMergerCrash() {
        QwenProvider p = new QwenProvider(System.getenv("DASHSCOPE_API_KEY"), System.getenv("DASHSCOPE_BASE_URL"));
        WeatherTool tool = new WeatherTool();
        ChatClient client = ChatClient.builder(p.chatModel()).defaultTools(tool).build();

        List<String> chunks = client.prompt()
                .user("调用工具查询北京天气，并告诉我结果")
                .options(p.options(p.defaultModel()).mutate())
                .stream().content()
                .collectList()
                .block(Duration.ofMinutes(3));

        assertTrue(tool.invoked.get(), "模型应触发工具调用（流式 tool_calls 分片路径）");
        assertFalse(String.join("", chunks).isBlank(), "工具结果回填后应有非空流式回答");
    }
}

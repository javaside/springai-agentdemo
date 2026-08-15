package io.github.javaside.springai.codetui.agent;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住 DeepSeek 流式请求必须带 {@code stream_options.include_usage=true}：不注入则 DeepSeek 流式不返回
 * usage，token 采集器拿不到计费输入、缓存命中率恒为空。这里只断言<b>出站请求体</b>（同步捕获、确定性），
 * 「usage 到达累加器」由 {@code UsageRecordingChatModelTest} / {@code CacheUsageExtractorTest} /
 * {@code TokenUsageAccumulatorTest} 各层单测覆盖。
 */
class DeepSeekUsageStreamingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void streamingRequestCarriesIncludeUsage() throws Exception {
        AtomicReference<JsonNode> streaming = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            JsonNode body = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
            boolean stream = body.path("stream").asBoolean(false);
            if (stream) {
                streaming.set(body);
            }
            byte[] response = stream
                    ? "data: {\"id\":\"x\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":null}],\"created\":1,\"model\":\"deepseek-v4-pro\",\"object\":\"chat.completion.chunk\"}\n\ndata: [DONE]\n\n"
                            .getBytes(StandardCharsets.UTF_8)
                    : "{\"id\":\"x\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],\"created\":1,\"model\":\"deepseek-v4-pro\",\"object\":\"chat.completion\",\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}"
                            .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", stream ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            DeepSeekProvider provider = new DeepSeekProvider("fake", "http://127.0.0.1:" + server.getAddress().getPort());
            Prompt prompt = new Prompt(List.of(new UserMessage("hello")), provider.options("deepseek-v4-pro"));
            provider.chatModel().stream(prompt).blockLast();

            JsonNode request = streaming.get();
            assertTrue(request != null, "应发出一次流式请求");
            assertTrue(request.path("stream_options").path("include_usage").asBoolean(false),
                    "流式请求必须带 stream_options.include_usage=true");
        } finally {
            server.stop(0);
        }
    }
}

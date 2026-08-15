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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住 DeepSeek 流式请求必须带 {@code stream_options.include_usage=true}：不注入则 DeepSeek 流式不返回
 * usage，token 采集器拿不到计费输入、缓存命中率恒为空。
 *
 * <p>① 断言<b>出站请求体</b>（同步捕获、确定性）；② 端到端断言：带 {@code prompt_tokens_details.cached_tokens}
 * 的 SSE 响应经真实 DeepSeekChatModel 流式链后，缓存命中数到达 {@code TokenUsageAccumulator}
 * （覆盖「各层单测手工喂 Usage 对象」测不到的 nativeUsage 保真链路）。
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

    @Test
    void cachedTokensReachAccumulatorThroughRealStreamingChain() throws Exception {
        // 最后 chunk 带 usage：prompt_tokens=100（含 cached_tokens=80），走真实 DeepSeekChatModel 流式链
        String sse =
                "data: {\"id\":\"x\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"hello\"},\"finish_reason\":null}],\"created\":1,\"model\":\"deepseek-v4-pro\",\"object\":\"chat.completion.chunk\"}\n\n"
                + "data: {\"id\":\"x\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\" world\"},\"finish_reason\":\"stop\"}],\"created\":1,\"model\":\"deepseek-v4-pro\",\"object\":\"chat.completion.chunk\",\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":50,\"total_tokens\":150,\"prompt_tokens_details\":{\"cached_tokens\":80}}}\n\n"
                + "data: [DONE]\n\n";
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            byte[] resp = sse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();
        try {
            DeepSeekProvider provider = new DeepSeekProvider("fake", "http://127.0.0.1:" + server.getAddress().getPort());
            TokenUsageAccumulator acc = new TokenUsageAccumulator();
            UsageRecordingChatModel recorded = new UsageRecordingChatModel(provider.chatModel(), acc);

            Prompt prompt = new Prompt(List.of(new UserMessage("hello")), provider.options("deepseek-v4-pro"));
            recorded.stream(prompt).blockLast();

            var s = acc.snapshot();
            assertEquals(100L, s.promptTokens(), "计费输入应端到端到达累加器");
            assertEquals(50L, s.completionTokens(), "输出 token 应端到端到达累加器");
            assertEquals(80L, s.cacheReadTokens(),
                    "nativeUsage 里的 cached_tokens 必须经 CacheUsageExtractor 兜底记到（丢失则缓存命中率恒 0）");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void cachedTokensReachAccumulatorThroughChatClientChain() throws Exception {
        // 模拟主 agent 生产链：UsageRecordingProvider + ChatClient + stream().chatClientResponse()。
        // usage 分片与真实 API 一致：choices 为空数组，且带 spring-ai-deepseek 不认识的字
        // 段（completion_tokens_details / prompt_cache_hit_tokens），验证解析容错。
        String sse =
                "data: {\"id\":\"x\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"hello\"},\"finish_reason\":null}],\"created\":1,\"model\":\"deepseek-v4-pro\",\"object\":\"chat.completion.chunk\"}\n\n"
                + "data: {\"id\":\"x\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\" world\"},\"finish_reason\":\"stop\"}],\"created\":1,\"model\":\"deepseek-v4-pro\",\"object\":\"chat.completion.chunk\"}\n\n"
                + "data: {\"id\":\"x\",\"choices\":[],\"created\":1,\"model\":\"deepseek-v4-pro\",\"object\":\"chat.completion.chunk\",\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":50,\"total_tokens\":150,\"prompt_tokens_details\":{\"cached_tokens\":80},\"completion_tokens_details\":{\"reasoning_tokens\":42},\"prompt_cache_hit_tokens\":80,\"prompt_cache_miss_tokens\":20}}\n\n"
                + "data: [DONE]\n\n";
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            byte[] resp = sse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();
        try {
            DeepSeekProvider provider = new DeepSeekProvider("fake", "http://127.0.0.1:" + server.getAddress().getPort());
            TokenUsageAccumulator acc = new TokenUsageAccumulator();
            UsageRecordingProvider wrapped = new UsageRecordingProvider(provider, acc);

            org.springframework.ai.chat.client.ChatClient client =
                    org.springframework.ai.chat.client.ChatClient.builder(wrapped.chatModel()).build();
            client.prompt().user("hello")
                    .stream().chatClientResponse().blockLast();

            var s = acc.snapshot();
            assertEquals(100L, s.promptTokens(), "计费输入应经 ChatClient 链端到端到达累加器");
            assertEquals(80L, s.cacheReadTokens(),
                    "cached_tokens 必须经 ChatClient 生产链到达累加器（丢失则缓存命中率不显示）");
        } finally {
            server.stop(0);
        }
    }
}

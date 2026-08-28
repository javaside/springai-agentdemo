package io.github.javaside.springai.codetui.agent.llm;

import com.sun.net.httpserver.HttpServer;
import io.github.javaside.springai.codetui.agent.thinking.ThinkingConfig;
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

class DeepSeekThinkingHttpIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void blockingAndStreamingRequestsCarryThinkingFields() throws Exception {
        AtomicReference<JsonNode> blocking = new AtomicReference<>();
        AtomicReference<JsonNode> streaming = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            JsonNode body = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
            boolean stream = body.path("stream").asBoolean(false);
            (stream ? streaming : blocking).set(body);
            byte[] response = stream ? ("data: {\"id\":\"x\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":null}],\"created\":1,\"model\":\"deepseek-v4-pro\",\"object\":\"chat.completion.chunk\"}\n\n"
                    + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8)
                    : "{\"id\":\"x\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],\"created\":1,\"model\":\"deepseek-v4-pro\",\"object\":\"chat.completion\",\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", stream ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            DeepSeekProvider provider = new DeepSeekProvider("fake", "http://127.0.0.1:" + server.getAddress().getPort());
            Prompt prompt = new Prompt(List.of(new UserMessage("hello")),
                    provider.options("deepseek-v4-pro", ThinkingConfig.enabledEffort("max")));
            provider.chatModel().call(prompt);
            provider.chatModel().stream(prompt).blockLast();
            assertEquals("enabled", blocking.get().path("thinking").path("type").stringValue());
            assertEquals("max", blocking.get().path("reasoning_effort").stringValue());
            assertEquals("enabled", streaming.get().path("thinking").path("type").stringValue());
            assertEquals("max", streaming.get().path("reasoning_effort").stringValue());
        } finally {
            server.stop(0);
        }
    }
}

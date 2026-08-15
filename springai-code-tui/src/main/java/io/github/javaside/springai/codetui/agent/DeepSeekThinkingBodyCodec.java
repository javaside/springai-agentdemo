package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.thinking.ThinkingConfig;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Adds DeepSeek thinking fields to an already serialized chat-completion request. */
final class DeepSeekThinkingBodyCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DeepSeekThinkingBodyCodec() {
    }

    static byte[] decorate(byte[] body, ThinkingConfig config) {
        if (config.mode() == io.github.javaside.springai.codetui.agent.thinking.ThinkingMode.DEFAULT) {
            return body;
        }
        try {
            JsonNode parsed = MAPPER.readTree(body);
            if (!(parsed instanceof ObjectNode root)) {
                return body;
            }
            ObjectNode thinking = root.putObject("thinking");
            thinking.put("type", config.mode() == io.github.javaside.springai.codetui.agent.thinking.ThinkingMode.ENABLED
                    ? "enabled" : "disabled");
            if (config.effort() != null) {
                root.put("reasoning_effort", config.effort());
            }
            return MAPPER.writeValueAsBytes(root);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("DeepSeek 请求不是合法 JSON，无法加入思考配置", e);
        }
    }

    /**
     * 流式请求的请求体改写：先注入 {@code stream_options.include_usage=true}，再叠加思考配置。
     *
     * <p><b>为什么必须注入</b>：DeepSeek 流式默认<b>不返回 usage</b>（与 OpenAI 一致，只有请求显式带
     * {@code stream_options.include_usage=true} 才会在最后一个 chunk 附上 usage），而 spring-ai-deepseek 的
     * {@code ChatCompletionRequest} 又没有 {@code stream_options} 字段、也不会自动加。不注入则 token 采集器
     * 永远拿不到计费输入，缓存命中率恒为空。{@link #decorate} 只处理思考，这里先把 usage 打开，再交它叠加思考。
     */
    static byte[] decorateStreaming(byte[] body, ThinkingConfig config) {
        return decorate(injectStreamOptions(body), config);
    }

    private static byte[] injectStreamOptions(byte[] body) {
        try {
            JsonNode parsed = MAPPER.readTree(body);
            if (!(parsed instanceof ObjectNode root)) {
                return body;
            }
            ObjectNode streamOptions = root.putObject("stream_options");
            streamOptions.put("include_usage", true);
            return MAPPER.writeValueAsBytes(root);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("DeepSeek 请求不是合法 JSON，无法加入 stream_options", e);
        }
    }
}

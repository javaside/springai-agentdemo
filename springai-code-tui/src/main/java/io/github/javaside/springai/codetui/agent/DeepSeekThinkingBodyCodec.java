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
}

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

    /** 既有两参入口：无视觉注册表 → 纯思考改写（行为与之前完全一致）。 */
    static byte[] decorate(byte[] body, ThinkingConfig config) {
        return decorate(body, config, null);
    }

    /** 思考 + 视觉：先按配置注入 thinking 字段，再把注册表命中的图片写进 user 消息。 */
    static byte[] decorate(byte[] body, ThinkingConfig config,
                           io.github.javaside.springai.codetui.agent.media.DeepSeekVisionMediaRegistry registry) {
        return decorateVision(decorateThinking(body, config), registry);
    }

    private static byte[] decorateThinking(byte[] body, ThinkingConfig config) {
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

    /** 流式：先注入 stream_options，再走 decorate（思考 + 视觉）。 */
    static byte[] decorateStreaming(byte[] body, ThinkingConfig config) {
        return decorateStreaming(body, config, null);
    }

    static byte[] decorateStreaming(byte[] body, ThinkingConfig config,
                                    io.github.javaside.springai.codetui.agent.media.DeepSeekVisionMediaRegistry registry) {
        return decorate(injectStreamOptions(body), config, registry);
    }

    /**
     * 视觉改写：把注册表里命中的图片块插进对应 user 消息的 content 数组。
     *
     * <p><b>只动 role=user 的消息</b>（图片只出现在 user 消息上）；system/assistant/tool 一概不碰。
     * <b>无任何命中 → 原样返回同一 body</b>（纯文本请求零行为变化）。文本块与原 content
     * 逐字一致（引用块、delivery 行等原样保留，模型照常能读「这是哪张图」）。
     *
     * <p><b>key 按「user 消息序号」而非「数组绝对下标」对齐</b>（与注册侧
     * {@code DeepSeekThinkingChatModel.registerMedia} 同一套计数）：序列化层把一条
     * {@code ToolResponseMessage} 的 N 条响应 flatMap 展开成 N 条消息，绝对下标会在带图 user
     * 之前有工具历史时漂移——按绝对下标消费 key 就错位、图片静默丢失（fail-open）。而每条
     * UserMessage 序列化后恰好是一条 role=user 消息、相对顺序不变，故「第几条 user」恒定一致。
     * 这里每见一条 role=user 消息序号 +1（无图的也占位），与注册侧逐字对应。
     *
     * <p><b>查不到 key 即 break 继续</b>（fail-open）：key 是按序号递增的，首个空洞之后的
     * 序号不可能再命中（注册是连续的）；改写失败绝不连累请求。
     */
    static byte[] decorateVision(byte[] body,
                                 io.github.javaside.springai.codetui.agent.media.DeepSeekVisionMediaRegistry registry) {
        if (registry == null) {
            return body;
        }
        try {
            JsonNode parsed = MAPPER.readTree(body);
            if (!(parsed instanceof ObjectNode root)) {
                return body;
            }
            JsonNode messages = root.get("messages");
            if (messages == null || !messages.isArray()) {
                return body;
            }
            boolean changed = false;
            int userSeq = 0;   // 与注册侧同一套「user 序号」计数：每条 role=user 都占位
            for (int i = 0; i < messages.size(); i++) {
                JsonNode msgNode = messages.get(i);
                if (!(msgNode instanceof ObjectNode msg)) {
                    continue;
                }
                if (!"user".equals(msg.path("role").asText())) {
                    continue;
                }
                int thisUser = userSeq++;
                JsonNode content = msg.get("content");
                if (content == null || content.isNull()) {
                    continue;
                }
                java.util.List<JsonNode> blocks = new java.util.ArrayList<>();
                if (content.isTextual()) {
                    blocks.add(MAPPER.createObjectNode().put("type", "text").put("text", content.textValue()));
                } else if (content.isArray()) {
                    content.forEach(blocks::add);   // 防御分支：序列化层只产 string，真数组则保留原块
                } else {
                    continue;
                }
                int j = 0;
                while (true) {
                    io.github.javaside.springai.codetui.agent.media.DeepSeekVisionMediaRegistry.Entry e =
                            registry.take(io.github.javaside.springai.codetui.agent.media.DeepSeekVisionMediaRegistry.key(thisUser, j));
                    if (e == null) {
                        break;
                    }
                    blocks.add(entryToNode(e));
                    changed = true;
                    j++;
                }
                if (j > 0) {
                    msg.set("content", MAPPER.valueToTree(blocks));
                }
            }
            return changed ? MAPPER.writeValueAsBytes(root) : body;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("DeepSeek 请求不是合法 JSON，无法注入图片", e);
        }
    }

    private static JsonNode entryToNode(io.github.javaside.springai.codetui.agent.media.DeepSeekVisionMediaRegistry.Entry e) {
        if (e.transport() == io.github.javaside.springai.codetui.agent.media.DeepSeekVisionMediaRegistry.Transport.FILES) {
            return MAPPER.createObjectNode().put("type", "file").put("file_id", e.fileId());
        }
        String b64 = java.util.Base64.getEncoder().encodeToString(e.bytes());
        ObjectNode imageUrl = MAPPER.createObjectNode()
                .put("url", "data:" + e.mimeType() + ";base64," + b64);
        return MAPPER.createObjectNode().put("type", "image_url").set("image_url", imageUrl);
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

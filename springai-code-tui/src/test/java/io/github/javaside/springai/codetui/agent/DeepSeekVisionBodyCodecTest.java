package io.github.javaside.springai.codetui.agent;

import io.github.javaside.springai.codetui.agent.media.DeepSeekVisionMediaRegistry;
import io.github.javaside.springai.codetui.agent.thinking.ThinkingConfig;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepSeekVisionBodyCodecTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final byte[] PNG = new byte[]{-119, 80, 78, 71, 1, 2, 3};
    private static final String B64 = Base64.getEncoder().encodeToString(PNG);

    private static byte[] userBody(String content) {
        return ("{\"model\":\"deepseek-v4-flash-vision-exp\",\"messages\":["
                + "{\"role\":\"system\",\"content\":\"sys\"},"
                + "{\"role\":\"user\",\"content\":\"" + content + "\"}]}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * 模拟真实会话形状：带图的 user 消息之前有一条含 2 条响应的 tool 消息。
     *
     * <p>spring-ai-deepseek 的 {@code createRequest} 用 {@code flatMap} 把每条
     * {@code ToolResponseMessage} 的 N 条响应展开成 N 条独立消息——序列化后 messages 数组
     * 的下标比 instructions 下标偏移。图片挂在第 2 条 user（下标 4）上，序列化后它变成下标 5。
     *
     * <p>这就是本类在真实会话里「模型收不到图」的根因：注册按 instructions 下标、消费按
     * 序列化下标，两者错位后 key 永远对不上，图片静默丢失。
     */
    private static byte[] toolHistoryBody(String userContent) {
        return ("{\"model\":\"deepseek-v4-flash-vision-exp\",\"messages\":["
                + "{\"role\":\"system\",\"content\":\"sys\"},"
                + "{\"role\":\"user\",\"content\":\"原始问题\"},"
                + "{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"f\",\"arguments\":\"{}\"}}]},"
                + "{\"role\":\"tool\",\"tool_call_id\":\"c1\",\"content\":\"r1\"},"
                + "{\"role\":\"tool\",\"tool_call_id\":\"c1\",\"content\":\"r2\"},"
                + "{\"role\":\"user\",\"content\":\"" + userContent + "\"}]}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static DeepSeekVisionMediaRegistry withInline(int msgIdx, int mediaIdx) {
        DeepSeekVisionMediaRegistry r = new DeepSeekVisionMediaRegistry();
        r.put(msgIdx, mediaIdx, DeepSeekVisionMediaRegistry.Entry.inline(PNG, "image/png"));
        return r;
    }

    @Test
    void userContent_becomesArray_withTextBlockAndImageBlock() throws Exception {
        DeepSeekVisionMediaRegistry r = withInline(0, 0);   // userBody 只有一条 user，序号 0
        byte[] out = DeepSeekThinkingBodyCodec.decorate(
                userBody("这张图是什么"), ThinkingConfig.defaults(), r);
        JsonNode root = MAPPER.readTree(out);
        JsonNode content = root.path("messages").get(1).path("content");
        assertTrue(content.isArray(), "命中注册表的 user 消息 content 应改写为数组");
        assertEquals(2, content.size());
        assertEquals("text", content.get(0).path("type").asText());
        assertEquals("这张图是什么", content.get(0).path("text").asText(), "文本块必须逐字保留");
        assertEquals("image_url", content.get(1).path("type").asText());
        assertEquals("data:image/png;base64," + B64,
                content.get(1).path("image_url").path("url").asText(), "必须是带 MIME 前缀的 data URI");
    }

    @Test
    void fileEntry_writesFileBlock() throws Exception {
        DeepSeekVisionMediaRegistry r = new DeepSeekVisionMediaRegistry();
        r.put(0, 0, DeepSeekVisionMediaRegistry.Entry.file("file-api-abc"));
        byte[] out = DeepSeekThinkingBodyCodec.decorate(
                userBody("看图"), ThinkingConfig.defaults(), r);
        JsonNode content = MAPPER.readTree(out).path("messages").get(1).path("content");
        assertEquals("file", content.get(1).path("type").asText());
        assertEquals("file-api-abc", content.get(1).path("file_id").asText());
    }

    @Test
    void noHit_returnsBodyUnchanged() {
        byte[] body = userBody("没有图");
        assertArrayEquals(body, DeepSeekThinkingBodyCodec.decorate(body, ThinkingConfig.defaults(),
                new DeepSeekVisionMediaRegistry()), "无注册命中必须逐字节不变");
    }

    @Test
    void nullRegistry_returnsBodyUnchanged() {
        byte[] body = userBody("没有图");
        assertArrayEquals(body, DeepSeekThinkingBodyCodec.decorate(body, ThinkingConfig.defaults(), null));
    }

    @Test
    void nonUserMessages_untouched() throws Exception {
        // key(0,0) 命中第一条 user（下标 1）并改写它；system（下标 0）必须原样保留。
        DeepSeekVisionMediaRegistry r = withInline(0, 0);
        byte[] out = DeepSeekThinkingBodyCodec.decorate(userBody("看图"), ThinkingConfig.defaults(), r);
        JsonNode sys = MAPPER.readTree(out).path("messages").get(0);
        assertTrue(sys.path("content").isTextual(), "system 消息不得被改写");
        assertEquals("sys", sys.path("content").asText());
        JsonNode user = MAPPER.readTree(out).path("messages").get(1);
        assertTrue(user.path("content").isArray(), "key 命中第一条 user，它应被改写为数组");
        assertTrue(r.isEmpty() || r.take(DeepSeekVisionMediaRegistry.key(0, 0)) != null,
                "未被消费的 key 可残留（由请求末清理），不得被误写进 system");
    }

    @Test
    void multipleImages_sequentialOrder() throws Exception {
        DeepSeekVisionMediaRegistry r = new DeepSeekVisionMediaRegistry();
        r.put(0, 0, DeepSeekVisionMediaRegistry.Entry.inline(PNG, "image/png"));
        r.put(0, 1, DeepSeekVisionMediaRegistry.Entry.file("file-api-x"));
        byte[] out = DeepSeekThinkingBodyCodec.decorate(userBody("两张"), ThinkingConfig.defaults(), r);
        JsonNode content = MAPPER.readTree(out).path("messages").get(1).path("content");
        assertEquals(3, content.size(), "text + 2 图");
        assertEquals("image_url", content.get(1).path("type").asText());
        assertEquals("file", content.get(2).path("type").asText());
    }

    @Test
    void streaming_decoratesAndStillInjectsUsage() throws Exception {
        DeepSeekVisionMediaRegistry r = withInline(0, 0);
        byte[] out = DeepSeekThinkingBodyCodec.decorateStreaming(
                userBody("看图"), ThinkingConfig.defaults(), r);
        JsonNode root = MAPPER.readTree(out);
        assertEquals(true, root.path("stream_options").path("include_usage").asBoolean(),
                "流式必须仍注入 stream_options.include_usage");
        assertEquals("image_url", root.path("messages").get(1).path("content").get(1).path("type").asText());
    }

    @Test
    void thinkingAndVision_compose() throws Exception {
        DeepSeekVisionMediaRegistry r = withInline(0, 0);
        byte[] out = DeepSeekThinkingBodyCodec.decorate(
                userBody("看图"), ThinkingConfig.enabledEffort("max"), r);
        JsonNode root = MAPPER.readTree(out);
        assertEquals("enabled", root.path("thinking").path("type").asText());
        assertEquals("image_url", root.path("messages").get(1).path("content").get(1).path("type").asText());
    }

    @Test
    void malformedBody_throws() {
        byte[] bad = "{not-json".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> DeepSeekThinkingBodyCodec.decorate(bad, ThinkingConfig.defaults(), withInline(1, 0)));
    }

    /**
     * ★ 回归：带图 user 之前有 tool 历史（序列化 flatMap 展开后下标偏移）时，图片必须仍被注入。
     *
     * <p>旧实现里注册表 key 用「instructions 下标」，而 decorateVision 用「序列化后 messages
     * 下标」消费——工具历史把两条 tool 响应展开成两条消息后，带图 user 从下标 4 变成下标 5，
     * key 错位 → 图片静默丢失（fail-open，不报错）。这正是会话里「模型收不到图」的直接根因。
     *
     * <p>修复后按「user 消息序号」对齐：图中 user 是第 1 条 user（0-based），key 应为 (1, 0)。
     */
    @Test
    void toolHistory_expandedMessages_mustNotBreakImageInjection() throws Exception {
        DeepSeekVisionMediaRegistry r = withInline(1, 0);   // 第 1 条 user（0-based）挂图
        byte[] out = DeepSeekThinkingBodyCodec.decorate(
                toolHistoryBody("看这张图"), ThinkingConfig.defaults(), r);
        JsonNode root = MAPPER.readTree(out);
        JsonNode last = root.path("messages").get(5);       // 展开后带图 user 的下标
        assertEquals("user", last.path("role").asText(), "第 5 条应是带图的 user 消息");
        JsonNode content = last.path("content");
        assertTrue(content.isArray(), "带图 user 的 content 应改写为数组——图片必须被注入");
        assertEquals("image_url", content.get(1).path("type").asText(),
                "工具历史展开后图片仍应到达模型（旧实现此处静默丢失）");
    }

    /** 多张图 + tool 历史：第 1 条 user 挂两张图，都应注入到展开后的那条 user 上。 */
    @Test
    void toolHistory_multipleImages_stillInjectInOrder() throws Exception {
        DeepSeekVisionMediaRegistry r = new DeepSeekVisionMediaRegistry();
        r.put(1, 0, DeepSeekVisionMediaRegistry.Entry.inline(PNG, "image/png"));
        r.put(1, 1, DeepSeekVisionMediaRegistry.Entry.file("file-api-y"));
        byte[] out = DeepSeekThinkingBodyCodec.decorate(
                toolHistoryBody("两张"), ThinkingConfig.defaults(), r);
        JsonNode content = MAPPER.readTree(out).path("messages").get(5).path("content");
        assertEquals(3, content.size(), "text + 2 图");
        assertEquals("image_url", content.get(1).path("type").asText());
        assertEquals("file", content.get(2).path("type").asText());
    }
}

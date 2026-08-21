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

    private static DeepSeekVisionMediaRegistry withInline(int msgIdx, int mediaIdx) {
        DeepSeekVisionMediaRegistry r = new DeepSeekVisionMediaRegistry();
        r.put(msgIdx, mediaIdx, DeepSeekVisionMediaRegistry.Entry.inline(PNG, "image/png"));
        return r;
    }

    @Test
    void userContent_becomesArray_withTextBlockAndImageBlock() throws Exception {
        DeepSeekVisionMediaRegistry r = withInline(1, 0);
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
        r.put(1, 0, DeepSeekVisionMediaRegistry.Entry.file("file-api-abc"));
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
        DeepSeekVisionMediaRegistry r = withInline(0, 0);   // 序号 0 是 system，不该被改写
        byte[] out = DeepSeekThinkingBodyCodec.decorate(userBody("看图"), ThinkingConfig.defaults(), r);
        JsonNode sys = MAPPER.readTree(out).path("messages").get(0);
        assertTrue(sys.path("content").isTextual(), "system 消息不得被改写");
        assertEquals("sys", sys.path("content").asText());
        assertTrue(r.isEmpty() || r.take(DeepSeekVisionMediaRegistry.key(0, 0)) != null,
                "未被消费的 key 可残留（由请求末清理），不得被误写进 system");
    }

    @Test
    void multipleImages_sequentialOrder() throws Exception {
        DeepSeekVisionMediaRegistry r = new DeepSeekVisionMediaRegistry();
        r.put(1, 0, DeepSeekVisionMediaRegistry.Entry.inline(PNG, "image/png"));
        r.put(1, 1, DeepSeekVisionMediaRegistry.Entry.file("file-api-x"));
        byte[] out = DeepSeekThinkingBodyCodec.decorate(userBody("两张"), ThinkingConfig.defaults(), r);
        JsonNode content = MAPPER.readTree(out).path("messages").get(1).path("content");
        assertEquals(3, content.size(), "text + 2 图");
        assertEquals("image_url", content.get(1).path("type").asText());
        assertEquals("file", content.get(2).path("type").asText());
    }

    @Test
    void streaming_decoratesAndStillInjectsUsage() throws Exception {
        DeepSeekVisionMediaRegistry r = withInline(1, 0);
        byte[] out = DeepSeekThinkingBodyCodec.decorateStreaming(
                userBody("看图"), ThinkingConfig.defaults(), r);
        JsonNode root = MAPPER.readTree(out);
        assertEquals(true, root.path("stream_options").path("include_usage").asBoolean(),
                "流式必须仍注入 stream_options.include_usage");
        assertEquals("image_url", root.path("messages").get(1).path("content").get(1).path("type").asText());
    }

    @Test
    void thinkingAndVision_compose() throws Exception {
        DeepSeekVisionMediaRegistry r = withInline(1, 0);
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
}

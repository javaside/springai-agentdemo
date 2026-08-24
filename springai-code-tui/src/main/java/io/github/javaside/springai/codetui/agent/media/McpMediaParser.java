// McpMediaParser.java
package io.github.javaside.springai.codetui.agent.media;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** 解析 MCP 工具结果串（内容块 List 的 JSON 顶层数组）。
 *  优先靠 type 判别符（image/audio/resource-blob = 媒体）；但真实 server（如
 *  {@code @modelcontextprotocol/server-filesystem} 的 read_media_file）返回的块<b>不带 type</b>，
 *  形如 {@code {"data":"<base64>","mimeType":"image/png"}}——对无 type 块用严格
 *  「string data + 非空 mimeType」兜底认作媒体。带 type 时仍以 type 为准（type=text 即便
 *  碰巧含 data/mimeType 也不当媒体），避免误判。 */
public final class McpMediaParser {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 一个媒体块的原始字节 + 声明 MIME。 */
    public record MediaBlock(byte[] bytes, String declaredMimeType) {}

    public record Parsed(boolean isMcpArray, List<MediaBlock> mediaBlocks, List<String> textBlocks) {}

    private static final Parsed NOT_ARRAY = new Parsed(false, List.of(), List.of());

    private McpMediaParser() {}

    public static Parsed parse(String result) {
        if (result == null || result.isBlank()) return NOT_ARRAY;
        String t = result.stripLeading();
        if (!t.startsWith("[")) return NOT_ARRAY;   // 顶层数组才可能是 MCP 内容块
        JsonNode root;
        try {
            root = MAPPER.readTree(result);
        } catch (RuntimeException e) {
            return NOT_ARRAY;   // 畸形 JSON：不误判、不崩
        }
        if (!root.isArray()) return NOT_ARRAY;

        List<MediaBlock> media = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (JsonNode block : root) {
            if (!block.isObject()) continue;              // 标量元素（[1,2,3]）跳过
            JsonNode typeNode = block.get("type");
            String type = typeNode != null ? typeNode.asString() : null;
            if (type == null) {
                // 无 type：真实 read_media_file 形态。严格兜底——必须 string data + 非空 mimeType 才当媒体。
                JsonNode data = block.get("data");
                String declared = mime(block);
                if (data != null && data.isString() && declared != null && !declared.isBlank()) {
                    addMedia(media, data, declared);
                }
            } else if ("text".equals(type)) {
                JsonNode txt = block.get("text");
                if (txt != null) texts.add(txt.asString());
            } else if ("image".equals(type) || "audio".equals(type)) {
                addMedia(media, block.get("data"), mime(block));
            } else if ("resource".equals(type)) {
                JsonNode res = block.get("resource");
                if (res != null && res.isObject()) addMedia(media, res.get("blob"), mime(res));
            }
            // 已知但非媒体的 type（如 text 已处理）/ 其它未知 type：不当媒体
        }
        return new Parsed(true, media, texts);
    }

    private static void addMedia(List<MediaBlock> out, JsonNode dataNode, String declaredMime) {
        if (dataNode == null || !dataNode.isString()) return;
        try {
            byte[] bytes = Base64.getDecoder().decode(dataNode.asString());
            out.add(new MediaBlock(bytes, declaredMime));
        } catch (IllegalArgumentException e) {
            // 单块 base64 解码失败：只丢该块，不影响其它 text/块
        }
    }

    /** 容驼峰 mimeType 与蛇形 mime_type（后者作他家 server 兼容）。 */
    private static String mime(JsonNode block) {
        JsonNode m = block.get("mimeType");
        if (m == null) m = block.get("mime_type");
        return m != null ? m.asString() : null;
    }
}

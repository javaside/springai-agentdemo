// McpMediaParserTest.java
package io.github.javaside.springai.codetui.agent.media;

import org.junit.jupiter.api.Test;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class McpMediaParserTest {
    private static String pngB64() {
        byte[] b = new byte[]{(byte)0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A};
        return Base64.getEncoder().encodeToString(b);
    }

    @Test
    void parsesTopLevelArray_imageBlockDetected_textKept() {
        String result = "[{\"type\":\"text\",\"text\":\"Took a screenshot\"},"
                + "{\"type\":\"image\",\"data\":\"" + pngB64() + "\",\"mimeType\":\"image/png\"}]";
        McpMediaParser.Parsed p = McpMediaParser.parse(result);
        assertTrue(p.isMcpArray());
        assertEquals(1, p.mediaBlocks().size());
        assertEquals("image/png", p.mediaBlocks().get(0).declaredMimeType());
        assertArrayEquals(new byte[]{(byte)0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A},
                p.mediaBlocks().get(0).bytes());
        assertEquals("Took a screenshot", p.textBlocks().get(0));
    }

    @Test
    void plainText_notMcpArray() {
        assertFalse(McpMediaParser.parse("just a normal string").isMcpArray());
    }

    @Test
    void jsonArrayOfScalars_notMisdetected() {
        McpMediaParser.Parsed p = McpMediaParser.parse("[1,2,3]");
        assertTrue(p.isMcpArray());          // 是数组
        assertTrue(p.mediaBlocks().isEmpty()); // 但无媒体块
    }

    @Test
    void incidentalDataMimeType_butTypeNotImage_notMedia() {
        // 一个恰好有 data/mimeType 字段但 type=text 的块 → 不误判为媒体
        String result = "[{\"type\":\"text\",\"text\":\"x\",\"data\":\"aaa\",\"mimeType\":\"image/png\"}]";
        assertTrue(McpMediaParser.parse(result).mediaBlocks().isEmpty());
    }

    /** 真实 @modelcontextprotocol/server-filesystem read_media_file 的线格式：无 type，只有 data+mimeType。
     *  这是线上真实抓到的形态（session 20260713T060836 里 54788 字符的 PNG）。修此前 mediaBlocks=0，字节漏进模型。 */
    @Test
    void realReadMediaFile_noTypeField_imageDetected() {
        String result = "[{\"data\":\"" + pngB64() + "\",\"mimeType\":\"image/png\"}]";
        McpMediaParser.Parsed p = McpMediaParser.parse(result);
        assertTrue(p.isMcpArray());
        assertEquals(1, p.mediaBlocks().size(), "无 type 的 data+mimeType 块必须被认作媒体");
        assertEquals("image/png", p.mediaBlocks().get(0).declaredMimeType());
        assertArrayEquals(new byte[]{(byte)0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A},
                p.mediaBlocks().get(0).bytes());
    }

    @Test
    void noType_missingMimeType_notMedia() {
        // 无 type 且缺 mimeType（或空）→ 严格兜底不认作媒体，避免误吞普通 data 块
        assertTrue(McpMediaParser.parse("[{\"data\":\"" + pngB64() + "\"}]").mediaBlocks().isEmpty());
        assertTrue(McpMediaParser.parse("[{\"data\":\"" + pngB64() + "\",\"mimeType\":\"\"}]")
                .mediaBlocks().isEmpty());
    }

    @Test
    void noType_dataNotString_notMedia() {
        // 无 type 且 data 非字符串（如数字）→ 不认作媒体
        assertTrue(McpMediaParser.parse("[{\"data\":123,\"mimeType\":\"image/png\"}]")
                .mediaBlocks().isEmpty());
    }
}

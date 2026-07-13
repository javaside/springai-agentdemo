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
}

package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CodingAgent#unwrapToolText} 单测：手动 /skill 注入前，把 Skill 工具（经 Spring AI 序列化后的）
 * JSON 字符串结果解回原始多行文本，避免字面 {@code \n} 与引号落进会话、在 {@code -c} 回放里显示成一行长条。
 */
class CodingAgentSkillUnwrapTest {

    @Test
    void decodesJsonStringLiteralBackToRawMultilineText() {
        // Spring AI 对返回 String 的工具序列化后的形态：首尾引号 + \n 被转义成两字符
        String serialized = "\"Base directory: /skills/x\\n\\n# Title\\n\\nBody line\"";
        String out = CodingAgent.unwrapToolText(serialized);

        assertEquals("Base directory: /skills/x\n\n# Title\n\nBody line", out);
        assertTrue(out.contains("\n"), "应含真实换行符");
        assertFalse(out.contains("\\n"), "不应再有字面 \\n 两字符");
        assertFalse(out.startsWith("\""), "不应保留 JSON 引号");
    }

    @Test
    void leavesPlainTextUntouched() {
        String plain = "already raw\ntext";      // 非 JSON 字符串字面量（无首尾引号）
        assertEquals(plain, CodingAgent.unwrapToolText(plain));
    }

    @Test
    void degradesGracefullyOnMalformedJson() {
        String broken = "\"unterminated";        // 有前引号但非合法 JSON 字符串 → 原样返回，不抛
        assertEquals(broken, CodingAgent.unwrapToolText(broken));
    }

    @Test
    void handlesNullAndTooShort() {
        assertNull(CodingAgent.unwrapToolText(null));
        assertEquals("\"", CodingAgent.unwrapToolText("\""));   // 长度 <2，原样
    }
}

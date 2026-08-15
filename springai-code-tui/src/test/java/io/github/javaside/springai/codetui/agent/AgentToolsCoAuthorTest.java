package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提交署名指引：默认关闭（未配 {@code CODETUI_CO_AUTHOR} 时不注入任何指引），
 * 显式配置后才渲染尾注指引，且签名须满足 GitHub co-author 的「名字 &lt;邮箱&gt;」格式。
 */
class AgentToolsCoAuthorTest {

    @Test
    void disabledByDefaultWhenEnvMissingOrBlank() {
        assertEquals("", AgentTools.coAuthorGuide(null),
                "未配置 CODETUI_CO_AUTHOR 时应默认关闭，不注入任何署名指引");
        assertEquals("", AgentTools.coAuthorGuide("   "),
                "空白值同样视为未配置，默认关闭");
    }

    @Test
    void enabledWhenExplicitlyConfigured() {
        String guide = AgentTools.coAuthorGuide("  CodeTui <noreply@codetui.dev>  ");

        assertTrue(guide.contains("Co-Authored-By:"), "启用后应给出尾注规则，实际=" + guide);
        assertTrue(guide.contains("CodeTui <noreply@codetui.dev>"),
                "签名应被内联进尾注，实际=" + guide);
    }

    /** 指引作为 param 值注入，正文不得含花括号，否则 StringTemplate 会炸掉整个系统提示渲染。 */
    @Test
    void guideContainsNoTemplateBraces() {
        String guide = AgentTools.coAuthorGuide("CodeTui <noreply@codetui.dev>");

        assertFalse(guide.contains("{") || guide.contains("}"),
                "署名指引正文不得含花括号，实际=" + guide);
    }
}

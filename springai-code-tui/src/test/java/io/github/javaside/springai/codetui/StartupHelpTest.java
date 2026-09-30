package io.github.javaside.springai.codetui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动参数 {@code -h} / {@code --help}：识别纪律与帮助文本覆盖面。
 *
 * <p><b>为什么本类在 {@code codetui} 包</b>：{@code CodeTuiApplication.hasHelpFlag} /
 * {@code usageText} 是包私有的（与 {@code PermissionStartupTest} 测 {@code hasBypassFlag}
 * 同一理由——不值得为了测试把入口类的私有件改 public）。
 *
 * <p><b>usageCoversAllFlags 是「帮助不撒谎」的契约</b>：用户照着 {@code -h} 输出敲参数，
 * 敲了不存在的就是我们的错。今后新增启动参数时，本测试与 {@code usageText()} 必须同步更新——
 * 这条测试就是防「加了参数忘了写进帮助」的那道闸。
 */
class StartupHelpTest {

    @Test
    @DisplayName("-h / --help 被识别（含与其他参数混用时）")
    void recognisesHelpFlag() {
        assertTrue(CodeTuiApplication.hasHelpFlag(new String[]{"-h"}));
        assertTrue(CodeTuiApplication.hasHelpFlag(new String[]{"--help"}));
        assertTrue(CodeTuiApplication.hasHelpFlag(new String[]{"-c", "--help"}));
        assertTrue(CodeTuiApplication.hasHelpFlag(new String[]{"--permission-mode", "plan", "-h"}));
    }

    @Test
    @DisplayName("前缀相近的参数不得误判（与 hasBypassFlag 同纪律）")
    void doesNotMatchPrefixSimilar() {
        assertFalse(CodeTuiApplication.hasHelpFlag(new String[]{}));
        assertFalse(CodeTuiApplication.hasHelpFlag(new String[]{"-help"}),
                "单横线长写法不是本参数，不得误判");
        assertFalse(CodeTuiApplication.hasHelpFlag(new String[]{"--h"}),
                "双横线短写法不是本参数，不得误判");
        assertFalse(CodeTuiApplication.hasHelpFlag(new String[]{"--helpx"}));
        assertFalse(CodeTuiApplication.hasHelpFlag(new String[]{"-hc"}));
        assertFalse(CodeTuiApplication.hasHelpFlag(new String[]{"-c"}),
                "续跑参数与帮助无关");
    }

    @Test
    @DisplayName("帮助文本覆盖全部现有启动参数（帮助不撒谎契约）")
    void usageCoversAllFlags() {
        String usage = CodeTuiApplication.usageText();
        for (String flag : new String[]{"-c", "--continue", "--permission-mode",
                "--dangerously-skip-permissions", "--force-fullredraw", "--band-off",
                "--preview-off", "--insert-lines", "-h", "--help"}) {
            assertTrue(usage.contains(flag), "帮助文本应包含 " + flag);
        }
        for (String mode : new String[]{"default", "acceptEdits", "plan"}) {
            assertTrue(usage.contains(mode), "帮助文本应说明 --permission-mode 的合法取值 " + mode);
        }
    }

    @Test
    @DisplayName("渲染诊断启动参数：精确匹配落 property，前缀相近不得误判")
    void renderDiagFlagsExactMatchOnly() {
        java.util.Properties clean = (java.util.Properties) System.getProperties().clone();
        try {
            CodeTuiApplication.applyRenderDiagFlags(new String[]{"--force-fullredraw", "--band-off"});
            assertEquals("1", System.getProperty("CODETUI_FORCE_FULLREDRAW"));
            assertEquals("1", System.getProperty("CODETUI_BAND_OFF"));
            assertNull(System.getProperty("CODETUI_PREVIEW_OFF"));

            CodeTuiApplication.applyRenderDiagFlags(new String[]{"--force-redraw", "--previewoff", "--band"});
            assertNull(System.getProperty("CODETUI_PREVIEW_OFF"), "前缀拼法不得误判");

            CodeTuiApplication.applyRenderDiagFlags(new String[]{"--preview-off"});
            assertEquals("1", System.getProperty("CODETUI_PREVIEW_OFF"));
        } finally {
            System.setProperties(clean);
        }
    }
}

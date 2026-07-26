package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ${VAR} 插值：解析器可注入，故测试不依赖真实环境变量。 */
class EnvInterpolatorTest {

    private static final Map<String, String> ENV = Map.of("TOKEN", "abc123", "USER", "zxh");

    @Test
    void replacesSingleVariable() {
        assertEquals("Bearer abc123",
                EnvInterpolator.interpolate("Bearer ${TOKEN}", ENV::get));
    }

    @Test
    void replacesMultipleVariablesInOneValue() {
        assertEquals("zxh:abc123",
                EnvInterpolator.interpolate("${USER}:${TOKEN}", ENV::get));
    }

    @Test
    void passesThroughWhenNoPlaceholder() {
        assertEquals("Bearer literal-token",
                EnvInterpolator.interpolate("Bearer literal-token", ENV::get),
                "不含 ${} 的字面值必须原样可用");
    }

    @Test
    void nullInputStaysNull() {
        assertNull(EnvInterpolator.interpolate(null, ENV::get));
    }

    @Test
    void undefinedVariableThrowsWithItsName() {
        EnvInterpolator.UndefinedVariableException ex = assertThrows(
                EnvInterpolator.UndefinedVariableException.class,
                () -> EnvInterpolator.interpolate("Bearer ${NOPE}", ENV::get));

        assertEquals("NOPE", ex.variable(), "异常要带变量名，调用方才能拼出可定位的 WARN");
        assertTrue(ex.getMessage().contains("NOPE"), "消息里也应含变量名，实际=" + ex.getMessage());
    }

    /** 替换值里若含 $ 或 \ ，正则替换会把它们当转义符——必须已被 quoteReplacement 处理。 */
    @Test
    void replacementValueWithDollarOrBackslashIsLiteral() {
        Map<String, String> tricky = Map.of("T", "a$b\\c");

        assertEquals("x=a$b\\c", EnvInterpolator.interpolate("x=${T}", tricky::get));
    }

    /** ${} 形状不合法（无变量名）时不匹配，原样保留，不抛。 */
    @Test
    void malformedPlaceholderIsLeftAlone() {
        assertEquals("${}", EnvInterpolator.interpolate("${}", ENV::get));
    }
}

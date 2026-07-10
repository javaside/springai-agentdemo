package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** LlmTimeouts 配置解析：默认/非法/钳制边界。纯函数，注入取值函数避免依赖真实环境变量。 */
class LlmTimeoutsTest {

    @Test
    void default_whenUnset_is300sRead_and30sConnect() {
        LlmTimeouts t = LlmTimeouts.from(name -> null);   // 环境变量缺失
        assertEquals(Duration.ofSeconds(300), t.readTimeout());
        assertEquals(Duration.ofSeconds(30), t.connectTimeout());
    }

    @Test
    void blankOrGarbage_fallsBackTo300() {
        assertEquals(Duration.ofSeconds(300), LlmTimeouts.from(n -> "  ").readTimeout());
        assertEquals(Duration.ofSeconds(300), LlmTimeouts.from(n -> "abc").readTimeout());
    }

    @Test
    void validValue_isUsed() {
        assertEquals(Duration.ofSeconds(90), LlmTimeouts.from(n -> "90").readTimeout());
    }

    @Test
    void clampsToRange_10_to_3600() {
        assertEquals(Duration.ofSeconds(10), LlmTimeouts.from(n -> "1").readTimeout());     // 下限
        assertEquals(Duration.ofSeconds(3600), LlmTimeouts.from(n -> "99999").readTimeout()); // 上限
    }

    @Test
    void connectTimeout_isAlwaysFixed30s_regardlessOfRead() {
        assertEquals(Duration.ofSeconds(30), LlmTimeouts.from(n -> "1200").connectTimeout());
    }
}

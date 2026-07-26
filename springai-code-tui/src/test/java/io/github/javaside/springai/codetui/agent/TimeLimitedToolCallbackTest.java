package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 超时装饰器：给没有自带超时的工具兜一层总超时。 */
class TimeLimitedToolCallbackTest {

    /** 可配置耗时与抛错行为的假工具。 */
    private static final class FakeTool implements ToolCallback {
        private final long sleepMillis;
        private final RuntimeException toThrow;

        FakeTool(long sleepMillis, RuntimeException toThrow) {
            this.sleepMillis = sleepMillis;
            this.toThrow = toThrow;
        }

        @Override public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name("SlowTool").description("d").inputSchema("{}").build();
        }

        @Override public String call(String toolInput) {
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", e);
            }
            if (toThrow != null) {
                throw toThrow;
            }
            return "fake-result";
        }

        @Override public String call(String toolInput, ToolContext toolContext) {
            return call(toolInput);
        }
    }

    @Test
    void fastCallReturnsNormally() {
        ToolCallback limited = new TimeLimitedToolCallback(
                new FakeTool(0, null), Duration.ofSeconds(5));

        assertEquals("fake-result", limited.call("{}"), "未超时应原样返回委托结果");
    }

    @Test
    void slowCallThrowsReadableTimeout() {
        ToolCallback limited = new TimeLimitedToolCallback(
                new FakeTool(2000, null), Duration.ofMillis(100));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> limited.call("{}"));

        assertTrue(ex.getMessage().contains("SlowTool"), "错误消息应点名是哪个工具超时，实际=" + ex.getMessage());
        assertTrue(ex.getMessage().contains("超时"), "应是可读的超时提示，实际=" + ex.getMessage());
    }

    @Test
    void delegateExceptionIsRethrownUnchanged() {
        IllegalArgumentException original = new IllegalArgumentException("委托自己的错");
        ToolCallback limited = new TimeLimitedToolCallback(
                new FakeTool(0, original), Duration.ofSeconds(5));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> limited.call("{}"));

        assertEquals("委托自己的错", ex.getMessage(),
                "委托抛的异常应原样传播，不能被包装成超时或执行失败——否则错误定位信息丢失");
    }

    @Test
    void definitionPassesThroughUnchanged() {
        ToolCallback limited = new TimeLimitedToolCallback(
                new FakeTool(0, null), Duration.ofSeconds(5));

        assertEquals("SlowTool", limited.getToolDefinition().name(), "本装饰器不改名，只管超时");
    }
}

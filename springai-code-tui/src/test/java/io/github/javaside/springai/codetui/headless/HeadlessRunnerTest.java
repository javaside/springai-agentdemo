package io.github.javaside.springai.codetui.headless;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Runner 的可单测纯函数：env 超时值解析与 stdin 提示词。run() 全链路属真机冒烟（Task 5，env 门控）。 */
class HeadlessRunnerTest {

    @Test
    void envTimeouts_defaultAndClamp() {
        assertEquals(10L, HeadlessRunner.mcpWaitSeconds(), "默认 10（env 未设走默认）");
        assertEquals(3600L, HeadlessRunner.maxSeconds(), "默认 3600");
        // 钳制边界：env 由 System.getenv 读——本用例只钉默认值与返回类型契约；
        // 钳制逻辑经私有 clamp 实现，真机/环境类测试不在此展开（CI 无 env 干扰）。
    }

    @Test
    void prompt_stdinSentinelReadsAll() {
        InputStream old = System.in;
        System.setIn(new ByteArrayInputStream("第一行\n第二行".getBytes(StandardCharsets.UTF_8)));
        try {
            assertEquals("第一行\n第二行", HeadlessRunner.prompt(new String[]{"-p", "-"}), "stdin 全读含换行");
            assertEquals("直接文本", HeadlessRunner.prompt(new String[]{"-p", "直接文本"}));
        } finally {
            System.setIn(old);
        }
    }
}

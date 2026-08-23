package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 注：本模块测试栈只有 JUnit 5（AssertJ 未在 classpath），故用 org.junit.jupiter.api.Assertions
// 逐条对应原 AssertJ 断言的语义（contains / doesNotContain / isNotBlank / isGreaterThan）。
class MemoryPromptTest {

    @Test
    void render_injectsRootPath_andPreservesDoubleBraceExamples() {
        String out = MemoryPrompt.render("/tmp/proj/.codetui/memory");

        // 1) 单花括号占位符被真实路径替换（注意库拼写为 DIERCTORY）
        assertTrue(out.contains("/tmp/proj/.codetui/memory"),
                "路径应被注入");
        assertFalse(out.contains("{MEMORIES_ROOT_DIERCTORY}"),
                "占位符应被替换掉");

        // 2) 双花括号示例块原样保留（不被当模板解析/剥离）——这是注入值、非模板
        assertTrue(out.contains("{{memory name}}"),
                "双花括号示例块应原样保留");

        // 3) 关键内容存在（确认读到的是真 prompt，非空/占位）
        assertTrue(out.contains("AutoMemoryTools"), "应读到真 prompt 内容");
        assertTrue(out.contains("MEMORY.md"), "应读到真 prompt 内容");
    }

    @Test
    void render_loadsProjectOverride_notUpstreamJarVersion() {
        // classpath 顺序下，本项目 resources 里的精简版应覆盖 spring-ai-agent-utils jar 里的上游版。
        // 若这个断言挂了，说明覆盖失效、静默回退到了上游 10.9KB 原版（token 白花）。
        String out = MemoryPrompt.render("/x/memory");
        assertTrue(out.contains("code-tui concise version"),
                "应加载项目精简版（其头部注释有该标记），实际长度=" + out.length());
        // 精简版应显著小于上游 10.9KB 原版。
        assertTrue(out.length() < 8000,
                "精简版应远小于上游原版，实际长度=" + out.length());
    }

    @Test
    void render_nonBlank_andReasonablySized() {
        String out = MemoryPrompt.render("/x/memory");
        assertFalse(out.isBlank(), "渲染结果不应为空白");
        // 精简版约 4.6KB：完整保留格式契约，但远小于上游 10.9KB 原版。
        assertTrue(out.length() > 3000, "精简版 ~4.6KB，长度应 > 3000");
    }

    @Test
    void render_rejectsNullDir() {
        assertThrows(NullPointerException.class, () -> MemoryPrompt.render(null));
    }
}

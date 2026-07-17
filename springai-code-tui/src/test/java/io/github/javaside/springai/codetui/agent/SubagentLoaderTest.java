package io.github.javaside.springai.codetui.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubagentLoaderTest {

    @Test
    void parsesFrontmatterAndBody() {
        SubagentSpec s = SubagentLoader.parse("classpath:/agents/sample.md");
        assertEquals("sample", s.name());
        assertEquals("a sample agent for testing", s.description());
        assertEquals(List.of("read", "grep", "glob"), s.allowTools());
        assertEquals(List.of("shell"), s.denyTools());
        assertNull(s.model());                      // model: 空 → null
        assertEquals(List.of("git-commit"), s.skills());
        assertTrue(s.systemPrompt().startsWith("You are a sample specialist"));
    }

    @Test
    void loadsBuiltinFourFromClasspath() {
        Map<String, SubagentSpec> all = SubagentLoader.loadBuiltins();
        assertEquals(4, all.size());
        assertTrue(all.containsKey("general-purpose"));
        assertTrue(all.containsKey("explore"));
        assertTrue(all.containsKey("plan"));
        assertTrue(all.containsKey("bash"));
    }
}

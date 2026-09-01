package io.github.javaside.springai.codetui.agent.compaction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 智谱视觉模型的内置窗口（2026-09-01 官方文档核实）：flash 1M / 4.6v 128K / 4.5v 64K。 */
class ModelContextWindowsTest {

    private final ModelContextWindows windows =
            ModelContextWindows.parse(null, ModelContextWindows.DEFAULT_UNKNOWN_WINDOW);

    @Test
    void zhipuVisionWindowsAreBuiltIn() {
        assertEquals(1_000_000L, windows.resolve("zhipu", "glm-5.3-flash"));
        assertEquals(128_000L, windows.resolve("zhipu", "glm-4.6v"));
        assertEquals(64_000L, windows.resolve("zhipu", "glm-4.5v"));
    }

    @Test
    void unverifiedZhipuVisionModelsFallBackConservatively() {
        // glm-4v / glm-4.1v 窗口未核实 → 不进表，落保守兜底 128k（可用 CODETUI_CONTEXT_WINDOWS 覆盖）。
        assertEquals(ModelContextWindows.DEFAULT_UNKNOWN_WINDOW, windows.resolve("zhipu", "glm-4v-plus"));
        assertEquals(ModelContextWindows.DEFAULT_UNKNOWN_WINDOW, windows.resolve("zhipu", "glm-4.1v-thinking"));
    }

    @Test
    void textFlagshipEntryUnaffected() {
        assertEquals(1_000_000L, windows.resolve("zhipu", "glm-5.3"), "既有条目不因新增而漂移");
    }
}
